package org.firstinspires.ftc.teamcode

import com.areslib.ftc.FtcMecanumRobot
import com.areslib.ftc.FtcTeleopDriveFrame
import com.areslib.telemetry.AresGamepad
import com.areslib.state.Alliance
import com.areslib.state.DriveState
import com.areslib.state.RobotState
import com.areslib.Store
import org.firstinspires.ftc.teamcode.opmodes.robot.AresDriveController
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito
import kotlin.math.pow

class AresDriveControllerTest {
    @Test
    fun `gamepad maps axes once and applies alliance only in field frame`() {
        for (frame in FtcTeleopDriveFrame.entries) {
            val base = Mockito.mock(FtcMecanumRobot::class.java, Mockito.RETURNS_DEEP_STUBS)
            val store = Mockito.mock(Store::class.java)
            Mockito.`when`(base.store).thenReturn(store)
            Mockito.`when`(store.state).thenReturn(RobotState(drive = DriveState(alliance = Alliance.BLUE)))
            Mockito.`when`(base.teleopDriveFrame).thenReturn(frame)
            val gamepad = Mockito.mock(AresGamepad::class.java, Mockito.RETURNS_DEEP_STUBS)
            Mockito.`when`(gamepad.leftStickY.value).thenReturn(-1f)
            Mockito.`when`(gamepad.leftStickX.value).thenReturn(1f)
            Mockito.`when`(gamepad.rightStickX.value).thenReturn(-1f)
            AresDriveController(base).driveWithGamepad(gamepad, useHeadingLock = false)
            if (frame == FtcTeleopDriveFrame.FIELD_RELATIVE)
                // Blue forward/right point toward field -Y/-X after the first EMA sample.
                Mockito.verify(base.mecanumDrive).driveFieldRelativeNormalized(-0.4, -0.4, 0.4, false)
            else Mockito.verify(base.mecanumDrive).driveRobotRelativeNormalized(0.4, -0.4, 0.4)
            Mockito.verify(store, Mockito.times(1)).state
        }
    }

    @Test
    fun `alignment and pose requests delegate without direct hardware access`() {
        val base = setupMockRobot()
        val controller = AresDriveController(base)
        val pose = com.areslib.math.geometry.Pose2d(1.0, 2.0, com.areslib.math.geometry.Rotation2d(0.3))
        controller.alignToTag(42)
        controller.resetPoseForAlliance()
        controller.resetPose(pose)
        Mockito.verify(base).alignToTag(42)
        Mockito.verify(base).resetPoseForAlliance()
        Mockito.verify(base).resetPose(pose)
    }
    @Test
    fun `invalid input clears prior smoothing immediately`() {
        val base = setupMockRobot()
        val controller = AresDriveController(base)
        controller.driveFieldCentric(1.0, 1.0, 1.0)
        Mockito.clearInvocations(base)
        controller.driveFieldCentric(Double.NaN, 0.5, 0.5)
        controller.driveFieldCentric(0.0, 0.0, 0.0)
        Mockito.verify(base, Mockito.times(2)).driveFieldCentric(0.0, 0.0, 0.0)
    }

    @Test
    fun `one immutable state snapshot shapes and mirrors the complete input frame`() {
        val base = setupMockRobot()
        val store = base.store
        val red = store.state
        val blue = red.copy(drive = red.drive.copy(alliance = Alliance.BLUE))
        Mockito.`when`(store.state).thenReturn(red, blue)
        Mockito.clearInvocations(store)
        AresDriveController(base).driveFieldCentric(1.0, 0.0, 0.0)
        Mockito.verify(store, Mockito.times(1)).state
        Mockito.verify(base).driveFieldCentric(0.4, 0.0, 0.0)
    }

    @Test
    fun `nonfinite tuning exponent uses the documented fallback curve`() {
        val base = setupMockRobot()
        val state = base.store.state
        Mockito.`when`(base.store.state).thenReturn(state.copy(tuning = state.tuning.copy(
            driver = state.tuning.driver.copy(deadbandExponent = Double.POSITIVE_INFINITY))))
        AresDriveController(base).driveRobotCentric(0.5, 0.0, 0.0)
        Mockito.verify(base).driveRobotCentric(
            org.mockito.AdditionalMatchers.eq(expectedSmoothed(0.5, 3.0), 1e-12),
            org.mockito.AdditionalMatchers.eq(0.0, 1e-12), org.mockito.AdditionalMatchers.eq(0.0, 1e-12))
    }

    private fun setupMockRobot(alliance: Alliance = Alliance.RED): FtcMecanumRobot {
        val base = Mockito.mock(FtcMecanumRobot::class.java)
        val store = Mockito.mock(Store::class.java)
        val state = RobotState(drive = DriveState(alliance = alliance))
        
        Mockito.`when`(base.store).thenReturn(store)
        Mockito.`when`(store.state).thenReturn(state)
        return base
    }

    // Helper: recompute expected output for the deadband formula.
    // processAxis(input) = sign(d) * |d|^exp  where d = (|input| - 0.05) / 0.95 * sign(input),
    // and exp comes from store.state.tuning.driver.deadbandExponent (default = 1.0).
    // smoothTransition on first call (lastX=0): smoothed = 0.0 * 0.6 + processed * 0.4
    private fun expectedSmoothed(input: Double, exponent: Double = 1.0): Double {
        val bounded = if (input.isFinite()) input.coerceIn(-1.0, 1.0) else 0.0
        if (kotlin.math.abs(bounded) < 0.05) return 0.0
        val deadzoned = (kotlin.math.abs(bounded) - 0.05) / 0.95 * kotlin.math.sign(bounded)
        val processed = kotlin.math.sign(deadzoned) * kotlin.math.abs(deadzoned).pow(exponent)
        return processed * 0.4 // first-frame smoothing: lastX starts at 0
    }

