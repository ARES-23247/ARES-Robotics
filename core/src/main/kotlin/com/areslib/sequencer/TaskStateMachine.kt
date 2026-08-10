package com.areslib.sequencer

/** Observable lifecycle states retained for each task. */
enum class TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }

/**
 * Thread-safe process-wide task-status registry.
 * Transitions are not validated; lifecycle owners are responsible for legal ordering. The
 * registry uses weak task keys so observing a terminal status does not retain discarded tasks.
 */
object TaskStateMachine {
    private val statuses = WeakIdentityMap<Task, TaskStatus>()

    /** Returns the retained status or [TaskStatus.PENDING] for an unregistered task. */
    fun getStatus(task: Task): TaskStatus = statuses[task] ?: TaskStatus.PENDING

    /** Stores [newStatus] without enforcing a transition graph. */
    fun transitionTo(task: Task, newStatus: TaskStatus) {
        statuses[task] = newStatus
    }

    /** Atomically marks [task] failed once and reports whether this call performed the transition. */
    @Synchronized
    fun markFailed(task: Task): Boolean {
        if (statuses[task] == TaskStatus.FAILED) {
            return false
        } else {
            statuses[task] = TaskStatus.FAILED
            return true
        }
    }
    
    /** Removes [task] from the registry. */
    fun reset(task: Task) {
        statuses.remove(task)
    }
}
