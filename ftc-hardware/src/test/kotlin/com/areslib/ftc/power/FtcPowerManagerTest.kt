package com.areslib.ftc.power

import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.VoltageSensor
import com.areslib.hardware.actuator.MotorIO
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MockVoltageSensor(override var voltage: Double = 12.0) : VoltageSensor

class MockMotorCurrentIO(override var currentAmps: Double = 0.0) : MotorIO {
    override var power: Double = 0.0
    override val velocity: Double = 0.0
    override val position: Double = 0.0
    override fun resetEncoder() {}
}

class FtcPowerManagerTest {
    @Test
    fun `test voltage filter sag compensation and rate limiting`() {
        val mockSensor = MockVoltageSensor(12.0)
        val hardwareMap = object : HardwareMap() {
            @Suppress("UNCHECKED_CAST")
            override fun <T> getAll(classOrType: Class<out T>): List<T> {
                return listOf(mockSensor as T)
            }
        }

        val powerManager = FtcPowerManager(hardwareMap)

        // Initial update
        powerManager.update(0.02, 100)
        assertEquals(12.0, powerManager.batteryVoltage, 1e-6)

        // Drop voltage, but the read is rate-limited to 10Hz (100ms)
        mockSensor.voltage = 9.0
        // Call update at timestamp 150 (only 50ms elapsed, should NOT read the new voltage)
        powerManager.update(0.02, 150)
        assertEquals(12.0, powerManager.batteryVoltage, 1e-6)

        // Call update at timestamp 250 (150ms elapsed, exceeds 100ms threshold, should read and filter)
        // filter math: alpha = dtSeconds / (0.1 + dtSeconds) = 0.02 / 0.12 = 1.0 / 6.0
        // voltage = 12.0 * (1.0 - alpha) + 9.0 * alpha = 11.5
        powerManager.update(0.02, 250)
        assertEquals(11.5, powerManager.batteryVoltage, 1e-6)
    }

    @Test
    fun `test brownout guard and current budgeting fallback`() {
        val mockSensor = MockVoltageSensor(12.0)
        val hardwareMap = object : HardwareMap() {
            @Suppress("UNCHECKED_CAST")
            override fun <T> getAll(classOrType: Class<out T>): List<T> {
                return listOf(mockSensor as T)
            }
        }

        com.areslib.hardware.HardwareRegistry.clear()
        try {
            val powerManager = FtcPowerManager(hardwareMap)
            val motors = listOf(MockMotorCurrentIO(1.0), MockMotorCurrentIO(2.0))
            com.areslib.hardware.HardwareRegistry.registerMotor("motor1", motors[0])
            com.areslib.hardware.HardwareRegistry.registerMotor("motor2", motors[1])

            // Assert estimated current is sum of motor currents
            assertEquals(3.0, powerManager.currentAmps, 1e-6)

            // Normal run should have 1.0 power scale
            val scale1 = powerManager.update(0.02, 100)
            assertEquals(1.0, scale1, 1e-6)

            // Trigger brownout (drop voltage to 7.0V)
            mockSensor.voltage = 7.0
            for (i in 1..10) {
                powerManager.update(0.02, 300 + i * 110L)
            }
            
            // Power scale should be reduced by brownout guard
            assertTrue(powerManager.powerScale < 1.0)
        } finally {
            com.areslib.hardware.HardwareRegistry.clear()
        }
    }
}
