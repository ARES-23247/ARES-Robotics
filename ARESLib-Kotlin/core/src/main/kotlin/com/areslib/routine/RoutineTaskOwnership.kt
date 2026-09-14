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
internal class RoutineTaskOwnership : TaskRuntimeStateOwner {
    private val released = IdentityHashMap<Task, Boolean>()
    private val nodes = ArrayList<Task>()
    private val leaves = ArrayList<Task>()
    private var cleanupFailure: Throwable? = null

    fun <T : Task> register(task: T): T {
        check(!released.containsKey(task)) { "Routine task factories must return fresh task instances" }
        check(TaskStateMachine.getStatus(task) == TaskStatus.PENDING) {
            "Routine task factories must return unstarted task instances"
        }
        TaskRuntimeOwnership.acquire(task, this)
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

    override fun release(task: Task) {
        if (released[task] != false) return
        released[task] = true
        try {
            TaskRuntimeOwnership.releaseDirect(task)
        } catch (failure: Throwable) {
            cleanupFailure = combineFailures(cleanupFailure, failure)
            throw failure
        } finally {
            // releaseDirect clears callback/timeout state even when the custom hook throws.
            TaskRuntimeOwnership.releaseClaim(task, this)
        }
    }

    /** Undo an unsuccessful admission before lifecycle begins, preserving the caller's metadata. */
    fun abandonClaims() {
        for (task in nodes) TaskRuntimeOwnership.releaseClaim(task, this)
        nodes.clear(); leaves.clear(); released.clear()
        cleanupFailure = null
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
    fun suspend(state: RobotState, paused: Boolean, owner: Task): List<RobotAction> {
        for (index in nodes.indices) {
            val task = nodes[index]
            if (released[task] == false) {
                if (paused) TaskTimeoutManager.pause(task) else TaskTimeoutManager.resume(task)
            }
        }
        val actions = ArrayList<RobotAction>()
        // A late terminal child/group must be noticed before any sibling can reactivate.
        if (!paused && propagateOwnedTerminal(owner)) {
            throw TaskTransitionAbort(actions, null, TaskStateMachine.getStatus(owner))
        }
        var failure: Throwable? = null
        for (index in leaves.indices) {
            val task = leaves[index]
            val status = TaskStateMachine.getStatus(task)
            if (released[task] == false && status == TaskStatus.RUNNING) {
                try {
                    task.setTimeoutSuspended(paused)
                    actions.addAll(if (paused) task.pause(state) else task.resume(state))
                } catch (caught: Throwable) {
                    if (caught is TaskTransitionAbort) {
                        actions.addAll(caught.actions)
                        if (caught.terminalStatus == TaskStatus.CANCELLED && TaskStateMachine.getStatus(task) != TaskStatus.FAILED)
                            TaskStateMachine.transitionTo(task, TaskStatus.CANCELLED)
                        else TaskStateMachine.markFailed(task)
                    } else TaskStateMachine.markFailed(task)
                    failure = combineFailures(failure, caught)
                }
                // Pausing still neutralizes other leaves; failed/cancelled resume stops activation.
                if (!paused && TaskStateMachine.getStatus(task) != TaskStatus.RUNNING) break
            }
        }
        if (propagateOwnedTerminal(owner)) {
            // Private wrappers may simply forward pause/resume, so status alone cannot reach the executor.
            throw TaskTransitionAbort(actions, failure, TaskStateMachine.getStatus(owner))
        }
        return actions
    }

    /** Admission is already exclusive; observe queued descendant cancellation before starting work. */
    fun propagateQueuedTerminal(owner: Task): Boolean = propagateOwnedTerminal(owner, queued = true)

    private fun propagateOwnedTerminal(owner: Task, queued: Boolean = false): Boolean {
        var cancelled = TaskStateMachine.getStatus(owner) == TaskStatus.CANCELLED
        var failed = TaskStateMachine.getStatus(owner) == TaskStatus.FAILED
        for (task in nodes) {
            if (released[task] != false) continue
            if (queued && task is CompiledRoutineTask) task.propagateQueuedTerminal()
            when (TaskStateMachine.getStatus(task)) {
                TaskStatus.FAILED -> failed = true
                TaskStatus.CANCELLED -> cancelled = true
                TaskStatus.COMPLETED -> if (queued) failed = true
                else -> Unit
            }
        }
        if (failed) TaskStateMachine.markFailed(owner)
        else if (cancelled) TaskStateMachine.transitionTo(owner, TaskStatus.CANCELLED)
        return failed || cancelled
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
                    retainTaskInterruption(caught)
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
    internal fun propagateQueuedTerminal(): Boolean = ownership.propagateQueuedTerminal(this)
    override fun pause(state: RobotState): List<RobotAction> = ownership.suspend(state, paused = true, owner = this)
    override fun resume(state: RobotState): List<RobotAction> = ownership.suspend(state, paused = false, owner = this)
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
