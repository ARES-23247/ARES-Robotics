package com.areslib.math

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.random.Random

class AnglePrecisionAuditTest {
    @Test fun `already wrapped angles preserve every represented bit`() {
        for (angle in listOf(-0.0, 0.4, -0.4, 1e-200, -1e-200, Double.MIN_VALUE,
            -Double.MIN_VALUE, Math.nextDown(Math.PI), -Math.PI)) {
            assertEquals(angle.toRawBits(), wrapAngle(angle).toRawBits(), "angle=$angle")
        }
    }

    @Test fun `large angles reduce before an offset can be rounded away`() {
        val period = BigDecimal(2.0 * Math.PI)
        val pi = BigDecimal(Math.PI)
        for (angle in listOf(Math.scalb(1.0, 55), -Math.scalb(1.0, 55), Double.MAX_VALUE, -Double.MAX_VALUE)) {
            var exact = BigDecimal(angle).remainder(period)
            if (exact >= pi) exact = exact.subtract(period)
            if (exact < pi.negate()) exact = exact.add(period)
            val expected = exact.toDouble()
            assertEquals(expected, wrapAngle(angle), Math.ulp(expected) * 2.0, "angle=$angle")
            assertTrue(wrapAngle(angle) >= -Math.PI && wrapAngle(angle) < Math.PI)
        }
    }

    @Test fun `seeded full-exponent inputs match the exact represented-period remainder`() {
        val rng = Random(4703)
        val period = BigDecimal(2.0 * Math.PI)
        val pi = BigDecimal(Math.PI)
        repeat(4000) {
            val angle = Math.scalb(rng.nextDouble(-1.0, 1.0), rng.nextInt(-1074, 1024))
            var exact = BigDecimal(angle).remainder(period)
            if (exact >= pi) exact = exact.subtract(period)
            if (exact < pi.negate()) exact = exact.add(period)
            val expected = exact.toDouble()
            val actual = wrapAngle(angle)
            assertEquals(expected, actual, Math.ulp(expected) * 2.0, "angle=$angle")
            assertTrue(actual >= -Math.PI && actual < Math.PI)
        }
    }
}
