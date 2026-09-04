package com.ares.analytics.service

import com.ares.analytics.service.log.*
import com.ares.analytics.shared.AppJsonPretty
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.util.Sha256
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * Automated log file importer watching local directories and remote robot storage systems (FTC ADB / FRC RoboRIO).
 *
 * Polling service executing on `Dispatchers.IO` to continuously discover and import new robot log files.
 * Supports FTC Control Hub log pulling via ADB (`adb pull /sdcard/FIRST/logs/` on port 5555) and FRC RoboRIO SCP pulling (`rio@10.TE.AM.2`).
 * Interoperates with [LogParserService] to automatically ingest `.wpilog`, `.rlog`, `.hoot`, `.dslog`, `.revlog`, `.jsonl`, `.csv`, and `.csv.gz` files.
 *
 * ### Import Pipelines:
 * 1. **Local Disk Watcher**: Scans active workspace project directory for newly created `.jsonl` or `.wpilog` files.
 * 2. **FTC ADB Puller**: Polls connected Android Control Hubs via ADB daemon.
 * 3. **FRC SSH/SCP Puller**: Fetches USB driver station logs from connected RoboRIOs.
 *
 * ### Thread Safety & Performance Guarantees:
 * Executes in a cancellable background coroutine on [Dispatchers.IO]. Pushes notifications to a shared event flow [importNotifications].
 *
 * @param logParserService Central log parser service.
 * @param hootDecoderService Decoder service for CTRE `.hoot` logs.
 * @param adbConnected Current Android Debug Bridge connection state.
 * @param configProvider Lambda supplying current active workspace configuration.
 * @param scope Coroutine scope running background watcher loops.
 *
 * @see LogParserService
 * @see AdbService
 */
