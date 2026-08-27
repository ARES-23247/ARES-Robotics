package com.ares.analytics.ui.components.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class EkfSensorFusionLabTest {

    @Test
    fun testEkfFusionAndOutlierRejection() {
        val simStates = EkfMath.simulateEkfFusion(
            processNoiseQ = 0.05,
            measurementNoiseR = 0.02,
            mahalanobisGate = 9.0,
            visionIntervalSteps = 10,
            injectVisionOutlier = true,
            steps = 100,
            dt = 0.02
        )

        assertEquals(101, simStates.size)
        val finalState = simStates.last()

        // Verify vision measurements were accepted
        assertTrue(finalState.acceptedVisionCount > 0, "EKF should accept valid vision observations")
        // Verify outlier was rejected
        assertTrue(finalState.rejectedVisionCount >= 1, "EKF Mahalanobis filter should reject injected outlier")

        // EKF estimate should be closer to ground truth than raw uncorrected odometry
        val odomError = kotlin.math.hypot(finalState.odomX - finalState.trueX, finalState.odomY - finalState.trueY)
        val ekfError = kotlin.math.hypot(finalState.ekfX - finalState.trueX, finalState.ekfY - finalState.trueY)

        assertTrue(ekfError < odomError, "EKF fusion should achieve lower error than raw dead-reckoned odometry (ekf=$ekfError vs odom=$odomError)")
        assertTrue(finalState.sigmaX > 0.0 && finalState.sigmaX < 0.20, "Uncertainty sigma should remain bounded")
    }

    @Test
    fun rejectsInvalidOrUnboundedTeachingInputs() {
        assertFailsWith<IllegalArgumentException> { EkfMath.simulateEkfFusion(processNoiseQ = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { EkfMath.simulateEkfFusion(measurementNoiseR = 0.0) }
        assertFailsWith<IllegalArgumentException> { EkfMath.simulateEkfFusion(steps = 5_001) }
        assertFailsWith<IllegalArgumentException> { EkfMath.simulateEkfFusion(dt = 0.5) }
    }
}
