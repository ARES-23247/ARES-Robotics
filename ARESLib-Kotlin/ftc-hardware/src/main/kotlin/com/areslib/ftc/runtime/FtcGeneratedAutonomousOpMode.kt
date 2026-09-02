package com.areslib.ftc.runtime

import com.areslib.action.RobotAction
import com.areslib.ftc.FtcMecanumRobot
import com.areslib.ftc.photon.AresFtcRuntimeOptions
import com.areslib.ftc.photon.AresFtcRuntimeOptionsProvider
import com.areslib.math.coordinate.AllianceMirroring
import com.areslib.math.coordinate.FieldSymmetry
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.AutonomousCatalogResolver
import com.areslib.routine.RoutineAlliance
import com.areslib.routine.RoutineManager
import com.areslib.routine.RoutinePose
import com.areslib.routine.RoutineRequestResult
import com.areslib.routine.RoutineStartPolicy
import com.areslib.state.Alliance
import com.areslib.state.RoutineExecutionState
import com.areslib.state.RoutineExecutionStatus
import com.areslib.telemetry.RobotStatusTracker
import com.areslib.util.PoseStorage
import com.areslib.util.RobotClock
import com.qualcomm.robotcore.eventloop.opmode.OpMode

/** Immutable generated-project metadata consumed by the FTC autonomous lifecycle host. */
data class FtcAutonomousProjectDefinition(
    val entries: List<AutonomousCatalogEntry>,
    val defaultEntryId: String?,
    val contentSha256: String,
)

/** Narrow mechanical runtime surface required by the FTC OpMode lifecycle. */
interface FtcGeneratedAutonomousRuntime {
    val routineManager: RoutineManager

    fun updateTasks()

    fun cancelAll(reason: String)
}

/**
 * FTC-only lifecycle host for a generated autonomous project.
 *
 * The library owns deterministic INIT selection, deadlines, routine lifecycle, telemetry,
 * fail-closed stop behavior, and final-pose evidence. The season repository still owns the
 * robot facade, generated project metadata, field/bounds validation, generated task runtime,
 * hardware adapters, and simulator. This is intentionally not a universal FTC/FRC host.
 */
abstract class FtcGeneratedAutonomousOpMode<R : Any> : OpMode(), AresFtcRuntimeOptionsProvider {
    private companion object {
        const val DEFAULT_MAXIMUM_RUNTIME_SECONDS = 29.5
        const val OVERRUN_THRESHOLD_MS = 30L
        const val LOOP_TELEMETRY_PERIOD_MS = 100L
    }

    /** Reviewed transport/runtime policy supplied by the season project. */
    protected abstract val configuredRuntimeOptions: AresFtcRuntimeOptions

    final override val aresFtcRuntimeOptions: AresFtcRuntimeOptions
        get() = configuredRuntimeOptions

    /** Generated autonomous catalog and immutable content identity. */
    protected abstract val autonomousProject: FtcAutonomousProjectDefinition

    /** Optional fixed entry used by a narrow validation OpMode; null enables driver selection. */
    protected open val lockedAutonomousEntryId: String? = null

    /** Optional fixed alliance used by a narrow validation OpMode; null enables INIT toggling. */
    protected open val lockedAutonomousAlliance: Alliance? = null

    /** Competition-safe hard deadline. FTC autonomous cannot exceed 30 seconds. */
    protected open val maximumRuntimeSeconds: Double = DEFAULT_MAXIMUM_RUNTIME_SECONDS

    private var robot: R? = null
    private var generatedRuntime: FtcGeneratedAutonomousRuntime? = null
    private lateinit var selector: FtcAutonomousSelector
    private var configurationError: String? = null
    private var hardwareError: String? = null
    private var deadlineMs = 0L
    private var started = false
    private var finished = false
    private var poseIsUsable = true
    private var closed = false
    private var loopCount = 0L
    private var overrunCount = 0L
    private var lastDashboardRequest: String? = null
    private var lastPublishedSelection: String? = null
    private var lastPublishedStatus: String? = null
    private var lastPublishedDetail: String? = null
    private var activeExecutionId: Long? = null
    private var successfulCompletion = false
    private var lastLoopTelemetryMs = 0L

    /** Constructs the season facade. Kept overridable by the consumer for focused tests. */
    protected abstract fun buildRobot(): R

