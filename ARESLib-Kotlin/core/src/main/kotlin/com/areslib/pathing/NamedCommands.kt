package com.areslib.pathing

import com.areslib.action.RobotAction
import com.areslib.sequencer.Task
import com.areslib.sequencer.TaskResources
import com.areslib.sequencer.TaskStateMachine
import com.areslib.sequencer.TaskStatus
import com.areslib.sequencer.TaskCallbacks
import com.areslib.sequencer.TaskTimeoutContainer
import com.areslib.sequencer.completionReady
import com.areslib.sequencer.setTimeoutSuspended
import com.areslib.state.RobotState

/** Stable identifier shared by code, path event markers, and the auto editor. */
@JvmInline
value class CommandKey(val value: String) {
    init {
        require(value.matches(VALID_COMMAND_KEY)) {
            "Command key '$value' must start with a letter and contain only letters, digits, '.', '_' or '-'"
        }
    }

    override fun toString(): String = value

    private companion object {
        val VALID_COMMAND_KEY = Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")
    }
}

/** Human-readable metadata that Analytics can present without guessing robot capabilities. */
data class NamedCommandDescriptor(
    val key: CommandKey,
    val displayName: String,
    val description: String,
    val category: String = "General",
    val requiredResources: Long = TaskResources.NONE
) {
    init {
        require(displayName.isNotBlank()) { "Command display name must not be blank" }
        require(description.isNotBlank()) { "Command description must not be blank" }
        require(category.isNotBlank()) { "Command category must not be blank" }
        require(requiredResources >= 0L) { "Command resources must use bits 0..62" }
    }
}

/**
 * Registry of fresh autonomous task factories.
 *
 * Registrations carry editor-facing metadata and factories rather than reusable task instances.
 * A task has mutable lifecycle state, so returning the same instance for multiple marker triggers
 * is invalid. Registry snapshots preserve insertion order for predictable novice-facing menus.
 */
object NamedCommands {
    private data class Registration(
        val descriptor: NamedCommandDescriptor,
        val taskFactory: (Long) -> Task
    )

    private val lock = Any()
    private val commands = linkedMapOf<CommandKey, Registration>()
    @Volatile
    private var revision: Long = 0L

    /** Monotonic process-local version used by telemetry publishers to refresh cached catalogs. */
    val catalogRevision: Long
        get() = revision

    /** Registers or replaces a command and its editor metadata. */
    fun register(descriptor: NamedCommandDescriptor, taskFactory: (Long) -> Task) {
        synchronized(lock) {
            commands[descriptor.key] = Registration(descriptor, taskFactory)
            revision++
        }
    }

    /** Convenience overload for code that does not need custom editor labels. */
    fun register(
        key: CommandKey,
        description: String,
        category: String = "General",
        requiredResources: Long = TaskResources.NONE,
        taskFactory: (Long) -> Task
    ) {
        register(
            NamedCommandDescriptor(
                key = key,
                displayName = key.value.replace('_', ' ').replace('-', ' '),
                description = description,
                category = category,
                requiredResources = requiredResources
            ),
            taskFactory
        )
    }

    /** Returns a fresh task for [key], or `null` when the robot did not register it. */
    fun create(key: CommandKey, timestampMs: Long): Task? {
        val registration = synchronized(lock) { commands[key] } ?: return null
        val task = registration.taskFactory(timestampMs)
        require(task.requiredResources == registration.descriptor.requiredResources) {
            "Named command '${key.value}' declares " +
                "${TaskResources.describe(registration.descriptor.requiredResources)} but its task requires " +
                TaskResources.describe(task.requiredResources)
        }
        return task
    }

    /** Returns an immutable, predictable snapshot for telemetry and editor discovery. */
    fun catalog(): List<NamedCommandDescriptor> = synchronized(lock) {
        commands.values.map { it.descriptor }
    }

    /** Returns whether this robot has advertised [key]. */
    fun contains(key: CommandKey): Boolean = synchronized(lock) { key in commands }

    /** Creates a lazy task so registration is checked when the sequence starts, not when it is built. */
    fun task(
        key: CommandKey,
        requiredResources: Long = registeredResources(key) ?: TaskResources.NONE
    ): Task = DeferredNamedCommandTask(key, requiredResources)

