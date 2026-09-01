package com.ares.analytics.service.log

import com.ares.analytics.service.*
import com.ares.analytics.shared.*
import com.ares.analytics.shared.models.*
import com.ares.analytics.util.Sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Service for decoding CTRE Phoenix 6 high-speed binary `.hoot` trace files.
 *
 * Utilizes CTRE's `owlet` CLI utility to unpack binary hoot traces into intermediate CSV formats, subsequently
 * ingesting high-frequency motor signals ($250\text{ Hz} \dots 1000\text{ Hz}$) into DuckDB telemetry frames. Automatically extracts
 * motor voltage ($V$), velocity ($rad/s$), stator current ($A$), and acceleration ($rad/s^2$) for SysId characterization.
 *
 * ### Signal Ingestion & Analysis:
 * - Direct motor signal mapping for TalonFX / Kraken X60 actuators
 * - Automated closed-loop setpoint tracking vs actual state error calculations
 * - Feedforward ($k_S, k_V, k_A$) and Feedback ($k_P, k_D$) SysId fitting integration via [SysIdService]
 *
 * ### Thread Safety & Performance Guarantees:
 * Executes process invocation and file parsing asynchronously on `Dispatchers.IO`. Cleans up intermediate converted CSV files automatically.
 *
 * @param databaseService Primary DuckDB storage interface.
 * @param summaryEngineService Service for generating session KPI summaries from decoded traces.
 * @param sysIdService Subsystem characterization engine for system identification analysis.
 *
 * @see BaseLogDecoder
 * @see SysIdService
 * @see SummaryEngineService
 */