    /** Provides the shared mecanum safety, Redux, telemetry, and localization boundary. */
    protected abstract fun getMecanumRobot(robot: R): FtcMecanumRobot

    /** Runs one season-owned hardware/control frame. */
    protected abstract fun updateRobot(robot: R)

    /** Closes all season-owned resources. */
    protected abstract fun closeRobot(robot: R)

    /** Returns the season facade's latched update failure, if any. */
    protected abstract fun fatalUpdateFailure(robot: R): Throwable?

    /** Builds the project-specific generated task and routine adapter. */
    protected abstract fun createGeneratedRuntime(
        robot: R,
        entry: AutonomousCatalogEntry?,
        alliance: Alliance,
    ): FtcGeneratedAutonomousRuntime

    /** Resolves an authored pose using the season's active field symmetry. */
    protected open fun resolveAutonomousPose(
        entry: AutonomousCatalogEntry,
        alliance: Alliance,
        pose: RoutinePose = entry.startingPose,
    ): Pose2d = resolveFtcAutonomousPose(entry, alliance, pose)

    /** Returns every blocking field/project validation error for the current selection. */
    protected abstract fun validateAutonomousSelection(
        robot: R,
        entry: AutonomousCatalogEntry,
        alliance: Alliance,
    ): List<String>

    /** Builds hardware and the generated routine registry before the mode can be armed. */
    final override fun init() {
        require(maximumRuntimeSeconds.isFinite() && maximumRuntimeSeconds > 0.0 && maximumRuntimeSeconds <= 30.0) {
            "Autonomous maximum runtime must be finite and in (0, 30] seconds"
        }
        val builtRobot = buildRobot()
        robot = builtRobot
        val base = getMecanumRobot(builtRobot)
        base.mecanumIO.kS = base.driveFeedforward.kS.takeIf { it > 0.0 } ?: 0.05
        val project = autonomousProject
        selector = FtcAutonomousSelector(
            entries = project.entries,
            defaultEntryId = project.defaultEntryId,
            initialAlliance = lockedAutonomousAlliance ?: Alliance.RED,
            lockedEntryId = lockedAutonomousEntryId,
            lockedAlliance = lockedAutonomousAlliance,
        )
        configurationError = runCatching {
            rebuildSelectedConfiguration(builtRobot, "Autonomous initialized")
            generatedRuntime?.routineManager?.validateProject()
                ?.firstOrNull { it.severity == com.areslib.routine.RoutineValidationSeverity.ERROR }
                ?.let { error(it.message) }
        }.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName }
        publishNetworkCatalog(builtRobot)
        publishInitStatus()
    }

    /** Keeps hardware diagnostics and selection live while the Driver Station shows INIT. */
    final override fun init_loop() {
        val activeRobot = robot ?: return
        val base = getMecanumRobot(activeRobot)
        val externalAllianceChanged = synchronizeSelectorAlliance(activeRobot)
        val dashboardRequest = base.telemetryManager.nt4
            .getString("ARES/Input/selectedAuto", "")
            .trim()
        var selectionChanged = externalAllianceChanged
        if (dashboardRequest.isNotEmpty() && dashboardRequest != lastDashboardRequest) {
            lastDashboardRequest = dashboardRequest
            selectionChanged = selector.selectEntry(dashboardRequest) || selectionChanged
        }
        selectionChanged = selector.update(
            left = gamepad1.dpad_left,
            right = gamepad1.dpad_right,
            toggleAlliance = gamepad1.x && !externalAllianceChanged,
        ) || selectionChanged
        if (selectionChanged) {
            configurationError = runCatching {
                rebuildSelectedConfiguration(activeRobot, "Autonomous selection changed during INIT")
            }.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName }
        }
        hardwareError = runCatching { updateRobot(activeRobot) }
            .exceptionOrNull()
            ?.let { failure ->
                val detail = failure.message ?: failure::class.java.simpleName
                if (fatalUpdateFailure(activeRobot) != null) {
                    "Robot failure latched; restart the OpMode: $detail"
                } else {
                    "Robot initialization failed: $detail"
                }
            }
        publishInitStatus()
    }

    /** Starts the selected generated routine, or blocks and stops safely when preflight failed. */
    final override fun start() {
        started = true
        RobotStatusTracker.activeOpMode = "Auto"
        val activeRobot = robot ?: return
        val startSelectionError = runCatching {
            if (synchronizeSelectorAlliance(activeRobot)) {
                rebuildSelectedConfiguration(activeRobot, "Autonomous alliance changed before START")
                configurationError = null
            }
        }.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName }
        if (startSelectionError != null) configurationError = startSelectionError
        val runtime = generatedRuntime
        val entry = selector.selected
        val finalBoundsError = runCatching {
            if (entry != null) requireNoValidationErrors(activeRobot, entry)
        }.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName }
        if (finalBoundsError != null) configurationError = finalBoundsError
        val blockingError = configurationError ?: hardwareError
        if (entry == null || runtime == null || blockingError != null) {
            poseIsUsable = false
            val safetyFailure = runCatching { safeRobot(activeRobot) }.exceptionOrNull()
            val reportedError = listOfNotNull(
                blockingError ?: if (entry == null || runtime == null) {
                    "No enabled autonomous entry exists"
                } else null,
                safetyFailure?.let { "Safety stop failed: ${it.message ?: it::class.java.simpleName}" },
            ).joinToString("; ")
            if (safetyFailure != null) hardwareError = reportedError
            telemetry.addData("AUTO BLOCKED", reportedError)
            publishNetworkStatus("Blocked", reportedError)
            telemetry.update()
            finished = true
            requestOpModeStop()
            return
        }

        val base = getMecanumRobot(activeRobot)
        base.store.dispatch(RobotAction.SetAlliance(selector.alliance))
        base.visionTracker.hasInitializedPoseWithVision = true
        when (val request = runtime.routineManager.request(entry.routineId, RoutineStartPolicy.RESTART_EXISTING)) {
            is RoutineRequestResult.Accepted -> {
                activeExecutionId = request.executionId
                deadlineMs = RobotClock.currentTimeMillis() + (maximumRuntimeSeconds * 1_000.0).toLong()
                publishNetworkStatus("Running")
            }
            is RoutineRequestResult.AlreadyRunning -> {
                activeExecutionId = request.executionId
                deadlineMs = RobotClock.currentTimeMillis() + (maximumRuntimeSeconds * 1_000.0).toLong()
                publishNetworkStatus("Running")
            }
            is RoutineRequestResult.Rejected -> {
                configurationError = request.issues.joinToString("; ") { it.message }
                poseIsUsable = false
                finishActiveRun("Generated routine was rejected")
            }
        }
    }

    /** Advances generated tasks, applies their Redux actions, then runs one hardware frame. */
    final override fun loop() {
        if (finished) return
        val activeRobot = robot ?: return
        val runtime = generatedRuntime ?: return
        val base = getMecanumRobot(activeRobot)
        if (base.store.state.drive.alliance != selector.alliance) {
            base.store.dispatch(RobotAction.SetAlliance(selector.alliance))
        }
        val loopStartMs = RobotClock.currentTimeMillis()
        if (loopStartMs >= deadlineMs) {
            poseIsUsable = false
            finishActiveRun("Runtime limit reached; outputs stopped")
            return
        }

        try {
            runtime.updateTasks()
            updateRobot(activeRobot)
            val executionId = activeExecutionId
                ?: throw IllegalStateException("Autonomous routine has no retained execution ID")
            val terminal = base.store.state.routineState.lastTerminalExecution
            when (classifyFtcAutoTerminal(executionId, terminal)) {
                FtcAutoTerminalDecision.COMPLETED -> {
                    successfulCompletion = true
                    finishActiveRun("Complete")
                }
                FtcAutoTerminalDecision.FAILED,
                FtcAutoTerminalDecision.CANCELLED -> {
                    poseIsUsable = false
                    configurationError = terminal?.message
                        ?: "Routine ${terminal?.status?.name?.lowercase() ?: "failed"}"
                    finishActiveRun("Aborted: $configurationError")
                }
                FtcAutoTerminalDecision.RUNNING -> Unit
            }
            if (finished) return
            if (runtime.routineManager.activeCount == 0 && runtime.routineManager.queuedCount == 0) {
                throw IllegalStateException("Routine ended without a matching terminal lifecycle event")
            }
        } catch (error: Throwable) {
            poseIsUsable = false
            configurationError = error.message ?: error::class.java.simpleName
            finishActiveRun("Aborted: $configurationError")
            return
        }

        val nowMs = RobotClock.currentTimeMillis()
        val elapsedMs = nowMs - loopStartMs
        loopCount++
        if (elapsedMs > OVERRUN_THRESHOLD_MS) overrunCount++
        if (nowMs - lastLoopTelemetryMs >= LOOP_TELEMETRY_PERIOD_MS) {
            lastLoopTelemetryMs = nowMs
            val pose = base.drive.odometryPose
            telemetry.addData("Pose X (m)", pose.x)
            telemetry.addData("Pose Y (m)", pose.y)
            telemetry.addData("Heading (deg)", Math.toDegrees(pose.heading.radians))
            telemetry.addData("Loop duration (ms)", elapsedMs)
            telemetry.addData("Loop overruns", overrunCount)
            telemetry.update()
        }
    }

    /** Cancels generated work, persists a usable final pose, and closes owned resources once. */
    final override fun stop() {
        if (closed) return
        closed = true
        val activeRobot = robot
        var firstFailure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                val primary = firstFailure
                if (primary == null) firstFailure = failure
                else if (primary !== failure) primary.addSuppressed(failure)
            }
        }

        if (!finished) attempt { publishNetworkStatus("Stopped") }
        attempt { generatedRuntime?.cancelAll("FTC OpMode stopped") }
        if (activeRobot != null) attempt { safeRobot(activeRobot) }
        val finalPose = activeRobot?.let(::getMecanumRobot)?.drive?.odometryPose
        if (activeRobot != null) attempt { closeRobot(activeRobot) }
        attempt { com.areslib.ftc.photon.AresPhotonCore.disable() }

        generatedRuntime = null
        robot = null
        val cleanupFailure = firstFailure
        if (cleanupFailure != null) {
            poseIsUsable = false
            successfulCompletion = false
            configurationError = "Autonomous cleanup failed: ${cleanupFailure.message ?: cleanupFailure::class.java.simpleName}"
        }
        if (finalPose != null && cleanupFailure == null &&
            shouldPersistFtcAutoPose(started, successfulCompletion, poseIsUsable, configurationError)
        ) {
            PoseStorage.currentPose = finalPose
            PoseStorage.alliance = selector.alliance
            PoseStorage.hasValidPose = true
        } else {
            PoseStorage.hasValidPose = false
        }
        if (cleanupFailure != null) {
            runCatching {
                telemetry.addData("AUTO CLEANUP FAILED", configurationError)
                telemetry.update()
            }
            throw cleanupFailure
        }
    }

    private fun seedSelectedPose(activeRobot: R) {
        val base = getMecanumRobot(activeRobot)
        base.store.dispatch(RobotAction.SetAlliance(selector.alliance))
        val entry = selector.selected ?: return
        base.resetPose(resolveAutonomousPose(entry, selector.alliance), resetHardware = true)
    }

    private fun synchronizeSelectorAlliance(activeRobot: R): Boolean {
        val base = getMecanumRobot(activeRobot)
        val lockedAlliance = lockedAutonomousAlliance
        if (lockedAlliance != null) {
            if (base.store.state.drive.alliance != lockedAlliance) {
                base.store.dispatch(RobotAction.SetAlliance(lockedAlliance))
            }
            return false
        }
        return selector.selectAlliance(base.store.state.drive.alliance)
    }

    private fun rebuildSelectedConfiguration(activeRobot: R, cancellationReason: String) {
        generatedRuntime?.cancelAll(cancellationReason)
        // Alliance and authored start pose are selection state, not evidence that the routine is
        // runnable. Apply them before validation so INIT, diagnostics, and locked validation
        // OpModes remain truthful even when missing hardware correctly blocks execution.
        seedSelectedPose(activeRobot)
        selector.selected?.let { requireNoValidationErrors(activeRobot, it) }
        generatedRuntime = createGeneratedRuntime(activeRobot, selector.selected, selector.alliance)
    }

    private fun requireNoValidationErrors(activeRobot: R, entry: AutonomousCatalogEntry) {
        val errors = validateAutonomousSelection(activeRobot, entry, selector.alliance)
        require(errors.isEmpty()) { errors.joinToString("; ") }
    }

    private fun finishActiveRun(status: String) {
        if (finished) return
        finished = true
        var firstFailure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                val primary = firstFailure
                if (primary == null) firstFailure = failure
                else if (primary !== failure) primary.addSuppressed(failure)
            }
        }
        attempt { generatedRuntime?.cancelAll(status) }
        robot?.let { attempt { safeRobot(it) } }
        val finishFailure = firstFailure
        val reportedStatus = if (finishFailure == null) {
            status
        } else {
            successfulCompletion = false
            poseIsUsable = false
            configurationError = "Autonomous stop failed: ${finishFailure.message ?: finishFailure::class.java.simpleName}"
            "Aborted: $configurationError"
        }
        if (!successfulCompletion) PoseStorage.hasValidPose = false
        telemetry.addData("Auto", reportedStatus)
        val networkStatus = when {
                successfulCompletion && finishFailure == null -> "Complete"
                started -> "Failed"
                else -> "Blocked"
            }
        publishNetworkStatus(networkStatus, if (networkStatus == "Complete") reportedStatus else configurationError ?: reportedStatus)
        telemetry.update()
        requestOpModeStop()
    }

    /** Stops shared drive hardware and every registered season subsystem. */
    protected open fun safeRobot(activeRobot: R) {
        val base = getMecanumRobot(activeRobot)
        var firstFailure: Throwable? = null
        try {
            base.safeAll()
        } catch (failure: Throwable) {
            firstFailure = failure
        }
        try {
            base.safeHardware()
        } catch (failure: Throwable) {
            val primary = firstFailure
            if (primary == null) firstFailure = failure
            else if (primary !== failure) primary.addSuppressed(failure)
        }
        firstFailure?.let { throw it }
    }

    private fun publishInitStatus() {
        val entry = selector.selected
        telemetry.addData("Autonomous", entry?.displayName ?: "Safe do-nothing")
        telemetry.addData("Routine ID", entry?.routineId ?: "none")
        telemetry.addData("Alliance", selector.alliance)
        val error = configurationError ?: hardwareError
        if (error == null && entry != null) {
            telemetry.addData("Status", "READY - press START")
            if (lockedAutonomousEntryId == null) telemetry.addData("Select", "D-pad left/right")
            if (lockedAutonomousAlliance == null) telemetry.addData("Alliance control", "X toggles red/blue")
        } else {
            telemetry.addData("Status", "BLOCKED")
            telemetry.addData("Fix", error ?: "Enable at least one autonomous entry in the project")
        }
        publishNetworkStatus(
            status = if (error == null && entry != null) "Ready" else "Blocked",
            detail = error.orEmpty(),
        )
        telemetry.update()
    }

    private fun publishNetworkCatalog(activeRobot: R) {
        val project = autonomousProject
        val enabledIds = project.entries
            .asSequence()
            .filter(AutonomousCatalogEntry::enabled)
            .sortedWith(compareBy<AutonomousCatalogEntry> { it.sortOrder }.thenBy { it.entryId })
            .joinToString(",") { it.entryId }
        val nt4 = getMecanumRobot(activeRobot).telemetryManager.nt4
        nt4.putString("ARES/Auto/AvailableDocuments", enabledIds)
        nt4.putString("ARES/Auto/Source", "generated:${project.contentSha256}")
        nt4.update()
    }

    private fun publishNetworkStatus(status: String, detail: String = "") {
        val activeRobot = robot ?: return
        val nt4 = getMecanumRobot(activeRobot).telemetryManager.nt4
        val selectedId = selector.selected?.entryId.orEmpty()
        if (selectedId == lastPublishedSelection && status == lastPublishedStatus && detail == lastPublishedDetail) return
        nt4.putString("ARES/Auto/Selected", selectedId)
        nt4.putString("ARES/Auto/Status", status)
        nt4.putString("ARES/Auto/Detail", detail)
        nt4.update()
        if (status == "Blocked" || status == "Failed") {
            println("[ARES FTC Auto] $status: ${detail.ifBlank { "No detail reported" }}")
        }
        lastPublishedSelection = selectedId
        lastPublishedStatus = status
        lastPublishedDetail = detail
    }
}

