
package com.ares.analytics.service

import com.ares.analytics.shared.AppJson

import com.ares.analytics.shared.*
import com.ares.analytics.shared.models.*
import com.ares.analytics.shared.models.allowsAutomaticExternalUpdates
import com.ares.analytics.util.Sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.io.File
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

internal fun robotProfileMutationApplied(
    before: List<RobotProfile>,
    desired: List<RobotProfile>,
    published: List<RobotProfile>,
): Boolean {
    val beforeById = before.associateBy(RobotProfile::robotId)
    val desiredById = desired.associateBy(RobotProfile::robotId)
    val publishedById = published.associateBy(RobotProfile::robotId)
    val changed = desiredById.filter { (robotId, profile) -> beforeById[robotId] != profile }
    val removed = beforeById.keys - desiredById.keys
    return changed.all { (robotId, profile) -> publishedById[robotId] == profile } &&
        removed.none(publishedById::containsKey)
}

/**
 * Desktop-owned synchronization service for historical telemetry sessions.
 *
 * The robot remains offline-first. Analytics packages a complete session as an immutable
 * `.ares-session.zip` bundle, uploads it to the active workspace's Google Drive folder, and then
 * publishes the Drive-hosted session index through version-bracketed reads, stale-version checks,
 * retry, and post-write verification. Content hashes provide incremental sync and download-
 * integrity checks.
 *
 * ### Thread Safety & Performance Guarantees:
 * All Google Drive requests execute asynchronously on `Dispatchers.IO`.
 *
 * @param databaseService Primary DuckDB telemetry database service.
 * @param environmentService Workspace environment settings provider.
 * @param summaryEngineService Match KPI summary calculator.
 * @param googleDriveService Authoritative workspace session storage service.
 *
 * @see DatabaseService
 */
