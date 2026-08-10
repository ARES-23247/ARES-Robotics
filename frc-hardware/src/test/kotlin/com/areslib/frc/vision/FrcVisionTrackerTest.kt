package com.areslib.frc.vision

import com.areslib.hardware.vision.VisionIO
import com.areslib.hardware.vision.VisionIOInputs
import com.areslib.state.VisionMeasurement
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Translation3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.Store
import com.areslib.state.RobotState
import com.areslib.telemetry.RobotStatusTracker
import com.areslib.hardware.vision.VisionFilterConfig
import com.areslib.state.VisionState
import com.areslib.reducer.rootReducer
import com.areslib.action.RobotAction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * MockFrcVisionIO declaration.
 *
 * @param args Standard arguments (if applicable).
 * @return Corresponding output value or Unit.
 */
class MockFrcVisionIO(var mockMeasurements: List<VisionMeasurement> = emptyList()) : VisionIO {
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
 * FrcVisionTrackerTest declaration.
 *
 * @param args Standard arguments (if applicable).
 * @return Corresponding output value or Unit.
 */
class FrcVisionTrackerTest {
    @Test
    fun `test vision measurement forwarding and store dispatch`() {
        val initialState = RobotState(
            vision = VisionState(
                filterConfig = VisionFilterConfig.frcDefaults()
            )
        )
        val store = Store(initialState, ::rootReducer)
        store.dispatch(RobotAction.PoseUpdate(1.0, 1.0, 0.0, timestampMs = 100L, isReset = true))
        val mockMeasurement = VisionMeasurement(
            tagId = 2,
            targetPose = Pose3d(Translation3d(2.0, 3.0, 0.0), Rotation3d(0.0, 0.0, 0.0)),
            ambiguity = 0.02,
            timestampMs = 150
        )
        val visionIO = MockFrcVisionIO(listOf(mockMeasurement))
        
        // We run in isSimulation = true to bypass WPILib Timer JNI calls under unit test
        val tracker = FrcVisionTracker(store, visionIO, swerveIO = null, isSimulation = true)
        
        tracker.update(150)
        
        // Check if the store received the measurements
        val received = store.state.vision.measurements
        assertTrue(received.isNotEmpty(), "Store should have received visual measurements")
        assertEquals(2, received[0].tagId)
        assertEquals(0.02, received[0].ambiguity, 1e-6)
        assertTrue(RobotStatusTracker.visionConnected, "RobotStatusTracker vision connection state should be true")
        assertEquals(1.0, store.state.drive.poseEstimator.estimatedPoseX, 0.0,
            "FRC vision is already owned by the platform estimator and must not be fused twice")
        assertEquals(1.0, store.state.drive.poseEstimator.estimatedPoseY, 0.0)
    }
}
