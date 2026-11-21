package com.areslib.frc

import com.areslib.action.RobotAction
import com.areslib.frc.telemetry.FrcTelemetryManager
import com.areslib.hardware.SubsystemIO
import com.areslib.state.DriveMode
import com.areslib.telemetry.ITelemetry
import com.areslib.util.RobotClock
import edu.wpi.first.networktables.NetworkTableInstance
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FreshAuditFrcRegressionTest {
    @AfterEach
    fun tearDown() {
        RobotClock.useSystemTime()
    }

    @Test
    fun `FRCTelemetry rejects post-close operations and close is idempotent`() {
        val instance = NetworkTableInstance.create()
        try {
            val telemetry = FRCTelemetry(instance, startDataLog = false)
            telemetry.putNumber("Robot/Test", 1.5)
            telemetry.update()
            telemetry.close()
            telemetry.close()
            assertThrows(IllegalStateException::class.java) { telemetry.putNumber("Robot/Test", 2.0) }
            assertThrows(IllegalStateException::class.java) { telemetry.update() }
        } finally {
            instance.close()
        }
    }

    @Test
    fun `FrcBaseRobot rejects clock rewind and propagates safeHardware failure on close`() {
        RobotClock.useMockTime(0L)
        val safetyError = AssertionError("safety stop failed")
        val robot = object : FrcBaseRobot(
            baseTelemetry = CapturingTelemetry(),
            isEnabledProvider = { true },
            robotModeProvider = { "TeleOp" },
        ) {
            init {
                batteryVoltageSupplier = java.util.function.DoubleSupplier { 12.5 }
                totalCurrentSupplier = java.util.function.DoubleSupplier { 1.0 }
                brownedOutSupplier = java.util.function.BooleanSupplier { false }
            }
            override fun updateHardwareInputs(timestampMs: Long) = Unit
            override fun writeHardwareOutputs(powerScale: Double, batteryVoltage: Double) = Unit
        }
        robot.update()
        RobotClock.useMockTime(50L)
        robot.update()
        RobotClock.useMockTime(40L)
        assertThrows(IllegalStateException::class.java) { robot.update() }

        robot.hardwareRegistry.registerDevice("faulty", object : SubsystemIO {
            override fun safe() { throw safetyError }
        })
        assertSame(safetyError, assertThrows(AssertionError::class.java) { robot.close() })
    }

    @Test
    fun `FrcTelemetryManager rotates field-centric swerve fallback by negative heading and honors X_BRAKE`() {
        val sink = CapturingTelemetry()
        val store = com.areslib.Store()
        val manager = FrcTelemetryManager(sink, store, swerveIO = null)
        try {
            store.dispatch(RobotAction.PoseUpdate(0.0, 0.0, Math.PI / 2.0, timestampMs = 100L, isReset = true))
            store.dispatch(
                RobotAction.JoystickDriveIntent(
                    targetXVelocity = 2.0,
                    targetYVelocity = 0.0,
                    targetAngularVelocity = 0.0,
                    timestampMs = 100L,
                    isFieldCentric = true,
                    isXLock = false,
                )
            )
            manager.publish(store.state, null, null, 0.02, 12.5)
            val states = requireNotNull(sink.arrays["Robot/SwerveStates"])
            for (i in 0..3) {
                assertEquals(-Math.PI / 2.0, states[i * 2], 1e-6, "Field +X at heading +pi/2 must map to robot -Y (-pi/2)")
                assertEquals(2.0, states[i * 2 + 1], 1e-6)
            }

            store.dispatch(RobotAction.SetDriveMode(DriveMode.X_BRAKE, timestampMs = 120L))
            manager.publish(store.state, null, null, 0.02, 12.5)
            val xBrake = requireNotNull(sink.arrays["Robot/SwerveStates"])
            for (i in 0..3) {
                assertEquals(0.0, xBrake[i * 2 + 1], 1e-9, "X_BRAKE must report zero wheel speeds")
            }
        } finally {
            manager.close()
        }
    }

    private class CapturingTelemetry : ITelemetry {
        val arrays = mutableMapOf<String, DoubleArray>()
        override fun putNumber(key: String, value: Double) = Unit
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun putString(key: String, value: String) = Unit
        override fun putDoubleArray(key: String, value: DoubleArray) {
            arrays[key] = value.copyOf()
        }
        override fun getNumber(key: String, defaultValue: Double) = defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun getString(key: String, defaultValue: String) = defaultValue
        override fun update() = Unit
    }
}
