package org.firstinspires.ftc.teamcode

import com.areslib.ftc.FtcMecanumRobot
import com.areslib.ftc.dsl.FtcTeleOpBase
import com.areslib.ftc.dsl.teleOp
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.state.Alliance
import com.areslib.action.RobotAction
import com.areslib.telemetry.GamepadState
import com.areslib.util.PoseStorage
import org.junit.After
import org.junit.Test
import org.mockito.Mockito.*

/** Exercises the real shared lifecycle used by the season TeleOp adapter. */
class AutoToTeleOpTransitionTest {
    @org.junit.Before fun fixedClock() = com.areslib.util.RobotClock.useMockTime(1000L)
    private val originalSnapshot = PoseStorage.snapshot
    private val originalTags = com.areslib.math.estimation.PoseEstimator.activeTags
    private val originalMode = com.areslib.telemetry.RobotStatusTracker.activeOpMode

    @After fun restoreGlobals() {
        com.areslib.util.RobotClock.useSystemTime()
        originalSnapshot?.let { PoseStorage.save(it.pose, it.alliance) } ?: PoseStorage.clear()
        com.areslib.math.estimation.PoseEstimator.activeTags = originalTags
        com.areslib.telemetry.RobotStatusTracker.activeOpMode = originalMode
    }

    private class Harness(val base: FtcMecanumRobot) : FtcTeleOpBase<FtcMecanumRobot>() {
        init {
            gamepad1 = mock(com.qualcomm.robotcore.hardware.Gamepad::class.java)
            gamepad2 = mock(com.qualcomm.robotcore.hardware.Gamepad::class.java)
            telemetry = mock(org.firstinspires.ftc.robotcore.external.Telemetry::class.java)
        }
        override fun define() = teleOp<FtcMecanumRobot> { everyLoop { } }
        override fun buildRobot() = base
        override fun getBaseRobot(robot: FtcMecanumRobot) = robot
        override fun updateRobot(robot: FtcMecanumRobot, g1: GamepadState, g2: GamepadState) = Unit
        override fun closeRobot(robot: FtcMecanumRobot) = Unit
    }

    @Test fun `valid auto pose and alliance are restored at start not init`() {
        val base = mock(FtcMecanumRobot::class.java, RETURNS_DEEP_STUBS)
        val pose = Pose2d(1.25, -0.85, Rotation2d(Math.PI / 4))
        PoseStorage.save(pose, Alliance.BLUE)
        val mode = Harness(base)
        mode.init()
        verifyNoInteractions(base)
        mode.start()
        verify(base.store).dispatch(RobotAction.SetAlliance(Alliance.BLUE))
        verify(base).resetPose(pose)
        verify(base, never()).resetPoseForAlliance()
    }

    @Test fun `invalid storage ignores stale blue alliance and pose`() {
        val base = mock(FtcMecanumRobot::class.java, RETURNS_DEEP_STUBS)
        val stalePose = Pose2d(7.0, -8.0, Rotation2d(2.0))
        PoseStorage.save(stalePose, Alliance.BLUE)
        PoseStorage.clear()
        val mode = Harness(base)
        mode.init()
        mode.start()
        val order = inOrder(base.store, base)
        order.verify(base.store).dispatch(RobotAction.SetAlliance(Alliance.RED))
        order.verify(base).resetPoseForAlliance()
        verify(base, never()).resetPose(stalePose)
    }

    @Test fun `start uses latest pose storage rather than an init snapshot`() {
        val base = mock(FtcMecanumRobot::class.java, RETURNS_DEEP_STUBS)
        PoseStorage.clear()
        val mode = Harness(base)
        mode.init()
        val pose = Pose2d(-1.0, 0.5, Rotation2d(-0.4))
        PoseStorage.save(pose, Alliance.RED)
        mode.start()
        verify(base.store).dispatch(RobotAction.SetAlliance(Alliance.RED))
        verify(base).resetPose(pose)
    }

    @Test fun `alliance observer cannot replace the pose being restored`() {
        val base = mock(FtcMecanumRobot::class.java, RETURNS_DEEP_STUBS)
        val store = base.store
        val pose = Pose2d(1.25, -0.85, Rotation2d(Math.PI / 4))
        PoseStorage.save(pose, Alliance.BLUE)
        doAnswer {
            PoseStorage.clear()
            null
        }.`when`(store).dispatch(RobotAction.SetAlliance(Alliance.BLUE))
        val mode = Harness(base)
        mode.init()
        mode.start()
        verify(base).resetPose(pose)
    }

    @Test fun `real tuning mode enables calibration only after start`() {
        val robot = mock(org.firstinspires.ftc.teamcode.opmodes.AresRobot::class.java, RETURNS_DEEP_STUBS)
        val mode = spy(org.firstinspires.ftc.teamcode.opmodes.ARESTuningTeleOp())
        doReturn(robot).`when`(mode).buildRobot()
        mode.gamepad1 = mock(com.qualcomm.robotcore.hardware.Gamepad::class.java)
        mode.gamepad2 = mock(com.qualcomm.robotcore.hardware.Gamepad::class.java)
        mode.telemetry = mock(org.firstinspires.ftc.robotcore.external.Telemetry::class.java)
        PoseStorage.clear()
        mode.init()
        mode.init_loop()
        verify(robot, never()).enableCalibrationMode()
        mode.start()
        verify(robot, times(1)).enableCalibrationMode()
        verify(robot.base.mecanumIO).slewRateLimit = 4.0
    }

    @Test fun `competition mode startup never enables calibration`() {
        val robot = mock(org.firstinspires.ftc.teamcode.opmodes.AresRobot::class.java, RETURNS_DEEP_STUBS)
        val mode = spy(org.firstinspires.ftc.teamcode.opmodes.ARESMecanumTeleOp())
        doReturn(robot).`when`(mode).buildRobot()
        mode.gamepad1 = mock(com.qualcomm.robotcore.hardware.Gamepad::class.java)
        mode.gamepad2 = mock(com.qualcomm.robotcore.hardware.Gamepad::class.java)
        mode.telemetry = mock(org.firstinspires.ftc.robotcore.external.Telemetry::class.java)
        PoseStorage.clear()
        mode.init()
        mode.init_loop()
        mode.start()
        verify(robot, never()).enableCalibrationMode()
        verify(robot.base.mecanumIO).slewRateLimit = 4.0
    }
}
