package org.aresfirst.marvin.robot

import com.areslib.action.RobotAction
import org.aresfirst.marvin.Dyn4jSimulation
import com.areslib.frc.FrcSwerveRobot
import org.aresfirst.marvin.generated.GeneratedAresProject
import org.aresfirst.marvin.generatedruntime.FrcGeneratedRoutineCapabilities
import org.aresfirst.marvin.generatedruntime.requireFrcRoutinePoseInsideField
import org.aresfirst.marvin.marvin.SetMechanismSafetyInhibit
import org.aresfirst.marvin.marvin.LatchMechanismSafetyFault
import org.aresfirst.marvin.marvin.marvin
import com.areslib.math.geometry.Pose2d
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.AutonomousCatalogResolver
import com.areslib.routine.RoutineManager
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineRequestResult
import com.areslib.routine.RoutineStartPolicy
import com.areslib.routine.RoutineStep
import com.areslib.state.RoutineExecutionStatus
import com.areslib.util.RobotClock

/**
 * Executes one generated ARES routine during the FRC autonomous period.
 *
 * Selection is sampled and locked exactly once in [autonomousInit]. Every routine and autonomous
 * entry is compiled into the robot program; no loose runtime auto format is supported. Missing
 * selections fall back to the generated do-nothing entry, while invalid catalogs, field poses,
 * task compilation, and runtime failures fail closed.
 */
