package com.areslib.control.safety

import com.areslib.hardware.CurrentSourceIO
import com.areslib.hardware.actuator.MotorIO
import org.junit.jupiter.api.Test
import kotlin.test.*

class CurrentBudgetReconciliationTest {
    @Test
    fun `unregister preserves surviving calibration and round robin position`() {
        val first = Motor(2.0)
        val second = Motor(8.0)
        val manager = CurrentBudgetManager()
        manager.register(first, stallCurrentAmps = 10.0)
        manager.register(second, stallCurrentAmps = 20.0)
        manager.update(12.0, enableCalibration = true) // first -> 4.4A; second remains 20A
        val trips = manager.tripCount
        assertTrue(manager.unregister(first))
        assertFalse(manager.unregister(first))
        manager.update(12.0, enableCalibration = true) // remaining second -> 11.6A
        assertEquals(11.6, manager.totalEstimatedAmps, 1e-10)
        assertEquals(1, first.currentReads)
        assertEquals(1, second.currentReads)
        assertEquals(trips, manager.tripCount)
        assertTrue(manager.unregister(second))
        manager.update(12.0, enableCalibration = true)
        assertEquals(0.0, manager.totalEstimatedAmps)
    }

    @Test
    fun `invalid optional aggregate uses independently modeled motor`() {
        val motor = Motor()
        val aggregate = Aggregate(Double.NaN, motor)
        val manager = CurrentBudgetManager()
        manager.register(motor, stallCurrentAmps = 10.0)
        manager.updateFromCurrentSources(12.0, listOf(motor, aggregate))
        assertEquals(10.0, manager.totalEstimatedAmps, 1e-10)
        assertEquals(0, motor.currentReads) // calibration disabled; never sampled as a raw source
    }

    @Test
    fun `overlapping aggregates subtract each modeled motor at most once`() {
        val motor = Motor()
        val manager = CurrentBudgetManager()
        manager.register(motor, stallCurrentAmps = 10.0)
        manager.updateFromCurrentSources(12.0, listOf(motor, Aggregate(12.0, motor), Aggregate(15.0, motor)))
        assertEquals(27.0, manager.totalEstimatedAmps, 1e-10)
    }

    @Test
    fun `unregistered motor cannot silently become zero current`() {
        val manager = CurrentBudgetManager()
        manager.updateFromCurrentSources(12.0, listOf(Motor()))
        assertTrue(manager.totalEstimatedAmps.isNaN())
        assertEquals(0.0, manager.powerScale)
    }

    @Test
    fun `unknown model remains unknown even beside a valid aggregate`() {
        val motor = Motor().also { it.power = Double.NaN }
        val manager = CurrentBudgetManager()
        manager.register(motor)
        manager.updateFromCurrentSources(12.0, listOf(motor, Aggregate(12.0, motor)))
        assertTrue(manager.totalEstimatedAmps.isNaN())
        assertEquals(0.0, manager.powerScale)
    }

    @Test
    fun `current source mode advances hysteresis only once per frame`() {
        val manager = CurrentBudgetManager()
        val source = Aggregate(30.0)
        manager.updateFromCurrentSources(12.0, listOf(source))
        assertEquals(CurrentBudgetState.CRITICAL, manager.state)
        source.amps = 0.0
        manager.updateFromCurrentSources(12.0, listOf(source))
        assertEquals(CurrentBudgetState.WARNING, manager.state)
        manager.updateFromCurrentSources(12.0, listOf(source))
        assertEquals(CurrentBudgetState.HEALTHY, manager.state)
        assertEquals(1, manager.tripCount)
    }

    @Test
    fun `invalid battery does not sample current providers`() {
        val source = object : CurrentSourceIO {
            var reads = 0
            override val currentAmps: Double get() { reads++; return 2.0 }
        }
        val manager = CurrentBudgetManager()
        manager.updateFromCurrentSources(Double.NaN, listOf(source))
        assertTrue(manager.totalEstimatedAmps.isNaN())
        assertEquals(0.0, manager.powerScale)
        assertEquals(0, source.reads)
    }

    private class Aggregate(var amps: Double, private vararg val motors: MotorIO) : CurrentSourceIO {
        override val currentAmps: Double get() = amps
        override fun includesCurrentFrom(other: CurrentSourceIO): Boolean =
            other === this || motors.any { it === other }
    }

    private class Motor(private val measured: Double = 0.0) : MotorIO {
        var currentReads = 0
        override var power = 1.0
        override val velocity = 0.0
        override val position = 0.0
        override val currentAmps: Double get() { currentReads++; return measured }
        override fun resetEncoder() = Unit
    }
}
