
package com.ares.analytics.service

import com.ares.analytics.shared.AppJson

import com.ares.analytics.shared.*
import com.areslib.subsystem.SubsystemDocument
import com.areslib.controls.ControlSchemeCodec
import com.areslib.controls.ControlSchemeDocument
import com.areslib.drivetrain.DrivetrainDocument
import com.areslib.drivetrain.DrivetrainDocumentCodec
import com.google.gson.GsonBuilder
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.request.setBody
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest
import java.util.TimeZone

/**
 * Installs a newly-created immutable cloud object behind an optimistic manifest pointer.
 *
 * [swapManifest] may invoke [recordPriorObjectIds] more than once while retrying conflicts; only
 * the IDs from the final attempt are eligible for cleanup. If a manifest request has an ambiguous
 * transport failure, [currentManifestObjectId] reconciles whether the write actually committed
 * before this function decides whether the new object is safe to delete.
 */
internal suspend fun installImmutableCloudObject(
    uploadNewObject: suspend () -> String,
    swapManifest: suspend (
        newObjectId: String,
        recordPriorObjectIds: (Set<String>) -> Unit
    ) -> Unit,
    currentManifestObjectId: suspend () -> String?,
    deleteObject: suspend (String) -> Unit
): String {
    val newObjectId = uploadNewObject()
    require(newObjectId.isNotBlank()) { "Cloud upload returned a blank object ID" }

    var priorObjectIds = emptySet<String>()
    try {
        swapManifest(newObjectId) { ids ->
            priorObjectIds = ids
        }
    } catch (failure: Exception) {
        var reconciliationSucceeded = false
        val referencedObjectId = try {
            currentManifestObjectId().also { reconciliationSucceeded = true }
        } catch (_: Exception) {
            null
        }

        if (!reconciliationSucceeded || referencedObjectId != newObjectId) {
            // A successful reconciliation proving that the manifest points elsewhere makes the
            // new upload an orphan. If reconciliation itself failed, retain the object: deleting
            // a possibly-committed object would corrupt the live manifest.
            if (reconciliationSucceeded) runCatching { deleteObject(newObjectId) }
            throw failure
        }
        // The manifest write committed but its response was lost. Continue as success and retire
        // only the objects captured from that attempted manifest snapshot.
    }

    for (priorObjectId in priorObjectIds) {
        if (priorObjectId != newObjectId) runCatching { deleteObject(priorObjectId) }
    }
    return newObjectId
}

/**
 * Removes a cloud object from the authoritative manifest before retiring its immutable payload.
 *
 * A failed manifest update must leave the payload intact because readers may still reference it.
 * Once the manifest update succeeds, payload deletion is only garbage collection: surfacing that
 * cleanup failure as a failed session deletion would invite a retry that can no longer find the
 * manifest entry.
 */
internal suspend fun removeImmutableCloudObject(
    removeManifestReference: suspend () -> Unit,
    deleteObject: suspend () -> Unit,
    onCleanupFailure: (Throwable) -> Unit = {}
) {
    removeManifestReference()
    try {
        deleteObject()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        onCleanupFailure(failure)
    }
}

/**
 * Desktop-owned synchronization service for historical telemetry sessions.
 *
 * The robot remains offline-first. Analytics packages a complete session as an immutable
 * `.ares-session.zip` bundle, uploads it to the active workspace's Google Drive folder, and then
 * atomically updates the Drive-hosted session index. Content hashes provide incremental sync and
 * download-integrity checks.
 *
 * The Cloud Run gateway is used for protected diagnostics and AI requests; it is not in the
 * telemetry upload path.
 *
 * ### Thread Safety & Performance Guarantees:
 * All Google Drive and gateway requests execute asynchronously on `Dispatchers.IO`.
 *
 * @param databaseService Primary DuckDB telemetry database service.
 * @param parquetExporterService Legacy constructor dependency; Drive sync uses complete session bundles.
 * @param firebaseClientService Firebase auth service supplying bearer tokens.
 * @param environmentService Workspace environment settings provider.
 * @param teamApiService FIRST team metadata API service.
 * @param summaryEngineService Match KPI summary calculator.
 * @param googleDriveService Authoritative workspace session storage service.
 * @param gatewayUrl Base URL for the Ktor Cloud Run gateway.
 * @param httpClient Ktor HTTP client configured with JSON serialization.
 *
 * @see DatabaseService
 * @see ParquetExporterService
 * @see FirebaseClientService
 */
