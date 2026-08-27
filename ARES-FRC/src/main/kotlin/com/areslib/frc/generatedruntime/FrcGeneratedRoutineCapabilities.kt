package com.areslib.frc.generatedruntime

import com.areslib.action.RobotAction
import com.areslib.frc.FrcSwerveRobot
import com.areslib.frc.generated.GeneratedAresProjectCapabilities
import com.areslib.frc.marvin.MarvinConfig
import com.areslib.frc.robot.FrcAutoCapabilities
import com.areslib.math.coordinate.AllianceMirroring
import com.areslib.math.coordinate.CoordinateTransformers
import com.areslib.math.coordinate.FieldOrigin
import com.areslib.math.coordinate.FieldSymmetry
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.pathing.CommandKey
import com.areslib.pathing.DriveModel
import com.areslib.pathing.HolonomicPathFollower
import com.areslib.pathing.JerkLimitedTrajectoryProvider
import com.areslib.pathing.NamedCommands
import com.areslib.pathing.TimedTrajectoryEvent
import com.areslib.pathing.TrajectoryEngine
import com.areslib.pathing.TrajectoryLimits
import com.areslib.pathing.TrajectoryPlanner
import com.areslib.pathing.TrajectoryPreset
import com.areslib.pathing.TrajectoryRequest
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineAlliance
import com.areslib.routine.RoutineDriveStep
import com.areslib.routine.RoutinePose
import com.areslib.sequencer.FollowPathTask
import com.areslib.sequencer.ParallelDeadlineGroup
import com.areslib.sequencer.ParallelTaskGroup
import com.areslib.sequencer.SequentialTaskGroup
import com.areslib.sequencer.Task
import com.areslib.sequencer.TaskStateMachine
import com.areslib.sequencer.TaskStatus
import com.areslib.state.Alliance
import com.areslib.state.RobotState

/** FRC field transform shared by autonomous pose seeding and generated drive targets. */
object FrcRoutinePoseTransform {
    fun apply(
        pose: RoutinePose,
        authoredAlliance: RoutineAlliance,
        activeAlliance: Alliance,
        mirrorForOppositeAlliance: Boolean
    ): Pose2d {
        val authored = when (authoredAlliance) {
            RoutineAlliance.RED -> Alliance.RED
            RoutineAlliance.BLUE -> Alliance.BLUE
        }
        val base = Pose2d(pose.xMeters, pose.yMeters, Rotation2d(pose.headingRadians))
        if (!mirrorForOppositeAlliance || authored == activeAlliance) return base

        // The physical FRC transform is an involution. Passing RED asks the shared helper to apply
        // that transform once, regardless of which alliance the document was authored for.
        return AllianceMirroring.mirror(
            pose = base,
            alliance = Alliance.RED,
            symmetry = FieldSymmetry.MIRRORED,
            fieldLength = CoordinateTransformers.FRC_FIELD_LENGTH,
            fieldWidth = CoordinateTransformers.FRC_FIELD_WIDTH,
            fieldOrigin = FieldOrigin.CORNER
        )
    }
}

/**
 * Generated capability adapter for Marvin XIX.
 *
 * Action and condition factories delegate to the source-owned catalog. Drive tasks are generated
 * natively from the immutable pose snapshot seen when each step starts; no `.aresauto`,
 * PathPlanner, or Choreo file is consulted on the roboRIO.
 */
