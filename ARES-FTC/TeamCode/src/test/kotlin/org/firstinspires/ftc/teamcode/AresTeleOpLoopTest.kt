package org.firstinspires.ftc.teamcode

import com.areslib.util.RobotClock
import com.areslib.util.PoseStorage
import com.areslib.math.estimation.PoseEstimator
import com.areslib.telemetry.RobotStatusTracker
import org.firstinspires.ftc.teamcode.dsl.AresTeleOpBase
import org.firstinspires.ftc.teamcode.dsl.FtcGeneratedProjectRuntime
import org.firstinspires.ftc.teamcode.opmodes.AresRobot
import org.firstinspires.ftc.teamcode.opmodes.ARESMecanumTeleOp
import org.firstinspires.ftc.teamcode.opmodes.ARESTuningTeleOp
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class AresTeleOpLoopTest {
    private val oldTags = PoseEstimator.activeTags
    private val oldMode = RobotStatusTracker.activeOpMode
    private val oldValid = PoseStorage.hasValidPose

    @Before fun setupClock() { RobotClock.useMockTime(1000L); PoseStorage.hasValidPose = false }
    @After fun restore() {
        RobotClock.useSystemTime()
        PoseEstimator.activeTags = oldTags
        RobotStatusTracker.activeOpMode = oldMode
        PoseStorage.hasValidPose = oldValid
    }

    private class Fixture(val mode: AresTeleOpBase, val robot: AresRobot, val runtime: FtcGeneratedProjectRuntime)

    private fun fixture(tuning: Boolean): Fixture {
        val mode: AresTeleOpBase = if (tuning) spy(ARESTuningTeleOp()) else spy(ARESMecanumTeleOp())
        val robot = mock(AresRobot::class.java, RETURNS_DEEP_STUBS)
        val runtime = mock(FtcGeneratedProjectRuntime::class.java)
        doReturn(robot).`when`(mode).buildRobot()
        mode.gamepad1 = mock(com.qualcomm.robotcore.hardware.Gamepad::class.java)
        mode.gamepad2 = mock(com.qualcomm.robotcore.hardware.Gamepad::class.java)
        mode.telemetry = mock(org.firstinspires.ftc.robotcore.external.Telemetry::class.java)
        // Replace only the runtime normally constructed alongside hardware. Exercise the real
        // season input adapters, frame reuse, generated-drive gate and lifecycle callbacks.
        AresTeleOpBase::class.java.getDeclaredField("generatedRuntime").apply {
            isAccessible = true
            set(mode, runtime)
        }
        mode.init()
        mode.start()
        clearInvocations(robot, runtime)
        return Fixture(mode, robot, runtime)
    }

    private fun calls(target: Any, name: String) = mockingDetails(target).invocations.filter { it.method.name == name }

    @Test fun `tuning suppresses manual drive while armed and never emits generated drive`() {
        val f = fixture(true)
        `when`(f.runtime.hasGeneratedDriveBindings).thenReturn(true)
        for ((index, armed) in listOf(false, true, false).withIndex()) {
            `when`(f.robot.base.isCalibrationModeArmed).thenReturn(armed)
            RobotClock.useMockTime(1000L + index * 20)
            f.mode.loop()
        }
        assertEquals(2, calls(f.robot, "driveWithGamepad").size)
        assertTrue(calls(f.robot, "driveWithGamepad").all { it.arguments[1] == true })
        val frames = calls(f.runtime, "updateControls")
        assertEquals(3, frames.size)
        assertTrue(frames.all { it.arguments[3] == false })
        assertEquals(listOf(1000000000L, 1020000000L, 1040000000L), frames.map { it.arguments[2] })
        assertSame(frames[0].arguments[0], frames[2].arguments[0])
        assertSame(frames[0].arguments[1], frames[2].arguments[1])
        assertNotSame(frames[0].arguments[0], frames[0].arguments[1])
        val updates = calls(f.robot, "update")
        assertEquals(3, updates.size)
        frames.zip(updates).forEach { (frame, update) -> assertTrue(frame.sequenceNumber < update.sequenceNumber) }
    }

    @Test fun `competition manual fallback stops when generated drive bindings exist`() {
        val f = fixture(false)
        `when`(f.runtime.hasGeneratedDriveBindings).thenReturn(false)
        f.mode.loop()
        `when`(f.runtime.hasGeneratedDriveBindings).thenReturn(true)
        f.mode.loop()
        f.mode.loop()
        assertEquals(1, calls(f.robot, "driveWithGamepad").size)
        val frames = calls(f.runtime, "updateControls")
        assertEquals(3, frames.size)
        assertTrue(frames.all { it.arguments[3] == true })
        assertEquals(3, calls(f.robot, "update").size)
    }

    @Test fun `heading toggle is edge triggered and reaches generated and fallback paths`() {
        val f = fixture(false)
        `when`(f.runtime.hasGeneratedDriveBindings).thenReturn(false)
        for (pressed in listOf(true, true, false, true)) {
            f.mode.gamepad1.left_stick_button = pressed
            f.mode.loop()
        }
        assertEquals(listOf(false, false, false, true), calls(f.robot, "driveWithGamepad").map { it.arguments[1] })
        assertEquals(listOf(false, true), calls(f.runtime, "setHeadingLockEnabled").map { it.arguments[0] })
    }

    @Test fun `alliance and pose buttons fire once per press with alliance before reset`() {
        for (tuning in listOf(false, true)) {
            val f = fixture(tuning)
            f.mode.gamepad1.x = true
            f.mode.loop()
            f.mode.loop()
            val toggle = calls(f.robot, "toggleAlliance").single()
            val reset = calls(f.robot, "resetPoseForAlliance").single()
            assertTrue(toggle.sequenceNumber < reset.sequenceNumber)
            f.mode.gamepad1.x = false
            f.mode.gamepad1.y = true
            f.mode.loop()
            f.mode.loop()
            assertEquals(1, calls(f.robot, "toggleAlliance").size)
            assertEquals(2, calls(f.robot, "resetPoseForAlliance").size)
        }
    }

    @Test fun `season cleanup cancels tasks and drops cached references even when robot close throws`() {
        val f = fixture(false)
        f.mode.loop()
        f.mode.cancelProjectControls(f.robot)
        verify(f.runtime).cancelAll("FTC TeleOp stopped")
        val failure = IllegalStateException("close failed")
        doThrow(failure).`when`(f.robot).close()
        assertSame(failure, runCatching { f.mode.closeRobot(f.robot) }.exceptionOrNull())
        for (name in listOf("generatedRuntime", "driverAdapter", "operatorAdapter")) {
            val field = AresTeleOpBase::class.java.getDeclaredField(name).apply { isAccessible = true }
            assertNull(name, field.get(f.mode))
        }
    }
}
