package com.ares.analytics.service

import com.areslib.control.assist.SysIdMechanism
import com.ares.analytics.service.tuning.TuningParameterKeys
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.sign
import kotlin.test.*

class AutoTunerInputAuditTest {
    private fun rows(): List<AlignedDataRow> {
        var velocity = 0.0
        return List(120) { i ->
            val voltage = when { i < 5 -> 0.0; i < 35 -> -4.0; i < 60 -> -2.0; else -> 6.0 }
            val accel = (voltage - 0.45 * sign(velocity) - 1.8 * velocity) / 0.25
            AlignedDataRow(i * 20L, voltage, velocity, accel).also { velocity += accel * 0.02 }
        }
    }
    private fun test(block: suspend (AutoTunerService) -> Unit) = runTest {
        val file = File.createTempFile("tuner-input-audit", ".duckdb")
        val db = DatabaseService(file.absolutePath)
        val client = Nt4ClientService(db)
        try { block(AutoTunerService(client, SysIdService(db))) }
        finally { client.stop(); db.close(); file.delete() }
    }
    private fun imported(tuner: AutoTunerService, content: String, extension: String="csv"): AutoTunerService.TuningRecommendation? {
        val file = File.createTempFile("tuner-import-audit", ".$extension")
        return try { file.writeText(content); tuner.analyzeLogFile(file) } finally { file.delete() }
    }
    @Test fun `quality permits exactly ten percent invalid numeric samples`() {
        val data = rows().take(100)
        val atLimit = data.mapIndexed { i,r -> if (i >= 90) r.copy(voltage=Double.NaN) else r }
        val overLimit = data.mapIndexed { i,r -> if (i >= 89) r.copy(voltage=Double.NaN) else r }
        assertTrue(AutoTuningSafetyPolicy.assessData(SysIdMechanism.LINEAR, atLimit).passed)
        val rejected = AutoTuningSafetyPolicy.assessData(SysIdMechanism.LINEAR, overLimit)
        assertFalse(rejected.passed)
        assertTrue(rejected.blockers.any { it.contains("10%") })
    }
    @Test fun `duplicate timestamps remain blocked after sorting`() {
        val data = (rows() + rows()[40]).reversed()
        val q = AutoTuningSafetyPolicy.assessData(SysIdMechanism.LINEAR, data)
        assertFalse(q.passed); assertTrue(q.blockers.any { it.contains("unique") })
    }
    @Test fun `timestamps at the supported limit retain finite elapsed time`() {
        val limit = com.ares.analytics.shared.models.MAX_SUPPORTED_TIMESTAMP_MS
        val data = rows().map { it.copy(timestampMs=limit-2380+it.timestampMs) }
        val q = AutoTuningSafetyPolicy.assessData(SysIdMechanism.LINEAR, data)
        assertEquals(2380L, q.durationMs); assertEquals(20.0, q.medianPeriodMs)
        assertTrue(q.passed); assertTrue(q.score.isFinite())
    }
    @Test fun `prepared rows own their sorted snapshot without mutating the caller`() {
        val original = rows().reversed().toMutableList()
        val before = original.toList()
        val prepared = PreparedSysIdData.from(original)
        assertEquals(before, original)
        original.clear()
        assertEquals(rows(), prepared.rows)
        val empty = AutoTuningSafetyPolicy.assessData(SysIdMechanism.LINEAR, emptyList())
        assertFalse(empty.passed); assertEquals(0.0, empty.score); assertEquals(0L, empty.durationMs)
    }
    @Test fun `flywheel retains its voltage velocity declaration mapping`() = test { tuner ->
        val r = assertNotNull(tuner.analyzeSamples(SysIdMechanism.FLYWHEEL, rows()))
        assertEquals(6, r.topicValues.size)
        assertTrue(r.topicValues.keys.all { it.startsWith("subsystem.flywheel.") })
        assertEquals(r.recommendedGains.kP, r.topicValues[TuningParameterKeys.FLYWHEEL_VELOCITY_KP])
    }
    @Test fun `invalid numeric fields and out of domain times cannot contaminate the prepared fit`() = test { tuner ->
        val clean = rows()
        val dirty = clean + listOf(clean[20].copy(voltage=Double.NaN), clean[30].copy(velocity=Double.POSITIVE_INFINITY),
            clean[40].copy(accel=Double.NaN), clean[50].copy(timestampMs=-1), clean[60].copy(timestampMs=Long.MAX_VALUE))
        val prepared = PreparedSysIdData.from(dirty)
        assertEquals(clean, prepared.rows)
        assertEquals(125, prepared.originalCount); assertEquals(122, prepared.finiteSampleCount)
        assertEquals(2, prepared.invalidTimestampCount)
        val r = assertNotNull(tuner.analyzeSamples(SysIdMechanism.LINEAR, dirty))
        assertEquals(1.8, r.recommendedkV, 1e-10)
        assertEquals(RecommendationQuality.REJECTED, r.quality)
    }
    @Test fun `reverse only flywheel data cannot qualify for tuning`() {
        val q = AutoTuningSafetyPolicy.assessData(SysIdMechanism.FLYWHEEL,
            rows().map { it.copy(velocity=-kotlin.math.abs(it.velocity)) })
        assertFalse(q.passed)
        assertTrue(q.blockers.any { it.contains("positive velocity") })
    }
    @Test fun `invalid timestamp is a data blocker even among otherwise valid samples`() {
        val data = rows().toMutableList(); data[0] = data[0].copy(timestampMs=-20)
        val q = AutoTuningSafetyPolicy.assessData(SysIdMechanism.LINEAR, data)
        assertFalse(q.passed); assertEquals(119, q.sampleCount)
        assertTrue(q.blockers.any { it.contains("timestamp", true) })
    }
    @Test fun `even period median averages the two middle observations`() {
        val data = rows().take(31).mapIndexed { i,r -> r.copy(timestampMs=if(i<=15) i*10L else 150+(i-15)*30L) }
        val q = AutoTuningSafetyPolicy.assessData(SysIdMechanism.LINEAR, data)
        assertEquals(20.0, q.medianPeriodMs)
    }
    @Test fun `overflowing sample spans are rejected with finite diagnostics`() {
        val data = rows().mapIndexed { i,r -> r.copy(voltage=if(i%2==0) Double.MAX_VALUE else -Double.MAX_VALUE) }
        val q = AutoTuningSafetyPolicy.assessData(SysIdMechanism.LINEAR, data)
        assertFalse(q.passed); assertTrue(q.score.isFinite()); assertTrue(q.voltageSpan.isFinite())
    }
    @Test fun `drivetrain voltage velocity gains never target position tracking controllers`() = test { tuner ->
        for (mechanism in listOf(SysIdMechanism.LINEAR, SysIdMechanism.ANGULAR)) {
            val r = assertNotNull(tuner.analyzeSamples(mechanism, rows()))
            assertEquals(3, r.topicValues.size)
            assertTrue(r.topicValues.keys.none { it.contains("pathTranslation") || it.contains("pathRotation") })
        }
    }
    @Test fun `unbound and gravity mechanisms do not inherit unrelated proposal keys`() = test { tuner ->
        for (mechanism in listOf(SysIdMechanism.ARM, SysIdMechanism.ELEVATOR, SysIdMechanism.CUSTOM)) {
            val r = assertNotNull(tuner.analyzeSamples(mechanism, rows()))
            assertTrue(r.topicValues.isEmpty()); assertEquals(RecommendationQuality.REJECTED, r.quality)
            assertTrue(r.warnings.any { it.contains("mapping", true) || it.contains("gravity", true) })
        }
    }
    @Test fun `altered proposal values cannot bypass coefficient validation`() = test { tuner ->
        val r = assertNotNull(tuner.analyzeSamples(SysIdMechanism.LINEAR, rows()))
        tuner.approveAndApplyGains(r.copy(topicValues=r.topicValues + (TuningParameterKeys.DRIVE_FEEDFORWARD_KV to 999.0)))
        assertEquals(TuningApplyPhase.FAILED, tuner.applyState.value.phase)
    }
    @Test fun `proposal rechecks canonical envelope instead of supplied limits`() = test { tuner ->
        val r = assertNotNull(tuner.analyzeSamples(SysIdMechanism.LINEAR, rows()))
        tuner.approveAndApplyGains(r.copy(recommendedkV=999.0, safetyEnvelope=r.safetyEnvelope.copy(maxKV=1000.0),
            topicValues=r.topicValues + (TuningParameterKeys.DRIVE_FEEDFORWARD_KV to 999.0)))
        assertEquals(TuningApplyPhase.FAILED, tuner.applyState.value.phase)
    }
    @Test fun `nonfinite recommendation confidence cannot enter proposal inbox`() = test { tuner ->
        val r = assertNotNull(tuner.analyzeSamples(SysIdMechanism.LINEAR, rows()))
        tuner.approveAndApplyGains(r.copy(confidence=Double.NaN))
        assertEquals(TuningApplyPhase.FAILED, tuner.applyState.value.phase)
    }
    @Test fun `CSV without time cannot fabricate a recommendation`() = test { tuner ->
        val content = "voltage,velocity,accel\n" + rows().joinToString("\n") { "${it.voltage},${it.velocity},${it.accel}" }
        assertNull(imported(tuner, content))
    }
    @Test fun `explicit seconds and quoted CSV columns preserve elapsed time`() = test { tuner ->
        val content = "time(s),voltage,velocity,accel,note\n" + rows().joinToString("\n") {
            "${it.timestampMs/1000.0},${it.voltage},${it.velocity},${it.accel},\"sample, measured\""
        }
        val r = assertNotNull(imported(tuner, content))
        assertEquals(2380L, r.dataQuality.durationMs)
        assertEquals(1.8, r.recommendedkV, 1e-10)
    }
    @Test fun `missing log clears a previously published recommendation`() = test { tuner ->
        tuner.analyzeSamples(SysIdMechanism.LINEAR, rows())
        val missing = File.createTempFile("missing-tuner", ".csv"); missing.delete()
        assertNull(tuner.analyzeLogFile(missing)); assertNull(tuner.currentRecommendation.value)
    }
    @Test fun `camel case JSON timestamp and accel alias preserve explicit zero`() = test { tuner ->
        val content = rows().joinToString("\n") {
            "{\"timestampMs\":${it.timestampMs},\"voltage\":${it.voltage},\"speed\":${it.velocity},\"accel\":${it.accel}}"
        }
        val r = assertNotNull(imported(tuner, content, "jsonl"))
        assertEquals(120, r.dataQuality.sampleCount); assertEquals(0.25, r.recommendedkA, 1e-10)
    }
}
