package com.areslib.control

import com.areslib.control.filters.Debouncer
import com.areslib.control.filters.EMAFilter
import com.areslib.util.RobotClock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FiltersTest {

    @BeforeEach
    fun setUp() {
        RobotClock.useMockTime(0)
    }

    @Test
    fun testDebouncer() {
        val debouncer = Debouncer(risingTimeMs = 50, fallingTimeMs = 50)
        
        RobotClock.useMockTime(0)
        assertFalse(debouncer.calculate(false))
        
        // Input goes true, but only for 10ms
        RobotClock.useMockTime(10)
        assertFalse(debouncer.calculate(true))
        
        RobotClock.useMockTime(20)
        assertFalse(debouncer.calculate(false)) // Input bounced back to false

        // Input goes true and stays true for 60ms
        RobotClock.useMockTime(30)
        assertFalse(debouncer.calculate(true))
        
        RobotClock.useMockTime(85)
        assertTrue(debouncer.calculate(true)) // It has been 55ms since baseline changed to true!
        
        // Test falling edge
        RobotClock.useMockTime(90)
        assertTrue(debouncer.calculate(false)) // False for only 5ms
        
        RobotClock.useMockTime(150)
        assertFalse(debouncer.calculate(false)) // False for 60ms, correctly registers false
    }

    @Test
    fun testEMAFilter() {
        val filter = EMAFilter(alpha = 0.5)
        
        assertEquals(10.0, filter.calculate(10.0), 0.001) // First value sets the baseline
        assertEquals(15.0, filter.calculate(20.0), 0.001) // (0.5 * 20) + (0.5 * 10) = 15
        assertEquals(22.5, filter.calculate(30.0), 0.001) // (0.5 * 30) + (0.5 * 15) = 22.5
    }
}
