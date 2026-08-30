package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.service.db.CloudImportStage
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseServiceIntegrationTest {

    @Test
    fun `mixed live and persistent frames are routed by each frame session`() = runTest {
        withDatabase { database ->
            database.insertTelemetryFrames(
                listOf(
                    TelemetryFrame(1000, "live-telemetry", "Drive/Live", 1.0),
                    TelemetryFrame(1000, "recorded", "Drive/Recorded", 2.0)
                )
            )

            assertEquals(
                listOf(1.0),
                database.getTelemetryRange("live-telemetry", 0, Long.MAX_VALUE).map { it.value }
            )
            assertEquals(
                listOf(2.0),
                database.getTelemetryRange("recorded", 0, Long.MAX_VALUE).map { it.value }
            )
        }
    }

    @Test
    fun `persistent appender preserves null separately from an empty string`() = runTest {
        withDatabase { database ->
            database.insertTelemetryFrames(
                listOf(
                    TelemetryFrame(1000, "session", "Numeric", 4.0, null),
                    TelemetryFrame(1000, "session", "EmptyString", 0.0, "")
                )
            )

            assertNull(database.getTelemetryForKey("session", "Numeric").single().stringValue)
            assertEquals("", database.getTelemetryForKey("session", "EmptyString").single().stringValue)
        }
    }

    @Test
    fun `latest baseline is strict and returns one newest value per key`() = runTest {
        withDatabase { database ->
            database.insertTelemetryFrames(
                listOf(
                    TelemetryFrame(1000, "session", "A", 1.0),
                    TelemetryFrame(1100, "session", "A", 2.0),
                    TelemetryFrame(1050, "session", "B", 0.0, "READY"),
                    TelemetryFrame(1100, "session", "B", 0.0, "RUNNING")
                )
            )

            val baseline = database.getLatestTelemetryBefore("session", 1100).associateBy { it.key }

            assertEquals(1.0, baseline.getValue("A").value)
            assertEquals("READY", baseline.getValue("B").stringValue)
        }
    }

    @Test
    fun `microsecond timestamps and same-time duplicate samples are preserved in order`() = runTest {
        withDatabase { database ->
            database.insertTelemetryFrames(
                listOf(
                    TelemetryFrame(1000, "session", "/Drive/Velocity", 1.0, timestampUs = 1_000_001),
                    TelemetryFrame(1000, "session", "Drive/Velocity", 2.0, timestampUs = 1_000_002),
                    TelemetryFrame(1000, "session", "Drive/Velocity", 3.0, timestampUs = 1_000_002)
                )
            )

            val frames = database.getTelemetryForKey("session", "/Drive/Velocity")
            assertEquals(listOf(1.0, 2.0, 3.0), frames.map { it.value })
            assertEquals(listOf(1_000_001L, 1_000_002L, 1_000_002L), frames.map { it.timestampUs })
            assertTrue(frames.map { it.sampleOrder }.distinct().size == 3)
            assertEquals(listOf("Drive/Velocity"), database.getDistinctTelemetryKeys("session"))
        }
    }

    @Test
    fun `database browser accepts read-only metadata queries`() = runTest {
        withDatabase { database ->
            val result = database.executeQueryRaw("SHOW TABLES")
            assertTrue(result.rows.flatten().contains("telemetry_frames"))
        }
    }

    @Test
    fun `telemetry schema is append only and has no ART indexes`() = runTest {
        withDatabase { database ->
            val indexes = database.executeQueryRaw(
                "SELECT index_name FROM duckdb_indexes() " +
                    "WHERE table_name = 'telemetry_frames' ORDER BY index_name"
            ).rows.flatten()
            val keyConstraints = database.executeQueryRaw(
                "SELECT constraint_type FROM duckdb_constraints() " +
                    "WHERE table_name = 'telemetry_frames' " +
                    "AND constraint_type IN ('PRIMARY KEY', 'UNIQUE')"
            ).rows.flatten()

            assertTrue(indexes.isEmpty())
            assertTrue(keyConstraints.isEmpty())
        }
    }

    @Test
    fun `database browser caps custom query rows and reports truncation`() = runTest {
        withDatabase { database ->
            val result = database.executeQueryRaw(
                sql = "SELECT range AS value FROM range(0, 25)",
                rowLimit = 10
            )

            assertEquals(10, result.rows.size)
            assertEquals("0", result.rows.first().single())
            assertEquals("9", result.rows.last().single())
            assertEquals(10, result.rowLimit)
            assertTrue(result.isTruncated)
        }
    }

    @Test
    fun `database browser does not claim truncation at exact row limit`() = runTest {
        withDatabase { database ->
            val result = database.executeQueryRaw(
                sql = "SELECT range AS value FROM range(0, 10)",
                rowLimit = 10
            )

            assertEquals(10, result.rows.size)
            assertFalse(result.isTruncated)
        }
    }

    @Test
    fun `database browser bounds oversized cells before returning UI state`() = runTest {
        withDatabase { database ->
            val result = database.executeQueryRaw("SELECT repeat('x', 20000) AS payload")

            assertEquals(1, result.truncatedCellCount)
            assertEquals(QueryResult.MAX_CELL_CHARACTERS + 1, result.rows.single().single().length)
            assertTrue(result.rows.single().single().endsWith("…"))
        }
    }

    @Test
    fun `database browser still rejects write statements`() = runTest {
        withDatabase { database ->
            val failure = runCatching {
                database.executeQueryRaw("DELETE FROM sessions")
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
        }
    }

    @Test
    fun `cloud session bundle rolls back every table when any stage fails`() = runTest {
        withDatabase { database ->
            val tempDir = Files.createTempDirectory("ares-cloud-import-atomic").toFile()
            try {
                database.insertTelemetryFrames(listOf(TelemetryFrame(1_000L, "source", "Drive/X", 1.0)))
                val parquet = tempDir.resolve("source.parquet")
                database.exportSessionToParquet("source", parquet)
                database.deleteTelemetryFrames("source")

                CloudImportStage.entries.forEachIndexed { index, failingStage ->
                    val id = "target-$index"
                    val summary = SessionSummary(id, "team", "season", "robot", 1_000L)
                    val session = Session(id, "team", "season", "robot", 1_000L)
                    database.setCloudImportFailureInjector { stage ->
                        if (stage == failingStage) throw IllegalStateException("injected $stage")
                    }

                    assertFailsWith<IllegalStateException> {
                        database.importCloudSessionAtomically(parquet, summary, session)
                    }
                    assertEquals(0L, database.countTelemetryFrames(id))
                    assertNull(database.getSessionSummary(id))
                    assertTrue(database.getSessions().none { it.sessionId == id })
                }
            } finally {
                database.setCloudImportFailureInjector(null)
                tempDir.deleteRecursively()
            }
        }
    }

    @Test
    fun `Parquet and ZIP replace failure preserves prior destination bytes`() = runTest {
        withDatabase { database ->
            val sessionId = "atomic-export"
            database.insertTelemetryFrames(
                listOf(TelemetryFrame(1_000L, sessionId, "Drive/X", 1.0, sampleOrder = 1L)),
            )
            val outputDirectory = Files.createTempDirectory("ares-backup-atomic").toFile()
            try {
                database.setExportReplaceFailureInjector { temporary, destination ->
                    assertEquals(destination.parent, temporary.parent)
                    assertTrue(Files.isRegularFile(temporary))
                    throw IOException("injected replace failure")
                }

                val parquet = outputDirectory.resolve("session.parquet").apply { writeText("old-parquet") }
                assertFailsWith<IOException> {
                    database.exportSessionToParquet(sessionId, parquet)
                }
                assertEquals("old-parquet", parquet.readText())

                val zip = outputDirectory.resolve("sessions.zip").apply { writeText("old-zip") }
                assertFailsWith<IOException> {
                    database.exportSessionsToZip(listOf(sessionId), zip)
                }
                assertEquals("old-zip", zip.readText())
            } finally {
                database.setExportReplaceFailureInjector(null)
                outputDirectory.deleteRecursively()
            }
        }
    }

    @Test
    fun `legacy millisecond telemetry schema migrates without data loss`() = runTest {
        val tempDir = Files.createTempDirectory("ares-database-migration").toFile()
        val databaseFile = tempDir.resolve("telemetry.duckdb")
        Class.forName("org.duckdb.DuckDBDriver")
        DriverManager.getConnection("jdbc:duckdb:${databaseFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE telemetry_frames (
                        timestamp_ms BIGINT NOT NULL,
                        session_id VARCHAR NOT NULL,
                        key VARCHAR NOT NULL,
                        value DOUBLE NOT NULL,
                        string_value VARCHAR,
                        PRIMARY KEY (session_id, key, timestamp_ms)
                    )
                    """.trimIndent()
                )
                statement.execute("INSERT INTO telemetry_frames VALUES (42, 'legacy', '/Drive/Velocity', 3.5, NULL)")
            }
        }

        val database = DatabaseService(databaseFile.absolutePath)
        try {
            val migrated = database.getTelemetryForKey("legacy", "Drive/Velocity").single()
            assertEquals(42_000L, migrated.timestampUs)
            assertEquals(3.5, migrated.value)
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `legacy precision schema drops secondary indexes without rewriting its primary key`() = runTest {
        val tempDir = Files.createTempDirectory("ares-append-only-migration").toFile()
        val databaseFile = tempDir.resolve("telemetry.duckdb")
        Class.forName("org.duckdb.DuckDBDriver")
        DriverManager.getConnection("jdbc:duckdb:${databaseFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE telemetry_frames (
                        timestamp_ms BIGINT NOT NULL,
                        session_id VARCHAR NOT NULL,
                        key VARCHAR NOT NULL,
                        value DOUBLE NOT NULL,
                        string_value VARCHAR,
                        timestamp_us BIGINT NOT NULL,
                        sample_order BIGINT NOT NULL,
                        PRIMARY KEY (session_id, key, timestamp_us, sample_order)
                    )
                    """.trimIndent()
                )
                statement.execute("CREATE INDEX idx_telemetry_session_id ON telemetry_frames(session_id)")
                statement.execute(
                    "CREATE INDEX idx_telemetry_session_key_time " +
                        "ON telemetry_frames(session_id, key, timestamp_us, sample_order)"
                )
                statement.execute("CREATE INDEX idx_telemetry_session_time ON telemetry_frames(session_id, timestamp_ms)")
                statement.execute(
                    "INSERT INTO telemetry_frames VALUES " +
                        "(42, 'legacy', 'Drive/Velocity', 3.5, NULL, 42001, 0), " +
                        "(42, 'legacy', 'Drive/Velocity', 4.5, NULL, 42002, 1)"
                )
            }
        }

        val database = DatabaseService(databaseFile.absolutePath)
        try {
            val migrated = database.getTelemetryForKey("legacy", "Drive/Velocity")
            assertEquals(listOf(42_001L, 42_002L), migrated.map { it.timestampUs })
            assertEquals(listOf(3.5, 4.5), migrated.map { it.value })
            assertTrue(
                database.executeQueryRaw(
                    "SELECT index_name FROM duckdb_indexes() WHERE table_name = 'telemetry_frames'"
                ).rows.isEmpty()
            )
            assertEquals(
                listOf(listOf("PRIMARY KEY")),
                database.executeQueryRaw(
                    "SELECT constraint_type FROM duckdb_constraints() " +
                        "WHERE table_name = 'telemetry_frames' " +
                        "AND constraint_type IN ('PRIMARY KEY', 'UNIQUE')"
                ).rows,
            )
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `deleteSession removes session records and summaries`() = runTest {
        withDatabase { database ->
            val session = Session("del-session", "23247", "2026", "bot", 1000L)
            database.insertSession(session)
            database.insertTelemetryFrames(
                listOf(TelemetryFrame(1000L, "del-session", "Drive/Pose_X", 5.0))
            )
            val summary = SessionSummary(
                sessionId = "del-session",
                teamId = "23247",
                seasonId = "2026",
                robotId = "bot",
                createdAt = 1000L,
                minBatteryVoltage = 12.2,
                maxEkfDrift = 0.05,
                avgLoopTimeMs = 20.0
            )
            database.insertSessionSummary(summary)

            assertEquals(1, database.getSessions().size)
            assertEquals(summary, database.getSessionSummary("del-session"))
            assertEquals(1, database.getTelemetryForKey("del-session", "Drive/Pose_X").size)

            database.deleteSession("del-session")

            assertEquals(0, database.getSessions().size)
            assertNull(database.getSessionSummary("del-session"))
            assertEquals(0, database.getTelemetryForKey("del-session", "Drive/Pose_X").size)
        }
    }

    @Test
    fun `startup removes interrupted imports but preserves completed sessions`() = runTest {
        val tempDirectory = Files.createTempDirectory("ares-import-recovery").toFile()
        val databaseFile = tempDirectory.resolve("telemetry.duckdb")
        val interrupted = Session("interrupted", "23247", "2026", "robot", 1_000L)
        val complete = Session("complete", "23247", "2026", "robot", 2_000L)

        DatabaseService(databaseFile.absolutePath).also { database ->
            database.insertImportSession(interrupted)
            database.insertTelemetryFrames(listOf(TelemetryFrame(1_000L, interrupted.sessionId, "Drive/X", 1.0)))
            database.insertSession(complete)
            database.insertTelemetryFrames(listOf(TelemetryFrame(2_000L, complete.sessionId, "Drive/X", 2.0)))
            database.close()
        }

        val reopened = DatabaseService(databaseFile.absolutePath)
        try {
            assertEquals(listOf(complete.sessionId), reopened.getSessions().map(Session::sessionId))
            assertEquals(0L, reopened.countTelemetryFrames(interrupted.sessionId))
            assertEquals(1L, reopened.countTelemetryFrames(complete.sessionId))
        } finally {
            reopened.close()
            tempDirectory.deleteRecursively()
        }
    }

    @Test
    fun `workspace session queries fail closed across team season and robot identity`() = runTest {
        withDatabase { database ->
            val expected = Session("expected", "23247", "2026", "alpha", 1_000L)
            database.insertSession(expected)
            database.insertSession(Session("other-team", "99999", "2026", "alpha", 2_000L))
            database.insertSession(Session("other-season", "23247", "2025", "alpha", 3_000L))
            database.insertSession(Session("other-robot", "23247", "2026", "beta", 4_000L))

            assertEquals(
                listOf(expected.sessionId),
                database.getSessionsForWorkspace("23247", "2026", "alpha").map(Session::sessionId),
            )
        }
    }

    private suspend fun withDatabase(block: suspend (DatabaseService) -> Unit) {
        val tempDir = Files.createTempDirectory("ares-database-integration").toFile()
        val database = DatabaseService(tempDir.resolve("telemetry.duckdb").absolutePath)
        try {
            block(database)
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }
}
