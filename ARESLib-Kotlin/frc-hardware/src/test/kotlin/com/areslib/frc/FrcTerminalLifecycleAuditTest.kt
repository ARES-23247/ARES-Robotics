package com.areslib.frc

import com.areslib.hardware.HardwareRegistry
import com.areslib.hardware.SubsystemIO
import com.areslib.frc.telemetry.FrcTelemetryManager
import com.areslib.telemetry.ITelemetry
import com.areslib.telemetry.RobotStatusTracker
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FrcTerminalLifecycleAuditTest {
    @BeforeEach fun mockTime() { RobotClock.useMockTime(1_000L) }
    @AfterEach fun restoreTime() { RobotStatusTracker.isEnabled = false; RobotClock.useSystemTime() }

    @Test
    fun `closed robot rejects update before providers hardware and telemetry`() = withRobot { robot, events ->
        robot.close()
        events.clear()
        assertThrows(IllegalStateException::class.java) { robot.update() }
        assertTrue(events.isEmpty(), events.toString())
        assertFalse(RobotStatusTracker.isEnabled)
        assertNull(robot.fatalUpdateFailure, "Lifecycle misuse is not a new control fault")
    }

    @Test
    fun `closed robot rejects topology publication even if it was already published`() = withRobot { robot, events ->
        robot.publishHardwareTopology("before")
        robot.close()
        events.clear()
        assertThrows(IllegalStateException::class.java) { robot.publishHardwareTopology("after") }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `failed close remains terminal and attempts remaining owned resources`() = withRobot { robot, events ->
        val failure = AssertionError("stop")
        robot.safetyFailure = failure
        assertSame(failure, assertThrows(AssertionError::class.java) { robot.close() })
        assertTrue(events.contains("telemetry-close"))
        assertTrue(events.contains("device-close"))
        events.clear()
        robot.close()
        assertThrows(IllegalStateException::class.java) { robot.update() }
        assertTrue(events.isEmpty())
        assertFalse(RobotStatusTracker.isEnabled)
    }

    @Test
    fun `fatal retry retains first control failure and distinct safety diagnostics`() = withRobot { robot, events ->
        val primary = IllegalStateException("control")
        val initialSafety = AssertionError("initial stop")
        val retrySafety = LinkageError("retry stop")
        robot.controlFailure = primary
        robot.safetyFailure = initialSafety
        assertSame(primary, assertThrows(IllegalStateException::class.java) { robot.update() })
        assertSame(primary, robot.fatalUpdateFailure)
        assertEquals(listOf(initialSafety), primary.suppressed.toList())
        events.clear()
        robot.safetyFailure = retrySafety
        repeat(3) {
            assertSame(primary, assertThrows(IllegalStateException::class.java) { robot.update() })
        }
        assertEquals(listOf("safe", "safe", "safe"), events, "Latched faults must not resume normal work")
        assertEquals(listOf(initialSafety, retrySafety), primary.suppressed.toList())
    }

    @Test
    fun `shared throwable across control and safety preserves primary without self suppression`() = withRobot { robot, _ ->
        val shared = AssertionError("shared")
        robot.controlFailure = shared
        robot.safetyFailure = shared
        assertSame(shared, assertThrows(AssertionError::class.java) { robot.update() })
        assertSame(shared, assertThrows(AssertionError::class.java) { robot.update() })
        assertTrue(shared.suppressed.isEmpty())
    }

    private fun withRobot(block: (ProbeRobot, MutableList<String>) -> Unit) {
        val events = mutableListOf<String>()
        val robot = ProbeRobot(events)
        robot.hardwareRegistry.registerDevice("probe", object : SubsystemIO, AutoCloseable {
            override fun refresh() { events.add("refresh") }
            override fun safe() { events.add("device-safe") }
            override fun close() { events.add("device-close") }
        })
        try { block(robot, events) } finally { robot.safetyFailure = null; robot.close() }
    }

    private class ProbeRobot(val events: MutableList<String>) : FrcBaseRobot(
        hardwareRegistry = HardwareRegistry(),
        baseTelemetry = RecordingTelemetry(events),
        telemetryManagerFactory = { store, telemetry ->
            object : FrcTelemetryManager(telemetry, store) {
                override fun close() { events.add("telemetry-close"); super.close() }
            }
        },
        isEnabledProvider = { events.add("enabled"); true },
        robotModeProvider = { events.add("mode"); "Test" }
    ) {
        var controlFailure: Throwable? = null
        var safetyFailure: Throwable? = null
        init {
            batteryVoltageSupplier = java.util.function.DoubleSupplier { 12.6 }
            totalCurrentSupplier = java.util.function.DoubleSupplier { 0.0 }
            brownedOutSupplier = java.util.function.BooleanSupplier { false }
        }
        override fun updateHardwareInputs(timestampMs: Long) {
            events.add("read")
            controlFailure?.let { throw it }
        }
        override fun writeHardwareOutputs(powerScale: Double, batteryVoltage: Double) { events.add("write") }
        override fun safeHardware() {
            events.add("safe")
            safetyFailure?.let { throw it }
            super.safeHardware()
        }
    }

    private class RecordingTelemetry(val events: MutableList<String>) : ITelemetry {
        override fun putNumber(key: String, value: Double) { events.add("number") }
        override fun putBoolean(key: String, value: Boolean) { events.add("boolean") }
        override fun putString(key: String, value: String) { events.add("string") }
        override fun putDoubleArray(key: String, value: DoubleArray) { events.add("array") }
        override fun getNumber(key: String, defaultValue: Double) = defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun getString(key: String, defaultValue: String) = defaultValue
        override fun update() { events.add("flush") }
    }
}
