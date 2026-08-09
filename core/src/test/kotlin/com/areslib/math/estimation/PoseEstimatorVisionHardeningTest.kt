package com.areslib.math.estimation

import com.areslib.state.VisionMeasurement
import com.areslib.math.coordinate.FieldLayouts
import com.areslib.math.geometry.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * PoseEstimatorVisionHardeningTest declaration.
 *
 * @param args Standard arguments (if applicable).
 * @return Corresponding output value or Unit.
 */
class PoseEstimatorVisionHardeningTest {

    private val testCovariance = Matrix3x3(0.01, 0.0, 0.0, 0.0, 0.01, 0.0, 0.0, 0.0, 0.01)
    private val originalTags = PoseEstimator.activeTags

    @BeforeEach
    /**
     * setUp declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun setUp() {
        PoseEstimator.activeTags = mapOf(
            3 to Pose3d(Translation3d(-1.8, 1.8, 0.5), Rotation3d(0.0, 0.0, 0.0))
        )
    }

    @AfterEach
    /**
     * tearDown declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun tearDown() {
        PoseEstimator.activeTags = originalTags
    }

    @Test
    fun `test angle of incidence covariance scaling`() {
        // Tag 3 is at (-1.8, 1.8, 0.5) with yaw 0.0
        
        // --- CASE 1: Face-on vision measurement (phi = 0) ---
        // Robot at (-2.8, 1.8) -> dx = 1.0, dy = 0.0 -> phi = 0
        var stateFaceOn = PoseEstimatorState().apply {
            covarianceArray[0] = testCovariance.m00; covarianceArray[1] = testCovariance.m01; covarianceArray[2] = testCovariance.m02
            covarianceArray[3] = testCovariance.m10; covarianceArray[4] = testCovariance.m11; covarianceArray[5] = testCovariance.m12
            covarianceArray[6] = testCovariance.m20; covarianceArray[7] = testCovariance.m21; covarianceArray[8] = testCovariance.m22
        }
        stateFaceOn = PoseEstimator.addOdometryObservation(
            stateFaceOn,
            100L,
            Translation2d(-2.8, 1.8),
            Rotation2d(0.0)
        )
        
        // Vision says robot is at (-2.7, 1.8)
        val visionPoseFaceOn = Pose3d(Translation3d(-2.7, 1.8, 0.0), Rotation3d(0.0, 0.0, 0.0))
        val measurementFaceOn = VisionMeasurement(
            timestampMs = 100L,
            targetPose = visionPoseFaceOn,
            tagId = 3,
            ambiguity = 0.0
        )
        
        val visionStdDevs = Vector3(0.1, 0.1, 0.1)
        val finalStateFaceOn = PoseEstimator.addVisionMeasurement(
            stateFaceOn,
            measurementFaceOn,
            visionStdDevs,
            numTags = 1,
            useMahalanobisRejection = false
        )
        
        // --- CASE 2: Skewed vision measurement (phi = 60 deg) ---
        // Robot at (-2.3, 1.8 - sqrt(3)/2) -> dx = 0.5, dy = sqrt(3)/2 -> losHeading = 60 deg -> phi = 60 deg
        // cos(phi) = 0.5 -> incidenceScale = 4.0
        var stateSkewed = PoseEstimatorState().apply {
            covarianceArray[0] = testCovariance.m00; covarianceArray[1] = testCovariance.m01; covarianceArray[2] = testCovariance.m02
            covarianceArray[3] = testCovariance.m10; covarianceArray[4] = testCovariance.m11; covarianceArray[5] = testCovariance.m12
            covarianceArray[6] = testCovariance.m20; covarianceArray[7] = testCovariance.m21; covarianceArray[8] = testCovariance.m22
        }
        stateSkewed = PoseEstimator.addOdometryObservation(
            stateSkewed,
            100L,
            Translation2d(-2.3, 1.8 - kotlin.math.sqrt(3.0) / 2.0),
            Rotation2d(0.0)
        )
        
        // Vision says robot is 0.1m closer along x
        val visionPoseSkewed = Pose3d(Translation3d(-2.2, 1.8 - kotlin.math.sqrt(3.0) / 2.0, 0.0), Rotation3d(0.0, 0.0, 0.0))
        val measurementSkewed = VisionMeasurement(
            timestampMs = 100L,
            targetPose = visionPoseSkewed,
            tagId = 3,
            ambiguity = 0.0
        )
        
        val finalStateSkewed = PoseEstimator.addVisionMeasurement(
            stateSkewed,
            measurementSkewed,
            visionStdDevs,
            numTags = 1,
            useMahalanobisRejection = false
        )
        
        // The face-on correction should pull the state closer to the vision measurement than the skewed correction,
        // because the skewed observation has inflated covariance (scaled by 4.0 in standard deviations, i.e., 16x in variance).
        val pullFaceOn = finalStateFaceOn.estimatedPose.x - (-2.8)
        val pullSkewed = finalStateSkewed.estimatedPose.x - (-2.3)
        
        assertTrue(pullFaceOn > 0.0, "Face-on pull should be positive: $pullFaceOn")
        assertTrue(pullSkewed > 0.0, "Skewed pull should be positive: $pullSkewed")
        assertTrue(pullFaceOn > pullSkewed * 1.2, "Face-on pull ($pullFaceOn) should be significantly larger than skewed pull ($pullSkewed)")
    }

    @Test
    fun `test tracking ambiguity covariance scaling`() {
        // Tag 3 is at (-1.8, 1.8, 0.5) with yaw 0.0
        
        // --- CASE 1: Low ambiguity (ambiguity = 0.0) ---
        var stateLowAmbiguity = PoseEstimatorState().apply {
            covarianceArray[0] = testCovariance.m00; covarianceArray[1] = testCovariance.m01; covarianceArray[2] = testCovariance.m02
            covarianceArray[3] = testCovariance.m10; covarianceArray[4] = testCovariance.m11; covarianceArray[5] = testCovariance.m12
            covarianceArray[6] = testCovariance.m20; covarianceArray[7] = testCovariance.m21; covarianceArray[8] = testCovariance.m22
        }
        stateLowAmbiguity = PoseEstimator.addOdometryObservation(
            stateLowAmbiguity,
            100L,
            Translation2d(-2.8, 1.8),
            Rotation2d(0.0)
        )
        
        val visionPose = Pose3d(Translation3d(-2.7, 1.8, 0.0), Rotation3d(0.0, 0.0, 0.0))
        val measurementLowAmbiguity = VisionMeasurement(
            timestampMs = 100L,
            targetPose = visionPose,
            tagId = 3,
            ambiguity = 0.0
        )
        
        val visionStdDevs = Vector3(0.1, 0.1, 0.1)
        val finalStateLowAmbiguity = PoseEstimator.addVisionMeasurement(
            stateLowAmbiguity,
            measurementLowAmbiguity,
            visionStdDevs,
            numTags = 1,
            useMahalanobisRejection = false
        )
        
        // --- CASE 2: High ambiguity (ambiguity = 0.5) ---
        // ambiguityScale = 1.0 + 10.0 * 0.25 = 3.5
        var stateHighAmbiguity = PoseEstimatorState().apply {
            covarianceArray[0] = testCovariance.m00; covarianceArray[1] = testCovariance.m01; covarianceArray[2] = testCovariance.m02
            covarianceArray[3] = testCovariance.m10; covarianceArray[4] = testCovariance.m11; covarianceArray[5] = testCovariance.m12
            covarianceArray[6] = testCovariance.m20; covarianceArray[7] = testCovariance.m21; covarianceArray[8] = testCovariance.m22
        }
        stateHighAmbiguity = PoseEstimator.addOdometryObservation(
            stateHighAmbiguity,
            100L,
            Translation2d(-2.8, 1.8),
            Rotation2d(0.0)
        )
        
        val measurementHighAmbiguity = VisionMeasurement(
            timestampMs = 100L,
            targetPose = visionPose,
            tagId = 3,
            ambiguity = 0.5
        )
        
        val finalStateHighAmbiguity = PoseEstimator.addVisionMeasurement(
            stateHighAmbiguity,
            measurementHighAmbiguity,
            visionStdDevs,
            numTags = 1,
            useMahalanobisRejection = false,
            maxAmbiguity = 0.6
        )
        
        val pullLow = finalStateLowAmbiguity.estimatedPose.x - (-2.8)
        val pullHigh = finalStateHighAmbiguity.estimatedPose.x - (-2.8)
        
        assertTrue(pullLow > 0.0, "Low ambiguity pull should be positive: $pullLow")
        assertTrue(pullHigh > 0.0, "High ambiguity pull should be positive: $pullHigh")
        assertTrue(pullLow > pullHigh * 1.1, "Low ambiguity pull ($pullLow) should be significantly larger than high ambiguity pull ($pullHigh)")
    }

    @Test
    fun `test combined incidence angle and ambiguity scaling`() {
        // Tag 3 is at (-1.8, 1.8, 0.5) with yaw 0.0
        
        // Robot at (-2.3, 1.8 - sqrt(3)/2), looking at tag 3 -> phi = 60 deg -> incidenceScale = 4.0
        // ambiguity = 0.5 -> ambiguityScale = 3.5
        // finalScale = 4.0 * 3.5 = 14.0
        
        var stateCombined = PoseEstimatorState().apply {
            covarianceArray[0] = testCovariance.m00; covarianceArray[1] = testCovariance.m01; covarianceArray[2] = testCovariance.m02
            covarianceArray[3] = testCovariance.m10; covarianceArray[4] = testCovariance.m11; covarianceArray[5] = testCovariance.m12
            covarianceArray[6] = testCovariance.m20; covarianceArray[7] = testCovariance.m21; covarianceArray[8] = testCovariance.m22
        }
        stateCombined = PoseEstimator.addOdometryObservation(
            stateCombined,
            100L,
            Translation2d(-2.3, 1.8 - kotlin.math.sqrt(3.0) / 2.0),
            Rotation2d(0.0)
        )
        
        val visionPose = Pose3d(Translation3d(-2.2, 1.8 - kotlin.math.sqrt(3.0) / 2.0, 0.0), Rotation3d(0.0, 0.0, 0.0))
        val measurementCombined = VisionMeasurement(
            timestampMs = 100L,
            targetPose = visionPose,
            tagId = 3,
            ambiguity = 0.5
        )
        
        val visionStdDevs = Vector3(0.1, 0.1, 0.1)
        val finalStateCombined = PoseEstimator.addVisionMeasurement(
            stateCombined,
            measurementCombined,
            visionStdDevs,
            numTags = 1,
            useMahalanobisRejection = false,
            maxAmbiguity = 0.6
        )
        
        val pullCombined = finalStateCombined.estimatedPose.x - (-2.3)
        
        // Compared to pure face-on low ambiguity case
        var stateBase = PoseEstimatorState().apply {
            covarianceArray[0] = testCovariance.m00; covarianceArray[1] = testCovariance.m01; covarianceArray[2] = testCovariance.m02
            covarianceArray[3] = testCovariance.m10; covarianceArray[4] = testCovariance.m11; covarianceArray[5] = testCovariance.m12
            covarianceArray[6] = testCovariance.m20; covarianceArray[7] = testCovariance.m21; covarianceArray[8] = testCovariance.m22
        }
        stateBase = PoseEstimator.addOdometryObservation(
            stateBase,
            100L,
            Translation2d(-2.8, 1.8),
            Rotation2d(0.0)
        )
        
        val visionPoseBase = Pose3d(Translation3d(-2.7, 1.8, 0.0), Rotation3d(0.0, 0.0, 0.0))
        val measurementBase = VisionMeasurement(
            timestampMs = 100L,
            targetPose = visionPoseBase,
            tagId = 3,
            ambiguity = 0.0
        )
        
        val finalStateBase = PoseEstimator.addVisionMeasurement(
            stateBase,
            measurementBase,
            visionStdDevs,
            numTags = 1,
            useMahalanobisRejection = false
        )
        
        val pullBase = finalStateBase.estimatedPose.x - (-2.8)
        
        assertTrue(pullCombined > 0.0, "Combined pull should be positive: $pullCombined")
        assertTrue(pullBase > pullCombined * 4.0, "Base pull ($pullBase) should be massive compared to combined highly skewed/ambiguous pull ($pullCombined)")
    }
}
