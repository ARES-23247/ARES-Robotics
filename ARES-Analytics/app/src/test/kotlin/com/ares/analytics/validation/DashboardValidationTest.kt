package com.ares.analytics.validation

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.ExportService
import com.ares.analytics.service.ReplayEngineService
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max
import kotlin.test.assertTrue

/**
 * Automated production-style validation for the dashboard data path.
 *
 * The test generates a deterministic multi-topic session, measures ingestion and indexed
 * query performance, verifies lossless Parquet round-tripping, loads/scrubs replay state,
 * and writes JSON plus Markdown reports before enforcing configurable performance budgets.
 */
class DashboardValidationTest {

    @Test
    fun `dashboard data pipeline stays within performance budgets`() = runBlocking {
        val config = ValidationConfig.fromSystemProperties()
        val reportDirectory = File(
            System.getProperty("ares.validation.reportDir", "build/reports/dashboard-validation")
        ).canonicalFile
        val tempDirectory = Files.createTempDirectory("ares-dashboard-validation").toFile()
        val metrics = linkedMapOf<String, Double>()
        val violations = mutableListOf<String>()
        var unexpectedFailure: Throwable? = null
        var database: DatabaseService? = null
        var replay: ReplayEngineService? = null

        try {
            forceGc()
            val memoryBefore = usedHeapBytes()
            val databaseFile = tempDirectory.resolve("validation.duckdb")
            val db = DatabaseService(databaseFile.absolutePath)
            database = db
            val sessionId = "automated-${config.profile}"
            db.insertSession(
                Session(
                    sessionId = sessionId,
                    teamId = "23247",
                    seasonId = "validation",
                    robotId = "synthetic-dashboard",
                    createdAt = BASE_TIMESTAMP_MS
                )
            )

            val keys = validationKeys(config.topicCount)
            val expectedFrames = config.simulatedSeconds.toLong() * config.sampleRateHz * keys.size
            val insertionBatchTimesMs = mutableListOf<Double>()
            val insertStarted = System.nanoTime()
            val batch = ArrayList<TelemetryFrame>(config.batchSize)
            val totalTicks = config.simulatedSeconds.toLong() * config.sampleRateHz

            suspend fun flushBatch() {
                if (batch.isEmpty()) return
                insertionBatchTimesMs += elapsedMs {
                    db.insertTelemetryFrames(batch.toList())
                }
                batch.clear()
            }

            for (tick in 0 until totalTicks) {
                val timestampMs = BASE_TIMESTAMP_MS + tick * 1_000L / config.sampleRateHz
                val timestampUs = BASE_TIMESTAMP_MS * 1_000L + tick * 1_000_000L / config.sampleRateHz
                keys.forEachIndexed { index, key ->
                    val stringValue = if (key == "Robot/Mode") {
                        if (tick < totalTicks / 2) "AUTO" else "TELEOP"
                    } else {
                        null
                    }
                    batch += TelemetryFrame(
                        timestampMs = timestampMs,
                        sessionId = sessionId,
                        key = key,
                        value = syntheticValue(index, tick),
                        stringValue = stringValue,
                        timestampUs = timestampUs
                    )
                    if (batch.size >= config.batchSize) flushBatch()
                }
            }
            flushBatch()

            val insertionElapsedSeconds = (System.nanoTime() - insertStarted) / 1_000_000_000.0
                .coerceAtLeast(0.001)
            val persistedCount = db.countTelemetryFrames(sessionId)
            metrics["frames_expected"] = expectedFrames.toDouble()
            metrics["frames_persisted"] = persistedCount.toDouble()
            metrics["drop_rate"] = 1.0 - persistedCount.toDouble() / expectedFrames.toDouble()
            metrics["ingestion_frames_per_second"] = expectedFrames / insertionElapsedSeconds
            metrics["insert_batch_p95_ms"] = percentile95(insertionBatchTimesMs)

            val queryTimesMs = mutableListOf<Double>()
            repeat(config.queryIterations) {
                queryTimesMs += elapsedMs {
                    db.getTelemetryForFilters(
                        sessionId,
                        listOf("Robot/BatteryVoltage", "Robot/LoopTimeMs"),
                        emptyList()
                    )
                }
                queryTimesMs += elapsedMs {
                    db.getTelemetryForKeyPatterns(sessionId, listOf("Hardware/Motors/%/Current%"))
                }
                queryTimesMs += elapsedMs { db.getDistinctTelemetryKeys(sessionId) }
                queryTimesMs += elapsedMs {
                    val end = BASE_TIMESTAMP_MS + config.simulatedSeconds * 1_000L
                    db.getTelemetryRange(sessionId, end - 5_000L, end)
                }
            }
            metrics["query_p95_ms"] = percentile95(queryTimesMs)

            val csvFile = tempDirectory.resolve("validation.csv")
            val csvExportMs = elapsedMs {
                ExportService(db).exportToCsvTable(
                    sessionId,
                    listOf("Robot/BatteryVoltage", "Drive/Pose_X", "Robot/Mode"),
                    csvFile,
                    samplingPeriodMs = 20L
                )
            }
            assertTrue(csvFile.isFile && csvFile.length() > 0L, "CSV validation export was empty")
            metrics["csv_export_ms"] = csvExportMs

            val parquetFile = tempDirectory.resolve("validation.parquet")
            metrics["parquet_export_ms"] = elapsedMs {
                db.exportSessionToParquet(sessionId, parquetFile)
            }
            assertTrue(parquetFile.isFile && parquetFile.length() > 0L, "Parquet validation export was empty")

            db.deleteTelemetryFrames(sessionId)
            metrics["parquet_import_ms"] = elapsedMs { db.importParquet(parquetFile) }
            val restoredCount = db.countTelemetryFrames(sessionId)
            metrics["frames_restored"] = restoredCount.toDouble()
            metrics["round_trip_drop_rate"] = 1.0 - restoredCount.toDouble() / expectedFrames.toDouble()

            val replayEngine = ReplayEngineService(db)
            replay = replayEngine
            metrics["replay_load_ms"] = elapsedMs { replayEngine.loadSession(sessionId) }
            val scrubTimesMs = mutableListOf<Double>()
            for (step in 0..10) {
                scrubTimesMs += elapsedMs {
                    replayEngine.scrubTo(step / 10.0)
                    awaitReplaySeek(replayEngine)
                }
            }
            metrics["replay_scrub_p95_ms"] = percentile95(scrubTimesMs)
            assertTrue(replayEngine.currentFrame.value != null, "Replay did not produce a current frame")
            metrics["replay_rapid_seek_burst_ms"] = elapsedMs {
                repeat(51) { index ->
                    replayEngine.scrubTo(
                        when {
                            index == 50 -> 0.75
                            index % 2 == 0 -> 0.10
                            else -> 0.90
                        }
                    )
                }
                awaitReplaySeek(replayEngine)
            }
            assertTrue(
                replayEngine.progress.value in 0.749..0.751,
                "Rapid seeks committed a stale request instead of the final 75% playhead",
            )

            forceGc()
            metrics["heap_growth_mb"] = max(0L, usedHeapBytes() - memoryBefore) / BYTES_PER_MIB

            checkBudget(
                metrics.getValue("drop_rate") <= config.maxDropRate,
                "Ingestion drop rate ${metrics.getValue("drop_rate")} exceeded ${config.maxDropRate}",
                violations
            )
            checkBudget(
                metrics.getValue("round_trip_drop_rate") <= config.maxDropRate,
                "Round-trip drop rate ${metrics.getValue("round_trip_drop_rate")} exceeded ${config.maxDropRate}",
                violations
            )
            checkBudget(
                metrics.getValue("ingestion_frames_per_second") >= config.minIngestionFramesPerSecond,
                "Ingestion throughput ${metrics.getValue("ingestion_frames_per_second")} was below ${config.minIngestionFramesPerSecond}",
                violations
            )
            checkBudget(
                metrics.getValue("query_p95_ms") <= config.maxQueryP95Ms,
                "Query p95 ${metrics.getValue("query_p95_ms")}ms exceeded ${config.maxQueryP95Ms}ms",
                violations
            )
            checkBudget(
                metrics.getValue("replay_load_ms") <= config.maxReplayLoadMs,
                "Replay load ${metrics.getValue("replay_load_ms")}ms exceeded ${config.maxReplayLoadMs}ms",
                violations
            )
            checkBudget(
                metrics.getValue("replay_scrub_p95_ms") <= config.maxReplayScrubP95Ms,
                "Replay scrub p95 ${metrics.getValue("replay_scrub_p95_ms")}ms exceeded ${config.maxReplayScrubP95Ms}ms",
                violations
            )
            checkBudget(
                metrics.getValue("parquet_export_ms") <= config.maxParquetOperationMs &&
                    metrics.getValue("parquet_import_ms") <= config.maxParquetOperationMs,
                "Parquet import/export exceeded ${config.maxParquetOperationMs}ms",
                violations
            )
            checkBudget(
                metrics.getValue("heap_growth_mb") <= config.maxHeapGrowthMb,
                "Heap growth ${metrics.getValue("heap_growth_mb")}MiB exceeded ${config.maxHeapGrowthMb}MiB",
                violations
            )
        } catch (failure: Throwable) {
            unexpectedFailure = failure
            violations += "Unexpected validation failure: ${failure.message ?: failure::class.simpleName}"
        } finally {
            replay?.dispose()
            database?.close()
            val report = ValidationReport(
                generatedAt = Instant.now().toString(),
                profile = config.profile,
                status = if (violations.isEmpty()) "PASS" else "FAIL",
                configuration = config.asReportMap(),
                metrics = metrics,
                violations = violations
            )
            writeReport(reportDirectory, report)
            tempDirectory.deleteRecursively()
        }

        unexpectedFailure?.let { throw it }
        assertTrue(
            violations.isEmpty(),
            violations.joinToString(prefix = "Dashboard validation failed:\n- ", separator = "\n- ")
        )
    }

