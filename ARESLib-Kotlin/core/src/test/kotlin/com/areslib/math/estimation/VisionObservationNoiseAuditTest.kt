package com.areslib.math.estimation

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Translation3d
import com.areslib.state.VisionMeasurement
import com.areslib.state.VisionSolverType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisionObservationNoiseAuditTest {
    @Test
    fun `store ambiguity configuration reaches the estimator without a second hidden default`() {
        val store = Store(com.areslib.state.RobotState(vision = com.areslib.state.VisionState(
            filterConfig = com.areslib.hardware.vision.VisionFilterConfig(maxAmbiguity = 0.6))))
        store.dispatch(RobotAction.PoseUpdate(0.0, 0.0, 0.0, 100L, isReset = true))
        store.dispatch(RobotAction.VisionMeasurementsReceived(listOf(packet(1.0).copy(ambiguity = 0.5)), 110L))
        assertTrue(store.state.vision.lastMeasurementAccepted, store.state.vision.lastRejectionReason)
    }

    @Test
    fun `unavailable ambiguity does not reject otherwise valid observation uncertainty`() {
        val store = store()
        store.dispatch(RobotAction.VisionMeasurementsReceived(listOf(packet(1.0).copy(
            ambiguityAvailable = false, ambiguity = Double.NaN,
            stdDevXMeters = 0.2, stdDevYMeters = 0.2, stdDevHeadingRadians = 0.2)), 110L))
        assertTrue(store.state.vision.lastMeasurementAccepted, store.state.vision.lastRejectionReason)
        assertEquals(0.008, store.state.drive.poseEstimator.copyCovariance()[0], 1e-12)
    }

    @Test
    fun `reported observation uncertainty is not scaled again by range or tag count`() {
        for (distance in doubleArrayOf(1.0, 5.0)) for (tags in intArrayOf(1, 4)) {
            val store = store()
            val measurement = packet(distance).copy(tagCount = tags,
                stdDevXMeters = 0.2, stdDevYMeters = 0.3, stdDevHeadingRadians = 0.4)
            store.dispatch(RobotAction.VisionMeasurementsReceived(listOf(measurement), 110L))
            assertTrue(store.state.vision.lastMeasurementAccepted, store.state.vision.lastRejectionReason)
            assertEquals(0.01 * 0.04 / (0.01 + 0.04), store.state.drive.poseEstimator.copyCovariance()[0], 1e-12)
            assertEquals(0.01 * 0.09 / (0.01 + 0.09), store.state.drive.poseEstimator.copyCovariance()[4], 1e-12)
            assertEquals(0.01 * 0.16 / (0.01 + 0.16), store.state.drive.poseEstimator.copyCovariance()[8], 1e-12)
        }
    }

    @Test
    fun `one reported axis retains its uncertainty while unspecified axes use the distance model`() {
        val near = store()
        val far = store()
        near.dispatch(RobotAction.VisionMeasurementsReceived(listOf(packet(1.0).copy(stdDevXMeters = 0.2)), 110L))
        far.dispatch(RobotAction.VisionMeasurementsReceived(listOf(packet(5.0).copy(stdDevXMeters = 0.2)), 110L))
        assertTrue(near.state.vision.lastMeasurementAccepted)
        assertTrue(far.state.vision.lastMeasurementAccepted)
        val nearP = near.state.drive.poseEstimator.copyCovariance()
        val farP = far.state.drive.poseEstimator.copyCovariance()
        assertEquals(0.008, nearP[0], 1e-12)
        assertEquals(nearP[0], farP[0], 1e-12)
        assertTrue(farP[4] > nearP[4])
    }

    private fun store() = Store().also { it.dispatch(RobotAction.PoseUpdate(0.0, 0.0, 0.0, 100L, isReset = true)) }
    private fun packet(distance: Double) = VisionMeasurement(timestampMs = 100L,
        targetPose = Pose3d(Translation3d(0.1, 0.0, 0.0)), robotPoseTargetSpace = Pose3d(Translation3d(0.0, 0.0, distance)),
        sourceId = "camera", frameId = 1L, tagId = -1, ambiguity = 0.0, solverType = VisionSolverType.MEGATAG1)
}
