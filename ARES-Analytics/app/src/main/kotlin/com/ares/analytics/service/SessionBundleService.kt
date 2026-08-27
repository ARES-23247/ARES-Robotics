package com.ares.analytics.service

import com.ares.analytics.shared.AnalysisDiagnostic
import com.ares.analytics.shared.AppJson
import com.ares.analytics.shared.ConsoleMessage
import com.ares.analytics.shared.RobotActionRecord
import com.ares.analytics.shared.Session
import com.ares.analytics.shared.SessionAnnotation
import com.ares.analytics.shared.SessionSummary
import com.ares.analytics.shared.AlertRecord
import com.ares.analytics.shared.WorkspaceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@Serializable
internal data class SessionBundleManifest(
    val formatVersion: Int,
    val workspaceKey: String,
    val session: Session,
    val summary: SessionSummary,
    val actions: List<RobotActionRecord>,
    val annotations: List<SessionAnnotation>,
    val alerts: List<AlertRecord>,
    val consoleMessages: List<ConsoleMessage>,
    val analysisDiagnostics: List<AnalysisDiagnostic>,
    val importReports: List<ImportReport>,
    val telemetryEntry: String = TELEMETRY_ENTRY,
    val telemetrySizeBytes: Long,
    val telemetrySha256: String,
) {
    init {
        require(formatVersion == CURRENT_SESSION_BUNDLE_VERSION) { "Unsupported session bundle version" }
        require(workspaceKey.matches(Regex("[A-Za-z0-9._:-]{1,320}"))) { "Bundle workspace key is invalid" }
        require(session.sessionId == summary.sessionId) { "Bundle session and summary identities differ" }
        require(
            session.teamId == summary.teamId &&
                session.seasonId == summary.seasonId &&
                session.robotId == summary.robotId &&
                session.createdAt == summary.createdAt
        ) { "Bundle session and summary robot metadata differ" }
        require(actions.all { it.sessionId == session.sessionId }) { "Bundle contains actions from another session" }
        require(annotations.all { it.sessionId == session.sessionId }) { "Bundle contains annotations from another session" }
        require(alerts.all { it.sessionId == session.sessionId }) { "Bundle contains alerts from another session" }
        require(analysisDiagnostics.all { it.sessionId == session.sessionId }) {
            "Bundle contains analysis diagnostics from another session"
        }
        require(importReports.all { it.sessionId == session.sessionId }) {
            "Bundle contains import reports from another session"
        }
        require(telemetryEntry == TELEMETRY_ENTRY) { "Bundle telemetry entry is not canonical" }
        require(telemetrySizeBytes in 0L..MAX_BUNDLE_TELEMETRY_BYTES) { "Bundle telemetry size is invalid" }
        require(telemetrySha256.matches(Regex("[0-9a-f]{64}"))) { "Bundle telemetry SHA-256 is invalid" }
    }
}

internal data class ExtractedSessionBundle(
    val manifest: SessionBundleManifest,
    val telemetryFile: File,
) : AutoCloseable {
    override fun close() {
        telemetryFile.delete()
    }
}

