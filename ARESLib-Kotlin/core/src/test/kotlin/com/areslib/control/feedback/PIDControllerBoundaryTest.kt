package com.areslib.control.feedback

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PIDControllerBoundaryTest {
    @Test
    fun `negative integral gain does not wind farther into saturation`() {
        val pid = PIDController(-1.0, -1.0, 0.0)
        pid.setOutputLimits(-1.0, 1.0)
        repeat(100) { assertEquals(-1.0, pid.calculate(0.0, 10.0, 0.02)) }
        assertEquals(0.0, pid.calculate(0.0, 0.0, 0.02), 0.0)
    }

    @Test
    fun `disabled integral does not accumulate unusable history`() {
        val pid = PIDController(0.0, 0.0, 0.0)
        repeat(600) { assertEquals(0.0, pid.calculate(0.0, 1e308, 0.02)) }
        pid.i = 1.0
        assertEquals(0.0, pid.calculate(0.0, 0.0, 0.02), 0.0)
    }

    @Test
    fun `disabling integral clears history before it is enabled again`() {
        val pid = PIDController(0.0, 1.0, 0.0)
        assertEquals(1.0, pid.calculate(0.0, 1.0, 1.0))
        pid.i = 0.0
        assertEquals(0.0, pid.calculate(0.0, 0.0, 1.0))
        pid.i = 1.0
        assertEquals(0.0, pid.calculate(0.0, 0.0, 1.0))
    }

    @Test
    fun `disabled derivative ignores overflowing measurement differences`() {
        val pid = PIDController(1.0, 0.0, 0.0)
        assertEquals(0.0, pid.calculate(1e308, 1e308, 0.02))
        assertEquals(0.0, pid.calculate(-1e308, -1e308, 0.02))
    }

    @Test
    fun `invalid input clears integral and derivative history`() {
        val integral = PIDController(0.0, 1.0, 0.0)
        assertEquals(1.0, integral.calculate(0.0, 1.0, 1.0))
        assertEquals(0.0, integral.calculate(Double.NaN, 0.0, 0.02))
        assertEquals(0.0, integral.calculate(0.0, 0.0, 0.02))
        val derivative = PIDController(0.0, 0.0, 1.0)
        derivative.calculate(0.0, 0.0, 1.0)
        assertTrue(derivative.calculate(1.0, 0.0, 1.0) < 0.0)
        assertEquals(0.0, derivative.calculate(1.0, 0.0, 0.0))
        assertEquals(0.0, derivative.calculate(1.0, 0.0, 0.02))
    }

    @Test
    fun `overflowing proportional effort neutralizes before saturation`() {
        val pid = PIDController(Double.MAX_VALUE, 0.0, 0.0)
        pid.setOutputLimits(-1.0, 1.0)
        assertEquals(0.0, pid.calculate(0.0, 2.0, 0.02))
        assertEquals(0.0, pid.calculate(0.0, 0.0, 0.02), 0.0)
    }

    @Test
    fun `overflowing integral neutralizes before integral clamping`() {
        val pid = PIDController(0.0, 1.0, 0.0)
        pid.setIntegratorRange(-1.0, 1.0)
        assertEquals(0.0, pid.calculate(0.0, 1e308, 2.0))
        assertEquals(0.0, pid.calculate(0.0, 0.0, 0.02), 0.0)
    }

    @Test
    fun `overflowing derivative neutralizes and recovers without stale rate`() {
        val pid = PIDController(0.0, 0.0, 1.0)
        pid.setOutputLimits(-1.0, 1.0)
        pid.calculate(0.0, 0.0, 0.02)
        assertEquals(0.0, pid.calculate(1e308, 1e308, 0.02))
        assertEquals(0.0, pid.calculate(1e308, 1e308, 0.02))
    }

    @Test
    fun `unrepresentable linear error neutralizes`() {
        val pid = PIDController(1.0, 0.0, 0.0)
        assertEquals(0.0, pid.calculate(-Double.MAX_VALUE, Double.MAX_VALUE, 0.02))
    }

    @Test
    fun `invalid output and integral intervals neutralize until repaired`() {
        val pid = PIDController(1.0, 1.0, 0.0)
        pid.setOutputLimits(1.0, -1.0)
        assertEquals(0.0, pid.calculate(0.0, 1.0, 1.0))
        pid.setOutputLimits(-1.0, 1.0)
        pid.setIntegratorRange(1.0, -1.0)
        assertEquals(0.0, pid.calculate(0.0, 1.0, 1.0))
        pid.setIntegratorRange(-1.0, 1.0)
        assertEquals(0.0, pid.calculate(0.0, 0.0, 1.0))
    }

    @Test
    fun `unbounded and one sided limits preserve their configured semantics`() {
        val pid = PIDController(2.0, 0.0, 0.0)
        pid.setOutputLimits(Double.NaN, 1.0)
        assertEquals(1.0, pid.calculate(0.0, 10.0, 0.02))
        assertEquals(-20.0, pid.calculate(0.0, -10.0, 0.02))
        pid.setOutputLimits(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        assertEquals(20.0, pid.calculate(0.0, 10.0, 0.02))
        pid.setOutputLimits(0.2, 0.8)
        assertEquals(0.2, pid.calculate(0.0, 0.0, 0.02))
    }

    @Test
    fun `invalid continuous input intervals neutralize`() {
        for ((minimum, maximum) in listOf(1.0 to 1.0, 1.0 to -1.0, Double.NaN to 1.0,
            0.0 to Double.POSITIVE_INFINITY, -Double.MAX_VALUE to Double.MAX_VALUE)) {
            val pid = PIDController(1.0, 0.0, 0.0)
            pid.enableContinuousInput(minimum, maximum)
            assertEquals(0.0, pid.calculate(0.0, 0.5, 0.02))
        }
    }

    @Test
    fun `large periodic error wraps without overflowing the half period shift`() {
        val pid = PIDController(1.0, 0.0, 0.0)
        val period = Math.scalb(1.0, 1023)
        pid.enableContinuousInput(0.0, period)
        // MAX_VALUE = 2 * period - ulp(MAX_VALUE), so its shortest residual is -ulp.
        assertEquals(-Math.ulp(Double.MAX_VALUE), pid.calculate(0.0, Double.MAX_VALUE, 1.0))
    }

    @Test
    fun `finite periodic endpoints wrap even when their direct difference overflows`() {
        val pid = PIDController(1.0, 0.0, 0.0)
        pid.enableContinuousInput(0.0, Math.scalb(1.0, 1023))
        assertEquals(-2.0 * Math.ulp(Double.MAX_VALUE), pid.calculate(-Double.MAX_VALUE, Double.MAX_VALUE, 1.0))
    }

    @Test
    fun `derivative responds to measurement and not setpoint steps`() {
        val pid = PIDController(0.0, 0.0, 1.0)
        pid.calculate(0.0, 0.0, 0.02)
        assertEquals(0.0, pid.calculate(0.0, 100.0, 0.02))
        assertEquals(-0.2, pid.calculate(0.02, 100.0, 0.02), 1e-12)
    }

    @Test
    fun `negative integral contribution may unwind through output saturation`() {
        val pid = PIDController(0.0, -1.0, 0.0)
        pid.setOutputLimits(-1.0, 1.0)
        assertEquals(0.5, pid.calculate(0.0, -0.5, 1.0))
        pid.p = 20.0
        assertEquals(1.0, pid.calculate(0.0, 0.1, 1.0))
        pid.p = 0.0
        assertEquals(0.4, pid.calculate(0.0, 0.0, 1.0), 1e-12)
    }

    @Test
    fun `disabled derivative clears its old filtered state`() {
        val pid = PIDController(0.0, 0.0, 1.0)
        pid.calculate(0.0, 0.0, 1.0)
        assertEquals(-0.2, pid.calculate(1.0, 0.0, 1.0), 1e-12)
        pid.d = 0.0
        assertEquals(0.0, pid.calculate(1.0, 0.0, 1.0))
        pid.d = 1.0
        assertEquals(0.0, pid.calculate(1.0, 0.0, 1.0))
    }

    @Test
    fun `invalid deadzone or gain neutralizes and clears stored effort`() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            val pid = PIDController(0.0, 1.0, 0.0)
            pid.calculate(0.0, 1.0, 1.0)
            pid.deadzone = invalid
            assertEquals(0.0, pid.calculate(0.0, 1.0, 1.0))
            pid.deadzone = 0.0
            assertEquals(0.0, pid.calculate(0.0, 0.0, 1.0))
        }
        val pid = PIDController(0.0, 1.0, 0.0)
        pid.calculate(0.0, 1.0, 1.0)
        pid.p = Double.NaN
        assertEquals(0.0, pid.calculate(0.0, 1.0, 1.0))
        pid.p = 0.0
        assertEquals(0.0, pid.calculate(0.0, 0.0, 1.0))
    }

    @Test
    fun `inward infinite limits cannot manufacture infinite effort`() {
        for ((minimum, maximum) in listOf(Double.POSITIVE_INFINITY to Double.NaN,
            Double.NaN to Double.NEGATIVE_INFINITY)) {
            val pid = PIDController(1.0, 1.0, 0.0)
            pid.setOutputLimits(minimum, maximum)
            assertEquals(0.0, pid.calculate(0.0, 1.0, 1.0))
            pid.setOutputLimits(Double.NaN, Double.NaN)
            pid.setIntegratorRange(minimum, maximum)
            assertEquals(0.0, pid.calculate(0.0, 1.0, 1.0))
        }
    }
}