class HootDecoderService(
    private val databaseService: DatabaseService,
    private val summaryEngineService: SummaryEngineService,
    private val sysIdService: SysIdService
) : BaseLogDecoder() {

    /**
     * Topic key grouping for a single CTRE motor channel used during SysId characterization and motor telemetry extraction.
     *
     * @property motorName Canonical name of the motor (e.g. `"Drive/fl"`).
     * @property voltageKey Topic key for motor output voltage ($V$).
     * @property velocityKey Topic key for motor rotational velocity ($rad/s$).
     * @property currentKey Optional topic key for motor stator current ($A$).
     * @property accelKey Topic key for motor rotational acceleration ($rad/s^2$).
     */
    data class MotorKeys(
        val motorName: String,
        val voltageKey: String,
        val velocityKey: String,
        val currentKey: String?,
        val accelKey: String
    )

    /**
     * Pair mapping an actual mechanism measurement key to its target closed-loop setpoint key.
     *
     * @property actualKey Topic key for measured mechanism pose or velocity.
     * @property setpointKey Topic key for commanded target setpoint.
     */
    data class SetpointPair(
        val actualKey: String,
        val setpointKey: String
    )

    /**
     * Discovers CTRE's owlet CLI utility on the system.
     * Looks in environment path, user home folder, and AdvantageScope's downloaded binaries in AppData.
     */
    fun findOwletPath(): File? {
        val os = System.getProperty("os.name").lowercase()
        val isWindows = os.contains("win")
        val exeExtension = if (isWindows) ".exe" else ""

        // Check system path first
        val pathEnv = System.getenv("PATH") ?: ""
        val pathDirs = pathEnv.split(File.pathSeparator)
        for (dirStr in pathDirs) {
            val dir = File(dirStr)
            val file = File(dir, "owlet$exeExtension")
            if (file.exists() && file.canExecute()) {
                return file
            }
        }

        // Check standard directories
        val pathsToCheck = mutableListOf<File>()

        // 1. AdvantageScope downloaded binaries in AppData
        val appData = System.getenv("APPDATA")
        if (appData != null) {
            pathsToCheck.add(File(appData, "AdvantageScope/owlet"))
            pathsToCheck.add(File(appData, "AdvantageScope"))
        }
        val userHome = System.getProperty("user.home")
        pathsToCheck.add(File(userHome, "Library/Application Support/AdvantageScope/owlet"))
        pathsToCheck.add(File(userHome, ".config/AdvantageScope/owlet"))
        pathsToCheck.add(File(userHome, ".ctre"))
        pathsToCheck.add(File(userHome, ".ctre/owlet"))

        for (dir in pathsToCheck) {
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles { _, name ->
                    val lower = name.lowercase()
                    lower.startsWith("owlet") && (exeExtension.isEmpty() || lower.endsWith(exeExtension))
                }
                if (files != null && files.isNotEmpty()) {
                    // Pick the latest one by name/version (e.g. owlet-26.3.0-C19.exe > owlet-25.0.0-C9.exe)
                    return files.sortedByDescending { it.name }.first()
                }
            }
        }
        return null
    }

    override suspend fun decode(
        file: File,
        sessionId: String,
        batcher: FrameBatcher
    ) {
        // HootDecoderService uses native CSV DuckDB insertion
        // For interface compliance, this is a no-op when called directly.
    }

    /**
     * Converts the selected `.hoot` binary file into a temporary CSV file,
     * reads and parses it line-by-line to write into DuckDB, and runs diagnostics.
     */
    suspend fun importHootLog(
        hootFile: File,
        teamId: String,
        seasonId: String,
        robotId: String,
        sourceName: String = hootFile.name,
    ): LogImportResult = withContext(Dispatchers.IO) {
        val sourceSha256 = Sha256.fileHex(hootFile)
        databaseService.findCompletedSessionBySourceHashes(
            teamId,
            seasonId,
            robotId,
            setOf(sourceSha256),
        )?.let { existing ->
            val report = databaseService.getSessionImportReports(existing.sessionId).firstOrNull()
                ?: buildHootReport(hootFile, sourceName, sourceSha256, existing.sessionId)
            return@withContext LogImportResult(existing, report, wasAlreadyImported = true)
        }
        val owletFile = findOwletPath() ?: throw IllegalStateException("owlet CLI tool not found. Please install CTRE tools or AdvantageScope.")
        val tempCsv = File.createTempFile("hoot_import_", ".csv")
        tempCsv.deleteOnExit()
        val pb = ProcessBuilder(
            owletFile.absolutePath,
            hootFile.absolutePath,
            tempCsv.absolutePath,
            "-f",
            "csv"
        )
        // Merge stderr into stdout and drain the combined stream on a background thread so a
        // verbose child cannot fill its OS pipe buffer and deadlock waitFor() (AUDIT H3).
        pb.redirectErrorStream(true)
        val process = pb.start()
        val capturedOutput = StringBuffer()
        val outputTruncated = AtomicBoolean(false)
        val drainThread = Thread {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        if (!appendBoundedProcessLine(capturedOutput, line, MAX_PROCESS_OUTPUT_CHARS)) {
                            outputTruncated.set(true)
                        }
                        line = reader.readLine()
                    }
                }
            } catch (e: Exception) {
                // Best-effort drain; do not mask the original failure.
            }
        }
        drainThread.isDaemon = true
        drainThread.start()
        val finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            drainThread.join(2000)
            tempCsv.delete()
            throw IllegalStateException("owlet CLI timed out converting hoot log. Output:\n${capturedOutput.withTruncation(outputTruncated.get())}")
        }
        drainThread.join(5000)
        val exitCode = process.exitValue()
        if (exitCode != 0) {
            tempCsv.delete()
            throw IllegalStateException("owlet CLI failed to convert hoot log. Exit code: $exitCode. Output:\n${capturedOutput.withTruncation(outputTruncated.get())}")
        }
        val sessionId = "hoot-${UUID.randomUUID()}"
        try {
            val stagingSession = Session(
                sessionId = sessionId,
                teamId = teamId,
                seasonId = seasonId,
                robotId = robotId,
                createdAt = hootFile.lastModified(),
                tags = listOf("hoot-import"),
            )
            databaseService.insertImportSession(stagingSession)
            // Parse CSV and batch-insert into DB
            val (firstTime, lastTime, parsedKeys) = parseAndInsertTelemetry(tempCsv, sessionId)
            val durationMs = lastTime - firstTime
            require(durationMs > 0L) { "Hoot log file contains no valid timestamp ranges." }

            val session = stagingSession.copy(
                createdAt = firstTime.takeIf { it >= EARLIEST_PLAUSIBLE_EPOCH_MS }
                    ?: hootFile.lastModified(),
                durationMs = durationMs,
            )
            val summary = summaryEngineService.generateSummary(session)
            val completedSession = session.copy(tags = summary.tags)
            runDiagnostics(sessionId, parsedKeys, firstTime, lastTime, durationMs)
            val report = buildHootReport(
                hootFile = hootFile,
                sourceName = sourceName,
                sourceSha256 = sourceSha256,
                sessionId = sessionId,
            )
            databaseService.completeSessionImport(completedSession, listOf(report))
            LogImportResult(completedSession, report)
        } catch (error: CancellationException) {
            // Cancellation has the same ownership obligation as a decoder failure. Startup
            // recovery is a last resort for power loss, not a substitute for prompt cleanup.
            cleanupFailedHootImport(databaseService, sessionId, error)
            throw error
        } catch (error: Exception) {
            // Imports are all-or-nothing from the application's perspective. deleteSession also
            // removes telemetry when the parent session row was never reached.
            cleanupFailedHootImport(databaseService, sessionId, error)
            throw error
        } finally {
            tempCsv.delete()
        }
    }

    private suspend fun buildHootReport(
        hootFile: File,
        sourceName: String,
        sourceSha256: String,
        sessionId: String,
    ): ImportReport {
        val range = databaseService.getSessionTimestampRange(sessionId)
        return ImportReport(
            sourceName = sourceName,
            sourceSha256 = sourceSha256,
            sourceSizeBytes = hootFile.length(),
            decoder = "hoot",
            status = ImportStatus.SUCCESS,
            sessionId = sessionId,
            acceptedRecords = databaseService.countTelemetryFrames(sessionId),
            detectedTopics = databaseService.getDistinctTelemetryKeys(sessionId),
            minTimestampMs = range?.first,
            maxTimestampMs = range?.second,
        )
    }

    internal suspend fun parseAndInsertTelemetry(
        csvFile: File,
        sessionId: String
    ): Triple<Long, Long, Set<String>> = withContext(Dispatchers.IO) {
        val absolutePath = csvFile.absolutePath.replace("\\", "/").replace("'", "''")

        // 1. Read header to detect time column and extract key names
        val reader = csvFile.bufferedReader(Charsets.UTF_8)
        val headerLine: String
        val headers: List<String>
        val keysSet: Set<String>
        val scale: Double
        try {
            headerLine = reader.readLine() ?: throw IllegalArgumentException("Empty CSV file.")
            headers = headerLine.split(",").map { it.trim().replace("\"", "") }
            if (headers.isEmpty() || !headers[0].lowercase().contains("time")) {
                throw IllegalArgumentException("Invalid CSV header format: first column must be timestamp")
            }
            keysSet = headers.drop(1).toSet()

            // 2. Prefer an explicit unit in the timestamp header, then fall back to
            // an interval heuristic for older Owlet exports whose header is just `time`.
            val firstLine = reader.readLine() ?: return@withContext Triple(0L, 0L, emptySet<String>())
            val secondLine = reader.readLine()
            val parts1 = firstLine.split(",")
            val t1 = parts1[0].toDoubleOrNull() ?: 0.0
            val t2 = secondLine?.split(",")?.firstOrNull()?.toDoubleOrNull()
            scale = timestampScaleToMilliseconds(headers[0], t1, t2)
        } finally {
            reader.close()
        }
        val escapedSessionId = sessionId.replace("'", "''")
        val escapedTimeCol = headers[0].replace("'", "''").replace("\"", "\"\"")

        // 3. DuckDB native UNPIVOT import — single SQL pass, no Kotlin-side string parsing
        databaseService.executeNativeCsvImport("""
                INSERT INTO telemetry_frames
                    (timestamp_ms, session_id, key, value, string_value, timestamp_us, sample_order)
                SELECT
                    CAST(CAST("$escapedTimeCol" AS DOUBLE) * $scale AS BIGINT) AS timestamp_ms,
                    '$escapedSessionId' AS session_id,
                    REGEXP_REPLACE(TRIM(key), '^/+', ''),
                    COALESCE(
                        CASE
                            WHEN LOWER(CAST(value AS VARCHAR)) = 'true' THEN 1.0
                            WHEN LOWER(CAST(value AS VARCHAR)) = 'false' THEN 0.0
                            ELSE TRY_CAST(value AS DOUBLE)
                        END,
                        0.0
                    ) AS value,
                    CASE
                        WHEN LOWER(CAST(value AS VARCHAR)) IN ('true', 'false') THEN NULL
                        WHEN TRY_CAST(value AS DOUBLE) IS NULL THEN CAST(value AS VARCHAR)
                    END AS string_value,
                    CAST(CAST("$escapedTimeCol" AS DOUBLE) * $scale * 1000 AS BIGINT) AS timestamp_us,
                    ROW_NUMBER() OVER () AS sample_order
                FROM (
                    SELECT * FROM read_csv_auto('$absolutePath', header=true, ignore_errors=false, all_varchar=true)
                ) UNPIVOT (
                    value FOR key IN (* EXCLUDE ("$escapedTimeCol"))
                )
                WHERE value IS NOT NULL AND CAST(value AS VARCHAR) != ''
            """.trimIndent())

        // 4. Query time range from imported data
        val range = databaseService.getSessionTimestampRange(sessionId)
        val firstTime = range?.first ?: 0L
        val lastTime = range?.second ?: 0L

        Triple(firstTime, lastTime, keysSet)
    }

    /** Returns the multiplier that converts the Owlet timestamp column to milliseconds. */
    internal fun timestampScaleToMilliseconds(header: String, first: Double, second: Double?): Double {
        val normalized = header.lowercase()
            .replace('µ', 'u')
            .replace('μ', 'u')
        val tokens = normalized.split(Regex("[^a-z]+"))
            .filter { it.isNotEmpty() }
        val compact = tokens.joinToString("")
        val explicitScale = when {
            tokens.lastOrNull() in setOf("us", "usec", "microsecond", "microseconds") ||
                compact.endsWith("microsecond") || compact.endsWith("microseconds") ||
                compact.endsWith("timeus") || compact.endsWith("timestampus") -> 0.001
            tokens.lastOrNull() in setOf("ms", "msec", "millisecond", "milliseconds") ||
                compact.endsWith("millisecond") || compact.endsWith("milliseconds") ||
                compact.endsWith("timems") || compact.endsWith("timestampms") -> 1.0
            tokens.lastOrNull() in setOf("ns", "nsec", "nanosecond", "nanoseconds") ||
                compact.endsWith("nanosecond") || compact.endsWith("nanoseconds") ||
                compact.endsWith("timens") || compact.endsWith("timestampns") -> 0.000001
            tokens.lastOrNull() in setOf("s", "sec", "second", "seconds") ||
                compact.endsWith("second") || compact.endsWith("seconds") -> 1000.0
            else -> null
        }
        if (explicitScale != null) return explicitScale

        val interval = second?.let { abs(it - first) } ?: return 1.0
        return when {
            interval >= 1000.0 -> 0.001 // A 1000 us sample period is exactly 1 ms.
            interval < 1.0 -> 1000.0
            else -> 1.0
        }
    }

    private suspend fun runDiagnostics(
        sessionId: String,
        keys: Set<String>,
        firstTime: Long,
        lastTime: Long,
        durationMs: Long
    ) {
        val motors = mutableMapOf<String, MotorKeys>()
        suspend fun sampledTopic(key: String) = databaseService.getTelemetrySeries(
            sessionId = sessionId,
            key = key,
            startMs = firstTime,
            endMs = lastTime,
            maxPoints = MAX_HOOT_TOPIC_FRAMES,
        )

        // 1. Detect Motors from voltage and velocity patterns
        for (key in keys) {
            val parts = key.split("/")
            if (parts.size >= 2) {
                val last = parts.last().lowercase()
                if (last == "voltage" || last == "appliedoutput" || last == "appliedvolts" || last.contains("motorvoltage")) {
                    val name = parts[parts.size - 2]
                    val pathPrefix = parts.dropLast(1).joinToString("/")
                    val velKey = keys.firstOrNull {
                        it.startsWith(pathPrefix) && (it.endsWith("Velocity") || it.endsWith("VelocityRps") || it.lowercase().contains("speed"))
                    }
                    val currentKey = keys.firstOrNull {
                        it.startsWith(pathPrefix) && (it.endsWith("Current") || it.endsWith("StatorCurrent") || it.lowercase().contains("amps"))
                    }

                    if (velKey != null) {
                        motors[name] = MotorKeys(
                            motorName = name,
                            voltageKey = key,
                            velocityKey = velKey,
                            currentKey = currentKey,
                            accelKey = "$pathPrefix/Acceleration"
                        )
                    }
                }
            }
        }

        // 2. Compute derivative acceleration if missing, and run SysId
        val sysIdResults = mutableMapOf<String, CalculatedSummary>()
        for (motor in motors.values) {
            if (!keys.contains(motor.accelKey)) {
                val velocities = sampledTopic(motor.velocityKey)
                val accelFrames = mutableListOf<TelemetryFrame>()
                for (i in 1 until velocities.size) {
                    val prev = velocities[i-1]
                    val curr = velocities[i]
                    val dt = (curr.timestampMs - prev.timestampMs) / 1000.0
                    if (dt > 0.0) {
                        val accel = (curr.value - prev.value) / dt
                        accelFrames.add(TelemetryFrame(curr.timestampMs, sessionId, motor.accelKey, accel))
                    }
                }
                databaseService.insertTelemetryFrames(accelFrames)
            }
            val summary = sysIdService.analyzeMotorData(sessionId, motor.voltageKey, motor.velocityKey, motor.accelKey)
            if (summary.rSquared > 0.1) {
                sysIdResults[motor.motorName] = summary
            }
        }

        if (sysIdResults.isNotEmpty()) {
            val report = StringBuilder()
            report.append("Drive Motor SysId characterization results:\n")
            for ((name, summary) in sysIdResults) {
                report.append("- $name: kS = ${"%.4f".format(summary.kS)}, kV = ${"%.4f".format(summary.kV)}, kA = ${"%.4f".format(summary.kA)} (R² = ${"%.2f".format(summary.rSquared)})\n")
            }
            val sysIdNote = SessionAnnotation(
                annotationId = UUID.randomUUID().toString(),
                sessionId = sessionId,
                text = report.toString(),
                createdAt = System.currentTimeMillis(),
                authorId = "SysId Engine"
            )
            databaseService.insertAnnotation(sysIdNote)
        }

        // 3. PID & Backlash FFT oscillation audit
        val setpointPairs = mutableListOf<SetpointPair>()
        for (key in keys) {
            val lowercase = key.lowercase()
            if (lowercase.endsWith("setpoint") || lowercase.contains("setpoint") || lowercase.endsWith("target") || lowercase.contains("target")) {
                val baseKey = keys.firstOrNull {
                    it != key && (key.startsWith(it) || it.startsWith(key.replace("setpoint", "", true).replace("target", "", true)))
                }
                if (baseKey != null) {
                    setpointPairs.add(SetpointPair(baseKey, key))
                }
            }
        }

        for (pair in setpointPairs) {
            val actuals = sampledTopic(pair.actualKey)
            val setpoints = sampledTopic(pair.setpointKey)
            if (actuals.size >= 32 && setpoints.isNotEmpty()) {
                val actualsSorted = actuals.sortedBy { it.timestampMs }
                val setpointsSorted = setpoints.sortedBy { it.timestampMs }
                val errorList = mutableListOf<Double>()
                val timestamps = mutableListOf<Long>()
                var setpointIndex = 0

                for (act in actualsSorted) {
                    val targetTime = act.timestampMs
                    while (setpointIndex + 1 < setpointsSorted.size &&
                           abs(setpointsSorted[setpointIndex + 1].timestampMs - targetTime) <= abs(setpointsSorted[setpointIndex].timestampMs - targetTime)) {
                        setpointIndex++
                    }
                    val spVal = setpointsSorted[setpointIndex].value
                    errorList.add(spVal - act.value)
                    timestamps.add(act.timestampMs)
                }

                if (errorList.size >= 32) {
                    val avgDeltaMs = (timestamps.last() - timestamps.first()).toDouble() / (timestamps.size - 1)
                    val sampleRateHz = if (avgDeltaMs > 0.0) 1000.0 / avgDeltaMs else 50.0
                    val fftResult = sysIdService.performFftAnalysis(errorList.toDoubleArray(), sampleRateHz)
                    val domFreq = fftResult.dominantFrequency
                    val maxError = errorList.map { abs(it) }.maxOrNull() ?: 0.0

                    if (maxError > 0.05 && domFreq in 2.0..40.0) {
                        val alert = AlertRecord(
                            alertId = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            ruleKey = "/Diagnostics/BacklashFFT/${pair.actualKey}",
                            triggerTimestampMs = firstTime,
                            resolveTimestampMs = lastTime,
                            durationMs = durationMs,
                            peakValue = domFreq,
                            triaged = false
                        )
                        databaseService.insertAlert(alert)
                    }
                }
            }
        }

        // 4. Thermal & Stall diagnostics
        for (motor in motors.values) {
            val currentKey = motor.currentKey ?: continue
            val currents = sampledTopic(currentKey)
            val velocities = sampledTopic(motor.velocityKey)

            if (currents.isEmpty() || velocities.isEmpty()) continue
            val currentsSorted = currents.sortedBy { it.timestampMs }
            val velocitiesSorted = velocities.sortedBy { it.timestampMs }
            var velIndex = 0
            var thermalSum = 0.0
            var maxStallDurationMs = 0L
            var currentStallDurationMs = 0L
            var lastTimeMs = 0L

            for (currFrame in currentsSorted) {
                val t = currFrame.timestampMs
                while (velIndex + 1 < velocitiesSorted.size &&
                       abs(velocitiesSorted[velIndex + 1].timestampMs - t) <= abs(velocitiesSorted[velIndex].timestampMs - t)) {
                    velIndex++
                }
                val velFrame = velocitiesSorted[velIndex]
                val current = currFrame.value
                val velocity = velFrame.value

                if (lastTimeMs > 0L) {
                    val dt = (t - lastTimeMs) / 1000.0
                    if (dt > 0.0) {
                        thermalSum += current * current * 0.05 * dt // I^2 * R * dt, R = 0.05 Ohms

                        if (current > 40.0 && abs(velocity) < 0.1) {
                            currentStallDurationMs += (t - lastTimeMs)
                            maxStallDurationMs = maxOf(maxStallDurationMs, currentStallDurationMs)
                        } else {
                            currentStallDurationMs = 0L
                        }
                    }
                }
                lastTimeMs = t
            }

            if (maxStallDurationMs >= 500L) {
                val alert = AlertRecord(
                    alertId = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    ruleKey = "/Diagnostics/MotorStall/${motor.motorName}",
                    triggerTimestampMs = firstTime,
                    resolveTimestampMs = lastTime,
                    durationMs = maxStallDurationMs,
                    peakValue = maxStallDurationMs.toDouble(),
                    triaged = false
                )
                databaseService.insertAlert(alert)
            }

            if (thermalSum > 10000.0) {
                val alert = AlertRecord(
                    alertId = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    ruleKey = "/Diagnostics/ThermalLoad/${motor.motorName}",
                    triggerTimestampMs = firstTime,
                    resolveTimestampMs = lastTime,
                    durationMs = durationMs,
                    peakValue = thermalSum,
                    triaged = false
                )
                databaseService.insertAlert(alert)
            }
        }

        // 5. CAN Jitter analysis on periodic update signals
        for (key in keys) {
            val frames = sampledTopic(key)
            if (frames.size < 50) continue
            val deltas = mutableListOf<Double>()
            var prevTime = 0L
            for (f in frames.sortedBy { it.timestampMs }) {
                if (prevTime > 0L) {
                    deltas.add((f.timestampMs - prevTime).toDouble())
                }
                prevTime = f.timestampMs
            }

            if (deltas.isEmpty()) continue
            val avg = deltas.average()
            val variance = deltas.map { (it - avg) * (it - avg) }.average()
            val stdDev = sqrt(variance)

            // Flag signals with high jitter (> 8ms standard deviation for signals < 100ms interval)
            if (stdDev > 8.0 && avg < 100.0) {
                val alert = AlertRecord(
                    alertId = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    ruleKey = "/Diagnostics/CANJitter/$key",
                    triggerTimestampMs = firstTime,
                    resolveTimestampMs = lastTime,
                    durationMs = durationMs,
                    peakValue = stdDev,
                    triaged = false
                )
                databaseService.insertAlert(alert)
            }
        }
    }

    private fun StringBuffer.withTruncation(truncated: Boolean): String =
        toString() + if (truncated) "\n[output truncated]" else ""

    private companion object {
        const val MAX_PROCESS_OUTPUT_CHARS = 64 * 1024
        const val MAX_HOOT_TOPIC_FRAMES = 4_096
        const val EARLIEST_PLAUSIBLE_EPOCH_MS = 946_684_800_000L
    }
}

internal fun appendBoundedProcessLine(buffer: StringBuffer, line: String, maxChars: Int): Boolean {
    require(maxChars > 0) { "Process output bound must be positive" }
    val remaining = maxChars - buffer.length
    if (remaining <= 0) return false
    val withNewline = "$line\n"
    buffer.append(withNewline, 0, minOf(remaining, withNewline.length))
    return withNewline.length <= remaining
}

internal suspend fun cleanupFailedHootImport(
    databaseService: DatabaseService,
    sessionId: String,
    failure: Throwable,
) {
    runCatching { databaseService.deleteSession(sessionId) }
        .onFailure(failure::addSuppressed)
}
