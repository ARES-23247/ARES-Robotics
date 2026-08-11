package com.areslib.sequencer

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.pathing.HolonomicPathFollower
import com.areslib.pathing.Path
import com.areslib.pathing.PathPoint
import com.areslib.state.RobotState
import com.areslib.subsystem.DrivetrainSubsystem
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FollowPathFailureTest {
    private class RecordingDrivetrain : DrivetrainSubsystem {
        var stopCalls = 0

        override fun setChassisSpeeds(vx: Double, vy: Double, omega: Double) {
            if (vx == 0.0 && vy == 0.0 && omega == 0.0) stopCalls++
        }

        override fun getEstimatedPose(): Pose2d = Pose2d()
        override fun readSensors(store: Store, timestampMs: Long) = Unit
        override fun writeOutputs(state: RobotState, scale: Double) = Unit
        override fun close() = Unit
    }

    private class InitializationProbe : Task {
        override val name = "must-not-run"
        var initialized = false

        override fun initialize(state: RobotState): List<RobotAction> {
            initialized = true
            return super.initialize(state)
        }

        override fun isCompleted(state: RobotState, elapsedMs: Long) = true
    }

    @Test
    fun `empty path fails and interrupts sequence without initializing next task`() {
        val drivetrain = RecordingDrivetrain()
        val pathTask = FollowPathTask(HolonomicPathFollower(drivetrain), Path(emptyList()))
        val nextTask = InitializationProbe()
        val queuedAfterSequence = InitializationProbe()
        val sequence = SequentialTaskGroup(listOf(pathTask, nextTask))
        val executor = TaskExecutor()
        executor.addTask(sequence)
        executor.addTask(queuedAfterSequence)

        executor.update(RobotState(), 0L)
        executor.update(RobotState(), 20L)

        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(pathTask))
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(sequence))
        assertFalse(nextTask.initialized)
        assertFalse(queuedAfterSequence.initialized)
        assertEquals(0, executor.size)
        assertTrue(drivetrain.stopCalls > 0, "failed path must interrupt and stop the follower")
    }

    @Test
    fun `blocked path times out as failure instead of successful completion`() {
        val drivetrain = RecordingDrivetrain()
        val path = Path(
            listOf(
                PathPoint(Pose2d(), 0.0, distanceMeters = 0.0),
                PathPoint(Pose2d(2.0, 0.0, Rotation2d()), 0.0, distanceMeters = 2.0)
            )
        )
        val pathTask = FollowPathTask(
            follower = HolonomicPathFollower(drivetrain),
            path = path,
            mirrorForAlliance = false
        )
        val queuedAfterPath = InitializationProbe()
        val executor = TaskExecutor()
        executor.addTask(pathTask)
        executor.addTask(queuedAfterPath)

        executor.update(RobotState(), 0L)
        executor.update(RobotState(), 15_000L)
        executor.update(RobotState(), 15_020L)

        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(pathTask))
        assertFalse(queuedAfterPath.initialized)
        assertEquals(0, executor.size)
        assertTrue(drivetrain.stopCalls > 0, "timed-out path must be interrupted and stopped")
    }
}
