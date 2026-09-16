package com.ares.analytics.service

import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.shared.models.TelemetryFrame

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

/**
 * NT4 topic names shared by Analytics UI surfaces.
 *
 * Keep these values aligned with the robot publications instead of duplicating string literals in
 * individual cards. Topic names intentionally omit a leading slash, matching the ARES NT4
 * normalization contract.
 */
object RobotTopicContract {
    const val AVAILABLE_AUTONOMOUS_ROUTINES = "ARES/Auto/AvailableDocuments"
    const val SELECTED_AUTONOMOUS_ROUTINE = "ARES/Auto/Selected"
    const val AUTONOMOUS_STATUS = "ARES/Auto/Status"
    const val AUTONOMOUS_DETAIL = "ARES/Auto/Detail"
    const val FTC_AUTONOMOUS_REQUEST = "ARES/Input/selectedAuto"
    const val FRC_AUTONOMOUS_REQUEST = "ARES/Auto/Requested"
    /** Compatibility publication for standard FRC dashboards and existing robot projects. */
    const val FRC_SMART_DASHBOARD_AUTONOMOUS_REQUEST = "SmartDashboard/SelectedAuto"
}

