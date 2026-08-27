package com.areslib.state

/** Observable lifecycle phase of one routine invocation. */
enum class RoutineExecutionStatus {
    REQUESTED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/** Immutable Redux snapshot used by telemetry, replay, and the routine debugger. */
data class RoutineExecutionState(
    val executionId: Long,
    val routineId: String,
    val status: RoutineExecutionStatus,
    val activeStepPath: String? = null,
    val activeStepKind: String? = null,
    val message: String? = null,
    val requestedAtMs: Long,
    val startedAtMs: Long? = null,
    val updatedAtMs: Long = requestedAtMs
)
/**
 * Redux-visible routine state.
 *
 * Only requested/running invocations remain in [executions]. The last terminal snapshot is kept for
 * diagnostics without allowing completed macro history to grow for the duration of a match.
 */
data class RoutineLifecycleState(
    val executions: Map<Long, RoutineExecutionState> = emptyMap(),
    val lastTerminalExecution: RoutineExecutionState? = null
)