    private fun validationKeys(topicCount: Int): List<String> {
        val base = mutableListOf(
            "Robot/BatteryVoltage",
            "Robot/LoopTimeMs",
            "Drive/Pose_X",
            "Drive/Pose_Y",
            "Drive/Drive_Heading",
            "Hardware/Motors/fl/CurrentAmps",
            "Hardware/Motors/fr/CurrentAmps",
            "Vision/EKF/Innovation",
            "Robot/Mode"
        )
        while (base.size < topicCount) base += "Synthetic/Signal/${base.size}"
        return base.take(topicCount)
    }

    private fun syntheticValue(topicIndex: Int, tick: Long): Double = when (topicIndex) {
        0 -> 12.8 - (tick % 500L) / 1_000.0
        1 -> 10.0 + (tick % 7L) * 0.25
        else -> topicIndex + (tick % 100L) / 100.0
    }

    private suspend fun elapsedMs(block: suspend () -> Unit): Double {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000.0
    }

    private suspend fun awaitReplaySeek(replay: ReplayEngineService) {
        withTimeout(5_000) {
            while (replay.isSeeking.value) delay(2)
        }
    }

    private fun percentile95(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val index = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(sorted.indices)
        return sorted[index]
    }

    private fun checkBudget(condition: Boolean, message: String, violations: MutableList<String>) {
        if (!condition) violations += message
    }

