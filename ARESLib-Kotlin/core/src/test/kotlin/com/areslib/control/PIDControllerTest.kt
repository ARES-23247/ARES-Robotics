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
        
        // First step, no previous measurement so derivative is 0
        val out1 = pid.calculate(0.0, 10.0, 0.1)
        assertEquals(0.0, out1, 0.001)
        
        // Second step, measurement moved from 0.0 to 2.0
        // derivative-on-measurement: -(2.0 - 0.0) / 0.1 = -20.0
        // EMA filter with alpha=0.2: filtered = 0.2 * -20.0 + 0.8 * 0.0 = -4.0
        // output = D * filtered_derivative = 0.5 * -4.0 = -2.0
        val out2 = pid.calculate(2.0, 10.0, 0.1)
        assertEquals(-2.0, out2, 0.001)
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
    fun `deadzone suppresses stored integral and derivative output`() {
        val integralPid = PIDController(0.0, 1.0, 0.0)
        integralPid.calculate(measurement = 0.0, setpoint = 1.0, dtSeconds = 1.0)
        integralPid.deadzone = 1.0
        assertEquals(0.0, integralPid.calculate(1.0, 1.0, 0.1), 1e-12)

        val derivativePid = PIDController(0.0, 0.0, 1.0)
        derivativePid.calculate(measurement = 0.0, setpoint = 10.0, dtSeconds = 0.1)
        derivativePid.deadzone = 1.0
        assertEquals(0.0, derivativePid.calculate(9.5, 10.0, 0.1), 1e-12)
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

    @Test
    fun testContinuousInputShortestPath() {
        val pid = PIDController(1.0, 0.0, 0.0)
        pid.enableContinuousInput(-Math.PI, Math.PI)

        // Measurement = 3.0 rad (~171.8 deg), Setpoint = -3.0 rad (~-171.8 deg)
        // Direct error = -3.0 - 3.0 = -6.0 rad
        // Shortest wrapped error = -6.0 + 2*PI ~= +0.28318 rad
        val out = pid.calculate(measurement = 3.0, setpoint = -3.0, dtSeconds = 0.02)
        val expectedWrappedError = -6.0 + 2.0 * Math.PI
        assertEquals(expectedWrappedError, out, 0.001)
    }

    @Test
    fun testOutputLimitsClamping() {
        val pid = PIDController(2.0, 0.0, 0.0)
        pid.setOutputLimits(-5.0, 5.0)

        // Error = 10.0, P*error = 20.0 -> clamped to 5.0
        val outPositive = pid.calculate(0.0, 10.0, 0.02)
        assertEquals(5.0, outPositive, 0.001)

        // Error = -10.0, P*error = -20.0 -> clamped to -5.0
        val outNegative = pid.calculate(10.0, 0.0, 0.02)
        assertEquals(-5.0, outNegative, 0.001)
    }

    @Test
    fun testIntegratorFreezesDuringOutputSaturation() {
        val pid = PIDController(1.0, 1.0, 0.0)
        pid.setOutputLimits(-5.0, 5.0)

        // Error = 10.0, P term alone (10.0) saturates output limit (5.0).
        // Since output is saturated in the direction of error, integrator must freeze (not accumulate excess windup).
        for (step in 1..10) {
            val out = pid.calculate(measurement = 0.0, setpoint = 10.0, dtSeconds = 1.0)
            assertEquals(5.0, out, 0.001)
        }

        // When error becomes 0, because the integrator froze at 0.0 rather than winding up,
        // the output should immediately drop to 0.0 without needing to unwind.
        val outAtSetpoint = pid.calculate(measurement = 10.0, setpoint = 10.0, dtSeconds = 1.0)
        assertEquals(0.0, outAtSetpoint, 0.001)

        // Test negative saturation direction
        for (step in 1..10) {
            val out = pid.calculate(measurement = 10.0, setpoint = 0.0, dtSeconds = 1.0)
            assertEquals(-5.0, out, 0.001)
        }

        val outAtSetpointNeg = pid.calculate(measurement = 0.0, setpoint = 0.0, dtSeconds = 1.0)
        assertEquals(0.0, outAtSetpointNeg, 0.001)
    }

    @Test
    fun `optimized output reuses candidate integral only when unsaturated`() {
        val pid = PIDController(1.0, 1.0, 0.0)
        pid.setOutputLimits(-5.0, 5.0)

        assertEquals(4.0, pid.calculate(measurement = 0.0, setpoint = 2.0, dtSeconds = 1.0), 1e-12)
        assertEquals(4.0, pid.calculate(measurement = 0.0, setpoint = 2.0, dtSeconds = 1.0), 1e-12)
        assertEquals(2.0, pid.calculate(measurement = 2.0, setpoint = 2.0, dtSeconds = 1.0), 1e-12)
    }
}
