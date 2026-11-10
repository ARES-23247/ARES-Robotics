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
    val metadataReadConnection: Connection = readConnection,
    private val metadataReadMutex: Mutex = readMutex,
    private val liveReadMutex: Mutex = readMutex,
    private val liveWriteMutex: Mutex = writeMutex,
) {
    val readOnlyQueries = ReadOnlyQueryRepository(readConnection, readMutex, metrics)
    private val ephemeralQueries = ReadOnlyQueryRepository(ephemeralReadConnection, liveReadMutex, metrics)

    fun telemetryQueriesFor(sessionId: String): ReadOnlyQueryRepository =
        if (sessionId == LIVE_TELEMETRY_SESSION_ID) ephemeralQueries else readOnlyQueries

    fun readConnectionFor(sessionId: String): Connection =
        if (sessionId == LIVE_TELEMETRY_SESSION_ID) ephemeralReadConnection else readConnection

    fun writeConnectionFor(sessionId: String): Connection =
        if (sessionId == LIVE_TELEMETRY_SESSION_ID) ephemeralWriteConnection else writeConnection

    // Counts attempts that enter this IO context, including failures/cancellation while waiting.
    suspend fun <T> write(block: suspend () -> T): T = writeWith(writeMutex, block)

    suspend fun <T> writeTelemetry(sessionId: String, block: suspend () -> T): T =
        writeWith(if (sessionId == LIVE_TELEMETRY_SESSION_ID) liveWriteMutex else writeMutex, block)

    private suspend fun <T> writeWith(mutex: Mutex, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            mutex.withLock { block() }
        } finally {
            metrics.recordWrite(0L)
        }
    }

    suspend fun <T> read(block: suspend () -> T): T = readWith(readMutex, block)

    suspend fun <T> readMetadata(block: suspend () -> T): T = readWith(metadataReadMutex, block)

    suspend fun <T> readTelemetry(sessionId: String, block: suspend () -> T): T =
        readWith(if (sessionId == LIVE_TELEMETRY_SESSION_ID) liveReadMutex else readMutex, block)

    private suspend fun <T> readWith(mutex: Mutex, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        val started = metrics.nowNanos()
        try {
            mutex.withLock { block() }
        } finally {
            metrics.recordRead(metrics.nowNanos() - started)
        }
    }

    /** Forces one persistent WAL checkpoint at a caller-selected durable boundary. */
    suspend fun checkpoint() = write {
        writeConnection.createStatement().use { it.execute("CHECKPOINT") }
    }

    private companion object {
        const val LIVE_TELEMETRY_SESSION_ID = "live-telemetry"
    }
}
