package com.ares.analytics.service

import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionSummary
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class SessionMetadataReadAuditTest {
    private fun audit(block: suspend (DatabaseService, String) -> Unit) = runTest {
        val root = Files.createTempDirectory("metadata-read-audit").toFile()
        val path = root.resolve("test.duckdb").path
        val db = DatabaseService(path)
        try { block(db, path) } finally { db.close(); root.deleteRecursively() }
    }
    private fun session(id: String, created: Long = 10) = Session(id, "team", "season", "robot", created,
        tags = listOf("simulation", "review"), matchNumber = 3, allianceColor = "BLUE")

    @Test fun `direct lookup preserves metadata and hides incomplete imports`() = audit { db, _ ->
        val complete = session("complete")
        db.insertSession(complete); db.insertImportSession(session("pending"))
        assertEquals(complete, db.getSession("complete"))
        assertNull(db.getSession("pending")); assertNull(db.getSession("missing"))
        assertEquals(listOf(complete), db.getSessions())
    }
    @Test fun `session identifiers remain bound values`() = audit { db, _ ->
        val selected = session("x' OR 1=1 --")
        db.insertSession(session("other")); db.insertSession(selected)
        assertEquals(selected, db.getSession(selected.sessionId))
        assertNull(db.getSession("' OR 1=1 --"))
    }
    @Test fun `single session lookup does not decode unrelated malformed metadata`() = audit { db, path ->
        val good = session("good")
        db.insertSession(good); db.insertSession(session("malformed"))
        DriverManager.getConnection("jdbc:duckdb:$path").use { connection ->
            connection.createStatement().use { it.executeUpdate("UPDATE sessions SET tags = 'invalid json' WHERE session_id = 'malformed'") }
        }
        assertEquals(good, db.getSession("good"))
        assertNull(db.getSession("missing"))
    }
    @Test fun `metadata readers do not acquire the writer coordinator`() = audit { db, _ ->
        db.insertSession(session("complete"))
        val before = db.metrics.snapshot()
        db.getSession("complete"); db.getSessions(); db.getAllSessionSummaries()
        db.getAnnotations("complete"); db.getAlerts("complete"); db.getTopology("robot")
        db.getConsoleMessages("complete")
        val after = db.metrics.snapshot()
        assertEquals(before.writeCount, after.writeCount)
        assertEquals(before.queryCount + 7, after.queryCount)
    }
    @Test fun `workspace query filters all identity fields and orders completed sessions`() = audit { db, _ ->
        val earlier = session("earlier", 10); val later = session("later", 20)
        db.insertSession(earlier); db.insertSession(later)
        db.insertSession(session("team").copy(teamId = "different"))
        db.insertSession(session("season").copy(seasonId = "different"))
        db.insertSession(session("robot").copy(robotId = "different"))
        db.insertImportSession(session("pending", 30))
        assertEquals(listOf(later, earlier), db.getSessionsForWorkspace("team", "season", "robot"))
    }
    @Test fun `paired metadata updates preserve tags and nullable match details`() = audit { db, _ ->
        db.insertSession(session("selected"))
        db.insertSessionSummary(SessionSummary("selected", "team", "season", "robot", 10))
        db.updateSessionTags("selected", listOf("quoted\"tag", "simulation"))
        db.updateSessionMatchDetails("selected", 12, "RED")
        assertEquals(listOf("quoted\"tag", "simulation"), db.getSession("selected")?.tags)
        assertEquals(db.getSession("selected")?.tags, db.getSessionSummary("selected")?.tags)
        assertEquals(12, db.getSession("selected")?.matchNumber)
        assertEquals(12, db.getSessionSummary("selected")?.matchNumber)
        assertEquals("RED", db.getSessionSummary("selected")?.allianceColor)
        db.updateSessionMatchDetails("selected", null, null)
        assertNull(db.getSession("selected")?.matchNumber)
        assertNull(db.getSessionSummary("selected")?.matchNumber)
        assertNull(db.getSession("selected")?.allianceColor)
        assertNull(db.getSessionSummary("selected")?.allianceColor)
        // A recording may legitimately have no derived summary yet.
        db.insertSession(session("no-summary")); db.updateSessionTags("no-summary", listOf("new"))
        assertEquals(listOf("new"), db.getSession("no-summary")?.tags)
    }
    @Test fun `failed summary tag write rolls back the session row`() = audit { db, path ->
        val original = session("selected"); db.insertSession(original)
        dropSummaryTable(path)
        assertFailsWith<java.sql.SQLException> { db.updateSessionTags("selected", listOf("partial")) }
        assertEquals(original, db.getSession("selected"))
        db.insertSession(session("after-failure"))
        assertNotNull(db.getSession("after-failure"))
    }
    @Test fun `failed summary match write rolls back the session row`() = audit { db, path ->
        val original = session("selected"); db.insertSession(original)
        dropSummaryTable(path)
        assertFailsWith<java.sql.SQLException> { db.updateSessionMatchDetails("selected", 99, "RED") }
        assertEquals(original, db.getSession("selected"))
    }
    private fun dropSummaryTable(path: String) {
        DriverManager.getConnection("jdbc:duckdb:$path").use { connection ->
            connection.createStatement().use { it.executeUpdate("DROP TABLE session_summaries") }
        }
    }
}
