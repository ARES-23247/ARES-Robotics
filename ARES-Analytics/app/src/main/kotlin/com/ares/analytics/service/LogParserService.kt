package com.ares.analytics.service

import com.ares.analytics.service.log.JsonlLogDecoder
import com.ares.analytics.service.log.WpiLogDecoder
import com.ares.analytics.service.log.CsvLogDecoder
import com.ares.analytics.service.log.ParquetLogDecoder
import com.ares.analytics.shared.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * High-level unified log parser and session ingestion service.
 *
 * Serves as the primary entry point for importing diverse robot log file formats into DuckDB telemetry storage.
 * Auto-detects log file types based on extension or magic bytes, dispatching parsing to specialized decoders
 * ([WpiLogDecoder], [JsonlLogDecoder], [CsvLogDecoder]), buffering frames through [FrameBatcher], and calculating
 * session summary KPIs via [SummaryEngineService].
 *
 * ### Supported Formats:
 * - `.wpilog` / `.rlog` / `.revlog`: WPILib, AdvantageKit, REV binary logs
 * - `.jsonl`: Line-delimited JSON Redux action and telemetry streams
 * - `.csv` / `.csv.gz`: Wide or long tabular CSV log recordings
 * - `.parquet`: Native columnar telemetry backups with timestamp/key/value columns
 *
 * ### Thread Safety & Performance Guarantees:
 * All parsing operations execute asynchronously on `Dispatchers.IO`. Utilizes bounded [FrameBatcher] memory buffers
 * to guarantee zero heap exhaustion during large file imports.
 *
 * @param databaseService Primary DuckDB database management service.
 * @param summaryEngineService Service for generating aggregate session KPI summaries post-ingest.
 *
 * @see FrameBatcher
 * @see SummaryEngineService
 */