class FrcGeneratedRoutineCapabilities(
    private val robot: FrcSwerveRobot
) : GeneratedAresProjectCapabilities by FrcAutoCapabilities {
    private val trajectoryPlanner = TrajectoryPlanner(listOf(JerkLimitedTrajectoryProvider))
    private val follower = HolonomicPathFollower(robot.drive)
    private var activeEntry: AutonomousCatalogEntry? = null
    private var activeAlliance: Alliance = Alliance.BLUE

    /** Locks the match-only transform before [com.areslib.routine.RoutineManager.request]. */
    fun configure(entry: AutonomousCatalogEntry, alliance: Alliance) {
        activeEntry = entry
        activeAlliance = alliance
    }

    fun clearConfiguration() {
        activeEntry = null
    }

    fun transform(pose: RoutinePose): Pose2d {
        val entry = checkNotNull(activeEntry) { "Autonomous entry was not configured" }
        return FrcRoutinePoseTransform.apply(
            pose,
            entry.authoredAlliance,
            activeAlliance,
            entry.mirrorForOppositeAlliance
        )
    }

    override fun createDriveTask(step: RoutineDriveStep): Task {
        val transformedTarget = transform(step.target)
        return NativeFrcDriveTask(
            step = step,
            target = transformedTarget,
            planner = trajectoryPlanner,
            follower = follower,
            limitsForPreset = ::trajectoryLimits
        )
    }

    /**
     * Generated teleop drivetrain sink. Axis bindings already applied deadband and shaping, so this
     * scales the normalized command to season limits and applies the alliance perspective. Field
     * coordinates are blue-origin: RED rotates translation intent 180 degrees (matches
     * FRCTeleOpDriveController); rotation is never alliance-mirrored. Generated bindings run after
     * the hand-written controller, so an explicitly authored GUI binding stays authoritative.
     */
    private val driveCommandScratch = DoubleArray(3)

    override fun onDriveCommand(vx: Double, vy: Double, omega: Double, active: Boolean) {
        if (!active) return
        generatedSwerveTeleopCommand(vx, vy, omega, robot.store.state.drive.alliance, driveCommandScratch)
        robot.drive.joystickDrive(
            driveCommandScratch[0],
            driveCommandScratch[1],
            driveCommandScratch[2],
            isFieldCentric = true,
        )
    }

    private fun trajectoryLimits(preset: TrajectoryPreset): TrajectoryLimits {
        val scale = when (preset) {
            TrajectoryPreset.SAFE -> 0.45
            TrajectoryPreset.BALANCED -> 0.70
            TrajectoryPreset.FAST -> 0.90
            TrajectoryPreset.ADAPTIVE -> 0.60
        }
        val maxAcceleration = robot.store.state.tuning.drive.pathAccelerationLimit
            .takeIf { it.isFinite() && it > 0.0 }
            ?.times(scale)
            ?: DEFAULT_ACCELERATION_MPS2 * scale
        return TrajectoryLimits(
            maxVelocityMps = robot.drive.maxSpeedMps * scale,
            maxAccelerationMps2 = maxAcceleration,
            maxJerkMps3 = maxAcceleration * 4.0,
            maxCentripetalAccelerationMps2 = maxAcceleration * 0.75,
            maxAngularVelocityRps = robot.drive.maxAngularSpeedRadiansPerSecond * scale,
            maxAngularAccelerationRps2 = maxAcceleration / DRIVE_RADIUS_METERS
        )
    }

    internal companion object {
        const val DEFAULT_ACCELERATION_MPS2 = 3.0
        const val DRIVE_RADIUS_METERS = 0.3907
        const val MAX_TELEOP_DRIVE_SPEED_MPS = 4.5
        const val MAX_TELEOP_ROTATION_RPS = Math.PI
    }
}

/** Builds the trajectory from the fresh Redux pose and delegates its complete task lifecycle. */
private class NativeFrcDriveTask(
    private val step: RoutineDriveStep,
    private val target: Pose2d,
    private val planner: TrajectoryPlanner,
    private val follower: HolonomicPathFollower,
    private val limitsForPreset: (TrajectoryPreset) -> TrajectoryLimits
) : Task {
    override val name: String = "NativeFrcDrive(${target.x}, ${target.y})"
    private var delegate: Task? = null

    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        val preset = parsePreset(step.motionPresetKey)
        val preferredEngine = step.preferredEngineKey?.let(::parseEngine)
        val generation = planner.generate(
            TrajectoryRequest(
                waypoints = listOf(state.drive.poseEstimator.estimatedPose, target),
                driveModel = DriveModel.SWERVE,
                preset = preset,
                limits = limitsForPreset(preset),
                preferredEngine = preferredEngine
            )
        )
        val errors = generation.diagnostics.filter {
            it.severity == com.areslib.pathing.TrajectoryDiagnosticSeverity.ERROR
        }
        require(errors.isEmpty()) { errors.joinToString(separator = "; ") { it.message } }
        val generated = requireNotNull(generation.trajectory) { "Trajectory provider returned no path" }
        val withMarkers = generated.copy(
            events = step.markers.map { marker ->
                TimedTrajectoryEvent(
                    command = CommandKey(marker.actionKey),
                    timeSeconds = generated.durationSeconds * marker.progress
                )
            }
        )
        val follow = FollowPathTask(
            follower = follower,
            path = withMarkers.toPath(),
            fieldLength = CoordinateTransformers.FRC_FIELD_LENGTH,
            fieldWidth = CoordinateTransformers.FRC_FIELD_WIDTH,
            mirrorForAlliance = false
        )
        val during = step.duringActionKeys.map(::namedTask)
        val drive: Task = if (during.isEmpty()) follow else ParallelDeadlineGroup(follow, during)
        val arrivals = step.arrivalActionKeys.map(::namedTask)
        val compiled = when (arrivals.size) {
            0 -> drive
            1 -> SequentialTaskGroup(listOf(drive, arrivals.single()))
            else -> SequentialTaskGroup(listOf(drive, ParallelTaskGroup(arrivals)))
        }
        delegate = compiled
        return compiled.initialize(state)
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        val task = checkNotNull(delegate) { "Drive task was not initialized" }
        if (TaskStateMachine.getStatus(task) == TaskStatus.FAILED) {
            TaskStateMachine.markFailed(this)
            return false
        }
        val completed = task.isCompleted(state, elapsedMs)
        if (TaskStateMachine.getStatus(task) == TaskStatus.FAILED) {
            TaskStateMachine.markFailed(this)
        }
        return completed
    }

    override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        super.execute(state, elapsedMs)
        val task = checkNotNull(delegate) { "Drive task was not initialized" }
        val actions = task.execute(state, elapsedMs)
        if (TaskStateMachine.getStatus(task) == TaskStatus.FAILED) {
            TaskStateMachine.markFailed(this)
        }
        return actions
    }

    override fun pause(state: RobotState): List<RobotAction> = delegate?.pause(state).orEmpty()

    override fun resume(state: RobotState): List<RobotAction> = delegate?.resume(state).orEmpty()

    override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        val task = delegate
        val failed = task != null && TaskStateMachine.getStatus(task) == TaskStatus.FAILED
        val actions = task?.end(state, interrupted || failed).orEmpty()
        task?.releaseRuntimeState()
        delegate = null
        super.end(state, interrupted || failed)
        return actions
    }

    override fun releaseRuntimeState() {
        delegate?.releaseRuntimeState()
        delegate = null
        super.releaseRuntimeState()
    }

    private fun namedTask(key: String): Task {
        val command = CommandKey(key)
        require(NamedCommands.contains(command)) { "Generated action '$key' is not registered" }
        return NamedCommands.task(command)
    }

    private fun parsePreset(key: String): TrajectoryPreset = when (key.lowercase()) {
        "safe" -> TrajectoryPreset.SAFE
        "balanced" -> TrajectoryPreset.BALANCED
        "fast" -> TrajectoryPreset.FAST
        "adaptive" -> TrajectoryPreset.ADAPTIVE
        else -> error("Unknown motion preset '$key'")
    }

    private fun parseEngine(key: String): TrajectoryEngine = when (key.lowercase()) {
        "jerk-limited", "jerk_limited" -> TrajectoryEngine.JERK_LIMITED
        "dynamics-optimized", "dynamics_optimized" -> TrajectoryEngine.DYNAMICS_OPTIMIZED
        "online-replan", "online_replan" -> TrajectoryEngine.ONLINE_REPLAN
        else -> error("Unknown trajectory engine '$key'")
    }
}

