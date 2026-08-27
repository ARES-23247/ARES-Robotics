package com.areslib.frc

import com.areslib.action.RobotAction
import com.areslib.hardware.HardwareRegistry
import com.areslib.hardware.LoggableDevice
import com.areslib.hardware.TopologyNode
import com.areslib.hardware.TopologyNodeType
import com.areslib.state.DriveMode
import com.areslib.state.RobotState
import com.areslib.subsystem.Subsystem
import com.areslib.telemetry.ITelemetry
import com.areslib.frc.telemetry.FrcTelemetryManager
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FrcBaseRobotLifecycleTest {
    @BeforeEach
    fun setUp() {
        HardwareRegistry.clear()
        RobotClock.useMockTime(1_000L)
    }

    @AfterEach
    fun tearDown() {
        HardwareRegistry.clear()
        RobotClock.useSystemTime()
    }

    @Test
    fun `disabled transition safes once gates writes and clears stale drive intent`() {
        var enabled = true
        val robot = TestRobot({ enabled }, FrameTelemetry())
        val subsystem = RecordingSubsystem()
        robot.registerSubsystem(subsystem)
        robot.store.dispatch(
            RobotAction.JoystickDriveIntent(
                targetXVelocity = 2.0,
                targetYVelocity = -1.0,
                targetAngularVelocity = 0.5,
                isFieldCentric = false
            )
        )

        robot.update()
        assertEquals(1, robot.platformWrites.size)
        assertEquals(listOf(1.0), subsystem.outputScales)

        enabled = false
        RobotClock.setMockTimeMs(1_020L)
        robot.update()
        assertEquals(1, robot.platformWrites.size, "normal platform writes must be gated while disabled")
        assertEquals(listOf(1.0, 0.0), subsystem.outputScales)
        assertEquals(0.0, robot.store.state.drive.xVelocityMetersPerSecond, 1e-9)
        assertEquals(0.0, robot.store.state.drive.yVelocityMetersPerSecond, 1e-9)
        assertEquals(0.0, robot.store.state.drive.angularVelocityRadiansPerSecond, 1e-9)
        assertEquals(DriveMode.X_BRAKE, robot.store.state.drive.driveMode)
        assertTrue(robot.store.state.drive.isXLock)

        RobotClock.setMockTimeMs(1_040L)
        robot.update()
        assertEquals(listOf(1.0, 0.0), subsystem.outputScales, "safety runs on the transition, not every disabled frame")

        enabled = true
        RobotClock.setMockTimeMs(1_060L)
        robot.update()
        assertEquals(2, robot.platformWrites.size)
        assertEquals(DriveMode.X_BRAKE, robot.platformWrites.last().driveMode)
        assertEquals(0.0, robot.platformWrites.last().xVelocityMetersPerSecond, 1e-9)
        robot.close()
    }

    @Test
    fun `one FRC update flushes one coherent telemetry frame after platform topics`() {
        val base = FrameTelemetry()
        val robot = TestRobot({ true }, base)
        robot.telemetryManager.customPublishers.add { _, telemetry ->
            telemetry.putNumber("Test/CustomTopic", 7.0)
        }

        robot.update()

        assertEquals(1, base.updateCount)
        val frame = base.frames.single()
        assertTrue(frame.containsKey("Drive/Pose_X"))
        assertEquals(7.0, frame["Test/CustomTopic"])
        assertTrue(frame.containsKey("Robot/TotalCurrentAmps"))
        assertEquals(42.0, frame["Test/PlatformTopic"])
        robot.close()
    }

    @Test
    fun `completed hardware topology is queued exactly once without an early flush`() {
        val base = FrameTelemetry()
        val robot = TestRobot({ true }, base)
        HardwareRegistry.registerDevice(
            "Shooter",
            object : LoggableDevice {},
            TopologyNode(
                id = "Shooter",
                type = TopologyNodeType.CAN_MOTOR_CONTROLLER,
                displayName = "Shooter",
                canId = 9,
                canBus = "CAN2"
            )
        )

        robot.publishHardwareTopology("Marvin-XIX")
        robot.publishHardwareTopology("Marvin-XIX")

        assertEquals(1, base.stringPutCounts["Topology/HardwareMap"])
        assertEquals(0, base.updateCount)
        assertTrue(base.pending["Topology/HardwareMap"].toString().contains("\"canBus\":\"CAN2\""))

        robot.update()
        assertEquals(1, base.updateCount)
        assertTrue(base.frames.single().containsKey("Topology/HardwareMap"))
        robot.close()
    }

    @Test
    fun `base robot constructs and closes exactly one injected telemetry manager`() {
        val base = FrameTelemetry()
        var factoryCalls = 0
        var closeCalls = 0
        val robot = object : FrcBaseRobot(
            baseTelemetry = base,
            telemetryManagerFactory = { store, telemetry ->
                factoryCalls++
                object : FrcTelemetryManager(telemetry, store) {
                    override fun close() {
                        closeCalls++
                        super.close()
                    }
                }
            },
            isEnabledProvider = { false },
            robotModeProvider = { "Disabled" }
        ) {
            override fun updateHardwareInputs(timestampMs: Long) = Unit
            override fun writeHardwareOutputs(powerScale: Double, batteryVoltage: Double) = Unit
        }

        assertEquals(1, factoryCalls)
        robot.close()
        robot.close()
        assertEquals(1, closeCalls)
    }

    private class TestRobot(
        enabledProvider: () -> Boolean,
        baseTelemetry: ITelemetry
    ) : FrcBaseRobot(
        baseTelemetry = baseTelemetry,
        isEnabledProvider = enabledProvider,
        robotModeProvider = { "Test" }
    ) {
        val platformWrites = mutableListOf<com.areslib.state.DriveState>()

        init {
            batteryVoltageSupplier = java.util.function.DoubleSupplier { 12.6 }
            totalCurrentSupplier = java.util.function.DoubleSupplier { 0.0 }
            brownedOutSupplier = java.util.function.BooleanSupplier { false }
        }

        override fun updateHardwareInputs(timestampMs: Long) = Unit

        override fun writeHardwareOutputs(powerScale: Double, batteryVoltage: Double) {
            platformWrites.add(store.state.drive)
        }

        override fun publishRobotTelemetry(timestampMs: Long) {
            telemetry.putNumber("Test/PlatformTopic", 42.0)
        }
    }

    private class RecordingSubsystem : Subsystem {
        val outputScales = mutableListOf<Double>()
        override fun readSensors(store: com.areslib.Store, timestampMs: Long) = Unit
        override fun writeOutputs(state: RobotState, scale: Double) { outputScales.add(scale) }
    }

    private class FrameTelemetry : ITelemetry {
        val pending = linkedMapOf<String, Any>()
        val frames = mutableListOf<Map<String, Any>>()
        val stringPutCounts = mutableMapOf<String, Int>()
        var updateCount = 0

        override fun putNumber(key: String, value: Double) { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) { pending[key] = value }
        override fun putString(key: String, value: String) {
            pending[key] = value
            stringPutCounts[key] = (stringPutCounts[key] ?: 0) + 1
        }
        override fun putDoubleArray(key: String, value: DoubleArray) { pending[key] = value.copyOf() }
        override fun getNumber(key: String, defaultValue: Double): Double = pending[key] as? Double ?: defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = pending[key] as? Boolean ?: defaultValue
        override fun getString(key: String, defaultValue: String): String = pending[key] as? String ?: defaultValue
        override fun update() {
            updateCount++
            frames.add(LinkedHashMap(pending))
            pending.clear()
        }
    }
}