class SyncEngineService(
    private val databaseService: DatabaseService,
    private val environmentService: EnvironmentService,
    private val summaryEngineService: SummaryEngineService,
    private val googleDriveService: GoogleDriveService,
) {
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
        val extension = ".ares-session.zip"
        return "ARES_Telemetry_${date}_${safeFileComponent(summary.robotId)}$match${alliance}_${mode}_${safeFileComponent(summary.sessionId)}$extension"
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
     * Serializes this process's index.json read-modify-write sequences. Cross-process writers are
     * handled separately through Drive version checks, retry, and post-write verification.
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
        require(summary.cloudBundleVersion == CURRENT_SESSION_BUNDLE_VERSION) {
            "Cloud session bundle version is unsupported"
        }
        require(summary.cloudWorkspaceKey?.matches(Regex("[A-Za-z0-9._:-]{1,320}")) == true) {
            "Cloud session workspace identity is invalid"
        }
    }

    private suspend fun readIndex(fileId: String): Pair<List<SessionSummary>, Long> {
        val snapshot = googleDriveService.readFileSnapshot(fileId)
        val summaries = AppJson.decodeFromString<List<SessionSummary>>(
            String(snapshot.bytes, Charsets.UTF_8)
        )
        return summaries to snapshot.version
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

    private suspend fun readRobotProfiles(fileId: String): Pair<List<RobotProfile>, Long> {
        val snapshot = googleDriveService.readFileSnapshot(fileId)
        val profiles = AppJson.decodeFromString<List<RobotProfile>>(
            String(snapshot.bytes, Charsets.UTF_8)
        )
        return profiles to snapshot.version
    }

    /**
     * Optimistic cross-process read-modify-write for index.json. Google Drive permits duplicate
     * names, so duplicate first-run indexes are merged and collapsed into a canonical file.
     */
    private suspend fun mutateRemoteIndex(
        rootFolderId: String,
        transform: (List<SessionSummary>) -> List<SessionSummary>,
        isApplied: (List<SessionSummary>) -> Boolean,
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
            val expectedVersion = snapshots.getValue(canonicalId).second
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
                    expectedVersion = expectedVersion
                )
                val publishedIds = googleDriveService.findFiles("index.json", rootFolderId)
                val published = mergeIndexSummaries(publishedIds.map { readIndex(it).first })
                if (isApplied(published)) {
                    publishedIds.asSequence().filter { it != canonicalId }.forEach { duplicateId ->
                        runCatching { googleDriveService.deleteFile(duplicateId) }
                    }
                    return@withLock
                }
                if (attempt == INDEX_UPDATE_ATTEMPTS - 1) {
                    throw IllegalStateException("index.json did not retain the requested update")
                }
                kotlinx.coroutines.delay(INDEX_RETRY_DELAY_MS * (attempt + 1))
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
    suspend fun uploadSession(sessionId: String) = withContext(Dispatchers.IO) {
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
                cloudSha256 = Sha256.fileHex(tempFile)
            )

            // 3. Always create immutable bundle bytes. The currently indexed object is never
            // passed to the writer and therefore can never be overwritten before the manifest
            // transaction commits.
            val installedObjectId = installImmutableCloudObject(
                uploadNewObject = {
                    createImmutableSessionObject(
                        name = descriptiveName,
                        file = tempFile,
                        parentId = sessionsFolderId,
                        mimeType = "application/zip",
                    )
                },
                swapManifest = { newObjectId, recordPriorObjectIds ->
                    // Cross-process optimistic update; the callback is refreshed on each version
                    // retry so cleanup only targets objects superseded by the winning attempt.
                    mutateRemoteIndex(
                        rootFolderId = rootFolderId,
                        transform = { indexList ->
                            recordPriorObjectIds(
                                indexList.asSequence()
                                    .filter { it.sessionId == sessionId }
                                    .mapNotNull { it.cloudFileId }
                                    .toSet()
                            )
                            indexList.filter { it.sessionId != sessionId } +
                                uploadSummary.copy(cloudFileId = newObjectId)
                        },
                        isApplied = { published ->
                            published.singleOrNull { it.sessionId == sessionId }?.cloudFileId == newObjectId
                        },
                    )
                },
                currentManifestObjectId = {
                    currentSessionObjectId(rootFolderId, sessionId)
                },
                deleteObject = googleDriveService::deleteFile
            )
            databaseService.integrationEvents.cloudUploadCommitted(
                workspace = com.ares.analytics.shared.models.IntegrationWorkspaceIdentity(
                    teamId = workspace.teamId,
                    seasonId = workspace.seasonId,
                    robotId = workspace.robotId,
                ),
                sessionId = sessionId,
                remoteObjectId = installedObjectId,
                manifestRevision = requireNotNull(uploadSummary.cloudSha256),
                occurredAtMs = System.currentTimeMillis(),
                externalUpdatesAllowed = summary.allowsAutomaticExternalUpdates(),
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
     * Safely mutates the shared robot registry. Read or parse failures propagate and
     * therefore can never be mistaken for an empty registry. Drive versions detect stale
     * snapshots before an update; the surrounding retry loop re-reads and reapplies changes.
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
                        expectedVersion = snapshots.getValue(canonicalId).second
                    )
                    val publishedIds = googleDriveService.findFiles("robots.json", rootFolderId).sorted()
                    val published = mergeRobotProfiles(publishedIds.map { readRobotProfiles(it).first })
                    if (robotProfileMutationApplied(current, updated, published)) {
                        publishedIds.asSequence().filter { it != canonicalId }.forEach { duplicateId ->
                            runCatching { googleDriveService.deleteFile(duplicateId) }
                        }
                        return@withLock published
                    }
                    if (attempt == INDEX_UPDATE_ATTEMPTS - 1) {
                        throw IllegalStateException("robots.json did not retain the requested update")
                    }
                    kotlinx.coroutines.delay(INDEX_RETRY_DELAY_MS * (attempt + 1))
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

    /** Downloads and atomically restores one complete session bundle from Google Drive. */
    suspend fun downloadSession(summary: SessionSummary) = withContext(Dispatchers.IO) {
        validateCloudSummary(summary)
        val cloudFileId = requireNotNull(summary.cloudFileId) { "Cloud manifest is missing its immutable file id" }
        val cloudFileName = requireNotNull(summary.cloudFileName) { "Cloud manifest is missing its canonical filename" }
        val cloudSha256 = requireNotNull(summary.cloudSha256) { "Cloud manifest is missing its SHA-256" }
        require(summary.fileSizeBytes > 0L) { "Cloud manifest file size is invalid" }
        val tempFile = File.createTempFile("cloud_sync_${summary.sessionId}_", ".ares-session.zip")
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
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Syncs local sessions with Google Drive repository.
     */
    suspend fun performDeltaSync(teamId: String, seasonId: String) = withContext(Dispatchers.IO) {
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
                uploadSession(summary.sessionId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** Deletes only the Google Drive copy of a session. Local DuckDB data is independent. */
    suspend fun deleteCloudSession(sessionId: String, teamId: String) = withContext(Dispatchers.IO) {
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
                    mutateRemoteIndex(
                        rootFolderId = rootFolderId,
                        transform = { indexList -> indexList.filter { it.sessionId != sessionId } },
                        isApplied = { published -> published.none { it.sessionId == sessionId } },
                    )
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

    private companion object {

        const val INDEX_UPDATE_ATTEMPTS = 5
        const val INDEX_RETRY_DELAY_MS = 100L
        const val CLOUD_UPLOAD_ATTEMPTS = 3
        const val CLOUD_UPLOAD_RETRY_DELAY_MS = 1_000L
        const val MAX_CLOUD_SESSION_BYTES = 2L * 1024L * 1024L * 1024L
    }
}
