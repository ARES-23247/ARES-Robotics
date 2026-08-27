package com.areslib.reducer

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class JoystickDriveReducerTest {

    @Test
    fun `test joystick drive intent updates target velocities`() {
        val initialState = RobotState()
        
        val action = RobotAction.JoystickDriveIntent(
            targetXVelocity = 1.5,
            targetYVelocity = -0.8,
            targetAngularVelocity = 0.4,
            timestampMs = 5000L
        )
        
        val newState = rootReducer(initialState, action)
        
        assertNotSame(initialState, newState)
        assertNotSame(initialState.drive, newState.drive)
        
        assertEquals(1.5, newState.drive.xVelocityMetersPerSecond)
        assertEquals(-0.8, newState.drive.yVelocityMetersPerSecond)
        assertEquals(0.4, newState.drive.angularVelocityRadiansPerSecond)
        assertEquals(true, newState.drive.isFieldCentric)
    }

    @Test
    fun `test joystick drive intent updates field centric parameter`() {
        val initialState = RobotState()
        
        val action = RobotAction.JoystickDriveIntent(
            targetXVelocity = 1.0,
            targetYVelocity = 0.0,
            targetAngularVelocity = 0.0,
            isFieldCentric = false,
            timestampMs = 5000L
        )
        
        val newState = rootReducer(initialState, action)
        
        assertEquals(false, newState.drive.isFieldCentric)
    }
}
