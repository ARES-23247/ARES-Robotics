package com.areslib.math

import com.areslib.control.feedback.GravityFeedforward
import com.areslib.math.kinematics.KinematicsMath
import java.math.BigDecimal
import java.math.MathContext
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScalarMotionBoundaryTest {
    private val precision = MathContext(80)
    private fun expectedSpeed(v: Double, a: Double, d: Double): Double {
        val squared = BigDecimal(v).multiply(BigDecimal(v)).add(BigDecimal(a).multiply(BigDecimal(d)).multiply(BigDecimal(2)))
        if (squared.signum() <= 0) return 0.0
        return squared.sqrt(precision).toDouble().let { if (it.isFinite()) it else 0.0 }
    }
    private fun near(expected: Double, actual: Double, label: String) {
        assertTrue(actual.isFinite(), "$label returned $actual")
        assertTrue(abs(expected - actual) <= maxOf(16 * Math.ulp(expected), abs(expected) * 3e-15),
            "$label expected $expected, got $actual")
    }

    @Test fun `zero acceleration or distance preserves initial speed across finite range`() {
        for (v in listOf(Double.MIN_VALUE, 1e-200, 3.0, 1e200, Double.MAX_VALUE)) {
            assertEquals(v, KinematicsMath.finalVelocity(-v, 0.0, Double.MAX_VALUE))
            assertEquals(v, KinematicsMath.finalVelocity(v, Double.MAX_VALUE, 0.0))
        }
    }

    @Test fun `finite speed survives overflowing and underflowing intermediate products`() {
        val cases = listOf(Triple(1e200, 1e200, 1e200), Triple(0.0, Double.MAX_VALUE, 0.25),
            Triple(0.0, Double.MIN_VALUE, Double.MIN_VALUE), Triple(1e-200, 1e-200, 1e-200),
            Triple(1e200, -1e200, 0.25e200), Triple(Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE / 2))
        for ((v, a, d) in cases) near(expectedSpeed(v, a, d), KinematicsMath.finalVelocity(v, a, d), "$v,$a,$d")
    }

    @Test fun `braking cancellation retains a positive squared speed residual`() {
        val v = Math.nextUp(1.0)
        val a = -0.5
        val d = Math.nextUp(Math.nextUp(1.0))
        near(expectedSpeed(v, a, d), KinematicsMath.finalVelocity(v, a, d), "near braking stop")
    }

    @Test fun `invalid and unrepresentable speed requests return neutral`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(0.0, KinematicsMath.finalVelocity(bad, 1.0, 1.0))
            assertEquals(0.0, KinematicsMath.finalVelocity(1.0, bad, 1.0))
            assertEquals(0.0, KinematicsMath.finalVelocity(1.0, 1.0, bad))
        }
        assertEquals(0.0, KinematicsMath.finalVelocity(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE))
        assertEquals(0.0, KinematicsMath.finalVelocity(2.0, -3.0, 1.0))
    }

    @Test fun `speed agrees with independent exact radicands across exponents`() {
        val random = java.util.Random(6701)
        repeat(1500) {
            fun value(): Double = Math.scalb(0.5 + random.nextDouble(), random.nextInt(2098) - 1074)
            val v = value(); val a = value() * if (random.nextBoolean()) 1 else -1; val d = value()
            near(expectedSpeed(v, a, d), KinematicsMath.finalVelocity(v, a, d), "$v,$a,$d")
        }
    }

    @Test fun `adaptive gravity avoids intermediate overflow and preserves cancellation`() {
        val cases = listOf(Triple(1e-300, 2, Double.MAX_VALUE), Triple(Double.MAX_VALUE, 0, Double.MAX_VALUE),
            Triple(2.0, 3, -1.0 / 3.0), Triple(Double.MIN_VALUE, Int.MAX_VALUE, 0.5),
            Triple(-1e-300, Int.MAX_VALUE, Double.MAX_VALUE))
        for ((base, count, factor) in cases) {
            val expected = BigDecimal(base).multiply(BigDecimal.ONE.add(BigDecimal(factor).multiply(BigDecimal(count)))).toDouble()
            near(expected, GravityFeedforward.calculateAdaptiveElevator(base, count, factor), "$base,$count,$factor")
        }
    }

    @Test fun `gravity rejects nonfinite output and preserves signed coefficients and empty counts`() {
        assertEquals(0.0, GravityFeedforward.calculateAdaptiveElevator(Double.MAX_VALUE, 1, 1.0))
        assertEquals(-3.0, GravityFeedforward.calculateAdaptiveElevator(-3.0, -1, 0.5))
        assertEquals(0.0, GravityFeedforward.calculateAdaptiveElevator(1.0, 1, Double.NaN))
        assertEquals(-3.0, GravityFeedforward.calculateElevator(-3.0))
    }

    @Test fun `arm gravity remains finite when subtracting finite angles would overflow`() {
        val angle = Double.MAX_VALUE; val offset = -Double.MAX_VALUE
        // Decimal Machin-pi reduction and cosine series agree at 450 and 550 decimal digits.
        val expected = 0.9999507580093401867058265056662600851
        near(expected, GravityFeedforward.calculateArm(angle, 1.0, offset), "overflowing angle difference")
        assertTrue(GravityFeedforward.calculateArm(angle, Double.MAX_VALUE, offset).isFinite())
        assertEquals(0.0, GravityFeedforward.calculateArm(1.0 + Math.PI / 2, 1.0, 1.0), 1e-15)
    }

    @Test fun `adaptive gravity agrees with exact products over gain and payload ranges`() {
        val random = java.util.Random(6702)
        repeat(1500) {
            val base = Math.scalb(random.nextDouble() - 0.5, random.nextInt(2098) - 1074)
            val factor = Math.scalb(random.nextDouble() - 0.5, random.nextInt(2098) - 1074)
            val count = random.nextInt(Int.MAX_VALUE)
            val exact = BigDecimal(base).multiply(BigDecimal.ONE.add(BigDecimal(factor).multiply(BigDecimal(count)))).toDouble()
            near(if (exact.isFinite()) exact else 0.0,
                GravityFeedforward.calculateAdaptiveElevator(base, count, factor), "$base,$count,$factor")
        }
    }

    @Test fun `all gravity input positions reject nonfinite values`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(0.0, GravityFeedforward.calculateElevator(bad))
            assertEquals(0.0, GravityFeedforward.calculateAdaptiveElevator(bad, 1, 0.1))
            assertEquals(0.0, GravityFeedforward.calculateAdaptiveElevator(1.0, 1, bad))
            assertEquals(0.0, GravityFeedforward.calculateArm(bad, 1.0, 0.0))
            assertEquals(0.0, GravityFeedforward.calculateArm(0.0, bad, 0.0))
            assertEquals(0.0, GravityFeedforward.calculateArm(0.0, 1.0, bad))
        }
    }

    @Test fun `scaled product roundoff matches an exact independent residual`() {
        val random = java.util.Random(6703)
        repeat(5000) {
            val a = (random.nextDouble() - 0.5) * 8
            val b = (random.nextDouble() - 0.5) * 8
            val product = a * b
            val expected = BigDecimal(a).multiply(BigDecimal(b)).subtract(BigDecimal(product)).toDouble()
            assertEquals(expected, productRoundoff(a, b, product))
        }
    }

    @Test fun `ordinary gravity gains and signed motion retain the declared formulas`() {
        assertEquals(1.5, GravityFeedforward.calculateAdaptiveElevator(1.0, 5))
        assertEquals(0.0, GravityFeedforward.calculateAdaptiveElevator(0.0, 5))
        assertEquals(-2.0, GravityFeedforward.calculateArm(0.75, -2.0, 0.75))
        assertEquals(5.0, KinematicsMath.finalVelocity(-3.0, -2.0, -4.0))
    }
}
