package com.ares.analytics.service

import com.ares.analytics.shared.models.Session
import kotlinx.coroutines.runBlocking
import org.duckdb.DuckDBConnection
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DatabaseInitializationCleanupTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Connections {
        val writer = mock(DuckDBConnection::class.java)
        val historical = mock(DuckDBConnection::class.java)
        val metadata = mock(DuckDBConnection::class.java)
        val liveWriter = mock(DuckDBConnection::class.java)
        val liveReader = mock(DuckDBConnection::class.java)
        val statement = mock(Statement::class.java)
        val liveStatement = mock(Statement::class.java)
        val all = listOf(writer, historical, metadata, liveWriter, liveReader)

        init {
            `when`(writer.unwrap(DuckDBConnection::class.java)).thenReturn(writer)
            `when`(writer.duplicate()).thenReturn(historical, metadata)
            `when`(liveWriter.unwrap(DuckDBConnection::class.java)).thenReturn(liveWriter)
            `when`(liveWriter.duplicate()).thenReturn(liveReader)
            `when`(writer.createStatement()).thenReturn(statement)
            `when`(liveWriter.createStatement()).thenReturn(liveStatement)
        }
    }

    private fun withConnections(block: (Connections) -> Unit) {
        // Load/register the real driver before temporarily intercepting this thread's JDBC calls.
        Class.forName("org.duckdb.DuckDBDriver")
        val connections = Connections()
        mockStatic(DriverManager::class.java).use { driver ->
            driver.`when`<Connection> { DriverManager.getConnection(anyString()) }
                .thenReturn(connections.writer, connections.liveWriter)
            block(connections)
        }
    }

    private fun construct() = DatabaseService(
        temporary.root.resolve("telemetry.duckdb").absolutePath,
        duckDbMemoryLimit = "256MB", duckDbWorkerThreads = 1,
    )

    @Test
    fun `a failed reader duplicate closes the already opened persistent writer`() = withConnections { connections ->
        val failure = SQLException("duplicate failed")
        `when`(connections.writer.duplicate()).thenThrow(failure)
        assertSame(failure, assertFailsWith<SQLException> { construct() })
        verify(connections.writer).close()
        verify(connections.historical, never()).close()
        verify(connections.liveWriter, never()).close()
    }

    @Test
    fun `extension preparation failure closes the writer and both persistent readers`() = withConnections { connections ->
        val failure = SQLException("extension unavailable")
        `when`(connections.statement.execute("INSTALL parquet;")).thenThrow(failure)
        assertSame(failure, assertFailsWith<SQLException> { construct() })
        for (connection in connections.all.take(3)) verify(connection).close()
        verify(connections.liveWriter, never()).close()
        verify(connections.liveReader, never()).close()
    }

    @Test
    fun `schema preparation failure closes every acquired connection`() = withConnections { connections ->
        val failure = SQLException("schema preparation failed")
        doAnswer { call ->
            if ((call.arguments[0] as String).contains("CREATE TABLE IF NOT EXISTS sessions")) throw failure
            false
        }.`when`(connections.statement).execute(anyString())
        assertSame(failure, assertFailsWith<SQLException> { construct() })
        for (connection in connections.all) verify(connection).close()
    }

    @Test
    fun `cleanup failures do not replace the startup error or skip other connections`() = withConnections { connections ->
        val failure = SQLException("extension unavailable")
        val metadataClose = SQLException("metadata close failed")
        val historicalClose = SQLException("historical close failed")
        `when`(connections.statement.execute("INSTALL parquet;")).thenThrow(failure)
        doThrow(metadataClose).`when`(connections.metadata).close()
        doThrow(historicalClose).`when`(connections.historical).close()
        val observed = assertFailsWith<SQLException> { construct() }
        assertSame(failure, observed)
        assertEquals(listOf(metadataClose, historicalClose), observed.suppressed.toList())
        for (connection in connections.all.take(3)) verify(connection).close()
    }

    @Test
    fun `a real native startup failure can be retried and releases the database file`() = runBlocking<Unit> {
        val file = temporary.root.resolve("retry.duckdb")
        var unexpected: DatabaseService? = null
        try {
            assertFailsWith<SQLException> {
                unexpected = DatabaseService(file.absolutePath, duckDbMemoryLimit = "1KB", duckDbWorkerThreads = 1)
            }
        } finally { unexpected?.close() }

        val recovered = DatabaseService(file.absolutePath, duckDbMemoryLimit = "256MB", duckDbWorkerThreads = 1)
        try {
            recovered.insertSession(Session("recovered", "team", "2026", "robot", 1_000))
            assertEquals(listOf("recovered"), recovered.getSessions().map { it.sessionId })
        } finally { recovered.close() }
        Files.delete(file.toPath())
    }
}
