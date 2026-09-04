package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Translation2d
import com.areslib.state.RobotState
import com.areslib.pathing.Path
import com.areslib.pathing.PathPlannerParser
import com.areslib.pathing.ThetaStarPlanner
import com.areslib.pathing.Costmap
import com.areslib.pathing.HolonomicPathFollower
import com.areslib.math.coordinate.FieldSymmetry
import com.areslib.state.Alliance

/**
 * Task that dynamically plans a collision-free path around static costmap obstacles
 * using Theta* and follows it to a target Pose2d.
 *
 * The generated [FollowPathTask] delegate owns the real drive lifecycle. This wrapper must
 * preserve the [Task] contract itself — status transitions, timeout origin, callbacks — by
 * calling the default implementations, and must propagate a fail-closed delegate so the
 * executor observes this task as FAILED instead of ticking a wrapper that can never finish.
 */
class PathfindToPoseTask @kotlin.jvm.JvmOverloads constructor(
    private val targetPose: Pose2d,
    private val follower: HolonomicPathFollower,
    private val costmap: Costmap,
    private val maxVelocityMps: Double = 2.0,
    private val maxAccelerationMps2: Double = 1.5,
    private val mirrorForAlliance: Boolean = true,
    private val symmetry: FieldSymmetry = FieldSymmetry.MIRRORED,
    private val authoredAlliance: Alliance = Alliance.BLUE
) : Task {
    override val name = "PathfindToPose($targetPose)"
    override val requiredResources: Long = TaskResources.DRIVE
    private var delegateTask: FollowPathTask? = null

    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        val startPose = state.drive.poseEstimator.estimatedPose
        val shouldTransform = mirrorForAlliance && state.drive.alliance != authoredAlliance
        // AllianceMirroring's RED branch is the involutive geometry operation. Authorship
        // metadata determines whether it is applied, so either alliance may be canonical.
        val transformSelector = if (shouldTransform) Alliance.RED else Alliance.BLUE
        val activeTargetPose = com.areslib.math.coordinate.AllianceMirroring.mirror(
            targetPose,
            transformSelector,
            symmetry
        )

        val startTrans = Translation2d(startPose.x, startPose.y)
        val targetTrans = Translation2d(activeTargetPose.x, activeTargetPose.y)

        // Plan 2D coordinate waypoints using Theta* any-angle pathfinder
        val coordinateWaypoints = ThetaStarPlanner.plan(costmap, startTrans, targetTrans)

        // Ensure we always have at least start and end if pathfind fails or returns direct
        val finalWaypoints = if (coordinateWaypoints.size < 2) {
            listOf(startTrans, targetTrans)
        } else {
            coordinateWaypoints
        }

        // Generate smooth profiled trajectory splines through coordinate joints
        val path = PathPlannerParser.generatePath(
            points = finalWaypoints,
            startHeading = startPose.heading,
            endHeading = activeTargetPose.heading,
            maxVelocityMps = maxVelocityMps,
            maxAccelerationMps2 = maxAccelerationMps2
        )

        // We already mirrored the targetPose and planned in absolute/mirrored space,
        // so we disable mirroring inside the inner FollowPathTask.
        val task = FollowPathTask(follower, path, mirrorForAlliance = false)
        delegateTask = task
        return task.initialize(state)
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        val delegate = delegateTask ?: return true
        val completed = delegate.isCompleted(state, elapsedMs)
        if (!completed && TaskStateMachine.getStatus(delegate) == TaskStatus.FAILED) {
            propagateDelegateFailure(delegate)
        }
        return completed
    }

    override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        super.execute(state, elapsedMs)
        // The default implementation above may have marked this task failed (wrapper timeout);
        // a failed wrapper must not keep producing drive commands.
        if (TaskStateMachine.getStatus(this) == TaskStatus.FAILED) return emptyList()
        val delegate = delegateTask ?: return emptyList()
        val actions = delegate.execute(state, elapsedMs)
        if (TaskStateMachine.getStatus(delegate) == TaskStatus.FAILED) {
            propagateDelegateFailure(delegate)
        }
        return actions
    }

    /** Stops drive output while a higher-priority task owns the drivetrain. */
    internal fun suspendTimeouts(paused: Boolean) {
        delegateTask?.setTimeoutSuspended(paused)
    }

    override fun pause(state: RobotState): List<RobotAction> =
        delegateTask?.pause(state) ?: emptyList()

    override fun resume(state: RobotState): List<RobotAction> =
        delegateTask?.resume(state) ?: emptyList()

    override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        // Delegate cleanup runs first so a throwing cleanup still leaves this wrapper
        // eligible for a terminal status via the default implementation.
        val delegateActions = delegateTask?.end(state, interrupted) ?: emptyList()
        return delegateActions + super.end(state, interrupted)
    }

    private fun propagateDelegateFailure(delegate: Task) {
        if (TaskStateMachine.markFailed(this)) {
            System.err.println("PathfindToPoseTask: delegate ${delegate.name} failed")
            try {
                TaskCallbacks.invokeFail(this)
            } catch (e: Exception) {
                System.err.println("PathfindToPoseTask: Exception during failure callback: ${e.message}")
            }
        }
    }
}
