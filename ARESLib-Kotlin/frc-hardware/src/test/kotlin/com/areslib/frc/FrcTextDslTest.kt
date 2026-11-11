package com.areslib.frc

import com.areslib.Store
import com.areslib.state.RobotState
import com.areslib.subsystem.Subsystem
import com.areslib.telemetry.GamepadState
import com.areslib.telemetry.ITelemetry
import edu.wpi.first.wpilibj.XboxController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrcTextDslTest {
    private object NoopTelemetry : ITelemetry {
        override fun putNumber(key: String, value: Double) {}
        override fun putBoolean(key: String, value: Boolean) {}
        override fun putString(key: String, value: String) {}
        override fun putDoubleArray(key: String, value: DoubleArray) {}
        override fun getNumber(key: String, defaultValue: Double): Double = defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue
        override fun getString(key: String, defaultValue: String): String = defaultValue
        override fun update() {}
    }

    @Test
    fun `frcSwerveRobot DSL configures swerve IO subsystems and live tuning flag`() {
        var subsystemReadCount = 0
        val dummySubsystem = object : Subsystem {
            override fun readSensors(store: Store, timestampMs: Long) {
                subsystemReadCount++
            }
            override fun writeOutputs(state: RobotState, scale: Double) {}
        }

        val robot = frcSwerveRobot {
            isSimulation = true
            isLiveTuningEnabled = true
            baseTelemetry = NoopTelemetry
            isEnabledProvider = { true }
            robotModeProvider = { "Teleop" }
            subsystems(dummySubsystem)
        }

        try {
            assertTrue(robot.isLiveTuningEnabled)
            robot.update()
            assertEquals(1, subsystemReadCount)
        } finally {
            robot.close()
        }
    }

    @Test
    fun `FrcGamepadSession evaluates button and trigger bindings without allocating per tick`() {
        val rawDriver = GamepadState()
        val rawOperator = GamepadState()
        val events = mutableListOf<String>()

        val session = FrcGamepadSession(
            driverController = XboxController(0),
            operatorController = XboxController(1),
            driverSampler = { state ->
                state.a = rawDriver.a
                state.rightTrigger = rawDriver.rightTrigger
                state.leftStickX = rawDriver.leftStickX
            },
            operatorSampler = { state ->
                state.y = rawOperator.y
            },
        ).apply {
            driver.leftStickX.withDeadband(0.1).withInversion()
            driver.a.onPress("Driver A") { events += "driver-a" }
            driver.rightTrigger.whileHeld("Spin", threshold = 0.6) { events += "driver-rt" }
            operator.y.onPress("Operator Y") { events += "operator-y" }
        }

        rawDriver.a = true
        rawDriver.rightTrigger = 0.75f
        rawDriver.leftStickX = 0.5f
        rawOperator.y = true
        session.update()

        assertEquals(listOf("driver-rt", "driver-a", "operator-y"), events)
        assertTrue(session.driver.leftStickX.shapedValue < 0.0)
    }
}
