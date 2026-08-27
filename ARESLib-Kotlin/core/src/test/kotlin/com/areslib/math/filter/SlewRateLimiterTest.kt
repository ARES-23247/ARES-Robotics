package com.areslib.math.filter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlewRateLimiterTest {

    @Test
    fun `constructor initial value is rate limited on first update`() {
        val limiter = SlewRateLimiter(1.0, initialValue = 0.0)
        assertEquals(1.0, limiter.calculate(10.0, 1.0), 1e-9)
    }

    @Test
    fun `invalid first input cannot poison limiter state`() {
        val limiter = SlewRateLimiter(1.0, initialValue = 2.0)
        assertEquals(2.0, limiter.calculate(Double.NaN, 1.0), 1e-9)
        assertEquals(3.0, limiter.calculate(10.0, 1.0), 1e-9)
    }

    @Test
    fun testSlewRateLimitingSymmetric() {
        // Limit to 2.0 units per second
        val limiter = SlewRateLimiter(2.0)

        // First call should snap to input directly
        assertEquals(0.0, limiter.calculate(0.0, 0.02), 1e-6)

        // Big step change from 0 to 10 with dt = 0.5 seconds
        // Max positive change = 2.0 * 0.5 = 1.0
        // Expected value = 0.0 + 1.0 = 1.0
        assertEquals(1.0, limiter.calculate(10.0, 0.5), 1e-6)

        // Small change that doesn't exceed rate limit (change of 0.1, max change is 2.0 * 0.1 = 0.2)
        assertEquals(1.1, limiter.calculate(1.1, 0.1), 1e-6)

        // Large negative change from 1.1 to -10.0 with dt = 0.5 seconds
        // Max negative change = -2.0 * 0.5 = -1.0
        // Expected value = 1.1 - 1.0 = 0.1
        assertEquals(0.1, limiter.calculate(-10.0, 0.5), 1e-6)
    }

    @Test
    fun testSlewRateLimitingAsymmetric() {
        // Positive limit = 1.0, Negative limit = -5.0 (decelerates or drops much faster than it accelerates)
        val limiter = SlewRateLimiter(1.0, -5.0)

        assertEquals(0.0, limiter.calculate(0.0, 0.02), 1e-6)

        // Positive step: limit = 1.0 * 0.5 = 0.5
        assertEquals(0.5, limiter.calculate(10.0, 0.5), 1e-6)

        // Negative step: limit = -5.0 * 0.5 = -2.5
        // Change from 0.5 to -10.0 -> max negative change allowed is -2.5
        // Expected value = 0.5 - 2.5 = -2.0
        assertEquals(-2.0, limiter.calculate(-10.0, 0.5), 1e-6)
    }

    @Test
    fun testSlewRateLimiterReset() {
        val limiter = SlewRateLimiter(2.0, initialValue = 5.0)
        
        // Reset to 12.0
        limiter.reset(12.0)
        assertEquals(12.0, limiter.value, 1e-6)

        // Should limit starting from 12.0
        // positive change = 2.0 * 0.5 = 1.0
        assertEquals(13.0, limiter.calculate(20.0, 0.5), 1e-6)
    }

    @Test
    fun testSlewRateLimiterClear() {
        val limiter = SlewRateLimiter(2.0)
        limiter.calculate(5.0, 0.5)

        limiter.clear()
        // Next call snaps directly to input
        assertEquals(100.0, limiter.calculate(100.0, 0.1), 1e-6)
    }

    @Test
    fun testZeroAndNegativeDtHandling() {
        val limiter = SlewRateLimiter(2.0, initialValue = 5.0)
        assertEquals(5.0, limiter.calculate(10.0, 0.0), 1e-6)
        assertEquals(5.0, limiter.calculate(10.0, -0.5), 1e-6)
    }
}
