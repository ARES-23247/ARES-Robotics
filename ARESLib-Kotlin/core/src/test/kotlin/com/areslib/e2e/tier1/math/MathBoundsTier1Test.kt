package com.areslib.e2e.tier1.math

import com.areslib.math.estimation.PoseEstimator
import com.areslib.math.estimation.PoseEstimatorState
import com.areslib.math.geometry.Vector3
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Matrix3x3
import com.areslib.state.VisionMeasurement
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Translation3d
import com.areslib.math.geometry.Rotation3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MathBoundsTier1Test {

    @Test
    fun testEkfOutlierRejection_shouldRejectExceedingMahalanobis() {
        val initialState = PoseEstimatorState()
        // Add one history entry
        val stateWithHistory = PoseEstimator.addOdometryObservation(
            initialState, 100L, Translation2d(0.0, 0.0), Rotation2d(0.0)
        )
        
        // Simulate a wildly incorrect vision measurement
        val badMeasurement = VisionMeasurement(
            timestampMs = 100L,
            targetPose = Pose3d(Translation3d(100.0, 100.0, 0.0), Rotation3d(0.0, 0.0, 0.0)),
            tagId = 1
        )
        
        val resultState = PoseEstimator.addVisionMeasurement(
            stateWithHistory,
            badMeasurement,
            Vector3(0.1, 0.1, 0.1), // small standard deviation = high confidence expectation
            useMahalanobisRejection = true,
            mahalanobisThreshold = 12.0
        )
        
        // Pose should remain unchanged because it was rejected
        assertEquals(0.0, resultState.estimatedPose.x, 1e-6)
        assertEquals(0.0, resultState.estimatedPose.y, 1e-6)
    }

    @Test
    fun testEkfBeachingLimit_shouldFreezeOdometry() {
        val initialState = PoseEstimatorState()
        
        // High pitch (>15) triggers beaching
        val beachedState = PoseEstimator.addOdometryObservation(
            initialState, 100L, Translation2d(1.0, 0.0), Rotation2d(0.0),
            pitchDegrees = 16.0
        )
        
        assertTrue(beachedState.isBeached)
        // Pose should not be updated due to beaching
        assertEquals(0.0, beachedState.estimatedPose.x, 1e-6)
    }

    @Test
    fun testEkfRecovery_shouldUnfreezeOdometry() {
        val initialState = PoseEstimatorState()
        
        // High pitch (>15) triggers beaching
        val beachedState = PoseEstimator.addOdometryObservation(
            initialState, 100L, Translation2d(1.0, 0.0), Rotation2d(0.0),
            pitchDegrees = 16.0
        )
        
        // Lower pitch (<12) triggers recovery
        val recoveredState = PoseEstimator.addOdometryObservation(
            beachedState, 200L, Translation2d(1.0, 0.0), Rotation2d(0.0),
            pitchDegrees = 10.0
        )
        
        assertFalse(recoveredState.isBeached)
        // Odometry should apply again
        assertEquals(1.0, recoveredState.estimatedPose.x, 1e-6)
    }
}
