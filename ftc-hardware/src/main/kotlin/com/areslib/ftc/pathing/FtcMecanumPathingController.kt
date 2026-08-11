package com.areslib.ftc.pathing

import com.areslib.Store
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.pathing.AutoBuilder
import com.areslib.pathing.Costmap
import com.areslib.pathing.FieldWaypointLoader
import com.areslib.pathing.HolonomicPathFollower
import com.areslib.sequencer.PathfindToPoseTask
import com.areslib.state.RobotFieldManager
import com.areslib.state.TuningState
import com.areslib.subsystem.DriveSubsystem
import com.areslib.ftc.drivetrain.MecanumHardwareIO
import com.areslib.ftc.telemetry.FtcTelemetryManager
import com.areslib.util.RobotClock

/**
 * Manages autonomous pathfinding tasks, costmap obstacle maps, and waypoint navigation
 * for FTC mecanum drivetrains.
 */
class FtcMecanumPathingController(
    private val drive: DriveSubsystem
) {
    val pathfindFollower by lazy { HolonomicPathFollower(drive) }

    /**
     * Declarative AutoBuilder facade automatically configured to use this robot's pathfind follower.
     */
    val autoBuilder by lazy { AutoBuilder().configureFollower(pathfindFollower) }

    var activePathfindTask: PathfindToPoseTask? = null
        private set

    private var pathfindStartMs = 0L
    var wasPathfindRequested = false
        private set

    /**
     * Drives the robot to a target pose on the field, automatically generating
     * and following a path around static obstacles.
     */
    @kotlin.jvm.JvmOverloads
    fun driveToPose(
        store: Store,
        mecanumIO: MecanumHardwareIO,
        targetPose: Pose2d,
        isRequested: Boolean,
        mirrorForAlliance: Boolean = true
    ) {
        val now = RobotClock.currentTimeMillis()
        val task = activePathfindTask
        val elapsed = if (task != null) now - pathfindStartMs else 0L

        when {
            isRequested && !wasPathfindRequested -> {
                val config = RobotFieldManager.activeConfig
                val costmap = Costmap.fromFieldConfig(config)

                activePathfindTask = PathfindToPoseTask(
                    targetPose = targetPose,
                    follower = pathfindFollower,
                    costmap = costmap,
                    maxVelocityMps = mecanumIO.maxWheelSpeedMetersPerSecond * store.state.tuning.pathVelocityScale,
                    maxAccelerationMps2 = store.state.tuning.pathAccelerationLimit,
                    mirrorForAlliance = mirrorForAlliance,
                    symmetry = config.allianceSymmetry,
                    authoredAlliance = com.areslib.state.Alliance.RED
                )

                pathfindStartMs = now
                val initActions = activePathfindTask!!.initialize(store.state)
                initActions.forEach { store.dispatch(it) }
                wasPathfindRequested = true
            }
            isRequested && wasPathfindRequested && task != null && task.isCompleted(store.state, elapsed) -> {
                val endActions = task.end(store.state, interrupted = false)
                endActions.forEach { store.dispatch(it) }
                activePathfindTask = null
            }
            isRequested && wasPathfindRequested && task != null -> {
                val execActions = task.execute(store.state, elapsed)
                execActions.forEach { store.dispatch(it) }
            }
            !isRequested && wasPathfindRequested -> {
                if (task != null) {
                    val endActions = task.end(store.state, interrupted = true)
                    endActions.forEach { store.dispatch(it) }
                }
                pathfindFollower.stop()
                activePathfindTask = null
                wasPathfindRequested = false
            }
        }
    }

    /**
     * Drives the robot to a named field waypoint loaded from field_waypoints.json,
     * automatically pathfinding around static obstacles.
     */
    @kotlin.jvm.JvmOverloads
    fun driveToWaypoint(
        store: Store,
        mecanumIO: MecanumHardwareIO,
        telemetryManager: FtcTelemetryManager,
        name: String,
        isRequested: Boolean,
        mirrorForAlliance: Boolean = true
    ) {
        val wp = FieldWaypointLoader.getWaypoint(name)
        if (wp != null) {
            driveToPose(store, mecanumIO, wp.toPose(), isRequested, mirrorForAlliance)
        } else {
            if (isRequested) {
                telemetryManager.customDriverStationText["Error"] = "Waypoint '$name' not found!"
            }
            driveToPose(store, mecanumIO, Pose2d(0.0, 0.0, Rotation2d(0.0)), false, false)
        }
    }

    /**
     * Updates path follower PID controller gains when tuning state changes.
     */
    fun updateTuning(currentTuning: TuningState) {
        if (wasPathfindRequested || activePathfindTask != null) {
            pathfindFollower.xController.p = currentTuning.pathTranslationGains.kP
            pathfindFollower.xController.i = currentTuning.pathTranslationGains.kI
            pathfindFollower.xController.d = currentTuning.pathTranslationGains.kD
            pathfindFollower.yController.p = currentTuning.pathTranslationGains.kP
            pathfindFollower.yController.i = currentTuning.pathTranslationGains.kI
            pathfindFollower.yController.d = currentTuning.pathTranslationGains.kD
            pathfindFollower.thetaController.p = currentTuning.pathRotationGains.kP
            pathfindFollower.thetaController.i = currentTuning.pathRotationGains.kI
            pathfindFollower.thetaController.d = currentTuning.pathRotationGains.kD
        }
    }
}
