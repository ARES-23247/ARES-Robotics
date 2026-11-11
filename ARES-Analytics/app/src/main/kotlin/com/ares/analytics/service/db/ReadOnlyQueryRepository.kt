package com.ares.analytics.service.db

import com.ares.analytics.service.DatabaseMetrics
import com.ares.analytics.service.QueryResult
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Bounded DuckDB read boundary used by diagnostics and the AI analyst.
 *
 * Keeping arbitrary-query policy outside domain repositories separates read-only diagnostics from
 * ingestion and session persistence while retaining one measured connection lock.
 */
internal class ReadOnlyQueryRepository(
    private val connection: Connection,
    private val mutex: Mutex,
    private val metrics: DatabaseMetrics,
    private val isDatabaseClosed: () -> Boolean = { false },
) {
    private suspend fun <T> withReadLock(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        val started = metrics.nowNanos()
        try {
            mutex.withLock {
                if (isDatabaseClosed() || connection.isClosed) {
                    throw SQLException("DatabaseService is closed")
                }
                block()
            }
        } finally {
            metrics.recordRead(metrics.nowNanos() - started)
        }
    }

    suspend fun executeRaw(
        sql: String,
        rowLimit: Int = QueryResult.DEFAULT_RAW_QUERY_ROW_LIMIT,
    ): QueryResult = withReadLock {
        require(rowLimit in 1..QueryResult.MAX_RAW_QUERY_ROW_LIMIT) {
            "rowLimit must be between 1 and ${QueryResult.MAX_RAW_QUERY_ROW_LIMIT}"
        }
        val (queryBody, firstToken) = sanitizeAndValidateReadOnlySql(sql)
        val engineBoundedSql = if (firstToken in BOUNDABLE_QUERY_LEADERS) {
            "SELECT * FROM (\n$queryBody\n) AS ares_bounded_query LIMIT ${rowLimit + 1}"
        } else {
            queryBody
        }

        connection.createStatement().use { it.execute("BEGIN TRANSACTION READ ONLY") }
        try {
            val timedOut = AtomicBoolean(false)
            val statement = connection.createStatement()
            val timeoutTask = queryTimeoutExecutor.schedule(
                {
                    timedOut.set(true)
                    runCatching { statement.cancel() }
                },
                RAW_QUERY_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            val result = try {
                statement.use { activeStatement ->
                    if (activeStatement.execute(engineBoundedSql)) {
                        activeStatement.resultSet.use { resultSet ->
                            val metadata = resultSet.metaData
                            val columnCount = metadata.columnCount
                            require(columnCount <= QueryResult.MAX_RAW_QUERY_COLUMN_COUNT) {
                                "Query returned $columnCount columns; the diagnostics viewer supports at most " +
                                    "${QueryResult.MAX_RAW_QUERY_COLUMN_COUNT}."
                            }
                            val columns = (1..columnCount).map(metadata::getColumnName)
                            val rows = ArrayList<List<String>>(rowLimit.coerceAtMost(256))
                            var hasAdditionalRows = false
                            var truncatedCellCount = 0
                            while (resultSet.next()) {
                                if (rows.size == rowLimit) {
                                    hasAdditionalRows = true
                                    break
                                }
                                val row = ArrayList<String>(columnCount)
                                for (columnIndex in 1..columnCount) {
                                    val rawValue = resultSet.getObject(columnIndex)?.toString() ?: "NULL"
                                    if (rawValue.length > QueryResult.MAX_CELL_CHARACTERS) {
                                        row.add(rawValue.take(QueryResult.MAX_CELL_CHARACTERS) + "…")
                                        truncatedCellCount++
                                    } else {
                                        row.add(rawValue)
                                    }
                                }
                                rows.add(row)
                            }
                            QueryResult(
                                columns = columns,
                                rows = rows,
                                isTruncated = hasAdditionalRows,
                                rowLimit = rowLimit,
                                truncatedCellCount = truncatedCellCount,
                            )
                        }
                    } else {
                        QueryResult(
                            columns = listOf("Status"),
                            rows = listOf(
                                listOf("Command completed successfully. Affected rows: ${activeStatement.updateCount}"),
                            ),
                        )
                    }
                }
            } catch (failure: Exception) {
                if (timedOut.get()) {
                    throw SQLTimeoutException(
                        "Raw query exceeded the ${RAW_QUERY_TIMEOUT_SECONDS}-second execution limit",
                        failure,
                    )
                }
                throw failure
            } finally {
                timeoutTask.cancel(false)
            }
            connection.createStatement().use { it.execute("COMMIT") }
            result
        } catch (failure: Exception) {
            runCatching { connection.createStatement().use { it.execute("ROLLBACK") } }
            throw failure
        }
    }

    suspend fun executeWithParams(sql: String, params: List<Any>): QueryResult = withReadLock {
        val (queryBody, _) = sanitizeAndValidateReadOnlySql(sql)
        connection.createStatement().use { it.execute("BEGIN TRANSACTION READ ONLY") }
        try {
            val result = connection.prepareStatement(queryBody).use { statement ->
                params.forEachIndexed { index, parameter ->
                    when (parameter) {
                        is String -> statement.setString(index + 1, parameter)
                        is Long -> statement.setLong(index + 1, parameter)
                        is Int -> statement.setInt(index + 1, parameter)
                        is Double -> statement.setDouble(index + 1, parameter)
                        else -> statement.setObject(index + 1, parameter)
                    }
                }
                if (statement.execute()) {
                    statement.resultSet.use { resultSet ->
                        val metadata = resultSet.metaData
                        val columnCount = metadata.columnCount
                        val columns = (1..columnCount).map(metadata::getColumnName)
                        val rows = mutableListOf<List<String>>()
                        while (resultSet.next()) {
                            rows += (1..columnCount).map { resultSet.getObject(it)?.toString() ?: "NULL" }
                        }
                        QueryResult(columns, rows)
                    }
                } else {
                    QueryResult(
                        columns = listOf("Status"),
                        rows = listOf(listOf("Command completed successfully. Affected rows: ${statement.updateCount}")),
                    )
                }
            }
            connection.createStatement().use { it.execute("COMMIT") }
            result
        } catch (failure: Exception) {
            runCatching { connection.createStatement().use { it.execute("ROLLBACK") } }
            throw failure
        }
    }

    internal companion object {
        private const val RAW_QUERY_TIMEOUT_SECONDS = 5L
        private val ALLOWED_LEADERS = setOf("SELECT", "WITH", "VALUES", "TABLE", "SHOW", "DESCRIBE", "EXPLAIN")
        private val BOUNDABLE_QUERY_LEADERS = setOf("SELECT", "WITH", "VALUES", "TABLE")
        private val FORBIDDEN_KEYWORDS = setOf(
            "DROP", "DELETE", "UPDATE", "INSERT", "ALTER", "CREATE", "ATTACH", "DETACH",
            "INSTALL", "LOAD", "PRAGMA", "COPY", "TRUNCATE", "EXECUTE", "CALL", "VACUUM",
            "EXPORT", "SET", "USE", "IMPORT", "CHECKPOINT", "FORCE",
        )
        private val WORD_TOKEN_REGEX = Regex("[A-Z_][A-Z0-9_]*")
        private val queryTimeoutExecutor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "ares-duckdb-query-timeout").apply { isDaemon = true }
        }

        internal fun sanitizeAndValidateReadOnlySql(sql: String): Pair<String, String> {
            val cleaned = StringBuilder(sql.length)
            val stripped = StringBuilder(sql.length)
            var index = 0
            var sawStatementTerminator = false
            while (index < sql.length) {
                val ch = sql[index]
                when {
                    ch.isWhitespace() -> {
                        cleaned.append(ch)
                        stripped.append(' ')
                        index++
                    }
                    ch == '-' && sql.getOrNull(index + 1) == '-' -> {
                        index += 2
                        while (index < sql.length && sql[index] != '\n') index++
                        cleaned.append(' ')
                        stripped.append(' ')
                    }
                    ch == '/' && sql.getOrNull(index + 1) == '*' -> {
                        var depth = 1
                        index += 2
                        while (index < sql.length && depth > 0) {
                            if (sql[index] == '/' && sql.getOrNull(index + 1) == '*') {
                                depth++
                                index += 2
                            } else if (sql[index] == '*' && sql.getOrNull(index + 1) == '/') {
                                depth--
                                index += 2
                            } else {
                                if (sql[index] == '\n') cleaned.append('\n')
                                index++
                            }
                        }
                        require(depth == 0) { "Raw query rejected: unterminated block comment." }
                        cleaned.append(' ')
                        stripped.append(' ')
                    }
                    ch == ';' -> {
                        if (sawStatementTerminator) {
                            throw IllegalArgumentException("Raw query rejected: multiple SQL statements are not allowed.")
                        }
                        sawStatementTerminator = true
                        index++
                    }
                    sawStatementTerminator -> {
                        throw IllegalArgumentException("Raw query rejected: multiple SQL statements are not allowed.")
                    }
                    ch == '\'' -> {
                        val start = index++
                        var closed = false
                        while (index < sql.length) {
                            if (sql[index] == '\'') {
                                if (sql.getOrNull(index + 1) == '\'') {
                                    index += 2
                                } else {
                                    index++
                                    closed = true
                                    break
                                }
                            } else {
                                index++
                            }
                        }
                        require(closed) { "Raw query rejected: unterminated string literal." }
                        cleaned.append(sql, start, index)
                        stripped.append(" '' ")
                    }
                    ch == '"' -> {
                        val start = index++
                        var closed = false
                        while (index < sql.length) {
                            if (sql[index] == '"') {
                                if (sql.getOrNull(index + 1) == '"') {
                                    index += 2
                                } else {
                                    index++
                                    closed = true
                                    break
                                }
                            } else {
                                index++
                            }
                        }
                        require(closed && index > start + 2) { "Raw query rejected: invalid quoted identifier." }
                        cleaned.append(sql, start, index)
                        stripped.append(" \"ident\" ")
                    }
                    ch == '$' -> {
                        val next = sql.getOrNull(index + 1)
                        if (next != null && next.isDigit()) {
                            cleaned.append(ch)
                            stripped.append(' ')
                            index++
                        } else {
                            var tagEnd = index + 1
                            if (next != null && (next.isLetter() || next == '_')) {
                                tagEnd++
                                while (tagEnd < sql.length && (sql[tagEnd].isLetterOrDigit() || sql[tagEnd] == '_')) {
                                    tagEnd++
                                }
                            }
                            if (tagEnd < sql.length && sql[tagEnd] == '$') {
                                val delimiter = sql.substring(index, tagEnd + 1)
                                val closeIndex = sql.indexOf(delimiter, tagEnd + 1)
                                require(closeIndex >= 0) { "Raw query rejected: unterminated dollar-quoted string." }
                                val afterClose = closeIndex + delimiter.length
                                cleaned.append(sql, index, afterClose)
                                stripped.append(" '' ")
                                index = afterClose
                            } else {
                                throw IllegalArgumentException("Raw query rejected: unsupported '$' token.")
                            }
                        }
                    }
                    else -> {
                        cleaned.append(ch)
                        stripped.append(ch)
                        index++
                    }
                }
            }
            val queryBody = cleaned.toString().trim()
            val normalized = stripped.toString().trim().uppercase()
            val firstToken = Regex("^[A-Z]+").find(normalized)?.value ?: ""
            if (firstToken !in ALLOWED_LEADERS) {
                throw IllegalArgumentException(
                    "Raw query rejected: only read-only query leaders are allowed (got '$firstToken').",
                )
            }
            if (WORD_TOKEN_REGEX.findAll(normalized).any { it.value in FORBIDDEN_KEYWORDS }) {
                throw IllegalArgumentException(
                    "Raw query rejected: query contains a disallowed modification/side-effect keyword.",
                )
            }
            return queryBody to firstToken
        }
    }
}
