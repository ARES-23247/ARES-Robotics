package com.areslib.math.estimation

import com.areslib.math.geometry.Matrix3x3
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals

class EstimatorMathRegressionTest {

    @Test
    fun `robot-frame forward motion follows the current heading`() {
        val state = PoseEstimatorState(estimatedPoseHeading = PI / 2.0)

        processOdometry(
            state = state,
            timestampMs = 100L,
            deltaX = 1.0,
            deltaY = 0.0,
            deltaHeading = 0.0,
            dtSeconds = 1.0,
            baseQ = Matrix3x3()
        )

        assertEquals(0.0, state.estimatedPoseX, 1e-12)
        assertEquals(1.0, state.estimatedPoseY, 1e-12)
        assertEquals(PI / 2.0, state.estimatedPoseHeading, 1e-12)
    }

    @Test
    fun `covariance Jacobian linearizes the exact integrated arc`() {
        val state = PoseEstimatorState()
        val quarterTurnArcComponent = 2.0 / PI

        processOdometry(
            state = state,
            timestampMs = 100L,
            deltaX = 1.0,
            deltaY = 0.0,
            deltaHeading = PI / 2.0,
            dtSeconds = 1.0,
            baseQ = Matrix3x3()
        )

        assertEquals(quarterTurnArcComponent, state.estimatedPoseX, 1e-12)
        assertEquals(quarterTurnArcComponent, state.estimatedPoseY, 1e-12)

        // For P = I and field displacement (a, a), F02 = -a and F12 = a.
        // These exact derivatives differ from the midpoint approximation at finite turns.
        assertEquals(1.0 + quarterTurnArcComponent * quarterTurnArcComponent, state.covarianceArray[0], 1e-12)
        assertEquals(1.0 + quarterTurnArcComponent * quarterTurnArcComponent, state.covarianceArray[4], 1e-12)
        assertEquals(-quarterTurnArcComponent, state.covarianceArray[2], 1e-12)
        assertEquals(quarterTurnArcComponent, state.covarianceArray[5], 1e-12)
    }

    @Test
    fun `historical heading correction rotates later robot-frame motion`() {
        val state = PoseEstimatorState()
        val zeroQ = Matrix3x3()

        processOdometry(state, 100L, 0.0, 0.0, 0.0, 0.02, zeroQ)
        processOdometry(state, 200L, 1.0, 0.0, 0.0, 0.02, zeroQ)

        val scratchHistory = HistoryBuffer(150)
        state.history.copyInto(scratchHistory)
        val correctedCovariance = copyMatrix(state.history[0].covariance)

        EKFStatePropagator.repropagateHistory(
            state = state,
            closestIndex = 0,
            baseEntry = state.history[0],
            dxX = 0.0,
            dxY = 0.0,
            dxZ = PI / 2.0,
            updatedCovariance = correctedCovariance,
            baseQ = zeroQ,
            scratchHistory = scratchHistory,
            scratchCov2 = Matrix3x3()
        )

        assertEquals(0.0, state.estimatedPoseX, 1e-12)
        assertEquals(1.0, state.estimatedPoseY, 1e-12)
        assertEquals(PI / 2.0, state.estimatedPoseHeading, 1e-12)
    }

    @Test
    fun `repropagation reuses the complete forward process-noise scale`() {
        val baseQ = Matrix3x3(
            0.01, 0.0, 0.0,
            0.0, 0.01, 0.0,
            0.0, 0.0, 0.01
        )
        val state = PoseEstimatorState()

        processOdometry(state, 100L, 0.0, 0.0, 0.0, 0.1, baseQ)
        processOdometry(
            state = state,
            timestampMs = 300L,
            deltaX = 0.4,
            deltaY = 0.0,
            deltaHeading = 0.2,
            dtSeconds = 0.2,
            baseQ = baseQ,
            pitchDegrees = 10.0,
            gyroRateRadPerSec = 0.1
        )

        // tilt 25.75 * slip 10 * speed 2 m/s * dt 0.2 s
        assertEquals(103.0, state.history[1].qScale, 1e-12)
        val forwardCovariance = state.covarianceArray.copyOf()

        val scratchHistory = HistoryBuffer(150)
        state.history.copyInto(scratchHistory)
        val baseEntry = state.history[0]
        val baseCovariance = copyMatrix(baseEntry.covariance)

        EKFStatePropagator.repropagateHistory(
            state = state,
            closestIndex = 0,
            baseEntry = baseEntry,
            dxX = 0.0,
            dxY = 0.0,
            dxZ = 0.0,
            updatedCovariance = baseCovariance,
            baseQ = baseQ,
            scratchHistory = scratchHistory,
            scratchCov2 = Matrix3x3()
        )

        for (index in forwardCovariance.indices) {
            assertEquals(forwardCovariance[index], state.covarianceArray[index], 1e-12, "covariance[$index]")
        }
    }

    private fun processOdometry(
        state: PoseEstimatorState,
        timestampMs: Long,
        deltaX: Double,
        deltaY: Double,
        deltaHeading: Double,
        dtSeconds: Double,
        baseQ: Matrix3x3,
        pitchDegrees: Double = 0.0,
        gyroRateRadPerSec: Double = 0.0
    ) {
        OdometryFusionController.processOdometryDirect(
            state = state,
            timestampMs = timestampMs,
            deltaX = deltaX,
            deltaY = deltaY,
            deltaHeadingRad = deltaHeading,
            pitchDegrees = pitchDegrees,
            rollDegrees = 0.0,
            pitchVelocityDegPerSec = 0.0,
            rollVelocityDegPerSec = 0.0,
            gyroRateRadPerSec = gyroRateRadPerSec,
            dtSeconds = dtSeconds,
            baseQ = baseQ,
            scratchQ = Matrix3x3(),
            scratchCov = Matrix3x3()
        )
    }

    private fun copyMatrix(source: Matrix3x3) = Matrix3x3(
        source.m00, source.m01, source.m02,
        source.m10, source.m11, source.m12,
        source.m20, source.m21, source.m22
    )
}
