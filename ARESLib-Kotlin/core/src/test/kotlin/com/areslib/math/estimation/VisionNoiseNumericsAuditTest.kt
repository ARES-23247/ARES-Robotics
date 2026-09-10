package com.areslib.math.estimation

import com.areslib.math.geometry.Matrix3x3
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation3d
import com.areslib.state.VisionMeasurement
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisionNoiseNumericsAuditTest {
    @Test
    fun `independently scaled correlated axes match a dimensionless solve`() {
        val p = arrayOf(doubleArrayOf(1.0, 0.2, -0.1),
            doubleArrayOf(0.2, 0.8, 0.15), doubleArrayOf(-0.1, 0.15, 0.6))
        val s = Array(3) { i -> DoubleArray(3) { j -> p[i][j] + if (i == j) 0.01 else 0.0 } }
        val inverse = inverseByElimination(s)
        val gain = Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { k -> p[i][k] * inverse[k][j] } } }
        val residual = doubleArrayOf(0.03, -0.02, 0.0)
        val expectedNis = (0..2).sumOf { i -> (0..2).sumOf { j -> residual[i] * inverse[i][j] * residual[j] } }
        for (axes in listOf(doubleArrayOf(1e-120, 1.0, 1e120),
            doubleArrayOf(1e120, 1e-120, 1.0), doubleArrayOf(1.0, 1e120, 1e-120))) {
            val scaled = Array(3) { i -> DoubleArray(3) { j -> p[i][j] * axes[i] * axes[j] } }
            val f = Fixture(Matrix3x3(scaled[0][0], scaled[0][1], scaled[0][2],
                scaled[1][0], scaled[1][1], scaled[1][2], scaled[2][0], scaled[2][1], scaled[2][2]))
            VisionMahalanobisFilter.processVisionMeasurement(f.state,
                packet().copy(targetPose = Pose3d(Translation3d(residual[0] * axes[0], residual[1] * axes[1], 0.0))),
                axes[0] * 0.1, axes[1] * 0.1, axes[2] * 0.1,
                1, false, 12.0, 0.2, emptyMap(), Matrix3x3(), f.r, Matrix3x3(), Matrix3x3(), f.k,
                Matrix3x3(), HistoryBuffer(), Matrix3x3(), PoseHistoryEntry(), false, false, false)
            assertTrue(f.state.lastMeasurementAccepted, f.state.lastRejectionReason)
            assertEquals(expectedNis, f.state.lastNormalizedInnovationSquared, 1e-12)
            for (i in 0..2) for (j in 0..2) {
                assertEquals(gain[i][j], f.state.lastKalmanGain[i * 3 + j] / axes[i] * axes[j], 1e-11)
                assertEquals(gain[i][j] * 0.01,
                    f.state.covarianceArray[i * 3 + j] / axes[i] / axes[j], 1e-11)
            }
        }
    }

    @Test
    fun `in place replay preserves original adjacent poses for legacy motion reconstruction`() {
        val state = PoseEstimatorState()
        val history = HistoryBuffer()
        history.addEntryDirect(100L, 0.0, 0.0, 0.0, Matrix3x3.IDENTITY, 0.0)
        history.addEntryDirect(200L, 1.0, 0.0, 0.0, Matrix3x3.IDENTITY, 0.0)
        history.addEntryDirect(300L, 1.0, 2.0, 0.0, Matrix3x3.IDENTITY, 0.0)
        assertTrue(EKFStatePropagator.tryRepropagateHistory(state, history, 0, history[0],
            0.1, 0.0, Math.PI / 2.0, Matrix3x3.IDENTITY, Matrix3x3(), history, Matrix3x3()))
        assertEquals(-1.9, state.estimatedPoseX, 1e-12)
        assertEquals(1.0, state.estimatedPoseY, 1e-12)
        assertEquals(2.0, state.covarianceArray[0], 1e-12)
        assertEquals(2.0, state.covarianceArray[1], 1e-12)
        assertEquals(5.0, state.covarianceArray[4], 1e-12)
        assertEquals(-1.0, state.covarianceArray[2], 1e-12)
        assertEquals(-2.0, state.covarianceArray[5], 1e-12)
    }

    @Test
    fun `failed delayed replay preserves live pose covariance and unsplit history`() {
        for (captureTime in listOf(100L, 150L)) {
            for (motion in listOf(1e200, Double.NaN)) {
                val f = Fixture()
                f.state.history.addEntryDirect(200L, 0.0, 0.0, 0.0, Matrix3x3.IDENTITY, 0.0,
                    0.0, 0.0, 0.0, true)
                f.state.history.addEntryDirect(300L, 1.0, 0.0, 0.0, Matrix3x3.IDENTITY, 0.0,
                    motion, 0.0, 0.0, true)
                val before = historyValues(f.state.history)
                val covariance = f.state.covarianceArray.copyOf()
                f.update(packet().copy(timestampMs = captureTime))
                assertFalse(f.state.lastMeasurementAccepted)
                assertEquals("invalid_replay", f.state.lastRejectionReason)
                assertEquals(0.0, f.state.estimatedPoseX)
                assertEquals(0.0, f.state.estimatedPoseY)
                assertEquals(0.0, f.state.estimatedPoseHeading)
                kotlin.test.assertContentEquals(covariance, f.state.covarianceArray)
                assertEquals(before, historyValues(f.state.history))
            }
        }
    }

    private fun historyValues(history: HistoryBuffer): List<List<Double>> = (0 until history.size).map { i ->
        val entry = history[i]
        with(entry.covariance) {
            listOf(entry.timestampMs.toDouble(), entry.x, entry.y, entry.headingRad,
                entry.deltaXRobot, entry.deltaYRobot, entry.deltaHeadingRad, entry.qScale,
                entry.effectiveQHeadingScale, if (entry.hasMotion) 1.0 else 0.0,
                m00, m01, m02, m10, m11, m12, m20, m21, m22)
        }
    }

    @Test
    fun `unmapped tag range includes lateral and vertical target-space displacement`() {
        val f = Fixture()
        f.update(packet = packet().copy(robotPoseTargetSpace = Pose3d(Translation3d(3.0, 0.0, 4.0))))
        assertTrue(f.state.lastMeasurementAccepted)
        assertEquals(0.26, f.r.m00, 1e-12) // sigma^2 * (1 + range^2) = .01 * 26
        val vertical = Fixture()
        vertical.update(packet = packet().copy(robotPoseTargetSpace = Pose3d(Translation3d(0.0, 3.0, 4.0))))
        assertEquals(0.26, vertical.r.m00, 1e-12)
    }

    @Test
    fun `invalid ambiguity limit fails closed`() {
        for (limit in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1)) {
            val f = Fixture()
            f.update(maxAmbiguity = limit)
            assertFalse(f.state.lastMeasurementAccepted, "Invalid ambiguity limit: $limit")
            assertEquals(0.0, f.state.estimatedPoseX)
        }
    }

    @Test
    fun `malformed upper covariance cannot publish an accepted nonfinite pose`() {
        for (upper in doubleArrayOf(Double.NaN, 0.5)) {
            val f = Fixture(Matrix3x3(1.0, upper, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
            f.update()
            assertFalse(f.state.lastMeasurementAccepted)
            assertEquals(0.0, f.state.estimatedPoseX)
            assertEquals(0.0, f.state.history[0].x)
        }
    }

    @Test
    fun `small positive innovation covariance is not rejected by an absolute unit cutoff`() {
        assertScaledCorrection(1e-24)
    }

    @Test
    fun `large correlated covariance does not overflow inverse factor products`() {
        assertScaledCorrection(1e240)
    }

    @Test
    fun `correction does not inflate a valid small covariance above its prior`() {
        val f = Fixture(Matrix3x3(1e-10, 0.0, 0.0, 0.0, 1e-10, 0.0, 0.0, 0.0, 1e-10))
        f.update(stdDev = 1e-6)
        assertTrue(f.state.lastMeasurementAccepted)
        assertEquals(1e-12 / 1.01, f.state.covarianceArray[0], 1e-24)
    }

    @Test
    fun `dense correlated corrections match an independent linear solve across covariance scales`() {
        val random = kotlin.random.Random(781)
        repeat(10) {
            val l = arrayOf(doubleArrayOf(0.5 + random.nextDouble(), 0.0, 0.0),
                doubleArrayOf(random.nextDouble() - 0.5, 0.5 + random.nextDouble(), 0.0),
                doubleArrayOf(random.nextDouble() - 0.5, random.nextDouble() - 0.5, 0.5 + random.nextDouble()))
            val p = Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { k -> l[i][k] * l[j][k] } } }
            val s = Array(3) { i -> DoubleArray(3) { j -> p[i][j] + if (i == j) 0.01 else 0.0 } }
            val inverse = inverseByElimination(s)
            val expected = Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { k -> p[i][k] * inverse[k][j] } } }
            for (scale in doubleArrayOf(1e-24, 1e-6, 1.0, 1e60, 1e240)) {
                val f = Fixture(Matrix3x3(p[0][0]*scale, p[0][1]*scale, p[0][2]*scale,
                    p[1][0]*scale, p[1][1]*scale, p[1][2]*scale, p[2][0]*scale, p[2][1]*scale, p[2][2]*scale))
                f.update(stdDev = sqrt(0.01 * scale))
                assertTrue(f.state.lastMeasurementAccepted, f.state.lastRejectionReason)
                for (i in 0..2) for (j in 0..2) {
                    assertEquals(expected[i][j], f.state.lastKalmanGain[i*3+j], 1e-11)
                    // With isotropic R, posterior P = K R; independent of the Joseph implementation.
                    assertEquals(expected[i][j]*0.01, f.state.covarianceArray[i*3+j]/scale, 1e-11)
                }
            }
        }
    }

    @Test
    fun `singular valid prior is accepted but indefinite or invalid range is rejected`() {
        val zero = Fixture(Matrix3x3())
        zero.update()
        assertTrue(zero.state.lastMeasurementAccepted)
        assertEquals(0.0, zero.state.covarianceArray[0])
        val indefinite = Fixture(Matrix3x3(1.0, 2.0, 0.0, 2.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        indefinite.update(stdDev = 10.0)
        assertFalse(indefinite.state.lastMeasurementAccepted)
        for (variance in doubleArrayOf(0.0, 1e-30)) {
            val invalidAxis = Fixture(Matrix3x3(variance, 1e-9, 0.0, 1e-9, 1.0, 0.0, 0.0, 0.0, 1.0))
            invalidAxis.update(stdDev = 10.0)
            assertFalse(invalidAxis.state.lastMeasurementAccepted)
        }
        val invalidRange = Fixture()
        invalidRange.update(packet().copy(robotPoseTargetSpace = Pose3d(Translation3d(Double.NaN, 0.0, 1.0))))
        assertFalse(invalidRange.state.lastMeasurementAccepted)
    }

    @Test
    fun `measurement noise cannot hide a negative prior covariance`() {
        val f = Fixture(Matrix3x3(-0.5, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        f.update(stdDev = 1.0)
        assertFalse(f.state.lastMeasurementAccepted)
        assertEquals(0.0, f.state.history[0].x)
    }

    private fun assertScaledCorrection(scale: Double) {
        val f = Fixture(Matrix3x3(scale, 0.0, 0.2 * scale, 0.0, scale, 0.0, 0.2 * scale, 0.0, scale))
        f.update(stdDev = sqrt(0.01 * scale))
        assertTrue(f.state.lastMeasurementAccepted, f.state.lastRejectionReason)
        // Independent closed-form inverse of the x/heading 2x2 block, S=[[1.01,.2],[.2,1.01]].
        val determinant = 1.01 * 1.01 - 0.04
        val gainDiagonal = (1.01 - 0.04) / determinant
        val gainCross = 0.002 / determinant
        assertEquals(gainDiagonal, f.k.m00, 1e-12)
        assertEquals(gainCross, f.k.m02, 1e-12)
        assertEquals(gainCross, f.k.m20, 1e-12)
        assertEquals(1.0 / 1.01, f.k.m11, 1e-12)
        assertEquals(0.01 * gainDiagonal, f.state.covarianceArray[0] / scale, 1e-12)
    }

    private fun packet() = VisionMeasurement(timestampMs = 100L, ambiguity = 0.0, tagId = -1,
        targetPose = Pose3d(Translation3d(0.1, 0.0, 0.0), Rotation3d()))

    private fun inverseByElimination(matrix: Array<DoubleArray>): Array<DoubleArray> {
        val augmented = Array(3) { i -> DoubleArray(6) { j ->
            if (j < 3) matrix[i][j] else if (j - 3 == i) 1.0 else 0.0
        } }
        for (column in 0..2) {
            val pivot = (column..2).maxBy { kotlin.math.abs(augmented[it][column]) }
            val row = augmented[column]
            augmented[column] = augmented[pivot]
            augmented[pivot] = row
            val divisor = augmented[column][column]
            for (j in 0..5) augmented[column][j] /= divisor
            for (i in 0..2) if (i != column) {
                val multiplier = augmented[i][column]
                for (j in 0..5) augmented[i][j] -= multiplier * augmented[column][j]
            }
        }
        return Array(3) { i -> DoubleArray(3) { j -> augmented[i][j+3] } }
    }

    private class Fixture(p: Matrix3x3 = Matrix3x3.IDENTITY) {
        val state = PoseEstimatorState().also { it.history.addEntryDirect(100L, 0.0, 0.0, 0.0, p, 0.0) }
        val r = Matrix3x3()
        val k = Matrix3x3()
        fun update(packet: VisionMeasurement = VisionMeasurement(timestampMs = 100L, ambiguity = 0.0, tagId = -1,
            targetPose = Pose3d(Translation3d(0.1, 0.0, 0.0), Rotation3d())), stdDev: Double = 0.1,
            maxAmbiguity: Double = 0.2) {
            VisionMahalanobisFilter.processVisionMeasurement(state, packet, stdDev, stdDev, stdDev, 1,
                false, 12.0, maxAmbiguity, emptyMap(), Matrix3x3(), r, Matrix3x3(), Matrix3x3(), k,
                Matrix3x3(), HistoryBuffer(), Matrix3x3(), PoseHistoryEntry())
        }
    }
}
