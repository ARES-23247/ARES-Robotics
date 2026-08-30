package com.ares.analytics.service

import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.WorkspaceConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class RobotLogSource(
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
)

sealed interface RobotLogImportOutcome {
    val session: Session

    data class Imported(
        override val session: Session,
        val archivedFiles: List<File>,
    ) : RobotLogImportOutcome

    data class AlreadyImported(
        override val session: Session,
    ) : RobotLogImportOutcome
}

/**
 * Downloads one robot-hosted log with an encoded query parameter and an exact byte ceiling.
 * The caller owns [destination] and is responsible for deleting it after a failed attempt.
 */
internal class RobotLogDownloader(
    private val httpClient: HttpClient = createRobotLogHttpClient(),
) : AutoCloseable {

    suspend fun download(robotBaseUrl: String, source: RobotLogSource, destination: File): String {
        validateRobotLogSource(source)
        require(destination.parentFile?.isDirectory == true) { "Download staging directory is missing" }

        var attempt = 0
        var delayMs = INITIAL_RETRY_DELAY_MS
        var lastFailure: Throwable? = null
        while (attempt < MAX_DOWNLOAD_ATTEMPTS) {
            try {
                destination.delete()
                val digest = MessageDigest.getInstance("SHA-256")
                httpClient.prepareGet("${robotBaseUrl.trimEnd('/')}/api/download") {
                    parameter("file", source.name)
                }.execute { response ->
                    require(response.status == HttpStatusCode.OK) {
                        "Robot returned HTTP ${response.status.value} for ${source.name}"
                    }
                    response.contentLength()?.let { contentLength ->
                        require(contentLength == source.sizeBytes) {
                            "Robot reported $contentLength bytes for ${source.name}; expected ${source.sizeBytes}"
                        }
                    }
                    FileOutputStream(destination).use { fileOutput ->
                        val digesting = java.security.DigestOutputStream(fileOutput, digest)
                        val bounded = ExactSizeOutputStream(digesting, source.sizeBytes)
                        response.bodyAsChannel().copyTo(bounded)
                        bounded.verifyExactSize()
                        bounded.flush()
                        fileOutput.fd.sync()
                    }
                }
                check(destination.length() == source.sizeBytes) {
                    "Downloaded ${destination.length()} bytes for ${source.name}; expected ${source.sizeBytes}"
                }
                if (source.lastModifiedMs > 0L) destination.setLastModified(source.lastModifiedMs)
                return digest.digest().toHexString()
            } catch (failure: CancellationException) {
                destination.delete()
                throw failure
            } catch (failure: Throwable) {
                destination.delete()
                lastFailure = failure
                attempt++
                if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                    delay(delayMs)
                    delayMs *= 2
                }
            }
        }
        throw java.io.IOException(
            "Failed to download ${source.name} after $MAX_DOWNLOAD_ATTEMPTS attempts",
            lastFailure,
        )
    }

    override fun close() {
        httpClient.close()
    }

    private class ExactSizeOutputStream(
        delegate: OutputStream,
        private val expectedBytes: Long,
    ) : FilterOutputStream(delegate) {
        private var bytesWritten = 0L

        override fun write(value: Int) {
            requireCapacity(1)
            out.write(value)
            bytesWritten++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            requireCapacity(length)
            out.write(buffer, offset, length)
            bytesWritten += length
        }

        fun verifyExactSize() {
            require(bytesWritten == expectedBytes) {
                "Robot stream ended at $bytesWritten bytes; expected $expectedBytes"
            }
        }

        private fun requireCapacity(additionalBytes: Int) {
            require(additionalBytes >= 0 && bytesWritten <= expectedBytes - additionalBytes) {
                "Robot stream exceeded the declared $expectedBytes-byte size"
            }
        }
    }

    companion object {
        private const val MAX_DOWNLOAD_ATTEMPTS = 3
        private const val INITIAL_RETRY_DELAY_MS = 1_000L
    }
}

/**
 * Imports a complete robot run as one durable transaction boundary:
 * download to unique staging files, archive raw evidence, parse once, persist reports, then mark
 * the content fingerprint imported. Robot-hosted source files are never deleted here.
 */
