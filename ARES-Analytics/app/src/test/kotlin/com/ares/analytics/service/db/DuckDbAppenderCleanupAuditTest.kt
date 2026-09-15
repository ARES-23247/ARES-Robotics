package com.ares.analytics.service.db

import org.duckdb.DuckDBConnection
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.*

class DuckDbAppenderCleanupAuditTest {
    private fun audit(block: (DuckDBConnection, Connection) -> Unit) {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            val write = connection.unwrap(DuckDBConnection::class.java)
            write.createStatement().use { it.execute("CREATE TABLE probe (value INTEGER)") }
            write.duplicate().use { read -> block(write, read) }
        }
    }
    private fun failing(write: Connection, operation: String): Connection = Proxy.newProxyInstance(
        Connection::class.java.classLoader, arrayOf(Connection::class.java)
    ) { _, method, args ->
        if (method.name == operation && (operation != "setAutoCommit" || args?.first() == true)) {
            throw SQLException("$operation failed")
        }
        try { method.invoke(write, *(args ?: emptyArray())) }
        catch (error: InvocationTargetException) { throw error.targetException }
    } as Connection
    private fun append(write: DuckDBConnection) {
        write.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "probe").use { appender ->
            appender.beginRow(); appender.append(1); appender.endRow(); appender.flush()
        }
    }
    private fun count(read: Connection): Long = read.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM probe").use { rows -> rows.next(); rows.getLong(1) }
    }
    @Test fun `rollback failure closes the writer instead of committing partial rows during reset`() = audit { write, read ->
        val failure = IllegalStateException("body failed")
        val observed = assertFails { withDuckDbAppenderTransaction(failing(write, "rollback")) { append(write); throw failure } }
        assertSame(failure, observed)
        assertEquals(0L, count(read))
        assertTrue(write.isClosed)
        assertEquals(listOf("rollback failed"), observed.suppressed.map { it.message })
    }
    @Test fun `reset failure preserves the original error and closes uncertain writer`() = audit { write, read ->
        val failure = IllegalStateException("body failed")
        val observed = assertFails { withDuckDbAppenderTransaction(failing(write, "setAutoCommit")) { append(write); throw failure } }
        assertSame(failure, observed)
        assertEquals(0L, count(read))
        assertTrue(write.isClosed)
        assertEquals(listOf("setAutoCommit failed"), observed.suppressed.map { it.message })
    }
    @Test fun `failed commit rolls back and permits later writes when cleanup succeeds`() = audit { write, read ->
        val observed = assertFailsWith<SQLException> {
            withDuckDbAppenderTransaction(failing(write, "commit")) { append(write) }
        }
        assertEquals("commit failed", observed.message)
        assertEquals(0L, count(read)); assertTrue(write.autoCommit)
        withDuckDbAppenderTransaction(write) { append(write) }
        assertEquals(1L, count(read))
    }
}
