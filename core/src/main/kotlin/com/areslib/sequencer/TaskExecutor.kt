package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import java.util.ArrayDeque

/**
 * Synchronized task queue with nested last-in/first-out preemption.
 *
 * Task lifecycle methods execute while holding the executor monitor. They must return quickly and
 * must not call back from another thread while waiting for this executor. Returned Redux actions are
 * caller-owned and are not dispatched internally. An update may finish/start several immediately
 * complete tasks, capped at 100 transitions to prevent a malformed queue from locking the loop.
 *
 * Preemption calls [Task.pause] without performing terminal cleanup and pauses the task's watchdog
 * until [Task.resume] is called. [clear] performs best-effort interrupted
 * cleanup, releases runtime registries, and suppresses task exceptions.
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
                actions = addActions(actions, currentActive.pause(state))
            } catch (e: Exception) {
                System.err.println("TaskExecutor: Exception while pausing task ${currentActive.name}: ${e.message}")
                e.printStackTrace()
            } finally {
                TaskTimeoutManager.pause(currentActive)
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
                        TaskStateMachine.transitionTo(resumedTask, TaskStatus.RUNNING)
                        TaskTimeoutManager.resume(resumedTask)
                        try {
                            actions = addActions(actions, resumedTask.resume(state))
                        } catch (e: Exception) {
                            System.err.println("TaskExecutor: Exception while resuming task ${resumedTask.name}: ${e.message}")
                            actions = addActions(actions, handleTaskFailure(resumedTask, state))
                            task = null
                            continue
                        }
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
                    } finally {
                        task.releaseRuntimeState()
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
        if (TaskStateMachine.markFailed(task)) {
            try {
                TaskCallbacks.invokeFail(task)
            } catch (e: Exception) {
                System.err.println("TaskExecutor: Exception during failure callback: ${e.message}")
            }
        }
        val cleanupActions = try {
            task.end(state, interrupted = true)
        } catch (e: Exception) {
            System.err.println("TaskExecutor: Exception during task.end cleanup: ${e.message}")
            e.printStackTrace()
            emptyList()
        } finally {
            task.releaseRuntimeState()
        }
        activeTask = null
        return cleanupActions
    }

    /**
     * Interrupts initialized tasks, clears the executor, and returns every safe-cleanup action.
     *
     * Unlike [clear], this method makes cancellation output observable to its caller. Runtime
     * managers must dispatch the returned actions before publishing their cancelled lifecycle
     * event. Tasks that never initialized are released without calling `end`, because their
     * cleanup implementations may depend on initialization-only state.
     */
    @Synchronized
    fun cancelAll(state: RobotState): List<RobotAction> {
        val actions = mutableListOf<RobotAction>()
        activeTask?.let { task ->
            try {
                actions.addAll(task.end(state, interrupted = true))
            } catch (error: Exception) {
                System.err.println("TaskExecutor: Exception ending active task ${task.name}: ${error.message}")
            } finally {
                task.releaseRuntimeState()
            }
        }
        for ((task, _) in preemptedStack) {
            try {
                actions.addAll(task.end(state, interrupted = true))
            } catch (error: Exception) {
                System.err.println("TaskExecutor: Exception ending preempted task ${task.name}: ${error.message}")
            } finally {
                task.releaseRuntimeState()
            }
        }
        for (task in queue) {
            task.releaseRuntimeState()
        }
        queue.clear()
        preemptedStack.clear()
        activeTask = null
        return actions
    }

    /**
     * Clears all tasks while retaining the legacy API that intentionally discards cleanup output.
     * New lifecycle owners should call [cancelAll] and dispatch its returned actions.
     */
    @Synchronized
    fun clear(state: RobotState) {
        cancelAll(state)
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
