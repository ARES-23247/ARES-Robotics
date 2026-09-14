package org.aresfirst.starter.frc

import com.areslib.action.RobotAction
import com.areslib.control.feedback.PIDController
import com.areslib.math.wrapAngle
import com.areslib.math.coordinate.AllianceMirroring
import com.areslib.math.coordinate.CoordinateTransformers
import com.areslib.math.coordinate.FieldOrigin
import com.areslib.math.coordinate.FieldSymmetry
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.AutonomousCatalogResolution
import com.areslib.routine.AutonomousCatalogResolver
import com.areslib.routine.RoutineAlliance
import com.areslib.routine.RoutineDriveStep
import com.areslib.routine.RoutinePose
import com.areslib.routine.RoutineRequestResult
import com.areslib.sequencer.ParallelDeadlineGroup
import com.areslib.sequencer.ParallelTaskGroup
import com.areslib.sequencer.SequentialTaskGroup
import com.areslib.sequencer.Task
import com.areslib.sequencer.TaskExecutor
import com.areslib.sequencer.TaskResources
import com.areslib.sequencer.TaskStateMachine
import com.areslib.sequencer.TaskStatus
import com.areslib.state.Alliance
import com.areslib.state.RobotState
import com.areslib.state.RoutineExecutionStatus
import com.areslib.util.RobotClock
import edu.wpi.first.networktables.NetworkTableInstance
import edu.wpi.first.wpilibj.DriverStation
import com.areslib.frc.runtime.FrcGeneratedProjectControlsRuntime
import org.aresfirst.starter.frc.generated.GeneratedAresProject
import org.aresfirst.starter.frc.generated.GeneratedAresProjectCapabilities
import org.aresfirst.starter.frc.generated.drivebase.GeneratedAresDrivebaseConfig
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

internal typealias StarterAutonomousSelection = AutonomousCatalogResolution

/** Shares enabled-entry ordering and configured fallback policy with the other league hosts. */
internal class StarterFrcAutonomousSelector(
    entries: List<AutonomousCatalogEntry>,
    defaultEntryId: String?,
) {
    private val resolver = AutonomousCatalogResolver(entries, defaultEntryId)

    val availableEntryIds: List<String> get() = resolver.availableEntryIds

    fun resolve(requestedId: String): StarterAutonomousSelection = resolver.resolve(requestedId)
}

/** Platform-neutral transform shared by starter pose seeding and every generated drive target. */
internal fun transformStarterFrcPose(
    pose: RoutinePose,
    entry: AutonomousCatalogEntry,
    activeAlliance: Alliance,
): Pose2d {
    val authoredAlliance = when (entry.authoredAlliance) {
        RoutineAlliance.RED -> Alliance.RED
        RoutineAlliance.BLUE -> Alliance.BLUE
    }
    val base = Pose2d(pose.xMeters, pose.yMeters, Rotation2d(pose.headingRadians))
    if (!entry.mirrorForOppositeAlliance || authoredAlliance == activeAlliance) return base
    return AllianceMirroring.mirror(
        pose = base,
        alliance = Alliance.RED,
        symmetry = FieldSymmetry.MIRRORED,
        fieldLength = CoordinateTransformers.FRC_FIELD_LENGTH,
        fieldWidth = CoordinateTransformers.FRC_FIELD_WIDTH,
        fieldOrigin = FieldOrigin.CORNER,
    )
}

/** A fail-safe no-motion entry must never teleport the simulated or estimated robot pose. */
internal fun shouldSeedStarterFrcAutonomousPose(entry: AutonomousCatalogEntry): Boolean =
    entry.entryId != "do-nothing" || entry.routineId != "do-nothing"

internal fun resolveStarterFrcAutonomousRequest(
    aresRequested: String?,
    smartDashboardRequested: String?,
    fallback: String,
): String = aresRequested?.trim()?.takeIf(String::isNotEmpty)
    ?: smartDashboardRequested?.trim()?.takeIf(String::isNotEmpty)
    ?: fallback

