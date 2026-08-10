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

/**
 * Task that dynamically plans a collision-free path around static costmap obstacles
 * using Theta* and follows it to a target Pose2d.
 */
class PathfindToPoseTask @kotlin.jvm.JvmOverloads constructor(
    private val targetPose: Pose2d,
    private val follower: HolonomicPathFollower,
    private val costmap: Costmap,
    private val maxVelocityMps: Double = 2.0,
    private val maxAccelerationMps2: Double = 1.5,
    private val mirrorForAlliance: Boolean = true
) : Task {
    override val name = "PathfindToPose($targetPose)"
    private var delegateTask: FollowPathTask? = null

    override fun initialize(state: RobotState): List<RobotAction> {
        val startPose = state.drive.poseEstimator.estimatedPose
        val alliance = if (mirrorForAlliance) state.drive.alliance else com.areslib.state.Alliance.BLUE
        val activeTargetPose = com.areslib.math.coordinate.AllianceMirroring.mirror(targetPose, alliance, com.areslib.math.coordinate.FieldSymmetry.MIRRORED)

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
        return delegateTask?.isCompleted(state, elapsedMs) ?: true
    }

    override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        return delegateTask?.execute(state, elapsedMs) ?: emptyList()
    }

    override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        return delegateTask?.end(state, interrupted) ?: emptyList()
    }
}
