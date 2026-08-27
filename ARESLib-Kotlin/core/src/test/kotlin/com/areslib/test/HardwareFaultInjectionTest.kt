package com.areslib.test

import com.areslib.math.estimation.PoseEstimator
import com.areslib.math.estimation.PoseEstimatorState
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation2d
import com.areslib.math.geometry.Translation3d
import com.areslib.math.geometry.Vector3
import com.areslib.state.VisionMeasurement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HardwareFaultInjectionTest {

    @Test
    fun testMahalanobisOutlierRejectionAndNanSafety() {
        var state = PoseEstimatorState(
            estimatedPoseX = 1.0,
            estimatedPoseY = 1.0,
            estimatedPoseHeading = 0.0
        )

        state = PoseEstimator.addOdometryObservation(
            state = state,
            timestampMs = 1000L,
            deltaTranslation = Translation2d(0.0, 0.0),
            deltaHeading = Rotation2d(0.0),
            dtSeconds = 0.02
        )

        // 1. Inject high ambiguity vision measurement (ambiguity = 0.8 > 0.2 threshold)
        val highAmbiguityMeasurement = VisionMeasurement(
            timestampMs = 1000L,
            targetPose = Pose3d(Translation3d(1.05, 1.05, 0.0), Rotation3d(0.0, 0.0, 0.0)),
            robotPoseTargetSpace = Pose3d(Translation3d(1.05, 1.05, 0.0), Rotation3d(0.0, 0.0, 0.0)),
            tagId = 1,
            ambiguity = 0.80
        )

        val stateAfterAmbiguity = PoseEstimator.addVisionMeasurement(
            state = state,
            measurement = highAmbiguityMeasurement,
            visionStdDevs = Vector3(0.1, 0.1, 0.1),
            maxAmbiguity = 0.2
        )

        assertFalse(stateAfterAmbiguity.lastMeasurementAccepted, "High ambiguity measurement should be rejected")
        assertEquals("high_ambiguity", stateAfterAmbiguity.lastRejectionReason)
        assertFalse(stateAfterAmbiguity.estimatedPose.x.isNaN())

        // 2. Inject NaN vision measurement
        val nanMeasurement = VisionMeasurement(
            timestampMs = 1000L,
            targetPose = Pose3d(Translation3d(Double.NaN, 1.0, 0.0), Rotation3d(0.0, 0.0, 0.0)),
            robotPoseTargetSpace = Pose3d(Translation3d(Double.NaN, 1.0, 0.0), Rotation3d(0.0, 0.0, 0.0)),
            tagId = 1,
            ambiguity = 0.05
        )

        val stateAfterNan = PoseEstimator.addVisionMeasurement(
            state = stateAfterAmbiguity,
            measurement = nanMeasurement,
            visionStdDevs = Vector3(0.1, 0.1, 0.1)
        )

        assertFalse(stateAfterNan.lastMeasurementAccepted, "NaN measurement should be rejected")
        assertEquals("nan_measurement", stateAfterNan.lastRejectionReason)
        assertFalse(stateAfterNan.estimatedPose.x.isNaN())

        // 3. Inject out-of-bounds / zero tag count measurement
        val noTagsMeasurement = VisionMeasurement(
            timestampMs = 1000L,
            targetPose = Pose3d(Translation3d(1.0, 1.0, 0.0), Rotation3d(0.0, 0.0, 0.0)),
            robotPoseTargetSpace = Pose3d(Translation3d(1.0, 1.0, 0.0), Rotation3d(0.0, 0.0, 0.0)),
            tagId = 1,
            ambiguity = 0.05
        )

        val stateAfterNoTags = PoseEstimator.addVisionMeasurement(
            state = stateAfterNan,
            measurement = noTagsMeasurement,
            visionStdDevs = Vector3(0.1, 0.1, 0.1),
            numTags = 0
        )

        assertFalse(stateAfterNoTags.lastMeasurementAccepted, "Zero tags measurement should be rejected")
        assertEquals("no_tags", stateAfterNoTags.lastRejectionReason)
        println("[FaultInjection Test] All safety fault rejection guards PASSED cleanly.")
    }
}
