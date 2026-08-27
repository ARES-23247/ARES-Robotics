package com.areslib.sequencer

import com.areslib.Store
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import com.areslib.math.estimation.PoseEstimatorSnapshot
import com.areslib.pathing.Costmap
import com.areslib.pathing.HolonomicPathFollower
import com.areslib.state.Alliance
import com.areslib.state.DriveState
import com.areslib.state.PathState
import com.areslib.state.RobotState
import com.areslib.subsystem.DrivetrainSubsystem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Lifecycle contract coverage for [PathfindToPoseTask]: the wrapper must preserve the default
 * [Task] status/timeout/callback behavior and must propagate a fail-closed delegate instead of
 * leaving the executor ticking a wrapper that can never complete.
 */
class PathfindToPoseTaskLifecycleTest {
    private val drivetrain = RecordingDrivetrain()
    private val follower = HolonomicPathFollower(drivetrain)
    private val costmap = Costmap(6.0, 6.0, 0.1, Translation2d(-3.0, -3.0))
    private val tasks = mutableListOf<Task>()

    @AfterEach
    fun tearDown() {
        tasks.forEach { it.reset() }
    }

    private fun newTask(): PathfindToPoseTask = PathfindToPoseTask(
        targetPose = Pose2d(1.0, 2.0, Rotation2d(0.25)),
        follower = follower,
        costmap = costmap,
        mirrorForAlliance = false
    ).also { tasks.add(it) }

    @Test
    fun `initialize marks the wrapper and delegate running`() {
        val task = newTask()
        task.initialize(RobotState())
        assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(task))
    }

    @Test
    fun `wrapper timeout fails the task and invokes the fail callback`() {
        val task = newTask()
        var failInvoked = false
        task.withTimeout(0L).onFail { failInvoked = true }

        val executor = TaskExecutor()
        executor.addTask(task)
        com.areslib.util.RobotClock.useMockTime(0L)
        try {
            executor.update(RobotState(), 0L)
            executor.update(RobotState(), 1L)
        } finally {
            com.areslib.util.RobotClock.useSystemTime()
        }

        assertTrue(failInvoked, "withTimeout must be honored by the wrapper, not silently bypassed")
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
        assertEquals(0, executor.size, "executor must drop the failed task instead of ticking forever")
    }

    @Test
    fun `delegate path failure propagates to the wrapper`() {
        val task = newTask()
        task.initialize(RobotState())
        var failInvoked = false
        task.onFail { failInvoked = true }

        // Far target, robot stuck at origin, elapsed past FollowPathTask's 15 s bound:
        // the delegate fail-closes and the wrapper must follow, not run forever.
        val completed = task.isCompleted(RobotState(), elapsedMs = 16_000L)

        assertFalse(completed)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
        assertTrue(failInvoked, "failure callback must fire on the wrapper, not only the delegate")
    }

    @Test
    fun `reaching the target completes the wrapper and ends the delegate`() {
        val task = newTask()
        var completeInvoked = false
        task.onComplete { completeInvoked = true }
        task.initialize(RobotState())

        val target = Pose2d(1.0, 2.0, Rotation2d(0.25))
        val atTarget = RobotState(
            drive = DriveState(
                poseEstimator = PoseEstimatorSnapshot(
                    estimatedPoseX = target.x,
                    estimatedPoseY = target.y,
                    estimatedPoseHeading = target.heading.radians
                )
            ),
            pathState = PathState(currentDistanceMeters = 10_000.0)
        )
        assertTrue(task.isCompleted(atTarget, elapsedMs = 500L))

        task.end(atTarget, interrupted = false)
        assertEquals(TaskStatus.COMPLETED, TaskStateMachine.getStatus(task))
        assertTrue(completeInvoked)
        // The delegate's interrupted-free end still halts the follower (holdVelocity = false).
        assertEquals(Triple(0.0, 0.0, 0.0), drivetrain.commands.last())
    }

    @Test
    fun `pause forwards to the delegate so drive output stops while preempted`() {
        val task = newTask()
        task.initialize(RobotState())
        drivetrain.commands.clear()

        task.pause(RobotState())

        assertEquals(listOf(Triple(0.0, 0.0, 0.0)), drivetrain.commands)
    }

    private class RecordingDrivetrain : DrivetrainSubsystem {
        val commands = mutableListOf<Triple<Double, Double, Double>>()
        override fun setChassisSpeeds(vx: Double, vy: Double, omega: Double) {
            commands.add(Triple(vx, vy, omega))
        }
        override fun getEstimatedPose(): Pose2d = Pose2d()
        override fun readSensors(store: Store, timestampMs: Long) = Unit
        override fun writeOutputs(state: RobotState, scale: Double) = Unit
    }
}
