package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Translation2d
import com.areslib.state.RobotState
import com.areslib.pathing.Path
import com.areslib.pathing.PathPoint
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
 * Initialization neutralizes before planning, rejects nonfinite/nonpositive profile limits and
 * preserves failures through cleanup. End the prior lifecycle before initializing again; reset
 * and cancel release metadata but do not replace the owner's duty to call end. Only RUNNING
 * tasks may advance a delegate. Geometry uses field meters and CCW-positive radians; planning
 * allocates and must not be repeatedly invoked from a held request.
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
        try {
            return initializePath(state)
        } catch (failure: Throwable) {
            failAndRethrow(failure)
        }
    }

    private fun initializePath(state: RobotState): List<RobotAction> {
        check(delegateTask == null) { "End the previous path task before reinitializing" }
        super.initialize(state)
        // Validation or planning can fail before FollowPathTask exists to own neutralization.
        follower.stop()
        require(maxVelocityMps.isFinite() && maxVelocityMps > 0.0 &&
            maxAccelerationMps2.isFinite() && maxAccelerationMps2 > 0.0) {
            "Path velocity and acceleration limits must be finite and positive"
        }
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

        if (coordinateWaypoints.size < 2) {
            // An empty plan means blocked/invalid endpoints or no route. A straight-line
            // substitute would turn a planner rejection into motion through the obstacle.
            val newlyFailed = TaskStateMachine.markFailed(this)
            TaskTimeoutManager.reset(this)
            if (newlyFailed) TaskCallbacks.invokeFail(this)
            return emptyList()
        }

        // Generate smooth profiled trajectory splines through coordinate joints
        val path = if (startTrans.x == targetTrans.x && startTrans.y == targetTrans.y) {
            // Spatial spline progress cannot interpolate a heading over zero travel distance.
            // Keep an owning target sample so the follower can turn until heading tolerance is met.
            Path(listOf(PathPoint(Pose2d(activeTargetPose.x, activeTargetPose.y, activeTargetPose.heading), 0.0)))
        } else PathPlannerParser.generatePath(
            points = coordinateWaypoints,
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
        if (TaskStateMachine.getStatus(this) != TaskStatus.RUNNING) return false
        try {
            val delegate = delegateTask ?: return false
            val completed = delegate.completionReady(state, elapsedMs)
            if (!completed && TaskStateMachine.getStatus(delegate) == TaskStatus.FAILED) {
                propagateDelegateFailure(delegate)
            }
            return completed
        } catch (failure: Throwable) {
            failAndRethrow(failure)
        }
    }

    override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        try {
            if (TaskStateMachine.getStatus(this) != TaskStatus.RUNNING) {
                follower.stop()
                return emptyList()
            }
            super.execute(state, elapsedMs)
            if (TaskStateMachine.getStatus(this) != TaskStatus.RUNNING) {
                follower.stop()
                return emptyList()
            }
            val delegate = delegateTask ?: return emptyList()
            val actions = delegate.execute(state, elapsedMs)
            if (TaskStateMachine.getStatus(delegate) == TaskStatus.FAILED) {
                propagateDelegateFailure(delegate)
            }
            return actions
        } catch (failure: Throwable) {
            failAndRethrow(failure)
        }
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
        var failure: Throwable? = null
        var delegateActions: List<RobotAction> = emptyList()
        val delegate = delegateTask
        delegateTask = null
        try {
            if (delegate == null) follower.stop()
            else delegateActions = delegate.end(state, interrupted)
        }
        catch (error: Throwable) {
            TaskStateMachine.markFailed(this)
            failure = error
        }
        try { delegate?.releaseRuntimeState() }
        catch (error: Throwable) {
            TaskStateMachine.markFailed(this)
            if (failure == null) failure = error else if (error !== failure) failure.addSuppressed(error)
        }
        if (delegate != null && TaskStateMachine.getStatus(delegate) == TaskStatus.FAILED) {
            propagateDelegateFailure(delegate)
        }
        var ownActions: List<RobotAction> = emptyList()
        try { ownActions = super.end(state, interrupted) }
        catch (error: Throwable) {
            if (failure == null) failure = error else if (error !== failure) failure.addSuppressed(error)
        }
        failure?.let { throw it }
        return if (ownActions.isEmpty()) delegateActions else delegateActions + ownActions
    }

    private fun failAndRethrow(failure: Throwable): Nothing {
        val newlyFailed = TaskStateMachine.markFailed(this)
        TaskTimeoutManager.reset(this)
        try { follower.stop() }
        catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
        try { if (newlyFailed) TaskCallbacks.invokeFail(this) }
        catch (callback: Throwable) { if (callback !== failure) failure.addSuppressed(callback) }
        finally { TaskCallbacks.reset(this) }
        throw failure
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
