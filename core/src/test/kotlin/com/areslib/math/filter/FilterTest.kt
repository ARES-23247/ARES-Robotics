package com.areslib.math.filter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilterTest {

    @Test
    fun testLowPassFilterSmoothing() {
        // Time constant = 0.1 seconds
        val filter = LowPassFilter(0.1)

        // First calculation should snap directly to the measurement (no history yet)
        assertEquals(10.0, filter.calculate(10.0, 0.02), 1e-6)

        // Second calculation with dt = 0.02s
        // Alpha = dt / (RC + dt) = 0.02 / (0.1 + 0.02) = 0.02 / 0.12 = 1/6 = 0.166667
        // Expected value = alpha * 20.0 + (1 - alpha) * 10.0 = 10.0 + (1/6 * 10.0) = 11.666667
        val expected = (1.0 / 6.0) * 20.0 + (5.0 / 6.0) * 10.0
        assertEquals(expected, filter.calculate(20.0, 0.02), 1e-5)
    }

    @Test
    fun testLowPassFilterBypass() {
        // Zero time constant means no filtering (snap immediately)
        val filter = LowPassFilter(0.0)
        
        assertEquals(5.0, filter.calculate(5.0, 0.02), 1e-6)
        assertEquals(10.0, filter.calculate(10.0, 0.02), 1e-6)
    }

    @Test
    fun testLowPassFilterReset() {
        val filter = LowPassFilter(0.5)
        filter.calculate(10.0, 0.02)
        
        filter.reset(25.0)
        assertEquals(25.0, filter.value, 1e-6)
        
        // Next calculate should filter from 25.0
        val out = filter.calculate(30.0, 0.05)
        // alpha = 0.05 / (0.5 + 0.05) = 0.05 / 0.55 = 1/11
        val expected = (1.0 / 11.0) * 30.0 + (10.0 / 11.0) * 25.0
        assertEquals(expected, out, 1e-5)
    }

    @Test
    fun testLowPassFilterClear() {
        val filter = LowPassFilter(0.2)
        filter.calculate(10.0, 0.02)
        
        filter.clear()
        // Clearing should make the next input snap directly
        assertEquals(50.0, filter.calculate(50.0, 0.02), 1e-6)
    }
}
