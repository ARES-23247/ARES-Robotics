package com.areslib.math.estimation

import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation3d
import com.areslib.math.geometry.Translation2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Vector3
import com.areslib.state.VisionMeasurement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VisionMahalanobisFilterTest {

    @Test
    fun `valid vision measurement updates pose and is accepted`() {
        var state = PoseEstimatorState(
            estimatedPoseX = 0.0,
            estimatedPoseY = 0.0,
            estimatedPoseHeading = 0.0
        )

        // Seed with initial odometry observations
        for (i in 0..10) {
            state = PoseEstimator.addOdometryObservation(
                state = state,
                timestampMs = 1000L + i * 20L,
                deltaTranslation = Translation2d(0.02, 0.01),
                deltaHeading = Rotation2d(0.005),
                dtSeconds = 0.02
            )
        }

        val measurement = VisionMeasurement(
            timestampMs = 1100L,
            targetPose = Pose3d(Translation3d(0.22, 0.11, 0.0), Rotation3d(0.0, 0.0, 0.05)),
            robotPoseTargetSpace = Pose3d(Translation3d(0.0, 0.0, 1.5), Rotation3d(0.0, 0.0, 0.0)),
            ambiguity = 0.05,
            tagId = 1
        )

        state = PoseEstimator.addVisionMeasurement(
            state = state,
            measurement = measurement,
            visionStdDevs = Vector3(0.05, 0.05, 0.05),
            numTags = 2,
            useMahalanobisRejection = true,
            mahalanobisThreshold = 12.0,
            maxAmbiguity = 0.2
        )

        assertTrue(state.lastMeasurementAccepted)
        assertNull(state.lastRejectionReason)
    }

    @Test
    fun `high ambiguity vision observation is rejected`() {
        var state = PoseEstimatorState(
            estimatedPoseX = 0.0,
            estimatedPoseY = 0.0,
            estimatedPoseHeading = 0.0
        )

        for (i in 0..5) {
            state = PoseEstimator.addOdometryObservation(
                state = state,
                timestampMs = 1000L + i * 20L,
                deltaTranslation = Translation2d(0.01, 0.0),
                deltaHeading = Rotation2d(0.0),
                dtSeconds = 0.02
            )
        }

        val ambiguousMeasurement = VisionMeasurement(
            timestampMs = 1050L,
            targetPose = Pose3d(Translation3d(0.05, 0.0, 0.0), Rotation3d(0.0, 0.0, 0.0)),
            robotPoseTargetSpace = Pose3d(Translation3d(0.0, 0.0, 1.0), Rotation3d(0.0, 0.0, 0.0)),
            ambiguity = 0.65, // > maxAmbiguity (0.2)
            tagId = 1
        )

        state = PoseEstimator.addVisionMeasurement(
            state = state,
            measurement = ambiguousMeasurement,
            visionStdDevs = Vector3(0.05, 0.05, 0.05),
            maxAmbiguity = 0.2
        )

        assertFalse(state.lastMeasurementAccepted)
        assertEquals("high_ambiguity", state.lastRejectionReason)
    }

    @Test
    fun `distant outlier is rejected by Mahalanobis distance`() {
        var state = PoseEstimatorState(
            estimatedPoseX = 0.0,
            estimatedPoseY = 0.0,
            estimatedPoseHeading = 0.0
        )

        for (i in 0..10) {
            state = PoseEstimator.addOdometryObservation(
                state = state,
                timestampMs = 1000L + i * 20L,
                deltaTranslation = Translation2d(0.01, 0.0),
                deltaHeading = Rotation2d(0.0),
                dtSeconds = 0.02
            )
        }

        // Outlier 8.0 meters away from actual pose
        val outlierMeasurement = VisionMeasurement(
            timestampMs = 1100L,
            targetPose = Pose3d(Translation3d(8.0, 8.0, 0.0), Rotation3d(0.0, 0.0, 0.0)),
            robotPoseTargetSpace = Pose3d(Translation3d(0.0, 0.0, 1.0), Rotation3d(0.0, 0.0, 0.0)),
            ambiguity = 0.05,
            tagId = 1
        )

        state = PoseEstimator.addVisionMeasurement(
            state = state,
            measurement = outlierMeasurement,
            visionStdDevs = Vector3(0.02, 0.02, 0.02),
            useMahalanobisRejection = true,
            mahalanobisThreshold = 9.21
        )

        assertFalse(state.lastMeasurementAccepted)
        assertEquals("mahalanobis_rejected", state.lastRejectionReason)
    }

    @Test
    fun `NaN measurement coordinates are rejected safely`() {
        var state = PoseEstimatorState(
            estimatedPoseX = 0.0,
            estimatedPoseY = 0.0,
            estimatedPoseHeading = 0.0
        )

        state = PoseEstimator.addOdometryObservation(
            state = state,
            timestampMs = 1000L,
            deltaTranslation = Translation2d(0.01, 0.0),
            deltaHeading = Rotation2d(0.0),
            dtSeconds = 0.02
        )

        val nanMeasurement = VisionMeasurement(
            timestampMs = 1000L,
            targetPose = Pose3d(Translation3d(Double.NaN, 0.0, 0.0), Rotation3d(0.0, 0.0, 0.0)),
            robotPoseTargetSpace = Pose3d(Translation3d(0.0, 0.0, 1.0), Rotation3d(0.0, 0.0, 0.0)),
            ambiguity = 0.05,
            tagId = 1
        )

        state = PoseEstimator.addVisionMeasurement(
            state = state,
            measurement = nanMeasurement,
            visionStdDevs = Vector3(0.05, 0.05, 0.05)
        )

        assertFalse(state.lastMeasurementAccepted)
        assertEquals("nan_measurement", state.lastRejectionReason)
    }
}