/** Capability boundary used by both generated TeleOp bindings and generated autonomous routines. */
internal class StarterGeneratedCapabilities(
    private val robot: StarterRobotRuntime,
    private val drivePermitted: Boolean,
) : GeneratedAresProjectCapabilities {
    private var autonomousEntry: AutonomousCatalogEntry? = null
    private var autonomousAlliance: Alliance = Alliance.BLUE

    fun configureAutonomous(entry: AutonomousCatalogEntry, alliance: Alliance) {
        autonomousEntry = entry
        autonomousAlliance = alliance
    }

    fun clearAutonomous() {
        autonomousEntry = null
    }

    fun transform(pose: RoutinePose): Pose2d = transformStarterFrcPose(
        pose,
        checkNotNull(autonomousEntry) { "Autonomous entry was not configured" },
        autonomousAlliance,
    )

    override fun onDriveCommand(vx: Double, vy: Double, omega: Double, active: Boolean) {
        val permitted = drivePermitted && active
        robot.store.dispatch(
            RobotAction.JoystickDriveIntent(
                targetXVelocity = if (permitted && vx.isFinite()) vx.coerceIn(-1.0, 1.0) *
                    GeneratedAresDrivebaseConfig.MAX_LINEAR_SPEED_METERS_PER_SECOND else 0.0,
                targetYVelocity = if (permitted && vy.isFinite()) vy.coerceIn(-1.0, 1.0) *
                    GeneratedAresDrivebaseConfig.MAX_LINEAR_SPEED_METERS_PER_SECOND else 0.0,
                targetAngularVelocity = if (permitted && omega.isFinite()) omega.coerceIn(-1.0, 1.0) *
                    GeneratedAresDrivebaseConfig.MAX_ANGULAR_SPEED_RADIANS_PER_SECOND else 0.0,
                isFieldCentric = true,
            )
        )
    }

    override fun createDriveTask(step: RoutineDriveStep): Task {
        val target = transform(step.target)
        val preset = StarterFrcMotionPreset.fromKey(step.motionPresetKey)
        val drive = StarterFrcDriveToPoseTask(target, preset)
        val markerTasks = step.markers.map { marker ->
            StarterFrcDriveMarkerTask(
                target = target,
                progress = marker.progress,
                action = requireGeneratedAction(marker.actionKey),
            )
        }
        val duringTasks = step.duringActionKeys.map(::requireGeneratedAction)
        val companions = markerTasks + duringTasks
        val driveWithCompanions: Task = if (companions.isEmpty()) {
            drive
        } else {
            ParallelDeadlineGroup(drive, companions)
        }
        val arrivals = step.arrivalActionKeys.map(::requireGeneratedAction)
        return when (arrivals.size) {
            0 -> driveWithCompanions
            1 -> SequentialTaskGroup(listOf(driveWithCompanions, arrivals.single()))
            else -> SequentialTaskGroup(listOf(driveWithCompanions, ParallelTaskGroup(arrivals)))
        }
    }

    private fun requireGeneratedAction(key: String): Task = requireNotNull(
        GeneratedAresProject.runtimeBindings(this).createActionTask(key, emptyMap())
    ) { "Generated drive action '$key' is unavailable" }
}

internal enum class StarterFrcMotionPreset(val speedScale: Double) {
    SAFE(0.35),
    BALANCED(0.60),
    FAST(0.85),
    ADAPTIVE(0.50);

    companion object {
        fun fromKey(key: String): StarterFrcMotionPreset = when (key.lowercase()) {
            "safe" -> SAFE
            "balanced" -> BALANCED
            "fast" -> FAST
            "adaptive" -> ADAPTIVE
            else -> throw IllegalArgumentException("Unknown FRC starter motion preset '$key'")
        }
    }
}

