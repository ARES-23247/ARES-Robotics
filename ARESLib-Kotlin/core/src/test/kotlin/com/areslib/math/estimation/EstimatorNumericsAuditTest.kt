package com.areslib.math.estimation

import com.areslib.math.geometry.Matrix3x3
import com.areslib.math.geometry.Vector3
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EstimatorNumericsAuditTest {
    @Test
    fun `inverse preserves representable entries when a shared reciprocal would overflow`() {
        val inverse = Matrix3x3(2e-308, 0.0, 0.0, 0.0, 1e-308, 0.0, 0.0, 0.0, 1e-308).inverse()
        assertMatrix(doubleArrayOf(5e307, 0.0, 0.0, 0.0, 1e308, 0.0, 0.0, 0.0, 1e308), inverse)
    }

    @Test
    fun `dense matrix and vector operations match independent indexed products`() {
        val random = Random(7601)
        repeat(100) {
            val a = DoubleArray(9) { random.nextDouble(-4.0, 4.0) }
            val b = DoubleArray(9) { random.nextDouble(-4.0, 4.0) }
            val am = matrix(a)
            val bm = matrix(b)
            assertMatrix(product(a, b), am * bm)
            assertMatrix(DoubleArray(9) { a[it] + b[it] }, am + bm)
            assertMatrix(DoubleArray(9) { a[it] - b[it] }, am - bm)
            assertMatrix(transpose(a), am.transpose())
            assertMatrix(DoubleArray(9) { a[it] * -0.25 }, am * -0.25)
            val scratch = Matrix3x3().also { it.setTo(am); it.addInPlace(bm); it.multiplyInPlace(-0.25) }
            assertMatrix(DoubleArray(9) { (a[it] + b[it]) * -0.25 }, scratch)
            val v = Vector3(0.7, -0.4, 1.2)
            val av = am * v
            val components = doubleArrayOf(v.x, v.y, v.z)
            val actual = doubleArrayOf(av.x, av.y, av.z)
            for (row in 0..2) assertNear((0..2).sumOf { a[row * 3 + it] * components[it] }, actual[row])
            assertMatrix(DoubleArray(9) { components[it / 3] * components[it % 3] }, v.outerProduct(v))
            assertEquals(Vector3(1.4, -0.8, 2.4), v + v)
            assertEquals(Vector3(), v - v)
            assertEquals(Vector3(1.4, -0.8, 2.4), v * 2.0)
        }
    }

    @Test
    fun `inverse identity holds across matrix scales and both product orders`() {
        val random = Random(7602)
        for (scale in doubleArrayOf(1e-300, 1e-150, 1e-12, 1.0, 1e12, 1e150, 1e300)) {
            repeat(30) {
                val a = DoubleArray(9) { random.nextDouble(-0.2, 0.2) }
                for (i in 0..2) a[i * 3 + i] += 1.0
                val am = matrix(DoubleArray(9) { a[it] * scale })
                val inverse = am.inverse()
                assertMatrix(values(Matrix3x3.IDENTITY), am * inverse)
                assertMatrix(values(Matrix3x3.IDENTITY), inverse * am)
            }
        }
    }

    @Test
    fun `scalar gain survives overflowing innovation covariance`() {
        val filter = KalmanFilter(0.0, 1e308, initialError = 1e308)
        filter.reset(0.0, 1e308)
        assertNear(5.0, filter.calculate(10.0))
        assertNear(20.0 / 3.0, filter.calculate(10.0))
    }

    @Test
    fun `scalar prediction can exceed double range while its posterior stays finite`() {
        val filter = KalmanFilter(Double.MAX_VALUE, Double.MAX_VALUE)
        filter.reset(0.0, Double.MAX_VALUE)
        assertNear(6.0, filter.calculate(9.0))
        assertTrue(filter.calculate(9.0).isFinite())
    }

    @Test
    fun `a rounded zero gain does not erase representable prior uncertainty`() {
        val filter = KalmanFilter(0.0, 1e200)
        filter.reset(0.0, 1e-200)
        assertEquals(0.0, filter.calculate(10.0))
        filter.setNoiseParameters(0.0, 1e-200)
        assertEquals(5.0, filter.calculate(10.0))
    }

    @Test
    fun `opposite extreme scalar measurements retain their finite weighted estimate`() {
        val filter = KalmanFilter(0.0, 1.0)
        filter.reset(Double.MAX_VALUE, 1.0)
        assertEquals(0.0, filter.calculate(-Double.MAX_VALUE), 0.0)
    }

    @Test
    fun `scalar recurrence agrees with a high precision reference across noise scales`() {
        val context = MathContext(60)
        fun d(value: Double) = BigDecimal.valueOf(value)
        for (scale in doubleArrayOf(1e-250, 1e-15, 1.0, 1e15, 1e250)) {
            val q = d(0.3 * scale)
            val r = d(0.7 * scale)
            var p = d(2.0 * scale)
            var x = d(-3.0)
            val filter = KalmanFilter(q.toDouble(), r.toDouble())
            filter.reset(x.toDouble(), p.toDouble())
            repeat(60) { index ->
                val measurement = sin(index.toDouble()) * 12.0
                val predicted = p.add(q, context)
                val gain = predicted.divide(predicted.add(r, context), context)
                x = x.add(gain.multiply(d(measurement).subtract(x, context), context), context)
                p = predicted.multiply(r, context).divide(predicted.add(r, context), context)
                assertNear(x.toDouble(), filter.calculate(measurement))
            }
        }
    }

    @Test
    fun `invalid scalar seeds fail before corrupting a valid filter`() {
        for (invalid in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { KalmanFilter(0.0, 1.0, initialState = invalid) }
            assertFailsWith<IllegalArgumentException> { KalmanFilter(0.0, 1.0, initialError = invalid) }
        }
        assertFailsWith<IllegalArgumentException> { KalmanFilter(0.0, 1.0, initialError = -1.0) }
        val filter = KalmanFilter(0.0, 1.0)
        filter.reset(2.0, 1.0)
        assertFailsWith<IllegalArgumentException> { filter.reset(100.0, -1.0) }
        assertFailsWith<IllegalArgumentException> { filter.reset(Double.NaN, 100.0) }
        assertEquals(2.0, filter.value)
        assertEquals(3.0, filter.calculate(4.0))
    }

    @Test
    fun `invalid observations and noise leave scalar state and covariance unchanged`() {
        val filter = KalmanFilter(0.0, 1.0)
        filter.reset(2.0, 1.0)
        for (invalid in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(2.0, filter.calculate(invalid))
        }
        filter.setNoiseParameters(-1.0, 1.0)
        assertEquals(2.0, filter.calculate(100.0))
        filter.setNoiseParameters(0.0, Double.NaN)
        assertEquals(2.0, filter.calculate(100.0))
        filter.setNoiseParameters(0.0, 1.0)
        assertEquals(3.0, filter.calculate(4.0))
        filter.reset(5.0, 0.0)
        filter.setNoiseParameters(0.0, 0.0)
        assertEquals(5.0, filter.calculate(7.0))
        filter.setNoiseParameters(1.0, 0.0)
        assertEquals(7.0, filter.calculate(7.0))
    }

    @Test
    fun `fractional interpolation and corrected replay match independent arc and dense covariance`() {
        for (storedMotion in listOf(true, false)) {
            val state = PoseEstimatorState()
            val p = doubleArrayOf(0.8, 0.1, 0.04, 0.1, 0.6, -0.02, 0.04, -0.02, 0.2)
            val q = doubleArrayOf(0.02, 0.001, 0.002, 0.001, 0.03, -0.003, 0.002, -0.003, 0.01)
            val baseHeading = 0.2
            val fullArc = arc(0.8, -0.3, 0.6, baseHeading)
            state.history.addEntryDirect(0L, 0.3, -0.4, baseHeading, matrix(p), 0.0)
            state.history.addEntryDirect(100L, 0.3 + fullArc[0], -0.4 + fullArc[1], 0.8,
                matrix(p), 2.0, 0.8, -0.3, 0.6, storedMotion, 8.0)
            val partial = PoseHistoryEntry()
            val fraction = EKFStatePropagator.interpolateHistoryEntry(state.history, 0, 25L, matrix(q), partial)
            assertEquals(0.25, fraction)
            val firstArc = arc(0.2, -0.075, 0.15, baseHeading)
            assertNear(0.3 + firstArc[0], partial.x)
            assertNear(-0.4 + firstArc[1], partial.y)
            assertNear(0.35, partial.headingRad)
            assertMatrix(propagated(p, firstArc, q, 0.5, 2.0), partial.covariance)

            val scratchHistory = HistoryBuffer(150)
            state.history.copyInto(scratchHistory)
            val correctedP = DoubleArray(9) { p[it] * 0.4 }
            EKFStatePropagator.repropagateHistory(state, 0, partial, 0.1, -0.05, 0.07,
                matrix(correctedP), matrix(q), scratchHistory, Matrix3x3(), fraction)
            val remainingArc = arc(0.6, -0.225, 0.45, 0.42)
            assertNear(partial.x + 0.1 + remainingArc[0], state.estimatedPoseX)
            assertNear(partial.y - 0.05 + remainingArc[1], state.estimatedPoseY)
            assertNear(0.87, state.estimatedPoseHeading)
            val expectedP = propagated(correctedP, remainingArc, q, 1.5, 6.0)
            assertMatrix(expectedP, matrix(state.covarianceArray))
            assertMatrix(expectedP, scratchHistory[1].covariance)
        }
    }

    @Test
    fun `EKF propagation matches dense F P F transpose plus Q for correlated covariance`() {
        val random = Random(7603)
        repeat(200) {
            val factor = DoubleArray(9) { random.nextDouble(-1.0, 1.0) }
            val p = product(factor, transpose(factor))
            val noiseFactor = DoubleArray(9) { random.nextDouble(-0.1, 0.1) }
            val q = product(noiseFactor, transpose(noiseFactor))
            val heading = random.nextDouble(-3.1, 3.1)
            val dx = random.nextDouble(-2.0, 2.0)
            val dy = random.nextDouble(-2.0, 2.0)
            val f = doubleArrayOf(1.0, 0.0, -dx * sin(heading) - dy * cos(heading),
                0.0, 1.0, dx * cos(heading) - dy * sin(heading), 0.0, 0.0, 1.0)
            val expected = product(product(f, p), transpose(f)).also { for (i in it.indices) it[i] += q[i] }
            val output = Matrix3x3()
            EKFStatePropagator.propagate(p, dx, dy, heading, matrix(q), output)
            assertMatrix(expected, output)
            // The caller may reuse the Q scratchpad as its output.
            val alias = matrix(q)
            EKFStatePropagator.propagate(p, dx, dy, heading, alias, alias)
            assertMatrix(expected, alias)
        }
    }

    @Test
    fun `interpolated process cross covariance survives scale product overflow and underflow`() {
        for (scale in doubleArrayOf(1e200, 1e-200)) {
            val history = HistoryBuffer(2)
            history.addEntryDirect(0L, 0.0, 0.0, 0.0, Matrix3x3(), 0.0)
            history.addEntryDirect(100L, 0.0, 0.0, 0.0, Matrix3x3(), 2.0 * scale,
                qHeadingScale = 8.0 * scale)
            val q = Matrix3x3(1.0 / scale, 0.0, 0.25 / scale,
                0.0, 1.0 / scale, 0.0, 0.25 / scale, 0.0, 1.0 / scale)
            val output = PoseHistoryEntry()
            EKFStatePropagator.interpolateHistoryEntry(history, 0, 50L, q, output)
            assertMatrix(doubleArrayOf(1.0, 0.0, 0.5, 0.0, 1.0, 0.0, 0.5, 0.0, 4.0), output.covariance)
        }
    }

    @Test
    fun `forward odometry preserves correlated noise across extreme finite time scales`() {
        for (scale in doubleArrayOf(1e200, 1e-200)) {
            val state = PoseEstimatorState()
            val q = Matrix3x3(1.0 / scale, 0.0, 0.25 / scale,
                0.0, 1.0 / scale, 0.0, 0.25 / scale, 0.0, 1.0 / scale)
            OdometryFusionController.processOdometryDirect(state, 1000L,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, scale / 0.001,
                applyGyroBiasCorrection = false, baseQ = q, scratchQ = Matrix3x3(), scratchCov = Matrix3x3())
            assertMatrix(doubleArrayOf(2.0, 0.0, 0.25, 0.0, 2.0, 0.0, 0.25, 0.0, 2.0), matrix(state.covarianceArray))
        }
    }

    private fun assertNear(expected: Double, actual: Double) {
        assertTrue(actual.isFinite(), "Expected $expected, got $actual")
        assertEquals(expected, actual, maxOf(1e-12, abs(expected) * 2e-12))
    }
    private fun arc(dx: Double, dy: Double, dh: Double, heading: Double): DoubleArray {
        // Independent equivalent: rotate the twist by its midpoint heading and
        // scale by the chord-to-arc ratio, rather than using production's s/c matrix.
        val chord = if (dh == 0.0) 1.0 else 2.0 * sin(dh / 2.0) / dh
        val mid = heading + dh / 2.0
        return doubleArrayOf(chord * (dx * cos(mid) - dy * sin(mid)), chord * (dx * sin(mid) + dy * cos(mid)))
    }
    private fun propagated(p: DoubleArray, arc: DoubleArray, q: DoubleArray, scale: Double, headingScale: Double): DoubleArray {
        val f = doubleArrayOf(1.0, 0.0, -arc[1], 0.0, 1.0, arc[0], 0.0, 0.0, 1.0)
        val d = doubleArrayOf(kotlin.math.sqrt(scale), 0.0, 0.0, 0.0, kotlin.math.sqrt(scale), 0.0,
            0.0, 0.0, kotlin.math.sqrt(headingScale))
        val noise = product(product(d, q), transpose(d))
        return product(product(f, p), transpose(f)).also { for (i in it.indices) it[i] += noise[i] }
    }
    private fun assertMatrix(expected: DoubleArray, actual: Matrix3x3) {
        val result = values(actual)
        for (i in 0..8) assertNear(expected[i], result[i])
    }
    private fun product(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        (0..2).sumOf { k -> a[i / 3 * 3 + k] * b[k * 3 + i % 3] }
    }
    private fun transpose(a: DoubleArray) = DoubleArray(9) { a[it % 3 * 3 + it / 3] }
    private fun matrix(a: DoubleArray) = Matrix3x3(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8])
    private fun values(a: Matrix3x3) = doubleArrayOf(a.m00, a.m01, a.m02, a.m10, a.m11, a.m12, a.m20, a.m21, a.m22)
}
