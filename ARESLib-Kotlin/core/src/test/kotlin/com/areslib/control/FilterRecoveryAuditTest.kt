package com.areslib.control

import com.areslib.control.filters.Debouncer
import com.areslib.control.filters.EMAFilter
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class FilterRecoveryAuditTest {
    @AfterEach
    fun restoreClock() = RobotClock.useSystemTime()

    @Test
    fun `invalid EMA samples remain invalid without poisoning later samples`() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val filter = EMAFilter(0.5)
            assertEquals(10.0, filter.calculate(10.0))
            assertFalse(filter.calculate(invalid).isFinite())
            assertEquals(15.0, filter.calculate(20.0))
            filter.reset()
            assertFalse(filter.calculate(invalid).isFinite())
            assertEquals(30.0, filter.calculate(30.0))
        }
    }

    @Test
    fun `EMA endpoint weights preserve their defined behavior after invalid data`() {
        for (alpha in listOf(0.0, 1.0)) {
            val filter = EMAFilter(alpha)
            filter.calculate(10.0)
            filter.calculate(Double.NaN)
            assertEquals(if (alpha == 0.0) 10.0 else 20.0, filter.calculate(20.0))
        }
    }

    @Test
    fun `debounce restarts dwell when replay rewinds before the edge`() {
        RobotClock.useMockTime(1_000)
        val filter = Debouncer(50)
        assertFalse(filter.calculate(true))
        RobotClock.useMockTime(0)
        assertFalse(filter.calculate(true))
        RobotClock.useMockTime(50)
        assertTrue(filter.calculate(true))
    }

    @Test
    fun `rewind within a pending dwell cannot count earlier observations`() {
        RobotClock.useMockTime(1_000)
        val filter = Debouncer(50, 20)
        filter.calculate(true)
        RobotClock.useMockTime(1_030)
        filter.calculate(true)
        RobotClock.useMockTime(1_020)
        assertFalse(filter.calculate(true))
        RobotClock.useMockTime(1_050)
        assertFalse(filter.calculate(true))
        RobotClock.useMockTime(1_070)
        assertTrue(filter.calculate(true))
        filter.calculate(false)
        RobotClock.useMockTime(1_090)
        assertFalse(filter.calculate(false))
    }

    @Test
    fun `negative debounce durations are rejected and zero remains immediate`() {
        assertFailsWith<IllegalArgumentException> { Debouncer(-1) }
        assertFailsWith<IllegalArgumentException> { Debouncer(1, -1) }
        val filter = Debouncer(0)
        assertTrue(filter.calculate(true))
        assertFalse(filter.calculate(false))
    }
}
