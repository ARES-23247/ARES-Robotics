package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.models.AnalysisDiagnostic
import com.ares.analytics.shared.models.Session
import com.ares.analytics.service.db.AnalysisTelemetryInput
import com.ares.analytics.ui.components.history.RunDataDictionary
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.*

class RecordedDriverAnalysisAuditTest {
    @Test fun `uniform submillisecond joystick capture retains its recorded spectrum`() = runTest { fixture {
        wave(4096, 500)
        val result = jitter()
        assertTrue(result.hasJitter)
        assertEquals(10.0, result.peakFrequencyHz, 0.3)
    } }
    @Test fun `fractional millisecond cadence does not bias the frequency scale`() = runTest { fixture {
        wave(1024, 10_500)
        assertEquals(10.0, jitter().peakFrequencyHz, 0.15)
    } }
    @Test fun `latest valid joystick replacement supersedes earlier invalid input`() = runTest { fixture {
        add("Gamepad1/LeftX", 0, Double.NaN)
        wave()
        assertTrue(jitter().hasJitter)
    } }
    @Test fun `text joystick placeholders cannot establish oscillation`() = runTest { fixture {
        wave(); frames.replaceAll { it.copy(stringValue = "unavailable") }
        assertFalse(jitter().hasJitter)
    } }
    @Test fun `joystick spectrum requires normalized input units`() = runTest { fixture {
        wave(); frames.replaceAll { it.copy(value = it.value + 2.0) }
        assertFalse(jitter().hasJitter)
    } }
    @Test fun `an observed oscillation does not identify response curve tuning values`() = runTest { fixture {
        wave()
        val result = jitter()
        assertTrue(result.hasJitter)
        assertNull(result.recommendedExponent)
        assertNull(result.recommendedSlewRate)
    } }
    @Test fun `each driver analysis uses one selected source query including absent topics`() = runTest { fixture {
        var before = database.metrics.snapshot().queryCount
        service.analyzeDriverJitter("s")
        assertEquals(1L, database.metrics.snapshot().queryCount - before)
        before = database.metrics.snapshot().queryCount
        service.analyzeDriverCoaching("s")
        assertEquals(1L, database.metrics.snapshot().queryCount - before)
    } }
    @Test fun `latest chassis updates count once at each source timestamp`() = runTest { fixture {
        repeat(40) { i ->
            add("Drive/ChassisSpeeds/vx", i * 20_000L, 100.0)
            motion(i * 20_000L, 1.0)
        }
        assertEquals(40, coaching().synchronizedSampleCount)
    } }
    @Test fun `text chassis placeholders cannot become motion evidence`() = runTest { fixture {
        repeat(40) { motion(it * 20_000L, 1.0) }
        frames.replaceAll { if (it.key.endsWith("vx")) it.copy(stringValue = "unavailable") else it }
        assertEquals(0, coaching().synchronizedSampleCount)
    } }
    @Test fun `latest invalid chassis update suppresses its earlier valid observation`() = runTest { fixture {
        repeat(40) { i -> motion(i * 20_000L, 1.0); add("Drive/ChassisSpeeds/vx", i * 20_000L, Double.NaN) }
        assertEquals(0, coaching().synchronizedSampleCount)
    } }
    @Test fun `stops break moving direction change comparisons`() = runTest { fixture {
        repeat(40) { i -> motion(i * 50_000L, when (i % 4) { 0 -> 1.0; 2 -> -1.0; else -> 0.0 }) }
        assertEquals(0.0, coaching().directionReversalRatePerMinute)
    } }
    @Test fun `gaps do not establish a reversal rate or strong motion coverage`() = runTest { fixture {
        repeat(220) { motion(it * 1_000_000L, if (it % 2 == 0) 1.0 else -1.0) }
        val report = coaching()
        assertNull(report.directionReversalRatePerMinute)
        assertEquals(DriverReviewConfidence.INSUFFICIENT, report.confidence)
    } }
    @Test fun `finite large vectors do not overflow direction comparison`() = runTest { fixture {
        repeat(40) { motion(it * 50_000L, if (it % 2 == 0) 1e200 else -1e200) }
        assertEquals(1200.0, assertNotNull(coaching().directionReversalRatePerMinute), 1e-9)
    } }
    @Test fun `motion source coverage counts the union of recorded timestamps`() = runTest { fixture {
        repeat(20) { motion(it * 50_000L, 1.0) }
        repeat(10) { add("Drive/ChassisSpeeds/vx", (it + 20) * 50_000L, 1.0) }
        repeat(10) { add("Drive/ChassisSpeeds/vy", (it + 30) * 50_000L, 0.0) }
        val report = coaching()
        assertEquals(40, report.sourceSampleCount)
        assertEquals(0.5, report.coverageFraction)
    } }

