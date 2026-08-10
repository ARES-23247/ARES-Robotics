package com.areslib.math.estimation

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PoseEstimatorNoiseValidationTest {
    @Test
    fun `nonfinite or negative process noise restores conservative default`() {
        val oldX = PoseEstimator.qX
        val oldY = PoseEstimator.qY
        val oldTheta = PoseEstimator.qTheta
        try {
            PoseEstimator.qX = Double.NaN
            PoseEstimator.qY = Double.NEGATIVE_INFINITY
            PoseEstimator.qTheta = -1.0

            assertEquals(0.01, PoseEstimator.qX, 1e-12)
            assertEquals(0.01, PoseEstimator.qY, 1e-12)
            assertEquals(0.01, PoseEstimator.qTheta, 1e-12)
        } finally {
            PoseEstimator.qX = oldX
            PoseEstimator.qY = oldY
            PoseEstimator.qTheta = oldTheta
        }
    }
}
