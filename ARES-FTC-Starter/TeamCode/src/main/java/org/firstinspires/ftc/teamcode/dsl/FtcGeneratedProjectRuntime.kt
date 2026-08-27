// ARES OWNERSHIP: GENERATED STARTER
// Stable generated-project runtime adapter. Replace only through a reviewed starter update.
package org.firstinspires.ftc.teamcode.dsl

import com.areslib.action.RobotAction
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import com.areslib.pathing.CommandKey
import com.areslib.pathing.NamedCommands
import com.areslib.pathing.PathEvent
import com.areslib.pathing.PathPlannerParser
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineDriveStep
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineManager
import com.areslib.routine.RoutinePose
import com.areslib.routine.RoutineStep
import com.areslib.routine.RoutineStepKind
import com.areslib.sequencer.FollowPathTask
import com.areslib.sequencer.ParallelDeadlineGroup
import com.areslib.sequencer.SequentialTaskGroup
import com.areslib.sequencer.Task
import com.areslib.sequencer.TaskResources
import com.areslib.sequencer.TaskStateMachine
import com.areslib.sequencer.TaskStatus
import com.areslib.sequencer.TimeWaitTask
import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import com.areslib.runtime.GeneratedProjectControlRuntime
import com.areslib.ftc.runtime.FtcGeneratedAutonomousRuntime
import com.areslib.ftc.runtime.resolveFtcAutonomousPose
import org.firstinspires.ftc.teamcode.generated.GeneratedAresProject
import org.firstinspires.ftc.teamcode.generated.GeneratedAresProjectCapabilities
import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresDrivebaseConfig
import org.firstinspires.ftc.teamcode.opmodes.AresRobot
import kotlin.math.hypot

