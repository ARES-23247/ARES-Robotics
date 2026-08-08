package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import java.util.ArrayDeque

/**
 * Thread-safe state-machine execution sequencer coordinating queued, conditional tasks.
 * Supports task preemption to temporarily pause and resume running tasks dynamically.
 */
class TaskExecutor {
    private val queue = ArrayDeque<Task>()
    private var activeTask: Task? = null
    private var activeTaskStartTimeMs: Long = 0L
    private var isSuspended = false
    
    // Stack of (Task, elapsedMsBeforePreemption) to enable nested preemption & resumption
    private val preemptedStack = ArrayDeque<Pair<Task, Long>>()

    /**
     * Appends a task to the standard queue.
     */
    @Synchronized
    fun addTask(task: Task) {
        queue.offer(task)
    }

    /**
     * Suspends execution of all tasks.
     */
    @Synchronized
    fun suspend() {
        isSuspended = true
    }

    /**
     * Resumes execution of tasks.
     */
    @Synchronized
    fun resume() {
        isSuspended = false
    }

    /**
     * Preempts the active task queue immediately with a high-priority task.
     * The currently active task is paused and pushed to the preemption stack, to be resumed later.
     */
    @Synchronized
    fun preempt(task: Task, state: RobotState, currentTimestampMs: Long): List<RobotAction> {
        var actions: MutableList<RobotAction>? = null

        val currentActive = activeTask
        if (currentActive != null) {
            val elapsed = currentTimestampMs - activeTaskStartTimeMs
            preemptedStack.push(Pair(currentActive, elapsed))
            try {
                actions = addActions(actions, currentActive.end(state, interrupted = true))
            } catch (e: Exception) {
                System.err.println("TaskExecutor: Exception during task.end for preempted task ${currentActive.name}: ${e.message}")
                e.printStackTrace()
            }
        }
        
        activeTask = task
        activeTaskStartTimeMs = currentTimestampMs
        try {
            actions = addActions(actions, task.initialize(state))
        } catch (e: Exception) {
            System.err.println("TaskExecutor: Exception during task.initialize for preempting task ${task.name}: ${e.message}")
            e.printStackTrace()
            actions = addActions(actions, handleTaskFailure(task, state))
        }
        return actions ?: emptyList()
    }

    /**
     * Evaluates the active task queue based on the latest RobotState.
     * Returns a list of actions to dispatch to the Redux store.
     */
    @Synchronized
    fun update(state: RobotState, currentTimestampMs: Long): List<RobotAction> {
        if (isSuspended) return emptyList()
        var actions: MutableList<RobotAction>? = null

        var task = activeTask
        var loopCount = 0
        val maxLoopCount = 100 // Prevent infinite loop stack overflows
        
        while (loopCount < maxLoopCount) {
            loopCount++
            if (task == null) {
                when {
                    preemptedStack.isNotEmpty() -> {
                        // Resume a previously preempted task
                        val (resumedTask, priorElapsed) = preemptedStack.pop()
                        activeTask = resumedTask
                        activeTaskStartTimeMs = currentTimestampMs - priorElapsed
                        task = resumedTask
                    }
                    queue.isNotEmpty() -> {
                        // Dequeue the next task
                        val nextTask = queue.poll()
                        activeTask = nextTask
                        activeTaskStartTimeMs = currentTimestampMs
                        try {
                            actions = addActions(actions, nextTask.initialize(state))
                        } catch (e: Exception) {
                            System.err.println("TaskExecutor: Exception during task.initialize for task ${nextTask.name}: ${e.message}")
                            e.printStackTrace()
                            actions = addActions(actions, handleTaskFailure(nextTask, state))
                            break
                        }
                        task = nextTask
                    }
                    else -> break
                }
            }

            if (task != null) {
                val elapsed = currentTimestampMs - activeTaskStartTimeMs
                val isCompleted = try {
                    task.isCompleted(state, elapsed)
                } catch (e: Exception) {
                    System.err.println("TaskExecutor: Exception in task.isCompleted for task ${task.name}: ${e.message}")
                    e.printStackTrace()
                    actions = addActions(actions, handleTaskFailure(task, state))
                    break
                }
                
                val isFailed = TaskStateMachine.getStatus(task) == TaskStatus.FAILED
                
                if (isCompleted || isFailed) {
                    // Finalize active task
                    try {
                        actions = addActions(actions, task.end(state, interrupted = isFailed))
                    } catch (e: Exception) {
                        System.err.println("TaskExecutor: Exception in task.end for task ${task.name}: ${e.message}")
                        e.printStackTrace()
                    }
                    activeTask = null
                    task = null // Continue loop to dequeue/resume instantly
                } else {
                    val execActions = try {
                        task.execute(state, elapsed)
                    } catch (e: Exception) {
                        System.err.println("TaskExecutor: Exception in task.execute for task ${task.name}: ${e.message}")
                        e.printStackTrace()
                        actions = addActions(actions, handleTaskFailure(task, state))
                        break
                    }
                    actions = addActions(actions, execActions)
                    break // Stop frame update as active task is currently running
                }
            }
        }

        if (loopCount >= maxLoopCount) {
            System.err.println("TaskExecutor: Loop transition threshold reached ($maxLoopCount). Aborting update to prevent lockup.")
        }

        return actions ?: emptyList()
    }

    private fun addActions(existing: MutableList<RobotAction>?, newActions: List<RobotAction>): MutableList<RobotAction>? {
        if (newActions.isEmpty()) return existing
        val list = existing ?: mutableListOf()
        list.addAll(newActions)
        return list
    }

    private fun handleTaskFailure(task: Task, state: RobotState): List<RobotAction> {
        System.err.println("TaskExecutor: Task ${task.name} failed. Removing task, preserving remaining queue.")
        val cleanupActions = try {
            task.end(state, interrupted = true)
        } catch (e: Exception) {
            System.err.println("TaskExecutor: Exception during task.end cleanup: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
        activeTask = null
        return cleanupActions
    }

    /**
     * Clear all tasks in queue and stack.
     */
    @Synchronized
    fun clear() {
        queue.clear()
        preemptedStack.clear()
        activeTask = null
    }

    /**
     * Returns the name of the currently active task, if any.
     */
    val activeTaskName: String?
        @Synchronized get() = activeTask?.name

    /**
     * Gets total tasks currently loaded/executing (queue + active + preempted).
     */
    val size: Int
        @Synchronized get() = queue.size + (if (activeTask != null) 1 else 0) + preemptedStack.size
}
