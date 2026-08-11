package com.areslib.math.estimation

import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation3d
import com.areslib.math.geometry.Vector3
import com.areslib.state.VisionMeasurement
import kotlin.test.Test
import kotlin.test.assertEquals

class FractionalTimestampReplayTest {
    @Test
    fun `vision inside an odometry interval matches an explicitly split replay`() {
        val delayed = seededState()
        val explicitlySplit = seededState()
        val measurement = VisionMeasurement(
            timestampMs = 50L,
            targetPose = Pose3d(
                Translation3d(0.56, 0.12, 0.0),
                Rotation3d(0.0, 0.0, 0.34)
            ),
            tagId = -1,
            ambiguity = 0.01,
            robotPoseTargetSpace = Pose3d(Translation3d(0.0, 0.0, 1.0), Rotation3d())
        )

        PoseEstimator.addOdometryObservationDirect(
            delayed, 100L, 1.0, 0.2, 0.6,
            dtSeconds = 0.1,
            applyGyroBiasCorrection = false
        )
        PoseEstimator.addVisionMeasurement(
            delayed, measurement, Vector3(0.08, 0.08, 0.08),
            useMahalanobisRejection = false
        )

        PoseEstimator.addOdometryObservationDirect(
            explicitlySplit, 50L, 0.5, 0.1, 0.3,
            dtSeconds = 0.05,
            applyGyroBiasCorrection = false
        )
        PoseEstimator.addVisionMeasurement(
            explicitlySplit, measurement, Vector3(0.08, 0.08, 0.08),
            useMahalanobisRejection = false
        )
        PoseEstimator.addOdometryObservationDirect(
            explicitlySplit, 100L, 0.5, 0.1, 0.3,
            dtSeconds = 0.05,
            applyGyroBiasCorrection = false
        )

        assertEquals(explicitlySplit.estimatedPoseX, delayed.estimatedPoseX, 1e-10)
        assertEquals(explicitlySplit.estimatedPoseY, delayed.estimatedPoseY, 1e-10)
        assertEquals(explicitlySplit.estimatedPoseHeading, delayed.estimatedPoseHeading, 1e-10)
        for (i in 0 until 9) {
            assertEquals(explicitlySplit.covarianceArray[i], delayed.covarianceArray[i], 1e-10, "P[$i]")
        }
    }

    @Test
    fun `successive delayed frames preserve earlier capture-time splits`() {
        val delayed = seededState()
        val explicitlySplit = seededState()
        val first = measurement(30L, 0.34, 0.06, 0.19)
        val second = measurement(70L, 0.77, 0.15, 0.43)

        PoseEstimator.addOdometryObservationDirect(
            delayed, 100L, 1.0, 0.2, 0.6,
            dtSeconds = 0.1,
            applyGyroBiasCorrection = false
        )
        fuse(delayed, first)
        fuse(delayed, second)

        PoseEstimator.addOdometryObservationDirect(
            explicitlySplit, 30L, 0.3, 0.06, 0.18,
            dtSeconds = 0.03,
            applyGyroBiasCorrection = false
        )
        fuse(explicitlySplit, first)
        PoseEstimator.addOdometryObservationDirect(
            explicitlySplit, 70L, 0.4, 0.08, 0.24,
            dtSeconds = 0.04,
            applyGyroBiasCorrection = false
        )
        fuse(explicitlySplit, second)
        PoseEstimator.addOdometryObservationDirect(
            explicitlySplit, 100L, 0.3, 0.06, 0.18,
            dtSeconds = 0.03,
            applyGyroBiasCorrection = false
        )

        assertEquals(explicitlySplit.estimatedPoseX, delayed.estimatedPoseX, 1e-10)
        assertEquals(explicitlySplit.estimatedPoseY, delayed.estimatedPoseY, 1e-10)
        assertEquals(explicitlySplit.estimatedPoseHeading, delayed.estimatedPoseHeading, 1e-10)
        for (i in 0 until 9) {
            assertEquals(explicitlySplit.covarianceArray[i], delayed.covarianceArray[i], 1e-10, "P[$i]")
        }
    }

    private fun measurement(timestampMs: Long, x: Double, y: Double, heading: Double) = VisionMeasurement(
        timestampMs = timestampMs,
        targetPose = Pose3d(Translation3d(x, y, 0.0), Rotation3d(0.0, 0.0, heading)),
        tagId = -1,
        ambiguity = 0.01,
        robotPoseTargetSpace = Pose3d(Translation3d(0.0, 0.0, 1.0), Rotation3d())
    )

    private fun fuse(state: PoseEstimatorState, measurement: VisionMeasurement) {
        PoseEstimator.addVisionMeasurement(
            state, measurement, Vector3(0.08, 0.08, 0.08),
            useMahalanobisRejection = false
        )
    }

    private fun seededState(): PoseEstimatorState = PoseEstimatorState().also { state ->
        state.history.addEntryDirect(
            0L,
            state.estimatedPoseX,
            state.estimatedPoseY,
            state.estimatedPoseHeading,
            state.covariance,
            0.0
        )
    }
}
