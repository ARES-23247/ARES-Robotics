package com.areslib.ftc.power

import com.areslib.control.safety.CurrentBudgetManager
import com.areslib.hardware.CurrentSourceIO
import com.areslib.hardware.HardwareRegistry
import com.areslib.hardware.SubsystemIO
import com.areslib.hardware.actuator.MotorIO
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.VoltageSensor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FtcPowerReconciliationAuditTest {
    @Test
    fun `downward calibration cannot undercount a measured aggregate`() = withRig { rig ->
        val motor = rig.motor("motor", measured = 2.0)
        rig.aggregate("branch", 12.0, motor)
        rig.manager.update(0.02, 100)
        // Model moves from 10A to 4.4A; the complete branch still measures 12A.
        assertEquals(12.0, rig.manager.currentAmps, 1e-10)
    }

    @Test
    fun `upward calibration does not double count measured branch current`() = withRig { rig ->
        val motor = rig.motor("motor", measured = 18.0)
        rig.aggregate("branch", 20.0, motor)
        rig.manager.update(0.02, 100)
        // Model moves from 10A to 15.6A, already included in the measured 20A.
        assertEquals(20.0, rig.manager.currentAmps, 1e-10)
    }

    @Test
    fun `each independent branch keeps its own conservative current floor`() = withRig { rig ->
        val first = rig.motor("first")
        val second = rig.motor("second")
        rig.aggregate("low", 5.0, first)
        rig.aggregate("high", 15.0, second)
        rig.manager.update(0.02, 100)
        assertEquals(25.0, rig.manager.currentAmps, 1e-10) // max(10,5) + max(10,15)
    }

    @Test
    fun `aggregate reconciliation reads each motor model input once`() = withRig { rig ->
        val motor = rig.motor("motor", measured = 2.0)
        val aggregate = rig.aggregate("branch", 12.0, motor)
        rig.manager.update(0.02, 100)
        assertEquals(1, motor.powerReads)
        assertEquals(1, motor.scaleReads)
        assertEquals(1, motor.velocityReads)
        assertEquals(1, motor.currentReads)
        assertEquals(1, aggregate.reads)
    }

    @Test
    fun `unknown mechanism current cannot become zero load`() = withRig { rig ->
        val mechanism = rig.aggregate("mechanism", Double.NaN)
        assertEquals(0.0, rig.manager.update(0.02, 100))
        assertTrue(rig.manager.currentAmps.isNaN())
        mechanism.amps = 3.0
        rig.manager.update(0.02, 120)
        rig.manager.update(0.02, 140)
        assertEquals(3.0, rig.manager.currentAmps, 1e-10)
        assertEquals(1.0, rig.manager.powerScale)
    }

    @Test
    fun `replaced registry motor stops participating in the budget`() = withRig { rig ->
        val old = rig.motor("motor")
        rig.manager.update(0.02, 100)
        val priorReads = old.powerReads
        val replacement = ProbeMotor().also { it.power = 0.2 }
        rig.registry.registerMotor("motor", replacement)
        rig.manager.update(0.02, 120)
        assertEquals(1, rig.budget.motorCount)
        assertEquals(1.84, rig.manager.currentAmps, 1e-10)
        assertEquals(priorReads, old.powerReads)
    }

    @Test
    fun `unchanged registry preserves custom electrical model and learned calibration`() = withRig { rig ->
        rig.motor("motor", measured = 2.0)
        rig.manager.update(0.02, 100)
        assertEquals(4.4, rig.manager.currentAmps, 1e-10)
        rig.manager.update(0.02, 120)
        assertEquals(2.72, rig.manager.currentAmps, 1e-10)
    }

    @Test
    fun `clearing registry removes owned slots but preserves explicitly external models`() = withRig { rig ->
        rig.motor("owned")
        val external = ProbeMotor()
        rig.budget.register(external, stallCurrentAmps = 20.0)
        rig.manager.update(0.02, 100)
        rig.registry.clear()
        rig.manager.update(0.02, 120)
        assertEquals(1, rig.budget.motorCount)
        assertTrue(rig.budget.isRegistered(external))
        assertEquals(20.0, rig.manager.currentAmps, 1e-10)
    }

    @Test
    fun `replacing budget manager retains the new managers configured models`() = withRig { rig ->
        val motor = rig.motor("motor")
        rig.manager.update(0.02, 100)
        val replacement = CurrentBudgetManager.ftcDefaults()
        replacement.register(motor, stallCurrentAmps = 30.0)
        rig.manager.currentBudgetManager = replacement
        rig.manager.update(0.02, 120)
        assertEquals(30.0, rig.manager.currentAmps, 1e-10)
        assertEquals(1, replacement.motorCount)
        assertEquals(1, rig.budget.motorCount)
    }

    @Test
    fun `timestamp zero is a valid first voltage sample`() = withRig { rig ->
        rig.manager.update(0.001, 0)
        rig.manager.update(0.001, 1)
        rig.manager.update(0.009, 10)
        assertEquals(1, rig.sensor.readCount)
        rig.manager.update(0.01, 20)
        assertEquals(2, rig.sensor.readCount)
    }

    @Test
    fun `rewound clock cannot preserve stale healthy voltage`() = withRig { rig ->
        rig.manager.update(0.02, 1000)
        rig.sensor.voltage = 7.0
        assertEquals(0.0, rig.manager.update(0.02, 500))
        assertEquals(2, rig.sensor.readCount)
        assertEquals(7.0, rig.manager.batteryVoltage)
    }

    @Test
    fun `ordered signed timestamp overflow still refreshes voltage`() = withRig { rig ->
        rig.manager.update(0.02, Long.MIN_VALUE + 10)
        rig.sensor.voltage = 7.0
        assertEquals(0.0, rig.manager.update(0.02, Long.MAX_VALUE - 10))
        assertEquals(2, rig.sensor.readCount)
    }

    private inline fun withRig(block: (Rig) -> Unit) {
        val rig = Rig()
        try { block(rig) } finally { rig.registry.clear() }
    }

    private class Rig {
        val sensor = MockVoltageSensor(12.0)
        private val sensors = listOf(sensor)
        val registry = HardwareRegistry()
        val budget = CurrentBudgetManager.ftcDefaults()
        val manager = FtcPowerManager(object : HardwareMap() {
            @Suppress("UNCHECKED_CAST")
            override fun <T> getAll(classOrType: Class<out T>): List<T> = sensors as List<T>
        }, registry).also { it.currentBudgetManager = budget }

        fun motor(name: String, measured: Double = 0.0): ProbeMotor = ProbeMotor(measured).also {
            registry.registerMotor(name, it)
            budget.register(it, stallCurrentAmps = 10.0)
        }

        fun aggregate(name: String, amps: Double, vararg motors: MotorIO): Aggregate =
            Aggregate(amps, motors).also { registry.registerDevice(name, it) }
    }

    private class Aggregate(var amps: Double, private val motors: Array<out MotorIO>) : SubsystemIO, CurrentSourceIO {
        var reads = 0
        override val currentAmps: Double get() { reads++; return amps }
        override fun includesCurrentFrom(other: CurrentSourceIO): Boolean =
            other === this || motors.any { it === other }
    }

    private class ProbeMotor(private val measured: Double = 0.0) : MotorIO {
        var powerReads = 0
        var scaleReads = 0
        var velocityReads = 0
        var currentReads = 0
        private var effort = 1.0
        private var scale = 1.0
        override var power: Double
            get() { powerReads++; return effort }
            set(value) { effort = value }
        override var powerScale: Double
            get() { scaleReads++; return scale }
            set(value) { scale = value }
        override val velocity: Double get() { velocityReads++; return 0.0 }
        override val currentAmps: Double get() { currentReads++; return measured }
        override val position: Double = 0.0
        override fun resetEncoder() = Unit
    }
}
