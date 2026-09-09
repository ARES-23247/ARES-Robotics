package com.ares.analytics.service

import com.ares.analytics.shared.models.MAX_SUPPORTED_TIMESTAMP_MS
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.test.runTest
import java.math.BigInteger
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.*

class TelemetryDensityAuditTest {
    private fun audit(block: suspend (DatabaseService) -> Unit) = runTest {
        val root = Files.createTempDirectory("density-audit").toFile()
        val db = DatabaseService(root.resolve("test.duckdb").path)
        try { block(db) } finally { db.close(); root.deleteRecursively() }
    }
    private suspend fun put(db: DatabaseService, times: List<Long>, session: String = "recorded") {
        db.insertTelemetryFrames(times.mapIndexed { i, time ->
            TelemetryFrame(time, session, "Value", i.toDouble(), sampleOrder = i + 1L)
        })
    }
    @Test fun `bucket intervals use floor with a closed final endpoint`() = audit { db ->
        put(db, listOf(0, 24, 25, 49, 50, 74, 75, 99, 100))
        assertEquals(listOf(2f/3, 2f/3, 2f/3, 1f), db.getTelemetryDensity("recorded", 4))
    }
    @Test fun `single instant has a visible first bucket rather than looking empty`() = audit { db ->
        put(db, listOf(100, 100, 100))
        assertEquals(listOf(1f, 0f, 0f), db.getTelemetryDensity("recorded", 3))
    }
    @Test fun `empty recordings return exactly the requested number of zero bins`() = audit { db ->
        assertEquals(List(4) { 0f }, db.getTelemetryDensity("missing", 4))
    }
    @Test fun `bucket count rejects zero negative and excessive allocations`() = audit { db ->
        for (count in listOf(0, -1, 10_001)) {
            assertFailsWith<IllegalArgumentException> { db.getTelemetryDensity("missing", count) }
        }
    }
    @Test fun `one bucket includes every endpoint and duplicate`() = audit { db ->
        put(db, listOf(0, 0, 1, 100))
        assertEquals(listOf(1f), db.getTelemetryDensity("recorded", 1))
    }
    @Test fun `live and persistent density reads remain isolated`() = audit { db ->
        put(db, listOf(0, 1, 2, 100))
        put(db, listOf(0, 100, 100, 100), "live-telemetry")
        assertEquals(listOf(1f, 1f/3), db.getTelemetryDensity("recorded", 2))
        assertEquals(listOf(1f/3, 1f), db.getTelemetryDensity("live-telemetry", 2))
    }
    @Test fun `density queries use the read coordinator rather than the ingestion lock`() = audit { db ->
        put(db, listOf(0, 100))
        val before = db.metrics.snapshot()
        db.getTelemetryDensity("recorded", 2)
        val after = db.metrics.snapshot()
        assertEquals(before.writeCount, after.writeCount)
        assertEquals(before.queryCount + 1, after.queryCount)
    }
    @Test fun `wide sparse timelines agree with an independent integer histogram`() = audit { db ->
        val random = Random(230923)
        repeat(12) { trial ->
            val end = MAX_SUPPORTED_TIMESTAMP_MS - trial
            val buckets = if (trial == 0) 10_000 else random.nextInt(2, 151)
            val times = listOf(0L, end) + List(60) { random.nextLong(end) } +
                (1..10).flatMap { b -> val edge = end / 11 * b; listOf(edge-1, edge, edge+1) }
            val id = "oracle-$trial"
            put(db, times, id)
            val counts = IntArray(buckets)
            for (time in times) {
                val bin = BigInteger.valueOf(time).multiply(BigInteger.valueOf(buckets.toLong()))
                    .divide(BigInteger.valueOf(end)).toInt().coerceAtMost(buckets-1)
                counts[bin]++
            }
            val peak = counts.max()
            assertEquals(counts.map { it.toFloat()/peak }, db.getTelemetryDensity(id, buckets))
        }
    }
}
