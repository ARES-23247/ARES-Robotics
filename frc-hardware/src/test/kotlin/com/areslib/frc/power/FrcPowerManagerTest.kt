package com.areslib.frc.power

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach

class FrcPowerManagerTest {
    @AfterEach
    fun clearHardwareRegistry() {
        com.areslib.hardware.HardwareRegistry.clear()
    }
    @Test
    fun `test battery voltage monitoring and brownout scaling`() {
        val powerManager = FrcPowerManager().apply {
            totalCurrentSupplier = java.util.function.DoubleSupplier { 0.0 }
        }
        
        // Default voltage should be 12.6V, normal scale 1.0
        assertEquals(12.6, powerManager.batteryVoltage, 1e-6)
        
        var currentVoltage = 12.6
        powerManager.batteryVoltageSupplier = java.util.function.DoubleSupplier { currentVoltage }
        
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

    @Test
    fun `invalid or failed voltage readings fail closed`() {
        val powerManager = FrcPowerManager().apply {
            totalCurrentSupplier = java.util.function.DoubleSupplier { 0.0 }
        }

        powerManager.batteryVoltageSupplier = java.util.function.DoubleSupplier { Double.NaN }
        assertEquals(0.0, powerManager.update(0.02, 1_000L), 1e-9)

        powerManager.batteryVoltageSupplier = java.util.function.DoubleSupplier {
            throw IllegalStateException("CAN unavailable")
        }
        assertEquals(0.0, powerManager.update(0.02, 1_020L), 1e-9)
        assertEquals(0.0, powerManager.batteryVoltage, 1e-9)

        powerManager.batteryVoltageSupplier = java.util.function.DoubleSupplier { 12.4 }
        assertEquals(1.0, powerManager.update(0.02, 1_040L), 1e-9)
    }

    @Test
    fun `PDH total current applies system budget before main breaker stress`() {
        val powerManager = FrcPowerManager()
        powerManager.batteryVoltageSupplier = java.util.function.DoubleSupplier { 12.0 }
        powerManager.totalCurrentSupplier = java.util.function.DoubleSupplier { 210.0 }

        val scale = powerManager.update(0.02, 1_000L)

        assertEquals(com.areslib.control.safety.CurrentBudgetState.WARNING, powerManager.currentBudgetState)
        assertEquals(210.0, powerManager.currentAmps, 1e-9)
        assertTrue(scale in 0.4..1.0)
    }

    @Test
    fun `roboRIO brownout signal forces immediate zero effort`() {
        val powerManager = FrcPowerManager()
        powerManager.batteryVoltageSupplier = java.util.function.DoubleSupplier { 12.0 }
        powerManager.totalCurrentSupplier = java.util.function.DoubleSupplier { 0.0 }
        powerManager.brownedOutSupplier = java.util.function.BooleanSupplier { true }

        assertEquals(0.0, powerManager.update(0.02, 1_000L), 1e-9)
        assertTrue(powerManager.isBrownedOut)
    }

    @Test
    fun `fallback reads once and rejects an uncovered failed branch`() {
        class Source(private val amps: Double) : com.areslib.hardware.SubsystemIO, com.areslib.hardware.CurrentSourceIO {
            var reads = 0
            override val currentAmps: Double get() { reads++; return amps }
        }
        val child = Source(5.0)
        val aggregate = object : com.areslib.hardware.SubsystemIO, com.areslib.hardware.CurrentSourceIO {
            var reads = 0
            override val currentAmps: Double get() { reads++; return 8.0 }
            override fun includesCurrentFrom(other: com.areslib.hardware.CurrentSourceIO) =
                other === this || other === child
        }
        val bad = object : com.areslib.hardware.SubsystemIO, com.areslib.hardware.CurrentSourceIO {
            var reads = 0
            override val currentAmps: Double get() { reads++; error("CAN timeout") }
        }
        com.areslib.hardware.HardwareRegistry.registerDevice("child", child)
        com.areslib.hardware.HardwareRegistry.registerDevice("aggregate", aggregate)
        com.areslib.hardware.HardwareRegistry.registerDevice("bad", bad)
        val powerManager = FrcPowerManager().apply {
            batteryVoltageSupplier = java.util.function.DoubleSupplier { 12.0 }
        }

        powerManager.update(0.02, 1_000L)

        assertTrue(powerManager.currentAmps.isNaN())
        assertEquals(false, powerManager.currentMeasurementValid)
        assertEquals(0.4, powerManager.powerScale, 1e-9)
        assertEquals(1, child.reads)
        assertEquals(1, aggregate.reads)
        assertEquals(1, bad.reads)
    }
}
