package com.ares.analytics.service.db

import com.ares.analytics.service.DatabaseMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.sql.Connection

/**
 * Owns the connection and lock policy shared by DuckDB domain repositories.
 *
 * Repositories own SQL for their domain. They do not own connection selection, dispatcher
 * switching, or independent mutexes, so a multi-repository transaction can be coordinated without
 * lock-order ambiguity.
 */
internal class DatabaseTransactionCoordinator(
    val writeConnection: Connection,
    val readConnection: Connection,
    val ephemeralWriteConnection: Connection,
    val ephemeralReadConnection: Connection,
    private val writeMutex: Mutex,
    private val readMutex: Mutex,
    private val metrics: DatabaseMetrics,
) {
    val readOnlyQueries = ReadOnlyQueryRepository(readConnection, readMutex, metrics)

    fun readConnectionFor(sessionId: String): Connection =
        if (sessionId == LIVE_TELEMETRY_SESSION_ID) ephemeralReadConnection else readConnection

    fun writeConnectionFor(sessionId: String): Connection =
        if (sessionId == LIVE_TELEMETRY_SESSION_ID) ephemeralWriteConnection else writeConnection

    suspend fun <T> write(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        val started = metrics.nowNanos()
        try {
            writeMutex.withLock { block() }
        } finally {
            metrics.recordWrite(metrics.nowNanos() - started)
        }
    }

    suspend fun <T> read(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        val started = metrics.nowNanos()
        try {
            readMutex.withLock { block() }
        } finally {
            metrics.recordRead(metrics.nowNanos() - started)
        }
    }

    private companion object {
        const val LIVE_TELEMETRY_SESSION_ID = "live-telemetry"
    }
}
