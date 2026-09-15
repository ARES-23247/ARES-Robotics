package com.areslib.ftc.drivetrain

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.pathing.FieldWaypoint
import com.areslib.sequencer.TaskStateMachine
import com.areslib.sequencer.TaskStatus
import com.areslib.sequencer.TaskTimeoutManager
import com.areslib.ftc.telemetry.FtcTelemetryManager
import com.areslib.math.geometry.Pose2d
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
 * Calls belong to one robot loop. Planning and file reloads allocate on explicit request edges;
 * held waypoint lookups reuse their pose. The delegated follower and immutable Redux actions
 * can still allocate during motion. Failed requests require release before another planning attempt.
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
    private val idlePose = Pose2d()
    private var cachedWaypoint: FieldWaypoint? = null
    private var cachedWaypointPose = idlePose
    private var missingWaypointName: String? = null
    private var missingWaypointMessage = ""

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
        if (!isRequested) {
            wasPathfindRequested = false
            activePathfindTask?.let { finishTask(store, it, interrupted = true) }
            return
        }
        if (wasPathfindRequested && activePathfindTask == null) return

        val now = RobotClock.currentTimeMillis()
        try {
            if (!wasPathfindRequested) {
                // Latch before planning or dispatch: a throwing request must not restart every frame.
                wasPathfindRequested = true
                val config = RobotFieldManager.activeConfig
                val task = PathfindToPoseTask(
                    targetPose = targetPose,
                    follower = pathfindFollower,
                    costmap = Costmap.fromFieldConfig(config),
                    maxVelocityMps = mecanumIO.maxWheelSpeedMetersPerSecond * store.state.tuning.drive.pathVelocityScale,
                    maxAccelerationMps2 = store.state.tuning.drive.pathAccelerationLimit,
                    mirrorForAlliance = mirrorForAlliance,
                    symmetry = config.allianceSymmetry,
                    authoredAlliance = com.areslib.state.Alliance.RED
                )
                activePathfindTask = task
                pathfindStartMs = now
                dispatch(store, task.initialize(store.state))
                if (TaskStateMachine.getStatus(task) != TaskStatus.RUNNING) {
                    finishTask(store, task, interrupted = false)
                }
                return
            }
            val task = activePathfindTask ?: return
            val elapsed = now - pathfindStartMs
            if (now < pathfindStartMs || elapsed < 0L || TaskTimeoutManager.isTimedOut(task, elapsed)) {
                TaskStateMachine.markFailed(task)
            }
            val completed = TaskStateMachine.getStatus(task) == TaskStatus.RUNNING &&
                task.isCompleted(store.state, elapsed)
            if (completed || TaskStateMachine.getStatus(task) != TaskStatus.RUNNING) {
                finishTask(store, task, interrupted = false)
            } else {
                dispatch(store, task.execute(store.state, elapsed))
                if (TaskStateMachine.getStatus(task) != TaskStatus.RUNNING) {
                    finishTask(store, task, interrupted = false)
                }
            }
        } catch (failure: Throwable) {
            val task = activePathfindTask
            if (task != null) TaskStateMachine.markFailed(task)
            try {
                if (task != null) finishTask(store, task, interrupted = true)
                else pathfindFollower.stop()
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    private fun dispatch(store: Store, actions: List<RobotAction>) {
        var index = 0
        while (index < actions.size) store.dispatch(actions[index++])
    }

    private fun finishTask(store: Store, task: PathfindToPoseTask, interrupted: Boolean) {
        activePathfindTask = null
        var failure: Throwable? = null
        try { dispatch(store, task.end(store.state, interrupted)) }
        catch (error: Throwable) { failure = error }
        try { task.releaseRuntimeState() }
        catch (error: Throwable) {
            if (failure == null) failure = error else if (error !== failure) failure.addSuppressed(error)
        }
        try { pathfindFollower.stop() }
        catch (error: Throwable) {
            if (failure == null) failure = error else if (error !== failure) failure.addSuppressed(error)
        }
        failure?.let { throw it }
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
        if (!isRequested) {
            driveToPose(store, mecanumIO, idlePose, false, mirrorForAlliance)
            return
        }
        val wp = FieldWaypointLoader.getWaypoint(name)
        if (wp != null) {
            if (cachedWaypoint !== wp) {
                cachedWaypoint = wp
                cachedWaypointPose = wp.toPose()
            }
            driveToPose(store, mecanumIO, cachedWaypointPose, true, mirrorForAlliance)
        } else {
            if (missingWaypointName != name) {
                missingWaypointName = name
                missingWaypointMessage = "Waypoint '$name' not found!"
            }
            if (telemetryManager.customDriverStationText["Error"] != missingWaypointMessage) {
                telemetryManager.customDriverStationText["Error"] = missingWaypointMessage
            }
            driveToPose(store, mecanumIO, idlePose, false, false)
        }
    }

    /**
     * Updates PID controller gain values across translational and rotational path controllers from [TuningState].
     *
     * @param currentTuning Desired tuning parameters snapshot from Redux state.
     */
    fun updateTuning(currentTuning: TuningState) {
        if (wasPathfindRequested || activePathfindTask != null) {
            pathfindFollower.xController.p = currentTuning.drive.pathTranslationGains.kP
            pathfindFollower.xController.i = currentTuning.drive.pathTranslationGains.kI
            pathfindFollower.xController.d = currentTuning.drive.pathTranslationGains.kD
            pathfindFollower.yController.p = currentTuning.drive.pathTranslationGains.kP
            pathfindFollower.yController.i = currentTuning.drive.pathTranslationGains.kI
            pathfindFollower.yController.d = currentTuning.drive.pathTranslationGains.kD
            pathfindFollower.thetaController.p = currentTuning.drive.pathRotationGains.kP
            pathfindFollower.thetaController.i = currentTuning.drive.pathRotationGains.kI
            pathfindFollower.thetaController.d = currentTuning.drive.pathRotationGains.kD
        }
    }
}
