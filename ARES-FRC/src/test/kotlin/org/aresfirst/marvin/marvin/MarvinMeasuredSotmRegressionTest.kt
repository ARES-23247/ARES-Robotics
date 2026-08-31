package org.aresfirst.marvin.marvin

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.control.assist.ShotResult
import com.areslib.control.assist.ShotSetup
import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import com.areslib.state.DriveState
import com.areslib.state.RobotState
import com.areslib.state.SuperstructureState
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MarvinMeasuredSotmRegressionTest {

    @BeforeEach
    fun useDeterministicClock() {
        RobotClock.useMockTime(1_000L)
    }

    @AfterEach
    fun restoreClock() {
        RobotClock.useSystemTime()
    }

    @Test
    fun `SOTM uses measured field velocity and omega rather than commanded intent`() {
        val commandedVx = 4.0
        val commandedVy = -3.0
        val commandedOmega = 2.0
        val store = Store(
            RobotState(
                drive = DriveState(
                    xVelocityMetersPerSecond = commandedVx,
                    yVelocityMetersPerSecond = commandedVy,
                    angularVelocityRadiansPerSecond = commandedOmega,
                    measuredMotionValid = true
                ),
                superstructure = SuperstructureState(custom = MarvinState())
            )
        ) { state, action -> MarvinReducer.reduce(state, action) }
        val shooter = MarvinShooterSubsystem(store)
        val pose = Pose2d(3.0, 2.0, Rotation2d(0.35))
        val target = Translation2d(0.0, 5.547868)
        val actual = ShotResult()

        // Prime the acceleration lookahead twice with a stationary measured chassis.
        shooter.updateShootOnTheMove(pose, target, actual)
        RobotClock.useMockTime(1_020L)
        shooter.updateShootOnTheMove(pose, target, actual)

        val stationaryExpected = ShotResult()
        ShotSetup(MarvinConfig.SHOT_CONFIG).calculate(
            pose,
            ChassisSpeeds(0.0, 0.0, 0.0),
            target,
            stationaryExpected
        )
        assertShotEquals(stationaryExpected, actual)
        val stationaryVirtualTargetX = actual.virtualTargetX

        val measuredVx = 1.25
        val measuredVy = -0.50
        val measuredOmega = 0.35
        store.dispatch(
            RobotAction.PoseUpdate(
                xMeters = pose.x,
                yMeters = pose.y,
                headingRadians = pose.heading.radians,
                timestampMs = 1_040L,
                isReset = true,
                xVelocityMetersPerSecond = measuredVx,
                yVelocityMetersPerSecond = measuredVy,
                angularVelocityRadiansPerSecond = measuredOmega
            )
        )

        // Pose observations update measured velocity without overwriting drive intent.
        assertEquals(commandedVx, store.state.drive.xVelocityMetersPerSecond)
        assertEquals(commandedVy, store.state.drive.yVelocityMetersPerSecond)
        assertEquals(commandedOmega, store.state.drive.angularVelocityRadiansPerSecond)

        // First sample absorbs the acceleration step; the next sample has zero acceleration
        // and therefore isolates the measured chassis velocity supplied to ShotSetup.
        RobotClock.useMockTime(1_040L)
        shooter.updateShootOnTheMove(pose, target, actual)
        RobotClock.useMockTime(1_060L)
        shooter.updateShootOnTheMove(pose, target, actual)

        val measuredExpected = ShotResult()
        ShotSetup(MarvinConfig.SHOT_CONFIG).calculate(
            pose,
            ChassisSpeeds(measuredVx, measuredVy, measuredOmega),
            target,
            measuredExpected
        )
        assertShotEquals(measuredExpected, actual)
        assertTrue(
            kotlin.math.abs(actual.virtualTargetX - stationaryVirtualTargetX) > 1e-6,
            "A real measured chassis velocity must move the SOTM virtual target"
        )
    }

    @Test
    fun `SOTM stops shooter and feeder when measured motion becomes invalid`() {
        val store = Store(
            RobotState(
                drive = DriveState(measuredMotionValid = true),
                superstructure = SuperstructureState(custom = MarvinState())
            )
        ) { state, action -> MarvinReducer.reduce(state, action) }
        val shooter = MarvinShooterSubsystem(store)
        val pose = Pose2d(3.0, 2.0, Rotation2d(0.35))
        val target = Translation2d(0.0, 5.547868)
        val result = ShotResult()

        shooter.updateShootOnTheMove(pose, target, result)
        assertTrue(store.state.superstructure.marvin.flywheelActive)

        store.dispatch(
            RobotAction.PoseUpdate(
                xMeters = pose.x,
                yMeters = pose.y,
                headingRadians = pose.heading.radians,
                timestampMs = 1_020L,
                motionMeasurementsValid = false,
                imuMeasurementsValid = true,
                isExternalEstimate = true
            )
        )
        val rotation = shooter.updateShootOnTheMove(pose, target, result)

        assertEquals(0.0, rotation)
        assertEquals(0.0, result.targetFlywheelRpm)
        assertEquals(0.0, result.targetCowlAngleRotations)
        assertEquals(false, store.state.superstructure.marvin.flywheelActive)
        assertEquals(0.0, store.state.superstructure.marvin.flywheel.targetVelocityRpm)
        assertEquals(0.0, store.state.superstructure.marvin.feeder.targetVelocityRps)
    }

    private fun assertShotEquals(expected: ShotResult, actual: ShotResult) {
        assertEquals(expected.virtualTargetX, actual.virtualTargetX, 1e-9)
        assertEquals(expected.virtualTargetY, actual.virtualTargetY, 1e-9)
        assertEquals(expected.robotTargetHeadingRad, actual.robotTargetHeadingRad, 1e-9)
        assertEquals(expected.aimDistanceMeters, actual.aimDistanceMeters, 1e-9)
        assertEquals(expected.targetFlywheelRpm, actual.targetFlywheelRpm, 1e-9)
        assertEquals(expected.targetCowlAngleRotations, actual.targetCowlAngleRotations, 1e-9)
        assertEquals(
            expected.angularVelocityFeedforwardRadPerSec,
            actual.angularVelocityFeedforwardRadPerSec,
            1e-9
        )
    }
}
