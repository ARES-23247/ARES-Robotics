package com.areslib.ftc

import com.areslib.ftc.core.FtcOpModeLifecycleController
import com.areslib.hardware.SubsystemIO
import com.areslib.hardware.actuator.FlywheelIO
import com.areslib.networktables.NT4Instance
import com.areslib.util.RobotClock
import com.qualcomm.robotcore.hardware.HardwareMap
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class FtcSafetyFailureAuditTest {
    @AfterEach fun restoreOwnedGlobals() {
        Thread.interrupted()
        RobotClock.useSystemTime()
        NT4Instance.defaultInstance.closeServer()
    }

    private class ProbeRobot : FtcBaseRobot(object : HardwareMap() {
        override fun <T> get(classOrType: Class<out T>, deviceName: String): T = error("No device")
        override fun <T> getAll(classOrType: Class<out T>): List<T> = emptyList()
    }, pinpointName = null, limelightName = null, imuName = null) {
        val controlFailure = IllegalStateException("control failed")
        var safetyFailure: Throwable? = null
        var safeCalls = 0
        var controlCalls = 0
        override fun updateHardwareInputs() = Unit
        override fun updateSubsystems(dtSeconds: Double, batteryVoltage: Double, powerScale: Double) {
            controlCalls++
            throw controlFailure
        }
        override fun publishRobotTelemetry(timestamp: Long) = Unit
        override fun safeHardware() { safeCalls++; safetyFailure?.let { throw it } }
    }

    private fun withBase(block: (ProbeRobot) -> Unit) {
        RobotClock.useMockTime(1000L)
        val pacing = FtcOpModeLifecycleController.beginExternallyPacedFrame()
        try {
            val robot = ProbeRobot()
            try { block(robot) }
            finally { robot.safetyFailure = null; Thread.interrupted(); robot.close() }
        } finally { FtcOpModeLifecycleController.endExternallyPacedFrame(pacing) }
    }

    private fun withMecanum(block: (FtcMecanumRobot, Array<MockDcMotorEx>) -> Unit) {
        val motors = Array(4) { MockDcMotorEx() }
        val names = listOf("fl", "fr", "rl", "rr")
        val map = object : HardwareMap() {
            override fun <T> get(classOrType: Class<out T>, deviceName: String): T =
                classOrType.cast(motors[names.indexOf(deviceName)])
            override fun <T> getAll(classOrType: Class<out T>): List<T> = emptyList()
        }
        val robot = FtcMecanumRobot(map, imuName = null)
        try { block(robot, motors) }
        finally { robot.sysIdFlywheelIO = null; Thread.interrupted(); robot.close() }
    }

    @Test fun aSafetyFailureFirstObservedOnRetryIsRetainedOnce() = withBase { robot ->
        assertSame(robot.controlFailure, assertFails { robot.update() })
        val safety = AssertionError("neutral retry failed")
        robot.safetyFailure = safety
        repeat(3) { assertSame(robot.controlFailure, assertFails { robot.update() }) }
        assertEquals(1, robot.controlCalls)
        assertEquals(4, robot.safeCalls)
        assertEquals(listOf(safety), robot.controlFailure.suppressed.toList())
    }

    @Test fun repeatedDistinctRetryFailuresDoNotGrowTheLatchedExceptionGraph() = withBase { robot ->
        assertSame(robot.controlFailure, assertFails { robot.update() })
        val first = AssertionError("first safety failure")
        robot.safetyFailure = first
        assertSame(robot.controlFailure, assertFails { robot.update() })
        repeat(1000) {
            robot.safetyFailure = IllegalStateException("subsequent safety failure")
            assertSame(robot.controlFailure, assertFails { robot.update() })
        }
        assertEquals(1, robot.controlCalls)
        assertEquals(1002, robot.safeCalls)
        assertEquals(listOf(first), robot.controlFailure.suppressed.toList())
    }

    @Test fun aLaterUnretainedRetryInterruptStillReachesTheCaller() = withBase { robot ->
        robot.safetyFailure = AssertionError("first safety failure")
        assertSame(robot.controlFailure, assertFails { robot.update() })
        robot.safetyFailure = InterruptedException("retry interrupted")
        assertSame(robot.controlFailure, assertFails { robot.update() })
        assertTrue(Thread.currentThread().isInterrupted)
        assertEquals(1, robot.controlFailure.suppressed.size)
    }

    @Test fun safetyInterruptRemainsVisibleAfterAnUnrelatedControlFailure() = withBase { robot ->
        val safety = InterruptedException("neutral interrupted")
        robot.safetyFailure = safety
        assertSame(robot.controlFailure, assertFails { robot.update() })
        assertEquals(listOf(safety), robot.controlFailure.suppressed.toList())
        assertTrue(Thread.currentThread().isInterrupted)
    }

    @Test fun interruptedShutdownStillClosesResourcesAndPreservesInterrupt() = withBase { robot ->
        val safety = InterruptedException("shutdown interrupted")
        var closed = 0
        robot.hardwareRegistry.registerCloseable(AutoCloseable { closed++ })
        robot.safetyFailure = safety
        assertSame(safety, assertFails { robot.close() })
        assertEquals(1, closed)
        assertTrue(Thread.currentThread().isInterrupted)
    }

    @Test fun diagnosticSinkFailureCannotSkipNeutralOrReplaceTheControlFailure() = withBase { robot ->
        val saved = System.err
        val logging = AssertionError("stderr failed")
        var safesAtDiagnostic = -1
        val sink = object : PrintStream(ByteArrayOutputStream()) {
            override fun println(message: String?) {
                safesAtDiagnostic = robot.safeCalls
                throw logging
            }
        }
        val thrown = try { System.setErr(sink); assertFails { robot.update() } }
            finally { System.setErr(saved); sink.close() }
        assertSame(robot.controlFailure, thrown)
        assertEquals(1, safesAtDiagnostic)
        assertEquals(1, robot.safeCalls)
        assertEquals(listOf(logging), thrown.suppressed.toList())
    }

    @Test fun mecanumSafetyTraversesEachRegisteredDeviceOnceAndNeutralsDrive() = withMecanum { robot, motors ->
        var safetyCalls = 0
        robot.hardwareRegistry.registerDevice("probe", object : SubsystemIO {
            override fun safe() { safetyCalls++ }
        })
        robot.mecanumIO.setMotorPowers(0.3, -0.2, 0.4, -0.5)
        assertTrue(motors.any { it.power != 0.0 })
        robot.safeHardware()
        assertTrue(motors.all { it.power == 0.0 })
        assertEquals(1, safetyCalls)
        assertFalse(robot.isCalibrationModeEnabled)
    }

    @Test fun interruptedFlywheelStopStillNeutralsDriveAndRevokesCalibration() = withMecanum { robot, motors ->
        val failure = InterruptedException("flywheel stop interrupted")
        robot.enableCalibrationMode()
        robot.sysIdFlywheelIO = object : FlywheelIO {
            override fun setVelocityRpm(rpm: Double, maxEffortScale: Double) = Unit
            override fun setAppliedVoltage(volts: Double) { assertEquals(0.0, volts); throw failure }
        }
        robot.mecanumIO.setMotorPowers(0.3, -0.2, 0.4, -0.5)
        assertSame(failure, assertFails { robot.disableCalibrationMode() })
        assertTrue(motors.all { it.power == 0.0 })
        assertFalse(robot.isCalibrationModeEnabled)
        assertFalse(robot.isCalibrationModeArmed)
        assertTrue(Thread.currentThread().isInterrupted)
    }

    @Test fun registryFailureCannotSkipAnotherDeviceAfterCalibrationStopFails() = withMecanum { robot, motors ->
        val calibration = AssertionError("flywheel neutral failed")
        val registry = AssertionError("device neutral failed")
        var failDevice = true
        var attempted = 0
        robot.sysIdFlywheelIO = object : FlywheelIO {
            override fun setVelocityRpm(rpm: Double, maxEffortScale: Double) = Unit
            override fun setAppliedVoltage(volts: Double) { throw calibration }
        }
        robot.hardwareRegistry.registerDevice("failing", object : SubsystemIO {
            override fun safe() { if (failDevice) throw registry }
        })
        robot.hardwareRegistry.registerDevice("remaining", object : SubsystemIO {
            override fun safe() { attempted++ }
        })
        try {
            robot.mecanumIO.setMotorPowers(0.3, -0.2, 0.4, -0.5)
            assertSame(calibration, assertFails { robot.safeHardware() })
            assertEquals(listOf(registry), calibration.suppressed.toList())
            assertEquals(1, attempted)
            assertTrue(motors.all { it.power == 0.0 })
        } finally { failDevice = false }
    }

    @Test fun sharedCalibrationAndDeviceFailureRetainsItsIdentity() = withMecanum { robot, _ ->
        val failure = AssertionError("shared failure")
        var failDevice = true
        robot.sysIdFlywheelIO = object : FlywheelIO {
            override fun setVelocityRpm(rpm: Double, maxEffortScale: Double) = Unit
            override fun setAppliedVoltage(volts: Double) { throw failure }
        }
        robot.hardwareRegistry.registerDevice("failing", object : SubsystemIO {
            override fun safe() { if (failDevice) throw failure }
        })
        try {
            assertSame(failure, assertFails { robot.safeHardware() })
            assertTrue(failure.suppressed.isEmpty())
        } finally { failDevice = false }
    }
}
