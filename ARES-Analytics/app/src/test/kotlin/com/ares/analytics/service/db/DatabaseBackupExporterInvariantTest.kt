package com.ares.analytics.service.db

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.shared.Session
import com.ares.analytics.shared.SessionSummary
import com.ares.analytics.shared.TelemetryFrame
import com.ares.analytics.shared.models.MAX_SUPPORTED_TIMESTAMP_MS
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DatabaseBackupExporterInvariantTest {

    @Test
    fun `native import rejects every hostile frame invariant without replacing existing rows`() = runTest {
        withDatabase { database, tempDir ->
            database.insertTelemetryFrames(
                listOf(TelemetryFrame(1_000L, DIRECT_SESSION, "Drive/X", 1.0))
            )

            hostileCases.forEachIndexed { index, hostileCase ->
                val parquet = tempDir.resolve("hostile-direct-$index.parquet")
                writeHostileParquet(parquet, hostileCase)

                assertFailsWith<IllegalArgumentException>(hostileCase.name) {
                    database.importParquet(parquet)
                }

                val retained = database.getTelemetryRange(DIRECT_SESSION, 0L, Long.MAX_VALUE)
                assertEquals(1, retained.size, hostileCase.name)
                assertEquals(1.0, retained.single().value, hostileCase.name)
            }
        }
    }

    @Test
    fun `remapped import rejects hostile parquet without partially upserting target session`() = runTest {
        withDatabase { database, tempDir ->
            val targetSession = "remapped-target"
            database.insertTelemetryFrames(
                listOf(TelemetryFrame(1_000L, targetSession, "Drive/X", 2.0))
            )
            val parquet = tempDir.resolve("hostile-remapped.parquet")
            writeHostileParquet(
                parquet,
                HostileCase(name = "negative sample order", sampleOrder = "-1::BIGINT")
            )

            assertFailsWith<IllegalArgumentException> {
                database.importParquetAsSession(parquet, targetSession)
            }

            val retained = database.getTelemetryRange(targetSession, 0L, Long.MAX_VALUE)
            assertEquals(1, retained.size)
            assertEquals(2.0, retained.single().value)
        }
    }

    @Test
    fun `cloud import rejects hostile parquet without replacing telemetry or metadata`() = runTest {
        withDatabase { database, tempDir ->
            val targetSession = "cloud-target"
            database.insertTelemetryFrames(
                listOf(TelemetryFrame(1_000L, targetSession, "Drive/X", 3.0))
            )
            database.insertSessionSummary(
                SessionSummary(targetSession, "old-team", "season", "robot", 1_000L)
            )
            database.insertSession(
                Session(targetSession, "old-team", "season", "robot", 1_000L)
            )
            val parquet = tempDir.resolve("hostile-cloud.parquet")
            writeHostileParquet(
                parquet,
                HostileCase(name = "infinite numeric value", value = "'Infinity'::DOUBLE")
            )

            assertFailsWith<IllegalArgumentException> {
                database.importCloudSessionAtomically(
                    parquet,
                    SessionSummary(targetSession, "new-team", "season", "robot", 2_000L),
                    Session(targetSession, "new-team", "season", "robot", 2_000L)
                )
            }

            val retained = database.getTelemetryRange(targetSession, 0L, Long.MAX_VALUE)
            assertEquals(1, retained.size)
            assertEquals(3.0, retained.single().value)
            assertEquals("old-team", database.getSessionSummary(targetSession)?.teamId)
            assertEquals("old-team", database.getSessions().single { it.sessionId == targetSession }.teamId)
        }
    }

    private fun writeHostileParquet(file: File, hostileCase: HostileCase) {
        val safePath = file.canonicalPath.replace("\\", "/").replace("'", "''")
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    COPY (
                        SELECT *
                        FROM (VALUES
                            (1000::BIGINT, '$DIRECT_SESSION'::VARCHAR, '/Drive/X'::VARCHAR,
                                99.0::DOUBLE, NULL::VARCHAR, 1000000::BIGINT, 0::BIGINT),
                            (${hostileCase.timestampMs}, 'hostile'::VARCHAR, ${hostileCase.key},
                                ${hostileCase.value}, NULL::VARCHAR, ${hostileCase.timestampUs},
                                ${hostileCase.sampleOrder})
                        ) AS frames(timestamp_ms, session_id, key, value, string_value, timestamp_us, sample_order)
                    ) TO '$safePath' (FORMAT PARQUET)
                    """.trimIndent()
                )
            }
        }
    }

    private suspend fun withDatabase(block: suspend (DatabaseService, File) -> Unit) {
        val tempDir = Files.createTempDirectory("ares-parquet-invariants").toFile()
        val database = DatabaseService(tempDir.resolve("telemetry.duckdb").absolutePath)
        try {
            block(database, tempDir)
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }

    private data class HostileCase(
        val name: String,
        val timestampMs: String = "1000::BIGINT",
        val key: String = "'Drive/Bad'::VARCHAR",
        val value: String = "1.0::DOUBLE",
        val timestampUs: String = "1000000::BIGINT",
        val sampleOrder: String = "0::BIGINT"
    )

    private companion object {
        const val DIRECT_SESSION = "direct-target"

        val hostileCases = listOf(
            HostileCase(name = "null millisecond timestamp", timestampMs = "NULL::BIGINT"),
            HostileCase(name = "negative millisecond timestamp", timestampMs = "-1::BIGINT"),
            HostileCase(
                name = "millisecond timestamp above supported domain",
                timestampMs = "${MAX_SUPPORTED_TIMESTAMP_MS + 1L}::BIGINT",
                timestampUs = "${(MAX_SUPPORTED_TIMESTAMP_MS + 1L) * 1_000L}::BIGINT"
            ),
            HostileCase(name = "null microsecond timestamp", timestampUs = "NULL::BIGINT"),
            HostileCase(name = "negative microsecond timestamp", timestampUs = "-1::BIGINT"),
            HostileCase(name = "inconsistent microsecond timestamp", timestampUs = "1001000::BIGINT"),
            HostileCase(name = "null sample order", sampleOrder = "NULL::BIGINT"),
            HostileCase(name = "negative sample order", sampleOrder = "-1::BIGINT"),
            HostileCase(name = "null numeric value", value = "NULL::DOUBLE"),
            HostileCase(name = "NaN numeric value", value = "'NaN'::DOUBLE"),
            HostileCase(name = "positive infinite numeric value", value = "'Infinity'::DOUBLE"),
            HostileCase(name = "negative infinite numeric value", value = "'-Infinity'::DOUBLE"),
            HostileCase(name = "null key", key = "NULL::VARCHAR"),
            HostileCase(name = "blank normalized key", key = "'///   '::VARCHAR")
        )
    }
}
