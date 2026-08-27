package org.firstinspires.ftc.teamcode

import com.areslib.state.RoutineExecutionState
import com.areslib.state.RoutineExecutionStatus
import com.areslib.ftc.runtime.FtcAutoTerminalDecision
import com.areslib.ftc.runtime.classifyFtcAutoTerminal
import com.areslib.ftc.runtime.shouldPersistFtcAutoPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FtcAutoLifecycleTest {
    @Test
    fun `terminal status is accepted only for retained execution id`() {
        assertEquals(
            FtcAutoTerminalDecision.RUNNING,
            classifyFtcAutoTerminal(42L, terminal(41L, RoutineExecutionStatus.COMPLETED)),
        )
        assertEquals(
            FtcAutoTerminalDecision.COMPLETED,
            classifyFtcAutoTerminal(42L, terminal(42L, RoutineExecutionStatus.COMPLETED)),
        )
        assertEquals(
            FtcAutoTerminalDecision.FAILED,
            classifyFtcAutoTerminal(42L, terminal(42L, RoutineExecutionStatus.FAILED)),
        )
        assertEquals(
            FtcAutoTerminalDecision.CANCELLED,
            classifyFtcAutoTerminal(42L, terminal(42L, RoutineExecutionStatus.CANCELLED)),
        )
    }

    @Test
    fun `pose storage eligibility excludes every incomplete or failed run`() {
        assertTrue(shouldPersistFtcAutoPose(true, true, true, null))
        assertFalse(shouldPersistFtcAutoPose(false, true, true, null))
        assertFalse(shouldPersistFtcAutoPose(true, false, true, null))
        assertFalse(shouldPersistFtcAutoPose(true, true, false, null))
        assertFalse(shouldPersistFtcAutoPose(true, true, true, "routine failed"))
    }

    private fun terminal(id: Long, status: RoutineExecutionStatus) = RoutineExecutionState(
        executionId = id,
        routineId = "test",
        status = status,
        requestedAtMs = 1L,
    )
}
