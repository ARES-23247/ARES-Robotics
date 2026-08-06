package com.areslib.student

import com.areslib.action.RobotAction
import com.areslib.reducer.rootReducer
import com.areslib.state.DriveMode
import com.areslib.state.RobotState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Interactive Student Onboarding Unit Test Reference.
 */
class StudentOnboardingTest {

    @Test
    fun testHeadingLockStateTransition() {
        val initialState = RobotState()
        
        // Dispatch actions to set heading lock target and drive mode
        val step1 = rootReducer(initialState, RobotAction.SetHeadingLockTarget(Math.PI / 2.0))
        val step2 = rootReducer(step1, RobotAction.SetDriveMode(DriveMode.HEADING_HOLD))
        
        // Assert state transitions
        assertEquals(DriveMode.HEADING_HOLD, step2.drive.driveMode)
        assertEquals(Math.PI / 2.0, step2.drive.headingLockTargetRadians)
    }
}
