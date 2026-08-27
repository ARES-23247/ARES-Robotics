package org.aresfirst.starter.frc

import com.areslib.action.RobotAction
import com.areslib.control.feedback.PIDController
import com.areslib.math.coordinate.AllianceMirroring
import com.areslib.math.coordinate.CoordinateTransformers
import com.areslib.math.coordinate.FieldOrigin
import com.areslib.math.coordinate.FieldSymmetry
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineAlliance
import com.areslib.routine.RoutineDriveStep
import com.areslib.routine.RoutinePose
import com.areslib.routine.RoutineRequestResult
import com.areslib.routine.RoutineStartPolicy
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

internal data class StarterAutonomousSelection(
    val entry: AutonomousCatalogEntry,
    val requestedId: String,
    val usedFallback: Boolean,
)

/** Deterministic generated-catalog selector with an always-safe fallback. */
internal class StarterFrcAutonomousSelector(
    entries: List<AutonomousCatalogEntry>,
    defaultEntryId: String?,
) {
    private val enabledEntries = entries.filter(AutonomousCatalogEntry::enabled)
        .sortedWith(compareBy<AutonomousCatalogEntry> { it.sortOrder }.thenBy { it.entryId })
    private val entriesById = enabledEntries.associateBy(AutonomousCatalogEntry::entryId)
    private val fallback = defaultEntryId?.let(entriesById::get)
        ?: entriesById[SAFE_FALLBACK_ENTRY_ID]
        ?: enabledEntries.firstOrNull()

    val availableEntryIds: List<String> = enabledEntries.map(AutonomousCatalogEntry::entryId)

    fun resolve(requestedId: String): StarterAutonomousSelection {
        val normalized = requestedId.trim()
        val requested = entriesById[normalized]
        val selected = requested ?: checkNotNull(fallback) {
            "Generated autonomous catalog has no enabled fail-safe entry"
        }
        return StarterAutonomousSelection(selected, normalized, requested == null)
    }

    private companion object {
        const val SAFE_FALLBACK_ENTRY_ID = "do-nothing"
    }
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
    private var settledSamples = 0
    private var lastControllerTimestampMs = -1L
    private var previousXVelocity = 0.0
    private var previousYVelocity = 0.0
    private var previousAngularVelocity = 0.0

    init {
        withTimeout(MAX_DRIVE_DURATION_MS)
        headingController.enableContinuousInput(-PI, PI)
    }

    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        settledSamples = 0
        lastControllerTimestampMs = -1L
        previousXVelocity = 0.0
        previousYVelocity = 0.0
        previousAngularVelocity = 0.0
        xController.reset()
        yController.reset()
        headingController.reset()
        return emptyList()
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        val pose = state.drive.poseEstimator.estimatedPose
        val positionReady = hypot(target.x - pose.x, target.y - pose.y) <= POSITION_TOLERANCE_METERS
        val headingReady = abs(wrapRadians(target.heading.radians - pose.heading.radians)) <= HEADING_TOLERANCE_RADIANS
        settledSamples = if (positionReady && headingReady) settledSamples + 1 else 0
        return settledSamples >= REQUIRED_SETTLED_SAMPLES
    }

    override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        super.execute(state, elapsedMs)
        val now = RobotClock.currentTimeMillis()
        val observation = state.drive.poseEstimator.lastObservationTimestampMs
        if (!state.drive.measuredMotionValid || observation < 0L || now < observation ||
            now - observation > GeneratedAresDrivebaseConfig.STALE_FEEDBACK_TIMEOUT_MS
        ) {
            TaskStateMachine.markFailed(this)
            return neutralResult
        }
        val pose = state.drive.poseEstimator.estimatedPose
        // The generated typed profile is the experiment boundary. Presets remain conservative
        // envelopes within that reviewed global scale, so a live-safe change affects real behavior.
        val tunedScale = state.tuning.drive.pathVelocityScale.coerceIn(0.0, 1.0)
        val maximumLinear = GeneratedAresDrivebaseConfig.MAX_LINEAR_SPEED_METERS_PER_SECOND *
            preset.speedScale * tunedScale
        val maximumAngular = GeneratedAresDrivebaseConfig.MAX_ANGULAR_SPEED_RADIANS_PER_SECOND *
            preset.speedScale * tunedScale
        val dtSeconds = if (lastControllerTimestampMs < 0L || now <= lastControllerTimestampMs) {
            NOMINAL_DT_SECONDS
        } else {
            ((now - lastControllerTimestampMs) / 1000.0).coerceIn(MIN_DT_SECONDS, MAX_DT_SECONDS)
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

        val requestedX = xController.calculate(pose.x, target.x, dtSeconds)
        val requestedY = yController.calculate(pose.y, target.y, dtSeconds)
        val requestedOmega = headingController.calculate(pose.heading.radians, target.heading.radians, dtSeconds)
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
        command.targetXVelocity = previousXVelocity + deltaX * translationScale
        command.targetYVelocity = previousYVelocity + deltaY * translationScale
        command.targetAngularVelocity = previousAngularVelocity +
            (requestedOmega - previousAngularVelocity).coerceIn(-angularDeltaLimit, angularDeltaLimit)
        previousXVelocity = command.targetXVelocity
        previousYVelocity = command.targetYVelocity
        previousAngularVelocity = command.targetAngularVelocity
        command.timestampMs = now
        return commandResult
    }

    override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        super.end(state, interrupted)
        neutral.timestampMs = RobotClock.currentTimeMillis()
        return neutralResult
    }

    override fun releaseRuntimeState() {
        settledSamples = 0
        lastControllerTimestampMs = -1L
        previousXVelocity = 0.0
        previousYVelocity = 0.0
        previousAngularVelocity = 0.0
        xController.reset()
        yController.reset()
        headingController.reset()
        super.releaseRuntimeState()
    }

    private companion object {
        const val POSITION_TOLERANCE_METERS = 0.05
        val HEADING_TOLERANCE_RADIANS = Math.toRadians(2.0)
        const val REQUIRED_SETTLED_SAMPLES = 3
        const val MAX_DRIVE_DURATION_MS = 10_000L
        const val NOMINAL_DT_SECONDS = 0.02
        const val MIN_DT_SECONDS = 0.001
        const val MAX_DT_SECONDS = 0.05
    }
}

