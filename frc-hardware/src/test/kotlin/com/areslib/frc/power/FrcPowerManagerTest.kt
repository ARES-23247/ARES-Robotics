package com.areslib.frc.power

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class FrcPowerManagerTest {
    @Test
    fun `test battery voltage monitoring and brownout scaling`() {
        val powerManager = FrcPowerManager()
        
        // Default voltage should be 12.6V, normal scale 1.0
        assertEquals(12.6, powerManager.batteryVoltage, 1e-6)
        
        var currentVoltage = 12.6
        powerManager.batteryVoltageSupplier = { currentVoltage }
        
        val scaleNormal = powerManager.update(0.02, 1000L)
        assertEquals(12.6, powerManager.batteryVoltage, 1e-6)
        assertEquals(1.0, scaleNormal, 1e-6)

        // Drop battery voltage to 7.0V (warning threshold is 8.5V, critical is 6.8V)
        currentVoltage = 7.0
        val scaleBrownout = powerManager.update(0.02, 1020L)
        assertEquals(7.0, powerManager.batteryVoltage, 1e-6)
        assertTrue(scaleBrownout < 1.0, "Power scale should be throttled during brownout")
        assertEquals(0.33823529411764705, scaleBrownout, 1e-6) // Warning ramp scale at 7.0V

        // Drop battery voltage to 6.0V (below critical threshold of 6.8V)
        currentVoltage = 6.0
        val scaleCritical = powerManager.update(0.02, 1040L)
        assertEquals(6.0, powerManager.batteryVoltage, 1e-6)
        assertEquals(0.0, scaleCritical, 1e-6) // Critical shutdown scale is 0.0
    }
}