class RobotLogIngestionService internal constructor(
    private val databaseService: DatabaseService,
    private val logParserService: LogParserService,
    private val autoImportService: AutoImportService,
    private val downloader: RobotLogDownloader,
) : AutoCloseable {
    private val ingestionMutex = Mutex()

    constructor(
        databaseService: DatabaseService,
        logParserService: LogParserService,
        autoImportService: AutoImportService,
    ) : this(databaseService, logParserService, autoImportService, RobotLogDownloader())

    suspend fun importRun(
        robotBaseUrl: String,
        sources: List<RobotLogSource>,
        workspace: WorkspaceConfig,
        teamId: String = workspace.teamId,
        seasonId: String = workspace.seasonId,
        robotId: String = workspace.robotId,
    ): RobotLogImportOutcome = ingestionMutex.withLock {
        withContext(Dispatchers.IO) {
            require(workspace.projectPath.isNotBlank()) { "Workspace path is not configured" }
            require(sources.isNotEmpty()) { "Robot run contains no log files" }
            sources.forEach(::validateRobotLogSource)
            require(sources.map { it.name.lowercase() }.distinct().size == sources.size) {
                "Robot run contains duplicate filenames"
            }
            val totalBytes = sources.fold(0L) { total, source -> Math.addExact(total, source.sizeBytes) }
            require(totalBytes <= MAX_RUN_BYTES) {
                "Robot run is too large (${totalBytes} bytes; maximum is $MAX_RUN_BYTES)"
            }

            val archiveDirectory = File(workspace.projectPath, "logs/imported").canonicalFile
            archiveDirectory.mkdirs()
            require(archiveDirectory.isDirectory) { "Unable to create robot log archive" }
            val stagingDirectory = File(archiveDirectory, ".robot-pull-staging").canonicalFile
            stagingDirectory.mkdirs()
            require(stagingDirectory.toPath().startsWith(archiveDirectory.toPath())) {
                "Robot staging path escaped the workspace archive"
            }

            val stagingFiles = mutableListOf<File>()
            val archivedFiles = mutableListOf<File>()
            val archivedSources = mutableListOf<Pair<File, RobotLogSource>>()
            var parsedSession: Session? = null
            try {
                val downloaded = sources.sortedBy { it.name.lowercase() }.map { source ->
                    val staging = File(stagingDirectory, "${UUID.randomUUID()}.partial").canonicalFile
                    require(staging.toPath().startsWith(stagingDirectory.toPath())) {
                        "Robot staging file escaped the workspace archive"
                    }
                    stagingFiles += staging
                    val sha256 = downloader.download(robotBaseUrl, source, staging)
                    DownloadedRobotLog(source, staging, sha256)
                }
                val runFingerprint = runFingerprint(downloaded)
                val manifest = File(archiveDirectory, ROBOT_PULL_MANIFEST_NAME)
                importedSessionId(manifest, runFingerprint)?.let { sessionId ->
                    val existing = databaseService.getSessionsForWorkspace(teamId, seasonId, robotId)
                        .firstOrNull { it.sessionId == sessionId }
                    if (existing != null) return@withContext RobotLogImportOutcome.AlreadyImported(existing)
                }

                downloaded.forEach { downloadedLog ->
                    val archiveFile = uniqueArchiveFile(
                        archiveDirectory,
                        downloadedLog.source.name,
                        runFingerprint,
                    )
                    moveAtomically(downloadedLog.stagingFile, archiveFile)
                    if (downloadedLog.source.lastModifiedMs > 0L) {
                        archiveFile.setLastModified(downloadedLog.source.lastModifiedMs)
                    }
                    archivedFiles += archiveFile
                    archivedSources += archiveFile to downloadedLog.source
                }

                val session = logParserService.parseLogFiles(
                    files = archivedFiles,
                    teamId = teamId,
                    seasonId = seasonId,
                    robotId = robotId,
                    tags = listOf("robot-pull", "archived-raw"),
                )
                parsedSession = session
                val reports = archivedSources.map { (archive, source) ->
                    val report = logParserService.buildImportReport(archive, session.sessionId)
                        .copy(sourceName = source.name)
                    autoImportService.writeImportReport(archive, report)
                    report
                }
                logParserService.persistExternalImportReports(session.sessionId, reports)
                markImported(manifest, runFingerprint, session.sessionId)
                RobotLogImportOutcome.Imported(session, archivedFiles.toList())
            } catch (failure: CancellationException) {
                parsedSession?.let { session -> runCatching { databaseService.deleteSession(session.sessionId) } }
                throw failure
            } catch (failure: Throwable) {
                parsedSession?.let { session ->
                    runCatching { databaseService.deleteSession(session.sessionId) }
                        .onFailure(failure::addSuppressed)
                }
                quarantineFailedArchives(workspace, archivedFiles, failure)
                throw failure
            } finally {
                stagingFiles.forEach(File::delete)
                stagingDirectory.delete()
            }
        }
    }

    override fun close() {
        downloader.close()
    }

    private fun quarantineFailedArchives(
        workspace: WorkspaceConfig,
        archivedFiles: List<File>,
        failure: Throwable,
    ) {
        if (archivedFiles.isEmpty()) return
        val quarantineDirectory = File(workspace.projectPath, "logs/quarantine")
        quarantineDirectory.mkdirs()
        archivedFiles.forEach { archived ->
            if (!archived.isFile) return@forEach
            runCatching {
                val destination = uniqueFile(quarantineDirectory, archived.name)
                moveAtomically(archived, destination)
                File(archived.parentFile, archived.name + AutoImportService.IMPORT_REPORT_SUFFIX)
                    .takeIf(File::isFile)
                    ?.let { report -> moveAtomically(report, File(quarantineDirectory, destination.name + AutoImportService.IMPORT_REPORT_SUFFIX)) }
                autoImportService.writeImportReport(
                    destination,
                    logParserService.buildRejectedImportReport(destination, failure),
                )
            }.onFailure(failure::addSuppressed)
        }
    }

    private fun uniqueArchiveFile(directory: File, originalName: String, fingerprint: String): File {
        val suffix = supportedSuffix(originalName)
        val stem = originalName.dropLast(suffix.length)
        return uniqueFile(directory, "${stem}_${fingerprint.take(12)}$suffix")
    }

    private fun uniqueFile(directory: File, preferredName: String): File {
        val root = directory.canonicalFile.toPath()
        var candidate = root.resolve(preferredName).normalize()
        var counter = 1
        while (Files.exists(candidate)) {
            val suffix = supportedSuffix(preferredName)
            val stem = preferredName.dropLast(suffix.length)
            candidate = root.resolve("${stem}_$counter$suffix").normalize()
            counter++
        }
        require(candidate.parent == root && candidate.startsWith(root)) { "Archive path escaped its root" }
        return candidate.toFile()
    }

    private fun importedSessionId(manifest: File, fingerprint: String): String? {
        if (!manifest.isFile) return null
        return manifest.useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.trim().split('\t', limit = 2)
                parts.takeIf { it.size == 2 && it[0] == fingerprint }?.get(1)
            }.lastOrNull()
        }
    }

    private fun markImported(manifest: File, fingerprint: String, sessionId: String) {
        manifest.parentFile?.mkdirs()
        FileOutputStream(manifest, true).use { output ->
            output.write("$fingerprint\t$sessionId\n".toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }

    private fun runFingerprint(downloaded: List<DownloadedRobotLog>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        downloaded.sortedBy { it.source.name.lowercase() }.forEach { log ->
            digest.update(log.source.name.lowercase().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(log.source.sizeBytes.toString().toByteArray(Charsets.US_ASCII))
            digest.update(0.toByte())
            digest.update(log.sha256.toByteArray(Charsets.US_ASCII))
            digest.update('\n'.code.toByte())
        }
        return digest.digest().toHexString()
    }

    private data class DownloadedRobotLog(
        val source: RobotLogSource,
        val stagingFile: File,
        val sha256: String,
    )

    companion object {
        private const val ROBOT_PULL_MANIFEST_NAME = ".robot-pull-import-index"
        private const val MAX_RUN_BYTES = 1_073_741_824L
    }
}

internal fun validateRobotLogSource(source: RobotLogSource) {
    require(source.name == File(source.name).name && source.name.length in 1..MAX_ROBOT_LOG_NAME_LENGTH) {
        "Invalid robot log filename"
    }
    require(source.name.none { it.code < 32 } && !source.name.endsWith('.') && !source.name.endsWith(' ')) {
        "Invalid robot log filename"
    }
    require(source.name.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' || it == ' ' }) {
        "Invalid robot log filename"
    }
    supportedSuffix(source.name)
    require(source.sizeBytes in 1..MAX_ROBOT_LOG_BYTES) {
        "Robot log ${source.name} has invalid size ${source.sizeBytes}"
    }
}

private fun supportedSuffix(fileName: String): String {
    val lower = fileName.lowercase()
    return ROBOT_LOG_SUFFIXES.firstOrNull(lower::endsWith)
        ?: throw IllegalArgumentException("Unsupported robot log format: $fileName")
}

private fun moveAtomically(source: File, destination: File) {
    destination.parentFile?.mkdirs()
    try {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), destination.toPath())
    }
}

private fun createRobotLogHttpClient(): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = 30 * 60 * 1_000L
        connectTimeoutMillis = 60 * 1_000L
        socketTimeoutMillis = 30 * 60 * 1_000L
    }
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

private val ROBOT_LOG_SUFFIXES = listOf(".csv.gz", ".jsonl", ".csv")
private const val MAX_ROBOT_LOG_NAME_LENGTH = 180
private const val MAX_ROBOT_LOG_BYTES = 536_870_912L