class AutoImportService(
    private val logParserService: LogParserService,
    private val hootDecoderService: HootDecoderService,
    private val adbConnected: StateFlow<Boolean>,
    private val configProvider: () -> WorkspaceConfig?,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, exception ->
        println("[AUTO-IMPORT] Unhandled exception in background scope: ${exception.message}")
    }),
    private val scanIntervalMs: Long = 5_000L,
    private val scanCycleOverride: (suspend () -> Unit)? = null
) {
    private val lifecycleMutex = Mutex()
    @Volatile private var job: Job? = null
    private val _importNotifications = MutableSharedFlow<String>(extraBufferCapacity = 100)
    val importNotifications: SharedFlow<String> = _importNotifications.asSharedFlow()

    private var onImportSuccessCallback: (() -> Unit)? = null
    internal data class SourceSnapshot(val size: Long, val modified: Long)
    internal data class ProcessExecution(
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean
    ) {
        val succeeded: Boolean get() = !timedOut && exitCode == 0
    }
    private val sourceObservations = java.util.concurrent.ConcurrentHashMap<String, SourceSnapshot>()
    private val importedFingerprintCaches = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    @Volatile private var discoveredAdbPath: String? = null
    private var importSucceededThisCycle = false

    /** Starts (or replaces) the scanner only after the prior generation has fully stopped. */
    suspend fun start(onImportSuccess: () -> Unit) = withContext(NonCancellable) {
        lifecycleMutex.withLock {
            val previous = job
            job = null
            previous?.cancelAndJoin()

            onImportSuccessCallback = onImportSuccess
            val replacement = scope.launch(start = CoroutineStart.LAZY) { runScannerLoop() }
            job = replacement
            replacement.start()
        }
    }

    /** Cancels and joins the active scanner before returning. */
    suspend fun stop() = withContext(NonCancellable) {
        lifecycleMutex.withLock {
            val previous = job
            job = null
            previous?.cancelAndJoin()
            onImportSuccessCallback = null
        }
    }

    /** Deterministic lifecycle entry point for shutdown owners and tests that must await cleanup. */
    internal suspend fun startAndJoinPrevious(onImportSuccess: () -> Unit) {
        start(onImportSuccess)
    }

    /** Cancels and joins the active scanner before returning. */
    internal suspend fun stopAndJoin() {
        stop()
    }

    internal val scannerActive: Boolean
        get() = job?.isActive == true

    private suspend fun runScannerLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                val override = scanCycleOverride
                if (override != null) {
                    override()
                } else {
                    scanConfiguredSources()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _importNotifications.emit("[AUTO-IMPORT] Error in scan cycle: ${e.message}")
                e.printStackTrace()
            }
            delay(scanIntervalMs)
        }
    }

    private suspend fun scanConfiguredSources() {
        val config = configProvider()
        if (config == null || config.projectPath.isNullOrEmpty()) return

        importSucceededThisCycle = false
        try {
            importLocalLogs(config)
            when (config.league) {
                League.FTC -> {
                    if (adbConnected.value) {
                        // Local logs must not wait for Android tooling discovery. Resolve ADB only
                        // when a robot connection actually requires it, then retain that result for
                        // later scan cycles.
                        val adbPath = discoveredAdbPath ?: findAdbPath().also { discoveredAdbPath = it }
                        importFtcRobotLogs(config, adbPath)
                    }
                }
                League.FRC -> {
                    val host = config.nt4Host ?: getDefaultFrcHost(config.teamId)
                    if (isHostReachable(host)) {
                        importFrcRobotLogs(config, host)
                    }
                }
                League.XRP -> {
                    // XRP logs are tethered or imported via local files/Wi-Fi
                }
            }
        } finally {
            // One scan may discover hundreds of archived robot logs. Refreshing Compose after
            // every file continuously reorders Run History and can make its controls impossible
            // to select. Publish one invalidation after the complete, deterministic batch.
            if (importSucceededThisCycle) onImportSuccessCallback?.invoke()
        }
    }

    private suspend fun importLocalLogs(config: WorkspaceConfig) {
        val logsDirs = listOf(
            File(config.projectPath, "logs"),
            File(config.projectPath, "ftc-app/logs")
        )

        for (dir in logsDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val files = dir.listFiles { _, name ->
                isSupportedLog(name) && !isDriverStationEventCompanionName(name)
            } ?: continue
            for (file in files) {
                if (file.isDirectory) continue

                val sourceId = "local:${file.absoluteFile.toPath().normalize()}"
                val snapshot = SourceSnapshot(file.length(), file.lastModified())
                if (!observeStableSource(sourceId, snapshot)) {
                    continue
                }
                val dsEventsSource = matchingDsEvents(file)?.takeIf(File::isFile)
                val dsEventsSnapshot = dsEventsSource?.let { SourceSnapshot(it.length(), it.lastModified()) }
                if (dsEventsSource != null && dsEventsSnapshot != null) {
                    val eventsSourceId = "local:${dsEventsSource.absoluteFile.toPath().normalize()}"
                    if (!observeStableSource(eventsSourceId, dsEventsSnapshot)) continue
                }

                val archiveDir = File(config.projectPath, "logs/imported")
                archiveDir.mkdirs()
                val manifest = File(archiveDir, IMPORT_MANIFEST_NAME)
                val quarantineManifest = quarantineManifest(config)
                val stagingFile = File(archiveDir, ".${stagingKey(sourceId, snapshot)}.partial")
                val eventsStagingFile = dsEventsSnapshot?.let {
                    File(archiveDir, ".${stagingKey("$sourceId:events", it)}.dsevents.partial")
                }
                var fingerprint: String? = null
                var archivedFile: File? = null

                try {
                    _importNotifications.emit("[AUTO-IMPORT] Found local log: ${file.name}. Importing...")
                    val baseTags = mutableListOf("auto-import")
                    if (file.name.lowercase().startsWith("sim_")) {
                        baseTags.add("simulated")
                    }
                    copyStableLocalFile(file, stagingFile, snapshot)
                    if (dsEventsSource != null && dsEventsSnapshot != null && eventsStagingFile != null) {
                        copyStableLocalFile(dsEventsSource, eventsStagingFile, dsEventsSnapshot)
                    }
                    val stableFingerprint = contentFingerprint(listOfNotNull(stagingFile, eventsStagingFile))
                    fingerprint = stableFingerprint
                    if (isFingerprintImported(manifest, stableFingerprint) ||
                        (isFingerprintImported(quarantineManifest, stableFingerprint) &&
                            !isExplicitRetrySource(config, file))
                    ) {
                        stagingFile.delete()
                        continue
                    }
                    val targetFile = safeArchiveFile(archiveDir, stableFingerprint, file.name)
                    archivedFile = targetFile
                    Files.move(stagingFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    if (eventsStagingFile != null && eventsStagingFile.exists()) {
                        Files.move(
                            eventsStagingFile.toPath(),
                            archivedDsEvents(targetFile).toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    }
                    val result = if (file.name.endsWith(".hoot", ignoreCase = true)) {
                        val imported = hootDecoderService.importHootLog(
                            targetFile,
                            config.teamId,
                            config.seasonId,
                            config.robotId,
                            sourceName = file.name,
                        )
                        imported.session.sessionId to imported.report
                    } else {
                        val imported = logParserService.parseLogFileWithReport(
                            targetFile, config.teamId, config.seasonId, config.robotId,
                            tags = baseTags
                        )
                        imported.session.sessionId to imported.report.copy(sourceName = file.name)
                    }
                    val (sessionId, report) = result

                    logParserService.persistExternalImportReports(sessionId, listOf(report))
                    writeImportReport(targetFile, report)
                    markFingerprintImported(manifest, stableFingerprint)
                    if (!file.delete()) {
                        _importNotifications.emit(
                            "[AUTO-IMPORT] Imported ${file.name}; source could not be removed and will be ignored by fingerprint"
                        )
                    }
                    dsEventsSource?.delete()
                    _importNotifications.emit("[AUTO-IMPORT] Successfully imported ${file.name} (Session ID: ${sessionId.take(8)}...)")

                    importSucceededThisCycle = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val failedFile = archivedFile
                    val failedFingerprint = fingerprint
                    var quarantineRecorded = false
                    if (failedFile != null && failedFingerprint != null && failedFile.exists()) {
                        runCatching { quarantineFailedImport(config, failedFile, failedFingerprint, e, file.name) }
                            .onSuccess { quarantineRecorded = true }
                            .onFailure { e.addSuppressed(it) }
                    }
                    _importNotifications.emit("[AUTO-IMPORT] Failed to import local log ${file.name}: ${e.message}")
                    // A durable quarantine report is the actionable diagnostic. Avoid dumping a
                    // full expected parser stack on every developer launch; retain stacks only
                    // when the quarantine itself failed and evidence may otherwise be lost.
                    if (!quarantineRecorded) e.printStackTrace()
                } finally {
                    stagingFile.delete()
                    eventsStagingFile?.delete()
                }
            }
        }
    }

    private suspend fun importFtcRobotLogs(config: WorkspaceConfig, adbPath: String) {
        val robotDirs = listOf(
            "/sdcard/FIRST/telemetry_logs/",
            "/sdcard/ctre-logs/",
            "/sdcard/FIRST/ctre-logs/"
        )
        val localDestDir = File(config.projectPath, "logs/imported")
        localDestDir.mkdirs()

        for (robotDir in robotDirs) {
            val filesOnRobot = listFilesOnFtcRobot(adbPath, robotDir)
            for (filename in filesOnRobot) {
                val lower = filename.lowercase()
                if (isSupportedLog(lower) && !isDriverStationEventCompanionName(lower)) {
                    val remotePath = "$robotDir$filename"
                    val sourceId = "ftc:$remotePath"
                    val snapshot = getFtcFileSnapshot(adbPath, remotePath) ?: continue
                    if (!observeStableSource(sourceId, snapshot)) continue
                    val manifest = File(localDestDir, IMPORT_MANIFEST_NAME)
                    val quarantineManifest = quarantineManifest(config)

                    // Check if file is still being written to by ARESDataLogger
                    if (isFileInUseOnFtcRobot(adbPath, remotePath)) {
                        continue
                    }
                    val tempLocalFile = File(localDestDir, ".${stagingKey(sourceId, snapshot)}.partial")
                    var fingerprint: String? = null
                    var archivedFile: File? = null

                    try {
                        _importNotifications.emit("[AUTO-IMPORT] Found FTC robot log: $filename. Pulling...")
                        if (pullFileFromFtcRobot(adbPath, remotePath, tempLocalFile)) {
                            val afterPull = getFtcFileSnapshot(adbPath, remotePath)
                            if (afterPull != snapshot || tempLocalFile.length() != snapshot.size) {
                                tempLocalFile.delete()
                                if (afterPull != null) sourceObservations[sourceId] = afterPull
                                continue
                            }
                            val stableFingerprint = contentFingerprint(tempLocalFile)
                            fingerprint = stableFingerprint
                            if (isFingerprintImported(manifest, stableFingerprint) ||
                                isFingerprintImported(quarantineManifest, stableFingerprint)
                            ) {
                                tempLocalFile.delete()
                                continue
                            }
                            val targetFile = safeArchiveFile(localDestDir, stableFingerprint, filename)
                            archivedFile = targetFile
                            Files.move(tempLocalFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            val result = if (lower.endsWith(".hoot")) {
                                val imported = hootDecoderService.importHootLog(
                                    targetFile,
                                    config.teamId,
                                    config.seasonId,
                                    config.robotId,
                                    sourceName = filename,
                                )
                                imported.session.sessionId to imported.report
                            } else {
                                val imported = logParserService.parseLogFileWithReport(
                                    targetFile, config.teamId, config.seasonId, config.robotId,
                                    tags = listOf("auto-import", "robot-log")
                                )
                                imported.session.sessionId to imported.report.copy(sourceName = filename)
                            }
                            val (sessionId, report) = result

                            logParserService.persistExternalImportReports(sessionId, listOf(report))
                            writeImportReport(targetFile, report)
                            markFingerprintImported(manifest, stableFingerprint)
                            // Keep imported file safely in logs/imported archive folder
                            _importNotifications.emit("[AUTO-IMPORT] Successfully imported robot log $filename (Session ID: ${sessionId.take(8)}...)")

                            importSucceededThisCycle = true
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val failedFile = archivedFile
                        val failedFingerprint = fingerprint
                        var quarantineRecorded = false
                        if (failedFile != null && failedFingerprint != null && failedFile.exists()) {
                            runCatching { quarantineFailedImport(config, failedFile, failedFingerprint, e, filename) }
                                .onSuccess { quarantineRecorded = true }
                                .onFailure { e.addSuppressed(it) }
                        }
                        _importNotifications.emit("[AUTO-IMPORT] Failed to import robot log $filename: ${e.message}")
                        if (!quarantineRecorded) e.printStackTrace()
                    } finally {
                        tempLocalFile.delete()
                    }
                }
            }
        }
    }

    private suspend fun importFrcRobotLogs(config: WorkspaceConfig, host: String) {
        val robotDirs = listOf(
            "/home/lvuser/logs/",
            "/media/sda1/logs/"
        )
        val localDestDir = File(config.projectPath, "logs/imported")
        localDestDir.mkdirs()

        for (robotDir in robotDirs) {
            val filesOnRobot = listFilesOnFrcRobot(host, robotDir)
            val remoteNamesByLowercase = filesOnRobot.associateBy { it.lowercase() }
            for (filename in filesOnRobot) {
                val lower = filename.lowercase()
                if (isSupportedLog(lower) && !isDriverStationEventCompanionName(lower)) {
                    val remotePath = "$robotDir$filename"
                    val sourceId = "frc:$host:$remotePath"
                    val snapshot = getFrcFileSnapshot(host, remotePath) ?: continue
                    if (!observeStableSource(sourceId, snapshot)) continue
                    val eventsFilename = filename.takeIf { lower.endsWith(".dslog") }
                        ?.let { it.substringBeforeLast('.') + ".dsevents" }
                        ?.let { remoteNamesByLowercase[it.lowercase()] }
                    val eventsRemotePath = eventsFilename?.let { "$robotDir$it" }
                    val eventsSourceId = eventsRemotePath?.let { "frc:$host:$it" }
                    val eventsSnapshot = eventsRemotePath?.let { getFrcFileSnapshot(host, it) }
                    if (eventsRemotePath != null && eventsSnapshot == null) continue
                    if (eventsSourceId != null && eventsSnapshot != null &&
                        !observeStableSource(eventsSourceId, eventsSnapshot)
                    ) continue
                    val manifest = File(localDestDir, IMPORT_MANIFEST_NAME)
                    val quarantineManifest = quarantineManifest(config)

                    // Check if file is still being written to by DataLogManager
                    if (isFileInUseOnFrcRobot(host, remotePath) ||
                        (eventsRemotePath != null && isFileInUseOnFrcRobot(host, eventsRemotePath))
                    ) {
                        continue
                    }
                    val tempLocalFile = File(localDestDir, ".${stagingKey(sourceId, snapshot)}.partial")
                    val tempEventsFile = eventsSnapshot?.let {
                        File(localDestDir, ".${stagingKey("$sourceId:events", it)}.dsevents.partial")
                    }
                    var fingerprint: String? = null
                    var archivedFile: File? = null

                    try {
                        _importNotifications.emit("[AUTO-IMPORT] Found FRC robot log: $filename. Pulling...")
                        if (pullFileFromFrcRobot(host, remotePath, tempLocalFile)) {
                            val afterPull = getFrcFileSnapshot(host, remotePath)
                            if (afterPull != snapshot || tempLocalFile.length() != snapshot.size) {
                                tempLocalFile.delete()
                                if (afterPull != null) sourceObservations[sourceId] = afterPull
                                continue
                            }
                            if (eventsRemotePath != null && eventsSnapshot != null && tempEventsFile != null) {
                                check(pullFileFromFrcRobot(host, eventsRemotePath, tempEventsFile)) {
                                    "Could not pull matching Driver Station events file"
                                }
                                val eventsAfterPull = getFrcFileSnapshot(host, eventsRemotePath)
                                check(eventsAfterPull == eventsSnapshot && tempEventsFile.length() == eventsSnapshot.size) {
                                    "Driver Station events file changed during transfer"
                                }
                            }
                            val stableFingerprint = contentFingerprint(listOfNotNull(tempLocalFile, tempEventsFile))
                            fingerprint = stableFingerprint
                            if (isFingerprintImported(manifest, stableFingerprint) ||
                                isFingerprintImported(quarantineManifest, stableFingerprint)
                            ) {
                                tempLocalFile.delete()
                                continue
                            }
                            val targetFile = safeArchiveFile(localDestDir, stableFingerprint, filename)
                            archivedFile = targetFile
                            Files.move(tempLocalFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            if (tempEventsFile != null && tempEventsFile.exists()) {
                                Files.move(
                                    tempEventsFile.toPath(),
                                    archivedDsEvents(targetFile).toPath(),
                                    StandardCopyOption.REPLACE_EXISTING
                                )
                            }
                            val result = if (lower.endsWith(".hoot")) {
                                val imported = hootDecoderService.importHootLog(
                                    targetFile,
                                    config.teamId,
                                    config.seasonId,
                                    config.robotId,
                                    sourceName = filename,
                                )
                                imported.session.sessionId to imported.report
                            } else {
                                val imported = logParserService.parseLogFileWithReport(
                                    targetFile, config.teamId, config.seasonId, config.robotId,
                                    tags = listOf("auto-import", "robot-log")
                                )
                                imported.session.sessionId to imported.report.copy(sourceName = filename)
                            }
                            val (sessionId, report) = result

                            logParserService.persistExternalImportReports(sessionId, listOf(report))
                            writeImportReport(targetFile, report)
                            markFingerprintImported(manifest, stableFingerprint)
                            // Keep imported file safely in logs/imported archive folder
                            _importNotifications.emit("[AUTO-IMPORT] Successfully imported RoboRIO log $filename (Session ID: ${sessionId.take(8)}...)")

                            importSucceededThisCycle = true
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val failedFile = archivedFile
                        val failedFingerprint = fingerprint
                        var quarantineRecorded = false
                        if (failedFile != null && failedFingerprint != null && failedFile.exists()) {
                            runCatching { quarantineFailedImport(config, failedFile, failedFingerprint, e, filename) }
                                .onSuccess { quarantineRecorded = true }
                                .onFailure { e.addSuppressed(it) }
                        }
                        _importNotifications.emit("[AUTO-IMPORT] Failed to import RoboRIO log $filename: ${e.message}")
                        if (!quarantineRecorded) e.printStackTrace()
                    } finally {
                        tempLocalFile.delete()
                        tempEventsFile?.delete()
                    }
                }
            }
        }
    }

    // --- FTC ADB Helper Methods ---

    private suspend fun listFilesOnFtcRobot(adbPath: String, directory: String): List<String> {
        val result = runProcessOrNull(
            ProcessBuilder(adbPath, "shell", "ls", directory),
            LIST_PROCESS_TIMEOUT_MS
        ) ?: return emptyList()
        if (!result.succeeded) return emptyList()
        return parseRemoteFileList(result.stdout)
    }

    private suspend fun pullFileFromFtcRobot(adbPath: String, remotePath: String, localFile: File): Boolean {
        return runProcessOrNull(
            ProcessBuilder(adbPath, "pull", remotePath, localFile.absolutePath),
            TRANSFER_PROCESS_TIMEOUT_MS
        )?.succeeded == true
    }

    private suspend fun getFtcFileSnapshot(adbPath: String, remotePath: String): SourceSnapshot? {
        return readSnapshotFromProcess(ProcessBuilder(adbPath, "shell", "stat", "-c", "%s:%Y", remotePath))
    }

    private suspend fun isFileInUseOnFtcRobot(adbPath: String, remotePath: String): Boolean {
        val result = runProcessOrNull(
            ProcessBuilder(adbPath, "shell", "lsof", remotePath),
            LIST_PROCESS_TIMEOUT_MS
        ) ?: return false
        // Only treat an explicit lsof match on the remote path as "in use". A usage banner from a
        // missing binary must not block every import.
        return result.stdout.contains(remotePath)
    }

    // --- FRC SSH/SCP Helper Methods ---

    private suspend fun listFilesOnFrcRobot(host: String, directory: String): List<String> {
        val result = runProcessOrNull(
            ProcessBuilder(
                listOf("ssh") + sshOptions(3) + listOf("lvuser@$host", "ls ${shellQuote(directory)}")
            ),
            LIST_PROCESS_TIMEOUT_MS
        ) ?: return emptyList()
        if (!result.succeeded) return emptyList()
        return parseRemoteFileList(result.stdout)
    }

    private suspend fun pullFileFromFrcRobot(host: String, remotePath: String, localFile: File): Boolean {
        return runProcessOrNull(
            ProcessBuilder(
                listOf("scp") + sshOptions(5) +
                    listOf("lvuser@$host:${shellQuote(remotePath)}", localFile.absolutePath)
            ),
            TRANSFER_PROCESS_TIMEOUT_MS
        )?.succeeded == true
    }

    private suspend fun getFrcFileSnapshot(host: String, remotePath: String): SourceSnapshot? {
        return readSnapshotFromProcess(
            ProcessBuilder(
                listOf("ssh") + sshOptions(3) +
                    listOf("lvuser@$host", "stat -c '%s:%Y' -- ${shellQuote(remotePath)}")
            )
        )
    }

    private suspend fun isFileInUseOnFrcRobot(host: String, remotePath: String): Boolean {
        return runProcessOrNull(
            ProcessBuilder(
                listOf("ssh") + sshOptions(3) +
                    listOf("lvuser@$host", "fuser ${shellQuote(remotePath)}")
            ),
            LIST_PROCESS_TIMEOUT_MS
        )?.succeeded == true // fuser returns 0 if any process is using the file
    }

    private suspend fun isHostReachable(host: String): Boolean {
        val processBuilder = ProcessBuilder(pingCommand(host, System.getProperty("os.name")))
        return runProcessOrNull(processBuilder, PING_PROCESS_TIMEOUT_MS)?.succeeded == true
    }

    private fun parseRemoteFileList(output: String): List<String> = output.split("\n", "\r")
        .map { it.trim() }
        .filter {
            it.isNotEmpty() &&
                !it.contains("No such file") &&
                !it.contains("Permission denied") &&
                !it.contains("ls:")
        }

    // --- General Utility Methods ---

    internal fun observeStableSource(sourceId: String, snapshot: SourceSnapshot): Boolean {
        return sourceObservations.put(sourceId, snapshot) == snapshot
    }

    private fun stagingKey(sourceId: String, snapshot: SourceSnapshot): String {
        return Sha256.hex("$sourceId\u0000${snapshot.size}\u0000${snapshot.modified}")
    }

    /** Hashes verified local copies, never mutable source metadata, for durable deduplication. */
    internal fun contentFingerprint(stableFile: File): String {
        return Sha256.fileHex(stableFile)
    }

    internal fun contentFingerprint(stableFiles: List<File>): String {
        require(stableFiles.isNotEmpty()) { "At least one stable file is required" }
        if (stableFiles.size == 1) return contentFingerprint(stableFiles.single())

        return Sha256.compositeHex {
            val buffer = ByteArray(CONTENT_HASH_BUFFER_BYTES)
            for (stableFile in stableFiles) {
                update(stableFile.length().toString().toByteArray(Charsets.US_ASCII))
                update(0.toByte())
                stableFile.inputStream().use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        update(buffer, 0, count)
                    }
                }
            }
        }
    }

    private fun matchingDsEvents(dslog: File): File? {
        if (!dslog.name.endsWith(".dslog", ignoreCase = true)) return null
        val baseName = dslog.name.substringBeforeLast('.')
        return dslog.parentFile?.listFiles()?.firstOrNull { candidate ->
            candidate.isFile &&
                candidate.name.endsWith(".dsevents", ignoreCase = true) &&
                candidate.name.substringBeforeLast('.').equals(baseName, ignoreCase = true)
        } ?: File(dslog.parentFile, "$baseName.dsevents")
    }

    private fun archivedDsEvents(dslog: File): File =
        File(dslog.parentFile, dslog.name.substringBeforeLast('.') + ".dsevents")

    internal fun safeArchiveFile(directory: File, fingerprint: String, sourceName: String): File {
        val basename = sourceName.substringAfterLast('/').substringAfterLast('\\').trim()
        require(basename.isNotEmpty() && basename != "." && basename != "..") {
            "Invalid log filename"
        }
        val sanitized = buildString(basename.length) {
            basename.forEach { character ->
                append(
                    when {
                        character.isLetterOrDigit() -> character
                        character == '.' || character == '_' || character == '-' || character == ' ' -> character
                        else -> '_'
                    }
                )
            }
        }.trim().take(MAX_ARCHIVE_BASENAME_LENGTH)
        require(sanitized.isNotEmpty() && isSupportedLog(sanitized)) { "Unsupported log filename" }

        val root = directory.toPath().toAbsolutePath().normalize()
        val target = root.resolve("${fingerprint.take(12)}_$sanitized").normalize()
        require(target.parent == root && target.startsWith(root)) { "Log archive path escaped its root" }
        return target.toFile()
    }

    private fun importedFingerprints(manifest: File): MutableSet<String> {
        return importedFingerprintCaches.computeIfAbsent(manifest.absolutePath) {
            java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {
                if (manifest.exists()) {
                    manifest.useLines { lines ->
                        lines.map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
                    }
                }
            }
        }
    }

    internal fun isFingerprintImported(manifest: File, fingerprint: String): Boolean {
        return fingerprint in importedFingerprints(manifest)
    }

    internal fun markFingerprintImported(manifest: File, fingerprint: String) {
        val fingerprints = importedFingerprints(manifest)
        if (!fingerprints.add(fingerprint)) return
        manifest.parentFile?.mkdirs()
        try {
            FileOutputStream(manifest, true).use { output ->
                output.write((fingerprint + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
        } catch (e: Exception) {
            fingerprints.remove(fingerprint)
            throw e
        }
    }

    internal fun quarantineFailedImport(
        config: WorkspaceConfig,
        archivedFile: File,
        fingerprint: String,
        failure: Throwable,
        sourceName: String = archivedFile.name
    ): File {
        val quarantineDir = File(config.projectPath, "logs/quarantine")
        quarantineDir.mkdirs()
        val quarantinedFile = File(quarantineDir, archivedFile.name)
        Files.move(archivedFile.toPath(), quarantinedFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val archivedEvents = matchingDsEvents(archivedFile)
        if (archivedEvents?.isFile == true) {
            Files.move(
                archivedEvents.toPath(),
                archivedDsEvents(quarantinedFile).toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
        val report = logParserService.buildRejectedImportReport(quarantinedFile, failure)
            .copy(sourceName = sourceName)
        writeImportReport(quarantinedFile, report)
        markFingerprintImported(File(quarantineDir, QUARANTINE_MANIFEST_NAME), fingerprint)
        return quarantinedFile
    }

    internal fun writeImportReport(logFile: File, report: ImportReport): File {
        val reportFile = File(logFile.parentFile, logFile.name + IMPORT_REPORT_SUFFIX)
        val temporaryFile = File(reportFile.parentFile, ".${reportFile.name}.tmp")
        temporaryFile.writeText(AppJsonPretty.encodeToString(report))
        try {
            Files.move(
                temporaryFile.toPath(),
                reportFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporaryFile.toPath(), reportFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return reportFile
    }

    private fun quarantineManifest(config: WorkspaceConfig): File =
        File(config.projectPath, "logs/quarantine/$QUARANTINE_MANIFEST_NAME")

    private fun isSupportedLog(name: String): Boolean {
        val lower = name.lowercase()
        return SUPPORTED_EXTENSIONS.any(lower::endsWith)
    }

    /** Only ImportArchiveService's direct-child retry files may bypass quarantine dedup once. */
    internal fun isExplicitRetrySource(config: WorkspaceConfig, file: File): Boolean {
        val logsRoot = File(config.projectPath, "logs").toPath().toAbsolutePath().normalize()
        val candidate = file.toPath().toAbsolutePath().normalize()
        return candidate.parent == logsRoot && file.name.startsWith("retry_") && isSupportedLog(file.name)
    }

    private fun copyStableLocalFile(source: File, destination: File, expected: SourceSnapshot) {
        destination.parentFile?.mkdirs()
        Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val afterCopy = SourceSnapshot(source.length(), source.lastModified())
        if (afterCopy != expected || destination.length() != expected.size) {
            destination.delete()
            throw java.io.IOException("Log changed while it was being copied")
        }
    }

    private fun sshOptions(connectTimeoutSeconds: Int): List<String> = listOf(
        "-o", "StrictHostKeyChecking=yes",
        "-o", "ConnectTimeout=$connectTimeoutSeconds",
        "-o", "BatchMode=yes"
    )

    private suspend fun readSnapshotFromProcess(processBuilder: ProcessBuilder): SourceSnapshot? {
        val result = runProcessOrNull(processBuilder, LIST_PROCESS_TIMEOUT_MS) ?: return null
        if (!result.succeeded) return null
        val parts = result.stdout.lineSequence().firstOrNull()?.trim()?.split(':') ?: return null
        return if (parts.size != 2) {
            null
        } else {
            runCatching { SourceSnapshot(parts[0].toLong(), parts[1].toLong()) }.getOrNull()
        }
    }

    internal suspend fun executeProcessForTest(command: List<String>, timeoutMs: Long): ProcessExecution {
        require(command.isNotEmpty()) { "Process command must not be empty" }
        return executeProcess(ProcessBuilder(command), timeoutMs)
    }

    private suspend fun runProcessOrNull(
        processBuilder: ProcessBuilder,
        timeoutMs: Long
    ): ProcessExecution? = try {
        executeProcess(processBuilder, timeoutMs)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    /**
     * Runs a helper process with bounded capture and cancellation-safe termination.
     *
     * Both streams are drained concurrently so a verbose child cannot fill an OS pipe. Only a
     * bounded prefix is retained. Timeout/cancellation forcibly kills the child and closes its
     * streams before any reader result is awaited, preventing `readText()` from hanging forever.
     */
    private suspend fun executeProcess(
        processBuilder: ProcessBuilder,
        timeoutMs: Long
    ): ProcessExecution = coroutineScope {
        require(timeoutMs > 0L) { "Process timeout must be positive" }
        // Do not let prompt dispatcher cancellation discard a process handle that the OS already
        // created. The first cancellable operation below will enter the cleanup path and kill it.
        val process = withContext(NonCancellable + Dispatchers.IO) { processBuilder.start() }
        runCatching { process.outputStream.close() }
        val stdoutReader = async(Dispatchers.IO) { readBounded(process.inputStream, MAX_PROCESS_OUTPUT_BYTES) }
        val stderrReader = async(Dispatchers.IO) { readBounded(process.errorStream, MAX_PROCESS_OUTPUT_BYTES) }
        var timedOut = false

        try {
            val completed = withTimeoutOrNull(timeoutMs) {
                runInterruptible(Dispatchers.IO) { process.waitFor() }
                true
            } == true
            if (!completed) {
                timedOut = true
                terminateProcess(process)
            }

            val stdout = stdoutReader.await()
            val stderr = stderrReader.await()
            val exitCode = runCatching { process.exitValue() }.getOrNull()
            ProcessExecution(exitCode, stdout, stderr, timedOut)
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) { terminateProcess(process) }
            throw cancellation
        } finally {
            if (process.isAlive) {
                withContext(NonCancellable) { terminateProcess(process) }
            }
        }
    }

    private suspend fun terminateProcess(process: Process) {
        withContext(NonCancellable + Dispatchers.IO) {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.waitFor(PROCESS_KILL_GRACE_MS, TimeUnit.MILLISECONDS) }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }

    private fun readBounded(input: InputStream, maximumBytes: Int): String {
        val retained = ByteArrayOutputStream(minOf(maximumBytes, 8_192))
        val buffer = ByteArray(8_192)
        try {
            input.use { stream ->
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    val remaining = maximumBytes - retained.size()
                    if (remaining > 0) retained.write(buffer, 0, minOf(count, remaining))
                }
            }
        } catch (_: java.io.IOException) {
            // Process termination closes streams to unblock readers; retain the prefix read so far.
        }
        return retained.toString(Charsets.UTF_8.name())
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private suspend fun findAdbPath(): String {
        try {
            val result = executeProcess(ProcessBuilder("adb", "--version"), ADB_PROBE_TIMEOUT_MS)
            if (result.succeeded) {
                return "adb"
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Ignore and fall through to fixed SDK paths.
        }
        val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        if (!androidHome.isNullOrEmpty()) {
            val exe = if (System.getProperty("os.name").contains("win", ignoreCase = true)) {
                File(androidHome, "platform-tools/adb.exe")
            } else {
                File(androidHome, "platform-tools/adb")
            }
            if (exe.exists() && exe.canExecute()) {
                return exe.absolutePath
            }
        }
        val userHome = System.getProperty("user.home")
        val defaultPaths = listOf(
            File(userHome, "AppData/Local/Android/Sdk/platform-tools/adb.exe"),
            File(userHome, "Library/Android/sdk/platform-tools/adb"),
            File("/usr/bin/adb"),
            File("/usr/local/bin/adb")
        )
        for (file in defaultPaths) {
            if (file.exists() && file.canExecute()) {
                return file.absolutePath
            }
        }

        return "adb"
    }

    private fun getDefaultFrcHost(teamId: String): String {
        val teamNumber = teamId.filter(Char::isDigit).toIntOrNull()
        return if (teamNumber != null && teamNumber in 1..25_599) {
            val te = teamNumber / 100
            val am = teamNumber % 100
            "10.$te.$am.2"
        } else {
            "10.0.0.2"
        }
    }

    companion object {
        internal const val IMPORT_MANIFEST_NAME = ".auto-import-index"
        internal const val QUARANTINE_MANIFEST_NAME = ".auto-import-quarantine-index"
        internal const val IMPORT_REPORT_SUFFIX = ".import-report.json"
        internal const val MAX_ARCHIVE_BASENAME_LENGTH = 160
        internal const val MAX_PROCESS_OUTPUT_BYTES = 64 * 1024
        private const val CONTENT_HASH_BUFFER_BYTES = 64 * 1024
        private const val ADB_PROBE_TIMEOUT_MS = 2_000L
        private const val PING_PROCESS_TIMEOUT_MS = 2_000L
        private const val LIST_PROCESS_TIMEOUT_MS = 10_000L
        private const val TRANSFER_PROCESS_TIMEOUT_MS = 60_000L
        private const val PROCESS_KILL_GRACE_MS = 1_000L
        internal val SUPPORTED_EXTENSIONS = setOf(
            ".wpilog", ".wpilogxz", ".jsonl", ".csv.gz", ".csv", ".parquet", ".hoot",
            ".dslog", ".rlog", ".revlog", ".log"
        )
    }
}

internal fun pingCommand(host: String, osName: String): List<String> = when {
    osName.contains("win", ignoreCase = true) -> listOf("ping", "-n", "1", "-w", "1000", host)
    osName.contains("mac", ignoreCase = true) -> listOf("ping", "-c", "1", "-W", "1000", host)
    else -> listOf("ping", "-c", "1", "-W", "1", host)
}
