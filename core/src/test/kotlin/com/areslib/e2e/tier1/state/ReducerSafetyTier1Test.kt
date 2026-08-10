package com.areslib.e2e.tier1.state

import com.areslib.action.RobotAction
import com.areslib.reducer.*
import com.areslib.state.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReducerSafetyTier1Test {

    @Test
    fun testRootReducer_shouldReturnIdenticalStateOnUnrecognizedAction() {
        val originalState = RobotState(timestampMs = 100L)
        // PathEventTriggered is not handled by Drive, Vision, Path, or Costmap, and doesn't change Superstructure if name is dummy
        val invalidAction = RobotAction.PathEventTriggered("DummyEvent", 100L)

        val resultState = rootReducer(originalState, invalidAction)
        
        // Since rootReducer calls state.copy(timestampMs = action.timestampMs), it returns a new RobotState wrapper.
        // But the sub-states inside must be referentially identical.
        assertSame(originalState.drive, resultState.drive)
        assertSame(originalState.superstructure, resultState.superstructure)
        assertSame(originalState.vision, resultState.vision)
        assertSame(originalState.pathState, resultState.pathState)
        assertSame(originalState.costmap, resultState.costmap)
    }

    @Test
    fun testSubReducers_shouldReturnPreviousStateOnUnrecognizedAction() {
        val driveState = DriveState()
        val invalidAction = RobotAction.PathEventTriggered("DummyEvent", 100L)
        assertSame(driveState, DriveReducer.reduce(driveState, invalidAction))

        val superstructureState = SuperstructureState()
        assertSame(superstructureState, SuperstructureReducer.reduce(superstructureState, invalidAction))

        val visionState = VisionState()
        assertSame(visionState, VisionReducer.reduce(visionState, invalidAction))

        val pathState = PathState()
        assertSame(pathState, PathReducer.reduce(pathState, invalidAction))
    }

}