/** Robot-side adapter for the deterministic Kotlin emitted from the checked-in `.ares` project. */
internal class FtcGeneratedProjectRuntime(
    private val robot: AresRobot,
    private val autonomousEntry: AutonomousCatalogEntry? = null,
    private val selectedAlliance: com.areslib.state.Alliance = robot.base.store.state.drive.alliance,
) : GeneratedAresProjectCapabilities, FtcGeneratedAutonomousRuntime {
    private val driveAssists = FtcDriveAssistModes()
    private val controlRuntime = GeneratedProjectControlRuntime(
        definition = GeneratedAresProject.runtimeDefinition,
        stateProvider = { robot.base.store.state },
        dispatch = robot.base.store::dispatch,
        capabilities = this,
        maximumControllerPorts = FTC_CONTROLLER_PORTS,
    )

    override val routineManager: RoutineManager
        get() = controlRuntime.routineManager

    /** True when the checked-in scheme binds drivetrain axes, replacing hand-written gamepad drive. */
    val hasGeneratedDriveBindings: Boolean
        get() = controlRuntime.hasGeneratedDriveBindings

    /** Samples the two FTC Driver Station ports through reusable frames, then runs submitted tasks. */
    fun updateControls(
        driverFrame: com.areslib.input.InputFrame,
        operatorFrame: com.areslib.input.InputFrame,
        nowNanos: Long,
        emitDriveCommand: Boolean = true,
    ) {
        controlRuntime.updatePort(DRIVER_PORT, driverFrame, nowNanos)
        controlRuntime.updatePort(OPERATOR_PORT, operatorFrame, nowNanos)
        if (emitDriveCommand) controlRuntime.emitDriveCommand()
        updateTasks()
    }

    /**
     * Generated drivetrain sink. Axis bindings already applied deadband, expo, and slew shaping, so
     * this only bounds the values, applies the alliance perspective, and hands the field-centric
     * command to the mecanum facade. Blue mirrors both field-relative translation axes; rotation is
     * never alliance-mirrored (AGENTS.md §5).
     */
    /** Heading-lock state for scheme-authored drive; OpModes toggle this at runtime. */
    var headingLockEnabled: Boolean
        get() = driveAssists.headingLockEnabled
        set(value) { driveAssists.headingLockEnabled = value }

    /** EKF position hold resists displacement only while pose feedback is fresh and valid. */
    var positionHoldEnabled: Boolean
        get() = driveAssists.positionHoldEnabled
        set(value) { driveAssists.positionHoldEnabled = value }

    override fun onDriveCommand(vx: Double, vy: Double, omega: Double, active: Boolean) {
        if (!active) return
        val boundedVx = if (vx.isFinite()) vx.coerceIn(-1.0, 1.0) else 0.0
        val boundedVy = if (vy.isFinite()) vy.coerceIn(-1.0, 1.0) else 0.0
        val boundedOmega = if (omega.isFinite()) omega.coerceIn(-1.0, 1.0) else 0.0
        // Blue mirrors both field-relative translation axes inline; rotation is never
        // alliance-mirrored. Kept inline (no Pair allocation) per the zero-GC loop contract.
        val mirror = if (robot.base.store.state.drive.alliance == com.areslib.state.Alliance.BLUE) -1.0 else 1.0
        val state = robot.base.store.state
        val positionHoldAllowed = driveAssists.positionHoldAllowed(
            state = state,
            nowMs = RobotClock.currentTimeMillis(),
            staleFeedbackTimeoutMs = GeneratedAresDrivebaseConfig.STALE_FEEDBACK_TIMEOUT_MS,
        )
        robot.base.mecanumDrive.driveFieldRelativeNormalized(
            mirror * boundedVx,
            mirror * boundedVy,
            boundedOmega,
            headingLockEnabled,
            positionHoldAllowed,
        )
    }

    /** Runs generated routine tasks and controller-submitted one-shot tasks once per robot frame. */
    override fun updateTasks() = controlRuntime.updateTasks()

    /** Disable/stop safety hook. Cleanup actions are dispatched before lifecycle cancellation. */
    override fun cancelAll(reason: String) = controlRuntime.cancelAll(reason)

    val controlsSource: String
        get() = controlRuntime.controlsSource

    override fun createDriveTask(step: RoutineDriveStep): Task {
        requireFtcDriveActionsAvailable(step)
        require(step.preferredEngineKey == null) {
            "FTC does not support a preferred trajectory engine; remove '${step.preferredEngineKey}'"
        }
        val target = autonomousEntry?.let { resolveFtcAutonomousPose(it, selectedAlliance, step.target) }
            ?: step.target.toPose2d()
        val envelope = ftcFieldEnvelopeForRobot(robot)
        require(isFtcRobotPoseWithinField(target, envelope)) {
            "Drive target (${target.x}, ${target.y}) leaves the FTC field with the configured robot footprint"
        }
        val preset = FtcMotionPreset.fromKey(step.motionPresetKey)
        return FtcRoutineDriveTask(robot, step, target, preset)
    }

    /** Resolves hand-authored actions without coupling this adapter to generated method names. */
    override fun createActionTask(actionKey: String, arguments: Map<String, String>): Task? {
        require(arguments.isEmpty()) {
            "FTC named action '$actionKey' does not accept arguments; use a generated subsystem capability"
        }
        return when (actionKey) {
            "drivetrain.headingLock.enable" -> driveAssistTask("Enable heading lock") { headingLockEnabled = true }
            "drivetrain.headingLock.disable" -> driveAssistTask("Disable heading lock") { headingLockEnabled = false }
            "drivetrain.headingLock.toggle" -> driveAssistTask("Toggle heading lock") { headingLockEnabled = !headingLockEnabled }
            "drivetrain.positionHold.enable" -> driveAssistTask("Enable anti-push position hold") { positionHoldEnabled = true }
            "drivetrain.positionHold.disable" -> driveAssistTask("Disable anti-push position hold") { positionHoldEnabled = false }
            "drivetrain.positionHold.toggle" -> driveAssistTask("Toggle anti-push position hold") { positionHoldEnabled = !positionHoldEnabled }
            else -> NamedCommands.create(CommandKey(actionKey), RobotClock.currentTimeMillis())
        }
    }

    private fun driveAssistTask(name: String, update: () -> Unit): Task = object : Task {
        override val name: String = name
        override val requiredResources: Long = TaskResources.DRIVE
        private var applied = false

        override fun initialize(state: RobotState): List<RobotAction> {
            super.initialize(state)
            update()
            applied = true
            return emptyList()
        }

        override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean = applied

        override fun releaseRuntimeState() {
            applied = false
            super.releaseRuntimeState()
        }
    }

    private companion object {
        const val DRIVER_PORT: Int = 0
        const val OPERATOR_PORT: Int = 1
        const val FTC_CONTROLLER_PORTS: Int = 2
    }
}

/** Mutable runtime policy owned by one OpMode; generated Redux state remains immutable. */
internal class FtcDriveAssistModes {
    @Volatile var headingLockEnabled: Boolean = true
    @Volatile var positionHoldEnabled: Boolean = false

    fun positionHoldAllowed(state: RobotState, nowMs: Long, staleFeedbackTimeoutMs: Long): Boolean {
        if (!positionHoldEnabled || !state.drive.measuredMotionValid) return false
        val observationMs = state.drive.poseEstimator.lastObservationTimestampMs
        if (observationMs < 0L || nowMs < observationMs) return false
        return nowMs - observationMs <= staleFeedbackTimeoutMs
    }
}

