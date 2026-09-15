package com.areslib.ftc

import com.areslib.Store
import com.areslib.state.RobotState
import com.areslib.subsystem.Subsystem
import com.qualcomm.robotcore.hardware.HardwareMap
import kotlin.test.*
import org.junit.jupiter.api.Test

class FtcRegisteredSubsystemLifecycleAuditTest {
    @Test fun closingBaseRobotNeutralizesAndClosesRegisteredSubsystemsExactlyOnce() {
        val robot = ProbeRobot()
        val events = mutableListOf<String>()
        robot.registerSubsystem(object : Subsystem {
            override fun readSensors(store: Store, timestampMs: Long) = Unit
            override fun writeOutputs(state: RobotState, scale: Double) { assertEquals(0.0, scale); events += "neutral" }
            override fun close() { events += "close" }
        })
        try { robot.close(); robot.close(); assertEquals(listOf("neutral", "close"), events) }
        finally { robot.close() }
    }

    @Test fun sharedFailureCannotSkipRemainingRobotResources() {
        val failure = AssertionError("shared")
        val robot = ProbeRobot()
        robot.safetyFailure = failure
        var resourceClosed = 0
        var subsystemClosed = 0
        robot.hardwareRegistry.registerCloseable(AutoCloseable { resourceClosed++ })
        robot.registerSubsystem(object : Subsystem {
            override fun readSensors(store: Store, timestampMs: Long) = Unit
            override fun writeOutputs(state: RobotState, scale: Double) = Unit
            override fun close() { subsystemClosed++; throw failure }
        })
        try {
            assertSame(failure, assertFailsWith<AssertionError> { robot.close() })
            assertEquals(1, subsystemClosed); assertEquals(1, resourceClosed)
            assertTrue(failure.suppressed.isEmpty())
        } finally { robot.close() }
    }

    private class ProbeRobot : FtcBaseRobot(object : HardwareMap() {
        override fun <T> get(classOrType: Class<out T>, deviceName: String): T = error("No device")
        override fun <T> getAll(classOrType: Class<out T>): List<T> = emptyList()
    }, pinpointName = null, limelightName = null, imuName = null) {
        var safetyFailure: Throwable? = null
        override fun updateHardwareInputs() = Unit
        override fun updateSubsystems(dtSeconds: Double, batteryVoltage: Double, powerScale: Double) = Unit
        override fun publishRobotTelemetry(timestamp: Long) = Unit
        override fun safeHardware() { safetyFailure?.let { throw it } }
    }
}