    private fun forceGc() {
        System.gc()
        Thread.sleep(100L)
    }

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun writeReport(directory: File, report: ValidationReport) {
        directory.mkdirs()
        val baseName = "dashboard-validation-${report.profile}"
        directory.resolve("$baseName.json").writeText(JSON.encodeToString(report))
        directory.resolve("$baseName.md").writeText(report.toMarkdown())
        println("Dashboard validation report: ${directory.resolve("$baseName.md").absolutePath}")
    }

    private fun ValidationReport.toMarkdown(): String = buildString {
        appendLine("# Dashboard Validation Report")
        appendLine()
        appendLine("- Status: **$status**")
        appendLine("- Profile: `$profile`")
        appendLine("- Generated: $generatedAt")
        appendLine()
        appendLine("## Metrics")
        appendLine()
        appendLine("| Metric | Value |")
        appendLine("|---|---:|")
        metrics.forEach { (name, value) -> appendLine("| `$name` | ${"%.3f".format(value)} |") }
        appendLine()
        appendLine("## Configuration")
        appendLine()
        appendLine("| Setting | Value |")
        appendLine("|---|---:|")
        configuration.forEach { (name, value) -> appendLine("| `$name` | ${"%.3f".format(value)} |") }
        appendLine()
        appendLine("## Violations")
        appendLine()
        if (violations.isEmpty()) appendLine("None.") else violations.forEach { appendLine("- $it") }
    }