/** Rejects unavailable hardware-backed actions while the routine request is still compiling. */
internal fun requireFtcDriveActionsAvailable(step: RoutineDriveStep) {
    fun requireAvailable(key: String) {
        require(NamedCommands.contains(CommandKey(key))) {
            "Generated drive action '$key' is unavailable on discovered FTC hardware"
        }
    }
    for (marker in step.markers) requireAvailable(marker.actionKey)
    for (key in step.duringActionKeys) requireAvailable(key)
    for (key in step.arrivalActionKeys) requireAvailable(key)
}

private enum class FtcMotionPreset(val speedScale: Double, val accelerationScale: Double) {
    SAFE(0.40, 0.45),
    BALANCED(0.65, 0.70),
    FAST(0.85, 0.90),
    ADAPTIVE(0.55, 0.60);

    companion object {
        fun fromKey(key: String): FtcMotionPreset = when (key.lowercase()) {
            "safe" -> SAFE
            "balanced" -> BALANCED
            "fast" -> FAST
            "adaptive" -> ADAPTIVE
            else -> throw IllegalArgumentException("Unknown FTC motion preset '$key'")
        }
    }
}

/** Builds a fresh direct spline from the estimator pose when the drive node actually starts. */
private class FtcRoutineDriveTask(
    private val robot: AresRobot,
    private val step: RoutineDriveStep,
    private val target: Pose2d,
    private val preset: FtcMotionPreset,
) : Task {
    override val name: String = "FTC drive to (%.2f, %.2f)".format(target.x, target.y)
    private var delegate: Task? = null
    private var delegateEnded = false
    private val statusBridge = FtcDelegateStatusBridge(this)

    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        delegateEnded = false
        statusBridge.reset()
        val start = state.drive.poseEstimator.estimatedPose
        val maximumVelocity = robot.base.mecanumIO.maxWheelSpeedMetersPerSecond * preset.speedScale
        val maximumAcceleration = state.tuning.drive.pathAccelerationLimit * preset.accelerationScale
        val motionKind = classifyFtcDriveMotion(start, target)
        val generatedPath = if (motionKind == FtcDriveMotionKind.TRANSLATE) {
            PathPlannerParser.generatePath(
                points = listOf(Translation2d(start.x, start.y), Translation2d(target.x, target.y)),
                startHeading = start.heading,
                endHeading = target.heading,
                maxVelocityMps = maximumVelocity,
                maxAccelerationMps2 = maximumAcceleration,
            )
        } else null
        // Preflight the geometry the robot will actually drive: a Hermite spline through the
        // same endpoints can bow away from the straight chord, so validating only the chord
        // could approve a spline that clips an obstacle the chord safely bypasses.
        if (generatedPath != null) {
            require(
                isFtcRobotPathSweepCollisionFree(
                    generatedPath,
                    ftcFieldEnvelopeForRobot(robot),
                    com.areslib.state.RobotFieldManager.activeConfig.obstacles
                )
            ) { "Generated drive spline intersects a blocking obstacle or field boundary" }
        } else {
            require(
                isFtcRobotSweepCollisionFree(
                    start,
                    target,
                    ftcFieldEnvelopeForRobot(robot),
                    com.areslib.state.RobotFieldManager.activeConfig.obstacles
                )
            ) { "Drive sweep intersects a blocking obstacle or field boundary" }
        }
        val totalDistance = generatedPath?.points?.lastOrNull()?.distanceMeters ?: 0.0
        val events = step.markers.map { marker ->
            PathEvent(marker.actionKey, marker.progress * totalDistance)
        }
        val driveTask: Task = when {
            generatedPath != null -> FollowPathTask(
                follower = robot.base.pathFollower,
                path = generatedPath.copy(events = events),
                mirrorForAlliance = false,
            )
            motionKind == FtcDriveMotionKind.IMMEDIATE -> TimeWaitTask(0L)
            else -> FtcRotateToHeadingTask(
                targetHeadingRadians = target.heading.radians,
                maxOmegaRadiansPerSecond = robot.base.drive.maxAngularSpeedRadiansPerSecond * preset.speedScale
            ).withTimeout(ROTATION_TIMEOUT_MS)
        }
        val duringTasks = buildList {
            step.duringActionKeys.forEach { add(NamedCommands.task(CommandKey(it))) }
            if (generatedPath == null) {
                // Distance markers collapse to the start of an in-place motion; execute them as
                // companions rather than constructing an invalid empty FollowPathTask.
                step.markers.forEach { add(NamedCommands.task(CommandKey(it.actionKey))) }
            }
        }
        val arrivalTasks = step.arrivalActionKeys.map { NamedCommands.task(CommandKey(it)) }
        val compiled = composeFtcDriveLifecycle(driveTask, duringTasks, arrivalTasks)
        delegate = compiled
        return compiled.initialize(state)
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        val task = delegate ?: return true
        val complete = task.isCompleted(state, elapsedMs)
        statusBridge.propagate(task)
        return complete
    }

    override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        super.execute(state, elapsedMs)
        if (TaskStateMachine.getStatus(this) == TaskStatus.FAILED) return emptyList()
        val task = delegate ?: return emptyList()
        val actions = task.execute(state, elapsedMs)
        statusBridge.propagate(task)
        return actions
    }

    override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        val task = delegate
        val childStatus = task?.let(TaskStateMachine::getStatus)
        val terminalInterrupted = interrupted || childStatus == TaskStatus.FAILED ||
            childStatus == TaskStatus.CANCELLED
        val actions = if (task != null && !delegateEnded) {
            delegateEnded = true
            task.end(state, terminalInterrupted)
        } else emptyList()
        super.end(state, terminalInterrupted)
        return actions
    }

    override fun releaseRuntimeState() {
        delegate?.releaseRuntimeState()
        delegate = null
        delegateEnded = false
        statusBridge.reset()
        super.releaseRuntimeState()
    }

    private companion object {
        const val ROTATION_TIMEOUT_MS = 5_000L
    }
}

