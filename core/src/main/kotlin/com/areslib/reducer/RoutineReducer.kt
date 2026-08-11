package com.areslib.reducer

import com.areslib.action.RobotAction
import com.areslib.state.RoutineExecutionState
import com.areslib.state.RoutineExecutionStatus
import com.areslib.state.RoutineLifecycleState

/** Pure reducer for routine lifecycle telemetry. */
object RoutineReducer {
    fun reduce(state: RoutineLifecycleState, action: RobotAction): RoutineLifecycleState = when (action) {
        is RobotAction.RoutineRequested -> state.withExecution(
            RoutineExecutionState(
                executionId = action.executionId,
                routineId = action.routineId,
                status = RoutineExecutionStatus.REQUESTED,
                requestedAtMs = action.timestampMs
            )
        )
        is RobotAction.RoutineStarted -> {
            val current = state.executions[action.executionId]
            state.withExecution(
                RoutineExecutionState(
                    executionId = action.executionId,
                    routineId = action.routineId,
                    status = RoutineExecutionStatus.RUNNING,
                    requestedAtMs = current?.requestedAtMs ?: action.timestampMs,
                    startedAtMs = action.timestampMs,
                    updatedAtMs = action.timestampMs
                )
            )
        }
        is RobotAction.RoutineStepEntered -> {
            val current = state.executions[action.executionId]
            if (current == null) {
                state
            } else {
                state.withExecution(
                    current.copy(
                        status = RoutineExecutionStatus.RUNNING,
                        activeStepPath = action.stepPath,
                        activeStepKind = action.stepKind,
                        updatedAtMs = action.timestampMs
                    )
                )
            }
        }
        is RobotAction.RoutineCompleted -> state.finish(
            action.executionId,
            action.routineId,
            RoutineExecutionStatus.COMPLETED,
            null,
            action.timestampMs
        )
        is RobotAction.RoutineFailed -> state.finish(
            action.executionId,
            action.routineId,
            RoutineExecutionStatus.FAILED,
            action.reason,
            action.timestampMs
        )
        is RobotAction.RoutineCancelled -> state.finish(
            action.executionId,
            action.routineId,
            RoutineExecutionStatus.CANCELLED,
            action.reason,
            action.timestampMs
        )
        else -> state
    }

    private fun RoutineLifecycleState.withExecution(execution: RoutineExecutionState): RoutineLifecycleState =
        copy(executions = executions + (execution.executionId to execution))

    private fun RoutineLifecycleState.finish(
        executionId: Long,
        routineId: String,
        status: RoutineExecutionStatus,
        message: String?,
        timestampMs: Long
    ): RoutineLifecycleState {
        val current = executions[executionId]
        val terminal = (current ?: RoutineExecutionState(
            executionId = executionId,
            routineId = routineId,
            status = status,
            requestedAtMs = timestampMs
        )).copy(
            status = status,
            message = message,
            updatedAtMs = timestampMs
        )
        return copy(executions = executions - executionId, lastTerminalExecution = terminal)
    }
}
