package com.areslib.sequencer

import java.util.concurrent.ConcurrentHashMap

/** Observable lifecycle states retained for each task. */
enum class TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }

/**
 * Concurrent process-wide task-status registry.
 * Transitions are not validated; lifecycle owners are responsible for legal ordering. [reset]
 * releases the strong task reference and restores the observable default of [TaskStatus.PENDING].
 */
object TaskStateMachine {
    private val statuses = ConcurrentHashMap<Task, TaskStatus>()

    /** Returns the retained status or [TaskStatus.PENDING] for an unregistered task. */
    fun getStatus(task: Task): TaskStatus = statuses.getOrDefault(task, TaskStatus.PENDING)

    /** Stores [newStatus] without enforcing a transition graph. */
    fun transitionTo(task: Task, newStatus: TaskStatus) {
        statuses[task] = newStatus
    }
    
    /** Removes [task] from the registry. */
    fun reset(task: Task) {
        statuses.remove(task)
    }
}
