package com.areslib.xrp

import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.xrp.hardware.StandardXrpDifferentialHardwareIO
import com.areslib.xrp.hardware.StandardXrpMecanumHardwareIO
import com.areslib.xrp.robot.XrpBaseRobot
import com.areslib.xrp.robot.XrpRobotMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XrpHardwareTest {

    @Test
    fun testDifferentialHardwareIO() {
        val io = StandardXrpDifferentialHardwareIO()
        io.drive(ChassisSpeeds(0.5, 0.0, 0.0), maxLinearSpeedMps = 1.0)
        assertEquals(0.5, io.leftMotor.effort, 1e-4)
        assertEquals(0.5, io.rightMotor.effort, 1e-4)

        io.update()
        assertTrue(io.leftMotor.positionRadians > 0.0)
        assertTrue(io.rightMotor.positionRadians > 0.0)

        io.stop()
        assertEquals(0.0, io.leftMotor.effort)
        assertEquals(0.0, io.rightMotor.effort)
    }

    @Test
    fun testMecanumHardwareIO() {
        val io = StandardXrpMecanumHardwareIO()
        // Strafe left at 0.5 m/s
        io.drive(ChassisSpeeds(0.0, 0.5, 0.0), maxLinearSpeedMps = 1.0)
        assertEquals(-0.5, io.frontLeftMotor.effort, 1e-4)
        assertEquals(0.5, io.frontRightMotor.effort, 1e-4)
        assertEquals(0.5, io.backLeftMotor.effort, 1e-4)
        assertEquals(-0.5, io.backRightMotor.effort, 1e-4)

        io.stop()
        assertEquals(0.0, io.frontLeftMotor.effort)
    }

    @Test
    fun testXrpBaseRobotLifecycle() {
        val robot = XrpBaseRobot()
        assertEquals(XrpRobotMode.INIT, robot.mode)

        robot.onStartTeleop()
        assertEquals(XrpRobotMode.TELEOP, robot.mode)

        robot.resetPose(0.35, 0.7112, 0.0)
        assertEquals(0.35, robot.currentPose.x, 1e-4)
        assertEquals(0.7112, robot.currentPose.y, 1e-4)

        robot.onStop()
        assertEquals(XrpRobotMode.DISABLED, robot.mode)
    }
}