    /** Returns the registered resource mask without constructing a mutable task instance. */
    fun registeredResources(key: CommandKey): Long? = synchronized(lock) {
        commands[key]?.descriptor?.requiredResources
    }

    /** Resolves an exact registered path-marker key. Unregistered markers are rejected. */
    fun getCommand(name: String, timestampMs: Long): Task? {
        val key = runCatching { CommandKey(name) }.getOrNull()
        return key?.let { create(it, timestampMs) }
    }

    /** Removes all registrations. Primarily used between robot/test lifecycles. */
    fun clear() {
        synchronized(lock) {
            if (commands.isNotEmpty()) {
                commands.clear()
                revision++
            }
        }
    }

}

/** Delegates lifecycle to a newly-created named task and propagates its failure status. */
private class DeferredNamedCommandTask(
    private val key: CommandKey,
    override val requiredResources: Long
) : Task, TaskTimeoutContainer {
    override val name: String = "NamedCommand(${key.value})"
    private var delegate: Task? = null

    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        val timestampMs = com.areslib.util.RobotClock.currentTimeMillis()
        val resolved = requireNotNull(NamedCommands.create(key, timestampMs)) {
            "Named command '${key.value}' is not registered on this robot"
        }
        require(resolved.requiredResources == requiredResources) {
            "Named command '${key.value}' was built with resources " +
                "${TaskResources.describe(requiredResources)} but is registered with " +
                TaskResources.describe(resolved.requiredResources)
        }
        delegate = resolved
        val actions = resolved.initialize(state)
        propagateTerminal(resolved)
        return actions
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        val task = requireNotNull(delegate) { "Named command '${key.value}' was not initialized" }
        if (TaskStateMachine.getStatus(this) != TaskStatus.RUNNING) return false
        val completed = task.completionReady(state, elapsedMs)
        return !propagateTerminal(task) && completed
    }

    override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        super.execute(state, elapsedMs)
        if (TaskStateMachine.getStatus(this) != TaskStatus.RUNNING) return emptyList()
        val task = requireNotNull(delegate) { "Named command '${key.value}' was not initialized" }
        if (propagateTerminal(task)) return emptyList()
        val actions = task.execute(state, elapsedMs)
        propagateTerminal(task)
        return actions
    }

    override fun pause(state: RobotState): List<RobotAction> = delegate?.pause(state).orEmpty()

    override fun resume(state: RobotState): List<RobotAction> = delegate?.resume(state).orEmpty()

    override fun suspendChildTimeouts(paused: Boolean) { delegate?.setTimeoutSuspended(paused) }

    override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        val task = delegate
        var actions: List<RobotAction> = emptyList()
        var failure: Throwable? = null
        if (task != null) {
            val terminal = propagateTerminal(task)
            if (TaskStateMachine.getStatus(task) == TaskStatus.FAILED) {
                try { TaskCallbacks.invokeFail(task) } catch (caught: Throwable) {
                    // Diagnostic callbacks must not prevent physical cleanup or discard its actions.
                    System.err.println("Named command failure callback failed: ${caught.message}")
                }
            }
            try {
                actions = task.end(state, interrupted || terminal)
            } catch (caught: Throwable) {
                TaskStateMachine.markFailed(task)
                failure = caught
            }
            propagateTerminal(task)
        }
        try {
            super.end(state, interrupted)
        } catch (caught: Throwable) {
            if (failure == null) failure = caught
            else if (failure !== caught && failure.suppressed.none { it === caught }) failure.addSuppressed(caught)
        }
        failure?.let { throw it }
        return actions
    }

    override fun releaseRuntimeState() {
        val task = delegate
        delegate = null
        try { task?.releaseRuntimeState() } finally { super.releaseRuntimeState() }
    }

    private fun propagateTerminal(task: Task): Boolean = when (TaskStateMachine.getStatus(task)) {
        TaskStatus.FAILED -> { TaskStateMachine.markFailed(this); true }
        TaskStatus.CANCELLED -> {
            if (TaskStateMachine.getStatus(this) != TaskStatus.FAILED) {
                TaskStateMachine.transitionTo(this, TaskStatus.CANCELLED)
            }
            true
        }
        else -> false
    }
}
