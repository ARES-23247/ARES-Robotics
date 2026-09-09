package com.ares.analytics.service.db

import com.ares.analytics.service.*
import com.ares.analytics.shared.models.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.duckdb.DuckDBConnection
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.*

class RepositoryTransactionAuditTest {
    private fun audit(block: suspend (SessionMetadataRepository, RunEvidenceRepository, DatabaseBackupExporter, Connection, Connection) -> Unit) = runTest {
        DriverManager.getConnection("jdbc:duckdb:").use { write ->
            DriverManager.getConnection("jdbc:duckdb:").use { ephemeral ->
                DatabaseSchemaInitializer(write, ephemeral).initialize()
                write.unwrap(DuckDBConnection::class.java).duplicate().use { read ->
                    ephemeral.unwrap(DuckDBConnection::class.java).duplicate().use { ephemeralRead ->
                        val writer = write; val mutex = Mutex()
                        val coordinator = DatabaseTransactionCoordinator(writer, read, ephemeral, ephemeralRead, mutex, Mutex(), DatabaseMetrics())
                        val sessions = SessionMetadataRepository(coordinator)
                        block(sessions, RunEvidenceRepository(coordinator, sessions), DatabaseBackupExporter(writer, mutex), write, read)
                    }
                }
            }
        }
    }
    private val session = Session("recorded", "team", "season", "robot", 10)
    private val report = ImportReport(sourceName = "log", sourceSha256 = "hash", sourceSizeBytes = 1,
        decoder = "test", status = ImportStatus.SUCCESS, sessionId = "recorded")

    @Test fun `session deletion participates in caller rollback`() = audit { sessions, _, _, write, _ ->
        sessions.insertSession(session)
        write.autoCommit = false
        sessions.deleteSession(session.sessionId)
        assertNotNull(sessions.getSession(session.sessionId))
        write.rollback(); write.autoCommit = true
        assertEquals(session, sessions.getSession(session.sessionId))
    }
    @Test fun `console insertion participates in caller rollback`() = audit { sessions, _, _, write, _ ->
        write.autoCommit = false
        sessions.insertConsoleMessages(listOf(ConsoleMessage(10, "new", "INFO")), session.sessionId)
        assertTrue(sessions.getConsoleMessages(session.sessionId).isEmpty())
        write.rollback(); write.autoCommit = true
        assertTrue(sessions.getConsoleMessages(session.sessionId).isEmpty())
    }
    @Test fun `all evidence mutations participate in caller rollback`() = audit { sessions, evidence, _, write, _ ->
        sessions.insertImportSession(session)
        write.autoCommit = false
        evidence.replaceAnalysisDiagnostics("recorded", listOf(AnalysisDiagnostic("recorded", "Diagnostics/Test", 1.0)))
        evidence.replaceImportReports("recorded", listOf(report))
        evidence.completeImport(session, listOf(report))
        assertNull(sessions.getSession("recorded"))
        assertTrue(evidence.getAnalysisDiagnostics("recorded").isEmpty())
        assertTrue(evidence.getImportReports("recorded").isEmpty())
        write.rollback(); write.autoCommit = true
        assertNull(sessions.getSession("recorded"))
        assertTrue(evidence.getImportReports("recorded").isEmpty())
    }
    @Test fun `failed console enumeration cannot leave rows for the next batch`() = audit { sessions, _, _, _, _ ->
        val failed = object : AbstractList<ConsoleMessage>() {
            override val size = 2
            override fun get(index: Int): ConsoleMessage {
                check(index == 0) { "source failed" }
                return ConsoleMessage(10, "old", "INFO")
            }
        }
        assertFailsWith<IllegalStateException> { sessions.insertConsoleMessages(failed, "recorded") }
        sessions.insertConsoleMessages(listOf(ConsoleMessage(30, "new", "INFO")), "recorded")
        assertEquals(listOf(ConsoleMessage(30, "new", "INFO")), sessions.getConsoleMessages("recorded"))
    }
    @Test fun `console duplicates preserve last severity and distinct texts and sessions`() = audit { sessions, _, _, _, _ ->
        sessions.insertConsoleMessages(listOf(ConsoleMessage(10, "text", "INFO")), "recorded")
        sessions.insertConsoleMessages(listOf(ConsoleMessage(10, "text", "WARN"), ConsoleMessage(10, "text", "ERROR"), ConsoleMessage(11, "other", "INFO")), "recorded")
        sessions.insertConsoleMessages(listOf(ConsoleMessage(10, "text", "OTHER")), "another")
        assertEquals(listOf(ConsoleMessage(10, "text", "ERROR"), ConsoleMessage(11, "other", "INFO")), sessions.getConsoleMessages("recorded"))
        assertEquals(listOf(ConsoleMessage(10, "text", "OTHER")), sessions.getConsoleMessages("another"))
    }
    @Test fun `console batching preserves boundary rows without retaining previous calls`() = audit { sessions, _, _, _, _ ->
        val rows = (0..10_000).map { ConsoleMessage(it.toLong(), "row-$it", "INFO") }
        sessions.insertConsoleMessages(rows, "recorded")
        sessions.insertConsoleMessages(emptyList(), "recorded")
        assertEquals(rows, sessions.getConsoleMessages("recorded"))
    }
    @Test fun `backup import participates in caller transaction`() = audit { _, _, backup, write, read ->
        val directory = Files.createTempDirectory("transaction-backup").toFile()
        try {
            val file = directory.resolve("input.parquet")
            val path = file.path.replace("\\", "/").replace("'", "''")
            write.createStatement().use { it.execute("COPY (SELECT 10::BIGINT AS timestamp_ms, 'source' AS session_id, 'Value' AS key, 1.0 AS value) TO '$path' (FORMAT PARQUET)") }
            write.autoCommit = false
            backup.importParquetAsSession(file, "recorded")
            assertEquals(0, rowCount(read, "telemetry_frames"))
            write.rollback(); write.autoCommit = true
            assertEquals(0, rowCount(read, "telemetry_frames"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun `fatal cloud import failure rolls back all stages`() = audit { sessions, _, backup, write, read ->
        val directory = Files.createTempDirectory("transaction-fatal").toFile()
        try {
            sessions.insertSession(session)
            val file = directory.resolve("input.parquet")
            val path = file.path.replace("\\", "/").replace("'", "''")
            write.createStatement().use { it.execute("COPY (SELECT 10::BIGINT AS timestamp_ms, 'Value' AS key, 1.0 AS value) TO '$path' (FORMAT PARQUET)") }
            val error = AssertionError("stage failed")
            backup.cloudImportFailureInjector = { throw error }
            val observed = assertFailsWith<AssertionError> { backup.importCloudSessionAtomically(file,
                SessionSummary("recorded", "team", "season", "robot", 10), session) }
            assertEquals("stage failed", observed.message)
            assertEquals(0, rowCount(read, "telemetry_frames"))
            assertEquals(session, sessions.getSession("recorded"))
        } finally { directory.deleteRecursively() }
    }
    private fun rowCount(read: Connection, table: String): Long = read.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows -> rows.next(); rows.getLong(1) }
    }
}
