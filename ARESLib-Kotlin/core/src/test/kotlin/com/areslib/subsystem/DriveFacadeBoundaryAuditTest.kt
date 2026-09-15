package com.areslib.subsystem

import com.areslib.Store
import com.areslib.action.ActionReplay
import com.areslib.action.ActionReplayException
import com.areslib.action.RobotAction
import com.areslib.control.tuning.PIDFCoefficients
import com.areslib.math.estimation.PoseEstimatorSnapshot
import com.areslib.math.geometry.Pose2d
import com.areslib.pathing.HolonomicPathFollower
import com.areslib.pathing.Path
import com.areslib.pathing.PathPoint
import com.areslib.state.*
import com.areslib.telemetry.AresGamepad
import com.areslib.telemetry.GamepadState
import com.areslib.util.RobotClock
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DriveFacadeBoundaryAuditTest {
    @TempDir lateinit var tempDir: File
    @AfterEach fun restoreClock() { RobotClock.useSystemTime() }

    @Test fun positionProvenanceSurvivesCopiesAndOwnedReplaySnapshots() {
        val source = RobotAction.JoystickDriveIntent(0.4, -0.2, 0.1, 42L, false, true, false, true)
        val copy = source.copy()
        val encoded = ActionReplay.encodeForLog(source)
        source.fromPositionHold = false
        source.targetXVelocity = 9.0
        val decoded = decode(encoded.payload)
        assertEquals(copy, decoded)
        assertTrue(decoded.fromPositionHold)
        val store = Store(RobotState(drive = DriveState(positionLockX = 1.0, positionLockY = 2.0,
            headingLockTargetRadians = 0.3, driveMode = DriveMode.POSITION_HOLD)))
        store.dispatch(decoded)
        assertEquals(1.0, store.state.drive.positionLockX)
        assertEquals(2.0, store.state.drive.positionLockY)
        assertEquals(0.3, store.state.drive.headingLockTargetRadians)
        store.dispatch(decoded.copy(fromPositionHold = false, fromHeadingHold = false))
        assertNull(store.state.drive.positionLockX)
        assertNull(store.state.drive.headingLockTargetRadians)
        assertEquals(DriveMode.TELEOP, store.state.drive.driveMode)
    }

    @Test fun olderJoystickLogsDefaultOnlyAbsentPositionProvenanceToFalse() {
        val payload = ActionReplay.encodeForLog(RobotAction.JoystickDriveIntent(0.1, 0.2, 0.3, 42L)).payload
        payload.remove("fromPositionHold")
        assertFalse(decode(payload).fromPositionHold)
        // Existing seven-argument Java constructors remain available through JvmOverloads.
        val constructor = RobotAction.JoystickDriveIntent::class.java.getConstructor(
            java.lang.Double.TYPE, java.lang.Double.TYPE, java.lang.Double.TYPE, java.lang.Long.TYPE,
            java.lang.Boolean.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
        assertFalse(constructor.newInstance(0.0, 0.0, 0.0, 42L, false, false, false).fromPositionHold)
    }

    @Test fun malformedProvenanceDoesNotRelaxOtherReplayValidation() {
        for (bad in listOf(JsonNull.INSTANCE, JsonPrimitive("true"), JsonPrimitive(1))) {
            val payload = ActionReplay.encodeForLog(RobotAction.JoystickDriveIntent(0.0, 0.0, 0.0, 42L)).payload
            payload.add("fromPositionHold", bad)
            assertThrows(ActionReplayException::class.java) { decode(payload) }
        }
        val payload = ActionReplay.encodeForLog(RobotAction.JoystickDriveIntent(0.0, 0.0, 0.0, 42L)).payload
        payload.remove("fromHeadingHold")
        assertThrows(ActionReplayException::class.java) { decode(payload) }
    }

    @Test fun headingWrapAndCircularPositionDeadzoneUseTheSamePhysicalFrame() {
        val heading = Math.PI - 0.02
        val state = RobotState(drive = DriveState(poseEstimator = PoseEstimatorSnapshot(estimatedPoseHeading = heading),
            headingLockTargetRadians = -Math.PI + 0.02, positionLockX = 0.015, positionLockY = 0.015,
            driveMode = DriveMode.POSITION_HOLD), tuning = TuningState(drive = DriveTuningState(
                positionHoldGains = PIDFCoefficients(1.0, 0.0, 0.0), positionHoldDeadzoneMeters = 0.02)))
        val store = Store(state)
        val facade = MecanumDriveFacade(store, PIDFCoefficients(2.0, 0.0, 0.0), 0.0)
        facade.driveFieldRelativeNormalized(0.0, 0.0, 0.0, true, true)
        val drive = store.state.drive
        assertEquals(0.08, drive.angularVelocityRadiansPerSecond, 1e-10)
        val cosH = kotlin.math.cos(heading)
        val sinH = kotlin.math.sin(heading)
        assertEquals(0.015, drive.xVelocityMetersPerSecond * cosH - drive.yVelocityMetersPerSecond * sinH, 1e-10)
        assertEquals(0.015, drive.xVelocityMetersPerSecond * sinH + drive.yVelocityMetersPerSecond * cosH, 1e-10)
        facade.driveRobotRelativeNormalized(0.3, 0.0, 0.1)
        assertNull(store.state.drive.positionLockX)
        assertNull(store.state.drive.headingLockTargetRadians)
    }

    @Test fun gamepadScalesAndAllianceMirroringRemainExplicit() {
        for (alliance in Alliance.entries) {
            val store = Store(RobotState(drive = DriveState(alliance = alliance)))
            val facade = SwerveDriveFacade(store)
            val pad = AresGamepad()
            pad.update(GamepadState(leftStickY = -1.0f, leftBumper = true, rightBumper = true))
            facade.driveWithGamepad(pad, useHeadingLock = false)
            val sign = if (alliance == Alliance.BLUE) -1.0 else 1.0
            assertEquals(sign * facade.maxSpeedMps, store.state.drive.xVelocityMetersPerSecond, 1e-10)
            pad.update(GamepadState(leftStickY = -1.0f, leftBumper = true))
            facade.driveWithGamepad(pad, useHeadingLock = false)
            assertEquals(sign * 0.4 * facade.maxSpeedMps, store.state.drive.xVelocityMetersPerSecond, 1e-10)
        }
    }

    @Test fun invalidInputsPoseAndHoldTuningStayNeutral() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val store = Store()
            val facade = MecanumDriveFacade(store)
            facade.driveFieldRelativeNormalized(bad, 0.7, 0.4, true, true)
            assertEquals(0.0, store.state.drive.xVelocityMetersPerSecond)
            assertEquals(0.0, store.state.drive.yVelocityMetersPerSecond)
            assertEquals(0.0, store.state.drive.angularVelocityRadiansPerSecond)
            assertNull(store.state.drive.positionLockX)
            val invalidPose = Store(RobotState(drive = DriveState(poseEstimator = PoseEstimatorSnapshot(estimatedPoseHeading = bad))))
            MecanumDriveFacade(invalidPose).driveFieldRelativeNormalized(0.5, 0.7, 0.4, true, true)
            assertEquals(0.0, invalidPose.state.drive.angularVelocityRadiansPerSecond)
        }
        val normal = DriveTuningState()
        for (tuning in listOf(normal.copy(positionHoldMaxOutputLimit = Double.NaN),
            normal.copy(positionHoldMaxOutputLimit = -0.1), normal.copy(positionHoldMaxOutputLimit = 1.1),
            normal.copy(positionHoldDeadzoneMeters = Double.NaN), normal.copy(positionHoldDeadzoneMeters = -0.1),
            normal.copy(positionHoldGains = PIDFCoefficients(Double.NaN, 0.0, 0.0)))) {
            val store = Store(RobotState(drive = DriveState(positionLockX = 0.5, positionLockY = 0.5), tuning = TuningState(drive = tuning)))
            MecanumDriveFacade(store).driveFieldRelativeNormalized(0.0, 0.0, 0.0, usePositionHold = true)
            assertEquals(0.0, store.state.drive.xVelocityMetersPerSecond)
            assertEquals(0.0, store.state.drive.yVelocityMetersPerSecond)
        }
    }

    @Test fun warmCombinedHoldCalculationsAllocateNoPoseOrPreparationWrappers() {
        var commands = 0
        var velocity = 0.0
        val state = RobotState(drive = DriveState(positionLockX = 0.1, positionLockY = 0.1,
            headingLockTargetRadians = 0.1, driveMode = DriveMode.POSITION_HOLD))
        val store = Store(state) { same, action ->
            if (action is RobotAction.JoystickDriveIntent) { commands++; velocity = action.targetXVelocity }
            same
        }
        val facade = MecanumDriveFacade(store)
        repeat(40_000) { facade.driveFieldRelativeNormalized(0.0, 0.0, 0.0, true, true) }
        commands = 0
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        repeat(10_000) { facade.driveFieldRelativeNormalized(0.0, 0.0, 0.0, true, true) }
        val bytes = bean.getThreadAllocatedBytes(id) - before
        assertEquals(10_000, commands)
        assertTrue(velocity > 0.0)
        println("Holonomic facade: " + bytes + " bytes / 10000 combined holds (no-op reducer, desktop JVM)")
        assertTrue(bytes <= 4096L, "Combined holds allocated " + bytes)
    }

    @Test fun pathBindingRejectsEmptyRequestsAndPropagatesSubmissionFailureWithoutPoseReset() {
        val store = Store()
        val facade = MecanumDriveFacade(store)
        var submissions = 0
        val failure = IllegalStateException("scheduler unavailable")
        facade.configurePathFollowing(HolonomicPathFollower(DriveSubsystem(store))) { submissions++; throw failure }
        assertThrows(IllegalArgumentException::class.java) { facade.followPath(Path(emptyList())) }
        assertEquals(0, submissions)
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            facade.followPath(Path(listOf(PathPoint(Pose2d(3.0, 4.0), 1.0))))
        })
        assertEquals(1, submissions)
        assertEquals(Pose2d(), facade.pose)
    }

    @Test fun controllerFactoriesUseRobotClockAndOnlyAdvanceLocalCacheAfterSuccessfulDispatch() {
        class Probe(store: Store) : SubsystemControllerBase(store) {
            var current = 0.0
            fun request(target: Double) = dispatchOnChange(current, target,
                { value, time -> RobotAction.SetHeadingLockTarget(value, time) }, { current = it })
        }
        val store = Store()
        var time = -1L
        store.actionListener = { time = it.timestampMs }
        val probe = Probe(store)
        RobotClock.useMockTime(321L)
        probe.request(-0.0)
        assertEquals(-1L, time)
        probe.request(0.5)
        assertEquals(321L, time)
        assertEquals(0.5, probe.current)
        val failure = IllegalStateException("observer unavailable")
        store.actionListener = { throw failure }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { probe.request(0.7) })
        assertEquals(0.5, probe.current)
        assertEquals(0.5, store.state.drive.headingLockTargetRadians)
    }

    private fun decode(payload: JsonObject): RobotAction.JoystickDriveIntent {
        val envelope = JsonObject().apply {
            addProperty("schema_version", ActionReplay.SCHEMA_VERSION)
            addProperty("type", "JoystickDriveIntent")
            add("payload", payload)
        }
        val file = File(tempDir, "action.jsonl")
        file.writeText(envelope.toString() + "\n")
        return ActionReplay.parseActions(file).single() as RobotAction.JoystickDriveIntent
    }
}
