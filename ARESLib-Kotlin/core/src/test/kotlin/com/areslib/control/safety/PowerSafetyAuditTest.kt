package com.areslib.control.safety

import com.areslib.hardware.actuator.MotorIO
import org.junit.jupiter.api.Test
import kotlin.test.*

class PowerSafetyAuditTest {
    @Test
    fun `invalid voltage counts a warning to critical transition only once`() {
        val guard = BrownoutGuard()
        guard.update(9.0)
        assertEquals(1, guard.tripCount)
        guard.update(Double.NaN)
        assertEquals(2, guard.tripCount)
        guard.update(Double.NaN)
        assertEquals(2, guard.tripCount)
    }

    @Test
    fun `overflowing current totals cannot bypass protection`() {
        val manager = CurrentBudgetManager()
        repeat(2) { manager.register(MockMotor(power = 1.0), stallCurrentAmps = Double.MAX_VALUE) }
        manager.update(12.0, enableCalibration = true)
        assertEquals(CurrentBudgetState.CRITICAL, manager.state)
        assertEquals(0.0, manager.powerScale)
        assertTrue(manager.totalEstimatedAmps.isNaN())
    }

    @Test
    fun `voltage commands are bounded and invalid requests neutralize`() {
        val motor = MockMotor()
        for (volts in listOf(24.0, -24.0)) {
            motor.setVoltage(volts, 12.0)
            assertEquals(if (volts > 0.0) 1.0 else -1.0, motor.power)
        }
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            motor.power = 0.5
            motor.setVoltage(invalid, 12.0)
            assertEquals(0.0, motor.power)
            motor.power = 0.5
            motor.setVoltage(6.0, invalid)
            assertEquals(0.0, motor.power)
        }
        motor.setVoltage(6.0, 12.0)
        assertEquals(0.5, motor.power)
    }

    @Test
    fun `current budget rejects invalid safety configuration`() {
        assertFailsWith<IllegalArgumentException> { CurrentBudgetManager(minPowerScale = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { CurrentBudgetManager(minPowerScale = 2.0) }
        assertFailsWith<IllegalArgumentException> { CurrentBudgetManager(warningCurrentAmps = 20.0, criticalCurrentAmps = 10.0) }
        assertFailsWith<IllegalArgumentException> { CurrentBudgetManager(hysteresisAmps = -1.0) }
    }

    @Test
    fun `brownout guard rejects invalid safety configuration`() {
        assertFailsWith<IllegalArgumentException> { BrownoutGuard(minPowerScale = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { BrownoutGuard(warningVoltage = 7.0, criticalVoltage = 8.0) }
        assertFailsWith<IllegalArgumentException> { BrownoutGuard(nominalVoltage = Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { BrownoutGuard(hysteresisVoltage = -1.0) }
    }

    @Test
    fun `unknown additional current cannot restore full effort`() {
        val manager = CurrentBudgetManager()
        manager.update(12.0, additionalMeasuredCurrentAmps = 25.0)
        manager.update(12.0, additionalMeasuredCurrentAmps = Double.NaN)
        assertEquals(CurrentBudgetState.CRITICAL, manager.state)
        assertEquals(0.0, manager.powerScale)
        assertTrue(manager.totalEstimatedAmps.isNaN())
        manager.update(12.0, additionalMeasuredCurrentAmps = 0.0)
        manager.update(12.0, additionalMeasuredCurrentAmps = 0.0)
        assertEquals(CurrentBudgetState.HEALTHY, manager.state)
    }

    @Test
    fun `calibration reuses one coherent model sample per motor`() {
        val motor = CountingMotor()
        val manager = CurrentBudgetManager()
        manager.register(motor, stallCurrentAmps = 10.0)
        manager.update(12.0, enableCalibration = true)
        assertEquals(1, motor.powerReads)
        assertEquals(1, motor.scaleReads)
        assertEquals(1, motor.velocityReads)
        assertEquals(1, motor.currentReads)
        assertEquals(3.6, manager.totalEstimatedAmps, 1e-10)
    }

    @Test
    fun `stale finite current cannot calibrate the model downward`() {
        val motor = object : MotorIO by MockMotor(power = 0.5, currentAmps = 3.0) {
            override fun isCurrentReadingValid(readingAmps: Double) = false
        }
        val manager = CurrentBudgetManager()
        manager.register(motor, stallCurrentAmps = 10.0)
        manager.update(12.0, enableCalibration = true)
        assertEquals(5.0, manager.totalEstimatedAmps, 1e-10)
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
            get() { scaleReads++; return 1.0 }
            set(value) {}
        override val velocity: Double get() { velocityReads++; return 0.0 }
        override val currentAmps: Double get() { currentReads++; return 3.0 }
        override val position: Double = 0.0
        override fun resetEncoder() = Unit
    }
}
