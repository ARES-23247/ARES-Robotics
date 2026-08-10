package com.areslib.e2e.tier1.control

import com.areslib.control.feedback.PIDController
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PidClampingTier1Test {

    @Test
    fun testOutputClamping_shouldClampToLimits() {
        val pid = PIDController(10.0, 0.0, 0.0)
        pid.setOutputLimits(-5.0, 5.0)
        pid.setSetpoint(10.0)

        // Large error (10.0 - 0.0 = 10.0), output with P=10.0 would be 100.0, should clamp to 5.0
        val outputPos = pid.calculate(0.0, 0.02)
        assertEquals(5.0, outputPos, 1e-6)

        pid.reset()
        pid.setSetpoint(-10.0)
        // Large error opposite direction (-10.0 - 0.0 = -10.0), output would be -100.0, should clamp to -5.0
        val outputNeg = pid.calculate(0.0, 0.02)
        assertEquals(-5.0, outputNeg, 1e-6)
    }

    @Test
    fun testIntegratorAntiWindup_shouldClampAccumulator() {
        val pid = PIDController(0.0, 1.0, 0.0)
        pid.setIntegratorRange(-10.0, 10.0)
        pid.setSetpoint(100.0)

        // Run calculation multiple times to wind up integral
        var lastOutput = 0.0
        for (i in 1..20) {
            lastOutput = pid.calculate(0.0, 1.0) // error = 100, totalError += 100 * 1 = 100
        }

        // Integral should wind up to max of 10.0, so output (I * totalError) should be exactly 10.0
        assertEquals(10.0, lastOutput, 1e-6)
    }

    @Test
    fun testContinuousInput_shouldWrapAnglesCorrectly() {
        val pid = PIDController(1.0, 0.0, 0.0)
        pid.enableContinuousInput(-180.0, 180.0)

        // Setpoint is 170.0, measurement is -170.0
        // Shortest path is -20.0 degrees (wrap-around), rather than +340.0 degrees
        pid.setSetpoint(170.0)
        val output = pid.calculate(-170.0, 0.02)
        
        // Error should wrap to -20.0. P=1.0, so output should be -20.0
        assertEquals(-20.0, output, 1e-6)
    }

    @Test
    fun testNonAllocatingPrimitiveCalculation() {
        val pid = PIDController(1.0, 0.0, 0.0)
        
        // Make sure it doesn't crash on standard updates
        val out = pid.calculate(0.0, 10.0, 0.02)
        assertEquals(10.0, out, 1e-6)
    }

    @Test
    fun testSetpointValidation_isFinite() {
        val pid = PIDController(1.0, 0.0, 0.0)
        
        assertEquals(0.0, pid.calculate(Double.NaN, 0.02), 1e-6)
        assertEquals(0.0, pid.calculate(0.0, Double.POSITIVE_INFINITY, 0.02), 1e-6)
    }
}
