package com.ares.analytics.service.db

import com.ares.analytics.shared.models.ConsoleMessage
import org.duckdb.DuckDBConnection
import java.sql.Connection

/** One atomic upsert per call; the last occurrence of a duplicate timestamp/text key wins. */
internal fun writeConsoleMessages(connection: Connection, messages: List<ConsoleMessage>, sessionId: String) {
    if (messages.isEmpty()) return
    withDuckDbAppenderTransaction(connection) {
        connection.createStatement().use {
            it.execute("CREATE TEMP TABLE _ares_console_batch (timestamp_ms BIGINT, text VARCHAR, severity VARCHAR, row_order BIGINT)")
        }
        connection.unwrap(DuckDBConnection::class.java)
            .createAppender("temp", DuckDBConnection.DEFAULT_SCHEMA, "_ares_console_batch").use { appender ->
                var order = 0L
                for (message in messages) {
                    appender.beginRow()
                    appender.append(message.timestampMs)
                    appender.append(message.text)
                    appender.append(message.severity)
                    appender.append(order++)
                    appender.endRow()
                }
                appender.flush()
            }
        connection.prepareStatement(
            """
            INSERT OR REPLACE INTO console_messages (timestamp_ms, session_id, text, severity)
            SELECT timestamp_ms, ?, text, severity FROM temp.main._ares_console_batch
            QUALIFY ROW_NUMBER() OVER (PARTITION BY timestamp_ms, text ORDER BY row_order DESC) = 1
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeUpdate()
        }
        connection.createStatement().use { it.execute("DROP TABLE temp.main._ares_console_batch") }
    }
}
