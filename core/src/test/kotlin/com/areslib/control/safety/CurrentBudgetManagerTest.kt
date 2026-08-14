package com.areslib.control.safety

import com.areslib.hardware.actuator.MotorIO
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class MockMotor(
    override var power: Double = 0.0,
    override var powerScale: Double = 1.0,
    override var velocity: Double = 0.0,
    override var position: Double = 0.0,
    override var currentAmps: Double = 0.0
) : MotorIO {
    override fun resetEncoder() {
        position = 0.0
    }
}

class CurrentBudgetManagerTest {

    private lateinit var manager: CurrentBudgetManager
    private lateinit var motor1: MockMotor
    private lateinit var motor2: MockMotor

    @BeforeEach
    fun setUp() {
        manager = CurrentBudgetManager(
            warningCurrentAmps = 15.0,
            criticalCurrentAmps = 18.0,
            minPowerScale = 0.2,
            hysteresisAmps = 1.5
        )
        motor1 = MockMotor()
        motor2 = MockMotor()
    }

    @Test
    fun `healthy current draw produces full power scale`() {
        manager.register(motor1)
        manager.register(motor2)

        // Command low power/velocity so total current is tiny
        motor1.power = 0.1
        motor2.power = 0.1

        manager.update(12.0)

        assertEquals(CurrentBudgetState.HEALTHY, manager.state)
        assertEquals(1.0, manager.powerScale, 0.001)
        assertTrue(manager.totalEstimatedAmps < manager.warningCurrentAmps)
    }

    @Test
    fun `warning current draw produces graduated power scaling`() {
        manager.register(motor1, stallCurrentAmps = 10.0, nominalVoltage = 12.0) // R = 1.2
        manager.register(motor2, stallCurrentAmps = 10.0, nominalVoltage = 12.0) // R = 1.2

        motor1.power = 0.8
        motor2.power = 0.8
        motor1.velocity = 0.01
        motor2.velocity = 0.01

        manager.update(12.0)

        assertEquals(CurrentBudgetState.WARNING, manager.state)
        assertEquals(0.7333, manager.powerScale, 0.01)
    }

    @Test
    fun `critical current draw scales power to minimum`() {
        manager.register(motor1, stallCurrentAmps = 10.0, nominalVoltage = 12.0)
        manager.register(motor2, stallCurrentAmps = 10.0, nominalVoltage = 12.0)

        motor1.power = 1.0
        motor2.power = 1.0
        motor1.velocity = 0.01
        motor2.velocity = 0.01

        manager.update(12.0)

        assertEquals(CurrentBudgetState.CRITICAL, manager.state)
        assertEquals(manager.minPowerScale, manager.powerScale, 0.001)
    }

    @Test
    fun `hysteresis prevents oscillation at warning boundary`() {
        manager.register(motor1, stallCurrentAmps = 10.0, nominalVoltage = 12.0)
        manager.register(motor2, stallCurrentAmps = 10.0, nominalVoltage = 12.0)

        motor1.power = 0.8
        motor2.power = 0.8
        motor1.velocity = 0.01
        motor2.velocity = 0.01
        manager.update(12.0)
        assertEquals(CurrentBudgetState.WARNING, manager.state)

        motor1.power = 0.7
        motor2.power = 0.7
        manager.update(12.0)
        assertEquals(CurrentBudgetState.WARNING, manager.state)

        motor1.power = 0.6
        motor2.power = 0.6
        manager.update(12.0)
        assertEquals(CurrentBudgetState.HEALTHY, manager.state)
    }

    @Test
    fun `round robin calibration blends actual current measurements`() {
        manager.register(motor1, stallCurrentAmps = 10.0, nominalVoltage = 12.0)
        manager.register(motor2, stallCurrentAmps = 10.0, nominalVoltage = 12.0)

        motor1.power = 1.0
        motor2.power = 1.0
        motor1.velocity = 0.01
        motor2.velocity = 0.01
        // Estimation: 10A each -> 20A total

        // Supply actual current measurements via MockMotor
        motor1.currentAmps = 8.0
        motor2.currentAmps = 9.0

        // Update loop 1 with calibration
        // calibrationIndex = 0 -> motor1 calibrated.
        // motor1 estimated: Blend of (10A * 0.3 + 8A * 0.7) = 8.6A
        // motor2 estimated: 10A (not calibrated this cycle)
        // Total = 18.6A
        manager.update(12.0, enableCalibration = true)
        assertEquals(18.6, manager.totalEstimatedAmps, 0.001)

        // Update loop 2 with calibration
        // calibrationIndex = 1 -> motor2 calibrated.
        // motor1 estimated: 8.6A (not calibrated this cycle)
        // motor2 estimated: Blend of (10A * 0.3 + 9A * 0.7) = 9.3A
        // Total = 17.9A
        manager.update(12.0, enableCalibration = true)
        assertEquals(17.9, manager.totalEstimatedAmps, 0.001)
    }

    @Test
    fun `backdriven motor uses signed back emf in current estimate`() {
        manager.register(motor1, stallCurrentAmps = 12.0, freeSpeedTps = 1200.0, nominalVoltage = 12.0)
        motor1.power = 0.5
        motor1.velocity = -600.0

        manager.update(12.0)

        assertEquals(12.0, manager.getMotorAmps(0), 1e-9)
    }

    @Test
    fun `powered zero velocity motor is estimated at stall current`() {
        manager.register(motor1, stallCurrentAmps = 12.0, freeSpeedTps = 1200.0, nominalVoltage = 12.0)
        motor1.power = 1.0
        motor1.velocity = 0.0

        manager.update(12.0)

        assertEquals(12.0, manager.getMotorAmps(0), 1e-9)
    }

    @Test
    fun `FTC defaults enforce the 20 amp fuse boundaries`() {
        val ftcManager = CurrentBudgetManager.ftcDefaults()

        ftcManager.update(12.0, additionalMeasuredCurrentAmps = 16.0)
        assertEquals(CurrentBudgetState.WARNING, ftcManager.state)
        assertEquals(1.0, ftcManager.powerScale, 1e-9)

        ftcManager.update(12.0, additionalMeasuredCurrentAmps = 17.0)
        assertEquals(CurrentBudgetState.WARNING, ftcManager.state)
        assertTrue(ftcManager.powerScale < 1.0)

        ftcManager.update(12.0, additionalMeasuredCurrentAmps = 20.0)
        assertEquals(CurrentBudgetState.CRITICAL, ftcManager.state)
        assertEquals(ftcManager.minPowerScale, ftcManager.powerScale, 1e-9)
    }

    @Test
    fun `estimateMotorAmps returns zero for unregistered motor`() {
        val unregisteredMotor = MockMotor()
        assertEquals(0.0, manager.estimateMotorAmps(unregisteredMotor, 12.0), 1e-9)
    }

    @Test
    fun `isRegistered accurately checks motor presence and clear resets everything`() {
        manager.register(motor1)
        assertTrue(manager.isRegistered(motor1))
        assertFalse(manager.isRegistered(motor2))
        assertEquals(1, manager.motorCount)

        manager.clear()
        assertFalse(manager.isRegistered(motor1))
        assertEquals(0, manager.motorCount)
        assertEquals(CurrentBudgetState.HEALTHY, manager.state)
        assertEquals(1.0, manager.powerScale, 1e-9)
    }
}
