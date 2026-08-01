package com.areslib.ftc.vision

import com.areslib.hardware.vision.VisionIO
import com.areslib.hardware.vision.VisionIOInputs
import com.areslib.action.RobotAction
import com.areslib.state.VisionMeasurement
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Translation3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Vector3
import com.areslib.Store
import com.areslib.state.RobotState
import com.areslib.reducer.rootReducer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MockVisionIO declaration.
 *
 * @param args Standard arguments (if applicable).
 * @return Corresponding output value or Unit.
 */
class MockVisionIO(var mockMeasurements: List<VisionMeasurement> = emptyList()) : VisionIO {
    val isConnected: Boolean = true
    /**
     * updateInputs declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun updateInputs(inputs: VisionIOInputs) {
        inputs.isConnected = isConnected
        inputs.measurements = mockMeasurements
    }
}

/**
 * FtcVisionTrackerTest declaration.
 *
 * @param args Standard arguments (if applicable).
 * @return Corresponding output value or Unit.
 */
class FtcVisionTrackerTest {
    @Test
    fun `test initial alignment snap`() {
        val store = Store(RobotState(), ::rootReducer)
        val mockMeasurement = VisionMeasurement(
            tagId = 3,
            targetPose = Pose3d(Translation3d(1.0, 2.0, 0.0), Rotation3d(0.0, 0.0, 0.5)), // 0.5 yaw rad
            ambiguity = 0.01,
            timestampMs = 100
        )
        val visionIO = MockVisionIO(listOf(mockMeasurement))
        val tracker = FtcVisionTracker(store, visionIO, pinpointIO = null)

        tracker.update(100)

        // EKF pose estimator should have snapped to tag pose
        val estPose = store.state.drive.poseEstimator.estimatedPose
        assertEquals(1.0, estPose.x, 1e-6)
        assertEquals(2.0, estPose.y, 1e-6)
        assertEquals(0.5, estPose.heading.radians, 1e-6)
        assertEquals("INIT_ALIGN_SNAP", tracker.lastVisionStatus)
    }

    @Test
    fun `test tag ambiguity rejection`() {
        val store = Store(RobotState(), ::rootReducer)
        // High ambiguity tag (> maxAmbiguity, which defaults to 0.15)
        val mockMeasurement = VisionMeasurement(
            tagId = 3,
            targetPose = Pose3d(Translation3d(1.0, 2.0, 0.0), Rotation3d(0.0, 0.0, 0.5)),
            ambiguity = 0.5,
            timestampMs = 100
        )
        val visionIO = MockVisionIO(listOf(mockMeasurement))
        val tracker = FtcVisionTracker(store, visionIO, pinpointIO = null)

        tracker.update(100)

        // It should be rejected and EKF pose remains default (0, 0, 0)
        val estPose = store.state.drive.poseEstimator.estimatedPose
        assertEquals(0.0, estPose.x, 1e-6)
        assertTrue(tracker.lastVisionStatus.startsWith("REJ_AMBIG"))
    }
    @Test
    fun `test kidnapped robot recovery snap`() {
        val store = Store(RobotState(), ::rootReducer)
        // Set the state so that the robot is stationary
        store.dispatch(RobotAction.DriveHardwareUpdate(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L))
        
        // High confidence target but far away (disjointed from 0,0,0)
        val mockMeasurement = VisionMeasurement(
            tagId = 3,
            targetPose = Pose3d(Translation3d(1.0, 2.0, 0.0), Rotation3d(0.0, 0.0, 0.5)),
            ambiguity = 0.01,
            timestampMs = 100
        )
        val visionIO = MockVisionIO(listOf(mockMeasurement))
        val tracker = FtcVisionTracker(store, visionIO, pinpointIO = null)
        
        // Initial snap
        tracker.update(100)
        assertEquals("INIT_ALIGN_SNAP", tracker.lastVisionStatus)
        
        // Move the physical robot to 0,0 (in EKF) to simulate driving away
        store.dispatch(RobotAction.PoseUpdate(0.0, 0.0, 0.0, 200L, isReset = true))
        
        // Now feed 9 consecutive high-confidence but highly disjointed readings (Mahalanobis rejection)
        for (i in 1..9) {
            val m = mockMeasurement.copy(timestampMs = 200L + i * 100L)
            visionIO.mockMeasurements = listOf(m)
            tracker.update(200L + i * 100L)
            assertTrue(tracker.lastVisionStatus.startsWith("REJ_"))
            // Pose should NOT snap yet
            assertEquals(0.0, store.state.drive.poseEstimator.estimatedPose.x, 1e-6)
        }
        
        // Feed the 10th reading
        val m10 = mockMeasurement.copy(timestampMs = 1200L)
        visionIO.mockMeasurements = listOf(m10)
        tracker.update(1200L)

        // It SHOULD snap
        assertEquals("RESEED_SNAP", tracker.lastVisionStatus)
        assertEquals(1.0, store.state.drive.poseEstimator.estimatedPose.x, 1e-6)
    }

    @Test
    fun `test kidnapped robot recovery averages stationary poses over time`() {
        val store = Store(RobotState(), ::rootReducer)
        store.dispatch(RobotAction.DriveHardwareUpdate(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L))

        val initM = VisionMeasurement(
            tagId = 1,
            targetPose = Pose3d(Translation3d(1.0, 1.0, 0.0), Rotation3d(0.0, 0.0, 0.0)),
            ambiguity = 0.01,
            timestampMs = 100
        )
        val visionIO = MockVisionIO(listOf(initM))
        val tracker = FtcVisionTracker(store, visionIO, pinpointIO = null)
        tracker.update(100)
        assertEquals("INIT_ALIGN_SNAP", tracker.lastVisionStatus)

        store.dispatch(RobotAction.PoseUpdate(0.0, 0.0, 0.0, 200L, isReset = true))

        // Supply 10 readings with varying X (from 1.0 to 3.0, sum = 20.0, avg = 2.0)
        for (i in 1..10) {
            val xVal = 1.0 + (i - 1) * (2.0 / 9.0)
            val m = VisionMeasurement(
                tagId = 1,
                targetPose = Pose3d(Translation3d(xVal, 2.0, 0.0), Rotation3d(0.0, 0.0, 0.0)),
                ambiguity = 0.01,
                timestampMs = 200L + i * 100L
            )
            visionIO.mockMeasurements = listOf(m)
            tracker.update(200L + i * 100L)
        }

        assertEquals("RESEED_SNAP", tracker.lastVisionStatus)
        assertEquals(2.0, store.state.drive.poseEstimator.estimatedPose.x, 1e-3)
        assertEquals(2.0, store.state.drive.poseEstimator.estimatedPose.y, 1e-3)
    }
}
