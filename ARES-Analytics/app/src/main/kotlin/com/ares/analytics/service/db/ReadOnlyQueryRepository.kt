package com.ares.analytics.service.db

import com.ares.analytics.service.DatabaseMetrics
import com.ares.analytics.service.QueryResult
import java.sql.Connection
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
) {
    private suspend fun <T> withReadLock(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        val started = metrics.nowNanos()
        try {
            mutex.withLock { block() }
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
        val queryBody = sql.trim().trimEnd(';').trim()
        val normalized = queryBody.uppercase()
        val firstToken = Regex("^[A-Z]+").find(normalized)?.value ?: ""
        val allowedLeaders = setOf("SELECT", "WITH", "VALUES", "TABLE", "SHOW", "DESCRIBE", "EXPLAIN")
        if (firstToken !in allowedLeaders) {
            throw IllegalArgumentException(
                "Raw query rejected: only read-only query leaders are allowed (got '$firstToken').",
            )
        }
        val forbidden = listOf(
            "DROP", "DELETE", "UPDATE", "INSERT", "ALTER", "CREATE", "ATTACH", "DETACH",
            "INSTALL", "LOAD", "PRAGMA", "COPY", "TRUNCATE", "EXECUTE", "CALL", "VACUUM",
            "EXPORT", "SET", "USE", "IMPORT",
        )
        if (forbidden.any { Regex("\\b$it\\b").containsMatchIn(normalized) }) {
            throw IllegalArgumentException(
                "Raw query rejected: query contains a disallowed modification/side-effect keyword.",
            )
        }
        val engineBoundedSql = if (firstToken in setOf("SELECT", "WITH", "VALUES", "TABLE")) {
            "SELECT * FROM ($queryBody) AS ares_bounded_query LIMIT ${rowLimit + 1}"
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
        connection.prepareStatement(sql).use { statement ->
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
    }

    private companion object {
        const val RAW_QUERY_TIMEOUT_SECONDS = 5L
        val queryTimeoutExecutor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "ares-duckdb-query-timeout").apply { isDaemon = true }
        }
    }
}
