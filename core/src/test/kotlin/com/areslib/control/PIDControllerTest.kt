package com.areslib.control

import com.areslib.control.feedback.PIDController
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs

class PIDControllerTest {

    @Test
    fun testProportionalOutput() {
        val pid = PIDController(2.0, 0.0, 0.0)
        
        // Error is setpoint - measurement = 10.0 - 5.0 = 5.0
        // Output = P * error = 2.0 * 5.0 = 10.0
        val output = pid.calculate(5.0, 10.0, 0.02)
        assertEquals(10.0, output, 0.001)
    }

    @Test
    fun testIntegralAccumulationAndAntiWindup() {
        val pid = PIDController(0.0, 1.0, 0.0)
        pid.setIntegratorRange(-5.0, 5.0)
        
        // dt = 1.0, error = 2.0 -> integral += 2.0
        pid.calculate(0.0, 2.0, 1.0)
        // dt = 1.0, error = 2.0 -> integral += 2.0 (total 4.0)
        pid.calculate(0.0, 2.0, 1.0)
        // dt = 1.0, error = 2.0 -> integral += 2.0 (total 6.0 -> capped to 5.0)
        val output = pid.calculate(0.0, 2.0, 1.0)
        
        assertEquals(5.0, output, 0.001) // Output is I * integral = 1.0 * 5.0
    }

    @Test
    fun testDerivativeFilter() {
        val pid = PIDController(0.0, 0.0, 0.5)
        
        // First step, velocity error is 0
        val out1 = pid.calculate(0.0, 10.0, 0.1)
        assertEquals(0.0, out1, 0.001)
        
        // Second step, measurement moved to 2.0. Error went from 10.0 to 8.0
        // error_diff = 8.0 - 10.0 = -2.0
        // vel_error = -2.0 / 0.1 = -20.0
        // output = D * vel_error = 0.5 * -20.0 = -10.0
        val out2 = pid.calculate(2.0, 10.0, 0.1)
        assertEquals(-10.0, out2, 0.001)
    }

    @Test
    fun testZeroDtSafety() {
        val pid = PIDController(1.0, 1.0, 1.0)
        val output = pid.calculate(5.0, 10.0, 0.0)
        assertEquals(0.0, output, 0.001)
    }

    @Test
    fun testNaNInputGuard() {
        val pid = PIDController(1.0, 1.0, 1.0)
        val output = pid.calculate(Double.NaN, 10.0, 0.02)
        assertEquals(0.0, output, 0.001)
    }

    @Test
    fun testSetpointToleranceDeadband() {
        val pid = PIDController(1.0, 0.0, 0.0)
        pid.deadzone = 1.0
        
        // Error = 0.5, within deadzone
        val out1 = pid.calculate(9.5, 10.0, 0.02)
        assertEquals(0.0, out1, 0.001)
        
        // Error = 1.5, outside deadzone
        val out2 = pid.calculate(8.5, 10.0, 0.02)
        assertEquals(1.5, out2, 0.001)
    }

    @Test
    fun testResetClearsIntegral() {
        val pid = PIDController(0.0, 1.0, 0.0)
        
        // Accumulate some integral
        pid.calculate(0.0, 10.0, 1.0)
        
        pid.reset()
        
        // Calculate with 0 error
        val out = pid.calculate(10.0, 10.0, 1.0)
        assertEquals(0.0, out, 0.001)
    }
}
