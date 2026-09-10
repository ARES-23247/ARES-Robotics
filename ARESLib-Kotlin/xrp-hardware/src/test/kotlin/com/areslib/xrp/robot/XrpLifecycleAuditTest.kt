package com.areslib.xrp.robot

import com.areslib.xrp.hardware.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class XrpLifecycleAuditTest {
    private class Drive : StandardXrpDifferentialHardwareIO() {
        var stops = 0
        var updates = 0
        var stopFailure: Throwable? = null
        var updateFailure: Throwable? = null
        override fun stop() { stops++; stopFailure?.let { throw it }; super.stop() }
        override fun update() { updates++; updateFailure?.let { throw it }; super.update() }
    }

    @Test fun `inactive ticks stop before advancing simulated feedback`() {
        for (disabled in listOf(false, true)) {
            val drive = Drive()
            val robot = XrpBaseRobot(drive)
            if (disabled) robot.onStop()
            drive.setPowers(0.5, 0.5)
            robot.periodic()
            assertEquals(0.0, drive.leftMotor.effort)
            assertEquals(0.0, drive.rightMotor.effort)
            assertEquals(0.0, drive.leftMotor.positionRadians)
            assertEquals(0.0, drive.rightMotor.positionRadians)
        }
    }

    @Test fun `sensor refresh failure disables and neutralizes before propagating`() {
        val drive = Drive()
        val failure = IllegalStateException("ultrasonic offline")
        val sensor = object : XrpUltrasonicIO {
            override val distanceMeters = Double.NaN
            override fun update() { throw failure }
        }
        val robot = XrpBaseRobot(drive, sensor)
        robot.onStartTeleop()
        drive.setPowers(0.5, 0.5)
        assertSame(failure, assertFailsWith<IllegalStateException> { robot.periodic() })
        assertEquals(XrpRobotMode.DISABLED, robot.mode)
        assertEquals(0.0, drive.leftMotor.effort)
        assertEquals(0.0, drive.rightMotor.effort)
    }

    @Test fun `cleanup failure does not replace the sensor failure or leave mode active`() {
        val drive = Drive()
        val failure = IllegalStateException("line sensor failed")
        val cleanup = IllegalStateException("stop failed")
        val sensor = object : XrpLineSensorIO {
            override val leftReflectance = 0.0
            override val rightReflectance = 0.0
            override fun update() { throw failure }
        }
        val robot = XrpBaseRobot(drive, lineSensor = sensor)
        robot.onStartAuto()
        drive.stopFailure = cleanup
        assertSame(failure, assertFailsWith<IllegalStateException> { robot.periodic() })
        assertEquals(XrpRobotMode.DISABLED, robot.mode)
        assertSame(cleanup, failure.suppressed.single())
    }

    @Test fun `active mode transitions require a successful neutral boundary`() {
        for (auto in listOf(false, true)) {
            val drive = Drive()
            val robot = XrpBaseRobot(drive)
            drive.setPowers(0.7, 0.7)
            if (auto) robot.onStartAuto() else robot.onStartTeleop()
            assertEquals(0.0, drive.leftMotor.effort)
            assertEquals(if (auto) XrpRobotMode.AUTO else XrpRobotMode.TELEOP, robot.mode)
            val failure = IllegalStateException("stop failed")
            drive.stopFailure = failure
            assertSame(failure, assertFailsWith<IllegalStateException> {
                if (auto) robot.onStartAuto() else robot.onStartTeleop()
            })
            assertEquals(XrpRobotMode.DISABLED, robot.mode)
        }
    }

    @Test fun `invalid tick duration rejects before refresh and disables active output`() {
        for (dt in listOf(0.0, -0.02, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val drive = Drive()
            val robot = XrpBaseRobot(drive)
            robot.onStartTeleop()
            drive.setPowers(0.6, 0.6)
            assertFailsWith<IllegalArgumentException> { robot.periodic(dt) }
            assertEquals(XrpRobotMode.DISABLED, robot.mode)
            assertEquals(0, drive.updates)
            assertEquals(0.0, drive.leftMotor.effort)
        }
    }

    @Test fun `invalid pose reset preserves the prior snapshot`() {
        val robot = XrpBaseRobot()
        robot.resetPose(1.0, -2.0, 0.4)
        val previous = robot.currentPose
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { robot.resetPose(invalid, 0.0, 0.0) }
            assertSame(previous, robot.currentPose)
            assertFailsWith<IllegalArgumentException> { robot.resetPose(0.0, invalid, 0.0) }
            assertSame(previous, robot.currentPose)
            assertFailsWith<IllegalArgumentException> { robot.resetPose(0.0, 0.0, invalid) }
            assertSame(previous, robot.currentPose)
        }
    }

    @Test fun `unmeasured battery voltage is unknown`() {
        assertTrue(XrpBaseRobot().batteryVoltage.isNaN())
    }

    @Test fun `successful active tick refreshes each device once with no hidden getters`() {
        val drive = Drive()
        var ultrasonicUpdates = 0
        var lineUpdates = 0
        val ultrasonic = object : XrpUltrasonicIO {
            override val distanceMeters: Double get() = error("unexpected getter")
            override fun update() { ultrasonicUpdates++ }
        }
        val line = object : XrpLineSensorIO {
            override val leftReflectance: Double get() = error("unexpected getter")
            override val rightReflectance: Double get() = error("unexpected getter")
            override fun update() { lineUpdates++ }
        }
        val robot = XrpBaseRobot(drive, ultrasonic, line)
        robot.onInit()
        robot.onStartAuto()
        val stops = drive.stops
        drive.setPowers(0.5, 0.5)
        robot.periodic(0.1)
        assertEquals(1, drive.updates)
        assertEquals(1, ultrasonicUpdates)
        assertEquals(1, lineUpdates)
        assertEquals(stops, drive.stops)
        // IO update has no dt argument: the motor fixture remains a fixed 20 ms step.
        assertEquals(0.3, drive.leftMotor.positionRadians, 1e-15)
        assertEquals(0.5, drive.leftMotor.effort)
    }

    @Test fun `drive failure aborts remaining refresh and preserves a reused cleanup exception`() {
        val failure = IllegalStateException("drive unavailable")
        val drive = Drive()
        var reads = 0
        val sensor = object : XrpUltrasonicIO {
            override val distanceMeters = 1.0
            override fun update() { reads++ }
        }
        val robot = XrpBaseRobot(drive, sensor)
        robot.onStartTeleop()
        drive.updateFailure = failure
        drive.stopFailure = failure
        val stops = drive.stops
        assertSame(failure, assertFailsWith<IllegalStateException> { robot.periodic() })
        assertTrue(failure.suppressed.isEmpty())
        assertEquals(0, reads)
        assertEquals(stops + 1, drive.stops)
        assertEquals(XrpRobotMode.DISABLED, robot.mode)
    }

    @Test fun `failed init or stop leaves the lifecycle disabled`() {
        for (initializing in listOf(false, true)) {
            val drive = Drive()
            val robot = XrpBaseRobot(drive)
            robot.onStartTeleop()
            val failure = IllegalStateException("neutral failed")
            drive.stopFailure = failure
            assertSame(failure, assertFailsWith<IllegalStateException> {
                if (initializing) robot.onInit() else robot.onStop()
            })
            assertEquals(XrpRobotMode.DISABLED, robot.mode)
        }
    }

    @Test fun `successful later ticks do not automatically restore an active mode`() {
        val drive = Drive()
        val robot = XrpBaseRobot(drive)
        robot.onStartTeleop()
        drive.updateFailure = IllegalStateException("temporary read failure")
        assertFailsWith<IllegalStateException> { robot.periodic() }
        drive.updateFailure = null
        drive.setPowers(0.5, 0.5)
        robot.periodic()
        assertEquals(XrpRobotMode.DISABLED, robot.mode)
        assertEquals(0.0, drive.leftMotor.effort)
        robot.onStartTeleop()
        assertEquals(XrpRobotMode.TELEOP, robot.mode)
        assertEquals(0.0, drive.leftMotor.effort)
    }
}
