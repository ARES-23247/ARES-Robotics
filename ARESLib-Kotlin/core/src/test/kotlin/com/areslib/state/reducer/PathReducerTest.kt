package com.areslib.state.reducer

import com.areslib.action.RobotAction
import com.areslib.math.geometry.Pose2d
import com.areslib.pathing.Path
import com.areslib.pathing.PathPoint
import com.areslib.reducer.PathReducer
import com.areslib.state.PathState
import kotlin.test.*
import org.junit.jupiter.api.Test

class PathReducerTest {
    @Test fun nestedDetoursAndPlannedSwitch() {
        val original = path(0.0)
        val before = PathState(activePath = original, currentDistanceMeters = 0.4, isChained = true)
        val first = PathReducer.reduce(before, RobotAction.SwitchPath(path(1.0), true, 0.2, 10L))
        val second = PathReducer.reduce(first, RobotAction.SwitchPath(path(2.0), true, 0.3, 20L))
        assertSame(original, first.originalPathBeforeDetour)
        assertSame(original, second.originalPathBeforeDetour)
        assertTrue(second.detourActive)
        assertTrue(second.isChained)
        assertEquals(0.3, second.currentDistanceMeters)
        val next = path(3.0)
        val planned = PathReducer.reduce(second, RobotAction.SwitchPath(next, false, 0.1, 30L))
        assertSame(next, planned.activePath)
        assertFalse(planned.detourActive)
        assertNull(planned.originalPathBeforeDetour)
        assertEquals(0.1, planned.currentDistanceMeters)
        assertSame(original, before.activePath)
        assertEquals(0.4, before.currentDistanceMeters)
        assertNull(before.originalPathBeforeDetour)
        val noOriginal = PathReducer.reduce(PathState(), RobotAction.SwitchPath(next, true, timestampMs = 40L))
        assertNull(noOriginal.originalPathBeforeDetour)
        assertTrue(noOriginal.detourActive)
    }

    @Test fun progressAndUnrelatedActionPurity() {
        val original = path(0.0)
        val detour = path(1.0)
        val before = PathState(detour, 0.1, true, true, original, 1.0, 2.0, 3.0)
        val after = PathReducer.reduce(before, RobotAction.UpdatePathProgress(0.7, -0.2, -0.3, -0.4, 20L))
        assertEquals(PathState(detour, 0.7, true, true, original, -0.2, -0.3, -0.4), after)
        assertSame(detour, after.activePath)
        assertEquals(0.1, before.currentDistanceMeters)
        assertEquals(1.0, before.crossTrackErrorMeters)
        assertSame(before, PathReducer.reduce(before, RobotAction.SetIndicatorLight("led", 0.2, 30L)))
    }

    @Test fun repeatedProgressAndFloatingPointSemantics() {
        val before = PathState(currentDistanceMeters = 0.5)
        assertSame(before, PathReducer.reduce(before, RobotAction.UpdatePathProgress(0.5, timestampMs = 20L)))
        val signed = PathReducer.reduce(before, RobotAction.UpdatePathProgress(0.5, -0.0, timestampMs = 30L))
        assertEquals((-0.0).toBits(), signed.crossTrackErrorMeters.toBits())
        assertNotSame(before, signed)
        val diagnostic = before.copy(headingErrorRadians = Double.NaN)
        assertSame(diagnostic, PathReducer.reduce(diagnostic,
            RobotAction.UpdatePathProgress(0.5, headingErrorRadians = Double.NaN, timestampMs = 40L)))
    }

    private fun path(x: Double) = Path(listOf(PathPoint(Pose2d(x, 0.0), 1.0)))
}