/**
 * Normalized scheme drive axes scaled to the season teleop limits and mirrored for RED (field
 * coordinates are blue-origin; rotation is never mirrored). Pure and writes into [out] as
 * {forwardMps, strafeMps, rotationRps} so the per-frame sink stays allocation-free and testable.
 */
internal fun generatedSwerveTeleopCommand(
    vx: Double,
    vy: Double,
    omega: Double,
    alliance: Alliance,
    out: DoubleArray,
) {
    val boundedVx = if (vx.isFinite()) vx.coerceIn(-1.0, 1.0) else 0.0
    val boundedVy = if (vy.isFinite()) vy.coerceIn(-1.0, 1.0) else 0.0
    val boundedOmega = if (omega.isFinite()) omega.coerceIn(-1.0, 1.0) else 0.0
    val allianceScale = if (alliance == Alliance.RED) -1.0 else 1.0
    out[0] = boundedVx * FrcGeneratedRoutineCapabilities.MAX_TELEOP_DRIVE_SPEED_MPS * allianceScale
    out[1] = boundedVy * FrcGeneratedRoutineCapabilities.MAX_TELEOP_DRIVE_SPEED_MPS * allianceScale
    out[2] = boundedOmega * FrcGeneratedRoutineCapabilities.MAX_TELEOP_ROTATION_RPS
}

/** Robot-footprint field check used before localization is reset or a routine is armed. */
fun requireFrcRoutinePoseInsideField(pose: Pose2d, label: String) {
    val halfLength = MarvinConfig.ROBOT_BUMPER_LENGTH_METERS / 2.0
    val halfWidth = MarvinConfig.ROBOT_BUMPER_WIDTH_METERS / 2.0
    val projectedX = kotlin.math.abs(kotlin.math.cos(pose.heading.radians)) * halfLength +
        kotlin.math.abs(kotlin.math.sin(pose.heading.radians)) * halfWidth
    val projectedY = kotlin.math.abs(kotlin.math.sin(pose.heading.radians)) * halfLength +
        kotlin.math.abs(kotlin.math.cos(pose.heading.radians)) * halfWidth
    require(
        pose.x in projectedX..(CoordinateTransformers.FRC_FIELD_LENGTH - projectedX) &&
            pose.y in projectedY..(CoordinateTransformers.FRC_FIELD_WIDTH - projectedY)
    ) {
        "$label places the ${MarvinConfig.ROBOT_BUMPER_LENGTH_METERS} m x " +
            "${MarvinConfig.ROBOT_BUMPER_WIDTH_METERS} m robot outside the field"
    }
}
