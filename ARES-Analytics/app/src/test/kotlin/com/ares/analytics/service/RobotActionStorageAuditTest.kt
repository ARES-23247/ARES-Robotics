package com.ares.analytics.service

import com.ares.analytics.shared.models.MAX_SUPPORTED_TIMESTAMP_MS
import com.ares.analytics.shared.models.RobotActionRecord
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class RobotActionStorageAuditTest {
    private fun audit(block: suspend (DatabaseService, String) -> Unit) = runTest {
        val root = Files.createTempDirectory("action-storage-audit").toFile()
        val path = root.resolve("test.duckdb").path
        val db = DatabaseService(path)
        try { block(db, path) } finally { db.close(); root.deleteRecursively() }
    }
    private fun action(time: Long = 10, session: String = "recorded") =
        RobotActionRecord(time, session, "run", "robot", 3, "BLUE", "Drive", "{\"value\":1}")

    @Test fun `action reads use the reader coordinator`() = audit { db, _ ->
        db.insertRobotActionsBulk(listOf(action()))
        val before = db.metrics.snapshot()
        assertEquals(listOf(action()), db.getActionsForSession("recorded"))
        val after = db.metrics.snapshot()
        assertEquals(before.writeCount, after.writeCount)
        assertEquals(before.queryCount + 1, after.queryCount)
    }
    @Test fun `failed action enumeration cannot flush a partial batch on close`() = audit { db, _ ->
        val failed = object : AbstractList<RobotActionRecord>() {
            override val size = 2
            override fun get(index: Int): RobotActionRecord {
                if (index == 1) error("source failed")
                return action()
            }
        }
        assertFailsWith<IllegalStateException> { db.insertRobotActionsBulk(failed) }
        assertTrue(db.getActionsForSession("recorded").isEmpty())
        db.insertRobotActionsBulk(listOf(action(20)))
        assertEquals(listOf(action(20)), db.getActionsForSession("recorded"))
    }
    @Test fun `invalid action domains roll back preceding valid rows`() = audit { db, _ ->
        for (invalid in listOf(action(-1), action(MAX_SUPPORTED_TIMESTAMP_MS + 1), action(session = " "))) {
            assertFailsWith<IllegalArgumentException> { db.insertRobotActionsBulk(listOf(action(), invalid)) }
            assertTrue(db.getActionsForSession("recorded").isEmpty())
        }
    }
    @Test fun `fields duplicates sparse identities and session isolation survive round trip`() = audit { db, _ ->
        val sparse = action(0).copy(runId = "", robotId = "", matchNumber = 0, alliance = "UNKNOWN")
        val last = action(MAX_SUPPORTED_TIMESTAMP_MS)
        db.insertRobotActionsBulk(listOf(last, sparse, sparse, action(session = "other")))
        assertEquals(listOf(sparse, sparse, last), db.getActionsForSession("recorded"))
        assertEquals(listOf(action(session = "other")), db.getActionsForSession("other"))
        assertTrue(db.getActionsForSession("missing").isEmpty())
    }
    @Test fun `same timestamp ordering is deterministic across all differing fields`() = audit { db, _ ->
        val rows = listOf(action().copy(robotId = "z"), action().copy(robotId = "a", matchNumber = 2),
            action().copy(robotId = "a", matchNumber = 1, alliance = "RED"),
            action().copy(robotId = "a", matchNumber = 1, alliance = "BLUE"))
        db.insertRobotActionsBulk(rows)
        assertEquals(rows.sortedWith(compareBy({ it.robotId }, { it.matchNumber }, { it.alliance })), db.getActionsForSession("recorded"))
    }
    @Test fun `invalid stored timestamps cannot enter a replay action timeline`() = audit { db, path ->
        db.insertRobotActionsBulk(listOf(action()))
        DriverManager.getConnection("jdbc:duckdb:$path").use { connection ->
            connection.createStatement().use { it.executeUpdate("UPDATE robot_actions SET timestamp_ms = -1") }
        }
        assertFailsWith<IllegalArgumentException> { db.getActionsForSession("recorded") }
    }
    @Test fun `empty batches do not change action history`() = audit { db, _ ->
        db.insertRobotActionsBulk(listOf(action()))
        db.insertRobotActionsBulk(emptyList())
        assertEquals(listOf(action()), db.getActionsForSession("recorded"))
    }
}
