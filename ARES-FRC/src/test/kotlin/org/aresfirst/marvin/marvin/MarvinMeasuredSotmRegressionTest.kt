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
    private fun movingStore() = Store(RobotState(superstructure = SuperstructureState(custom = MarvinState()))) {
        state, action -> MarvinReducer.reduce(state, action)
    }

    private fun observe(store: Store, timestamp: Long, vx: Double = 0.0, vy: Double = 0.0) {
        store.dispatch(RobotAction.PoseUpdate(3.0, 2.0, 0.35, timestamp,
            xVelocityMetersPerSecond = vx, yVelocityMetersPerSecond = vy, isExternalEstimate = true))
    }

    @Test
    fun `first measured motion establishes acceleration baseline without a fictitious zero sample`() {
        val store = movingStore()
        observe(store, 1000L, 1.0, 2.0)
        val result = ShotResult()
        val pose = Pose2d(3.0, 2.0, Rotation2d(0.35))
        val target = Translation2d(0.0, 5.0)
        MarvinShooterSubsystem(store).updateShootOnTheMove(pose, target, result)
        val expected = ShotResult()
        ShotSetup(MarvinConfig.SHOT_CONFIG).calculate(pose, ChassisSpeeds(1.0, 2.0, 0.0), target, expected)
        assertTrue(result.isValid)
        assertShotEquals(expected, result)
    }

    @Test
    fun `static aiming and trigger release reset moving acceleration history`() {
        val store = movingStore()
        val shooter = MarvinShooterSubsystem(store)
        val pose = Pose2d(3.0, 2.0, Rotation2d(0.35))
        val target = Translation2d(0.0, 5.0)
        observe(store, 1000L, 1.0)
        shooter.updateShootOnTheMove(pose, target, ShotResult())
        shooter.updateStaticShoot(pose, target)
        RobotClock.useMockTime(1020L)
        observe(store, 1020L, 2.0)
        val actual = ShotResult()
        shooter.updateShootOnTheMove(pose, target, actual)
        val expected = ShotResult()
        ShotSetup(MarvinConfig.SHOT_CONFIG).calculate(pose, ChassisSpeeds(2.0, 0.0, 0.0), target, expected)
        assertShotEquals(expected, actual)
        shooter.cancelTransfer()
        RobotClock.useMockTime(1040L)
        observe(store, 1040L, 3.0)
        shooter.updateShootOnTheMove(pose, target, actual)
        ShotSetup(MarvinConfig.SHOT_CONFIG).calculate(pose, ChassisSpeeds(3.0, 0.0, 0.0), target, expected)
        assertShotEquals(expected, actual)
    }

    @Test
    fun `duplicate observation preserves acceleration while later observations advance it`() {
        val store = movingStore()
        val shooter = MarvinShooterSubsystem(store)
        val pose = Pose2d(3.0, 2.0, Rotation2d(0.35))
        val target = Translation2d(0.0, 5.0)
        observe(store, 1000L)
        shooter.updateShootOnTheMove(pose, target, ShotResult())
        RobotClock.useMockTime(1020L)
        observe(store, 1020L, 1.0)
        val first = ShotResult()
        shooter.updateShootOnTheMove(pose, target, first)
        RobotClock.useMockTime(1030L)
        val duplicate = ShotResult()
        shooter.updateShootOnTheMove(pose, target, duplicate)
        assertShotEquals(first, duplicate)
        RobotClock.useMockTime(1040L)
        observe(store, 1040L, 1.0)
        val settled = ShotResult()
        shooter.updateShootOnTheMove(pose, target, settled)
        val expected = ShotResult()
        ShotSetup(MarvinConfig.SHOT_CONFIG).calculate(pose, ChassisSpeeds(1.0, 0.0, 0.0), target, expected)
        assertShotEquals(expected, settled)
    }

    @Test
    fun `stale measured motion revokes firing even when the validity flag remains true`() {
        val store = movingStore()
        observe(store, 1000L)
        val shooter = MarvinShooterSubsystem(store)
        val pose = Pose2d(3.0, 2.0, Rotation2d(0.35))
        val target = Translation2d(0.0, 5.0)
        val result = ShotResult()
        shooter.updateShootOnTheMove(pose, target, result)
        RobotClock.useMockTime(1100L)
        shooter.updateShootOnTheMove(pose, target, result)
        assertTrue(result.isValid)
        RobotClock.useMockTime(1101L)
        assertEquals(0.0, shooter.updateShootOnTheMove(pose, target, result))
        assertEquals(false, result.isValid)
        assertEquals(false, store.state.superstructure.marvin.flywheelActive)
        RobotClock.useMockTime(1120L)
        observe(store, 1120L, 1.0)
        shooter.updateShootOnTheMove(pose, target, result)
        val expected = ShotResult()
        ShotSetup(MarvinConfig.SHOT_CONFIG).calculate(pose, ChassisSpeeds(1.0, 0.0, 0.0), target, expected)
        assertTrue(result.isValid)
        assertShotEquals(expected, result)
    }

    @Test
    fun `clock rollback cannot accept a future measured observation`() {
        val store = movingStore()
        observe(store, 1000L)
        val shooter = MarvinShooterSubsystem(store)
        val pose = Pose2d(3.0, 2.0, Rotation2d(0.35))
        val target = Translation2d(0.0, 5.0)
        val result = ShotResult()
        shooter.updateShootOnTheMove(pose, target, result)
        RobotClock.useMockTime(999L)
        assertEquals(0.0, shooter.updateShootOnTheMove(pose, target, result))
        assertEquals(false, result.isValid)
        assertEquals(false, store.state.superstructure.marvin.flywheelActive)
    }

    @Test
    fun `invalid static target cancels an already active shooter`() {
        val store = Store(RobotState(superstructure = SuperstructureState(custom = MarvinState()))) {
            state, action -> MarvinReducer.reduce(state, action)
        }
        val shooter = MarvinShooterSubsystem(store)
        val pose = Pose2d(3.0, 2.0, Rotation2d(0.35))
        shooter.updateStaticShoot(pose, Translation2d(0.0, 5.0))
        assertTrue(store.state.superstructure.marvin.flywheelActive)
        assertEquals(0.0, shooter.updateStaticShoot(pose, Translation2d(Double.NaN, 5.0)))
        assertEquals(false, store.state.superstructure.marvin.flywheelActive)
        assertEquals(0.0, store.state.superstructure.marvin.feeder.targetVelocityRps)
    }

    @Test
    fun `invalid moving target clears result and cancels shooter intent`() {
        val store = Store(RobotState(drive = DriveState(measuredMotionValid = true),
            superstructure = SuperstructureState(custom = MarvinState()))) {
            state, action -> MarvinReducer.reduce(state, action)
        }
        val shooter = MarvinShooterSubsystem(store)
        val pose = Pose2d(3.0, 2.0, Rotation2d(0.35))
        val result = ShotResult()
        observe(store, 1000L)
        shooter.updateShootOnTheMove(pose, Translation2d(0.0, 5.0), result)
        assertTrue(result.isValid)
        assertEquals(0.0, shooter.updateShootOnTheMove(pose, Translation2d(Double.NaN, 5.0), result))
        assertEquals(false, result.isValid)
        assertEquals(false, store.state.superstructure.marvin.flywheelActive)
        assertEquals(0.0, store.state.superstructure.marvin.feeder.targetVelocityRps)
    }


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

        // Reuse one stationary observation across two control calls.
        observe(store, 1000L)
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
        store.dispatch(RobotAction.PoseUpdate(pose.x, pose.y, pose.heading.radians, 1060L,
            xVelocityMetersPerSecond = measuredVx, yVelocityMetersPerSecond = measuredVy,
            angularVelocityRadiansPerSecond = measuredOmega, isExternalEstimate = true))
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

        observe(store, 1000L)
        shooter.updateShootOnTheMove(pose, target, result)
        assertTrue(store.state.superstructure.marvin.flywheelActive)

        RobotClock.useMockTime(1_020L)
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
        assertEquals(RobotClock.currentTimeMillis(), store.state.drive.poseEstimator.lastObservationTimestampMs)
        assertEquals(false, store.state.drive.measuredMotionValid)
        val rotation = shooter.updateShootOnTheMove(pose, target, result)

        assertEquals(0.0, rotation)
        assertEquals(0.0, result.targetFlywheelRpm)
        assertEquals(0.0, result.targetCowlAngleRotations)
        assertEquals(false, store.state.superstructure.marvin.flywheelActive)
        assertEquals(0.0, store.state.superstructure.marvin.flywheel.targetVelocityRpm)
        assertEquals(0.0, store.state.superstructure.marvin.feeder.targetVelocityRps)
    }

    private fun assertShotEquals(expected: ShotResult, actual: ShotResult) {
        assertTrue(expected.isValid, "Reference shot must be valid")
        assertTrue(actual.isValid, "Actual shot must be valid")
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
