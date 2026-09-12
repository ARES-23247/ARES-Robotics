package org.firstinspires.ftc.teamcode

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.ftc.FtcMecanumRobot
import com.areslib.ftc.FtcTeleopDriveFrame
import com.areslib.ftc.input.FtcInputFrameAdapter
import com.areslib.input.InputFrame
import com.areslib.state.Alliance
import com.areslib.state.DriveState
import com.areslib.state.RobotState
import com.areslib.subsystem.MecanumDriveFacade
import com.areslib.telemetry.AresGamepad
import com.areslib.util.RobotClock
import com.qualcomm.robotcore.hardware.Gamepad
import org.firstinspires.ftc.teamcode.dsl.FtcGeneratedProjectRuntime
import org.firstinspires.ftc.teamcode.opmodes.AresRobot
import org.firstinspires.ftc.teamcode.opmodes.robot.AresDriveController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import kotlin.math.cos
import kotlin.math.sin

/** Exercise generated bindings and the actual Redux drive transform, without motor hardware. */
class LightbotFieldDriveTest {
    @After fun restoreClock() = RobotClock.useSystemTime()

    private class Fixture(alliance: Alliance) {
        val store = Store(RobotState(drive = DriveState(alliance = alliance)))
        val base = Mockito.mock(FtcMecanumRobot::class.java)
        val facade = MecanumDriveFacade(store).apply {
            maxSpeedMps = 1.0
            maxAngularSpeedRps = 1.0
        }
        init {
            Mockito.`when`(base.store).thenReturn(store)
            Mockito.`when`(base.mecanumDrive).thenReturn(facade)
            Mockito.`when`(base.teleopDriveFrame).thenReturn(FtcTeleopDriveFrame.FIELD_RELATIVE)
        }

        fun heading(value: Double) = store.dispatch(RobotAction.PoseUpdate(
            xMeters = 0.0, yMeters = 0.0, headingRadians = value,
            timestampMs = RobotClock.currentTimeMillis(), isReset = true,
        ))

        fun assertFieldVelocity(x: Double, y: Double, omega: Double = 0.0) {
            val state = store.state.drive
            val heading = state.poseEstimator.estimatedPoseHeading
            val vx = state.xVelocityMetersPerSecond
            val vy = state.yVelocityMetersPerSecond
            assertEquals("Field X must stay fixed as heading changes", x, vx * cos(heading) - vy * sin(heading), 1e-8)
            assertEquals("Field Y must stay fixed as heading changes", y, vx * sin(heading) + vy * cos(heading), 1e-8)
            assertEquals(omega, state.angularVelocityRadiansPerSecond, 1e-8)
        }
    }

    @Test fun `generated FTC stick controls retain alliance wall perspective through a full turn`() {
        RobotClock.useMockTime(1000L)
        for (alliance in Alliance.entries) {
            val fixture = Fixture(alliance)
            val robot = Mockito.mock(AresRobot::class.java)
            Mockito.`when`(robot.base).thenReturn(fixture.base)
            val runtime = FtcGeneratedProjectRuntime(robot).apply { headingLockEnabled = false }
            val gamepad = Mockito.mock(Gamepad::class.java)
            val adapter = FtcInputFrameAdapter(gamepad)
            val driver = InputFrame()
            val operator = InputFrame()
            fun sample() {
                RobotClock.useMockTime(RobotClock.currentTimeMillis() + 20L)
                adapter.sampleInto(driver)
                runtime.updateControls(driver, operator, RobotClock.nanoTime())
            }
            sample() // Connected neutral frame arms the generated bindings.
            val direction = if (alliance == Alliance.RED) 1.0 else -1.0
            for (heading in listOf(0.0, Math.PI / 2, Math.PI, -Math.PI / 2)) {
                fixture.heading(heading)
                gamepad.left_stick_y = -1f
                gamepad.left_stick_x = 0f
                sample()
                fixture.assertFieldVelocity(0.0, direction)
                gamepad.left_stick_y = 0f
                gamepad.left_stick_x = 1f
                sample()
                fixture.assertFieldVelocity(direction, 0.0)
            }
            gamepad.left_stick_x = 0f
            gamepad.right_stick_x = -1f
            sample()
            fixture.assertFieldVelocity(0.0, 0.0, 1.0)
            gamepad.right_stick_x = 0f
            sample()
            fixture.assertFieldVelocity(0.0, 0.0)
            runtime.cancelAll("Test complete")
        }
    }

    @Test fun `manual fallback uses the same field perspective at every heading`() {
        RobotClock.useMockTime(1000L)
        for (alliance in Alliance.entries) {
            val fixture = Fixture(alliance)
            val direction = if (alliance == Alliance.RED) 0.4 else -0.4
            for (heading in listOf(0.0, Math.PI / 2, Math.PI, -Math.PI / 2)) {
                fixture.heading(heading)
                val gamepad = Mockito.mock(AresGamepad::class.java, Mockito.RETURNS_DEEP_STUBS)
                Mockito.`when`(gamepad.leftStickY.value).thenReturn(-1f)
                AresDriveController(fixture.base).driveWithGamepad(gamepad, useHeadingLock = false)
                fixture.assertFieldVelocity(0.0, direction)
                Mockito.`when`(gamepad.leftStickY.value).thenReturn(0f)
                Mockito.`when`(gamepad.leftStickX.value).thenReturn(1f)
                AresDriveController(fixture.base).driveWithGamepad(gamepad, useHeadingLock = false)
                fixture.assertFieldVelocity(direction, 0.0)
            }
        }
    }
}
