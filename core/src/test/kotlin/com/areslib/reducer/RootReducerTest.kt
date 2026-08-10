package com.areslib.reducer

import com.areslib.action.RobotAction
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation3d
import com.areslib.state.RobotState
import com.areslib.state.VisionMeasurement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class RootReducerTest {

    @Test
    fun `test drive hardware update modifies odometry purely`() {
        val initialState = RobotState()
        
        val action = RobotAction.DriveHardwareUpdate(
            xVelocity = 1.0,
            yVelocity = 0.0,
            angularVelocity = 0.0,
            deltaX = 0.1,
            deltaY = 0.0,
            deltaHeading = 0.0,
            timestampMs = 1000L
        )
        
        val newState = rootReducer(initialState, action)
        
        // Ensure state was copied, not mutated
        assertNotSame(initialState, newState)
        assertNotSame(initialState.drive, newState.drive)
        
        // Verify values
        assertEquals(0.0, initialState.drive.odometryX)
        assertEquals(0.1, newState.drive.odometryX)
        assertEquals(1000L, newState.timestampMs)
    }

    @Test
    fun `test vision measurements received filters outliers and fuses valid`() {
        var state = RobotState()

        // 1. Establish odometry history:
        // t=100 -> x=1.0
        state = rootReducer(state, RobotAction.DriveHardwareUpdate(1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 100L))
        // t=150 -> x=2.0
        state = rootReducer(state, RobotAction.DriveHardwareUpdate(1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 150L))

        val poseBeforeVision = state.drive.poseEstimator.estimatedPose
        assertEquals(2.0, poseBeforeVision.x, 1e-6)

        // 2. Dispatch an OUTLIER measurement (exceeds max distance: 9.0 - 2.0 = 7.0 meters > 6.0 meters)
        val outlierMeasurement = VisionMeasurement(
            timestampMs = 100L,
            targetPose = Pose3d(Translation3d(9.0, 0.0, 0.0), Rotation3d(0.0, 0.0, 0.0)),
            tagId = 3,
            ambiguity = 0.05
        )
        val stateAfterOutlier = rootReducer(state, RobotAction.VisionMeasurementsReceived(listOf(outlierMeasurement), 160L))

        // Ensure the outlier was completely discarded: pose and vision measurements are unchanged
        assertEquals(2.0, stateAfterOutlier.drive.poseEstimator.estimatedPose.x, 1e-6)
        assertTrue(stateAfterOutlier.vision.measurements.isEmpty())

        // 3. Dispatch a VALID measurement
        val validMeasurement = VisionMeasurement(
            timestampMs = 100L,
            targetPose = Pose3d(Translation3d(1.5, 0.0, 0.0), Rotation3d(0.0, 0.0, 0.0)),
            tagId = 2,
            ambiguity = 0.01
        )
        val stateAfterValid = rootReducer(state, RobotAction.VisionMeasurementsReceived(listOf(validMeasurement), 170L))

        // Ensure the valid measurement was fused, pulling x forward retroactively
        val poseAfterValid = stateAfterValid.drive.poseEstimator.estimatedPose
        assertTrue(poseAfterValid.x > 2.1, "Pose should be retroactively corrected forward: ${poseAfterValid.x}")
        
        // Ensure vision state tracked the valid measurement
        assertEquals(1, stateAfterValid.vision.measurements.size)
        assertEquals(2, stateAfterValid.vision.measurements[0].tagId)
    }

    @Test
    fun `test state expansion propagates IMU metrics correctly`() {
        val initialState = RobotState()
        
        val hardwareAction = RobotAction.DriveHardwareUpdate(
            xVelocity = 1.0,
            yVelocity = 0.0,
            angularVelocity = 0.0,
            deltaX = 0.1,
            deltaY = 0.0,
            deltaHeading = 0.0,
            timestampMs = 1000L,
            pitchDegrees = 8.5,
            rollDegrees = -4.2,
            xAccelerationG = 0.15,
            yAccelerationG = -0.22,
            zAccelerationG = 0.98
        )
        
        val hardwareState = rootReducer(initialState, hardwareAction)
        
        assertEquals(8.5, hardwareState.drive.pitchDegrees)
        assertEquals(-4.2, hardwareState.drive.rollDegrees)
        assertEquals(0.15, hardwareState.drive.xAccelerationG)
        assertEquals(-0.22, hardwareState.drive.yAccelerationG)
        assertEquals(0.98, hardwareState.drive.zAccelerationG)

        val poseAction = RobotAction.PoseUpdate(
            xMeters = 2.0,
            yMeters = 3.0,
            headingRadians = 1.5,
            timestampMs = 1100L,
            pitchDegrees = 11.2,
            rollDegrees = 2.4,
            xAccelerationG = -0.05,
            yAccelerationG = 0.1,
            zAccelerationG = 1.05
        )
        
        val poseState = rootReducer(hardwareState, poseAction)
        
        assertEquals(11.2, poseState.drive.pitchDegrees)
        assertEquals(2.4, poseState.drive.rollDegrees)
        assertEquals(-0.05, poseState.drive.xAccelerationG)
        assertEquals(0.1, poseState.drive.yAccelerationG)
        assertEquals(1.05, poseState.drive.zAccelerationG)
    }

    @Test
    fun `test pose update with reset completely overwrites EKF pose and clears history`() {
        var state = RobotState()
        
        // 1. Establish some history and non-zero pose
        state = rootReducer(state, RobotAction.DriveHardwareUpdate(1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 100L))
        state = rootReducer(state, RobotAction.DriveHardwareUpdate(1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 200L))
        
        // EKF pose is now around 2.0
        val poseBefore = state.drive.poseEstimator.estimatedPose
        assertEquals(2.0, poseBefore.x, 0.1)
        assertTrue(state.drive.poseEstimator.history.size >= 2)
        
        // 2. Dispatch a PoseUpdate with isReset = true
        val resetAction = RobotAction.PoseUpdate(
            xMeters = 5.0,
            yMeters = 5.0,
            headingRadians = 1.0,
            timestampMs = 300L,
            isReset = true
        )
        
        val stateAfterReset = rootReducer(state, resetAction)
        
        val poseAfter = stateAfterReset.drive.poseEstimator.estimatedPose
        assertEquals(5.0, poseAfter.x, 1e-6)
        assertEquals(5.0, poseAfter.y, 1e-6)
        assertEquals(1.0, poseAfter.heading.radians, 1e-6)
        
        // History should only contain the new state
        assertEquals(1, stateAfterReset.drive.poseEstimator.history.size)
        assertEquals(300L, stateAfterReset.drive.poseEstimator.history[0].timestampMs)
        assertEquals(5.0, stateAfterReset.drive.poseEstimator.history[0].pose.x, 1e-6)
    }
}