/** Creates and validates versioned cloud objects that preserve a complete analyzed session. */
internal class SessionBundleService(
    private val databaseService: DatabaseService,
    private val environmentService: EnvironmentService,
    private val importArchiveService: ImportArchiveService = ImportArchiveService(),
) {
    suspend fun createBundle(
        sessionId: String,
        summary: SessionSummary,
        destination: File,
    ): SessionBundleManifest = withContext(Dispatchers.IO) {
        val session = databaseService.getSessions().singleOrNull { it.sessionId == sessionId }
            ?: throw IllegalArgumentException("Session not found for $sessionId")
        val workspace = requireNotNull(environmentService.loadConfig()) {
            "Choose an active workspace before uploading a session"
        }
        requireSessionInWorkspace(session, workspace)

        val telemetryFile = File.createTempFile("ares-session-telemetry-", ".parquet", destination.parentFile)
        try {
            databaseService.exportSessionToParquet(sessionId, telemetryFile)
            val archivedImportReports = importArchiveService.load(workspace.projectPath)
                .let { snapshot -> snapshot.imported + snapshot.quarantined }
                .mapNotNull(ImportArchiveEntry::report)
                .filter { it.sessionId == sessionId }
            val importReports = (databaseService.getSessionImportReports(sessionId) + archivedImportReports)
                .distinctBy { it.sourceSha256 to it.sourceName }
            val localSummary = summary.copy(
                rawGcsPath = null,
                fileSizeBytes = 0L,
                cloudFileId = null,
                cloudFileName = null,
                cloudSha256 = null,
                cloudBundleVersion = CURRENT_SESSION_BUNDLE_VERSION,
                cloudWorkspaceKey = workspace.cloudWorkspaceKey(),
            )
            val manifest = SessionBundleManifest(
                formatVersion = CURRENT_SESSION_BUNDLE_VERSION,
                workspaceKey = workspace.cloudWorkspaceKey(),
                session = session,
                summary = localSummary,
                actions = databaseService.getActionsForSession(sessionId),
                annotations = databaseService.getAnnotations(sessionId),
                alerts = databaseService.getAlerts(sessionId),
                consoleMessages = databaseService.getConsoleMessages(sessionId),
                analysisDiagnostics = databaseService.getAnalysisDiagnostics(sessionId),
                importReports = importReports,
                telemetrySizeBytes = telemetryFile.length(),
                telemetrySha256 = sha256(telemetryFile),
            )
            val manifestBytes = AppJson.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            require(manifestBytes.size <= MAX_BUNDLE_MANIFEST_BYTES) {
                "Session metadata exceeds the ${MAX_BUNDLE_MANIFEST_BYTES / (1024 * 1024)} MiB bundle limit"
            }

            writeFileAtomically(destination) { temporary ->
                ZipOutputStream(FileOutputStream(temporary).buffered()).use { output ->
                    output.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    output.write(manifestBytes)
                    output.closeEntry()
                    output.putNextEntry(ZipEntry(TELEMETRY_ENTRY))
                    telemetryFile.inputStream().buffered().use { input ->
                        input.copyTo(output, bufferSize = 256 * 1024)
                    }
                    output.closeEntry()
                }
            }
            manifest
        } finally {
            telemetryFile.delete()
        }
    }

    suspend fun extractAndValidate(
        bundle: File,
        expectedSummary: SessionSummary,
    ): ExtractedSessionBundle = withContext(Dispatchers.IO) {
        require(bundle.isFile) { "Cloud session bundle is missing" }
        val workspace = requireNotNull(environmentService.loadConfig()) {
            "Choose an active workspace before downloading a session"
        }
        val telemetryFile = File.createTempFile("ares-cloud-telemetry-", ".parquet", bundle.parentFile)
        try {
            val manifest = ZipFile(bundle).use { zip ->
                val entries = zip.entries().asSequence().toList()
                require(entries.size == 2 && entries.map(ZipEntry::getName).toSet() == setOf(MANIFEST_ENTRY, TELEMETRY_ENTRY)) {
                    "Cloud session bundle contains unexpected or missing entries"
                }
                require(entries.none(ZipEntry::isDirectory)) { "Cloud session bundle contains a directory entry" }
                val manifestEntry = requireNotNull(zip.getEntry(MANIFEST_ENTRY))
                require(manifestEntry.size in 1L..MAX_BUNDLE_MANIFEST_BYTES.toLong()) {
                    "Cloud session manifest size is invalid"
                }
                val manifestBytes = zip.getInputStream(manifestEntry).use { input ->
                    input.readNBytes(MAX_BUNDLE_MANIFEST_BYTES + 1)
                }
                require(manifestBytes.size <= MAX_BUNDLE_MANIFEST_BYTES) { "Cloud session manifest is too large" }
                val decoded = AppJson.decodeFromString<SessionBundleManifest>(String(manifestBytes, Charsets.UTF_8))
                require(decoded.workspaceKey == workspace.cloudWorkspaceKey()) {
                    "Cloud session belongs to a different workspace"
                }
                require(decoded.session.sessionId == expectedSummary.sessionId) { "Cloud session ID mismatch" }
                require(decoded.summary.teamId == expectedSummary.teamId &&
                    decoded.summary.seasonId == expectedSummary.seasonId &&
                    decoded.summary.robotId == expectedSummary.robotId
                ) { "Cloud session robot identity mismatch" }
                require(decoded.summary.cloudBundleVersion == CURRENT_SESSION_BUNDLE_VERSION) {
                    "Cloud session manifest version mismatch"
                }
                require(decoded.summary.cloudWorkspaceKey == decoded.workspaceKey) {
                    "Cloud session workspace identity mismatch"
                }
                val expectedBundleSummary = expectedSummary.copy(
                    rawGcsPath = null,
                    fileSizeBytes = 0L,
                    cloudFileId = null,
                    cloudFileName = null,
                    cloudSha256 = null,
                )
                require(decoded.summary == expectedBundleSummary) {
                    "Cloud index and session bundle metadata disagree"
                }

                val telemetryEntry = requireNotNull(zip.getEntry(TELEMETRY_ENTRY))
                require(telemetryEntry.size == decoded.telemetrySizeBytes) {
                    "Cloud session telemetry size mismatch"
                }
                require(telemetryEntry.size <= MAX_BUNDLE_TELEMETRY_BYTES) {
                    "Cloud session telemetry is too large"
                }
                zip.getInputStream(telemetryEntry).use { input ->
                    FileOutputStream(telemetryFile).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total = Math.addExact(total, count.toLong())
                            require(total <= decoded.telemetrySizeBytes) {
                                "Cloud session telemetry exceeds its declared size"
                            }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                require(telemetryFile.length() == decoded.telemetrySizeBytes) {
                    "Cloud session telemetry ended before its declared size"
                }
                require(sha256(telemetryFile) == decoded.telemetrySha256) {
                    "Cloud session telemetry SHA-256 mismatch"
                }
                decoded
            }
            ExtractedSessionBundle(manifest, telemetryFile)
        } catch (failure: Throwable) {
            telemetryFile.delete()
            throw failure
        }
    }

    private fun requireSessionInWorkspace(session: Session, workspace: WorkspaceConfig) {
        require(session.teamId == workspace.teamId &&
            session.seasonId == workspace.seasonId &&
            session.robotId == workspace.robotId
        ) { "Session belongs to a different workspace robot" }
    }
}

internal fun WorkspaceConfig.cloudWorkspaceKey(): String {
    val components = listOf(league.name, teamId, seasonId, robotId).map { it.trim().lowercase() }
    require(components.all { it.matches(Regex("[a-z0-9._-]{1,80}")) }) {
        "Workspace identity contains unsupported characters"
    }
    return components.joinToString(":")
}

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal const val CURRENT_SESSION_BUNDLE_VERSION = 1
private const val MANIFEST_ENTRY = "manifest.json"
private const val TELEMETRY_ENTRY = "telemetry.parquet"
private const val MAX_BUNDLE_MANIFEST_BYTES = 64 * 1024 * 1024
private const val MAX_BUNDLE_TELEMETRY_BYTES = 2L * 1024L * 1024L * 1024L