    @Test fun `bounded irregular source timing is resampled before spectral analysis`() = runTest { fixture {
        repeat(1024) { i ->
            val time = i * 10_000L + if (i % 2 == 0) 0 else 2_000
            add("Gamepad1/LeftX", time, 0.1 * sin(2 * PI * 10.0 * time / 1e6))
        }
        val result = jitter()
        assertTrue(result.hasJitter); assertEquals(10.0, result.peakFrequencyHz, 0.15)
    } }
    @Test fun `short captures cannot resolve the configured spectral band`() = runTest { fixture {
        wave(128, 500)
        val result = jitter()
        assertFalse(result.hasJitter)
        assertEquals("insufficient_duration", result.axes.first().status)
    } }
    @Test fun `gapped spectra remain unavailable instead of bridging a missing interval`() = runTest { fixture {
        wave()
        frames.replaceAll { if (it.timestampUs >= 1_000_000) it.copy(timestampMs = it.timestampMs + 1000, timestampUs = it.timestampUs + 1_000_000) else it }
        val result = jitter()
        assertFalse(result.hasJitter); assertEquals("irregular_or_gapped", result.axes.first().status)
    } }
    @Test fun `one latest invalid joystick update is not dropped before interpolation`() = runTest { fixture {
        wave(); add("Gamepad1/LeftX", 1_000_000, Double.NaN)
        assertEquals("invalid_normalized_input", jitter().axes.first().status)
    } }
    @Test fun `a quiet available axis cannot establish a negative result for missing controls`() = runTest { fixture {
        repeat(128) { add("Gamepad1/LeftX", it * 10_000L, 0.0) }
        val result = jitter()
        assertEquals("partial", result.inputStatus)
        assertNull(recordedDriverDiagnostics("s", result).find { it.key == "Diagnostics/Driver/JitterPresent" })
        assertFalse(result.message.contains("smooth and stable"))
    } }
    @Test fun `complete quiet axes can report no configured threshold crossing`() = runTest { fixture {
        repeat(128) { add("Gamepad1/LeftX", it * 10_000L, 0.0); add("Gamepad1/LeftY", it * 10_000L, 0.0) }
        val result = jitter()
        assertEquals("complete", result.inputStatus)
        assertEquals(0.0, recordedDriverDiagnostics("s", result).single { it.key == "Diagnostics/Driver/JitterPresent" }.value)
    } }
    @Test fun `a stronger noisy axis cannot hide another axis that meets detection thresholds`() {
        val frames = (0 until 128).flatMap { i -> listOf("x", "y").map { key -> TelemetryFrame(i * 10L, "s", key, 0.0) } }
        var calls = 0
        val result = RecordedDriverAnalysis.jitter(AnalysisTelemetryInput("complete", frames.size.toLong(), frames), listOf("x", "y")) { _, _ ->
            FftResult(doubleArrayOf(0.0, 5.0, 10.0, 15.0, 20.0),
                if (calls++ == 0) doubleArrayOf(0.0, 1.0, 0.2, 1.0, 1.0) else doubleArrayOf(0.0, 0.001, 0.1, 0.001, 0.001), 10.0)
        }
        assertTrue(result.hasJitter)
        assertFalse(result.axes[0].thresholdCrossed); assertTrue(result.axes[1].thresholdCrossed)
        assertTrue(result.message.contains("in y"))
    }
    @Test fun `oversized driver input yields explicit unavailable evidence without prefix analysis`() = runTest { fixture {
        wave(100_001, 1000)
        val result = jitter()
        assertEquals("row_limit", result.inputStatus)
        assertFalse(result.hasJitter); assertTrue(result.axes.all { it.status == "row_limit" })
        frames.clear(); repeat(33_334) { motion(it * 20_000L, 1.0) }
        val motion = coaching()
        assertEquals("row_limit", motion.inputStatus)
        assertNull(motion.directionReversalRatePerMinute)
    } }
    @Test fun `missing motion updates reduce matched and observed coverage separately`() = runTest { fixture {
        repeat(40) { motion(it * 20_000L, 1.0) }
        frames.removeAll { it.key.endsWith("vy") && it.timestampUs == 400_000L }
        val result = coaching()
        assertEquals(39, result.synchronizedSampleCount); assertEquals(40, result.sourceSampleCount)
        assertEquals(0.74, result.observedDurationSeconds, 1e-12)
        assertEquals(0.74 / 0.78, result.timeCoverageFraction, 1e-12)
    } }
    @Test fun `reversal rate uses observed adjacent intervals and excludes recording gaps`() = runTest { fixture {
        repeat(21) { motion(it * 50_000L, if (it == 20) -1.0 else 1.0) }
        repeat(21) { motion(100_000_000 + it * 50_000L, -1.0) }
        val result = coaching()
        assertEquals(2.0, result.observedDurationSeconds)
        assertEquals(30.0, result.directionReversalRatePerMinute)
        assertEquals(DriverReviewConfidence.INSUFFICIENT, result.confidence)
    } }
    @Test fun `an unrepresentable chassis vector norm is unavailable rather than motion evidence`() = runTest { fixture {
        repeat(40) { motion(it * 20_000L, Double.MAX_VALUE, Double.MAX_VALUE) }
        assertEquals(0, coaching().synchronizedSampleCount)
    } }
    @Test fun `continuous synchronized recording can establish strong data coverage`() = runTest { fixture {
        repeat(220) { motion(it * 50_000L, 1.0) }
        val result = coaching()
        assertEquals(DriverReviewConfidence.STRONG, result.confidence)
        assertEquals(1.0, result.coverageFraction); assertEquals(1.0, result.timeCoverageFraction)
    } }
    @Test fun `summary stores source coverage and removes obsolete tuning prescriptions`() = runTest { fixture {
        wave(); database.insertTelemetryFrames(frames)
        database.replaceAnalysisDiagnostics("s", listOf(AnalysisDiagnostic("s", "Diagnostics/Driver/RecommendedExponent", 1.6)))
        val sysId = SysIdService(database)
        SummaryEngineService(database, sysId, service).generateSummary(Session("s", "t", "season", "r", 0))
        val result = database.getAnalysisDiagnostics("s").associateBy { it.key }
        assertNull(result["Diagnostics/Driver/RecommendedExponent"])
        assertEquals(1.0, result["Diagnostics/Driver/AnalyzedAxes"]?.value)
        assertEquals(2.0, result["Diagnostics/Driver/RequestedAxes"]?.value)
        assertEquals(1.0, result["Diagnostics/Driver/JitterPresent"]?.value)
        assertEquals("Gamepad1/LeftX", result["Diagnostics/Driver/Axes/0/Source"]?.stringValue)
    } }
    @Test fun `history refuses legacy unproven results and partial all clear values`() {
        val rows = RunDataDictionary.buildBaseRowDefinitions()
        assertTrue(rows.none { it.label.startsWith("Driver Rec.") })
        val row = rows.single { it.label == "8-12 Hz threshold crossed" }
        val session = Session("s", "t", "season", "r", 0)
        fun value(analyzed: Double?, requested: Double?, crossed: Double): String {
            val values = mutableMapOf("Diagnostics/Driver/JitterPresent" to crossed)
            analyzed?.let { values["Diagnostics/Driver/AnalyzedAxes"] = it }
            requested?.let { values["Diagnostics/Driver/RequestedAxes"] = it }
            return row.getValue(session, null, values)
        }
        assertEquals("N/A", value(null, null, 1.0)); assertEquals("N/A", value(1.0, 2.0, 0.0))
        assertEquals("Yes", value(1.0, 2.0, 1.0)); assertEquals("No", value(2.0, 2.0, 0.0))
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.5, 3.0)) assertEquals("N/A", value(bad, 2.0, 1.0))
    }

    private class Fixture(val database: DatabaseService, profilesPath: String) {
        val frames = mutableListOf<TelemetryFrame>()
        val service = DriverAnalysisService(database, SysIdService(database), profilesPath)
        fun add(key: String, timeUs: Long, value: Double) {
            frames += TelemetryFrame(timeUs / 1000, "s", key, value, timestampUs = timeUs)
        }
        fun wave(n: Int = 256, stepUs: Long = 10_000) {
            repeat(n) { i -> val time = i * stepUs; add("Gamepad1/LeftX", time, 0.1 * sin(2 * PI * 10.0 * time / 1e6)) }
        }
        fun motion(time: Long, vx: Double, vy: Double = 0.0, omega: Double = 0.0) {
            add("Drive/ChassisSpeeds/vx", time, vx); add("Drive/ChassisSpeeds/vy", time, vy); add("Drive/ChassisSpeeds/omega", time, omega)
        }
        suspend fun jitter(): DriverProfileAnalysisResult { database.insertTelemetryFrames(frames); return service.analyzeDriverJitter("s") }
        suspend fun coaching(): DriverCoachingReport { database.insertTelemetryFrames(frames); return service.analyzeDriverCoaching("s") }
    }
    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val directory = Files.createTempDirectory("ares-recorded-driver").toFile()
        val database = DatabaseService(directory.resolve("telemetry.duckdb").absolutePath)
        try { Fixture(database, directory.resolve("profiles.json").absolutePath).block() }
        finally { database.close(); directory.deleteRecursively() }
    }
}