internal enum class FtcDriveMotionKind { TRANSLATE, ROTATE, IMMEDIATE }

/** Classifies exact/same-position drive nodes without constructing an invalid zero-length path. */
internal fun classifyFtcDriveMotion(start: Pose2d, target: Pose2d): FtcDriveMotionKind {
    val translationDistance = hypot(target.x - start.x, target.y - start.y)
    if (translationDistance >= SAME_POSITION_EPSILON_METERS) return FtcDriveMotionKind.TRANSLATE
    val headingError = kotlin.math.abs(
        com.areslib.math.wrapAngle(target.heading.radians - start.heading.radians)
    )
    return if (headingError < SAME_HEADING_EPSILON_RADIANS) {
        FtcDriveMotionKind.IMMEDIATE
    } else {
        FtcDriveMotionKind.ROTATE
    }
}

private const val SAME_POSITION_EPSILON_METERS = 1e-6
private const val SAME_HEADING_EPSILON_RADIANS = 1e-6

/** Mirrors the first nested terminal status exactly once and leaves cleanup to the wrapper. */
internal class FtcDelegateStatusBridge(private val owner: Task) {
    var terminalStatus: TaskStatus? = null
        private set

    fun propagate(delegate: Task): TaskStatus {
        val status = TaskStateMachine.getStatus(delegate)
        if (terminalStatus == null) {
            when (status) {
                TaskStatus.FAILED -> {
                    terminalStatus = status
                    TaskStateMachine.markFailed(owner)
                }
                TaskStatus.CANCELLED -> {
                    terminalStatus = status
                    TaskStateMachine.transitionTo(owner, TaskStatus.CANCELLED)
                }
                else -> Unit
            }
        }
        return status
    }

    fun reset() {
        terminalStatus = null
    }
}

/** Allocation-free in-place heading motion for a DRIVE_TO node with no translation. */
internal class FtcRotateToHeadingTask(
    private val targetHeadingRadians: Double,
    private val maxOmegaRadiansPerSecond: Double,
) : Task {
    override val name: String = "FTC rotate in place"
    private val command = RobotAction.JoystickDriveIntent(0.0, 0.0, 0.0, isFieldCentric = true)
    private val commandActions: List<RobotAction> = listOf(command)

    init {
        require(targetHeadingRadians.isFinite()) { "Target heading must be finite" }
        require(maxOmegaRadiansPerSecond.isFinite() && maxOmegaRadiansPerSecond > 0.0) {
            "Maximum rotation speed must be finite and positive"
        }
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean =
        kotlin.math.abs(headingError(state)) <= HEADING_TOLERANCE_RADIANS &&
            kotlin.math.abs(state.drive.measuredAngularVelocityRadiansPerSecond) <= ANGULAR_VELOCITY_TOLERANCE

    override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        super.execute(state, elapsedMs)
        val omega = (headingError(state) * ROTATION_KP)
            .coerceIn(-maxOmegaRadiansPerSecond, maxOmegaRadiansPerSecond)
        command.targetXVelocity = 0.0
        command.targetYVelocity = 0.0
        command.targetAngularVelocity = omega
        command.timestampMs = RobotClock.currentTimeMillis()
        command.isFieldCentric = true
        command.fromHeadingHold = true
        return commandActions
    }

    override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        super.end(state, interrupted)
        command.targetXVelocity = 0.0
        command.targetYVelocity = 0.0
        command.targetAngularVelocity = 0.0
        command.timestampMs = RobotClock.currentTimeMillis()
        return commandActions
    }

    private fun headingError(state: RobotState): Double = com.areslib.math.wrapAngle(
        targetHeadingRadians - state.drive.poseEstimator.estimatedPoseHeading
    )

    private companion object {
        const val ROTATION_KP = 2.5
        const val HEADING_TOLERANCE_RADIANS = Math.PI / 90.0
        const val ANGULAR_VELOCITY_TOLERANCE = 0.10
    }
}