class LogParserService(
    private val databaseService: DatabaseService,
    private val summaryEngineService: SummaryEngineService
) {
    private val jsonlDecoder = JsonlLogDecoder(databaseService)
    private val wpiLogDecoder = WpiLogDecoder()
    private val csvLogDecoder = CsvLogDecoder(databaseService)
    private val parquetLogDecoder = ParquetLogDecoder(databaseService)

    suspend fun parseLogFile(
        file: File,
        teamId: String,
        seasonId: String,
        robotId: String,
        matchNumber: Int? = null,
        allianceColor: String? = null,
        tags: List<String> = emptyList()
    ): Session = parseLogFileWithReport(
        file, teamId, seasonId, robotId, matchNumber, allianceColor, tags
    ).session

    suspend fun parseLogFileWithReport(
        file: File,
        teamId: String,
        seasonId: String,
        robotId: String,
        matchNumber: Int? = null,
        allianceColor: String? = null,
        tags: List<String> = emptyList()
    ): LogImportResult = withContext(Dispatchers.IO) {
        val primaryFile = canonicalLogImportFiles(listOf(file)).single()
        val sourceSize = primaryFile.length()
        val sourceSha256 = sha256(primaryFile)
        databaseService.findCompletedSessionBySourceHashes(
            teamId = teamId,
            seasonId = seasonId,
            robotId = robotId,
            sourceHashes = setOf(sourceSha256),
        )?.let { existing ->
            val report = databaseService.getSessionImportReports(existing.sessionId)
                .firstOrNull { it.sourceSha256 == sourceSha256 }
                ?: buildImportReport(primaryFile, existing.sessionId, sourceSize, sourceSha256)
            return@withContext LogImportResult(existing, report, wasAlreadyImported = true)
        }
        val session = parseLogFileInternal(
            primaryFile, teamId, seasonId, robotId, matchNumber, allianceColor, tags
        )
        val report = buildImportReport(primaryFile, session.sessionId, sourceSize, sourceSha256)
        if (report.acceptedRecords == 0L) {
            val failure = IllegalArgumentException("Log contained no importable records: ${primaryFile.name}")
            cleanupFailedImport(session.sessionId, failure)
            throw failure
        }
        try {
            databaseService.completeSessionImport(session, listOf(report))
            LogImportResult(session, report)
        } catch (failure: Throwable) {
            cleanupFailedImport(session.sessionId, failure)
            throw failure
        }
    }

    private suspend fun parseLogFileInternal(
        file: File,
        teamId: String,
        seasonId: String,
        robotId: String,
        matchNumber: Int?,
        allianceColor: String?,
        tags: List<String>
    ): Session = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString()
        val createdAt = file.lastModified()
        val session = Session(
            sessionId = sessionId,
            teamId = teamId,
            seasonId = seasonId,
            robotId = robotId,
            createdAt = createdAt,
            matchNumber = matchNumber,
            allianceColor = allianceColor,
            tags = tags
        )
        val batcher = FrameBatcher(databaseService)
        val lowerName = file.name.lowercase()

        try {
            databaseService.insertImportSession(session)
            when {
            lowerName.endsWith(".wpilog") -> {
                wpiLogDecoder.parseWpiLog(file, sessionId, batcher)
            }
            lowerName.endsWith(".wpilogxz") -> {
                val tempWpiFile = File.createTempFile("wpilog_", ".wpilog")
                try {
                    FileInputStream(file).use { fis ->
                        expandWpiLogXz(file, fis, tempWpiFile)
                    }
                    wpiLogDecoder.parseWpiLog(tempWpiFile, sessionId, batcher)
                } finally {
                    tempWpiFile.delete()
                }
            }
            lowerName.endsWith(".jsonl") -> {
                if (isActionLogName(lowerName)) {
                    val actionMeta = jsonlDecoder.parseActionLogJsonl(file, sessionId)
                    if (actionMeta != null) {
                        val enrichedSession = session.copy(
                            durationMs = actionMeta.durationMs,
                            matchNumber = matchNumber ?: actionMeta.matchNumber,
                            allianceColor = allianceColor ?: actionMeta.alliance,
                            tags = tags + "action-log"
                        )
                        val summary = summaryEngineService.generateSummary(enrichedSession)
                        return@withContext enrichedSession.copy(tags = summary.tags)
                    }
                } else {
                    jsonlDecoder.parseJsonlLog(file, sessionId, batcher)
                }
            }
            lowerName.endsWith(".csv.gz") -> {
                csvLogDecoder.parseCsvLogStreaming(file, sessionId, batcher)
            }
            lowerName.endsWith(".csv") -> {
                csvLogDecoder.parseCsvLogNative(file, sessionId)
                val range = databaseService.getSessionTimestampRange(sessionId)
                if (range != null) {
                    val finalSession = session.copy(durationMs = range.second - range.first)
                    val summary = summaryEngineService.generateSummary(finalSession)
                    return@withContext finalSession.copy(tags = summary.tags)
                } else {
                    val summary = summaryEngineService.generateSummary(session)
                    return@withContext session.copy(tags = summary.tags)
                }
            }
            lowerName.endsWith(".parquet") -> {
                val imported = parquetLogDecoder.parseParquetLog(file, sessionId)
                val duration = if (imported.minTimestampMs != null && imported.maxTimestampMs != null) {
                    imported.maxTimestampMs - imported.minTimestampMs
                } else {
                    0L
                }
                val finalSession = session.copy(durationMs = duration)
                val summary = summaryEngineService.generateSummary(finalSession)
                return@withContext finalSession.copy(tags = summary.tags)
            }
            lowerName.endsWith(".dslog") || lowerName.endsWith(".dsevents") -> {
                val targetFile = if (lowerName.endsWith(".dsevents")) {
                    File(file.parentFile, file.nameWithoutExtension + ".dslog")
                } else {
                    file
                }
                com.ares.analytics.service.log.DSLogDecoderService(databaseService).decode(targetFile, sessionId, batcher)
            }
            lowerName.endsWith(".log") -> {
                com.ares.analytics.service.log.RoadRunnerDecoderService().decode(file, sessionId, batcher)
            }
            lowerName.endsWith(".rlog") -> {
                com.ares.analytics.service.log.RlogDecoderService().decode(file, sessionId, batcher)
            }
            lowerName.endsWith(".revlog") -> {
                com.ares.analytics.service.log.RevlogDecoderService(this@LogParserService).decode(file, sessionId, batcher)
            }
            else -> {
                throw IllegalArgumentException("Unsupported log file format: ${file.name}")
            }
        }

            batcher.flush()
            val finalSession = if (batcher.frameCount > 0) {
                val duration = batcher.maxTimestamp - batcher.minTimestamp
                session.copy(durationMs = duration)
            } else {
                session
            }
            val summary = summaryEngineService.generateSummary(finalSession)
            return@withContext finalSession.copy(tags = summary.tags)
        } catch (failure: Throwable) {
            cleanupFailedImport(sessionId, failure)
            throw failure
        }
    }

    internal suspend fun buildImportReport(
        file: File,
        sessionId: String,
        sourceSizeBytes: Long = file.length(),
        sourceSha256: String = sha256(file),
        decoderOverride: String? = null
    ): ImportReport {
        val telemetryRecords = databaseService.countTelemetryFrames(sessionId)
        val actions = databaseService.getActionsForSession(sessionId)
        val actionRecords = actions.size.toLong()
        val acceptedRecords = telemetryRecords + actionRecords
        val telemetryRange = databaseService.getSessionTimestampRange(sessionId)
        val minTimestampMs = telemetryRange?.first ?: actions.minOfOrNull { it.timestampMs }
        val maxTimestampMs = telemetryRange?.second ?: actions.maxOfOrNull { it.timestampMs }
        val warnings = buildList {
            if (telemetryRecords == 0L && actionRecords > 0L) add("Action log contains no telemetry frames")
        }
        return ImportReport(
            sourceName = file.name,
            sourceSha256 = sourceSha256,
            sourceSizeBytes = sourceSizeBytes,
            decoder = decoderOverride ?: decoderName(file),
            status = if (acceptedRecords > 0L) ImportStatus.SUCCESS else ImportStatus.REJECTED,
            sessionId = sessionId,
            acceptedRecords = acceptedRecords,
            detectedTopics = databaseService.getDistinctTelemetryKeys(sessionId),
            minTimestampMs = minTimestampMs,
            maxTimestampMs = maxTimestampMs,
            warnings = warnings
        )
    }

    internal fun buildRejectedImportReport(
        file: File,
        error: Throwable,
        decoderOverride: String? = null
    ): ImportReport = ImportReport(
        sourceName = file.name,
        sourceSha256 = sha256(file),
        sourceSizeBytes = file.length(),
        decoder = decoderOverride ?: decoderName(file),
        status = ImportStatus.REJECTED,
        error = error.message ?: error::class.simpleName ?: "Import failed"
    )

    suspend fun parseLogFiles(
        files: List<File>,
        teamId: String,
        seasonId: String,
        robotId: String,
        matchNumber: Int? = null,
        allianceColor: String? = null,
        tags: List<String> = emptyList()
    ): Session = withContext(Dispatchers.IO) {
        val primaryFiles = canonicalLogImportFiles(files)
        if (primaryFiles.size == 1) {
            return@withContext parseLogFile(primaryFiles.first(), teamId, seasonId, robotId, matchNumber, allianceColor, tags)
        }
        val sourceEvidence = primaryFiles.map { source ->
            SourceEvidence(source, source.length(), sha256(source))
        }
        databaseService.findCompletedSessionBySourceHashes(
            teamId = teamId,
            seasonId = seasonId,
            robotId = robotId,
            sourceHashes = sourceEvidence.mapTo(linkedSetOf(), SourceEvidence::sha256),
        )?.let { existing -> return@withContext existing }
        val sessionId = UUID.randomUUID().toString()
        val createdAt = primaryFiles.first().lastModified()
        var currentMatchNumber = matchNumber
        var currentAlliance = allianceColor
        var currentTags = tags
        val batcher = FrameBatcher(databaseService, keyTransform = { key ->
            key.removePrefix("/")
        })
        val stagingSession = Session(
            sessionId = sessionId,
            teamId = teamId,
            seasonId = seasonId,
            robotId = robotId,
            createdAt = createdAt,
            matchNumber = matchNumber,
            allianceColor = allianceColor,
            tags = tags,
        )
        try {
            databaseService.insertImportSession(stagingSession)
            primaryFiles.forEach { file ->
            val lowerName = file.name.lowercase()
            when {
                lowerName.endsWith(".wpilog") -> wpiLogDecoder.parseWpiLog(file, sessionId, batcher)
                lowerName.endsWith(".wpilogxz") -> {
                    val tempWpiFile = File.createTempFile("wpilog_", ".wpilog")
                    try {
                        FileInputStream(file).use { fis ->
                            expandWpiLogXz(file, fis, tempWpiFile)
                        }
                        wpiLogDecoder.parseWpiLog(tempWpiFile, sessionId, batcher)
                    } finally {
                        tempWpiFile.delete()
                    }
                }
                lowerName.endsWith(".jsonl") -> {
                    if (isActionLogName(lowerName)) {
                        val actionMeta = jsonlDecoder.parseActionLogJsonl(file, sessionId)
                        if (actionMeta != null) {
                            currentMatchNumber = currentMatchNumber ?: actionMeta.matchNumber
                            currentAlliance = currentAlliance ?: actionMeta.alliance
                            if (!currentTags.contains("action-log")) {
                                currentTags = currentTags + "action-log"
                            }
                        }
                    } else {
                        jsonlDecoder.parseJsonlLog(file, sessionId, batcher)
                    }
                }
                lowerName.endsWith(".csv.gz") -> {
                    csvLogDecoder.parseCsvLogStreaming(file, sessionId, batcher)
                }
                lowerName.endsWith(".csv") -> {
                    // The native importer validates both wide robot logs and ARES' canonical
                    // lossless schema. It offsets sample order against any earlier file in this
                    // session, so overlapping timestamp/topic samples remain deterministic.
                    csvLogDecoder.parseCsvLogNative(file, sessionId)
                }
                lowerName.endsWith(".parquet") -> {
                    parquetLogDecoder.parseParquetLog(file, sessionId)
                }
                lowerName.endsWith(".dslog") || lowerName.endsWith(".dsevents") -> {
                    val targetFile = if (lowerName.endsWith(".dsevents")) {
                        File(file.parentFile, file.nameWithoutExtension + ".dslog")
                    } else {
                        file
                    }
                    com.ares.analytics.service.log.DSLogDecoderService(databaseService).decode(targetFile, sessionId, batcher)
                }
                lowerName.endsWith(".log") -> com.ares.analytics.service.log.RoadRunnerDecoderService().decode(file, sessionId, batcher)
                lowerName.endsWith(".rlog") -> com.ares.analytics.service.log.RlogDecoderService().decode(file, sessionId, batcher)
                lowerName.endsWith(".revlog") -> com.ares.analytics.service.log.RevlogDecoderService(this@LogParserService).decode(file, sessionId, batcher)
                else -> throw IllegalArgumentException("Unsupported log file format: ${file.name}")
            }

            }
            batcher.flush()
            val baseSession = Session(
                sessionId = sessionId,
                teamId = teamId,
                seasonId = seasonId,
                robotId = robotId,
                createdAt = createdAt,
                matchNumber = currentMatchNumber,
                allianceColor = currentAlliance,
                tags = currentTags
            )

            val range = databaseService.getSessionTimestampRange(sessionId)
            val finalSession = if (range != null) {
                val duration = range.second - range.first
                baseSession.copy(durationMs = duration)
            } else {
                baseSession
            }
            val summary = summaryEngineService.generateSummary(finalSession)
            val completedSession = finalSession.copy(tags = summary.tags)
            val reports = sourceEvidence.map { evidence ->
                val report = buildImportReport(
                    file = evidence.file,
                    sessionId = sessionId,
                    sourceSizeBytes = evidence.sizeBytes,
                    sourceSha256 = evidence.sha256,
                )
                report.copy(
                    warnings = report.warnings + "Record and topic totals describe the combined multi-file session",
                )
            }
            databaseService.completeSessionImport(completedSession, reports)

            return@withContext completedSession
        } catch (failure: Throwable) {
            cleanupFailedImport(sessionId, failure)
            throw failure
        }
    }

    internal suspend fun parseWpiLog(file: File, sessionId: String, batcher: FrameBatcher) {
        wpiLogDecoder.parseWpiLog(file, sessionId, batcher)
    }

    private suspend fun cleanupFailedImport(sessionId: String, failure: Throwable) {
        try {
            databaseService.deleteSession(sessionId)
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
    }

    internal fun decoderName(file: File): String {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".wpilogxz") -> "wpilog-xz"
            isActionLogName(name) && name.endsWith(".jsonl") -> "action-jsonl"
            name.endsWith(".wpilog") -> "wpilog"
            name.endsWith(".jsonl") -> "jsonl"
            name.endsWith(".csv.gz") -> "csv-gzip"
            name.endsWith(".csv") -> "csv"
            name.endsWith(".parquet") -> "parquet"
            name.endsWith(".dslog") || name.endsWith(".dsevents") -> "driver-station"
            name.endsWith(".rlog") -> "rlog"
            name.endsWith(".revlog") -> "revlog"
            name.endsWith(".log") -> "road-runner"
            else -> file.extension.lowercase().ifEmpty { "unknown" }
        }
    }

    private fun expandWpiLogXz(sourceFile: File, input: FileInputStream, destination: File) {
        val ratioBound = sourceFile.length().coerceAtLeast(1L)
            .coerceAtMost(MAX_XZ_EXPANDED_BYTES / MAX_XZ_EXPANSION_RATIO) * MAX_XZ_EXPANSION_RATIO
        val expandedLimit = maxOf(MIN_XZ_EXPANDED_BYTES, ratioBound)
            .coerceAtMost(MAX_XZ_EXPANDED_BYTES)
        org.tukaani.xz.XZInputStream(input, MAX_XZ_DECODER_MEMORY_KIB).use { expanded ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(XZ_COPY_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val read = expanded.read(buffer)
                    if (read < 0) break
                    total = Math.addExact(total, read.toLong())
                    require(total <= expandedLimit) {
                        "Compressed WPILOG expands beyond the ${expandedLimit / (1024 * 1024)} MiB safety limit"
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    internal suspend fun persistExternalImportReports(sessionId: String, reports: List<ImportReport>) {
        databaseService.replaceSessionImportReports(sessionId, reports)
    }

    private data class SourceEvidence(
        val file: File,
        val sizeBytes: Long,
        val sha256: String,
    )

    private companion object {
        private const val XZ_COPY_BUFFER_BYTES = 64 * 1024
        private const val MAX_XZ_DECODER_MEMORY_KIB = 64 * 1024
        private const val MAX_XZ_EXPANSION_RATIO = 100L
        private const val MIN_XZ_EXPANDED_BYTES = 16L * 1024L * 1024L
        private const val MAX_XZ_EXPANDED_BYTES = 512L * 1024L * 1024L
    }
}

private fun isActionLogName(lowerName: String): Boolean =
    lowerName.startsWith("action_log_") || HASH_PREFIXED_ACTION_LOG.matches(lowerName)

private val HASH_PREFIXED_ACTION_LOG = Regex("^[0-9a-f]{12}_action_log_.*\\.jsonl$")

/** `.dsevents` files are metadata companions and must never become independent import sessions. */
internal fun isDriverStationEventCompanionName(fileName: String): Boolean =
    fileName.endsWith(".dsevents", ignoreCase = true)

/** Resolves companion selections to their `.dslog` primary and removes duplicate primary inputs. */
internal fun canonicalLogImportFiles(files: List<File>): List<File> {
    require(files.isNotEmpty()) { "No log files provided" }
    require(files.all(File::isFile)) {
        "One or more selected log files do not exist"
    }
    return files.map { file ->
        if (!isDriverStationEventCompanionName(file.name)) {
            file.canonicalFile
        } else {
            val baseName = file.name.substringBeforeLast('.')
            val primary = file.parentFile?.listFiles()?.firstOrNull { candidate ->
                candidate.isFile &&
                    candidate.name.endsWith(".dslog", ignoreCase = true) &&
                    candidate.name.substringBeforeLast('.').equals(baseName, ignoreCase = true)
            } ?: File(file.parentFile, "$baseName.dslog")
            require(primary.isFile) {
                "Driver Station events file ${file.name} has no matching .dslog primary"
            }
            primary.canonicalFile
        }
    }.distinctBy { it.toPath() }
}
