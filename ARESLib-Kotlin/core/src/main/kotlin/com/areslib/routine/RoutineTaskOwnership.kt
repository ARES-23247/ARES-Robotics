package com.areslib.routine

import com.areslib.action.RobotAction
import com.areslib.sequencer.*
import com.areslib.state.RobotState
import java.util.IdentityHashMap

/**
 * Claims a fresh, unstarted task tree for composition inside an action/drive factory.
 *
 * The returned task forwards execution and pause/resume while owning metadata for every built-in
 * group descendant, including tasks that never start. Custom tasks still own their private children.
 * Keep the returned owner in the factory's cleanup scope until the enclosing tree is returned to
 * [RoutineCompiler]; release it if later construction fails. Acquisition rejects tasks already
 * claimed by another owner without releasing that owner's metadata, and cleans fresh descendants
 * of a rejected tree. This does not initialize tasks or perform hardware work.
 *
 * Metadata release propagates cleanup errors after attempting all acquired nodes and clearing their
 * callback/timeout registries. Like [Task.cancel], it cannot dispatch hardware-neutral actions;
 * active tasks must end through their executor. All use is confined to the owning robot thread.
 */
fun ownRoutineTaskTree(task: Task): Task {
    val ownership = RoutineTaskOwnership()
    try {
        ownership.acquire(task)
        return CompiledRoutineTask(task, ownership, propagateMetadataFailure = true)
    } catch (failure: Throwable) {
        combineFailures(null, failure)
        try { ownership.releaseAll() } catch (cleanup: Throwable) { combineFailures(failure, cleanup) }
        throw failure
    }
}

/** One compilation owns all created nodes, including dormant branches and future sequence steps. */
internal class RoutineTaskOwnership {
    private val released = IdentityHashMap<Task, Boolean>()
    private val nodes = ArrayList<Task>()
    private val leaves = ArrayList<Task>()
    private var cleanupFailure: Throwable? = null

    fun <T : Task> register(task: T): T {
        check(!released.containsKey(task)) { "Routine task factories must return fresh task instances" }
        check(TaskStateMachine.getStatus(task) == TaskStatus.PENDING) {
            "Routine task factories must return unstarted task instances"
        }
        RoutineTaskClaims.acquire(task)
        released[task] = false
        nodes.add(task)
        return task
    }

    fun <T : Task> internalNode(task: T): T = if (released.containsKey(task)) task else register(task)

    /** Built-in trees expose owned membership; custom tasks own their private child lifecycles. */
    fun <T : Task> acquire(task: T): T {
        register(task)
        val children = when (task) {
            is SequentialTaskGroup -> task.tasks
            is ParallelTaskGroup -> task.tasks
            is ParallelRaceGroup -> task.tasks
            is ParallelDeadlineGroup -> task.tasks
            else -> null
        }
        if (children == null) {
            leaves.add(task)
        } else {
            var failure: Throwable? = null
            for (child in children) {
                try { acquire(child) } catch (caught: Throwable) { failure = combineFailures(failure, caught) }
            }
            // A foreign/running child is untouched, but must not strand later fresh siblings.
            failure?.let { throw it }
        }
        return task
    }

    fun release(task: Task) {
        if (released[task] != false) return
        released[task] = true
        try {
            task.releaseRuntimeState()
        } catch (failure: Throwable) {
            cleanupFailure = combineFailures(cleanupFailure, failure)
            throw failure
        } finally {
            // An overriding metadata hook must not strand strong callback references/deadlines.
            TaskTimeoutManager.reset(task)
            TaskCallbacks.reset(task)
            RoutineTaskClaims.release(task)
        }
    }

    fun releaseAll() {
        var failure: Throwable?
        try {
            for (index in nodes.indices.reversed()) {
                try { release(nodes[index]) } catch (_: Throwable) { /* Retained by release(). */ }
            }
            failure = cleanupFailure
        } finally {
            nodes.clear(); leaves.clear(); released.clear()
            cleanupFailure = null
        }
        failure?.let { throw it }
    }

    /** Pause/resume is infrequent; steady update never scans the compiled tree. */
    fun suspend(state: RobotState, paused: Boolean): List<RobotAction> {
        for (index in nodes.indices) {
            val task = nodes[index]
            if (released[task] == false) {
                if (paused) TaskTimeoutManager.pause(task) else TaskTimeoutManager.resume(task)
            }
        }
        val actions = ArrayList<RobotAction>()
        for (index in leaves.indices) {
            val task = leaves[index]
            val status = TaskStateMachine.getStatus(task)
            if (released[task] == false && (status == TaskStatus.RUNNING || status == TaskStatus.FAILED)) {
                task.setTimeoutSuspended(paused)
                actions.addAll(if (paused) task.pause(state) else task.resume(state))
            }
        }
        return actions
    }
}