private fun RoutinePose.toPose2d(): Pose2d = Pose2d(xMeters, yMeters, Rotation2d(headingRadians))

/** Drive owns the deadline; unfinished during-actions are interrupted before arrival actions start. */
internal fun composeFtcDriveLifecycle(
    driveTask: Task,
    duringTasks: List<Task>,
    arrivalTasks: List<Task>,
): Task {
    val driveWithDuring = if (duringTasks.isEmpty()) {
        driveTask
    } else {
        ParallelDeadlineGroup(deadline = driveTask, otherTasks = duringTasks)
    }
    return SequentialTaskGroup(listOf(driveWithDuring) + arrivalTasks)
}

/** Center-origin FTC field and rectangular robot-footprint geometry used by runtime preflight. */
internal data class FtcFieldEnvelope(
    val fieldWidthMeters: Double,
    val fieldHeightMeters: Double,
    val robotLengthMeters: Double,
    val robotWidthMeters: Double,
) {
    init {
        require(fieldWidthMeters.isFinite() && fieldWidthMeters > 0.0)
        require(fieldHeightMeters.isFinite() && fieldHeightMeters > 0.0)
        require(robotLengthMeters.isFinite() && robotLengthMeters > 0.0)
        require(robotWidthMeters.isFinite() && robotWidthMeters > 0.0)
    }
}

/** Resolves the active FTC field plus the season robot's configured mecanum footprint. */
internal fun ftcFieldEnvelopeForRobot(robot: AresRobot): FtcFieldEnvelope {
    require(robot.hasCanonicalFieldContract) {
        "Canonical FTC season field is unavailable; autonomous obstacle preflight is disabled"
    }
    val field = com.areslib.state.RobotFieldManager.activeConfig
    require(field.fieldType == com.areslib.state.FieldType.FTC) {
        "Active field config '${field.name}' is not an FTC field"
    }
    return FtcFieldEnvelope(
        fieldWidthMeters = field.resolvedWidthMeters,
        fieldHeightMeters = field.resolvedHeightMeters,
        robotLengthMeters = GeneratedAresProject.ROBOT_LENGTH_METERS,
        robotWidthMeters = GeneratedAresProject.ROBOT_WIDTH_METERS,
    )
}

/** Returns true only when every corner of the rotated robot lies within the FTC field. */
internal fun isFtcRobotPoseWithinField(pose: Pose2d, envelope: FtcFieldEnvelope): Boolean {
    if (!pose.x.isFinite() || !pose.y.isFinite() || !pose.heading.radians.isFinite()) return false
    val heading = pose.heading.radians
    val absCos = kotlin.math.abs(kotlin.math.cos(heading))
    val absSin = kotlin.math.abs(kotlin.math.sin(heading))
    val xExtent = absCos * envelope.robotLengthMeters * 0.5 +
        absSin * envelope.robotWidthMeters * 0.5
    val yExtent = absSin * envelope.robotLengthMeters * 0.5 +
        absCos * envelope.robotWidthMeters * 0.5
    val halfFieldX = envelope.fieldWidthMeters * 0.5
    val halfFieldY = envelope.fieldHeightMeters * 0.5
    return pose.x - xExtent >= -halfFieldX && pose.x + xExtent <= halfFieldX &&
        pose.y - yExtent >= -halfFieldY && pose.y + yExtent <= halfFieldY
}

/**
 * Conservatively validates a straight-line translation sweep against field geometry.
 *
 * The rectangular robot is enclosed by its circumscribed circle, so inflating each blocking
 * polygon by that radius is safe for every interpolated heading. This can reject a tight-but-valid
 * path, but it cannot approve a polygon collision hidden between two safe endpoints.
 */
internal fun isFtcRobotSweepCollisionFree(
    start: Pose2d,
    end: Pose2d,
    envelope: FtcFieldEnvelope,
    obstacles: List<com.areslib.state.RobotFieldObstacle>,
): Boolean {
    if (!isFtcRobotPoseWithinField(start, envelope) || !isFtcRobotPoseWithinField(end, envelope)) return false
    val radius = 0.5 * hypot(envelope.robotLengthMeters, envelope.robotWidthMeters)
    val halfFieldX = envelope.fieldWidthMeters * 0.5
    val halfFieldY = envelope.fieldHeightMeters * 0.5
    if (kotlin.math.abs(start.x) + radius > halfFieldX || kotlin.math.abs(end.x) + radius > halfFieldX ||
        kotlin.math.abs(start.y) + radius > halfFieldY || kotlin.math.abs(end.y) + radius > halfFieldY
    ) return false

    for (obstacle in obstacles) {
        if (!obstacle.isBlocking || obstacle.obstacleType != com.areslib.state.ObstacleType.BLOCKING) continue
        val vertices = obstacleVertices(obstacle)
        if (vertices.size < 3) return false
        if (pointInPolygon(start.x, start.y, vertices) || pointInPolygon(end.x, end.y, vertices)) return false
        for (index in vertices.indices) {
            val a = vertices[index]
            val b = vertices[(index + 1) % vertices.size]
            if (segmentsIntersect(start.x, start.y, end.x, end.y, a.x, a.y, b.x, b.y) ||
                segmentDistance(start.x, start.y, end.x, end.y, a.x, a.y, b.x, b.y) <= radius
            ) return false
        }
    }
    return true
}