/** Starts one generated action when drive progress crosses its declarative marker. */
private class StarterFrcDriveMarkerTask(
    private val target: Pose2d,
    private val progress: Double,
    private val action: Task,
) : Task {
    override val name: String = "FRC drive marker ${action.name} at ${(progress * 100.0).toInt()}%"
    override val requiredResources: Long = action.requiredResources
    private val executor = TaskExecutor()
    private var startDistance = 0.0
    private var triggered = false

    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        val pose = state.drive.poseEstimator.estimatedPose
        startDistance = hypot(target.x - pose.x, target.y - pose.y)
        triggered = false
        return emptyList()
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean = triggered && executor.size == 0

    override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        super.execute(state, elapsedMs)
        if (!triggered) {
            val pose = state.drive.poseEstimator.estimatedPose
            val remaining = hypot(target.x - pose.x, target.y - pose.y)
            val completed = if (startDistance <= 1e-9) 1.0 else (1.0 - remaining / startDistance).coerceIn(0.0, 1.0)
            if (completed >= progress) {
                executor.addTask(action)
                triggered = true
            }
        }
        return if (triggered) executor.update(state, RobotClock.currentTimeMillis()) else emptyList()
    }

    override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        val cleanup = if (interrupted && executor.size > 0) executor.cancelAll(state) else emptyList()
        super.end(state, interrupted)
        return cleanup
    }

    override fun releaseRuntimeState() {
        startDistance = 0.0
        triggered = false
        super.releaseRuntimeState()
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
            publishStatus(if (selection.usedFallback) "Running safe fallback" else "Running")
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

private fun wrapRadians(value: Double): Double {
    var wrapped = value
    while (wrapped > PI) wrapped -= 2.0 * PI
    while (wrapped < -PI) wrapped += 2.0 * PI
    return wrapped
}