/** Preserves terminal status and deadline gates across private routine decorators. */
internal abstract class RoutineTaskWrapper(protected val ownership: RoutineTaskOwnership) : Task, TaskTimeoutContainer {
    protected abstract val delegate: Task?

    override fun suspendChildTimeouts(paused: Boolean) { delegate?.setTimeoutSuspended(paused) }

    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        val child = checkNotNull(delegate) { "Routine delegate was not selected" }
        val actions = child.initialize(state)
        propagateTerminal(child)
        return actions
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        val child = checkNotNull(delegate) { "Routine delegate was not initialized" }
        val complete = child.completionReady(state, elapsedMs)
        return !propagateTerminal(child) && complete
    }

    override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        super.execute(state, elapsedMs)
        val child = checkNotNull(delegate) { "Routine delegate was not initialized" }
        if (TaskStateMachine.getStatus(this) != TaskStatus.RUNNING || propagateTerminal(child)) return emptyList()
        val actions = child.execute(state, elapsedMs)
        propagateTerminal(child)
        return actions
    }

    override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        val child = delegate
        var actions: List<RobotAction> = emptyList()
        var failure: Throwable? = null
        if (child != null) {
            val terminal = propagateTerminal(child)
            if (TaskStateMachine.getStatus(child) == TaskStatus.FAILED) {
                try { TaskCallbacks.invokeFail(child) } catch (caught: Throwable) {
                    // Preserve physical cleanup even when a diagnostic callback fails.
                    System.err.println("Routine task failure callback failed: ${caught.message}")
                }
            }
            try { actions = child.end(state, interrupted || terminal) } catch (caught: Throwable) {
                failure = combineFailures(failure, caught)
                TaskStateMachine.markFailed(child)
            }
            propagateTerminal(child)
        }
        try { releaseOwnedMetadata() } catch (caught: Throwable) {
            // Metadata failure must not discard cleanup actions already returned by end().
            TaskStateMachine.markFailed(this)
            System.err.println("Routine task metadata cleanup failed: ${caught.message}")
        }
        if (failure != null) TaskStateMachine.markFailed(this)
        try { super.end(state, interrupted) } catch (caught: Throwable) {
            failure = combineFailures(failure, caught)
        }
        failure?.let { throw it }
        return actions
    }

    override fun releaseRuntimeState() {
        try { releaseOwnedMetadata() } catch (caught: Throwable) {
            TaskStateMachine.markFailed(this)
            System.err.println("Routine task metadata cleanup failed: ${caught.message}")
        } finally { super.releaseRuntimeState() }
    }

    protected open fun releaseOwnedMetadata() { delegate?.let(ownership::release) }

    private fun propagateTerminal(child: Task): Boolean = when (TaskStateMachine.getStatus(child)) {
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

/** Public compilation result delegates lifecycle to its tree and owns every node's final cleanup. */
internal class CompiledRoutineTask(
    override val delegate: Task,
    ownership: RoutineTaskOwnership,
    private val propagateMetadataFailure: Boolean = false,
) : RoutineTaskWrapper(ownership) {
    override val name: String = delegate.name
    override val priority: Int = delegate.priority
    override val requiredResources: Long = delegate.requiredResources
    override fun pause(state: RobotState): List<RobotAction> = ownership.suspend(state, paused = true)
    override fun resume(state: RobotState): List<RobotAction> = ownership.suspend(state, paused = false)
    override fun releaseRuntimeState() {
        if (!propagateMetadataFailure) {
            super.releaseRuntimeState()
            return
        }
        try { releaseOwnedMetadata() }
        finally { TaskTimeoutManager.reset(this); TaskCallbacks.reset(this) }
    }
    override fun releaseOwnedMetadata() {
        var failure: Throwable? = null
        try { super.releaseOwnedMetadata() } catch (caught: Throwable) { failure = caught }
        try { ownership.releaseAll() } catch (caught: Throwable) { failure = combineFailures(failure, caught) }
        failure?.let { throw it }
    }
}

private fun combineFailures(first: Throwable?, next: Throwable): Throwable {
    if (next is InterruptedException) Thread.currentThread().interrupt()
    if (first == null) return next
    if (first !== next && first.suppressed.none { it === next }) first.addSuppressed(next)
    return first
}

/** A weak identity claim prevents two unstarted compilations from sharing a factory task. */
private object RoutineTaskClaims {
    private val claimed = WeakIdentityMap<Task, Boolean>()
    @Synchronized fun acquire(task: Task) {
        check(claimed[task] != true) { "Routine task already belongs to another compiled invocation" }
        claimed[task] = true
    }
    @Synchronized fun release(task: Task) { claimed.remove(task) }
}
