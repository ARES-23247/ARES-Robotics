package com.areslib.math.kinematics

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.math.hypot

class OdometryMathTest {

    @Test
    fun `calculateDeltaPose at zero heading maps directly`() {
        val delta = OdometryMath.calculateDeltaPose(0.0, 1.0, 0.5)
        assertEquals(1.0, delta.x, 0.001)
        assertEquals(0.5, delta.y, 0.001)
    }

    @Test
    fun `calculateDeltaPose at 90 degrees swaps axes`() {
        val delta = OdometryMath.calculateDeltaPose(Math.PI / 2, 1.0, 0.5)
        assertEquals(-0.5, delta.x, 0.001)
        assertEquals(1.0, delta.y, 0.001)
    }

    @Test fun `rotation preserves length and inverse restores each input vector`() {
        val random = java.util.Random(6802)
        repeat(1500) {
            val heading = random.nextDouble() * 2e6 - 1e6
            val x = random.nextDouble() * 200 - 100; val y = random.nextDouble() * 200 - 100
            val result = OdometryMath.calculateDeltaPose(heading, x, y)
            val restored = OdometryMath.calculateDeltaPose(-heading, result.x, result.y)
            assertEquals(hypot(x, y), hypot(result.x, result.y), 1e-12)
            assertEquals(x, restored.x, 1e-12); assertEquals(y, restored.y, 1e-12)
            assertEquals(result.x, OdometryMath.calculateDeltaX(heading, x, y))
            assertEquals(result.y, OdometryMath.calculateDeltaY(heading, x, y))
        }
    }

    @Test fun `negative quarter turn and half turn follow CCW convention`() {
        val quarter = OdometryMath.calculateDeltaPose(-Math.PI / 2, 2.0, 3.0)
        assertEquals(3.0, quarter.x, 1e-14); assertEquals(-2.0, quarter.y, 1e-14)
        val half = OdometryMath.calculateDeltaPose(Math.PI, 2.0, 3.0)
        assertEquals(-2.0, half.x, 1e-14); assertEquals(-3.0, half.y, 1e-14)
    }

    @Test fun `invalid vectors remain observably nonfinite rather than becoming zero motion`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for ((heading, x, y) in listOf(Triple(bad, 1.0, 1.0), Triple(0.0, bad, 1.0), Triple(0.0, 1.0, bad))) {
                val result = OdometryMath.calculateDeltaPose(heading, x, y)
                assertTrue(!result.x.isFinite() || !result.y.isFinite())
                assertTrue(!OdometryMath.calculateDeltaX(heading, x, y).isFinite())
                assertTrue(!OdometryMath.calculateDeltaY(heading, x, y).isFinite())
            }
        }
    }

    @Test fun `each vector result is independent and this helper does not accumulate pose`() {
        val first = OdometryMath.calculateDeltaPose(0.0, Double.MAX_VALUE, Double.MIN_VALUE)
        val second = OdometryMath.calculateDeltaPose(0.0, Double.MAX_VALUE, Double.MIN_VALUE)
        assertNotSame(first, second)
        assertEquals(Double.MAX_VALUE, first.x); assertEquals(Double.MIN_VALUE, first.y)
        assertEquals(first, second)
    }
}
