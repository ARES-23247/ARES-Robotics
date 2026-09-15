package org.aresfirst.starter.frc

import com.areslib.action.RobotAction
import com.areslib.math.estimation.PoseEstimatorSnapshot
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.sequencer.TaskStateMachine
import com.areslib.sequencer.TaskStatus
import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import org.aresfirst.starter.frc.generated.drivebase.GeneratedAresDrivebaseConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.hypot

class StarterPoseControllerAuditTest {
    private fun feedback(time: Long, x: Double = 0.0, y: Double = 0.0, heading: Double = 0.0): RobotState {
        val state = RobotState()
        return state.copy(drive = state.drive.copy(measuredMotionValid = true,
            poseEstimator = PoseEstimatorSnapshot(estimatedPoseX = x, estimatedPoseY = y,
                estimatedPoseHeading = heading, lastObservationTimestampMs = time)))
    }

    private inline fun withTask(target: Pose2d = Pose2d(100.0, 100.0), block: (StarterFrcDriveToPoseTask) -> Unit) {
        val wasMocked = RobotClock.isMocked
        val previous = RobotClock.currentTimeMillis()
        RobotClock.useMockTime(1000L)
        val task = StarterFrcDriveToPoseTask(target, StarterFrcMotionPreset.FAST)
        try { task.initialize(feedback(1000L)); block(task) } finally {
            task.reset()
            if (wasMocked) RobotClock.useMockTime(previous) else RobotClock.useSystemTime()
        }
    }

    private fun output(task: StarterFrcDriveToPoseTask, state: RobotState, now: Long, elapsed: Long = now - 1000L): RobotAction.JoystickDriveIntent {
        RobotClock.useMockTime(now)
        return task.execute(state, elapsed).single() as RobotAction.JoystickDriveIntent
    }

    private fun assertNeutral(command: RobotAction.JoystickDriveIntent) {
        assertEquals(0.0, command.targetXVelocity)
        assertEquals(0.0, command.targetYVelocity)
        assertEquals(0.0, command.targetAngularVelocity)
    }

    @Test fun `diagonal speed is bounded by the linear speed envelope`() = withTask { task ->
        val state = feedback(1000L)
        val limit = GeneratedAresDrivebaseConfig.MAX_LINEAR_SPEED_METERS_PER_SECOND *
            StarterFrcMotionPreset.FAST.speedScale * state.tuning.drive.pathVelocityScale
        repeat(100) { index ->
            val now = 1000L + index * 20L
            val command = output(task, feedback(now), now)
            assertTrue(hypot(command.targetXVelocity, command.targetYVelocity) <= limit + 1e-12)
        }
    }

    @Test fun `lowering velocity scale applies the new envelope immediately`() = withTask(Pose2d(100.0, 0.0)) { task ->
        repeat(20) { index -> val now = 1000L + index * 20L; output(task, feedback(now), now) }
        val state = feedback(1400L)
        val reduced = state.copy(tuning = state.tuning.copy(drive = state.tuning.drive.copy(pathVelocityScale = 0.01)))
        val command = output(task, reduced, 1400L)
        val limit = GeneratedAresDrivebaseConfig.MAX_LINEAR_SPEED_METERS_PER_SECOND * 0.85 * 0.01
        assertTrue(hypot(command.targetXVelocity, command.targetYVelocity) <= limit + 1e-12)
    }

    @Test fun `zero velocity scale immediately neutralizes a moving controller`() = withTask { task ->
        repeat(10) { index -> val now = 1000L + index * 20L; output(task, feedback(now), now) }
        val state = feedback(1200L)
        assertNeutral(output(task, state.copy(tuning = state.tuning.copy(drive = state.tuning.drive.copy(pathVelocityScale = 0.0))), 1200L))
    }

    @Test fun `repeated timestamps cannot ramp the command`() = withTask { task ->
        val state = feedback(1000L)
        val first = output(task, state, 1000L).copy()
        repeat(20) {
            val command = output(task, state, 1000L)
            assertEquals(first.targetXVelocity, command.targetXVelocity)
            assertEquals(first.targetYVelocity, command.targetYVelocity)
        }
    }

    @Test fun `clock rewind fails and neutralizes rather than inventing another frame`() = withTask { task ->
        output(task, feedback(1000L), 1000L)
        assertNeutral(output(task, feedback(990L), 990L, 0L))
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
    }

