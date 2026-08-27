package com.areslib.math.estimation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GyroSourceSeparationTest {
    @Test
    fun `fused Pinpoint heading is not bias corrected a second time`() {
        val pinpoint = PoseEstimatorState(gyroBiasRadPerSec = 0.2)
        PoseEstimator.addOdometryObservationDirect(
            pinpoint,
            timestampMs = 100L,
            deltaX = 0.1,
            deltaY = 0.0,
            deltaHeadingRad = 0.1,
            gyroRateRadPerSec = 1.2,
            dtSeconds = 0.1,
            applyGyroBiasCorrection = false
        )
        assertEquals(0.1, pinpoint.estimatedPoseHeading, 1e-12)

        val fallback = PoseEstimatorState(gyroBiasRadPerSec = 0.2)
        PoseEstimator.addOdometryObservationDirect(
            fallback,
            timestampMs = 100L,
            deltaX = 0.1,
            deltaY = 0.0,
            deltaHeadingRad = 0.1,
            gyroRateRadPerSec = 1.2,
            dtSeconds = 0.1,
            applyGyroBiasCorrection = true
        )
        assertEquals(0.08, fallback.estimatedPoseHeading, 1e-12)
    }

    @Test
    fun `gyro bias learning requires stationary dwell`() {
        val state = PoseEstimatorState()
        repeat(25) { index ->
            PoseEstimator.addOdometryObservationDirect(
                state,
                timestampMs = (index + 1) * 20L,
                deltaX = 0.0,
                deltaY = 0.0,
                deltaHeadingRad = 0.0,
                gyroRateRadPerSec = 0.05,
                dtSeconds = 0.02,
                applyGyroBiasCorrection = true
            )
        }
        assertEquals(0.0, state.gyroBiasRadPerSec, 0.0)

        repeat(25) { index ->
            PoseEstimator.addOdometryObservationDirect(
                state,
                timestampMs = 520L + index * 20L,
                deltaX = 0.0,
                deltaY = 0.0,
                deltaHeadingRad = 0.0,
                gyroRateRadPerSec = 0.05,
                dtSeconds = 0.02,
                applyGyroBiasCorrection = true
            )
        }
        assertTrue(state.gyroBiasRadPerSec > 0.0)
    }
}
