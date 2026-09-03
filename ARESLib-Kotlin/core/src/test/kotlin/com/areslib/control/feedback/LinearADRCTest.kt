package com.areslib.control.feedback

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LinearADRCTest {

    @Test
    fun testADRCBasicCalculationAndReset() {
        val adrc = LinearADRC(b0 = 1.0, omegaC = 10.0, omegaO = 30.0)

        // Initial update with dt <= 0 should be 0.0
        assertEquals(0.0, adrc.calculate(1.0, 0.0, 0.0))

        // Reset measurement
        adrc.reset(5.0)
        assertEquals(5.0, adrc.xHat1)
        assertEquals(0.0, adrc.xHat2)

        // Calculate next step
        val output = adrc.calculate(6.0, 5.0, 0.02)
        assertTrue(output > 0.0, "Output should command forward motion to reach target")
    }

    @Test
    fun testADRCOutputClamping() {
        val adrc = LinearADRC(b0 = 1.0, omegaC = 10.0, omegaO = 30.0)
        adrc.setOutputLimits(-2.0, 2.0)

        // Massive step command that would normally yield high control effort
        val output = adrc.calculate(100.0, 0.0, 0.02)
        assertEquals(2.0, output, 1e-6)

        val outputNeg = adrc.calculate(-100.0, 0.0, 0.02)
        assertEquals(-2.0, outputNeg, 1e-6)
    }

    @Test
    fun testADRCContinuousInput() {
        val adrc = LinearADRC(b0 = 1.0, omegaC = 10.0, omegaO = 30.0)
        adrc.enableContinuousInput(-Math.PI, Math.PI)

        // Set target slightly past PI (e.g., -175 degrees vs +175 degrees)
        // Shortest path should be standard circular wrap
        val target = -Math.PI + 0.1
        val measurement = Math.PI - 0.1
        
        adrc.reset(measurement)
        val output = adrc.calculate(target, measurement, 0.02)
        
        // Output should be positive because -179 is clockwise/positive from +179
        assertTrue(output > 0.0)
    }

    @Test
    fun `continuous observer unwraps measurement across pi boundary`() {
        val adrc = LinearADRC(b0 = 1.0, omegaC = 1.0, omegaO = 10.0)
        adrc.enableContinuousInput(-Math.PI, Math.PI)
        adrc.reset(Math.PI - 0.01)

        adrc.calculate(
            target = -Math.PI + 0.01,
            measurement = -Math.PI + 0.01,
            dtSeconds = 0.01
        )

        assertTrue(adrc.xHat1 > 3.0, "Observer must remain on the local +pi branch: ${adrc.xHat1}")
        assertTrue(kotlin.math.abs(adrc.xHat1 - Math.PI) < 0.1)
    }

    @Test
    fun `nonfinite input returns zero without poisoning observer`() {
        val adrc = LinearADRC(b0 = 1.0, omegaC = 1.0, omegaO = 10.0)
        adrc.reset(2.0)

        assertEquals(0.0, adrc.calculate(Double.NaN, 2.0, 0.02))
        assertEquals(2.0, adrc.xHat1, 1e-12)
        assertEquals(0.0, adrc.xHat2, 1e-12)
    }

    @Test
    fun `eso tracks external disturbance force over time`() {
        val adrc = LinearADRC(b0 = 2.0, omegaC = 10.0, omegaO = 30.0)
        adrc.reset(0.0)
        var plantState = 0.0
        val target = 1.0
        val disturbance = 3.0 // Constant disturbance
        val dt = 0.02

        for (i in 0 until 50) {
            val u = adrc.calculate(target, plantState, dt)
            // Plant integration: dx = (b0 * u + disturbance) * dt
            plantState += (2.0 * u + disturbance) * dt
        }

        // ESO should have observed and compensated for the disturbance
        assertTrue(adrc.xHat2 > 0.0, "xHat2 should estimate positive disturbance: ${adrc.xHat2}")
        assertTrue(kotlin.math.abs(plantState - target) < 0.2, "Plant state $plantState should converge near target $target")
    }

    @Test
    fun `dynamic gain retuning updates internal cached gains accurately`() {
        val adrc = LinearADRC(b0 = 1.0, omegaC = 5.0, omegaO = 20.0)
        adrc.reset(0.0)

        // Retune properties dynamically
        adrc.b0 = 2.5
        adrc.omegaC = 12.0
        adrc.omegaO = 40.0

        val u = adrc.calculate(1.0, 0.0, 0.01)
        // Expected u0 = 12.0 * 1.0 = 12.0. xHat2 = 0.0. uUnsat = 12.0 / 2.5 = 4.8
        assertEquals(4.8, u, 1e-6)
    }
}