    @Test fun `one feedback observation cannot count as three settled samples`() = withTask(Pose2d()) { task ->
        val state = feedback(1000L)
        repeat(4) { assertFalse(task.isCompleted(state, 0L)) }
    }

    @Test fun `stale third settled observation cannot complete before execute checks freshness`() = withTask(Pose2d()) { task ->
        assertFalse(task.isCompleted(feedback(1000L), 0L))
        RobotClock.useMockTime(1020L)
        assertFalse(task.isCompleted(feedback(1020L), 20L))
        RobotClock.useMockTime(2000L)
        assertFalse(task.isCompleted(feedback(1040L), 1000L))
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
    }

    @Test fun `nonfinite raw heading cannot masquerade as zero rotation`() = withTask { task ->
        assertNeutral(output(task, feedback(1000L, heading = Double.NaN), 1000L))
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
    }

    @Test fun `invalid PID result fails the complete controller instead of moving the other axis`() = withTask { task ->
        val state = feedback(1000L, x = Double.MAX_VALUE, y = 0.0)
        assertNeutral(output(task, state, 1000L))
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
    }

    @Test fun `nonfinite velocity scale cannot disable PID output bounds`() = withTask { task ->
        val state = feedback(1000L)
        val invalid = state.copy(tuning = state.tuning.copy(drive = state.tuning.drive.copy(pathVelocityScale = Double.NaN)))
        assertNeutral(output(task, invalid, 1000L))
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
    }

    @Test fun `preemption emits neutral and resets the resume ramp`() = withTask { task ->
        repeat(20) { index -> val now = 1000L + index * 20L; output(task, feedback(now), now) }
        val cleanup = task.pause(feedback(1380L)).single() as RobotAction.JoystickDriveIntent
        assertNeutral(cleanup)
        task.resume(feedback(1400L))
        val resumed = output(task, feedback(1400L), 1400L)
        assertTrue(hypot(resumed.targetXVelocity, resumed.targetYVelocity) <= 0.060000000001)
    }

    @Test fun `expired direct execution cannot append nonzero output after super marks failure`() = withTask { task ->
        assertNeutral(output(task, feedback(11001L), 11001L, 10001L))
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
    }

    @Test fun `reused task retains its configured timeout`() = withTask { task ->
        task.withTimeout(40L)
        task.end(feedback(1000L), interrupted = true)
        task.releaseRuntimeState()
        RobotClock.useMockTime(2000L)
        task.initialize(feedback(2000L))
        assertNeutral(output(task, feedback(2060L), 2060L, 60L))
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
    }

    @Test fun `invalid target coordinates and raw heading reject construction`() {
        for (target in listOf(Pose2d(Double.NaN, 0.0), Pose2d(0.0, Double.POSITIVE_INFINITY),
            Pose2d(0.0, 0.0, Rotation2d(Double.NaN)))) {
            assertThrows(IllegalArgumentException::class.java) { StarterFrcDriveToPoseTask(target, StarterFrcMotionPreset.SAFE) }
        }
    }

    @Test fun `three distinct valid observations complete while a gap outside tolerance resets settling`() = withTask(Pose2d()) { task ->
        assertFalse(task.isCompleted(feedback(1000L), 0L))
        RobotClock.useMockTime(1020L)
        assertFalse(task.isCompleted(feedback(1020L, x = 0.1), 20L))
        for (now in listOf(1040L, 1060L)) {
            RobotClock.useMockTime(now)
            assertFalse(task.isCompleted(feedback(now), now - 1000L))
        }
        RobotClock.useMockTime(1080L)
        assertTrue(task.isCompleted(feedback(1080L), 80L))
    }

    @Test fun `invalid feedback variants neutralize and cannot recover without reinitialization`() {
        val base = feedback(1000L)
        val states = listOf(feedback(-1L), feedback(1020L), feedback(0L),
            feedback(1000L, x = Double.NaN), feedback(1000L, y = Double.POSITIVE_INFINITY),
            feedback(1000L, heading = Double.NEGATIVE_INFINITY),
            base.copy(drive = base.drive.copy(measuredMotionValid = false)))
        for (invalid in states) withTask { task ->
            assertNeutral(output(task, invalid, 1000L))
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
            assertNeutral(output(task, feedback(1020L), 1020L))
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
        }
    }

