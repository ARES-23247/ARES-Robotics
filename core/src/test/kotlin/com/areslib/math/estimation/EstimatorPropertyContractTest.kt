package com.areslib.math.estimation

import com.areslib.math.geometry.Matrix3x3
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EstimatorPropertyContractTest {

    @Test
    fun `random robot-local twists match an independent SE2 composition`() {
        val random = Random(23247)
        val state = PoseEstimatorState(
            estimatedPoseX = 0.3,
            estimatedPoseY = -0.2,
            estimatedPoseHeading = 3.0
        )
        var expectedX = state.estimatedPoseX
        var expectedY = state.estimatedPoseY
        var expectedHeading = state.estimatedPoseHeading

        repeat(500) { index ->
            val dx = random.nextDouble(-0.25, 0.25)
            val dy = random.nextDouble(-0.25, 0.25)
            val dtheta = if (index % 10 == 0) {
                random.nextDouble(-1e-9, 1e-9)
            } else {
                random.nextDouble(-1.2, 1.2)
            }

            val scaleSin: Double
            val scaleCos: Double
            if (abs(dtheta) < 1e-6) {
                scaleSin = 1.0 - dtheta * dtheta / 6.0
                scaleCos = dtheta * 0.5
            } else {
                scaleSin = sin(dtheta) / dtheta
                scaleCos = (1.0 - cos(dtheta)) / dtheta
            }
            val localArcX = scaleSin * dx - scaleCos * dy
            val localArcY = scaleCos * dx + scaleSin * dy
            expectedX += localArcX * cos(expectedHeading) - localArcY * sin(expectedHeading)
            expectedY += localArcX * sin(expectedHeading) + localArcY * cos(expectedHeading)
            expectedHeading = normalize(expectedHeading + dtheta)

            propagate(
                state = state,
                timestampMs = (index + 1) * 20L,
                deltaX = dx,
                deltaY = dy,
                deltaHeading = dtheta,
                dtSeconds = 0.02
            )

            assertEquals(expectedX, state.estimatedPoseX, 1e-10, "x at sample $index")
            assertEquals(expectedY, state.estimatedPoseY, 1e-10, "y at sample $index")
            assertEquals(expectedHeading, state.estimatedPoseHeading, 1e-10, "heading at sample $index")
            assertCovarianceIsFiniteAndSymmetric(state, index)
        }

        assertEquals(150, state.history.size, "history must retain its fixed-capacity newest window")
        assertEquals(7_020L, state.history[0].timestampMs)
        assertEquals(10_000L, state.history[state.history.size - 1].timestampMs)
    }

    @Test
    fun `every non-finite component and non-positive dt leaves state untouched`() {
        val invalidSamples = listOf(
            Sample(deltaX = Double.NaN),
            Sample(deltaX = Double.POSITIVE_INFINITY),
            Sample(deltaY = Double.NEGATIVE_INFINITY),
            Sample(deltaHeading = Double.NaN),
            Sample(pitch = Double.POSITIVE_INFINITY),
            Sample(roll = Double.NaN),
            Sample(pitchRate = Double.NEGATIVE_INFINITY),
            Sample(rollRate = Double.NaN),
            Sample(gyroRate = Double.POSITIVE_INFINITY),
            Sample(dt = Double.NaN),
            Sample(dt = Double.POSITIVE_INFINITY),
            Sample(dt = 0.0),
            Sample(dt = -0.02)
        )

        for ((index, sample) in invalidSamples.withIndex()) {
            val state = PoseEstimatorState(
                estimatedPoseX = 1.25,
                estimatedPoseY = -0.75,
                estimatedPoseHeading = 2.5,
                gyroBiasRadPerSec = 0.03
            )
            val covarianceBefore = state.covarianceArray.copyOf()

            OdometryFusionController.processOdometryDirect(
                state = state,
                timestampMs = 500L,
                deltaX = sample.deltaX,
                deltaY = sample.deltaY,
                deltaHeadingRad = sample.deltaHeading,
                pitchDegrees = sample.pitch,
                rollDegrees = sample.roll,
                pitchVelocityDegPerSec = sample.pitchRate,
                rollVelocityDegPerSec = sample.rollRate,
                gyroRateRadPerSec = sample.gyroRate,
                dtSeconds = sample.dt,
                baseQ = Matrix3x3(0.01, 0.0, 0.0, 0.0, 0.01, 0.0, 0.0, 0.0, 0.01),
                scratchQ = Matrix3x3(),
                scratchCov = Matrix3x3()
            )

            assertEquals(1.25, state.estimatedPoseX, 0.0, "x for invalid sample $index")
            assertEquals(-0.75, state.estimatedPoseY, 0.0, "y for invalid sample $index")
            assertEquals(2.5, state.estimatedPoseHeading, 0.0, "heading for invalid sample $index")
            assertEquals(0.03, state.gyroBiasRadPerSec, 0.0, "bias for invalid sample $index")
            assertEquals(0, state.history.size, "history for invalid sample $index")
            assertContentEquals(covarianceBefore, state.covarianceArray, "covariance for invalid sample $index")
        }
    }

    private fun propagate(
        state: PoseEstimatorState,
        timestampMs: Long,
        deltaX: Double,
        deltaY: Double,
        deltaHeading: Double,
        dtSeconds: Double
    ) {
        OdometryFusionController.processOdometryDirect(
            state = state,
            timestampMs = timestampMs,
            deltaX = deltaX,
            deltaY = deltaY,
            deltaHeadingRad = deltaHeading,
            pitchDegrees = 0.0,
            rollDegrees = 0.0,
            pitchVelocityDegPerSec = 0.0,
            rollVelocityDegPerSec = 0.0,
            gyroRateRadPerSec = 0.0,
            dtSeconds = dtSeconds,
            baseQ = Matrix3x3(0.01, 0.0, 0.0, 0.0, 0.01, 0.0, 0.0, 0.0, 0.01),
            scratchQ = Matrix3x3(),
            scratchCov = Matrix3x3()
        )
    }

    private fun assertCovarianceIsFiniteAndSymmetric(state: PoseEstimatorState, index: Int) {
        val p = state.covarianceArray
        assertTrue(p.all { it.isFinite() }, "non-finite covariance at sample $index")
        assertTrue(p[0] >= 0.0 && p[4] >= 0.0 && p[8] >= 0.0, "negative variance at sample $index")
        assertEquals(p[1], p[3], 1e-10, "Pxy symmetry at sample $index")
        assertEquals(p[2], p[6], 1e-10, "PxTheta symmetry at sample $index")
        assertEquals(p[5], p[7], 1e-10, "PyTheta symmetry at sample $index")
    }

    private fun normalize(angle: Double): Double = atan2(sin(angle), cos(angle))

    private data class Sample(
        val deltaX: Double = 0.1,
        val deltaY: Double = -0.05,
        val deltaHeading: Double = 0.02,
        val pitch: Double = 0.0,
        val roll: Double = 0.0,
        val pitchRate: Double = 0.0,
        val rollRate: Double = 0.0,
        val gyroRate: Double = 0.0,
        val dt: Double = 0.02
    )
}
