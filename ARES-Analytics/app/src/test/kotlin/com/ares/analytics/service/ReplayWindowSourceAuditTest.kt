package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class ReplayWindowSourceAuditTest {
    private fun database(block: suspend (DatabaseService) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("replay-source-audit").toFile()
        val database = DatabaseService(root.resolve("test.duckdb").path)
        try { block(database) } finally { database.close(); root.deleteRecursively() }
    }
    @Test fun `initial and later windows preserve strict baseline boundaries and string updates`() = database { db ->
        val rows = listOf(
            TelemetryFrame(0, "run", "A", 1.0),
            TelemetryFrame(10, "run", "A", 2.0),
            TelemetryFrame(15, "run", "Mode", 0.0, "AUTO"),
            TelemetryFrame(20, "run", "A", 3.0),
            TelemetryFrame(30, "run", "Mode", 0.0, "TELEOP"),
        ).mapIndexed { index, row -> row.copy(sampleOrder=index+1L) }
        db.insertTelemetryFrames(rows)
        val source = DatabaseReplayWindowSource(db)
        val initial = source.load("run", 0, 15, 0)
        assertTrue(initial.baseline.isEmpty()); assertEquals(rows.take(3), initial.frames)
        val later = source.load("run", 20, 30, 0)
        assertEquals(rows.subList(1, 3), later.baseline)
        assertEquals(rows.takeLast(2), later.frames)
        assertEquals(20, later.startMs); assertEquals(30, later.endMs); assertEquals("run", later.sessionId)
    }
    @Test fun `exact page multiples and duplicate milliseconds preserve source ordering`() = database { db ->
        val rows = listOf(
            TelemetryFrame(10, "run", "A", 1.0, timestampUs=10_001, sampleOrder=1),
            TelemetryFrame(10, "run", "A", 2.0, timestampUs=10_001, sampleOrder=2),
            TelemetryFrame(10, "run", "B", 3.0, timestampUs=10_002, sampleOrder=3),
            TelemetryFrame(20, "run", "A", 4.0, timestampUs=20_000, sampleOrder=4),
        )
        db.insertTelemetryFrames(rows)
        assertEquals(rows, loadTelemetryWindowPages(db, "run", 10, 20, 2))
        assertEquals(rows, loadTelemetryWindowPages(db, "run", 10, 20, 1))
    }
    @Test fun `empty window retains earlier latched state without inventing frames`() = database { db ->
        db.insertTelemetryFrames(listOf(TelemetryFrame(0, "run", "A", 1.0)))
        val window = DatabaseReplayWindowSource(db).load("run", 10, 20, 0)
        assertTrue(window.frames.isEmpty()); assertEquals(1.0, window.baseline.single().value)
        assertTrue(DatabaseReplayWindowSource(db).load("missing", 0, 20, 0).frames.isEmpty())
    }
    @Test fun `nonpositive page sizes fail before attempting pagination`() = database { db ->
        for (size in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException> { loadTelemetryWindowPages(db, "run", 0, 20, size) }
        }
    }
}