    @Test fun `invalid acceleration and coefficient settings fail before producing motion`() {
        val base = feedback(1000L)
        val drive = base.tuning.drive
        val settings = listOf(drive.copy(pathAccelerationLimit = Double.NaN),
            drive.copy(pathAccelerationLimit = Double.POSITIVE_INFINITY), drive.copy(pathAccelerationLimit = 0.0),
            drive.copy(pathTranslationGains = drive.pathTranslationGains.copy(kI = Double.NaN)),
            drive.copy(pathRotationGains = drive.pathRotationGains.copy(kD = Double.POSITIVE_INFINITY)))
        for (setting in settings) withTask { task ->
            assertNeutral(output(task, base.copy(tuning = base.tuning.copy(drive = setting)), 1000L))
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
        }
    }

    @Test fun `heading follows the short signed arc and large finite angles stay bounded`() {
        withTask(Pose2d(100.0, 0.0, Rotation2d(-Math.PI + 0.1))) { task ->
            val command = output(task, feedback(1000L, heading = Math.PI - 0.1), 1000L)
            assertTrue(command.targetAngularVelocity > 0.0)
        }
        withTask(Pose2d(100.0, 0.0, Rotation2d(1e300))) { task ->
            val command = output(task, feedback(1000L, heading = -1e300), 1000L)
            assertTrue(command.targetAngularVelocity.isFinite())
            assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(task))
        }
    }

    @Test fun `variable feedback respects vector and angular acceleration throughout the ramp`() = withTask(Pose2d()) { task ->
        val random = java.util.Random(267L)
        var previousX = 0.0
        var previousY = 0.0
        var previousOmega = 0.0
        repeat(600) { index ->
            val now = 1000L + index * 10L
            val state = feedback(now, random.nextDouble() * 2.0 - 1.0, random.nextDouble() * 2.0 - 1.0,
                random.nextDouble() * 2.0 * Math.PI - Math.PI)
            val command = output(task, state, now)
            val dt = if (index == 0) 0.02 else 0.01
            val acceleration = state.tuning.drive.pathAccelerationLimit
            val ratio = GeneratedAresDrivebaseConfig.MAX_ANGULAR_SPEED_RADIANS_PER_SECOND /
                GeneratedAresDrivebaseConfig.MAX_LINEAR_SPEED_METERS_PER_SECOND
            assertTrue(hypot(command.targetXVelocity - previousX, command.targetYVelocity - previousY) <= acceleration * dt + 1e-12)
            assertTrue(kotlin.math.abs(command.targetAngularVelocity - previousOmega) <= acceleration * ratio * dt + 1e-12)
            assertTrue(hypot(command.targetXVelocity, command.targetYVelocity) <=
                GeneratedAresDrivebaseConfig.MAX_LINEAR_SPEED_METERS_PER_SECOND * 0.85 * state.tuning.drive.pathVelocityScale + 1e-12)
            previousX = command.targetXVelocity
            previousY = command.targetYVelocity
            previousOmega = command.targetAngularVelocity
        }
    }

    @Test fun `timestamp zero is valid and reducing angular scale also applies on a repeated frame`() = withTask(Pose2d(100.0, 0.0, Rotation2d(1.0))) { task ->
        task.end(feedback(1000L), interrupted = true)
        task.releaseRuntimeState()
        RobotClock.useMockTime(0L)
        task.initialize(feedback(0L))
        assertTrue(output(task, feedback(0L), 0L, 0L).targetAngularVelocity > 0.0)
        val atTwenty = output(task, feedback(20L), 20L, 20L).copy()
        val state = feedback(20L)
        val reduced = state.copy(tuning = state.tuning.copy(drive = state.tuning.drive.copy(pathVelocityScale = 0.001)))
        val command = output(task, reduced, 20L, 20L)
        assertTrue(command.targetAngularVelocity < atTwenty.targetAngularVelocity)
        assertTrue(kotlin.math.abs(command.targetAngularVelocity) <=
            GeneratedAresDrivebaseConfig.MAX_ANGULAR_SPEED_RADIANS_PER_SECOND * 0.85 * 0.001 + 1e-12)
    }
}