/**
 * Validates the actual sampled geometry of a generated path against field obstacles/bounds.
 *
 * [com.areslib.pathing.Path] points are sampled at <= 5 cm spacing by the Hermite generator,
 * so sweeping every consecutive sample pair with [isFtcRobotSweepCollisionFree] approximates
 * the driven spline closely enough that a bow-around-an-obstacle cannot pass preflight while
 * the chord check alone would have approved it.
 */
internal fun isFtcRobotPathSweepCollisionFree(
    path: com.areslib.pathing.Path,
    envelope: FtcFieldEnvelope,
    obstacles: List<com.areslib.state.RobotFieldObstacle>,
): Boolean {
    val points = path.points
    if (points.isEmpty()) return false
    var previous = points.first().pose
    for (index in 1 until points.size) {
        val current = points[index].pose
        if (!isFtcRobotSweepCollisionFree(previous, current, envelope, obstacles)) return false
        previous = current
    }
    return true
}

private fun obstacleVertices(
    obstacle: com.areslib.state.RobotFieldObstacle,
): List<com.areslib.state.RobotFieldPoint> {
    if (obstacle.shape.equals("polygon", ignoreCase = true)) return obstacle.points
    if (!obstacle.x.isFinite() || !obstacle.y.isFinite() || !obstacle.width.isFinite() ||
        !obstacle.height.isFinite() || obstacle.width <= 0.0 || obstacle.height <= 0.0 ||
        !obstacle.rotation.isFinite()
    ) return emptyList()
    val halfWidth = obstacle.width * 0.5
    val halfHeight = obstacle.height * 0.5
    val radians = Math.toRadians(obstacle.rotation)
    val cosine = kotlin.math.cos(radians)
    val sine = kotlin.math.sin(radians)
    fun vertex(localX: Double, localY: Double): com.areslib.state.RobotFieldPoint =
        com.areslib.state.RobotFieldPoint(
            x = obstacle.x + localX * cosine - localY * sine,
            y = obstacle.y + localX * sine + localY * cosine,
        )
    return listOf(
        vertex(-halfWidth, -halfHeight),
        vertex(halfWidth, -halfHeight),
        vertex(halfWidth, halfHeight),
        vertex(-halfWidth, halfHeight),
    )
}

internal fun pointInPolygon(
    x: Double,
    y: Double,
    vertices: List<com.areslib.state.RobotFieldPoint>,
): Boolean {
    var inside = false
    var previous = vertices.last()
    for (current in vertices) {
        if (!current.x.isFinite() || !current.y.isFinite()) return true
        if ((current.y > y) != (previous.y > y)) {
            val crossingX = (previous.x - current.x) * (y - current.y) /
                (previous.y - current.y) + current.x
            if (x < crossingX) inside = !inside
        }
        previous = current
    }
    return inside
}

internal fun segmentsIntersect(
    ax: Double, ay: Double, bx: Double, by: Double,
    cx: Double, cy: Double, dx: Double, dy: Double,
): Boolean {
    fun cross(px: Double, py: Double, qx: Double, qy: Double, rx: Double, ry: Double): Double =
        (qx - px) * (ry - py) - (qy - py) * (rx - px)
    fun onSegment(px: Double, py: Double, qx: Double, qy: Double, rx: Double, ry: Double): Boolean =
        qx >= kotlin.math.min(px, rx) - GEOMETRY_EPSILON &&
            qx <= kotlin.math.max(px, rx) + GEOMETRY_EPSILON &&
            qy >= kotlin.math.min(py, ry) - GEOMETRY_EPSILON &&
            qy <= kotlin.math.max(py, ry) + GEOMETRY_EPSILON
    val c1 = cross(ax, ay, bx, by, cx, cy)
    val c2 = cross(ax, ay, bx, by, dx, dy)
    val c3 = cross(cx, cy, dx, dy, ax, ay)
    val c4 = cross(cx, cy, dx, dy, bx, by)
    if (((c1 > GEOMETRY_EPSILON && c2 < -GEOMETRY_EPSILON) ||
            (c1 < -GEOMETRY_EPSILON && c2 > GEOMETRY_EPSILON)) &&
        ((c3 > GEOMETRY_EPSILON && c4 < -GEOMETRY_EPSILON) ||
            (c3 < -GEOMETRY_EPSILON && c4 > GEOMETRY_EPSILON))
    ) return true
    return (kotlin.math.abs(c1) <= GEOMETRY_EPSILON && onSegment(ax, ay, cx, cy, bx, by)) ||
        (kotlin.math.abs(c2) <= GEOMETRY_EPSILON && onSegment(ax, ay, dx, dy, bx, by)) ||
        (kotlin.math.abs(c3) <= GEOMETRY_EPSILON && onSegment(cx, cy, ax, ay, dx, dy)) ||
        (kotlin.math.abs(c4) <= GEOMETRY_EPSILON && onSegment(cx, cy, bx, by, dx, dy))
}

