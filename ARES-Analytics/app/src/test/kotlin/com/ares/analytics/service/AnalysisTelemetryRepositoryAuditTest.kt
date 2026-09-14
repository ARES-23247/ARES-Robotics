package com.ares.analytics.service

import com.ares.analytics.service.db.AnalysisTelemetryGroup
import com.ares.analytics.service.db.AnalysisTelemetryLimits
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

class AnalysisTelemetryRepositoryAuditTest {
    @Test fun `one query returns complete independent groups and exact submillisecond order`() = runTest { database { db ->
        db.insertTelemetryFrames(listOf(frame("a", 900, 9.0), frame("b", 100, 2.0), frame("a", 100, 1.0)))
        val before = db.metrics.snapshot().queryCount
        val result = db.getAnalysisTelemetry("s", listOf(group("A", "a"), group("B", "b"), group("C", "missing")))
        assertEquals(1L, db.metrics.snapshot().queryCount - before)
        assertEquals(listOf(100L, 900L), result.getValue("A").frames.map { it.timestampUs })
        assertEquals(2L, result.getValue("A").sourceRows)
        assertEquals(2.0, result.getValue("B").frames.single().value)
        assertEquals("empty", result.getValue("C").status)
        assertTrue(result.values.all { it.complete })
    } }
    @Test fun `deduplication precedes bounds and overlapping rules do not multiply observations`() = runTest { database { db ->
        db.insertTelemetryFrames(listOf(frame("a", 100, 1.0).copy(sampleOrder = 1),
            frame("a", 100, Double.NaN).copy(sampleOrder = 3), frame("a", 100, 2.0).copy(sampleOrder = 2)))
        val result = db.getAnalysisTelemetry("s", listOf(AnalysisTelemetryGroup("A", listOf("a", "/a"), listOf("a"))),
            AnalysisTelemetryLimits(rows = 1)).getValue("A")
        assertTrue(result.complete)
        assertEquals(1L, result.sourceRows)
        assertTrue(result.frames.single().value.isNaN())
        assertEquals(3L, result.frames.single().sampleOrder)
    } }
    @Test fun `text replaces earlier numeric values without transporting the raw payload`() = runTest { database { db ->
        db.insertTelemetryFrames(listOf(frame("a", 0, 1.0), frame("a", 0, 42.0).copy(stringValue = "x".repeat(100_000))))
        val result = db.getAnalysisTelemetry("s", listOf(group("A", "a"))).getValue("A").frames.single()
        assertEquals("", result.stringValue)
        assertTrue(numericAnalyticsSeries(listOf(result), "s", "a").isEmpty())
    } }
    @Test fun `row budget rejects the entire oversized family while retaining another family`() = runTest { database { db ->
        db.insertTelemetryFrames(listOf(frame("a", 0), frame("a", 1), frame("a", 2), frame("b", 3)))
        val result = db.getAnalysisTelemetry("s", listOf(group("A", "a"), group("B", "b")), AnalysisTelemetryLimits(rows = 2))
        assertEquals("row_limit", result.getValue("A").status)
        assertEquals(3L, result.getValue("A").sourceRows)
        assertTrue(result.getValue("A").frames.isEmpty())
        assertFalse(result.getValue("A").complete)
        assertEquals(1, result.getValue("B").frames.size)
        assertEquals("complete", db.getAnalysisTelemetry("s", listOf(group("A", "a")), AnalysisTelemetryLimits(rows = 3)).getValue("A").status)
    } }
    @Test fun `topic and projected byte limits reject whole groups at their boundaries`() = runTest { database { db ->
        db.insertTelemetryFrames(listOf(frame("a", 0), frame("b", 0)))
        val selection = listOf(group("A", "a", "b"))
        assertEquals("topic_limit", db.getAnalysisTelemetry("s", selection, AnalysisTelemetryLimits(topics = 1)).getValue("A").status)
        assertEquals("byte_limit", db.getAnalysisTelemetry("s", selection, AnalysisTelemetryLimits(projectedBytes = 135)).getValue("A").status)
        assertEquals(2, db.getAnalysisTelemetry("s", selection, AnalysisTelemetryLimits(topics = 2, projectedBytes = 136)).getValue("A").frames.size)
    } }
    @Test fun `supplementary Unicode key storage is bounded before materialization`() = runTest { database { db ->
        val key = "\uD83D\uDE80"
        db.insertTelemetryFrames(listOf(frame(key, 0)))
        assertEquals("byte_limit", db.getAnalysisTelemetry("s", listOf(group("A", key)), AnalysisTelemetryLimits(projectedBytes = 67)).getValue("A").status)
        assertEquals(key, db.getAnalysisTelemetry("s", listOf(group("A", key)), AnalysisTelemetryLimits(projectedBytes = 68)).getValue("A").frames.single().key)
    } }
    @Test fun `selected oversized key is rejected without copying its content to frames`() = runTest { database { db ->
        db.insertTelemetryFrames(listOf(frame("Hardware/Motors/" + "m".repeat(20_000) + "/Voltage", 0)))
        val result = db.getAnalysisTelemetry("s", listOf(AnalysisTelemetryGroup("A", emptyList(), listOf(SummarySysIdDiagnostics.motorTopicPattern))),
            AnalysisTelemetryLimits(projectedBytes = 1024)).getValue("A")
        assertEquals("byte_limit", result.status)
        assertTrue(result.frames.isEmpty())
    } }
    @Test fun `exact motor rules preserve case and reject nested or irrelevant topics`() = runTest { database { db ->
        db.insertTelemetryFrames(listOf("Hardware/Motors/FL/Voltage", "Hardware/Motors/fl/VelocityRpm",
            "Hardware/Motors/fl/Power", "Hardware/Motors/fl/nested/Voltage", "hardware/Motors/fl/Voltage").map { frame(it, 0) })
        val result = db.getAnalysisTelemetry("s", listOf(AnalysisTelemetryGroup("A", emptyList(), listOf(SummarySysIdDiagnostics.motorTopicPattern)))).getValue("A")
        assertEquals(setOf("Hardware/Motors/FL/Voltage", "Hardware/Motors/fl/VelocityRpm"), result.frames.map { it.key }.toSet())
    } }
    @Test fun `session and topic filters are bound values including quotes and wildcards`() = runTest { database { db ->
        val session = "s' OR 1=1 --"
        val key = "a'%_"
        db.insertTelemetryFrames(listOf(frame(key, 0, 2.0).copy(sessionId = session), frame(key, 0, 999.0), frame("a", 0, 999.0).copy(sessionId = session)))
        val result = db.getAnalysisTelemetry(session, listOf(group("A", key))).getValue("A")
        assertEquals(2.0, result.frames.single().value)
        assertEquals(session, result.frames.single().sessionId)
    } }
    @Test fun `live telemetry uses the ephemeral connection and remains isolated from persisted sessions`() = runTest { database { db ->
        db.insertTelemetryFrames(listOf(frame("a", 0, 1.0)))
        db.insertTelemetryFrames(listOf(frame("a", 0, 2.0).copy(sessionId = "live-telemetry")))
        for ((session, value) in listOf("s" to 1.0, "live-telemetry" to 2.0)) {
            assertEquals(value, db.getAnalysisTelemetry(session, listOf(group("A", "a"))).getValue("A").frames.single().value)
        }
    } }
    @Test fun `explicit motor fit reads all three channels in one query`() = runTest { database { db ->
        val frames = buildList {
            repeat(30) { i ->
                val v = 1.0 + i * 0.1; val a = kotlin.math.sin(i * 0.7)
                add(frame("v", i * 1000L, v)); add(frame("a", i * 1000L, a))
                add(frame("u", i * 1000L, 0.4 + 1.6 * v + 0.32 * a))
            }
        }
        db.insertTelemetryFrames(frames)
        val before = db.metrics.snapshot().queryCount
        val fit = SysIdService(db).analyzeMotorData("s", "/u", "v", "a")
        assertEquals(1L, db.metrics.snapshot().queryCount - before)
        assertEquals(0.4, fit.kS, 1e-10); assertEquals(1.6, fit.kV, 1e-10); assertEquals(0.32, fit.kA, 1e-10)
    } }
    @Test fun `invalid group and resource configurations are rejected`() = runTest { database { db ->
        assertFailsWith<IllegalArgumentException> { AnalysisTelemetryLimits(rows = 0) }
        assertFailsWith<IllegalArgumentException> { AnalysisTelemetryLimits(rows = 100_001) }
        assertFailsWith<IllegalArgumentException> { AnalysisTelemetryLimits(topics = 257) }
        assertFailsWith<IllegalArgumentException> { AnalysisTelemetryLimits(projectedBytes = 0) }
        assertFailsWith<IllegalArgumentException> { db.getAnalysisTelemetry("s", emptyList()) }
        assertFailsWith<IllegalArgumentException> { db.getAnalysisTelemetry("s", listOf(group("A", "a"), group("A", "b"))) }
        assertFailsWith<IllegalArgumentException> { db.getAnalysisTelemetry("s", listOf(group("A"))) }
    } }

    private fun group(id: String, vararg keys: String) = AnalysisTelemetryGroup(id, keys.toList())
    private fun frame(key: String, timeUs: Long, value: Double = 1.0) = TelemetryFrame(timeUs / 1000, "s", key, value, timestampUs = timeUs)
    private suspend fun database(block: suspend (DatabaseService) -> Unit) {
        val directory = Files.createTempDirectory("ares-analysis-repository").toFile()
        val db = DatabaseService(directory.resolve("telemetry.duckdb").absolutePath)
        try { block(db) } finally { db.close(); directory.deleteRecursively() }
    }
}
