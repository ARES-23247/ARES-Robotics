package com.areslib.math.estimation

import com.areslib.state.VisionMeasurement
import com.areslib.math.geometry.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PoseEstimatorTest {

    @Test
    fun testOdometryPropagation() {
        var state = PoseEstimatorState()
        
        // Add odometry reading (moved 1 meter forward)
        state = PoseEstimator.addOdometryObservation(
            state,
            100L,
            Translation2d(1.0, 0.0),
            Rotation2d(0.0)
        )
        
        assertEquals(1.0, state.estimatedPose.x, 1e-6)
        assertEquals(0.0, state.estimatedPose.y, 1e-6)
        assertEquals(0.0, state.estimatedPose.heading.radians, 1e-6)
        assertEquals(1, state.history.size)
    }

    @Test
    fun testVisionFusionRetroactive() {
        var state = PoseEstimatorState()
        
        // t=100: Odometry says we are at (1, 0)
        state = PoseEstimator.addOdometryObservation(
            state,
            100L,
            Translation2d(1.0, 0.0),
            Rotation2d(0.0)
        )
        
        // t=150: Odometry says we moved another 1 meter forward to (2, 0)
        state = PoseEstimator.addOdometryObservation(
            state,
            150L,
            Translation2d(1.0, 0.0),
            Rotation2d(0.0)
        )
        
        assertEquals(2.0, state.estimatedPose.x, 1e-6)
        
        // A vision measurement comes in with timestamp t=100.
        // It says we were actually at (1.5, 0) at t=100.
        // The EKF should pull the state at t=100 towards (1.5, 0).
        // Then it should re-apply the delta from t=100 to t=150, which is (+1, 0).
        // So the final estimated pose should be pulled towards (2.5, 0).
        
        val visionPose = Pose3d(Translation3d(1.5, 0.0, 0.0), Rotation3d(0.0, 0.0, 0.0))
        val measurement = VisionMeasurement(
            timestampMs = 100L,
            targetPose = visionPose,
            tagId = 1,
            ambiguity = 0.01
        )
        
        // Low vision std dev means we trust vision heavily
        state = PoseEstimator.addVisionMeasurement(
            state,
            measurement,
            Vector3(0.001, 0.001, 0.001)
        )
        
        // Expect x > 2.0 because vision pulled it forward.
        // With very low vision variance, it should be very close to 2.5
        assertTrue(state.estimatedPose.x > 2.1, "Pose X should be retroactively corrected forward: ${state.estimatedPose.x}")
        assertEquals(0.0, state.estimatedPose.y, 1e-6)
    }

    @Test
    fun testOdometryCovarianceScalingUnderTilt() {
        val flatState = PoseEstimator.addOdometryObservation(
            PoseEstimatorState(),
            100L,
            Translation2d(1.0, 0.0),
            Rotation2d(0.0),
            pitchDegrees = 0.0,
            rollDegrees = 0.0
        )

        val tiltedState = PoseEstimator.addOdometryObservation(
            PoseEstimatorState(),
            100L,
            Translation2d(1.0, 0.0),
            Rotation2d(0.0),
            pitchDegrees = 10.0, // Exceeds 8.0 degree slip threshold
            rollDegrees = 0.0
        )

        // Flat state covariance: base Q added (e.g. m00 = 1.0 + 0.01 = 1.01)
        // Tilted state covariance: continuous scaled Q added (25.75x Q for 10 degrees)
        val flatCovDiff = flatState.covariance.m00 - 1.0
        val tiltedCovDiff = tiltedState.covariance.m00 - 1.0

        assertEquals(0.01, flatCovDiff, 1e-6)
        assertEquals(0.2575, tiltedCovDiff, 1e-6)
    }

    @Test
    fun testOdometryFreezeUnderBeaching() {
        val initialState = PoseEstimatorState()

        // 20 degrees roll exceeds the 15.0 degree beaching threshold
        val beachedState = PoseEstimator.addOdometryObservation(
            initialState,
            100L,
            Translation2d(1.0, 0.5),
            Rotation2d(0.1),
            pitchDegrees = 0.0,
            rollDegrees = 20.0
        )

        // The position and orientation should remain unchanged (frozen)
        assertEquals(initialState.estimatedPose.x, beachedState.estimatedPose.x, 1e-6)
        assertEquals(initialState.estimatedPose.y, beachedState.estimatedPose.y, 1e-6)
        assertEquals(initialState.estimatedPose.heading.radians, beachedState.estimatedPose.heading.radians, 1e-6)

        // The covariance should not grow
        assertEquals(initialState.covariance.m00, beachedState.covariance.m00, 1e-6)
        assertEquals(initialState.covariance.m11, beachedState.covariance.m11, 1e-6)
        assertEquals(initialState.covariance.m22, beachedState.covariance.m22, 1e-6)
    }

    @Test
    fun testVisionDistancePenalization() {
        // Setup initial history
        var stateClose = PoseEstimatorState()
        stateClose = PoseEstimator.addOdometryObservation(
            stateClose,
            100L,
            Translation2d(1.8, 1.0), // Robot is right in front of Tag 1
            Rotation2d(0.0)
        )

        var stateFar = PoseEstimatorState()
        stateFar = PoseEstimator.addOdometryObservation(
            stateFar,
            100L,
            Translation2d(0.0, 0.0), // Robot is far from Tag 1 (1.8, 1.8)
            Rotation2d(0.0)
        )

        // Vision says robot is at (+1.0m, +1.0m) relative to current position
        val measurementClose = VisionMeasurement(
            timestampMs = 100L,
            targetPose = Pose3d(Translation3d(2.8, 2.8, 0.0), Rotation3d(0.0, 0.0, 0.0)),
            tagId = 1
        )
        val measurementFar = VisionMeasurement(
            timestampMs = 100L,
            targetPose = Pose3d(Translation3d(1.0, 1.0, 0.0), Rotation3d(0.0, 0.0, 0.0)),
            tagId = 1
        )

        val updatedClose = PoseEstimator.addVisionMeasurement(
            stateClose,
            measurementClose,
            Vector3(0.1, 0.1, 0.1)
        )

        val updatedFar = PoseEstimator.addVisionMeasurement(
            stateFar,
            measurementFar,
            Vector3(0.1, 0.1, 0.1)
        )

        // The close measurement (distance = 0) should pull the estimator further than the far measurement
        val diffClose = updatedClose.estimatedPose.x - 1.8
        val diffFar = updatedFar.estimatedPose.x - 0.0

        // Because the far measurement has penalized trust (higher R due to distance),
        // the correction towards the far target pose should be significantly smaller.
        assertTrue(diffClose > diffFar, "Close vision measurement should pull more ($diffClose) than far penalized one ($diffFar)")
    }

    @Test
    fun testVisionMultiTagScaling() {
        var stateSingle = PoseEstimatorState()
        stateSingle = PoseEstimator.addOdometryObservation(
            stateSingle,
            100L,
            Translation2d(0.0, 0.0),
            Rotation2d(0.0)
        )

        var stateMulti = PoseEstimatorState()
        stateMulti = PoseEstimator.addOdometryObservation(
            stateMulti,
            100L,
            Translation2d(0.0, 0.0),
            Rotation2d(0.0)
        )

        val measurement = VisionMeasurement(
            timestampMs = 100L,
            targetPose = Pose3d(Translation3d(1.0, 1.0, 0.0), Rotation3d(0.0, 0.0, 0.0)),
            tagId = 1
        )

        // Fuse with 1 tag vs 4 tags
        val updatedSingle = PoseEstimator.addVisionMeasurement(
            stateSingle,
            measurement,
            Vector3(0.1, 0.1, 0.1),
            numTags = 1
        )

        val updatedMulti = PoseEstimator.addVisionMeasurement(
            stateMulti,
            measurement,
            Vector3(0.1, 0.1, 0.1),
            numTags = 4
        )

        val diffSingle = updatedSingle.estimatedPose.x
        val diffMulti = updatedMulti.estimatedPose.x

        // More tags reduces noise covariance R, which means we trust vision MORE, so diffMulti should be larger!
        assertTrue(diffMulti > diffSingle, "Multi-tag fusion should pull estimate closer to vision ($diffMulti) than single-tag ($diffSingle)")
    }

    @Test
    fun testMahalanobisOutlierRejection() {
        var state = PoseEstimatorState()
        state = PoseEstimator.addOdometryObservation(
            state,
            100L,
            Translation2d(0.0, 0.0),
            Rotation2d(0.0)
        )

        // Vision measurement is a major outlier (far away at 10m, 10m)
        val measurement = VisionMeasurement(
            timestampMs = 100L,
            targetPose = Pose3d(Translation3d(10.0, 10.0, 0.0), Rotation3d(0.0, 0.0, 0.0)),
            tagId = 1
        )

        // Fuse with Mahalanobis rejection enabled (default)
        val rejectedState = PoseEstimator.addVisionMeasurement(
            state,
            measurement,
            Vector3(0.1, 0.1, 0.1),
            useMahalanobisRejection = true,
            mahalanobisThreshold = 12.0
        )

        // Estimated pose should remain unchanged (rejected)
        assertEquals(0.0, rejectedState.estimatedPose.x, 1e-6)
        assertEquals(0.0, rejectedState.estimatedPose.y, 1e-6)

        // Fuse with Mahalanobis rejection disabled
        val acceptedState = PoseEstimator.addVisionMeasurement(
            state,
            measurement,
            Vector3(0.1, 0.1, 0.1),
            useMahalanobisRejection = false
        )

        // Estimated pose should be corrected towards the outlier (accepted)
        assertTrue(acceptedState.estimatedPose.x > 1.0, "State should be corrected when rejection is disabled")
    }

    @Test
    fun testHysteresisAndRecovery() {
        var state = PoseEstimatorState()
        
        // 1. Tilt to 14.0 - not beached yet, but high covariance
        state = PoseEstimator.addOdometryObservation(
            state, 100L, Translation2d(0.1, 0.0), Rotation2d(0.0),
            pitchDegrees = 14.0, rollDegrees = 0.0
        )
        assertEquals(false, state.isBeached)
        
        // 2. Tilt to 16.0 - beached!
        state = PoseEstimator.addOdometryObservation(
            state, 200L, Translation2d(0.1, 0.0), Rotation2d(0.0),
            pitchDegrees = 16.0, rollDegrees = 0.0
        )
        assertEquals(true, state.isBeached)
        
        // 3. Tilt drops to 13.0 - STILL beached due to hysteresis (must drop < 12.0)
        state = PoseEstimator.addOdometryObservation(
            state, 300L, Translation2d(0.1, 0.0), Rotation2d(0.0),
            pitchDegrees = 13.0, rollDegrees = 0.0
        )
        assertEquals(true, state.isBeached)
        
        // 4. Tilt drops to 10.0 - UNBEACHED
        state = PoseEstimator.addOdometryObservation(
            state, 400L, Translation2d(0.1, 0.0), Rotation2d(0.0),
            pitchDegrees = 10.0, rollDegrees = 0.0
        )
        assertEquals(false, state.isBeached)
        assertEquals(400L, state.lastUnbeachedTimeMs)
        
        // 5. In recovery period (t=450, dt=50ms since unbeached) -> forced 100x covariance
        val preRecovCov = state.covariance.m00
        state = PoseEstimator.addOdometryObservation(
            state, 450L, Translation2d(0.1, 0.0), Rotation2d(0.0),
            pitchDegrees = 0.0, rollDegrees = 0.0
        )
        val recovDiff = state.covariance.m00 - preRecovCov
        assertEquals(0.1, recovDiff, 1e-6) // 100 * 5.0 * Q (0.01) * dt = 0.1
        
        // 6. Out of recovery period (t=1000) -> normal covariance
        val preNormCov = state.covariance.m00
        state = PoseEstimator.addOdometryObservation(
            state, 1000L, Translation2d(0.1, 0.0), Rotation2d(0.0),
            pitchDegrees = 0.0, rollDegrees = 0.0
        )
        val normDiff = state.covariance.m00 - preNormCov
        assertEquals(0.001, normDiff, 1e-6)
    }

    @Test
    fun testImpactVelocityScaling() {
        val flatState = PoseEstimator.addOdometryObservation(
            PoseEstimatorState(),
            100L,
            Translation2d(1.0, 0.0),
            Rotation2d(0.0),
            pitchDegrees = 0.0,
            rollDegrees = 0.0,
            pitchVelocityDegPerSec = 30.0 // Violent impact
        )

        val flatCovDiff = flatState.covariance.m00 - 1.0
        // Expected scale = 50.0 * 50.0 due to high pitch velocity and movement
        assertEquals(0.5, flatCovDiff, 1e-6)
    }
}
