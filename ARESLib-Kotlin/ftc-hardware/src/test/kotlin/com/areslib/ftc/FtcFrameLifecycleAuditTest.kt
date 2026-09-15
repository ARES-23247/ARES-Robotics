package com.areslib.ftc

import com.areslib.ftc.core.FtcHardwareInitializer
import com.areslib.ftc.core.FtcOpModeLifecycleController
import com.areslib.util.RobotClock
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.IMU
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class FtcFrameLifecycleAuditTest {
    @AfterTest fun restoreClock() { RobotClock.useSystemTime() }

    @Test fun `rewound desktop time cannot request a sleep longer than one frame`() {
        RobotClock.useMockTime(0)
        completesPromptly { FtcOpModeLifecycleController().sleepRemaining(100_000, false) }
    }

    @Test fun `overflowed desktop elapsed time cannot park the worker indefinitely`() {
        RobotClock.useMockTime(Long.MAX_VALUE)
        completesPromptly { FtcOpModeLifecycleController().sleepRemaining(-1000, false) }
    }

    @Test fun `Android and externally paced frames do not acquire another sleep owner`() {
        RobotClock.useMockTime(0)
        completesPromptly {
            val lifecycle = FtcOpModeLifecycleController()
            lifecycle.sleepRemaining(100_000, true)
            val prior = FtcOpModeLifecycleController.beginExternallyPacedFrame()
            try { lifecycle.sleepRemaining(100_000, false) }
            finally { FtcOpModeLifecycleController.endExternallyPacedFrame(prior) }
            assertFalse(FtcOpModeLifecycleController.isCurrentFrameExternallyPaced())
        }
    }

    @Test fun `interruption of a valid desktop frame remains visible to its owner`() {
        RobotClock.useMockTime(1000)
        completesPromptly {
            Thread.currentThread().interrupt()
            FtcOpModeLifecycleController().sleepRemaining(1000, false)
            assertTrue(Thread.currentThread().isInterrupted)
        }
    }

    @Test fun `clock rewind inhibits subsystem updates and latches the original fault`() = withRobot { robot ->
        robot.update()
        RobotClock.useMockTime(900)
        val failure = assertFailsWith<IllegalStateException> { robot.update() }
        assertSame(failure, robot.fatalUpdateFailure)
        assertEquals(listOf(0.02), robot.steps)
        assertTrue(robot.safeCalls > 0)
        RobotClock.useMockTime(1100)
        assertSame(failure, assertFailsWith<IllegalStateException> { robot.update() })
        assertEquals(1, robot.steps.size)
    }

    @Test fun `overflowed control delta rejects before invoking subsystem logic`() = withRobot { robot ->
        robot.update()
        robot.seedPreviousTimestamp(-1000)
        RobotClock.useMockTime(Long.MAX_VALUE)
        assertFailsWith<IllegalStateException> { robot.update() }
        assertEquals(listOf(0.02), robot.steps)
        assertTrue(robot.safeCalls > 0)
    }

    @Test fun `zero is a valid first timestamp and does not erase the next elapsed interval`() = withRobot { robot ->
        RobotClock.useMockTime(0)
        robot.update()
        RobotClock.useMockTime(25)
        robot.update()
        robot.update() // Preserve the documented legacy repeated-millisecond fallback.
        assertEquals(listOf(0.02, 0.025, 0.02), robot.steps)
    }

    @Test fun `closed robots reject later control updates without invoking hardware hooks`() = withRobot { robot ->
        robot.close()
        assertFailsWith<IllegalStateException> { robot.update() }
        assertTrue(robot.steps.isEmpty())
        assertEquals(0, robot.reads)
    }

    @Test fun `closed robots reject sensor acquisition and pose reset`() = withRobot { robot ->
        robot.close()
        assertFailsWith<IllegalStateException> { robot.readSensors() }
        assertFailsWith<IllegalStateException> { robot.resetPose() }
        assertEquals(0, robot.reads)
    }

    @Test fun `closing a robot twice performs teardown once`() = withRobot { robot ->
        robot.close()
        robot.close()
        assertEquals(1, robot.safeCalls)
    }

    @Test fun `robot shutdown closes its registered resources exactly once`() = withRobot { robot ->
        var closures = 0
        robot.hardwareRegistry.registerCloseable(AutoCloseable { closures++ })
        robot.close()
        robot.close()
        assertEquals(1, closures)
    }

    @Test fun `failed neutralization still closes the lifecycle and inhibits later updates`() = withRobot { robot ->
        val failure = IllegalStateException("neutralization failed")
        robot.safetyFailure = failure
        assertSame(failure, assertFails { robot.close() })
        assertFalse(com.areslib.telemetry.RobotStatusTracker.isEnabled)
        assertNotSame(robot, FtcBaseRobot.activeInstance)
        assertFailsWith<IllegalStateException> { robot.update() }
        robot.close()
        assertEquals(1, robot.safeCalls)
        assertTrue(robot.steps.isEmpty())
    }

    @Test fun `shared control and neutralization throwable preserves the primary failure`() = withRobot { robot ->
        val failure = IllegalStateException("shared control and neutralization failure")
        robot.controlFailure = failure
        robot.safetyFailure = failure
        try {
            assertSame(failure, assertFails { robot.update() })
            assertSame(failure, robot.fatalUpdateFailure)
        } finally { robot.safetyFailure = null }
    }

    @Test fun `unused sensor initializer teardown never invokes hardware factories`() {
        val map = EmptyHardwareMap()
        val initializer = FtcHardwareInitializer(map)
        initializer.close()
        assertEquals(0, map.lookups)
        assertNull(initializer.pinpointIO)
        assertNull(initializer.imuIO)
        assertNull(initializer.limelightIO)
        assertEquals(0, map.lookups)
    }

    @Test fun `missing sensors are looked up once and shutdown does not retry initialization`() {
        val map = EmptyHardwareMap()
        val initializer = FtcHardwareInitializer(map)
        try {
            repeat(2) {
                assertNull(initializer.pinpointIO)
                assertNull(initializer.imuIO)
                assertNull(initializer.limelightIO)
            }
            assertEquals(3, map.lookups)
        } finally { initializer.close() }
        initializer.close()
        assertEquals(3, map.lookups)
    }

    @Test fun `shutdown and an already started lazy initialization finish without reopening sensors`() {
        val enteredFactory = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val imu = object : IMU {
            override fun initialize(parameters: IMU.Parameters) = true
            override fun resetYaw() = Unit
            override fun getRobotYawPitchRollAngles() = YawPitchRollAngles(AngleUnit.RADIANS, 0.5)
            override fun getRobotAngularVelocity(unit: AngleUnit) = AngularVelocity(AngleUnit.RADIANS)
        }
        var lookups = 0
        val initializer = FtcHardwareInitializer(object : HardwareMap() {
            override fun <T> get(classOrType: Class<out T>, deviceName: String): T {
                lookups++
                enteredFactory.countDown()
                check(releaseFactory.await(5, TimeUnit.SECONDS))
                return classOrType.cast(imu)
            }
            override fun <T> getAll(classOrType: Class<out T>): List<T> = emptyList()
        }, pinpointName = null, limelightName = null)
        val getter = Thread({
            try { initializer.imuIO } catch (error: Throwable) { failure.set(error) }
        }, "audit-owned-sensor-init")
        val closer = Thread({
            closeStarted.countDown()
            try { initializer.close() } catch (error: Throwable) { failure.set(error) }
        }, "audit-owned-sensor-close")
        try {
            getter.start()
            assertTrue(enteredFactory.await(2, TimeUnit.SECONDS))
            closer.start()
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
            releaseFactory.countDown()
            getter.join(3000)
            closer.join(3000)
            assertFalse(getter.isAlive)
            assertFalse(closer.isAlive)
            failure.get()?.let { throw it }
            assertNull(initializer.imuIO)
            assertEquals(1, lookups)
        } finally {
            releaseFactory.countDown()
            getter.interrupt()
            closer.interrupt()
            getter.join(3000)
            closer.join(3000)
            initializer.close()
            check(!getter.isAlive && !closer.isAlive)
        }
    }

    @Test fun `closing an initialized IMU joins its owned polling worker`() {
        val caller = Thread.currentThread()
        val polled = CountDownLatch(1)
        val worker = AtomicReference<Thread?>()
        val imu = object : IMU {
            override fun initialize(parameters: IMU.Parameters) = true
            override fun resetYaw() = Unit
            override fun getRobotYawPitchRollAngles(): YawPitchRollAngles {
                if (Thread.currentThread() !== caller) {
                    worker.set(Thread.currentThread())
                    polled.countDown()
                }
                return YawPitchRollAngles(AngleUnit.RADIANS, 0.5)
            }
            override fun getRobotAngularVelocity(unit: AngleUnit) = AngularVelocity(AngleUnit.RADIANS)
        }
        val initializer = FtcHardwareInitializer(object : HardwareMap() {
            override fun <T> get(classOrType: Class<out T>, deviceName: String): T = classOrType.cast(imu)
            override fun <T> getAll(classOrType: Class<out T>): List<T> = emptyList()
        }, pinpointName = null, limelightName = null)
        try {
            val io = assertNotNull(initializer.imuIO)
            assertSame(io, initializer.imuIO)
            assertTrue(polled.await(2, TimeUnit.SECONDS))
        } finally { initializer.close() }
        assertFalse(assertNotNull(worker.get()).isAlive, "Close must join this initializer's worker")
        assertNull(initializer.imuIO)
    }

    private fun withRobot(action: (ProbeRobot) -> Unit) {
        RobotClock.useMockTime(1000)
        val pacing = FtcOpModeLifecycleController.beginExternallyPacedFrame()
        try {
            val robot = ProbeRobot()
            try { action(robot) }
            finally {
                robot.safetyFailure = null
                robot.close()
            }
        } finally { FtcOpModeLifecycleController.endExternallyPacedFrame(pacing) }
    }

    private class EmptyHardwareMap : HardwareMap() {
        var lookups = 0
        override fun <T> get(classOrType: Class<out T>, deviceName: String): T {
            lookups++
            error("No device $deviceName")
        }
        override fun <T> getAll(classOrType: Class<out T>): List<T> = emptyList()
    }

    private class ProbeRobot : FtcBaseRobot(EmptyHardwareMap(), pinpointName = null, limelightName = null, imuName = null) {
        val steps = mutableListOf<Double>()
        var reads = 0
        var safeCalls = 0
        var controlFailure: Throwable? = null
        var safetyFailure: Throwable? = null
        fun seedPreviousTimestamp(value: Long) { lastUpdateTime = value }
        override fun updateHardwareInputs() { reads++ }
        override fun updateSubsystems(dtSeconds: Double, batteryVoltage: Double, powerScale: Double) {
            controlFailure?.let { throw it }
            steps += dtSeconds
        }
        override fun publishRobotTelemetry(timestamp: Long) = Unit
        override fun safeHardware() {
            safeCalls++
            safetyFailure?.let { throw it }
        }
    }

    /** This distinguishes a bounded frame sleep from a multi-second/year park, not 20 ms jitter. */
    private fun completesPromptly(action: () -> Unit) {
        val error = AtomicReference<Throwable?>()
        val thread = Thread({ try { action() } catch (failure: Throwable) { error.set(failure) } }, "audit-owned-frame-pacing")
        thread.start()
        try {
            thread.join(1000)
            assertFalse(thread.isAlive, "Frame pacing must not park beyond a frame after clock discontinuity")
            error.get()?.let { throw it }
        } finally {
            thread.interrupt()
            thread.join(2000)
            check(!thread.isAlive) { "Owned pacing fixture did not terminate" }
        }
    }
}
