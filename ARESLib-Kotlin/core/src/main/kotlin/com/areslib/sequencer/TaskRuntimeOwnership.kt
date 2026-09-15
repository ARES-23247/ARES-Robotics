package com.areslib.sequencer

import com.areslib.action.RobotAction
import java.lang.ref.WeakReference

/** An invocation owns metadata release even when a built-in group ends one of its children. */
internal interface TaskRuntimeStateOwner {
    fun release(task: Task)
}

/** Weak on both sides: abandoned ownership must not retain the task tree through this registry. */
internal object TaskRuntimeOwnership {
    private val owners = WeakIdentityMap<Task, WeakReference<TaskRuntimeStateOwner>>()

    @Synchronized fun acquire(task: Task, owner: TaskRuntimeStateOwner) {
        check(owners[task]?.get() == null) { "Routine task already belongs to another compiled invocation" }
        owners[task] = WeakReference(owner)
    }

    @Synchronized fun releaseClaim(task: Task, owner: TaskRuntimeStateOwner) {
        if (owners[task]?.get() === owner) owners.remove(task)
    }

    @Synchronized private fun owner(task: Task): TaskRuntimeStateOwner? = owners[task]?.get()

    fun release(task: Task) {
        val owner = owner(task)
        // Do not invoke user hooks while holding the global registry monitor.
        if (owner != null) owner.release(task) else releaseDirect(task)
    }

    fun releaseDirect(task: Task) {
        try { task.releaseRuntimeState() }
        finally { TaskTimeoutManager.reset(task); TaskCallbacks.reset(task) }
    }
}

/** Carries collected actions and terminal outcome through private task wrappers during transition. */
internal class TaskTransitionAbort(
    actions: List<RobotAction>,
    cause: Throwable?,
    val terminalStatus: TaskStatus,
) : RuntimeException("Task became $terminalStatus during transition", cause) {
    val actions: List<RobotAction> = actions.toList()
}

internal fun retainTaskInterruption(failure: Throwable) {
    if (failure is InterruptedException) Thread.currentThread().interrupt()
}