    @Test
    fun testFieldCentricDriveRedAlliance() {
        val base = setupMockRobot(Alliance.RED)
        val controller = AresDriveController(base)
        
        controller.driveFieldCentric(0.5, 0.5, 0.1)
        
        Mockito.verify(base).driveFieldCentric(
            org.mockito.AdditionalMatchers.eq(expectedSmoothed(0.5), 1e-4),
            org.mockito.AdditionalMatchers.eq(expectedSmoothed(0.5), 1e-4),
            org.mockito.AdditionalMatchers.eq(expectedSmoothed(0.1), 1e-6)
        )
    }

    @Test
    fun testFieldCentricDriveBlueAlliance() {
        val base = setupMockRobot(Alliance.BLUE)
        val controller = AresDriveController(base)
        
        controller.driveFieldCentric(0.7, -0.35, 0.1)
        
        // Asymmetric, opposite-sign axes prevent an implementation that mirrors or
        // swaps only one translation component from passing accidentally.
        Mockito.verify(base).driveFieldCentric(
            org.mockito.AdditionalMatchers.eq(-expectedSmoothed(0.7), 1e-4),
            org.mockito.AdditionalMatchers.eq(-expectedSmoothed(-0.35), 1e-4),
            org.mockito.AdditionalMatchers.eq(expectedSmoothed(0.1), 1e-6)
        )
    }

    @Test
    fun testRobotCentricDrive() {
        val base = setupMockRobot()
        val controller = AresDriveController(base)
        
        controller.driveRobotCentric(0.5, 0.5, 0.1)
        
        Mockito.verify(base).driveRobotCentric(
            org.mockito.AdditionalMatchers.eq(expectedSmoothed(0.5), 1e-4),
            org.mockito.AdditionalMatchers.eq(expectedSmoothed(0.5), 1e-4),
            org.mockito.AdditionalMatchers.eq(expectedSmoothed(0.1), 1e-6)
        )
    }

    @Test
    fun testRobotCentricDriveBlueAllianceDoesNotMirror() {
        val base = setupMockRobot(Alliance.BLUE)
        val controller = AresDriveController(base)

        controller.driveRobotCentric(0.5, 0.5, 0.1)

        Mockito.verify(base).driveRobotCentric(
            org.mockito.AdditionalMatchers.eq(expectedSmoothed(0.5), 1e-4),
            org.mockito.AdditionalMatchers.eq(expectedSmoothed(0.5), 1e-4),
            org.mockito.AdditionalMatchers.eq(expectedSmoothed(0.1), 1e-6)
        )
    }

    @Test
    fun testZeroJoystickInputProducesZeroOutput() {
        val base = setupMockRobot()
        val controller = AresDriveController(base)
        
        controller.driveFieldCentric(0.0, 0.0, 0.0)
        
        Mockito.verify(base).driveFieldCentric(0.0, 0.0, 0.0)
    }

    @Test
    fun testDeadbandFilteringEliminatesSmallNoise() {
        val base = setupMockRobot()
        val controller = AresDriveController(base)
        
        val noise = 0.01
        controller.driveFieldCentric(noise, noise, 0.0)
        
        Mockito.verify(base).driveFieldCentric(0.0, 0.0, 0.0)
    }
    
    @Test
    fun testMotorPowerBounds() {
        val base = setupMockRobot()
        val controller = AresDriveController(base)
        
        controller.driveFieldCentric(2.0, -2.0, 0.0)
        
        Mockito.verify(base).driveFieldCentric(
            org.mockito.AdditionalMatchers.eq(expectedSmoothed(2.0), 1e-3),
            org.mockito.AdditionalMatchers.eq(expectedSmoothed(-2.0), 1e-3),
            org.mockito.AdditionalMatchers.eq(0.0, 1e-6)
        )
    }

    @Test
    fun `non-finite joystick values fail closed`() {
        val base = setupMockRobot()
        val controller = AresDriveController(base)

        controller.driveFieldCentric(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)

        Mockito.verify(base).driveFieldCentric(0.0, 0.0, 0.0)
    }

    @Test
    fun `rotation command is shaped before delegation`() {
        val base = setupMockRobot()
        val controller = AresDriveController(base)
        
        controller.driveFieldCentric(0.0, 0.0, 0.5)
        Mockito.verify(base).driveFieldCentric(
            org.mockito.AdditionalMatchers.eq(0.0, 1e-6),
            org.mockito.AdditionalMatchers.eq(0.0, 1e-6),
            org.mockito.AdditionalMatchers.eq(expectedSmoothed(0.5), 1e-4)
        )
    }

    @Test
    fun testEmaConvergenceOverConsecutiveSteps() {
        val base = setupMockRobot(Alliance.RED)
        val controller = AresDriveController(base)

        // Drive with constant 0.5 input for 20 frames
        repeat(20) {
            controller.driveFieldCentric(0.5, 0.0, 0.0)
        }

        // After 20 frames with alpha=0.4, EMA converges to steady-state processAxis(0.5)
        val expectedSteadyState = (0.5 - 0.05) / 0.95 // = 0.473684
        Mockito.verify(base, Mockito.atLeastOnce()).driveFieldCentric(
            org.mockito.AdditionalMatchers.eq(expectedSteadyState, 1e-3),
            org.mockito.AdditionalMatchers.eq(0.0, 1e-6),
            org.mockito.AdditionalMatchers.eq(0.0, 1e-6)
        )
    }
}
