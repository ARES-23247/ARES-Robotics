package com.areslib.control.safety

import com.areslib.hardware.actuator.MotorIO
import org.junit.jupiter.api.Test
import kotlin.test.*

class CurrentBudgetObservationAuditTest {
    @Test fun `unknown battery does not become a healthy twelve volt estimate`() {
        for (voltage in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0, 0.05)) {
            val motor = MockMotor(power = 0.5)
            val manager = CurrentBudgetManager()
            manager.register(motor)
            manager.update(12.0)
            manager.update(voltage, enableCalibration = true)
            assertEquals(0.0, manager.powerScale)
            assertEquals(CurrentBudgetState.CRITICAL, manager.state)
            assertTrue(manager.totalEstimatedAmps.isNaN())
            assertTrue(manager.getMotorAmps(0).isNaN())
            assertTrue(manager.estimateMotorAmps(motor, voltage).isNaN())
        }
    }

    @Test fun `invalid motor observations remain unknown in both estimation paths`() {
        val invalid = listOf(
            MockMotor(power = Double.NaN), MockMotor(power = 0.5, powerScale = Double.NaN),
            MockMotor(power = 0.5, powerScale = -0.5), MockMotor(power = 0.5, powerScale = 1.5),
            MockMotor(power = 0.5, velocity = Double.NaN), MockMotor(power = 0.5, velocity = Double.POSITIVE_INFINITY)
        )
        for (motor in invalid) {
            val manager = CurrentBudgetManager()
            manager.register(motor)
            manager.update(12.0)
            assertEquals(0.0, manager.powerScale)
            assertTrue(manager.totalEstimatedAmps.isNaN())
            assertTrue(manager.estimateMotorAmps(motor, 12.0).isNaN())
        }
    }

    @Test fun `failed cached getters invalidate the budget without retaining old estimates`() {
        var fail = false
        val motor = object : MotorIO by MockMotor(power = 0.5) {
            override val velocity: Double get() { check(!fail) { "cached feedback failed" }; return 0.0 }
        }
        val manager = CurrentBudgetManager()
        manager.register(motor)
        manager.update(12.0)
        fail = true
        manager.update(12.0)
        assertEquals(0.0, manager.powerScale)
        assertTrue(manager.getMotorAmps(0).isNaN())
        assertTrue(manager.estimateMotorAmps(motor, 12.0).isNaN())
    }

    @Test fun `repeated registration cannot double count a physical motor`() {
        val motor = MockMotor(power = 0.5)
        val manager = CurrentBudgetManager()
        manager.register(motor, stallCurrentAmps = 10.0)
        manager.register(motor, stallCurrentAmps = 10.0)
        manager.update(12.0)
        assertEquals(1, manager.motorCount)
        assertEquals(5.0, manager.totalEstimatedAmps, 1e-12)
    }

    @Test fun `replacing a registered model does not retain its old calibration`() {
        val motor = MockMotor(power = 0.5, currentAmps = 2.0)
        val manager = CurrentBudgetManager()
        manager.register(motor, stallCurrentAmps = 10.0)
        manager.update(12.0, enableCalibration = true)
        assertEquals(2.9, manager.totalEstimatedAmps, 1e-12)
        manager.register(motor, stallCurrentAmps = 20.0)
        manager.update(12.0)
        assertEquals(1, manager.motorCount)
        assertEquals(10.0, manager.totalEstimatedAmps, 1e-12)
    }

    @Test fun `invalid electrical parameters cannot silently select a different motor`() {
        val manager = CurrentBudgetManager()
        val motor = MockMotor()
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0)) {
            assertFailsWith<IllegalArgumentException> { manager.register(motor, stallCurrentAmps = invalid) }
            assertFailsWith<IllegalArgumentException> { manager.register(motor, freeSpeedTps = invalid) }
            assertFailsWith<IllegalArgumentException> { manager.register(motor, nominalVoltage = invalid) }
        }
        assertEquals(0, manager.motorCount)
    }

    @Test fun `unrepresentable electrical model is rejected before registration`() {
        val manager = CurrentBudgetManager()
        assertFailsWith<IllegalArgumentException> {
            manager.register(MockMotor(), stallCurrentAmps = Double.MIN_VALUE, nominalVoltage = Double.MAX_VALUE)
        }
        assertFailsWith<IllegalArgumentException> {
            manager.register(MockMotor(), stallCurrentAmps = Double.MAX_VALUE, nominalVoltage = Double.MIN_VALUE)
        }
        assertEquals(0, manager.motorCount)
    }

    @Test fun `same model registration preserves a learned correction`() {
        val motor = MockMotor(power = 0.5, currentAmps = 2.0)
        val manager = CurrentBudgetManager()
        manager.register(motor, stallCurrentAmps = 10.0)
        manager.update(12.0, enableCalibration = true)
        manager.register(motor, stallCurrentAmps = 10.0)
        manager.update(12.0)
        assertEquals(2.9, manager.totalEstimatedAmps, 1e-12)
    }

    @Test fun `valid samples recover the budget after unknown observations`() {
        val motor = MockMotor(power = 0.5)
        val manager = CurrentBudgetManager()
        manager.register(motor, stallCurrentAmps = 10.0)
        manager.update(Double.NaN)
        manager.update(Double.NaN)
        assertEquals(1, manager.tripCount)
        manager.update(12.0)
        manager.update(12.0)
        assertEquals(CurrentBudgetState.HEALTHY, manager.state)
        assertEquals(1.0, manager.powerScale)
        assertEquals(5.0, manager.getMotorAmps(0), 1e-12)
    }

    @Test fun `per motor query does not change aggregate state or cached estimates`() {
        val motor = MockMotor(power = 0.5)
        val manager = CurrentBudgetManager()
        manager.register(motor, stallCurrentAmps = 10.0)
        manager.update(12.0)
        motor.power = 1.0
        assertEquals(10.0, manager.estimateMotorAmps(motor, 12.0), 1e-12)
        assertEquals(5.0, manager.totalEstimatedAmps, 1e-12)
        assertEquals(5.0, manager.getMotorAmps(0), 1e-12)
    }

    @Test fun `round robin calibration uses one coherent sample and one current getter per frame`() {
        val motors = Array(4) { CountingMotor() }
        val manager = CurrentBudgetManager()
        motors.forEach { manager.register(it, stallCurrentAmps = 10.0, freeSpeedTps = 1200.0) }
        repeat(4) { manager.update(12.0, enableCalibration = true) }
        motors.forEach {
            assertEquals(4, it.powerReads)
            assertEquals(4, it.scaleReads)
            assertEquals(4, it.velocityReads)
            assertEquals(1, it.currentReads)
        }
        manager.update(Double.NaN, enableCalibration = true)
        motors.forEach {
            assertEquals(4, it.powerReads)
            assertEquals(1, it.currentReads)
        }
    }

    @Test fun `safety and filter updates allocate no heap in steady operation`() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        org.junit.jupiter.api.Assumptions.assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!
        bean.isThreadAllocatedMemoryEnabled = true
        val manager = CurrentBudgetManager()
        repeat(4) { manager.register(CountingMotor(), stallCurrentAmps = 10.0, freeSpeedTps = 1200.0) }
        val guard = BrownoutGuard()
        val ema = com.areslib.control.filters.EMAFilter(0.25)
        val debounce = com.areslib.control.filters.Debouncer(20)
        var iterations = 0
        fun update() {
            manager.update(if ((iterations++ and 7) == 0) Double.NaN else 12.0, enableCalibration = true)
            guard.update(12.0)
            ema.calculate(manager.totalEstimatedAmps)
            debounce.calculate(false)
        }
        repeat(50_000) { update() }
        val thread = Thread.currentThread().id
        var zeroWindows = 0
        var allocated = -1L
        repeat(10) {
            if (zeroWindows < 2) {
                val before = bean.getThreadAllocatedBytes(thread)
                repeat(10_000) { update() }
                allocated = bean.getThreadAllocatedBytes(thread) - before
                zeroWindows = if (allocated == 0L) zeroWindows + 1 else 0
            }
        }
        println("Safety/filter allocation: $allocated bytes per 10000 updates; $zeroWindows consecutive zero windows")
        assertEquals(2, zeroWindows)
    }

    private class CountingMotor : MotorIO {
        var powerReads = 0
        var scaleReads = 0
        var velocityReads = 0
        var currentReads = 0
        override var power: Double
            get() { powerReads++; return 0.5 }
            set(value) {}
        override var powerScale: Double
            get() { scaleReads++; return 0.5 }
            set(value) {}
        override val velocity: Double get() { velocityReads++; return 100.0 }
        override val currentAmps: Double get() { currentReads++; return 2.0 }
        override val position: Double = 0.0
        override fun resetEncoder() {}
    }
}
