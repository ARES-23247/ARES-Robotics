package com.areslib.ftc.drivetrain

import com.areslib.Store
import com.areslib.ftc.telemetry.FtcTelemetryManager
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
import com.areslib.util.RobotClock

/**
 * Autonomous path planning, trajectory generation, and obstacle avoidance module for FTC Mecanum Robots.
 *
 * Encapsulates a [HolonomicPathFollower], an [AutoBuilder] trajectory generator, and costmap-based [PathfindToPoseTask]
 * pathfinders for dynamic real-time obstacle avoidance.
 *
 * ### Mathematical Formulations & Coordinate Conventions:
 * - **Field Reference Frame**: Origin $(0, 0)$ at field center. $+X$ forward, $+Y$ left.
 * - **Heading**: Radians ($rad$), **CCW-positive** standard ($0 = +X$, $\pi/2 = +Y$).
 * - **Velocities & Limits**: Scaled by tuning parameters `pathVelocityScale` and acceleration limit `pathAccelerationLimit` ($m/s, m/s^2$).
 *
 * ### Zero-GC Guarantee:
 * Once [activePathfindTask] is initialized, trajectory evaluation loops execute with zero dynamic heap allocations.
 *
 * @param drive Drive subsystem reference for motion commands.
 *
 * @see HolonomicPathFollower
 * @see PathfindToPoseTask
 * @see AutoBuilder
 */
class MecanumTrajectoryFollower(
    private val drive: DriveSubsystem
) {
    /** Lazy-initialized holonomic path follower instance. */
    val pathfindFollower by lazy { HolonomicPathFollower(drive) }

    /** Autonomous trajectory builder instance for path creation. */
    val autoBuilder by lazy { AutoBuilder().configureFollower(pathfindFollower) }

    /** Active pathfinding task instance (or `null` if idle). */
    var activePathfindTask: PathfindToPoseTask? = null
        private set

    private var pathfindStartMs = 0L

    /** Status flag indicating whether pathfinding was requested in the previous loop frame. */
    var wasPathfindRequested = false
        private set

    /**
     * Navigates the robot to a specified target pose, constructing a costmap pathfinder task if needed.
     *
     * @param store Redux state store reference.
     * @param mecanumIO Drivetrain hardware IO cluster.
     * @param targetPose Destination pose $(x, y, \theta)$ ($m, m, rad$).
     * @param isRequested Flag indicating whether trajectory execution is actively requested.
     * @param mirrorForAlliance Applies the active season field's symmetry when Blue uses a Red-authored target.
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
     * Navigates the robot to a named waypoint loaded from autonomous field configuration.
     *
     * @param store Redux state store reference.
     * @param mecanumIO Drivetrain hardware IO cluster.
     * @param telemetryManager Telemetry manager for driver station error messages.
     * @param name Named waypoint identifier string.
     * @param isRequested Flag indicating whether waypoint navigation is requested.
     * @param mirrorForAlliance Applies the active season field's symmetry when Blue uses a Red-authored waypoint.
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
                telemetryManager.customDriverStationText["Error"] = "Waypoint '${name}' not found!"
            }
            driveToPose(store, mecanumIO, Pose2d(0.0, 0.0, Rotation2d(0.0)), false, false)
        }
    }

    /**
     * Updates PID controller gain values across translational and rotational path controllers from [TuningState].
     *
     * @param currentTuning Desired tuning parameters snapshot from Redux state.
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