/** Deterministic INIT selector over enabled generated autonomous entries. */
class FtcAutonomousSelector(
    entries: List<AutonomousCatalogEntry>,
    defaultEntryId: String?,
    initialAlliance: Alliance,
    private val lockedEntryId: String? = null,
    private val lockedAlliance: Alliance? = null,
) {
    val entries: List<AutonomousCatalogEntry> = AutonomousCatalogResolver(entries, defaultEntryId).enabledEntries

    private var index = selectInitialIndex(lockedEntryId ?: defaultEntryId)
    private var previousLeft = false
    private var previousRight = false
    private var previousAllianceToggle = false

    var alliance: Alliance = lockedAlliance ?: initialAlliance
        private set

    val selected: AutonomousCatalogEntry?
        get() = entries.getOrNull(index)

    fun selectEntry(entryId: String): Boolean {
        if (lockedEntryId != null) return false
        val requestedIndex = entries.indexOfFirst { it.entryId == entryId }
        if (requestedIndex < 0 || requestedIndex == index) return false
        index = requestedIndex
        return true
    }

    fun selectAlliance(requestedAlliance: Alliance): Boolean {
        if (lockedAlliance != null || requestedAlliance == alliance) return false
        alliance = requestedAlliance
        return true
    }

    fun update(left: Boolean, right: Boolean, toggleAlliance: Boolean): Boolean {
        var changed = false
        if (lockedEntryId == null && entries.size > 1) {
            if (left && !previousLeft) {
                index = (index - 1 + entries.size) % entries.size
                changed = true
            }
            if (right && !previousRight) {
                index = (index + 1) % entries.size
                changed = true
            }
        }
        if (lockedAlliance == null && toggleAlliance && !previousAllianceToggle) {
            alliance = if (alliance == Alliance.RED) Alliance.BLUE else Alliance.RED
            changed = true
        }
        previousLeft = left
        previousRight = right
        previousAllianceToggle = toggleAlliance
        return changed
    }

    private fun selectInitialIndex(requestedId: String?): Int {
        if (entries.isEmpty()) return -1
        val requestedIndex = entries.indexOfFirst { it.entryId == requestedId }
        if (requestedIndex >= 0) return requestedIndex
        return if (lockedEntryId != null) -1 else 0
    }
}

