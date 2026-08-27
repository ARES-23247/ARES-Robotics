package com.areslib.runtime

import com.areslib.action.RobotAction
import com.areslib.input.ControllerBindingRuntime
import com.areslib.input.InputFrame
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineManager
import com.areslib.routine.RoutineRequestResult
import com.areslib.routine.RoutineRuntimeBindings
import com.areslib.routine.RoutineStartPolicy
import com.areslib.sequencer.Task
import com.areslib.sequencer.TaskExecutor
import com.areslib.state.RobotState
import com.areslib.util.RobotClock

/** Stable scheduler boundary used by generated direct-action controller bindings. */
fun interface GeneratedControlTaskSink {
    fun submit(bindingId: String, task: Task)
}

/**
 * Platform-neutral entry points emitted by the project compiler.
 *
 * Generated code supplies project-specific capability dispatch. League runtimes supply lifecycle,
 * input sampling, drive semantics, and physical IO. Function references are captured once during
 * initialization and are never created in a periodic update path.
 */
class GeneratedProjectDefinition<C>(
    val defaultControlSchemeId: String?,
    val contentSha256: String,
    val hasGeneratedDriveBindings: Boolean,
    val routines: Map<String, RoutineDocument>,
    val runtimeBindings: (C) -> RoutineRuntimeBindings,
    val createControllerRuntimes: (
        schemeId: String?,
        registry: C,
        routineManager: RoutineManager,
        taskSink: GeneratedControlTaskSink,
    ) -> Map<Int, ControllerBindingRuntime>,
    val emitDriveCommand: (C) -> Unit,
)

/**
 * Shared generated-project scheduler with no FTC, FRC, hardware, or simulator assumptions.
 *
 * A league host owns when ports are sampled, when drive output is emitted, and when cancellation is
 * required. This class owns only generated bindings, direct tasks, and checked-in routines.
 */
class GeneratedProjectControlRuntime<C>(
    private val definition: GeneratedProjectDefinition<C>,
    private val stateProvider: () -> RobotState,
    private val dispatch: (RobotAction) -> Unit,
    private val capabilities: C,
    maximumControllerPorts: Int,
) : GeneratedControlTaskSink {
    private val directTaskExecutor = TaskExecutor()
    val routineManager = RoutineManager(
        bindings = definition.runtimeBindings(capabilities),
        stateProvider = stateProvider,
        dispatch = dispatch,
    ).also { manager -> manager.replaceDocuments(definition.routines.values) }
    private val controllerRuntimes: Array<ControllerBindingRuntime?>

    init {
        require(maximumControllerPorts > 0) { "maximumControllerPorts must be positive" }
        controllerRuntimes = arrayOfNulls(maximumControllerPorts)
        val generated = definition.createControllerRuntimes(
            definition.defaultControlSchemeId,
            capabilities,
            routineManager,
            this,
        )
        for ((port, runtime) in generated) {
            require(port in controllerRuntimes.indices) {
                "Generated controller port $port is outside 0..${controllerRuntimes.lastIndex}"
            }
            check(controllerRuntimes[port] == null) { "Generated controller port $port is duplicated" }
            controllerRuntimes[port] = runtime
        }
    }

    val hasGeneratedDriveBindings: Boolean
        get() = definition.hasGeneratedDriveBindings

    val controlsSource: String
        get() = definition.defaultControlSchemeId?.let { scheme ->
            "generated:$scheme:${definition.contentSha256}"
        } ?: "hand-authored-only"

    val controllerPortCapacity: Int
        get() = controllerRuntimes.size

    val activeControllerPortCount: Int
        get() {
            var count = 0
            var port = 0
            while (port < controllerRuntimes.size) {
                if (controllerRuntimes[port] != null) count++
                port++
            }
            return count
        }

    fun hasControllerPort(port: Int): Boolean =
        port in controllerRuntimes.indices && controllerRuntimes[port] != null

    /** Advances one caller-sampled port without allocating or reading platform hardware. */
    fun updatePort(port: Int, frame: InputFrame, nowNanos: Long) {
        require(port in controllerRuntimes.indices) {
            "Controller port $port is outside 0..${controllerRuntimes.lastIndex}"
        }
        controllerRuntimes[port]?.update(frame, nowNanos)
    }

    /** Lets the league host decide whether generated drive output owns this frame. */
    fun emitDriveCommand() = definition.emitDriveCommand(capabilities)

    override fun submit(bindingId: String, task: Task) {
        require(bindingId.isNotBlank()) { "Generated binding ID must not be blank" }
        directTaskExecutor.addTask(task)
    }

    /** Advances direct controller tasks and checked-in routines once per robot frame. */
    fun updateTasks() {
        if (directTaskExecutor.size > 0) {
            val actions = directTaskExecutor.update(stateProvider(), RobotClock.currentTimeMillis())
            for (index in actions.indices) dispatch(actions[index])
        }
        updateRoutines()
    }

    fun requestRoutine(
        routineId: String,
        policy: RoutineStartPolicy = RoutineStartPolicy.RESTART_EXISTING,
    ): RoutineRequestResult = routineManager.request(routineId, policy)

    fun updateRoutines() {
        if (routineManager.activeCount > 0 || routineManager.queuedCount > 0) routineManager.update()
    }

    /** Releases every generated binding and task; league lifecycle code decides when this occurs. */
    fun cancelAll(reason: String) {
        var port = 0
        while (port < controllerRuntimes.size) {
            controllerRuntimes[port]?.cancel()
            port++
        }
        val actions = directTaskExecutor.cancelAll(stateProvider())
        for (index in actions.indices) dispatch(actions[index])
        routineManager.cancelAll(reason)
    }
}