/** Bounded pose controller that emits immutable-state drive intents and stops neutral on every exit. */
internal class StarterFrcDriveToPoseTask(
    private val target: Pose2d,
    private val preset: StarterFrcMotionPreset,
) : Task {
    override val name: String = "Starter FRC drive to (%.2f, %.2f)".format(target.x, target.y)
    override val requiredResources: Long = TaskResources.DRIVE
    private val command = RobotAction.JoystickDriveIntent(0.0, 0.0, 0.0, isFieldCentric = true)
    private val commandResult = listOf<RobotAction>(command)
    private val neutral = RobotAction.JoystickDriveIntent(0.0, 0.0, 0.0, isFieldCentric = true)
    private val neutralResult = listOf<RobotAction>(neutral)
    private val xController = PIDController(0.0, 0.0, 0.0)
    private val yController = PIDController(0.0, 0.0, 0.0)
    private val headingController = PIDController(0.0, 0.0, 0.0)
    private val targetHeading = wrapAngle(target.heading.rawRadians)
    private var configuredTimeoutMs = MAX_DRIVE_DURATION_MS
    private var settledSamples = 0
    private var lastSettledObservationMs = -1L
    private var lastObservedTimestampMs = -1L
    private var lastControllerTimestampMs = -1L
    private var previousXVelocity = 0.0
    private var previousYVelocity = 0.0
    private var previousAngularVelocity = 0.0

    init {
        require(target.x.isFinite() && target.y.isFinite() && target.heading.rawRadians.isFinite()) {
            "FRC drive target must contain finite coordinates and heading"
        }
        headingController.enableContinuousInput(-PI, PI)
    }

    override fun withTimeout(ms: Long): Task {
        super.withTimeout(ms)
        configuredTimeoutMs = ms
        return this
    }

    override fun initialize(state: RobotState): List<RobotAction> {
        // Terminal cleanup removes registry deadlines. Reapply the configured duration on reuse.
        super.withTimeout(configuredTimeoutMs)
        super.initialize(state)
        resetControlHistory()
        return emptyList()
    }

    private fun resetControlHistory() {
        settledSamples = 0
        lastSettledObservationMs = -1L
        lastObservedTimestampMs = -1L
        lastControllerTimestampMs = -1L
        previousXVelocity = 0.0
        previousYVelocity = 0.0
        previousAngularVelocity = 0.0
        xController.reset()
        yController.reset()
        headingController.reset()
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        if (!usable(state, RobotClock.currentTimeMillis(), elapsedMs)) return false
        val pose = state.drive.poseEstimator
        if (pose.lastObservationTimestampMs == lastSettledObservationMs) return false
        lastSettledObservationMs = pose.lastObservationTimestampMs
        val positionReady = hypot(target.x - pose.estimatedPoseX, target.y - pose.estimatedPoseY) <= POSITION_TOLERANCE_METERS
        val headingReady = abs(wrapAngle(targetHeading - wrapAngle(pose.estimatedPoseHeading))) <= HEADING_TOLERANCE_RADIANS
        settledSamples = if (positionReady && headingReady) settledSamples + 1 else 0
        return settledSamples >= REQUIRED_SETTLED_SAMPLES
    }

    override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        super.execute(state, elapsedMs)
        val now = RobotClock.currentTimeMillis()
        if (!usable(state, now, elapsedMs)) return neutralAt(now)
        val pose = state.drive.poseEstimator
        // The generated typed profile is the experiment boundary. Presets remain conservative
        // envelopes within that reviewed global scale, so a live-safe change affects real behavior.
        val tunedScale = state.tuning.drive.pathVelocityScale.coerceIn(0.0, 1.0)
        val maximumLinear = GeneratedAresDrivebaseConfig.MAX_LINEAR_SPEED_METERS_PER_SECOND *
            preset.speedScale * tunedScale
        val maximumAngular = GeneratedAresDrivebaseConfig.MAX_ANGULAR_SPEED_RADIANS_PER_SECOND *
            preset.speedScale * tunedScale
        // A repeated clock sample cannot integrate PID state or grow the acceleration ramp.
        if (now == lastControllerTimestampMs) {
            return limitedCommand(previousXVelocity, previousYVelocity, previousAngularVelocity,
                maximumLinear, maximumAngular, now)
        }
        val dtSeconds = if (lastControllerTimestampMs < 0L) {
            NOMINAL_DT_SECONDS
        } else {
            ((now - lastControllerTimestampMs) / 1000.0).coerceAtMost(MAX_DT_SECONDS)
        }
        lastControllerTimestampMs = now
        val translationGains = state.tuning.drive.pathTranslationGains
        xController.p = translationGains.kP
        xController.i = translationGains.kI
        xController.d = translationGains.kD
        yController.p = translationGains.kP
        yController.i = translationGains.kI
        yController.d = translationGains.kD
        xController.setOutputLimits(-maximumLinear, maximumLinear)
        yController.setOutputLimits(-maximumLinear, maximumLinear)
        val rotationGains = state.tuning.drive.pathRotationGains
        headingController.p = rotationGains.kP
        headingController.i = rotationGains.kI
        headingController.d = rotationGains.kD
        headingController.setOutputLimits(-maximumAngular, maximumAngular)

        var requestedX = xController.calculate(pose.estimatedPoseX, target.x, dtSeconds)
        var requestedY = yController.calculate(pose.estimatedPoseY, target.y, dtSeconds)
        val requestedOmega = headingController.calculate(wrapAngle(pose.estimatedPoseHeading), targetHeading, dtSeconds)
        if (!xController.lastCalculationValid || !yController.lastCalculationValid || !headingController.lastCalculationValid) {
            TaskStateMachine.markFailed(this)
            return neutralAt(now)
        }
        val requestedMagnitude = hypot(requestedX, requestedY)
        if (requestedMagnitude > maximumLinear) {
            val scale = maximumLinear / requestedMagnitude
            requestedX *= scale
            requestedY *= scale
        }
        val accelerationLimit = state.tuning.drive.pathAccelerationLimit.coerceIn(0.05, 20.0)
        val translationDeltaLimit = accelerationLimit * dtSeconds
        val deltaX = requestedX - previousXVelocity
        val deltaY = requestedY - previousYVelocity
        val deltaMagnitude = hypot(deltaX, deltaY)
        val translationScale = if (deltaMagnitude > translationDeltaLimit && deltaMagnitude > 0.0) {
            translationDeltaLimit / deltaMagnitude
        } else 1.0
        val angularAccelerationLimit = accelerationLimit *
            (GeneratedAresDrivebaseConfig.MAX_ANGULAR_SPEED_RADIANS_PER_SECOND /
                GeneratedAresDrivebaseConfig.MAX_LINEAR_SPEED_METERS_PER_SECOND.coerceAtLeast(0.01))
        val angularDeltaLimit = angularAccelerationLimit * dtSeconds
        return limitedCommand(previousXVelocity + deltaX * translationScale,
            previousYVelocity + deltaY * translationScale,
            previousAngularVelocity + (requestedOmega - previousAngularVelocity).coerceIn(-angularDeltaLimit, angularDeltaLimit),
            maximumLinear, maximumAngular, now)
    }

    /** Tightening a safety envelope wins over the normal acceleration ramp, including scale zero. */
    private fun limitedCommand(x: Double, y: Double, omega: Double, maximumLinear: Double, maximumAngular: Double,
        now: Long): List<RobotAction> {
        val magnitude = hypot(x, y)
        val scale = if (magnitude > maximumLinear) maximumLinear / magnitude else 1.0
        command.targetXVelocity = x * scale
        command.targetYVelocity = y * scale
        command.targetAngularVelocity = omega.coerceIn(-maximumAngular, maximumAngular)
        previousXVelocity = command.targetXVelocity
        previousYVelocity = command.targetYVelocity
        previousAngularVelocity = command.targetAngularVelocity
        command.timestampMs = now
        return commandResult
    }

    /** Validate the raw snapshot before wrapping angles can hide invalid input. */
    private fun usable(state: RobotState, now: Long, elapsedMs: Long): Boolean {
        if (TaskStateMachine.getStatus(this) != TaskStatus.RUNNING) return false
        val pose = state.drive.poseEstimator
        val observation = pose.lastObservationTimestampMs
        val tuning = state.tuning.drive
        val translation = tuning.pathTranslationGains
        val rotation = tuning.pathRotationGains
        if (elapsedMs < 0L || elapsedMs > configuredTimeoutMs ||
            !state.drive.measuredMotionValid || observation < 0L || now < observation ||
            now - observation > GeneratedAresDrivebaseConfig.STALE_FEEDBACK_TIMEOUT_MS ||
            observation < lastObservedTimestampMs || now < lastControllerTimestampMs ||
            !pose.estimatedPoseX.isFinite() || !pose.estimatedPoseY.isFinite() || !pose.estimatedPoseHeading.isFinite() ||
            !tuning.pathVelocityScale.isFinite() || !tuning.pathAccelerationLimit.isFinite() || tuning.pathAccelerationLimit <= 0.0 ||
            !translation.kP.isFinite() || !translation.kI.isFinite() || !translation.kD.isFinite() ||
            !rotation.kP.isFinite() || !rotation.kI.isFinite() || !rotation.kD.isFinite()) {
            TaskStateMachine.markFailed(this)
            return false
        }
        lastObservedTimestampMs = observation
        return true
    }

    private fun neutralAt(now: Long): List<RobotAction> {
        neutral.timestampMs = now
        return neutralResult
    }

    override fun pause(state: RobotState): List<RobotAction> {
        resetControlHistory()
        return neutralAt(RobotClock.currentTimeMillis())
    }

    override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        super.end(state, interrupted)
        return neutralAt(RobotClock.currentTimeMillis())
    }

    override fun releaseRuntimeState() {
        resetControlHistory()
        super.releaseRuntimeState()
    }

    companion object {
        const val POSITION_TOLERANCE_METERS = 0.05
        private val HEADING_TOLERANCE_RADIANS = Math.toRadians(2.0)
        private const val REQUIRED_SETTLED_SAMPLES = 3
        private const val MAX_DRIVE_DURATION_MS = 10_000L
        private const val NOMINAL_DT_SECONDS = 0.02
        private const val MAX_DT_SECONDS = 0.05
    }
}