    @Serializable
    private data class ValidationReport(
        val generatedAt: String,
        val profile: String,
        val status: String,
        val configuration: Map<String, Double>,
        val metrics: Map<String, Double>,
        val violations: List<String>
    )

    private data class ValidationConfig(
        val profile: String,
        val simulatedSeconds: Int,
        val sampleRateHz: Int,
        val topicCount: Int,
        val batchSize: Int,
        val queryIterations: Int,
        val minIngestionFramesPerSecond: Double,
        val maxQueryP95Ms: Double,
        val maxReplayLoadMs: Double,
        val maxReplayScrubP95Ms: Double,
        val maxParquetOperationMs: Double,
        val maxHeapGrowthMb: Double,
        val maxDropRate: Double
    ) {
        fun asReportMap(): Map<String, Double> = linkedMapOf(
            "simulated_seconds" to simulatedSeconds.toDouble(),
            "sample_rate_hz" to sampleRateHz.toDouble(),
            "topic_count" to topicCount.toDouble(),
            "batch_size" to batchSize.toDouble(),
            "query_iterations" to queryIterations.toDouble(),
            "min_ingestion_frames_per_second" to minIngestionFramesPerSecond,
            "max_query_p95_ms" to maxQueryP95Ms,
            "max_replay_load_ms" to maxReplayLoadMs,
            "max_replay_scrub_p95_ms" to maxReplayScrubP95Ms,
            "max_parquet_operation_ms" to maxParquetOperationMs,
            "max_heap_growth_mb" to maxHeapGrowthMb,
            "max_drop_rate" to maxDropRate
        )

        companion object {
            fun fromSystemProperties(): ValidationConfig {
                val profile = property("profile", "smoke")
                val isSoak = profile.equals("soak", ignoreCase = true)
                return ValidationConfig(
                    profile = profile,
                    simulatedSeconds = intProperty("simulatedSeconds", if (isSoak) 1_800 else 10),
                    sampleRateHz = intProperty("sampleRateHz", if (isSoak) 20 else 100),
                    topicCount = intProperty("topicCount", if (isSoak) 8 else 12),
                    batchSize = intProperty("batchSize", 5_000),
                    queryIterations = intProperty("queryIterations", if (isSoak) 10 else 3),
                    minIngestionFramesPerSecond = doubleProperty("minIngestionFramesPerSecond", 1_000.0),
                    maxQueryP95Ms = doubleProperty("maxQueryP95Ms", if (isSoak) 2_000.0 else 1_000.0),
                    maxReplayLoadMs = doubleProperty("maxReplayLoadMs", if (isSoak) 15_000.0 else 5_000.0),
                    maxReplayScrubP95Ms = doubleProperty("maxReplayScrubP95Ms", 2_000.0),
                    maxParquetOperationMs = doubleProperty("maxParquetOperationMs", if (isSoak) 30_000.0 else 15_000.0),
                    maxHeapGrowthMb = doubleProperty("maxHeapGrowthMb", if (isSoak) 512.0 else 256.0),
                    maxDropRate = doubleProperty("maxDropRate", 0.0)
                ).also {
                    require(it.simulatedSeconds > 0)
                    require(it.sampleRateHz > 0)
                    require(it.topicCount > 0)
                    require(it.batchSize > 0)
                    require(it.queryIterations > 0)
                }
            }

            private fun property(name: String, default: String): String =
                System.getProperty("ares.validation.$name", default)

            private fun intProperty(name: String, default: Int): Int =
                property(name, default.toString()).toInt()

            private fun doubleProperty(name: String, default: Double): Double =
                property(name, default.toString()).toDouble()
        }
    }

    private companion object {
        val JSON = Json { prettyPrint = true }
        const val BASE_TIMESTAMP_MS = 1_700_000_000_000L
        const val BYTES_PER_MIB = 1024.0 * 1024.0
    }
}