private const val GEOMETRY_EPSILON = 1e-9

internal fun segmentDistance(
    ax: Double, ay: Double, bx: Double, by: Double,
    cx: Double, cy: Double, dx: Double, dy: Double,
): Double = kotlin.math.min(
    kotlin.math.min(pointSegmentDistance(ax, ay, cx, cy, dx, dy), pointSegmentDistance(bx, by, cx, cy, dx, dy)),
    kotlin.math.min(pointSegmentDistance(cx, cy, ax, ay, bx, by), pointSegmentDistance(dx, dy, ax, ay, bx, by)),
)

private fun pointSegmentDistance(
    px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double,
): Double {
    val vx = bx - ax
    val vy = by - ay
    val lengthSquared = vx * vx + vy * vy
    if (lengthSquared <= 1e-12) return hypot(px - ax, py - ay)
    val t = (((px - ax) * vx + (py - ay) * vy) / lengthSquared).coerceIn(0.0, 1.0)
    return hypot(px - (ax + t * vx), py - (ay + t * vy))
}

/** Validates live action capabilities plus every reachable pose/sweep before hardware pose reset. */
internal fun validateFtcAutonomousBounds(
    entry: AutonomousCatalogEntry,
    routines: Map<String, RoutineDocument>,
    envelope: FtcFieldEnvelope,
    selectedAlliance: com.areslib.state.Alliance,
    obstacles: List<com.areslib.state.RobotFieldObstacle> = com.areslib.state.RobotFieldManager.activeConfig.obstacles,
): List<String> {
    val errors = mutableListOf<String>()
    val start = resolveFtcAutonomousPose(entry, selectedAlliance)
    if (!isFtcRobotPoseWithinField(start, envelope)) {
        errors += "starting pose leaves the FTC field with the configured robot footprint"
    }
    val callStack = mutableSetOf<String>()
    lateinit var visitSequence: (List<com.areslib.routine.RoutineStep>, Set<Pose2d>, String) -> Set<Pose2d>
    fun visitRoutine(routineId: String, starts: Set<Pose2d>, path: String): Set<Pose2d> {
        if (!callStack.add(routineId)) {
            errors += "$path recursive routine call"
            return starts
        }
        val routine = routines[routineId]
        if (routine == null) {
            errors += "routine '$routineId' does not exist"
            callStack.remove(routineId)
            return starts
        }
        val result = visitSequence(routine.steps, starts, "$path.steps")
        callStack.remove(routineId)
        return result
    }
    fun visitStep(
        step: com.areslib.routine.RoutineStep,
        starts: Set<Pose2d>,
        path: String,
    ): Set<Pose2d> {
        fun requireAction(key: String?, actionPath: String) {
            val parsedKey = key?.let { candidate ->
                runCatching { CommandKey(candidate) }.getOrNull()
            }
            if (parsedKey == null || !NamedCommands.contains(parsedKey)) {
                errors += "$actionPath action '${key ?: "<missing>"}' is unavailable on discovered FTC hardware"
            }
        }
        if (step.kind == com.areslib.routine.RoutineStepKind.ACTION) {
            requireAction(step.actionKey, path)
        }
        step.drive?.let { drive ->
            runCatching { FtcMotionPreset.fromKey(drive.motionPresetKey) }
                .exceptionOrNull()
                ?.let { errors += "$path ${it.message ?: "uses an unsupported FTC motion preset"}" }
            if (drive.preferredEngineKey != null) {
                errors += "$path preferred trajectory engine '${drive.preferredEngineKey}' is unsupported on FTC"
            }
            drive.markers.forEachIndexed { index, marker ->
                requireAction(marker.actionKey, "$path.markers[$index]")
            }
            drive.duringActionKeys.forEachIndexed { index, key ->
                requireAction(key, "$path.duringActionKeys[$index]")
            }
            drive.arrivalActionKeys.forEachIndexed { index, key ->
                requireAction(key, "$path.arrivalActionKeys[$index]")
            }
        }
        when (step.kind) {
            RoutineStepKind.TOGETHER -> {
                val driveOwners = step.children.count { containsFtcDrive(it, routines) }
                if (driveOwners > 1) {
                    errors += "$path has multiple concurrent drive owners"
                }
            }
            RoutineStepKind.FIRST_TO_FINISH -> {
                if (step.children.size > 1 && step.children.any { containsFtcDrive(it, routines) }) {
                    errors += "$path can interrupt a drive at an indeterminate pose"
                }
            }
            RoutineStepKind.DEADLINE -> {
                if (step.children.any { containsFtcDrive(it, routines) }) {
                    errors += "$path companion drive can be interrupted by its deadline"
                }
            }
            else -> Unit
        }
        return when (step.kind) {
            com.areslib.routine.RoutineStepKind.DRIVE_TO -> {
                val target = requireNotNull(step.drive).target
                val selectedTarget = resolveFtcAutonomousPose(entry, selectedAlliance, target)
                if (!isFtcRobotPoseWithinField(selectedTarget, envelope)) {
                    errors += "$path drive target leaves the FTC field"
                } else {
                    for (candidateStart in starts) {
                        if (!isFtcRobotSweepCollisionFree(candidateStart, selectedTarget, envelope, obstacles)) {
                            errors += "$path drive sweep intersects a blocking obstacle or field boundary"
                        }
                    }
                }
                setOf(selectedTarget)
            }
            com.areslib.routine.RoutineStepKind.CALL -> visitRoutine(requireNotNull(step.routineId), starts, path)
            com.areslib.routine.RoutineStepKind.REPEAT -> {
                var current = starts
                repeat(requireNotNull(step.repeatCount)) {
                    current = visitSequence(step.children, current, "$path.repeat[$it]")
                }
                current
            }
            com.areslib.routine.RoutineStepKind.BRANCH ->
                visitSequence(step.children, starts, "$path.true") +
                    visitSequence(step.elseChildren, starts, "$path.false")
            com.areslib.routine.RoutineStepKind.TOGETHER,
            com.areslib.routine.RoutineStepKind.FIRST_TO_FINISH -> step.children.flatMapIndexed { index, child ->
                visitStep(child, starts, "$path.children[$index]")
            }.toSet().ifEmpty { starts }
            com.areslib.routine.RoutineStepKind.DEADLINE -> {
                val outcomes = mutableSetOf<Pose2d>()
                step.deadline?.let { outcomes += visitStep(it, starts, "$path.deadline") }
                step.children.forEachIndexed { index, child ->
                    outcomes += visitStep(child, starts, "$path.children[$index]")
                }
                outcomes.ifEmpty { starts }
            }
            else -> starts
        }
    }
    visitSequence = { steps, starts, path ->
        var current = starts
        steps.forEachIndexed { index, step -> current = visitStep(step, current, "$path[$index]") }
        current
    }
    if (isFtcRobotSweepCollisionFree(start, start, envelope, obstacles)) {
        visitRoutine(entry.routineId, setOf(start), "routine")
    } else {
        errors += "starting pose intersects a blocking obstacle or field boundary"
    }
    return errors.distinct()
}

