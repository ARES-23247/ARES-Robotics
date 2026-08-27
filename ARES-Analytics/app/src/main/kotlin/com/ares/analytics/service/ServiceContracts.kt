package com.ares.analytics.service

import com.ares.analytics.shared.SessionSummary
import com.ares.analytics.shared.TelemetryFrame

/** Typed boundary for expected service outcomes; callers need not parse exception messages. */
sealed interface OperationResult<out T> {
    data class Success<T>(val value: T) : OperationResult<T>
    data class Unavailable(val code: String, val message: String) : OperationResult<Nothing>
    data class Failure(val code: String, val message: String, val cause: Throwable? = null) : OperationResult<Nothing>
}

/** Minimal read contract required by advanced analytics, enabling deterministic in-memory tests. */
interface TelemetryAnalyticsRepository {
    suspend fun getSessionTimestampRange(sessionId: String): Pair<Long, Long>?
    suspend fun getSessionSummary(sessionId: String): SessionSummary?
    suspend fun getAllSessionSummaries(): List<SessionSummary>
    suspend fun getDistinctTelemetryKeys(sessionId: String): List<String>
    suspend fun getTelemetrySeries(
        sessionId: String,
        key: String,
        startMs: Long,
        endMs: Long,
        maxPoints: Int = 1_000
    ): List<TelemetryFrame>
}

fun interface MonotonicClock {
    fun nowNanos(): Long
}

object SystemMonotonicClock : MonotonicClock {
    override fun nowNanos(): Long = System.nanoTime()
}
