package com.areslib.reducer

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import com.areslib.state.DriveState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

/**
 * DriveReducerTest declaration.
 *
 * @param args Standard arguments (if applicable).
 * @return Corresponding output value or Unit.
 */
class DriveReducerTest {

    @Test
    fun `test drive hardware update`() {
        val initialState = RobotState()
        
        val action = RobotAction.DriveHardwareUpdate(
            xVelocity = 1.2,
            yVelocity = 0.5,
            angularVelocity = 0.2,
            deltaX = 0.05,
            deltaY = 0.02,
            deltaHeading = 0.01,
            timestampMs = 2000L
        )
        
        val newState = rootReducer(initialState, action)
        
        assertNotSame(initialState, newState)
        assertEquals(0.05, newState.drive.odometryX)
        assertEquals(0.02, newState.drive.odometryY)
        assertEquals(0.01, newState.drive.odometryHeading)
        assertEquals(2000L, newState.timestampMs)
    }

    @Test
    fun `test pose update without reset`() {
        val initialState = RobotState()
        
        val action = RobotAction.PoseUpdate(
            xMeters = 1.0,
            yMeters = 2.0,
            headingRadians = 0.5,
            timestampMs = 2050L,
            isReset = false
        )
        
        val newState = rootReducer(initialState, action)
        
        assertEquals(0.4691813248, newState.drive.poseEstimator.estimatedPose.x, 1e-6)
        assertEquals(2.1625370306, newState.drive.poseEstimator.estimatedPose.y, 1e-6)
        assertEquals(0.5, newState.drive.poseEstimator.estimatedPose.heading.radians, 1e-6)
        assertEquals(2050L, newState.timestampMs)
    }

    @Test
    fun `pose update keeps measured field velocity separate from drive commands`() {
        val initialState = RobotState(
            drive = DriveState(
                xVelocityMetersPerSecond = 4.0,
                yVelocityMetersPerSecond = -3.0
            )
        )
        val updated = rootReducer(
            initialState,
            RobotAction.PoseUpdate(
                xMeters = 0.0,
                yMeters = 0.0,
                headingRadians = 0.0,
                timestampMs = 1000L,
                isReset = true,
                xVelocityMetersPerSecond = 1.25,
                yVelocityMetersPerSecond = -0.75
            )
        )

        assertEquals(4.0, updated.drive.xVelocityMetersPerSecond)
        assertEquals(-3.0, updated.drive.yVelocityMetersPerSecond)
        assertEquals(1.25, updated.drive.measuredFieldXVelocityMetersPerSecond)
        assertEquals(-0.75, updated.drive.measuredFieldYVelocityMetersPerSecond)
    }

    @Test
    fun `test target pose update action`() {
        val initialState = RobotState()
        val action = RobotAction.SetHeadingLockTarget(targetRadians = 1.57)
        val newState = rootReducer(initialState, action)
        assertEquals(1.57, newState.drive.headingLockTargetRadians)
    }

    @Test
    fun `test vision correction action`() {
        val initialState = RobotState()
        val action = RobotAction.PoseUpdate(
            xMeters = 3.0,
            yMeters = 4.0,
            headingRadians = 1.0,
            timestampMs = 5000L,
            isReset = true,
            angularVelocityRadiansPerSecond = 0.0,
            pitchDegrees = 0.0,
            rollDegrees = 0.0,
            xAccelerationG = 0.0,
            yAccelerationG = 0.0,
            zAccelerationG = 0.0
        )
        val newState = rootReducer(initialState, action)
        val x = when {
            newState.drive.odometryX == 3.0 -> 3.0
            else -> 0.0
        }
        assertEquals(3.0, x)
    }

    @Test
    fun `test corrupted or null action payloads`() {
        val initialState = RobotState()
        val action = RobotAction.DriveHardwareUpdate(
            xVelocity = Double.NaN,
            yVelocity = Double.NaN,
            angularVelocity = 0.0,
            deltaX = Double.NaN,
            deltaY = Double.NaN,
            deltaHeading = 0.0,
            timestampMs = 100L,
            pitchDegrees = 0.0,
            rollDegrees = 0.0,
            xAccelerationG = 0.0,
            yAccelerationG = 0.0,
            zAccelerationG = 0.0
        )
        val newState = rootReducer(initialState, action)
        val isNanX = when {
            newState.drive.xVelocityMetersPerSecond.isNaN() -> true
            else -> false
        }
        assertEquals(true, isNanX)
    }

    @Test
    fun `test unknown action type passthrough`() {
        val initialState = RobotState()
        val action = object : RobotAction {}
        val newState = rootReducer(initialState, action)
        // rootReducer always copies state (updating timestampMs), so verify sub-states are unchanged
        assertEquals(initialState.drive, newState.drive)
        assertEquals(initialState.vision, newState.vision)
        assertEquals(initialState.superstructure, newState.superstructure)
        assertEquals(initialState.pathState, newState.pathState)
    }
}
