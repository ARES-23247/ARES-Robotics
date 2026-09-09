package com.areslib.math.filter

import org.junit.jupiter.api.Test
import kotlin.test.*

class PrimitiveFilterAuditTest {
    @Test fun `median of large finite values remains finite`() {
        for (value in listOf(Double.MAX_VALUE, -Double.MAX_VALUE)) {
            val filter = MedianFilter(2)
            filter.calculate(value); assertEquals(value, filter.calculate(value)); assertEquals(value, filter.value)
        }
    }
    @Test fun `invalid median reset clears poisoned samples`() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val filter = MedianFilter(3)
            filter.calculate(10.0); filter.reset(invalid)
            assertEquals(0.0, filter.value); assertEquals(20.0, filter.calculate(20.0))
        }
    }
    @Test fun `overflowing low-pass denominator retains equal time weights`() {
        val filter = LowPassFilter(Double.MAX_VALUE)
        filter.reset(0.0)
        assertEquals(5.0, filter.calculate(10.0, Double.MAX_VALUE))
    }
    @Test fun `very small relative low-pass weights retain representable signal contributions`() {
        val filter = LowPassFilter(1e-300)
        filter.reset(1e300)
        assertEquals(1e-300, filter.calculate(0.0, 1e300), 1e-313)
    }
    @Test fun `invalid low-pass reset permits a fresh finite baseline`() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val filter = LowPassFilter(0.1)
            filter.reset(invalid); assertEquals(0.0, filter.value)
            assertEquals(20.0, filter.calculate(20.0, 0.02))
        }
    }
    @Test fun `nonpositive time cannot seed or bypass a low-pass filter`() {
        for (rc in listOf(0.0, -1.0, 0.1)) {
            val filter = LowPassFilter(rc)
            assertEquals(0.0, filter.calculate(10.0, 0.0))
            assertEquals(0.0, filter.calculate(10.0, -1.0))
            assertEquals(10.0, filter.calculate(10.0, 0.1))
            assertEquals(10.0, filter.calculate(20.0, 0.0))
        }
    }
    @Test fun `overflowing slew gap and budget still produce the bounded intermediate value`() {
        for (sign in listOf(-1.0, 1.0)) {
            val filter = SlewRateLimiter(Double.MAX_VALUE, initialValue = -sign * Double.MAX_VALUE)
            val expected = sign * (Double.MAX_VALUE / 2.0)
            assertEquals(expected, filter.calculate(sign * Double.MAX_VALUE, 1.5), Math.ulp(expected) * 4)
        }
    }
    @Test fun `sufficient overflowing slew budget reaches the finite target`() {
        val filter = SlewRateLimiter(Double.MAX_VALUE, initialValue = -Double.MAX_VALUE)
        assertEquals(Double.MAX_VALUE, filter.calculate(Double.MAX_VALUE, 3.0))
    }
    @Test fun `clear does not permit slew movement without positive elapsed time`() {
        val filter = SlewRateLimiter(2.0)
        filter.clear(); assertEquals(0.0, filter.calculate(10.0, 0.0))
        assertEquals(0.0, filter.calculate(10.0, -0.1))
        assertEquals(10.0, filter.calculate(10.0, 0.1))
    }
    @Test fun `median midpoint preserves subnormal values and opposite-sign cancellation`() {
        val filter = MedianFilter(2)
        filter.calculate(Double.MIN_VALUE); assertEquals(Double.MIN_VALUE, filter.calculate(Double.MIN_VALUE))
        assertEquals(2 * Double.MIN_VALUE, filter.calculate(2 * Double.MIN_VALUE))
        filter.clear(); filter.calculate(-Double.MAX_VALUE); assertEquals(0.0, filter.calculate(Double.MAX_VALUE))
    }
    @Test fun `low-pass subnormal midpoint and stationary extreme signals do not drift`() {
        val filter = LowPassFilter(1.0)
        filter.reset(Double.MIN_VALUE)
        assertEquals(2 * Double.MIN_VALUE, filter.calculate(2 * Double.MIN_VALUE, 1.0))
        for (value in listOf(Double.MAX_VALUE, -Double.MAX_VALUE, Double.MIN_VALUE)) {
            filter.reset(value); repeat(100) { assertEquals(value, filter.calculate(value, 0.3)) }
        }
    }
    @Test fun `invalid low-pass configuration holds state until repaired`() {
        val filter = LowPassFilter(1.0); filter.reset(2.0)
        filter.setTimeConstant(Double.NaN); assertEquals(2.0, filter.calculate(10.0, 0.1))
        filter.setTimeConstant(0.0); assertEquals(10.0, filter.calculate(10.0, 0.1))
        filter.clear(); assertEquals(0.0, filter.calculate(20.0, Double.NaN))
        assertEquals(20.0, filter.calculate(20.0, 0.1))
    }
    @Test fun `low-pass weighting retains a tiny new contribution as well as tiny history`() {
        val filter = LowPassFilter(1e300); filter.reset(0.0)
        assertEquals(1e-300, filter.calculate(1e300, 1e-300), 1e-313)
    }
    @Test fun `slew finite allowance handles an overflowing gap without overshoot`() {
        val filter = SlewRateLimiter(Double.MAX_VALUE, initialValue = -Double.MAX_VALUE)
        assertEquals(0.0, filter.calculate(Double.MAX_VALUE, 1.0))
        assertEquals(-Double.MAX_VALUE, filter.calculate(-Double.MAX_VALUE, 1.0))
        assertEquals(-Double.MAX_VALUE, filter.calculate(-Double.MAX_VALUE, Double.MAX_VALUE))
    }
    @Test fun `slew normalizes signs and preserves zero direction budgets`() {
        val filter = SlewRateLimiter(-2.0, 4.0, 1.0)
        assertEquals(3.0, filter.calculate(10.0, 1.0))
        assertEquals(-1.0, filter.calculate(-10.0, 1.0))
        filter.setRateLimits(0.0, 0.0); assertEquals(-1.0, filter.calculate(10.0, 1.0))
        assertEquals(-1.0, filter.calculate(-10.0, 1.0))
    }
    @Test fun `invalid slew settings and observations hold until repaired`() {
        val filter = SlewRateLimiter(Double.NaN, initialValue = 2.0)
        assertEquals(2.0, filter.calculate(10.0, 1.0))
        filter.setRateLimits(2.0); assertEquals(2.0, filter.calculate(Double.NaN, 1.0))
        assertEquals(2.0, filter.calculate(10.0, Double.POSITIVE_INFINITY))
        filter.reset(Double.NaN); assertEquals(0.0, filter.value)
        assertEquals(2.0, filter.calculate(10.0, 1.0))
    }
}
