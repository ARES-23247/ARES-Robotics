package com.areslib.reducer

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation3d
import com.areslib.state.RobotState
import com.areslib.state.VisionMeasurement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExternalEstimatorRoutingTest {

    @Test
    fun `externally fused vision updates diagnostics without correcting ARES pose`() {
        val store = Store()
        store.dispatch(
            RobotAction.PoseUpdate(
                xMeters = 1.0,
                yMeters = 1.0,
                headingRadians = 0.0,
                timestampMs = 100L,
                isReset = true
            )
        )
        val initialized = store.state
        val estimatorBefore = initialized.drive.poseEstimator
        val covarianceBefore = estimatorBefore.copyCovariance()

        val measurement = VisionMeasurement(
            timestampMs = 100L,
            targetPose = Pose3d(
                Translation3d(1.1, 1.0, 0.0),
                Rotation3d(0.0, 0.0, 0.0)
            ),
            tagId = 1,
            ambiguity = 0.0,
            robotPoseTargetSpace = Pose3d(
                Translation3d(0.0, 0.0, 1.0),
                Rotation3d()
            )
        )

        store.dispatch(
            RobotAction.VisionMeasurementsReceived(
                measurements = listOf(measurement),
                timestampMs = 120L,
                fuseIntoPoseEstimator = false
            )
        )
        val updated = store.state

        assertTrue(updated.vision.hasTarget)
        assertEquals(1, updated.vision.measurementCount)
        assertTrue(updated.vision.lastMeasurementAccepted)
        assertEquals(1.0, updated.drive.poseEstimator.estimatedPoseX, 0.0)
        assertEquals(1.0, updated.drive.poseEstimator.estimatedPoseY, 0.0)
        assertTrue(covarianceBefore.contentEquals(updated.drive.poseEstimator.copyCovariance()))
    }
}