/** Resolves an authored catalog pose for the selected alliance. */
fun resolveFtcAutonomousPose(
    entry: AutonomousCatalogEntry,
    selectedAlliance: Alliance,
    pose: RoutinePose = entry.startingPose,
    symmetry: FieldSymmetry = com.areslib.state.RobotFieldManager.activeConfig.allianceSymmetry,
): Pose2d {
    val authoredAlliance = when (entry.authoredAlliance) {
        RoutineAlliance.RED -> Alliance.RED
        RoutineAlliance.BLUE -> Alliance.BLUE
    }
    val authoredPose = Pose2d(pose.xMeters, pose.yMeters, Rotation2d(pose.headingRadians))
    if (!entry.mirrorForOppositeAlliance || selectedAlliance == authoredAlliance) return authoredPose
    return AllianceMirroring.mirror(authoredPose, Alliance.RED, symmetry)
}

enum class FtcAutoTerminalDecision { RUNNING, COMPLETED, FAILED, CANCELLED }

/** Accepts terminal lifecycle evidence only for the exact retained routine execution. */
fun classifyFtcAutoTerminal(
    activeExecutionId: Long,
    terminal: RoutineExecutionState?,
): FtcAutoTerminalDecision {
    if (terminal?.executionId != activeExecutionId) return FtcAutoTerminalDecision.RUNNING
    return when (terminal.status) {
        RoutineExecutionStatus.COMPLETED -> FtcAutoTerminalDecision.COMPLETED
        RoutineExecutionStatus.FAILED -> FtcAutoTerminalDecision.FAILED
        RoutineExecutionStatus.CANCELLED -> FtcAutoTerminalDecision.CANCELLED
        else -> FtcAutoTerminalDecision.RUNNING
    }
}

/** A pose handoff is legal only after proven success; every abort/failure invalidates storage. */
fun shouldPersistFtcAutoPose(
    started: Boolean,
    successfulCompletion: Boolean,
    poseIsUsable: Boolean,
    configurationError: String?,
): Boolean = started && successfulCompletion && poseIsUsable && configurationError == null
