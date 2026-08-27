package com.ares.analytics.validation

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.shared.TelemetryMetricCatalog
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertTrue

/** Optional self-hosted validation against a physical robot or a separately running simulator. */
class HardwareDashboardValidationTest {

    @Test
    fun `physical target streams usable dashboard telemetry`() = runBlocking {
        assumeTrue(
            "Set -Dares.validation.hardwareEnabled=true to run physical NT4 validation.",
            property("hardwareEnabled", "false").equals("true", ignoreCase = true)
        )
        val host = property("hardwareHost", "192.168.43.1")
        val port = property("hardwarePort", "5810").toInt()
        val observationSeconds = property("hardwareObservationSeconds", "30").toInt()
        val connectTimeoutSeconds = property("hardwareConnectTimeoutSeconds", "15").toInt()
        val minimumFrames = property("hardwareMinFrames", "100").toLong()
        val minimumTopics = property("hardwareMinTopics", "3").toInt()
        val requiredKeys = property("hardwareRequiredKeys", "")
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(TelemetryMetricCatalog::normalizeTopic)
        val reportDirectory = File(
            System.getProperty("ares.validation.reportDir", "build/reports/dashboard-validation")
        ).canonicalFile
        val tempDirectory = Files.createTempDirectory("ares-hardware-validation").toFile()
        val database = DatabaseService(tempDirectory.resolve("hardware.duckdb").absolutePath)
        val client = Nt4ClientService(database)
        val receivedFrames = AtomicLong()
        val observedKeys = ConcurrentHashMap.newKeySet<String>()
        val observedTimestamps = ArrayList<Long>()
        val violations = mutableListOf<String>()
        val metrics = linkedMapOf<String, Double>()
        var unexpectedFailure: Throwable? = null
        var collector: Job? = null

        try {
            collector = launch {
                client.telemetryFlow.collect { frame ->
                    receivedFrames.incrementAndGet()
                    observedKeys += TelemetryMetricCatalog.normalizeTopic(frame.key)
                    observedTimestamps += frame.timestampMs
                }
            }

            val connectStarted = System.nanoTime()
            client.start(host, "23247", "hardware-validation", "validation-target", port)
            withTimeout(connectTimeoutSeconds * 1_000L) {
                while (!client.isConnected.value) delay(50L)
            }
            metrics["connection_ms"] = (System.nanoTime() - connectStarted) / 1_000_000.0

            val observationStarted = System.nanoTime()
            delay(observationSeconds * 1_000L)
            val observedSeconds = (System.nanoTime() - observationStarted) / 1_000_000_000.0
            val connectedAfterObservation = client.isConnected.value
            client.stop()
            collector?.cancelAndJoin()

            val frameCount = receivedFrames.get()
            val persistedCount = database.countTelemetryFrames("live-telemetry")
            val newestObservedTimestamp = observedTimestamps.maxOrNull()
            val observedRetainedCount = newestObservedTimestamp?.let { newest ->
                observedTimestamps.count { timestamp ->
                    timestamp >= newest - Nt4ClientService.LIVE_RETENTION_MS
                }.toLong()
            } ?: 0L
            metrics["observation_seconds"] = observedSeconds
            metrics["frames_received"] = frameCount.toDouble()
            metrics["unique_topics"] = observedKeys.size.toDouble()
            metrics["frames_per_second"] = frameCount / observedSeconds.coerceAtLeast(0.001)
            metrics["frames_persisted"] = persistedCount.toDouble()
            metrics["frames_observed_in_live_window"] = observedRetainedCount.toDouble()

            if (frameCount < minimumFrames) {
                violations += "Received $frameCount frames; expected at least $minimumFrames"
            }
            if (observedKeys.size < minimumTopics) {
                violations += "Observed ${observedKeys.size} topics; expected at least $minimumTopics"
            }
            val missingKeys = requiredKeys.filterNot(observedKeys::contains)
            if (missingKeys.isNotEmpty()) {
                violations += "Required telemetry keys were not observed: ${missingKeys.joinToString()}"
            }
            // Persistence begins as soon as the NT4 reader starts, while this independent
            // SharedFlow observer may not be scheduled until after the first announcements.
            // Therefore the database may legitimately contain more rows than this observer saw,
            // but it must contain at least every observed row inside the live retention window.
            if (persistedCount < observedRetainedCount) {
                violations += "Persisted $persistedCount frames; expected at least $observedRetainedCount " +
                    "observed within the ${Nt4ClientService.LIVE_RETENTION_MS / 1_000L}-second live window " +
                    "($frameCount frames observed including retained NT4 values)"
            }
            if (!connectedAfterObservation) {
                violations += "NT4 connection dropped during the observation window"
            }
        } catch (failure: Throwable) {
            unexpectedFailure = failure
            violations += "Hardware validation failure: ${failure.message ?: failure::class.simpleName}"
        } finally {
            collector?.cancelAndJoin()
            client.stop()
            database.close()
            writeReport(
                reportDirectory,
                HardwareValidationReport(
                    generatedAt = Instant.now().toString(),
                    status = if (violations.isEmpty()) "PASS" else "FAIL",
                    target = "$host:$port",
                    metrics = metrics,
                    observedKeys = observedKeys.sorted(),
                    violations = violations
                )
            )
            tempDirectory.deleteRecursively()
        }

        unexpectedFailure?.let { throw it }
        assertTrue(
            violations.isEmpty(),
            violations.joinToString(prefix = "Hardware dashboard validation failed:\n- ", separator = "\n- ")
        )
    }

    private fun property(name: String, default: String): String =
        System.getProperty("ares.validation.$name", default)

    private fun writeReport(directory: File, report: HardwareValidationReport) {
        directory.mkdirs()
        directory.resolve("dashboard-validation-hardware.json").writeText(JSON.encodeToString(report))
        directory.resolve("dashboard-validation-hardware.md").writeText(report.toMarkdown())
        println("Hardware dashboard validation report: ${directory.absolutePath}")
    }

    private fun HardwareValidationReport.toMarkdown(): String = buildString {
        appendLine("# Hardware Dashboard Validation Report")
        appendLine()
        appendLine("- Status: **$status**")
        appendLine("- Target: `$target`")
        appendLine("- Generated: $generatedAt")
        appendLine()
        appendLine("## Metrics")
        appendLine()
        appendLine("| Metric | Value |")
        appendLine("|---|---:|")
        metrics.forEach { (name, value) -> appendLine("| `$name` | ${"%.3f".format(value)} |") }
        appendLine()
        appendLine("## Observed topics")
        appendLine()
        observedKeys.forEach { appendLine("- `$it`") }
        appendLine()
        appendLine("## Violations")
        appendLine()
        if (violations.isEmpty()) appendLine("None.") else violations.forEach { appendLine("- $it") }
    }

    @Serializable
    private data class HardwareValidationReport(
        val generatedAt: String,
        val status: String,
        val target: String,
        val metrics: Map<String, Double>,
        val observedKeys: List<String>,
        val violations: List<String>
    )

    private companion object {
        val JSON = Json { prettyPrint = true }
    }
}
