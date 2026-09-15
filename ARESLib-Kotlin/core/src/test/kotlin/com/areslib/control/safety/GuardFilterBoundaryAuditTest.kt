package com.areslib.control.safety

import com.areslib.control.filters.Debouncer
import com.areslib.control.filters.EMAFilter
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class GuardFilterBoundaryAuditTest {
    @AfterEach fun restoreClock() = RobotClock.useSystemTime()

    @Test fun `battery percentage uses the configured nominal voltage at every positive scale`() {
        val guard = BrownoutGuard(warningVoltage = 0.04, criticalVoltage = 0.02, nominalVoltage = 0.05)
        guard.update(0.025)
        assertEquals(50.0, guard.batteryPercent, 1e-12)
        assertEquals(0.475, guard.powerScale, 1e-12)
    }

    @Test fun `elapsed debounce dwell saturates across the signed clock range`() {
        RobotClock.useMockTime(Long.MIN_VALUE)
        val filter = Debouncer(50, 20)
        assertFalse(filter.calculate(true))
        RobotClock.useMockTime(Long.MAX_VALUE)
        assertTrue(filter.calculate(true))
    }

    @Test fun `brownout hysteresis boundaries and trip counting remain deterministic`() {
        val guard = BrownoutGuard(warningVoltage = 10.0, criticalVoltage = 8.0, hysteresisVoltage = 0.5)
        guard.update(8.0)
        assertEquals(BrownoutState.CRITICAL, guard.state)
        assertEquals(0.0, guard.powerScale)
        guard.update(8.5)
        assertEquals(BrownoutState.CRITICAL, guard.state)
        guard.update(9.0)
        assertEquals(BrownoutState.WARNING, guard.state)
        assertEquals(0.65, guard.powerScale, 1e-12)
        guard.update(10.5)
        assertEquals(BrownoutState.WARNING, guard.state)
        guard.update(11.0)
        assertEquals(BrownoutState.HEALTHY, guard.state)
        assertEquals(1, guard.tripCount)
        guard.update(Double.NaN)
        assertEquals(2, guard.tripCount)
        guard.reset()
        assertEquals(0, guard.tripCount)
        assertEquals(100.0, guard.batteryPercent)
    }

    @Test fun `debounce chatter requires continuous asymmetric dwell at exact boundaries`() {
        RobotClock.useMockTime(0)
        val filter = Debouncer(50, 20)
        assertFalse(filter.calculate(true))
        RobotClock.useMockTime(49)
        assertFalse(filter.calculate(true))
        assertFalse(filter.calculate(false))
        RobotClock.useMockTime(50)
        assertFalse(filter.calculate(true))
        RobotClock.useMockTime(100)
        assertTrue(filter.calculate(true))
        assertTrue(filter.calculate(false))
        RobotClock.useMockTime(119)
        assertTrue(filter.calculate(false))
        RobotClock.useMockTime(120)
        assertFalse(filter.calculate(false))
    }

    @Test fun `EMA follows its discrete recurrence and reset contract`() {
        val filter = EMAFilter(0.25)
        assertEquals(1.0, filter.calculate(1.0))
        assertEquals(3.0, filter.calculate(9.0))
        assertEquals(3.5, filter.calculate(5.0))
        assertTrue(filter.calculate(Double.NaN).isNaN())
        assertEquals(3.125, filter.calculate(2.0))
        filter.reset()
        assertEquals(-7.0, filter.calculate(-7.0))
    }

    @Test fun `EMA finite convex updates remain bounded at extreme magnitudes`() {
        for (alpha in listOf(0.0, 0.1, 0.25, 0.5, 0.9, 1.0)) {
            val filter = EMAFilter(alpha)
            filter.calculate(Double.MAX_VALUE)
            assertEquals(Double.MAX_VALUE, filter.calculate(Double.MAX_VALUE), Math.ulp(Double.MAX_VALUE))
            val result = filter.calculate(-Double.MAX_VALUE)
            assertTrue(result.isFinite())
            assertTrue(result >= -Double.MAX_VALUE && result <= Double.MAX_VALUE)
        }
    }
}