/** Starts one generated action at translation progress; the accepted position tolerance is the endpoint. */
internal class StarterFrcDriveMarkerTask(
    private val target: Pose2d,
    private val progress: Double,
    private val action: Task,
) : Task {
    override val name: String = "FRC drive marker ${action.name} at ${(progress * 100.0).toInt()}%"
    override val requiredResources: Long = action.requiredResources
    private var executor = TaskExecutor()
    private var startDistance = 0.0
    private var triggered = false
    private var childReleased = false
    private var ended = false
    private var lastProgressObservationMs = -1L
    private var configuredTimeoutMs = -1L

    init {
        require(progress.isFinite() && progress in 0.0..1.0) { "Marker progress must be in [0, 1]" }
        require(target.x.isFinite() && target.y.isFinite() && target.heading.rawRadians.isFinite()) {
            "Marker target must be finite"
        }
    }

    override fun initialize(state: RobotState): List<RobotAction> {
        check(executor.size == 0) { "Cannot reinitialize a marker with an active action" }
        if (configuredTimeoutMs >= 0L) super.withTimeout(configuredTimeoutMs)
        super.initialize(state)
        executor.resume()
        triggered = false
        childReleased = false
        ended = false
        lastProgressObservationMs = -1L
        startDistance = remainingDistance(state)
        return emptyList()
    }

    override fun withTimeout(ms: Long): Task {
        super.withTimeout(ms)
        configuredTimeoutMs = ms
        return this
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        propagateChildStatus()
        return !ended && TaskStateMachine.getStatus(this) == TaskStatus.RUNNING && triggered &&
            executor.size == 0 && TaskStateMachine.getStatus(action) == TaskStatus.COMPLETED
    }

    override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        super.execute(state, elapsedMs)
        if (ended || TaskStateMachine.getStatus(this) != TaskStatus.RUNNING) {
            return if (executor.size > 0) executor.cancelAll(state) else emptyList()
        }
        if (!triggered) {
            val remaining = remainingDistance(state)
            if (TaskStateMachine.getStatus(this) != TaskStatus.RUNNING) return emptyList()
            val completed = if (remaining <= StarterFrcDriveToPoseTask.POSITION_TOLERANCE_METERS || startDistance <= 1e-9) {
                1.0
            } else (1.0 - remaining / startDistance).coerceIn(0.0, 1.0)
            if (completed >= progress) {
                executor.addTask(action)
                triggered = true
            }
        }
        if (!triggered) return emptyList()
        val actions = executor.update(state, RobotClock.currentTimeMillis())
        propagateChildStatus()
        return actions
    }

    private fun remainingDistance(state: RobotState): Double {
        val pose = state.drive.poseEstimator
        val now = RobotClock.currentTimeMillis()
        val observation = pose.lastObservationTimestampMs
        val remaining = hypot(target.x - pose.estimatedPoseX, target.y - pose.estimatedPoseY)
        if (!state.drive.measuredMotionValid || observation < 0L || observation > now ||
            now - observation > GeneratedAresDrivebaseConfig.STALE_FEEDBACK_TIMEOUT_MS ||
            observation < lastProgressObservationMs || !remaining.isFinite() || !pose.estimatedPoseHeading.isFinite()) {
            TaskStateMachine.markFailed(this)
        } else lastProgressObservationMs = observation
        return remaining
    }

    private fun propagateChildStatus() {
        if (!triggered || TaskStateMachine.getStatus(this) != TaskStatus.RUNNING) return
        when (TaskStateMachine.getStatus(action)) {
            TaskStatus.FAILED -> TaskStateMachine.markFailed(this)
            TaskStatus.CANCELLED -> TaskStateMachine.transitionTo(this, TaskStatus.CANCELLED)
            else -> Unit
        }
    }

    override fun pause(state: RobotState): List<RobotAction> {
        if (!triggered || executor.size == 0 || ended) return emptyList()
        try { return action.pause(state) } finally { executor.suspend() }
    }

    override fun resume(state: RobotState): List<RobotAction> {
        if (!triggered || executor.size == 0 || ended) return emptyList()
        executor.resume()
        return action.resume(state)
    }

    override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        if (ended) return emptyList()
        propagateChildStatus()
        ended = true
        if (!interrupted && (!triggered || executor.size > 0 || TaskStateMachine.getStatus(action) != TaskStatus.COMPLETED)) {
            TaskStateMachine.markFailed(this)
        }
        var cleanup: List<RobotAction> = emptyList()
        var failure: Throwable? = null
        try {
            if (executor.size > 0) {
                cleanup = executor.cancelAll(state)
            } else if (!triggered && !childReleased) {
                childReleased = true
                action.releaseRuntimeState()
            }
        } catch (error: Throwable) { failure = retainStarterFailure(failure, error) }
        try { super.end(state, interrupted) }
        catch (error: Throwable) { failure = retainStarterFailure(failure, error) }
        failure?.let { throw it }
        return cleanup
    }

    override fun releaseRuntimeState() {
        try {
            // Like Task.cancel(), metadata release alone cannot dispatch hardware cleanup actions.
            if (!childReleased && (!triggered || executor.size > 0)) {
                childReleased = true
                if (triggered) action.cancel() else action.releaseRuntimeState()
            }
        } finally {
            if (executor.size > 0) executor = TaskExecutor()
            startDistance = 0.0
            triggered = false
            childReleased = true
            ended = true
            lastProgressObservationMs = -1L
            super.releaseRuntimeState()
        }
    }
}

