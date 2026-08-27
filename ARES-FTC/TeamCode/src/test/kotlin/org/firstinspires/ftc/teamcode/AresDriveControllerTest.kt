package org.firstinspires.ftc.teamcode

import com.areslib.ftc.FtcMecanumRobot
import com.areslib.state.Alliance
import com.areslib.state.DriveState
import com.areslib.state.RobotState
import com.areslib.Store
import org.firstinspires.ftc.teamcode.opmodes.robot.AresDriveController
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito
import org.mockito.ArgumentMatchers.anyDouble
import kotlin.math.pow

class AresDriveControllerTest {

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
    // and exp comes from store.state.tuning.driverDeadbandExponent (default TuningState = 1.0).
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
    fun testClosedLoopHeadingPID() {
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
