package com.ares.analytics.service.db

import com.ares.analytics.service.DatabaseMetrics
import com.ares.analytics.shared.models.RobotActionRecord
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.duckdb.DuckDBConnection
import java.sql.DriverManager
import kotlin.test.*

class DuckDbAppenderTransactionAuditTest {
    private fun audit(block: suspend (RobotActionRepository, TelemetryRepository, DuckDBConnection, DuckDBConnection) -> Unit) = runTest {
        DriverManager.getConnection("jdbc:duckdb:").use { write ->
            DriverManager.getConnection("jdbc:duckdb:").use { ephemeral ->
                DatabaseSchemaInitializer(write, ephemeral).initialize()
                write.unwrap(DuckDBConnection::class.java).duplicate().use { read ->
                    ephemeral.unwrap(DuckDBConnection::class.java).duplicate().use { ephemeralRead ->
                        val coordinator = DatabaseTransactionCoordinator(write, read, ephemeral, ephemeralRead,
                            Mutex(), Mutex(), DatabaseMetrics())
                        block(RobotActionRepository(coordinator), TelemetryRepository(coordinator),
                            write.unwrap(DuckDBConnection::class.java), ephemeral.unwrap(DuckDBConnection::class.java))
                    }
                }
            }
        }
    }
    private val row = RobotActionRecord(10, "recorded", "run", "robot", actionType = "Drive", payloadJson = "{}")

    @Test fun `caller owned transaction controls visibility and commit`() = audit { repository, _, write, _ ->
        write.autoCommit = false
        repository.insert(listOf(row))
        assertFalse(write.autoCommit)
        assertTrue(repository.getForSession("recorded").isEmpty())
        write.commit(); write.autoCommit = true
        assertEquals(listOf(row), repository.getForSession("recorded"))
    }
    @Test fun `caller rollback removes a successful batch without repository committing it`() = audit { repository, _, write, _ ->
        write.autoCommit = false
        repository.insert(listOf(row))
        write.rollback(); write.autoCommit = true
        assertTrue(repository.getForSession("recorded").isEmpty())
    }

    @Test fun `telemetry appender joins caller transactions for persistent and live storage`() = audit { _, telemetry, write, ephemeral ->
        for ((session, target) in listOf("recorded" to write, "live-telemetry" to ephemeral)) {
            target.autoCommit = false
            telemetry.insertTelemetryFrames(listOf(TelemetryFrame(10, session, "Value", 1.0)))
            assertFalse(target.autoCommit)
            assertTrue(telemetry.getTelemetryRange(session, 0, 20).isEmpty())
            target.commit(); target.autoCommit = true
            assertEquals(listOf(1.0), telemetry.getTelemetryRange(session, 0, 20).map { it.value })
        }
    }
    @Test fun `caller rollback removes telemetry from both storage routes`() = audit { _, telemetry, write, ephemeral ->
        for ((session, target) in listOf("recorded" to write, "live-telemetry" to ephemeral)) {
            target.autoCommit = false
            telemetry.insertTelemetryFrames(listOf(TelemetryFrame(10, session, "Value", 1.0)))
            target.rollback(); target.autoCommit = true
            assertTrue(telemetry.getTelemetryRange(session, 0, 20).isEmpty())
        }
    }
    @Test fun `owned transactions roll back already flushed native rows for exceptions errors and cancellation`() = audit { repository, _, write, _ ->
        for (failure in listOf(IllegalStateException("source failed"), AssertionError("source error"), CancellationException("cancelled"))) {
            val observed = assertFails {
                withDuckDbAppenderTransaction(write) {
                    appendAndFlush(write)
                    throw failure
                }
            }
            assertSame(failure, observed)
            assertTrue(write.autoCommit)
            assertTrue(repository.getForSession("recorded").isEmpty())
        }
    }
    @Test fun `failed work in caller transaction leaves rollback to caller`() = audit { repository, _, write, _ ->
        write.autoCommit = false
        assertFailsWith<IllegalStateException> {
            withDuckDbAppenderTransaction(write) { appendAndFlush(write); error("failed") }
        }
        assertFalse(write.autoCommit)
        assertTrue(repository.getForSession("recorded").isEmpty())
        write.rollback(); write.autoCommit = true
        assertTrue(repository.getForSession("recorded").isEmpty())
    }
    private fun appendAndFlush(write: DuckDBConnection) {
        write.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "robot_actions").use { appender ->
            appender.beginRow()
            appender.append(row.timestampMs); appender.append(row.sessionId); appender.append(row.runId)
            appender.append(row.robotId); appender.append(row.matchNumber); appender.append(row.alliance)
            appender.append(row.actionType); appender.append(row.payloadJson)
            appender.endRow(); appender.flush()
        }
    }
}