/** Owns FRC match-mode selection and the generated routine lifecycle for the generic starter. */
internal class StarterFrcAutonomousRuntime(
    private val robot: StarterRobotRuntime,
    private val simulation: StarterDriveSimulation,
    private val generatedControls: FrcGeneratedProjectControlsRuntime<GeneratedAresProjectCapabilities>,
    private val capabilities: StarterGeneratedCapabilities,
    private val isSimulation: Boolean,
    entries: List<AutonomousCatalogEntry> = GeneratedAresProject.autonomousEntries,
    defaultEntryId: String? = GeneratedAresProject.DEFAULT_AUTONOMOUS_ENTRY_ID,
    private val selectionProvider: () -> String = ::dashboardSelection,
) {
    private val selector = StarterFrcAutonomousSelector(entries, defaultEntryId)
    private var activeExecutionId: Long? = null
    private var selectedEntryId = defaultEntryId ?: "do-nothing"
    private var finished = true
    private var startedAtMs = -1L

    fun publishCatalog() {
        robot.telemetry.putString("ARES/Auto/AvailableDocuments", selector.availableEntryIds.joinToString(","))
        robot.telemetry.putString("ARES/Auto/Source", "generated:${GeneratedAresProject.CONTENT_SHA256}")
        publishStatus("Idle")
    }

    fun autonomousInit() {
        stop("Autonomous reinitialized", status = "Starting")
        try {
            val selection = selector.resolve(selectionProvider())
            val entry = selection.entry
            selectedEntryId = entry.entryId
            capabilities.configureAutonomous(entry, robot.store.state.drive.alliance)
            val now = RobotClock.currentTimeMillis()
            if (shouldSeedStarterFrcAutonomousPose(entry)) {
                val startPose = capabilities.transform(entry.startingPose)
                if (isSimulation) simulation.resetPose(startPose.x, startPose.y, startPose.heading.radians)
                robot.store.dispatch(
                    RobotAction.PoseUpdate(
                        xMeters = startPose.x,
                        yMeters = startPose.y,
                        headingRadians = startPose.heading.radians,
                        timestampMs = now,
                        isReset = true,
                        isExternalEstimate = true,
                        applyControlHubGyroCorrection = false,
                        motionMeasurementsValid = true,
                        imuMeasurementsValid = true,
                    )
                )
            }
            when (val request = generatedControls.requestRoutine(entry.routineId)) {
                is RoutineRequestResult.Accepted -> activeExecutionId = request.executionId
                is RoutineRequestResult.AlreadyRunning -> activeExecutionId = request.executionId
                is RoutineRequestResult.Rejected -> error(request.issues.joinToString("; ") { it.message })
            }
            startedAtMs = now
            finished = false
            publishStatus(if (selection.usedFallback) "Running fallback" else "Running")
        } catch (failure: Throwable) {
            fail("Autonomous preflight failed: ${failure.message ?: failure::class.java.simpleName}")
        }
    }

    fun autonomousPeriodic() {
        if (finished) return
        val now = RobotClock.currentTimeMillis()
        if (startedAtMs < 0L || now < startedAtMs || now - startedAtMs > MATCH_AUTONOMOUS_LIMIT_MS) {
            fail("Autonomous exceeded the 14.8 second match-safe limit")
            return
        }
        val executionId = activeExecutionId ?: run {
            fail("Autonomous routine was not armed")
            return
        }
        try {
            generatedControls.updateRoutines()
            val active = robot.store.state.routineState.executions[executionId]
            if (active != null) {
                robot.telemetry.putString("ARES/Auto/ActiveTask", active.activeStepPath ?: active.routineId)
                return
            }
            val terminal = robot.store.state.routineState.lastTerminalExecution
            if (terminal?.executionId != executionId) {
                fail("Autonomous ended without a matching terminal result")
                return
            }
            when (terminal.status) {
                RoutineExecutionStatus.COMPLETED -> {
                    finished = true
                    activeExecutionId = null
                    capabilities.clearAutonomous()
                    robot.safeHardware()
                    publishStatus("Complete")
                }
                RoutineExecutionStatus.FAILED -> fail(terminal.message ?: "Autonomous task failed")
                RoutineExecutionStatus.CANCELLED -> fail(terminal.message ?: "Autonomous was cancelled")
                RoutineExecutionStatus.REQUESTED,
                RoutineExecutionStatus.RUNNING -> fail("Autonomous left the active set before completion")
            }
        } catch (failure: Throwable) {
            fail("Autonomous runtime failed: ${failure.message ?: failure::class.java.simpleName}")
        }
    }

    fun stop(reason: String, status: String = "Stopped") {
        generatedControls.cancelAll(reason)
        activeExecutionId = null
        startedAtMs = -1L
        finished = true
        capabilities.clearAutonomous()
        robot.safeHardware()
        publishStatus(status)
    }

    private fun fail(message: String) {
        stop(message, status = "Blocked")
        robot.telemetry.putString("ARES/Auto/Error", message)
        runCatching { DriverStation.reportError("ARES auto: $message", false) }
    }

    private fun publishStatus(status: String) {
        robot.telemetry.putString("ARES/Auto/Selected", selectedEntryId)
        robot.telemetry.putString("ARES/Auto/Status", status)
    }

    internal val isFinishedForTest: Boolean get() = finished
    internal val selectedEntryIdForTest: String get() = selectedEntryId

    private companion object {
        const val ARES_REQUESTED_AUTO_ENTRY = "ARES/Auto/Requested"
        const val SMART_DASHBOARD_TABLE = "SmartDashboard"
        const val SELECTED_AUTO_ENTRY = "SelectedAuto"
        const val MATCH_AUTONOMOUS_LIMIT_MS = 14_800L

        fun dashboardSelection(): String {
            val fallback = GeneratedAresProject.DEFAULT_AUTONOMOUS_ENTRY_ID ?: "do-nothing"
            return runCatching {
                val instance = NetworkTableInstance.getDefault()
                resolveStarterFrcAutonomousRequest(
                    aresRequested = instance.getEntry(ARES_REQUESTED_AUTO_ENTRY).getString(""),
                    smartDashboardRequested = instance.getTable(SMART_DASHBOARD_TABLE)
                        .getEntry(SELECTED_AUTO_ENTRY)
                        .getString(""),
                    fallback = fallback,
                )
            }.getOrDefault(fallback)
        }
    }
}
