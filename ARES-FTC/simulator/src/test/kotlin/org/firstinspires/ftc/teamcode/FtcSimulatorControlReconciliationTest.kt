package org.firstinspires.ftc.teamcode

import com.areslib.action.RobotAction
import com.areslib.ftc.FtcMecanumRobot
import com.areslib.ftc.FtcTeleopDriveFrame
import com.areslib.sim.model.MecanumRobotDouble
import com.areslib.state.Alliance
import com.areslib.telemetry.AresGamepad
import com.areslib.telemetry.GamepadState
import com.areslib.util.RobotClock
import kotlin.math.abs
import org.firstinspires.ftc.teamcode.opmodes.robot.AresDriveController
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

class FtcSimulatorControlReconciliationTest {
    private var robot: FtcMecanumRobot? = null

    @After fun cleanUp() {
        runCatching { robot?.close() }
        RobotClock.useSystemTime()
    }

    @Test
    fun `normal teleop applies field and robot frames differently at ninety degree heading`() {
        RobotClock.useMockTime(1_000L)
        val base = MecanumRobotDouble().let { double ->
            FtcMecanumRobot(
                hardwareMap = double.hardwareMap,
                pinpointName = "pinpoint",
                limelightName = "limelight",
                imuName = "imu",
                pinpointIsCcwPositive = true,
            ).also { robot = it }
        }
        base.store.dispatch(RobotAction.SetAlliance(Alliance.RED))
        base.store.dispatch(RobotAction.PoseUpdate(
            xMeters = 0.0,
            yMeters = 0.0,
            headingRadians = Math.PI / 2.0,
            timestampMs = RobotClock.currentTimeMillis(),
            isReset = true,
        ))

        val fieldGamepad = AresGamepad().apply { update(GamepadState(leftStickY = -1.0f)) }
        base.teleopDriveFrame = FtcTeleopDriveFrame.FIELD_RELATIVE
        AresDriveController(base).driveWithGamepad(fieldGamepad, useHeadingLock = false)
        assertTrue(abs(base.store.state.drive.xVelocityMetersPerSecond) < 1e-8)
        assertTrue(base.store.state.drive.yVelocityMetersPerSecond < -0.1)

        val robotGamepad = AresGamepad().apply { update(GamepadState(leftStickY = -1.0f)) }
        base.teleopDriveFrame = FtcTeleopDriveFrame.ROBOT_RELATIVE
        AresDriveController(base).driveWithGamepad(robotGamepad, useHeadingLock = false)
        assertTrue(base.store.state.drive.xVelocityMetersPerSecond > 0.1)
        assertTrue(abs(base.store.state.drive.yVelocityMetersPerSecond) < 1e-8)
    }
}