class SyncEngineService(
    private val databaseService: DatabaseService,
    private val parquetExporterService: ParquetExporterService,
    private val environmentService: EnvironmentService,
    private val summaryEngineService: SummaryEngineService,
    private val googleDriveService: GoogleDriveService,
    private val httpClient: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(AppJson)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30 * 60 * 1000L
            connectTimeoutMillis = 60 * 1000L
            socketTimeoutMillis = 30 * 60 * 1000L
        }
    }
) {
    private val subsystemDocumentGson = GsonBuilder().create()
    private val sessionBundleService = SessionBundleService(databaseService, environmentService)

    private fun safeFileComponent(value: String): String = value
        .map { character ->
            when {
                character.isLetterOrDigit() -> character
                character == '.' || character == '_' || character == '-' -> character
                else -> '_'
            }
        }
        .joinToString("")
        .take(80)

    private fun cloudFileName(summary: SessionSummary): String {
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val date = dateFormat.format(java.util.Date(summary.createdAt))
        val match = summary.matchNumber?.let { "_Match_$it" }.orEmpty()
        val alliance = summary.allianceColor?.takeIf(String::isNotBlank)
            ?.let { "_${safeFileComponent(it)}" }.orEmpty()
        val mode = when {
            "Auto" in summary.tags -> "Auto"
            "TeleOp" in summary.tags -> "TeleOp"
            else -> "Init"
        }
        val extension = if (summary.cloudBundleVersion >= CURRENT_SESSION_BUNDLE_VERSION) {
            ".ares-session.zip"
        } else {
            ".parquet"
        }
        return "ARES_Telemetry_${date}_${safeFileComponent(summary.robotId)}$match${alliance}_${mode}_${safeFileComponent(summary.sessionId)}$extension"
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private suspend fun createImmutableSessionObject(
        name: String,
        file: File,
        parentId: String,
        mimeType: String,
    ): String {
        var attempt = 0
        var delayMs = CLOUD_UPLOAD_RETRY_DELAY_MS
        while (true) {
            try {
                return googleDriveService.createFileStreaming(
                    name = name,
                    file = file,
                    parentId = parentId,
                    mimeType = mimeType,
                )
            } catch (failure: Exception) {
                attempt++
                if (attempt >= CLOUD_UPLOAD_ATTEMPTS) throw failure
                kotlinx.coroutines.delay(delayMs)
                delayMs *= 2
            }
        }
    }

    /**
     * Serializes index.json read-modify-write sequences so two concurrent uploads (or an
     * upload + delete) cannot interleave their read/write and clobber each other's entries.
     */
    private val indexMutex = kotlinx.coroutines.sync.Mutex()
    private val robotProfilesMutex = kotlinx.coroutines.sync.Mutex()

    private fun mergeIndexSummaries(indexes: Iterable<List<SessionSummary>>): List<SessionSummary> {
        val merged = linkedMapOf<String, SessionSummary>()
        indexes.forEach { summaries ->
            summaries.forEach { summary ->
                validateCloudSummary(summary)
                val previous = merged.putIfAbsent(summary.sessionId, summary)
                require(previous == null || previous == summary) {
                    "Cloud index contains conflicting manifests for ${summary.sessionId}"
                }
            }
        }
        return merged.values.toList()
    }

    private fun validateCloudSummary(summary: SessionSummary) {
        require(summary.sessionId.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Cloud session ID is invalid" }
        val cloudFileId = summary.cloudFileId
        require(!cloudFileId.isNullOrBlank() && cloudFileId.length <= 256) {
            "Cloud session file ID is invalid"
        }
        require(summary.cloudFileName == cloudFileName(summary)) { "Cloud session filename is not canonical" }
        require(summary.fileSizeBytes in 1L..MAX_CLOUD_SESSION_BYTES) { "Cloud session size is invalid" }
        require(summary.cloudSha256?.matches(Regex("[0-9a-f]{64}")) == true) {
            "Cloud session SHA-256 is invalid"
        }
        require(summary.cloudBundleVersion in 0..CURRENT_SESSION_BUNDLE_VERSION) {
            "Cloud session bundle version is unsupported"
        }
        if (summary.cloudBundleVersion >= CURRENT_SESSION_BUNDLE_VERSION) {
            require(summary.cloudWorkspaceKey?.matches(Regex("[A-Za-z0-9._:-]{1,320}")) == true) {
                "Cloud session workspace identity is invalid"
            }
        }
    }

    private suspend fun readIndex(fileId: String): Pair<List<SessionSummary>, String> {
        val snapshot = googleDriveService.readFileSnapshot(fileId)
        val etag = snapshot.etag
            ?: throw IllegalStateException("Google Drive index.json did not include an ETag")
        val summaries = AppJson.decodeFromString<List<SessionSummary>>(
            String(snapshot.bytes, Charsets.UTF_8)
        )
        return summaries to etag
    }

    private fun mergeRobotProfiles(profileSets: Iterable<List<RobotProfile>>): List<RobotProfile> {
        val merged = linkedMapOf<String, RobotProfile>()
        profileSets.forEach { profiles ->
            profiles.forEach { profile ->
                require(profile.robotId.isNotBlank()) { "robots.json contains a blank robot ID" }
                merged[profile.robotId] = profile
            }
        }
        return merged.values.toList()
    }

    private suspend fun readRobotProfiles(fileId: String): Pair<List<RobotProfile>, String> {
        val snapshot = googleDriveService.readFileSnapshot(fileId)
        val etag = snapshot.etag
            ?: throw IllegalStateException("Google Drive robots.json did not include an ETag")
        val profiles = AppJson.decodeFromString<List<RobotProfile>>(
            String(snapshot.bytes, Charsets.UTF_8)
        )
        return profiles to etag
    }

    /**
     * Optimistic cross-process read-modify-write for index.json. Google Drive permits duplicate
     * names, so duplicate first-run indexes are merged and collapsed into a canonical file.
     */
    private suspend fun mutateRemoteIndex(
        rootFolderId: String,
        transform: (List<SessionSummary>) -> List<SessionSummary>
    ) = indexMutex.withLock {
        repeat(INDEX_UPDATE_ATTEMPTS) { attempt ->
            val indexIds = googleDriveService.findFiles("index.json", rootFolderId)
            if (indexIds.isEmpty()) {
                val updated = transform(emptyList()).also { summaries ->
                    summaries.forEach(::validateCloudSummary)
                }
                val bytes = AppJson.encodeToString(updated).toByteArray(Charsets.UTF_8)
                googleDriveService.writeFile(
                    name = "index.json",
                    bytes = bytes,
                    parentId = rootFolderId,
                    mimeType = "application/json"
                )
                return@withLock
            }

            val snapshots = indexIds.associateWith { readIndex(it) }
            val current = mergeIndexSummaries(snapshots.values.map { it.first })
            val canonicalId = indexIds.minOrNull()!!
            val expectedEtag = snapshots.getValue(canonicalId).second
            val updated = transform(current).also { summaries ->
                summaries.forEach(::validateCloudSummary)
            }
            val updatedBytes = AppJson.encodeToString(updated).toByteArray(Charsets.UTF_8)
            try {
                googleDriveService.writeFile(
                    name = "index.json",
                    bytes = updatedBytes,
                    parentId = rootFolderId,
                    mimeType = "application/json",
                    fileId = canonicalId,
                    expectedEtag = expectedEtag
                )
                indexIds.asSequence().filter { it != canonicalId }.forEach { duplicateId ->
                    runCatching { googleDriveService.deleteFile(duplicateId) }
                }
                return@withLock
            } catch (_: DrivePreconditionFailedException) {
                if (attempt == INDEX_UPDATE_ATTEMPTS - 1) {
                    throw IllegalStateException("index.json kept changing during update")
                }
                kotlinx.coroutines.delay(INDEX_RETRY_DELAY_MS * (attempt + 1))
            }
        }
    }

    /** Reads the currently published object ID without converting read/parse failures to absence. */
    private suspend fun currentSessionObjectId(rootFolderId: String, sessionId: String): String? {
        val indexIds = googleDriveService.findFiles("index.json", rootFolderId)
        if (indexIds.isEmpty()) return null
        return mergeIndexSummaries(indexIds.map { readIndex(it).first })
            .singleOrNull { it.sessionId == sessionId }
            ?.cloudFileId
    }

    /**
     * Outcome of reading the remote `index.json`. Distinguishes a *failed* read (Drive
     * outage / corrupt JSON — must NOT be treated as "remote is empty", or a single
     * failing byte would let an upload rewrite the index with only the uploaded session)
     * from a genuinely-absent index (first run — safe to seed fresh).
     */
    private sealed class RemoteIndexState {
        data class Loaded(val summaries: List<SessionSummary>) : RemoteIndexState()
        object Absent : RemoteIndexState()
        object Failed : RemoteIndexState()
    }

    /**
     * Reads the remote index.json with failure discrimination. Folder creation failure,
     * file read failure, and JSON parse failure all yield [RemoteIndexState.Failed]; a
     * missing index.json (first run) yields [RemoteIndexState.Absent].
     */
    private suspend fun readRemoteIndexState(): RemoteIndexState = withContext(Dispatchers.IO) {
        val rootFolderId = try {
            googleDriveService.workspaceRootId()
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext RemoteIndexState.Failed
        }
        val indexFileIds = try {
            googleDriveService.findFiles("index.json", rootFolderId)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext RemoteIndexState.Failed
        }
        if (indexFileIds.isEmpty()) return@withContext RemoteIndexState.Absent
        try {
            RemoteIndexState.Loaded(mergeIndexSummaries(indexFileIds.map { readIndex(it).first }))
        } catch (e: Exception) {
            e.printStackTrace()
            RemoteIndexState.Failed
        }
    }

    /**
     * Uploads a local session's log file to Google Drive.
     */
    suspend fun uploadSession(sessionId: String, authToken: String? = null) = withContext(Dispatchers.IO) {
        val summary = databaseService.getSessionSummary(sessionId)
            ?: run {
                val session = databaseService.getSessions().find { it.sessionId == sessionId }
                    ?: throw IllegalArgumentException("Session not found for $sessionId")
                val generated = summaryEngineService.generateSummary(session)
                databaseService.insertSessionSummary(generated)
                generated
            }

        val workspace = requireNotNull(environmentService.loadConfig()) {
            "Choose an active workspace before uploading a session"
        }
        val bundleSummary = summary.copy(
            cloudBundleVersion = CURRENT_SESSION_BUNDLE_VERSION,
            cloudWorkspaceKey = workspace.cloudWorkspaceKey(),
        )

        // 1. Export a complete, versioned session bundle to a unique temporary file.
        val tempDir = File(System.getProperty("java.io.tmpdir"), "ares-sync")
        tempDir.mkdirs()
        val descriptiveName = cloudFileName(bundleSummary)
        val tempFile = File.createTempFile("ares-session-upload-", ".ares-session.zip", tempDir)
        try {
            sessionBundleService.createBundle(sessionId, bundleSummary, tempFile)
            // 2. Locate or create folder structure in Google Drive
            val rootFolderId = googleDriveService.workspaceRootId()
            val sessionsFolderId = googleDriveService.findOrCreateFolder("sessions", rootFolderId)

            val uploadSummary = bundleSummary.copy(
                fileSizeBytes = tempFile.length(),
                cloudFileName = descriptiveName,
                cloudSha256 = sha256(tempFile)
            )

            // 3. Always create immutable bundle bytes. The currently indexed object is never
            // passed to the writer and therefore can never be overwritten before the manifest
            // transaction commits.
            installImmutableCloudObject(
                uploadNewObject = {
                    createImmutableSessionObject(
                        name = descriptiveName,
                        file = tempFile,
                        parentId = sessionsFolderId,
                        mimeType = "application/zip",
                    )
                },
                swapManifest = { newObjectId, recordPriorObjectIds ->
                    // Cross-process optimistic update; the callback is refreshed on each ETag
                    // retry so cleanup only targets objects superseded by the winning attempt.
                    mutateRemoteIndex(rootFolderId) { indexList ->
                        recordPriorObjectIds(
                            indexList.asSequence()
                                .filter { it.sessionId == sessionId }
                                .mapNotNull { it.cloudFileId }
                                .toSet()
                        )
                        indexList.filter { it.sessionId != sessionId } +
                            uploadSummary.copy(cloudFileId = newObjectId)
                    }
                },
                currentManifestObjectId = {
                    currentSessionObjectId(rootFolderId, sessionId)
                },
                deleteObject = googleDriveService::deleteFile
            )
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Gets all session summaries recorded in the Google Drive index.json file.
     */
    suspend fun getRemoteSummaries(): List<SessionSummary> = withContext(Dispatchers.IO) {
        val rootFolderId = googleDriveService.workspaceRootId()
        val indexFileIds = googleDriveService.findFiles("index.json", rootFolderId)
        mergeIndexSummaries(indexFileIds.map { readIndex(it).first })
    }

    /**
     * Gets all registered robot profiles recorded in the Google Drive robots.json file.
     */
    suspend fun getRemoteRobotProfiles(): List<RobotProfile> = withContext(Dispatchers.IO) {
        val rootFolderId = googleDriveService.workspaceRootId()
        val fileIds = googleDriveService.findFiles("robots.json", rootFolderId).sorted()
        if (fileIds.isEmpty()) emptyList()
        else mergeRobotProfiles(fileIds.map { readRobotProfiles(it).first })
    }

    /**
     * Atomically mutates the shared robot registry. Read or parse failures propagate and
     * therefore can never be mistaken for an empty registry. ETags prevent concurrent
     * dashboard instances from silently overwriting one another.
     */
    suspend fun mutateRemoteRobotProfiles(
        transform: (List<RobotProfile>) -> List<RobotProfile>
    ): List<RobotProfile> = withContext(Dispatchers.IO) {
        val rootFolderId = googleDriveService.workspaceRootId()
        robotProfilesMutex.withLock {
            repeat(INDEX_UPDATE_ATTEMPTS) { attempt ->
                val fileIds = googleDriveService.findFiles("robots.json", rootFolderId).sorted()
                if (fileIds.isEmpty()) {
                    val updated = mergeRobotProfiles(listOf(transform(emptyList())))
                    googleDriveService.writeFile(
                        name = "robots.json",
                        bytes = AppJson.encodeToString(updated).toByteArray(Charsets.UTF_8),
                        parentId = rootFolderId,
                        mimeType = "application/json"
                    )
                    return@withLock updated
                }

                val snapshots = fileIds.associateWith { readRobotProfiles(it) }
                val current = mergeRobotProfiles(fileIds.map { snapshots.getValue(it).first })
                val updated = mergeRobotProfiles(listOf(transform(current)))
                val canonicalId = fileIds.first()
                try {
                    googleDriveService.writeFile(
                        name = "robots.json",
                        bytes = AppJson.encodeToString(updated).toByteArray(Charsets.UTF_8),
                        parentId = rootFolderId,
                        mimeType = "application/json",
                        fileId = canonicalId,
                        expectedEtag = snapshots.getValue(canonicalId).second
                    )
                    fileIds.drop(1).forEach { duplicateId ->
                        runCatching { googleDriveService.deleteFile(duplicateId) }
                    }
                    return@withLock updated
                } catch (_: DrivePreconditionFailedException) {
                    if (attempt == INDEX_UPDATE_ATTEMPTS - 1) {
                        throw IllegalStateException("robots.json kept changing during update")
                    }
                    kotlinx.coroutines.delay(INDEX_RETRY_DELAY_MS * (attempt + 1))
                }
            }
            error("robots.json update attempts exhausted")
        }
    }

    /**
     * Downloads a session object from Google Drive. Versioned bundles restore ancillary records;
     * legacy telemetry-only Parquet objects remain readable for backward compatibility.
     */
    suspend fun downloadSession(summary: SessionSummary) = withContext(Dispatchers.IO) {
        validateCloudSummary(summary)
        val cloudFileId = requireNotNull(summary.cloudFileId) { "Cloud manifest is missing its immutable file id" }
        val cloudFileName = requireNotNull(summary.cloudFileName) { "Cloud manifest is missing its canonical filename" }
        val cloudSha256 = requireNotNull(summary.cloudSha256) { "Cloud manifest is missing its SHA-256" }
        require(summary.fileSizeBytes > 0L) { "Cloud manifest file size is invalid" }
        val suffix = if (summary.cloudBundleVersion >= CURRENT_SESSION_BUNDLE_VERSION) {
            ".ares-session.zip"
        } else {
            ".parquet"
        }
        val tempFile = File.createTempFile("cloud_sync_${summary.sessionId}_", suffix)
        var attempt = 0
        var success = false
        var delayMs = 1000L
        while (attempt < 3 && !success) {
            try {
                googleDriveService.readFileStreaming(
                    fileId = cloudFileId,
                    destination = tempFile,
                    expectedName = cloudFileName,
                    expectedBytes = summary.fileSizeBytes,
                    expectedSha256 = cloudSha256
                )
                success = true
            } catch (e: Exception) {
                attempt++
                if (attempt >= 3) throw e
                kotlinx.coroutines.delay(delayMs)
                delayMs += 1000L
            }
        }

        try {
            if (summary.cloudBundleVersion >= CURRENT_SESSION_BUNDLE_VERSION) {
                sessionBundleService.extractAndValidate(tempFile, summary).use { extracted ->
                    val manifest = extracted.manifest
                    databaseService.importCloudSessionBundleAtomically(
                        file = extracted.telemetryFile,
                        summary = manifest.summary,
                        session = manifest.session,
                        actions = manifest.actions,
                        annotations = manifest.annotations,
                        alerts = manifest.alerts,
                        consoleMessages = manifest.consoleMessages,
                        analysisDiagnostics = manifest.analysisDiagnostics,
                        importReports = manifest.importReports,
                    )
                }
                return@withContext
            }
            val session = Session(
                sessionId = summary.sessionId,
                teamId = summary.teamId,
                seasonId = summary.seasonId,
                robotId = summary.robotId,
                createdAt = summary.createdAt,
                durationMs = summary.durationMs,
                tags = summary.tags,
                matchNumber = summary.matchNumber,
                allianceColor = summary.allianceColor
            )
            databaseService.importCloudSessionAtomically(tempFile, summary, session)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Syncs local sessions with Google Drive repository.
     */
    suspend fun performDeltaSync(teamId: String, seasonId: String, authToken: String? = null) = withContext(Dispatchers.IO) {
        // Use the failure-discriminating reader so a Drive outage / corrupt index.json is
        // NOT mistaken for "remote is empty" (which would upload one session and rewrite
        // index.json containing only that session — wiping the real remote manifest).
        val remoteIndexState = readRemoteIndexState()
        if (remoteIndexState is RemoteIndexState.Failed) {
            // Cannot safely determine remote contents this pass; skip the whole sync rather
            // than risk truncating the remote index. The next successful pass will catch up.
            return@withContext
        }
        val remoteSummaries = (remoteIndexState as? RemoteIndexState.Loaded)?.summaries ?: emptyList()

        val localSummaries = databaseService.getAllSessionSummaries()
        val localIds = localSummaries.map { it.sessionId }.toSet()
        val remoteIds = remoteSummaries.map { it.sessionId }.toSet()

        // Download remote sessions missing locally (existing behavior).
        val missingSummaries = remoteSummaries.filter {
            it.teamId == teamId && it.seasonId == seasonId && !localIds.contains(it.sessionId)
        }
        for (summary in missingSummaries) {
            try {
                downloadSession(summary)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Symmetric branch: upload local sessions missing remotely. Best-effort per
        // session, scoped to the active team/season so user-triggered sync stays bounded.
        val localMissingRemote = localSummaries.filter {
            it.teamId == teamId && it.seasonId == seasonId && !remoteIds.contains(it.sessionId)
        }
        for (summary in localMissingRemote) {
            try {
                uploadSession(summary.sessionId, authToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getPrivateKey(privateKeyPem: String): java.security.PrivateKey {
        val cleanPem = privateKeyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val decoded = java.util.Base64.getDecoder().decode(cleanPem)
        val spec = java.security.spec.PKCS8EncodedKeySpec(decoded)
        val kf = java.security.KeyFactory.getInstance("RSA")
        return kf.generatePrivate(spec)
    }

    private fun createGcpJwt(clientEmail: String, privateKeyPem: String, tokenUri: String): String {
        val privateKey = getPrivateKey(privateKeyPem)
        val header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}"
        val nowSec = System.currentTimeMillis() / 1000L
        val claims = """
            {
              "iss": "$clientEmail",
              "scope": "https://www.googleapis.com/auth/cloud-platform",
              "aud": "$tokenUri",
              "exp": ${nowSec + 3600},
              "iat": $nowSec
            }
        """.trimIndent()
        val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        val headerBase64 = encoder.encodeToString(header.toByteArray(Charsets.UTF_8))
        val claimsBase64 = encoder.encodeToString(claims.toByteArray(Charsets.UTF_8))
        val input = "$headerBase64.$claimsBase64"
        val signatureInstance = java.security.Signature.getInstance("SHA256withRSA")
        signatureInstance.initSign(privateKey)
        signatureInstance.update(input.toByteArray(Charsets.UTF_8))
        val signatureBytes = signatureInstance.sign()
        val signatureBase64 = encoder.encodeToString(signatureBytes)

        return "$input.$signatureBase64"
    }

    private suspend fun getVertexAccessToken(serviceAccountJsonPath: String): String {
        val file = File(serviceAccountJsonPath)
        if (!file.exists()) throw IllegalArgumentException("Service Account file not found at: $serviceAccountJsonPath")
        val parsedJson = AppJson.parseToJsonElement(file.readText()).jsonObject
        val clientEmail = parsedJson["client_email"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing client_email")
        val privateKeyPem = parsedJson["private_key"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing private_key")
        val tokenUri = parsedJson["token_uri"]?.jsonPrimitive?.content ?: "https://oauth2.googleapis.com/token"

        val jwt = createGcpJwt(clientEmail, privateKeyPem, tokenUri)
        val response = httpClient.post(tokenUri) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                listOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:jwt-bearer",
                    "assertion" to jwt
                ).formUrlEncode()
            )
        }
        if (response.status != HttpStatusCode.OK) {
            throw Exception("Failed to exchange Service Account JWT for access token: ${response.bodyAsText()}")
        }
        val responseObj = response.body<JsonObject>()
        return responseObj["access_token"]?.jsonPrimitive?.content ?: throw Exception("Missing access_token in response")
    }

    /**
     * Requests diagnostics directly on the client using Google AI Studio or Vertex AI REST API.
     */
    suspend fun requestSubsystemDesignProposal(
        current: SubsystemDocument,
        studentRequest: String,
    ): SubsystemDesignProposal = withContext(Dispatchers.IO) {
        require(studentRequest.isNotBlank()) { "Describe what you want the subsystem to do first." }
        require(studentRequest.length <= 4_000) { "Subsystem design request is limited to 4,000 characters." }

        // This descriptor contains form data only. No Kotlin source, credentials, logs, or robot
        // network data are sent to Gemini by this workflow.
        val currentJson = subsystemDocumentGson.toJson(current)
        val prompt = """
            You are the ARES Subsystem Builder form assistant for novice FTC and FRC students.
            Propose edits to the supplied ARES subsystem descriptor. Do not write Kotlin source.

            Safety and contract rules:
             - Return a complete schemaVersion ${current.schemaVersion} descriptor using the exact JSON shape supplied.
            - Preserve schemaVersion, documentId, uid, platform, revision, parentContentHash,
              implementation, and capabilityActionKeys exactly. The desktop app also enforces this.
            - Preserve existing uid values for existing hardware, state, and control entries.
            - Use only enum names already visible in the descriptor or these supported choices:
              homing NONE, DIGITAL_SENSOR, CURRENT_STALL, VELOCITY_STALL,
              CURRENT_AND_VELOCITY_STALL, CUSTOM_MEASUREMENT; feedforward NONE, SIMPLE_MOTOR,
              ELEVATOR, ARM; follower transforms SAME_DIRECTION, INVERTED, MIRRORED_POSITION.
            - Hardware reads are cached once per loop. Unknown current is invalid, never zero.
            - Keep configuration health, safe neutral output, failed-write latching, explicit neutral
              recovery, telemetry, and zero-allocation periodic paths enabled for actuators.
            - Sensorless homing requires bounded search output, fresh evidence, dwell, and timeout.
            - Followers share one compatible leader and cannot own a controller or homing sequence.
            - Device inversion corrects physical mounting; follower direction is a separate transform.
            - Feedforward units and referenced state fields must be internally consistent.
            - Do not invent unsupported hardware APIs, source paths, catalog actions, or secrets.

            Respond only with one JSON object:
            {
              "summary": "one plain-language sentence",
              "explanations": ["why change 1 helps", "why change 2 is safe"],
               "proposedDocument": { complete descriptor object matching the supplied schema }
            }

            Student request:
            $studentRequest

            Current descriptor:
            $currentJson
        """.trimIndent()

        requestDesignProposalWithRepair(prompt, { requestGeminiStructuredJson(it) }) {
            parseSubsystemDesignProposalResponse(
                current = current,
                responseText = it,
                gson = subsystemDocumentGson,
            )
        }
    }

    suspend fun requestDrivebaseDesignProposal(
        current: DrivetrainDocument,
        studentRequest: String,
    ): DrivebaseDesignProposal = withContext(Dispatchers.IO) {
        require(studentRequest.isNotBlank()) { "Describe the drivebase or change you want first." }
        require(studentRequest.length <= 4_000) { "Drivebase design request is limited to 4,000 characters." }
        val prompt = """
            You are the ARES Drivebase Builder form assistant for novice FTC and FRC students.
            Propose edits to the supplied canonical drivetrain document. Do not write source code,
            edit vendor files, invent calibration evidence, or command hardware.

            Rules:
            - Return the complete JSON document using the exact supplied schema and enum names.
            - Preserve schemaVersion, uid, drivebaseId, kind, platform, canonicalProfileUid,
              parameters, calibrationProvenance, and ctreImport exactly.
            - Preserve existing component/module uid values when they represent the same device.
            - Keep one primary localization source; vision may only be a secondary source.
            - Keep CCW-positive heading, cached inputs, safe neutral, disabled neutral output,
              configuration health, feedback freshness, fault latching, explicit neutral recovery,
              and zero-allocation periodic requirements enabled.
            - A follower must reference one direct drive-motor leader; no follower chains.
            - Inversion describes physical mounting and remains independent from following.
            - Unknown current is invalid, not zero. Do not claim a current limit unless the
              controller actually enforces it.

            Respond only with:
            {"summary":"one sentence","explanations":["reason"],"proposedDocument":{}}

            Student request:
            $studentRequest

            Current drivetrain document:
            ${DrivetrainDocumentCodec.encode(current)}
        """.trimIndent()
        requestDesignProposalWithRepair(prompt, { requestGeminiStructuredJson(it) }) {
            parseDrivebaseDesignProposalResponse(current, it)
        }
    }

    suspend fun requestControlsDesignProposal(
        current: ControlSchemeDocument,
        context: ControlsDesignContext,
        studentRequest: String,
    ): ControlsDesignProposal = withContext(Dispatchers.IO) {
        require(studentRequest.isNotBlank()) { "Describe the controls you want first." }
        require(studentRequest.length <= 4_000) { "Controller design request is limited to 4,000 characters." }
        val controls = context.profileControls.entries.joinToString("\n") { (profile, ids) ->
            "$profile: ${ids.sorted().joinToString()}"
        }
        val prompt = """
            You are the ARES Controller Bindings form assistant for novice FTC and FRC students.
            Propose edits to the supplied control scheme. Do not write source code, save files, or
            invent action/routine/control keys.

            Rules:
            - Return a complete control-scheme JSON document matching the schema below.
            - Preserve schemaVersion, documentId, revision, parentContentHash, and controllers.
            - Targets may use only the allowed action keys or routine IDs below.
            - Sources may use only controls belonging to that controller's assigned profile.
            - Prefer PRESS for one-shot actions, HELD only for actions safe while held, VALUE for
              analog actions, and explicit maximum-active/cooldown policies for risky mechanisms.
            - Do not bind the same input ambiguously. Give chords higher priority and suppress
              constituent bindings when appropriate.
            - Preserve all existing valid bindings unless the student explicitly asks to replace them.

            Binding schema — every binding must have exactly this shape:
            {"bindingId":"stable unique id","displayName":"plain language name",
             "source":{"kind":"BUTTON|CHORD|AXIS_THRESHOLD|AXIS_VALUE|AXIS_ZONE",
                       "controllerSlot":"a slot from controllers below","controlIds":["one allowed control id"],
                       "transform":null,"pressThreshold":null,"releaseThreshold":null,
                       "thresholdDirection":"ABOVE","zoneMinimum":null,"zoneMaximum":null,
                       "zoneHysteresis":0.0,"chordWindowSeconds":0.075},
             "event":"PRESS|RELEASE|HELD|HOLD|REPEAT|VALUE|ZONE_ENTER|ZONE_ACTIVE|ZONE_EXIT",
             "target":{"kind":"ACTION|ROUTINE|CANCEL_ROUTINE|DRIVE","key":"an allowed action key or routine id",
                       "arguments":{},"routinePolicy":"IGNORE_IF_RUNNING"},
             "timing":{"pressDebounceSeconds":0.0,"releaseDebounceSeconds":0.0,"holdAfterSeconds":null,
                       "repeatAfterSeconds":null,"repeatEverySeconds":null,"cooldownSeconds":0.0,
                       "maximumActiveSeconds":null},
             "analogPolicy":null,"priority":0,"suppressConstituentBindings":false,"enabled":true}
            "target" is always a JSON object with "kind" and "key" fields — never a plain string.
            Drivetrain control uses DRIVE targets: {"kind":"DRIVE","key":"vx"} with key one of
            vx (forward), vy (strafe), or omega (rotate); the source must be AXIS_VALUE with event
            VALUE, an analogPolicy is required, and each axis may be bound at most once. When the
            student asks how a stick drives the robot, propose the three DRIVE bindings.
            Example binding:
            {"bindingId":"b-intake-run","displayName":"Run intake","source":{"kind":"BUTTON","controllerSlot":"operator","controlIds":["<an allowed control id>"]},"event":"PRESS","target":{"kind":"ACTION","key":"<an allowed action key>","arguments":{}}}

            Allowed actions: ${context.actionKeys.sorted().joinToString()}
            Allowed routines: ${context.routineIds.sorted().joinToString()}
            Profile controls:
            $controls

            Respond only with:
            {"summary":"one sentence","explanations":["reason"],"proposedDocument":{}}

            Student request:
            $studentRequest

            Current control scheme:
            ${ControlSchemeCodec.encode(current)}
        """.trimIndent()
        requestDesignProposalWithRepair(prompt, { requestGeminiStructuredJson(it) }) {
            parseControlsDesignProposalResponse(current, context, it)
        }
    }

    private suspend fun requestGeminiStructuredJson(prompt: String): String {
        val config = environmentService.loadConfig()
            ?: throw IllegalStateException("No active workspace configuration loaded")
        val aiMode = config.aiMode ?: "STUDIO"
        val modelName = configuredGeminiModel(config.geminiModel)
        val body = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", prompt) }) })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseMimeType", "application/json")
            })
        }
        val response = if (aiMode == "STUDIO") {
            val apiKey = config.geminiApiKey
                ?: throw IllegalStateException("Gemini API key is not configured in Profile → AI Diagnostics")
            httpClient.post("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent") {
                header("x-goog-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } else {
            val serviceAccountPath = config.vertexServiceAccountPath
                ?: throw IllegalStateException("GCP Service Account path is not configured in Profile → AI Diagnostics")
            val projectId = config.vertexProjectId
                ?: throw IllegalStateException("GCP Project ID is not configured in Profile → AI Diagnostics")
            val location = config.vertexLocation ?: "us-central1"
            val accessToken = getVertexAccessToken(serviceAccountPath)
            httpClient.post(
                "https://$location-aiplatform.googleapis.com/v1/projects/$projectId/locations/$location/publishers/google/models/$modelName:generateContent"
            ) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        if (response.status != HttpStatusCode.OK) {
            throw IllegalStateException("Gemini structured proposal failed: ${response.bodyAsText().take(1_000)}")
        }
        val responseObject = response.body<JsonObject>()
        return responseObject["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.content
            ?: throw IllegalStateException("Gemini returned no subsystem proposal text.")
    }

    suspend fun requestForensics(request: ForensicsRequest, authToken: String? = null): ForensicsResponse = withContext(Dispatchers.IO) {
        val config = environmentService.loadConfig()
            ?: throw IllegalStateException("No active workspace configuration loaded")
        val aiMode = config.aiMode ?: "STUDIO"
        val prompt = """
            You are ARES Pit Forensics AI, a diagnostic copilot for FTC/FRC robotics teams.
            Analyze the following telemetry packet containing session statistics, triggered threshold alerts, motor currents, EKF positioning drift, and hardware topology.

            Identify the most likely hardware failure (e.g., loose CAN bus wire, brownout, battery sag, motor stall, camera disconnection, pinpoint encoder drift).

            Respond ONLY with a JSON object conforming exactly to this schema:
            {
              "probableRootCause": "Detailed description of what failed and why",
              "confidenceScore": 0.85,
              "cascadingNodesAffected": ["node_id_1", "node_id_2"],
              "hardwareFaultLocus": {
                "failedNodeId": "id of the primary node that failed",
                "interruptedLinkId": "optional link connection id that was broken"
              },
              "recommendedActions": [
                "Step-by-step checklist action 1",
                "Step-by-step checklist action 2"
              ]
            }

            Data Packet:
            ${Json.encodeToString(ForensicsRequest.serializer(), request)}
        """.trimIndent()
        val modelName = configuredGeminiModel(config.geminiModel)
        val jsonResponse = if (aiMode == "STUDIO") {
            val apiKey = config.geminiApiKey ?: throw IllegalStateException("Gemini API key is not configured in settings")
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"
            val response = httpClient.post(url) {
                header("x-goog-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("contents", buildJsonArray {
                            add(buildJsonObject {
                                put("parts", buildJsonArray {
                                    add(buildJsonObject {
                                        put("text", prompt)
                                    })
                                })
                            })
                        })
                    }
                )
            }
            if (response.status != HttpStatusCode.OK) {
                throw Exception("Google AI Studio request failed: ${response.bodyAsText()}")
            }
            val resObj = response.body<JsonObject>()
            resObj["candidates"]?.jsonArray?.get(0)?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content ?: "{}"
        } else {
            val saPath = config.vertexServiceAccountPath ?: throw IllegalStateException("GCP Service Account path is not configured in settings")
            val projectId = config.vertexProjectId ?: throw IllegalStateException("GCP Project ID is not configured in settings")
            val location = config.vertexLocation ?: "us-central1"
            val accessToken = getVertexAccessToken(saPath)
            val url = "https://$location-aiplatform.googleapis.com/v1/projects/$projectId/locations/$location/publishers/google/models/$modelName:generateContent"
            val response = httpClient.post(url) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("contents", buildJsonArray {
                            add(buildJsonObject {
                                put("role", "user")
                                put("parts", buildJsonArray {
                                    add(buildJsonObject {
                                        put("text", prompt)
                                    })
                                })
                            })
                        })
                    }
                )
            }
            if (response.status != HttpStatusCode.OK) {
                throw Exception("Vertex AI request failed: ${response.bodyAsText()}")
            }
            val resObj = response.body<JsonObject>()
            resObj["candidates"]?.jsonArray?.get(0)?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content ?: "{}"
        }
        val sanitizedJson = jsonResponse.replace(Regex("```(?:json)?\\n?(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL), "$1").trim()

        try {
            AppJson.decodeFromString<ForensicsResponse>(sanitizedJson)
        } catch (e: Exception) {
            ForensicsResponse(
                probableRootCause = "AI produced unparseable diagnostics: $sanitizedJson",
                confidenceScore = 0.0,
                cascadingNodesAffected = emptyList(),
                hardwareFaultLocus = null,
                recommendedActions = listOf("Retry diagnostics", "Check logs manually")
            )
        }
    }

    suspend fun requestChatCoach(
        request: ForensicsRequest,
        userQuestion: String,
        chatHistory: List<Pair<String, String>> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        val config = environmentService.loadConfig()
            ?: throw IllegalStateException("No active workspace configuration loaded")
        val aiMode = config.aiMode ?: "STUDIO"
        val modelName = configuredGeminiModel(config.geminiModel)
        val historyStr = chatHistory.joinToString("\n") { (role, text) ->
            if (role == "user") "User: $text" else "Coach: $text"
        }
        val prompt = """
            You are ARES Pit Coach AI, a diagnostic copilot for FTC/FRC robotics teams.
            You are helping the team debug their robot using the following telemetry, alerts, and forensics context.

            Diagnostics Context:
            - Team: ${request.teamId}
            - Session: ${request.sessionId}
            - Alerts: ${request.alerts.joinToString { it.ruleKey }}

            Conversation History:
            $historyStr

            Analyze the context and answer the user's question. Provide specific, concise, actionable advice (e.g. recommend PID tuning changes, check specific cables, calibrate sensors) for a robotics student. Use markdown formatting.

            User's Question: $userQuestion
        """.trimIndent()
        val jsonResponse = if (aiMode == "STUDIO") {
            val apiKey = config.geminiApiKey ?: throw IllegalStateException("Gemini API key is not configured in settings")
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"
            val response = httpClient.post(url) {
                header("x-goog-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("contents", buildJsonArray {
                            add(buildJsonObject {
                                put("parts", buildJsonArray {
                                    add(buildJsonObject {
                                        put("text", prompt)
                                    })
                                })
                            })
                        })
                    }
                )
            }
            if (response.status != HttpStatusCode.OK) {
                throw Exception("Google AI Studio request failed: ${response.bodyAsText()}")
            }
            val resObj = response.body<JsonObject>()
            resObj["candidates"]?.jsonArray?.get(0)?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
        } else {
            val saPath = config.vertexServiceAccountPath ?: throw IllegalStateException("GCP Service Account path is not configured in settings")
            val projectId = config.vertexProjectId ?: throw IllegalStateException("GCP Project ID is not configured in settings")
            val location = config.vertexLocation ?: "us-central1"
            val accessToken = getVertexAccessToken(saPath)
            val url = "https://$location-aiplatform.googleapis.com/v1/projects/$projectId/locations/$location/publishers/google/models/$modelName:generateContent"
            val response = httpClient.post(url) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("contents", buildJsonArray {
                            add(buildJsonObject {
                                put("role", "user")
                                put("parts", buildJsonArray {
                                    add(buildJsonObject {
                                        put("text", prompt)
                                    })
                                })
                            })
                        })
                    }
                )
            }
            if (response.status != HttpStatusCode.OK) {
                throw Exception("Vertex AI request failed: ${response.bodyAsText()}")
            }
            val resObj = response.body<JsonObject>()
            resObj["candidates"]?.jsonArray?.get(0)?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
        }
        jsonResponse
    }

    suspend fun requestSqlAnalysis(
        userQuestion: String,
        databaseService: DatabaseService
    ): String = withContext(Dispatchers.IO) {
        val config = environmentService.loadConfig()
            ?: throw IllegalStateException("No active workspace configuration loaded")
        val aiMode = config.aiMode ?: "STUDIO"
        val modelName = configuredGeminiModel(config.geminiModel)
        val schemaPrompt = """
            You are ARES SQL Data Analyst, a diagnostic agent for a robotics team telemetry database.
            We run on DuckDB.

            Database Tables:
            1. `sessions`:
               - `session_id` VARCHAR (PRIMARY KEY)
               - `team_id` VARCHAR
               - `season_id` VARCHAR
               - `robot_id` VARCHAR
               - `created_at` BIGINT (epoch ms)
               - `duration_ms` BIGINT
               - `tags` VARCHAR (json array of strings)
               - `match_number` BIGINT
               - `alliance_color` VARCHAR
            2. `session_summaries`:
               - `session_id` VARCHAR (PRIMARY KEY)
               - `team_id` VARCHAR
               - `season_id` VARCHAR
               - `robot_id` VARCHAR
               - `created_at` BIGINT
               - `duration_ms` BIGINT
               - `min_battery_voltage` DOUBLE
               - `max_ekf_drift` DOUBLE
               - `avg_loop_time_ms` DOUBLE
               - `p95_loop_time_ms` DOUBLE
               - `motor_current_averages` VARCHAR (json map of motor names to averages, e.g. '{"fl": 2.5, "fr": 2.3}')
               - `vision_acceptance_rate` DOUBLE
               - `avg_cross_track_error` DOUBLE
               - `avg_battery_resistance` DOUBLE
               - `max_motor_temps` VARCHAR (json map)
               - `avg_vision_latency_ms` DOUBLE
            3. `alerts`:
               - `alert_id` VARCHAR (PRIMARY KEY)
               - `session_id` VARCHAR
               - `rule_key` VARCHAR
               - `trigger_timestamp_ms` BIGINT
               - `resolve_timestamp_ms` BIGINT
               - `duration_ms` BIGINT
               - `peak_value` DOUBLE
               - `triaged` BIGINT (0 or 1)

            Task: Generate a single read-only SQL SELECT statement to extract the data needed to answer this user question.
            Provide ONLY a JSON object matching this schema:
            {
              "sql": "SELECT ... FROM session_summaries ..."
            }
            Do NOT run modifying queries (INSERT, UPDATE, DELETE, DROP). Keep it strictly read-only.

            User's Question: $userQuestion
        """.trimIndent()
        val jsonResponse = if (aiMode == "STUDIO") {
            val apiKey = config.geminiApiKey ?: throw IllegalStateException("Gemini API key is not configured in settings")
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"
            val response = httpClient.post(url) {
                header("x-goog-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("contents", buildJsonArray {
                            add(buildJsonObject {
                                put("parts", buildJsonArray {
                                    add(buildJsonObject {
                                        put("text", schemaPrompt)
                                    })
                                })
                            })
                        })
                    }
                )
            }
            if (response.status != HttpStatusCode.OK) {
                throw Exception("Google AI Studio request failed: ${response.bodyAsText()}")
            }
            val resObj = response.body<JsonObject>()
            resObj["candidates"]?.jsonArray?.get(0)?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content ?: "{}"
        } else {
            val saPath = config.vertexServiceAccountPath ?: throw IllegalStateException("GCP Service Account path is not configured in settings")
            val projectId = config.vertexProjectId ?: throw IllegalStateException("GCP Project ID is not configured in settings")
            val location = config.vertexLocation ?: "us-central1"
            val accessToken = getVertexAccessToken(saPath)
            val url = "https://$location-aiplatform.googleapis.com/v1/projects/$projectId/locations/$location/publishers/google/models/$modelName:generateContent"
            val response = httpClient.post(url) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("contents", buildJsonArray {
                            add(buildJsonObject {
                                put("role", "user")
                                put("parts", buildJsonArray {
                                    add(buildJsonObject {
                                        put("text", schemaPrompt)
                                    })
                                })
                            })
                        })
                    }
                )
            }
            if (response.status != HttpStatusCode.OK) {
                throw Exception("Vertex AI request failed: ${response.bodyAsText()}")
            }
            val resObj = response.body<JsonObject>()
            resObj["candidates"]?.jsonArray?.get(0)?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content ?: "{}"
        }
        val sanitizedJson = jsonResponse.replace(Regex("```(?:json)?\\n?(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL), "$1").trim()
        val sqlQuery = try {
            val parsed = AppJson.parseToJsonElement(sanitizedJson).jsonObject
            parsed["sql"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("No SQL generated")
        } catch (e: Exception) {
            return@withContext "I was unable to formulate a SQL query to extract the data. Details: $sanitizedJson"
        }

        val queryResult = try {
            // AiSqlQueryGuard tokenizes the complete statement, restricts tables/functions, and
            // rejects file/URL table functions. Keep one authoritative policy instead of a
            // keyword regex that can disagree on comments, literals, or WITH clauses.
            databaseService.executeAiQuery(sqlQuery)
        } catch (e: IllegalArgumentException) {
            return@withContext "Security Error: ${e.message ?: "The generated query was blocked."}"
        } catch (e: Exception) {
            return@withContext "Failed to execute generated SQL query:\n```sql\n$sqlQuery\n```\nError: ${e.message}"
        }
        val summaryPrompt = """
            You are ARES SQL Data Analyst.
            The user asked: "$userQuestion"

            To answer it, we ran this SQL query:
            ```sql
            $sqlQuery
            ```

            And got these results:
            Columns: ${queryResult.columns.joinToString(", ")}
            Rows:
            ${queryResult.rows.joinToString("\n") { it.joinToString(", ") }}

            Write a clear, concise, and helpful summary answering the user's question based on the retrieved data. Use markdown formatting. Mention match numbers or averages clearly.
        """.trimIndent()
        val finalResponse = if (aiMode == "STUDIO") {
            val apiKey = config.geminiApiKey ?: throw IllegalStateException("Gemini API key is not configured in settings")
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"
            val response = httpClient.post(url) {
                header("x-goog-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("contents", buildJsonArray {
                            add(buildJsonObject {
                                put("parts", buildJsonArray {
                                    add(buildJsonObject {
                                        put("text", summaryPrompt)
                                    })
                                })
                            })
                        })
                    }
                )
            }
            if (response.status != HttpStatusCode.OK) {
                throw Exception("Google AI Studio request failed: ${response.bodyAsText()}")
            }
            val resObj = response.body<JsonObject>()
            resObj["candidates"]?.jsonArray?.get(0)?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
        } else {
            val saPath = config.vertexServiceAccountPath ?: throw IllegalStateException("GCP Service Account path is not configured in settings")
            val projectId = config.vertexProjectId ?: throw IllegalStateException("GCP Project ID is not configured in settings")
            val location = config.vertexLocation ?: "us-central1"
            val accessToken = getVertexAccessToken(saPath)
            val url = "https://$location-aiplatform.googleapis.com/v1/projects/$projectId/locations/$location/publishers/google/models/$modelName:generateContent"
            val response = httpClient.post(url) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("contents", buildJsonArray {
                            add(buildJsonObject {
                                put("role", "user")
                                put("parts", buildJsonArray {
                                    add(buildJsonObject {
                                        put("text", summaryPrompt)
                                    })
                                })
                            })
                        })
                    }
                )
            }
            if (response.status != HttpStatusCode.OK) {
                throw Exception("Vertex AI request failed: ${response.bodyAsText()}")
            }
            val resObj = response.body<JsonObject>()
            resObj["candidates"]?.jsonArray?.get(0)?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
        }

        finalResponse
    }

    /** Deletes only the Google Drive copy of a session. Local DuckDB data is independent. */
    suspend fun deleteCloudSession(sessionId: String, teamId: String, authToken: String? = null) = withContext(Dispatchers.IO) {
        try {
            val indexState = readRemoteIndexState() as? RemoteIndexState.Loaded
                ?: throw IllegalStateException("Cloud session index is unavailable")
            val removedSummary = indexState.summaries.singleOrNull { it.sessionId == sessionId }
                ?: throw IllegalArgumentException("Cloud session $sessionId is not indexed")
            val cloudObjectFileId = requireNotNull(removedSummary.cloudFileId) {
                "Cloud session manifest is missing its immutable file id"
            }
            val rootFolderId = googleDriveService.workspaceRootId()
            removeImmutableCloudObject(
                removeManifestReference = {
                    mutateRemoteIndex(rootFolderId) { indexList ->
                        indexList.filter { it.sessionId != sessionId }
                    }
                },
                deleteObject = {
                    googleDriveService.deleteFile(cloudObjectFileId)
                },
                onCleanupFailure = { cleanupFailure ->
                    System.err.println(
                        "[SyncEngineService] Cloud session $sessionId was removed from the index, " +
                            "but orphan object $cloudObjectFileId could not be deleted: ${cleanupFailure.message}"
                    )
                }
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            throw IllegalStateException("Cloud session $sessionId could not be deleted", e)
        }
    }

    fun close() {
        httpClient.close()
    }

    private companion object {
        fun configuredGeminiModel(configured: String?): String = configured
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.takeUnless { it == "gemini-1.5-flash" }
            ?: DEFAULT_GEMINI_MODEL

        const val INDEX_UPDATE_ATTEMPTS = 5
        const val INDEX_RETRY_DELAY_MS = 100L
        const val CLOUD_UPLOAD_ATTEMPTS = 3
        const val CLOUD_UPLOAD_RETRY_DELAY_MS = 1_000L
        const val MAX_CLOUD_SESSION_BYTES = 2L * 1024L * 1024L * 1024L
    }
}