class FRCAutoOrchestrator @JvmOverloads constructor(
    private val robot: FrcSwerveRobot,
    private val sim: Dyn4jSimulation? = null,
    private val selectionProvider: () -> String = ::dashboardSelection,
    autonomousEntries: List<AutonomousCatalogEntry> = GeneratedAresProject.autonomousEntries,
    defaultAutonomousEntryId: String? = GeneratedAresProject.DEFAULT_AUTONOMOUS_ENTRY_ID,
    private val routineDocuments: Map<String, RoutineDocument> = GeneratedAresProject.routines
) {
    private val selector = AutonomousCatalogResolver(
        autonomousEntries,
        defaultAutonomousEntryId
    )
    private val capabilities = FrcGeneratedRoutineCapabilities(robot)
    private val routineManager = RoutineManager(
        bindings = GeneratedAresProject.runtimeBindings(capabilities),
        stateProvider = { robot.store.state },
        dispatch = robot.store::dispatch
    ).also { manager -> manager.replaceDocuments(routineDocuments.values) }

    private var activeExecutionId: Long? = null
    private var autoFaulted = false
    private var finished = true
    private var selectedAutoId = defaultAutonomousEntryId ?: "do-nothing"
    private var status = "Idle"

    /** Publishes generated choices and initializes the dashboard selection without robot IO. */
    fun publishCatalog() {
        runCatching {
            val table = edu.wpi.first.networktables.NetworkTableInstance.getDefault()
                .getTable(SMART_DASHBOARD_TABLE)
            table.getEntry(SELECTED_AUTO_ENTRY).setDefaultString(selectedAutoId)
            table.getEntry(AVAILABLE_AUTOS_ENTRY).setStringArray(selector.availableEntryIds.toTypedArray())
        }
        robot.telemetry.putString(
            "ARES/Auto/AvailableDocuments",
            selector.availableEntryIds.joinToString(",")
        )
        robot.telemetry.putString("ARES/Auto/Source", "generated:${GeneratedAresProject.CONTENT_SHA256}")
    }

    /** Locks, validates, alliance-transforms, seeds, and requests the selected generated routine. */
    fun autonomousInit() {
        cancelActive("Autonomous reinitialized")
        stopAndXLockDrive()
        autoFaulted = false
        finished = false

        if (robot.store.state.superstructure.marvin.let {
                it.mechanismSafetyInhibited || it.mechanismSafetyFaultLatched
            }) {
            abort(MECHANISM_SAFETY_BLOCK_REASON)
            return
        }

        try {
            val selection = selector.resolve(selectionProvider())
            val entry = selection.entry
            selectedAutoId = entry.entryId
            capabilities.configure(entry, robot.store.state.drive.alliance)
            validateFieldBounds(entry)

            if (selection.usedFallback) {
                robot.telemetry.putString(
                    "ARES/Auto/Warning/1",
                    "Requested '${selection.requestedId}' is unavailable; using '${entry.entryId}'"
                )
            }

            if (shouldSeedAutonomousPose(entry.entryId)) {
                seedPose(capabilities.transform(entry.startingPose))
            }
            when (val result = routineManager.request(entry.routineId, RoutineStartPolicy.RESTART_EXISTING)) {
                is RoutineRequestResult.Accepted -> activeExecutionId = result.executionId
                is RoutineRequestResult.AlreadyRunning -> activeExecutionId = result.executionId
                is RoutineRequestResult.Rejected -> error(
                    result.issues.joinToString(separator = "; ") { it.message }
                )
            }
            setStatus(if (selection.usedFallback) "Running safe fallback" else "Running")
        } catch (error: Throwable) {
            abort("Preflight failed: ${error.message ?: error::class.java.simpleName}")
        }
    }

    /** Advances the single shared routine manager and observes its Redux terminal lifecycle. */
    fun autonomousPeriodic() {
        if (finished || autoFaulted) return
        if (robot.store.state.superstructure.marvin.let {
                it.mechanismSafetyInhibited || it.mechanismSafetyFaultLatched
            }) {
            abort(MECHANISM_SAFETY_BLOCK_REASON)
            return
        }
        val executionId = activeExecutionId ?: run {
            abort("Autonomous routine was not armed")
            return
        }

        try {
            routineManager.update()
            val active = robot.store.state.routineState.executions[executionId]
            if (active != null) {
                robot.telemetry.putString(
                    "ARES/Auto/ActiveTask",
                    active.activeStepPath ?: active.routineId
                )
                return
            }

            val terminal = robot.store.state.routineState.lastTerminalExecution
            if (terminal?.executionId != executionId) {
                abort("Routine lifecycle ended without a matching terminal result")
                return
            }
            when (terminal.status) {
                RoutineExecutionStatus.COMPLETED -> complete()
                RoutineExecutionStatus.FAILED -> abort(terminal.message ?: "Routine task failed")
                RoutineExecutionStatus.CANCELLED -> abort(terminal.message ?: "Routine was cancelled")
                RoutineExecutionStatus.REQUESTED,
                RoutineExecutionStatus.RUNNING -> abort("Routine left the active set before completion")
            }
        } catch (error: Throwable) {
            abort("Runtime failed: ${error.message ?: error::class.java.simpleName}")
        }
    }

    /** Cancels every active/queued routine and drives all outputs to their fail-safe state. */
    fun stop() {
        cancelActive("Robot disabled or autonomous exited")
        finished = true
        failSafeStop()
        setStatus("Stopped")
    }

    private fun complete() {
        finished = true
        activeExecutionId = null
        capabilities.clearConfiguration()
        failSafeStop()
        setStatus("Complete")
    }

    private fun abort(message: String) {
        autoFaulted = true
        finished = true
        cancelActive(message)
        failSafeStop(message)
        setStatus("Blocked")
        robot.telemetry.putString("ARES/Auto/Error", message)
        runCatching { edu.wpi.first.wpilibj.DriverStation.reportError("ARES auto: $message", false) }
    }

    private fun cancelActive(reason: String) {
        routineManager.cancelAll(reason)
        activeExecutionId = null
        capabilities.clearConfiguration()
    }

    private fun seedPose(pose: Pose2d) {
        sim?.resetPose(pose.x, pose.y, pose.heading.radians)
        robot.swerveDrivetrainIO?.seedPose(pose)
        robot.store.dispatch(
            RobotAction.PoseUpdate(
                xMeters = pose.x,
                yMeters = pose.y,
                headingRadians = pose.heading.radians,
                timestampMs = RobotClock.currentTimeMillis(),
                isReset = true
            )
        )
    }

    private fun validateFieldBounds(entry: AutonomousCatalogEntry) {
        require(routineDocuments.containsKey(entry.routineId)) {
            "Entry '${entry.entryId}' references missing routine '${entry.routineId}'"
        }
        requireFrcRoutinePoseInsideField(capabilities.transform(entry.startingPose), "starting pose")
        val visited = mutableSetOf<String>()
        fun validateRoutine(routineId: String) {
            if (!visited.add(routineId)) return
            val routine = requireNotNull(routineDocuments[routineId]) {
                "Routine '$routineId' does not exist"
            }
            fun validateStep(step: RoutineStep, path: String) {
                step.drive?.target?.let { target ->
                    requireFrcRoutinePoseInsideField(capabilities.transform(target), "$path drive goal")
                }
                step.routineId?.let(::validateRoutine)
                step.deadline?.let { validateStep(it, "$path.deadline") }
                step.children.forEachIndexed { index, child -> validateStep(child, "$path.children[$index]") }
                step.elseChildren.forEachIndexed { index, child ->
                    validateStep(child, "$path.elseChildren[$index]")
                }
            }
            routine.steps.forEachIndexed { index, step -> validateStep(step, "steps[$index]") }
        }
        validateRoutine(entry.routineId)
    }

    private fun failSafeStop(faultReason: String? = null) {
        stopAndXLockDrive()
        robot.store.dispatch(
            if (faultReason == null) {
                SetMechanismSafetyInhibit(true)
            } else {
                LatchMechanismSafetyFault("Autonomous fault: $faultReason")
            }
        )
        robot.safeHardware()
    }

    private fun stopAndXLockDrive() {
        robot.drive.joystickDrive(0.0, 0.0, 0.0, isFieldCentric = false)
        robot.swerveDrive.brake()
    }

    private fun setStatus(value: String) {
        status = value
        robot.telemetry.putString("ARES/Auto/Selected", selectedAutoId)
        robot.telemetry.putString("ARES/Auto/Status", value)
    }

    internal val isFaultedForTest: Boolean
        get() = autoFaulted
    internal val isFinishedForTest: Boolean
        get() = finished
    internal val selectedAutoForTest: String
        get() = selectedAutoId
    internal val statusForTest: String
        get() = status

    private companion object {
        const val SMART_DASHBOARD_TABLE = "SmartDashboard"
        const val SELECTED_AUTO_ENTRY = "SelectedAuto"
        const val AVAILABLE_AUTOS_ENTRY = "AvailableAutos"
        const val MECHANISM_SAFETY_BLOCK_REASON = "Mechanism safety is inhibited; autonomous start is blocked"

        fun dashboardSelection(): String = runCatching {
            edu.wpi.first.networktables.NetworkTableInstance.getDefault()
                .getTable(SMART_DASHBOARD_TABLE)
                .getEntry(SELECTED_AUTO_ENTRY)
                .getString(GeneratedAresProject.DEFAULT_AUTONOMOUS_ENTRY_ID ?: "do-nothing")
        }.getOrDefault(GeneratedAresProject.DEFAULT_AUTONOMOUS_ENTRY_ID ?: "do-nothing")
    }
}

/** The fail-safe routine intentionally leaves the operator's existing localization untouched. */
internal fun shouldSeedAutonomousPose(entryId: String): Boolean = entryId != "do-nothing"
