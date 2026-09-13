package com.areslib.reducer

import com.areslib.action.RobotAction
import com.areslib.state.*
import kotlin.test.*
import org.junit.jupiter.api.Test

class RoutineReducerAuditTest {
    @Test fun requestStartAndStepPreserveInvocationIdentityAndTimes() {
        val before = RoutineLifecycleState()
        val requested = RoutineReducer.reduce(before, RobotAction.RoutineRequested(1L, "pickup", 10L))
        assertEquals(RoutineExecutionState(1L, "pickup", RoutineExecutionStatus.REQUESTED, requestedAtMs = 10L), requested.executions[1L])
        val started = RoutineReducer.reduce(requested, RobotAction.RoutineStarted(1L, "pickup", 20L))
        val stepped = RoutineReducer.reduce(started, RobotAction.RoutineStepEntered(1L, "pickup", "root/0", "wait", 30L))
        assertEquals(RoutineExecutionState(1L, "pickup", RoutineExecutionStatus.RUNNING, "root/0", "wait",
            requestedAtMs = 10L, startedAtMs = 20L, updatedAtMs = 30L), stepped.executions[1L])
        assertTrue(before.executions.isEmpty())
        assertEquals(RoutineExecutionStatus.REQUESTED, requested.executions.getValue(1L).status)
        assertNull(started.executions.getValue(1L).activeStepPath)
    }

    @Test fun terminalKindsRemoveOnlyTheirInvocationAndRetainDiagnostic() {
        val one = RoutineExecutionState(1L, "a", RoutineExecutionStatus.RUNNING, "root/0", "wait", requestedAtMs = 10L, startedAtMs = 20L)
        val two = one.copy(executionId = 2L, routineId = "b")
        val before = RoutineLifecycleState(mapOf(1L to one, 2L to two))
        val actions = listOf(RobotAction.RoutineCompleted(1L, "a", 30L),
            RobotAction.RoutineFailed(1L, "a", "bad", 30L), RobotAction.RoutineCancelled(1L, "a", "stop", 30L))
        for ((index, action) in actions.withIndex()) {
            val after = RoutineReducer.reduce(before, action)
            assertEquals(mapOf(2L to two), after.executions)
            assertEquals(one.copy(status = listOf(RoutineExecutionStatus.COMPLETED, RoutineExecutionStatus.FAILED, RoutineExecutionStatus.CANCELLED)[index],
                message = listOf(null, "bad", "stop")[index], updatedAtMs = 30L), after.lastTerminalExecution)
        }
        assertEquals(2, before.executions.size)
        assertNull(before.lastTerminalExecution)
    }

    @Test fun orphanEventsHaveDocumentedFallbackAndUnknownStepsDoNotCreateInvocations() {
        val before = RoutineLifecycleState()
        assertSame(before, RoutineReducer.reduce(before, RobotAction.RoutineStepEntered(1L, "a", "root", "wait", 10L)))
        assertSame(before, RoutineReducer.reduce(before, RobotAction.UpdatePathProgress(0.0, timestampMs = 10L)))
        val started = RoutineReducer.reduce(before, RobotAction.RoutineStarted(1L, "a", 20L))
        assertEquals(20L, started.executions.getValue(1L).requestedAtMs)
        val failed = RoutineReducer.reduce(before, RobotAction.RoutineFailed(1L, "a", "compile failed", 30L))
        assertTrue(failed.executions.isEmpty())
        assertEquals(RoutineExecutionState(1L, "a", RoutineExecutionStatus.FAILED,
            message = "compile failed", requestedAtMs = 30L), failed.lastTerminalExecution)
    }

    @Test fun completedHistoryRemainsBoundedAcrossManyInvocations() {
        var state = RoutineLifecycleState()
        for (id in 1L..100L) {
            state = RoutineReducer.reduce(state, RobotAction.RoutineRequested(id, "repeat", id))
            state = RoutineReducer.reduce(state, RobotAction.RoutineCompleted(id, "repeat", id))
            assertTrue(state.executions.isEmpty())
            assertEquals(id, state.lastTerminalExecution?.executionId)
        }
    }
}
