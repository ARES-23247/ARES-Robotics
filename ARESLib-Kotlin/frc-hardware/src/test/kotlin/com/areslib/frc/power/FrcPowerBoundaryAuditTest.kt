package com.areslib.frc.power

import com.areslib.control.safety.CurrentBudgetState
import com.areslib.hardware.CurrentSourceIO
import com.areslib.hardware.HardwareRegistry
import com.areslib.hardware.SubsystemIO
import com.areslib.hardware.actuator.MotorIO
import java.util.function.BooleanSupplier
import java.util.function.DoubleSupplier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class FrcPowerBoundaryAuditTest {
    @Test
    fun `current thresholds and hysteresis use exact boundaries`() = withRig { rig ->
        val cases = listOf(
            Triple(180.0, CurrentBudgetState.WARNING, 1.0),
            Triple(210.0, CurrentBudgetState.WARNING, 0.7),
            Triple(240.0, CurrentBudgetState.CRITICAL, 0.4),
            Triple(220.0, CurrentBudgetState.CRITICAL, 0.4),
            Triple(219.0, CurrentBudgetState.WARNING, 0.61),
            Triple(160.0, CurrentBudgetState.WARNING, 1.0),
            Triple(159.0, CurrentBudgetState.HEALTHY, 1.0),
        )
        for ((current, state, scale) in cases) {
            rig.current = current
            assertEquals(scale, rig.manager.update(0.02, 0), 1e-12)
            assertEquals(state, rig.manager.currentBudgetState)
        }
    }

    @Test
    fun `valid PDH avoids branch reads and all suppliers are sampled once`() = withRig { rig ->
        val branch = Source(4.0)
        rig.registry.registerDevice("branch", branch)
        rig.current = 8.0
        rig.manager.update(0.02, 100)
        repeat(10) {
            rig.manager.batteryVoltage
            rig.manager.currentAmps
            rig.manager.powerScale
            rig.manager.isBrownedOut
        }
        assertEquals(1, rig.voltageReads)
        assertEquals(1, rig.currentReads)
        assertEquals(1, rig.brownoutReads)
        assertEquals(0, branch.reads)
        assertEquals(8.0, rig.manager.currentAmps)
    }

    @Test
    fun `invalid PDH observations use complete cached aggregate fallback`() = withRig { rig ->
        val child = Source(5.0)
        val aggregate = object : SubsystemIO, CurrentSourceIO {
            override val currentAmps = 8.0
            override fun includesCurrentFrom(other: CurrentSourceIO) = other === this || other === child
        }
        rig.registry.registerDevice("child", child)
        rig.registry.registerDevice("aggregate", aggregate)
        for (invalid in listOf(Double.NaN, -1.0, Double.POSITIVE_INFINITY)) {
            rig.current = invalid
            rig.manager.update(0.02, 100)
            assertTrue(rig.manager.currentMeasurementValid)
            assertEquals(8.0, rig.manager.currentAmps)
        }
        rig.manager.totalCurrentSupplier = DoubleSupplier { error("PDH offline") }
        rig.manager.update(0.02, 120)
        assertEquals(8.0, rig.manager.currentAmps)
        assertEquals(4, child.reads)
    }

    @Test
    fun `unknown current keeps existing conservative policy and staged recovery`() = withRig { rig ->
        rig.current = Double.NaN
        assertEquals(0.4, rig.manager.update(0.02, 100))
        assertFalse(rig.manager.currentMeasurementValid)
        assertTrue(rig.manager.currentAmps.isNaN())
        assertEquals(CurrentBudgetState.CRITICAL, rig.manager.currentBudgetState)
        rig.current = 0.0
        rig.manager.update(0.02, 120)
        assertEquals(CurrentBudgetState.WARNING, rig.manager.currentBudgetState)
        rig.manager.update(0.02, 140)
        assertEquals(CurrentBudgetState.HEALTHY, rig.manager.currentBudgetState)
    }

    @Test
    fun `failed hardware brownout supplier forces zero effort`() = withRig { rig ->
        val motor = Motor()
        rig.registry.registerMotor("motor", motor)
        rig.manager.brownedOutSupplier = BooleanSupplier { error("HAL unavailable") }
        assertEquals(0.0, rig.manager.update(0.02, 100))
        assertEquals(0.0, motor.powerScale)
        assertTrue(rig.manager.isBrownedOut)
    }

    @Test
    fun `voltage and current protection take the tighter bound`() = withRig { rig ->
        rig.current = 210.0
        rig.voltage = 7.0
        assertEquals(0.33823529411764705, rig.manager.update(0.02, 100), 1e-12)
        rig.voltage = 8.0
        assertEquals(0.7, rig.manager.update(0.02, 120), 1e-12)
    }

    @Test
    fun `power distribution follows current registry identities`() = withRig { rig ->
        val old = Motor()
        rig.registry.registerMotor("motor", old)
        rig.current = 210.0
        rig.manager.update(0.02, 100)
        val replacement = Motor()
        rig.registry.registerMotor("motor", replacement)
        rig.current = 240.0
        rig.manager.update(0.02, 120)
        assertEquals(0.7, old.powerScale, 1e-12)
        assertEquals(0.4, replacement.powerScale, 1e-12)
    }

    private inline fun withRig(block: (Rig) -> Unit) {
        val rig = Rig()
        try { block(rig) } finally { rig.registry.clear() }
    }

    private class Rig {
        val registry = HardwareRegistry()
        var voltage = 12.6
        var current = 0.0
        var voltageReads = 0
        var currentReads = 0
        var brownoutReads = 0
        val manager = FrcPowerManager(registry).apply {
            batteryVoltageSupplier = DoubleSupplier { voltageReads++; voltage }
            totalCurrentSupplier = DoubleSupplier { currentReads++; current }
            brownedOutSupplier = BooleanSupplier { brownoutReads++; false }
        }
    }

    private class Source(private val amps: Double) : SubsystemIO, CurrentSourceIO {
        var reads = 0
        override val currentAmps: Double get() { reads++; return amps }
    }

    private class Motor : MotorIO {
        override var power = 0.0
        override var powerScale = 1.0
        override val velocity = 0.0
        override val position = 0.0
        override fun resetEncoder() = Unit
    }
}