/** Resolves nested/CALL structure when checking concurrent drivetrain ownership. */
private fun containsFtcDrive(
    step: RoutineStep,
    routines: Map<String, RoutineDocument>,
    visitingRoutines: MutableSet<String> = mutableSetOf(),
): Boolean = when (step.kind) {
    RoutineStepKind.DRIVE_TO -> true
    RoutineStepKind.CALL -> {
        val routineId = step.routineId
        if (routineId == null || !visitingRoutines.add(routineId)) {
            false
        } else {
            val contains = routines[routineId]?.steps?.any { containsFtcDrive(it, routines, visitingRoutines) } == true
            visitingRoutines.remove(routineId)
            contains
        }
    }
    else -> step.deadline?.let { containsFtcDrive(it, routines, visitingRoutines) } == true ||
        step.children.any { containsFtcDrive(it, routines, visitingRoutines) } ||
        step.elseChildren.any { containsFtcDrive(it, routines, visitingRoutines) }
}

/**
 * Field-centric translation components for generated drivetrain commands. Blue alliance mirrors
 * both translation axes so the driver's perspective matches their alliance wall; rotation is
 * never mirrored. Kept pure so the mirroring rule stays unit-testable.
 */
internal fun generatedDriveFieldComponents(
    vx: Double,
    vy: Double,
    alliance: com.areslib.state.Alliance,
): Pair<Double, Double> =
    if (alliance == com.areslib.state.Alliance.BLUE) -vx to -vy else vx to vy
