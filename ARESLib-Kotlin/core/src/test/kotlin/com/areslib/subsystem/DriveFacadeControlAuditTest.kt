package com.areslib.subsystem

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.control.tuning.PIDFCoefficients
import com.areslib.math.estimation.PoseEstimatorSnapshot
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.pathing.Path
import com.areslib.pathing.PathPoint
import com.areslib.state.*
import com.areslib.util.RobotClock
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.math.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DriveFacadeControlAuditTest {
    @AfterEach fun restoreClock() { RobotClock.useSystemTime() }

    @Test fun positionCorrectionDoesNotCancelItsOwnTarget() {
        val store = heldStore(x = 0.0, y = 0.0, targetX = 1.0, targetY = -0.5)
        val facade = MecanumDriveFacade(store)
        repeat(4) {
            facade.driveFieldRelativeNormalized(0.0, 0.0, 0.0, usePositionHold = true)
            assertEquals(1.0, store.state.drive.positionLockX)
            assertEquals(-0.5, store.state.drive.positionLockY)
            assertTrue(store.state.drive.xVelocityMetersPerSecond > 0.0)
            assertTrue(store.state.drive.yVelocityMetersPerSecond < 0.0)
        }
    }

    @Test fun holdAcquisitionIgnoresSubDeadzoneStickNoise() {
        val store = Store()
        val facade = MecanumDriveFacade(store)
        facade.driveFieldRelativeNormalized(0.04, -0.04, 0.04, true, true)
        assertEquals(0.0, store.state.drive.headingLockTargetRadians)
        assertEquals(0.0, store.state.drive.positionLockX)
        assertEquals(0.0, store.state.drive.positionLockY)
        assertNeutral(store)
    }

    @Test fun disablingHoldsRestoresTeleopAndManualMovementRetainsOnlyRequestedHold() {
        val store = heldStore(headingTarget = 0.3)
        val facade = MecanumDriveFacade(store)
        facade.driveFieldRelativeNormalized(0.3, 0.0, 0.0, true, true)
        assertNull(store.state.drive.positionLockX)
        assertNotNull(store.state.drive.headingLockTargetRadians)
        assertEquals(DriveMode.HEADING_HOLD, store.state.drive.driveMode)
        facade.driveFieldRelativeNormalized(0.0, 0.0, 0.0, false, false)
        assertNull(store.state.drive.headingLockTargetRadians)
        assertEquals(DriveMode.TELEOP, store.state.drive.driveMode)
    }

    @Test fun manualRotationCanReleaseHeadingWhilePositionRemainsHeld() {
        val store = heldStore(headingTarget = 0.3)
        MecanumDriveFacade(store).driveFieldRelativeNormalized(0.0, 0.0, 0.5, true, true)
        assertNull(store.state.drive.headingLockTargetRadians)
        assertEquals(0.0, store.state.drive.positionLockX)
        assertEquals(DriveMode.POSITION_HOLD, store.state.drive.driveMode)
    }

    @Test fun positionCorrectionUsesVelocityUnitsAndOneCircularSpeedLimit() {
        val store = heldStore(targetX = 2.0, targetY = 1.0)
        val facade = MecanumDriveFacade(store).apply { maxSpeedMps = 2.0 }
        facade.driveFieldRelativeNormalized(0.0, 0.0, 0.0, usePositionHold = true)
        val drive = store.state.drive
        assertEquals(0.4, hypot(drive.xVelocityMetersPerSecond, drive.yVelocityMetersPerSecond), 1e-12)
        // Both axis PID outputs are saturated before the shared vector limit; kS is not velocity.
        assertEquals(drive.xVelocityMetersPerSecond, drive.yVelocityMetersPerSecond, 1e-12)
    }

    @Test fun smallPositionCorrectionIsIndependentOfMotorFeedforward() {
        val store = heldStore(targetX = 0.1)
        MecanumDriveFacade(store).driveFieldRelativeNormalized(0.0, 0.0, 0.0, usePositionHold = true)
        assertEquals(0.1, store.state.drive.xVelocityMetersPerSecond, 1e-12)
    }

    @Test fun malformedPartialPositionTargetNeutralizesWithoutThrowing() {
        for ((x, y) in listOf(1.0 to null, null to 1.0, Double.NaN to 1.0, 1.0 to Double.POSITIVE_INFINITY)) {
            val store = Store(RobotState(drive = DriveState(positionLockX = x, positionLockY = y, driveMode = DriveMode.POSITION_HOLD)))
            assertDoesNotThrow { MecanumDriveFacade(store).driveFieldRelativeNormalized(0.0, 0.0, 0.0, usePositionHold = true) }
            assertNeutral(store)
            assertNull(store.state.drive.positionLockX)
            assertNull(store.state.drive.positionLockY)
        }
    }

    @Test fun invalidHoldTimingCannotReintroduceStaticEffortAfterPidNeutralizes() {
        for (dt in listOf(0.0, -0.02, Double.NaN, Double.POSITIVE_INFINITY)) {
            val store = heldStore(targetX = 0.1)
            MecanumDriveFacade(store).driveFieldRelativeNormalized(0.0, 0.0, 0.0, usePositionHold = true, dtSeconds = dt)
            assertNeutral(store)
        }
    }

    @Test fun constructorHeadingGainsApplyUntilNewDriveTuningArrives() {
        val store = heldStore(headingTarget = 0.1)
        val facade = MecanumDriveFacade(store, PIDFCoefficients(3.0, 0.0, 0.0), 0.0)
        facade.driveFieldRelativeNormalized(0.0, 0.0, 0.0, useHeadingLock = true)
        assertEquals(0.3, store.state.drive.angularVelocityRadiansPerSecond, 1e-12)
        val tuning = store.state.tuning
        store.dispatch(RobotAction.UpdateTuningState(tuning.copy(drive = tuning.drive.copy(headingGains = PIDFCoefficients(4.0, 0.0, 0.0)))))
        facade.driveFieldRelativeNormalized(0.0, 0.0, 0.0, useHeadingLock = true)
        assertEquals(0.4, store.state.drive.angularVelocityRadiansPerSecond, 1e-12)
    }

    @Test fun brakingNeutralizesPriorVelocityAndDoesNotReacquireHolds() {
        val store = heldStore(targetX = 1.0, headingTarget = 0.2)
        val facade = SwerveDriveFacade(store)
        facade.driveRobotRelativeNormalized(0.6, 0.2, 0.3)
        facade.brake()
        assertNeutral(store)
        assertEquals(DriveMode.X_BRAKE, store.state.drive.driveMode)
        facade.driveFieldRelativeNormalized(0.0, 0.0, 0.0, true, true)
        assertNeutral(store)
        assertEquals(DriveMode.X_BRAKE, store.state.drive.driveMode)
        facade.driveRobotRelativeNormalized(0.5, 0.0, 0.0)
        assertEquals(DriveMode.TELEOP, store.state.drive.driveMode)
    }

    @Test fun measuredGettersDoNotExposeCommandedVelocities() {
        val store = Store(RobotState(drive = DriveState(xVelocityMetersPerSecond = 3.0, yVelocityMetersPerSecond = -4.0,
            angularVelocityRadiansPerSecond = 5.0, measuredFieldXVelocityMetersPerSecond = 0.7,
            measuredFieldYVelocityMetersPerSecond = -0.8, measuredAngularVelocityRadiansPerSecond = 0.9)))
        val facade = MecanumDriveFacade(store)
        val drive = DriveSubsystem(store)
        assertEquals(0.7, facade.xVelocity)
        assertEquals(-0.8, facade.yVelocity)
        assertEquals(0.9, facade.angularVelocity)
        assertEquals(0.7, drive.xVelocity)
        assertEquals(-0.8, drive.yVelocity)
        assertEquals(0.9, drive.angularVelocity)
    }

    @Test fun diagonalNormalizedCommandsRetainFieldDirectionAtEveryHeading() {
        repeat(16) { index ->
            val heading = index * Math.PI / 8.0
            val store = Store(RobotState(drive = DriveState(poseEstimator = PoseEstimatorSnapshot(estimatedPoseHeading = heading))))
            val facade = MecanumDriveFacade(store)
            facade.driveFieldRelativeNormalized(1.0, 0.5, 0.0)
            val d = store.state.drive
            val fieldX = d.xVelocityMetersPerSecond * cos(heading) - d.yVelocityMetersPerSecond * sin(heading)
            val fieldY = d.xVelocityMetersPerSecond * sin(heading) + d.yVelocityMetersPerSecond * cos(heading)
            assertEquals(facade.maxSpeedMps / hypot(1.0, 0.5), fieldX, 1e-10)
            assertEquals(fieldX * 0.5, fieldY, 1e-10)
        }
    }

    @Test fun invalidSpeedConfigurationProducesOnlyFiniteNeutralCommands() {
        for (limit in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val store = Store()
            val facade = MecanumDriveFacade(store).apply { maxSpeedMps = limit }
            facade.driveRobotRelativeNormalized(0.5, 0.4, 0.3)
            assertNeutral(store)
            val drive = DriveSubsystem(store).apply { maxAngularSpeedRadiansPerSecond = limit }
            drive.setChassisSpeeds(0.5, 0.4, 0.3)
            assertNeutral(store)
        }
    }

    @Test fun physicalCommandsRespectVectorLimitsEvenAtFiniteExtremeInputs() {
        val store = Store()
        val drive = DriveSubsystem(store).apply { maxSpeedMps = 2.0; maxAngularSpeedRadiansPerSecond = 3.0 }
        drive.setChassisSpeeds(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
        assertEquals(sqrt(2.0), store.state.drive.xVelocityMetersPerSecond, 1e-12)
        assertEquals(sqrt(2.0), store.state.drive.yVelocityMetersPerSecond, 1e-12)
        assertEquals(3.0, store.state.drive.angularVelocityRadiansPerSecond, 1e-12)
    }

    @Test fun pathFollowingRequiresAnExecutionOwnerAndNeverSilentlyRelocatesEstimator() {
        val store = Store()
        val path = Path(listOf(PathPoint(Pose2d(5.0, 6.0, Rotation2d(0.4)), 1.0)))
        assertThrows(IllegalStateException::class.java) { MecanumDriveFacade(store).followPath(path) }
        assertEquals(Pose2d(), store.state.drive.poseEstimator.estimatedPose)
    }

    @Test fun repeatedPhysicalCommandsRefreshTimeWithoutAllocatingActions() {
        var sum = 0.0
        var timestamp = -1L
        val store = Store(RobotState()) { state, action ->
            if (action is RobotAction.JoystickDriveIntent) { sum += action.targetXVelocity; timestamp = action.timestampMs }
            state
        }
        val drive = DriveSubsystem(store)
        RobotClock.useMockTime(100L)
        repeat(30_000) { drive.setChassisSpeeds(0.5, 0.0, 0.0) }
        RobotClock.useMockTime(200L)
        val bytes = allocations { repeat(10_000) { drive.setChassisSpeeds(0.5, 0.0, 0.0) } }
        assertEquals(20_000.0, sum)
        assertEquals(200L, timestamp)
        println("DriveSubsystem: " + bytes + " bytes / 10000 physical commands (no-op reducer, desktop JVM)")
        assertTrue(bytes <= 4096L, "Physical commands allocated " + bytes)
    }

    @Test fun unchangedNumericControllerTargetsAvoidFactoryAndBoxingWork() {
        class Probe(store: Store) : SubsystemControllerBase(store) {
            var factories = 0
            fun request(current: Double, target: Double) = dispatchOnChange(current, target,
                { value, time -> factories++; RobotAction.SetHeadingLockTarget(value, time) }, {})
        }
        val probe = Probe(Store())
        repeat(30_000) { probe.request(0.3, 0.3) }
        val bytes = allocations { repeat(10_000) { probe.request(0.3, 0.3) } }
        assertEquals(0, probe.factories)
        println("SubsystemControllerBase: " + bytes + " bytes / 10000 unchanged Double requests (desktop JVM)")
        assertTrue(bytes <= 4096L, "Unchanged controller requests allocated " + bytes)
    }

    private fun heldStore(x: Double = 0.0, y: Double = 0.0, targetX: Double = 0.0, targetY: Double = 0.0, headingTarget: Double? = null): Store =
        Store(RobotState(drive = DriveState(poseEstimator = PoseEstimatorSnapshot(estimatedPoseX = x, estimatedPoseY = y),
            positionLockX = targetX, positionLockY = targetY, headingLockTargetRadians = headingTarget, driveMode = DriveMode.POSITION_HOLD),
            tuning = TuningState(drive = DriveTuningState(positionHoldGains = PIDFCoefficients(1.0, 0.0, 0.0), positionHoldMaxOutputLimit = 0.2))))

    private fun assertNeutral(store: Store) {
        assertEquals(0.0, store.state.drive.xVelocityMetersPerSecond, 0.0)
        assertEquals(0.0, store.state.drive.yVelocityMetersPerSecond, 0.0)
        assertEquals(0.0, store.state.drive.angularVelocityRadiansPerSecond, 0.0)
    }

    private inline fun allocations(block: () -> Unit): Long {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        block()
        return bean.getThreadAllocatedBytes(id) - before
    }
}
