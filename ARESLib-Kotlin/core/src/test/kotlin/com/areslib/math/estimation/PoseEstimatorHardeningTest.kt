package com.areslib.math.estimation

import org.junit.jupiter.api.Test
import com.areslib.math.geometry.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PoseEstimatorHardeningTest {

    @Test
    fun `test online gyro bias calibration during stationary intervals`() {
        var state = PoseEstimatorState()
        
        val stationaryTranslation = Translation2d(0.0, 0.0)
        val stationaryHeading = Rotation2d(0.0)
        val dt = 0.02
        
        // Let's simulate a constant gyro drift rate of 0.05 rad/sec when the robot is stationary
        val rawGyroDrift = 0.05
        
        // Feed observations over time to let EMA filter estimate the bias
        for (i in 1..30000) {
            state = PoseEstimator.addOdometryObservation(
                state = state,
                timestampMs = i * 20L,
                deltaTranslation = stationaryTranslation,
                deltaHeading = stationaryHeading,
                gyroRateRadPerSec = rawGyroDrift,
                dtSeconds = dt
            )
        }
        
        // The estimated bias should converge close to the raw drift value of 0.05
        assertTrue(state.gyroBiasRadPerSec > 0.04, "Estimated gyro bias did not converge: ${state.gyroBiasRadPerSec}")
        
        // Now, during active navigation, the estimated bias should be subtracted from heading updates
        // If we move forward with a raw delta heading of 0.1 rad over 1 second, but we have 0.05 rad/sec drift bias.
        // Expected corrected delta heading: 0.1 - (0.05 * 1.0) = 0.05 rad.
        val activeTranslation = Translation2d(1.0, 0.0)
        val activeHeading = Rotation2d(0.1)
        
        val updatedState = PoseEstimator.addOdometryObservation(
            state = state,
            timestampMs = 10020L,
            deltaTranslation = activeTranslation,
            deltaHeading = activeHeading,
            gyroRateRadPerSec = rawGyroDrift,
            dtSeconds = 1.0
        )
        
        // Start heading was 0.0. The new estimated heading should be close to 0.05 due to bias correction
        assertEquals(0.05, updatedState.estimatedPose.heading.radians, 1e-3, "Heading bias correction was not applied correctly")
    }
}
