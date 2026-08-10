package com.areslib.pathing

import com.areslib.action.RobotAction
import com.areslib.hardware.actuator.IndicatorLightColor
import com.areslib.sequencer.Task
import com.areslib.sequencer.TaskStateMachine
import com.areslib.sequencer.TaskStatus
import com.areslib.sequencer.tasks.SetIndicatorColorTask
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
    val category: String = "General"
) {
    init {
        require(displayName.isNotBlank()) { "Command display name must not be blank" }
        require(description.isNotBlank()) { "Command description must not be blank" }
        require(category.isNotBlank()) { "Command category must not be blank" }
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
        taskFactory: (Long) -> Task
    ) {
        register(
            NamedCommandDescriptor(
                key = key,
                displayName = key.value.replace('_', ' ').replace('-', ' '),
                description = description,
                category = category
            ),
            taskFactory
        )
    }

    /** Returns a fresh task for [key], or `null` when the robot did not register it. */
    fun create(key: CommandKey, timestampMs: Long): Task? {
        val factory = synchronized(lock) { commands[key]?.taskFactory }
        return factory?.invoke(timestampMs)
    }

    /** Returns an immutable, predictable snapshot for telemetry and editor discovery. */
    fun catalog(): List<NamedCommandDescriptor> = synchronized(lock) {
        commands.values.map { it.descriptor }
    }

    /** Returns whether this robot has advertised [key]. */
    fun contains(key: CommandKey): Boolean = synchronized(lock) { key in commands }

    /** Creates a lazy task so registration is checked when the sequence starts, not when it is built. */
    fun task(key: CommandKey): Task = DeferredNamedCommandTask(key)

    /** Resolves a path marker by string and retains explicit legacy indicator marker support. */
    fun getCommand(name: String, timestampMs: Long): Task? {
        val key = runCatching { CommandKey(name) }.getOrNull()
        if (key != null) {
            create(key, timestampMs)?.let { return it }
        }
        return createLegacyIndicatorTask(name)
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

    private fun createLegacyIndicatorTask(name: String): Task? {
        val parts = name.split('_')
        val targetAndColor = when {
            parts.size == 3 && parts[0] == "SetIndicatorColor" -> parts[1] to parts[2]
            parts.size == 2 -> {
                val target = when (parts[0]) {
                    "SetIndicatorColor" -> "indicator"
                    "SetSecondIndicatorColor" -> "indicator2"
                    "SetThirdIndicatorColor" -> "indicator3"
                    "SetFourthIndicatorColor" -> "indicator4"
                    else -> return null
                }
                target to parts[1]
            }
            else -> return null
        }
        val color = IndicatorLightColor.entries.firstOrNull {
            it.name.equals(targetAndColor.second, ignoreCase = true)
        } ?: return null
        return SetIndicatorColorTask(targetAndColor.first, color)
    }
}

/** Delegates lifecycle to a newly-created named task and propagates its failure status. */
private class DeferredNamedCommandTask(private val key: CommandKey) : Task {
    override val name: String = "NamedCommand(${key.value})"
    private var delegate: Task? = null

    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        val timestampMs = com.areslib.util.RobotClock.currentTimeMillis()
        val resolved = requireNotNull(NamedCommands.create(key, timestampMs)) {
            "Named command '${key.value}' is not registered on this robot"
        }
        delegate = resolved
        return resolved.initialize(state)
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        val task = requireNotNull(delegate) { "Named command '${key.value}' was not initialized" }
        if (TaskStateMachine.getStatus(task) == TaskStatus.FAILED) {
            TaskStateMachine.markFailed(this)
            return false
        }
        return task.isCompleted(state, elapsedMs)
    }

    override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        super.execute(state, elapsedMs)
        if (TaskStateMachine.getStatus(this) == TaskStatus.FAILED) return emptyList()
        val task = requireNotNull(delegate) { "Named command '${key.value}' was not initialized" }
        val actions = task.execute(state, elapsedMs)
        if (TaskStateMachine.getStatus(task) == TaskStatus.FAILED) {
            TaskStateMachine.markFailed(this)
        }
        return actions
    }

    override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        val task = delegate
        val delegateFailed = task != null && TaskStateMachine.getStatus(task) == TaskStatus.FAILED
        val actions = task?.end(state, interrupted || delegateFailed).orEmpty()
        super.end(state, interrupted || delegateFailed)
        return actions
    }

    override fun releaseRuntimeState() {
        delegate?.releaseRuntimeState()
        delegate = null
        super.releaseRuntimeState()
    }
}
