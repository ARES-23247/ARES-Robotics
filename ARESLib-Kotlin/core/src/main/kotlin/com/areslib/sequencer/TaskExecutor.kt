package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.routine.RoutineTaskOwnership
import com.areslib.state.RobotState
import java.util.ArrayDeque
import java.util.IdentityHashMap

/**
 * Synchronized task queue with nested last-in/first-out preemption.
 *
 * Task lifecycle methods execute while holding the executor monitor. They must return quickly and
 * must not call back from another thread while waiting for this executor. Returned Redux actions are
 * caller-owned and are not dispatched internally. An update may finish/start several immediately
 * complete tasks, capped at 100 transitions to prevent a malformed queue from locking the loop.
 *
 * Preemption calls [Task.pause] without performing terminal cleanup and pauses the task's watchdog
 * until [Task.resume] is called. [cancelAll] performs best-effort interrupted cleanup and releases
 * runtime registries. Each admission exclusively owns its task tree until terminal cleanup.
 * Failed admission preserves the caller's task metadata and does not pause existing work.
 *
 * Same-executor recursive update/preempt/cancel/suspend/resume is rejected before mutation. A
 * lifecycle callback may append fresh work with [addTask], except during failure/cancellation or
 * the executor's final metadata drain. These phases reject additions so they cannot replenish the queue.
 */
class TaskExecutor {
    private val queue = ArrayDeque<Task>()
    private val admissions = IdentityHashMap<Task, RoutineTaskOwnership>()
    private var isOperating = false
    private var isCleaningUp = false
    private var activeTask: Task? = null
    private var activeTaskStartTimeMs: Long = 0L
    private var isSuspended = false
    private var suspendedAtMs: Long = 0L

    // Stack of (Task, elapsedMsBeforePreemption) to enable nested preemption & resumption
    private val preemptedStack = ArrayDeque<Pair<Task, Long>>()

    /**
     * Claims an idle task tree and appends it to the standard queue. Completed raw tasks may be
     * resubmitted; completed compiled invocations must be rebuilt. Failed/cancelled tasks need reset.
     * Duplicate, running, and foreign-owned instances are rejected without metadata loss.
     * Callers must not initialize or reset admitted tasks before the executor releases them.
     */
    @Synchronized
    fun addTask(task: Task) {
        check(!isCleaningUp) { "Cannot enqueue tasks during executor cleanup" }
        admit(task)
        queue.offer(task)
    }

    private fun admit(task: Task) {
        val ownership = RoutineTaskOwnership(allowCompleted = true)
        try { ownership.acquire(task) }
        catch (failure: Throwable) {
            ownership.abandonClaims()
            throw failure
        }
        admissions[task] = ownership
    }

    private fun canInitialize(task: Task): Boolean {
        val ownership = checkNotNull(admissions[task])
        return ownership.permitsInitialStatus(task) && !ownership.propagateQueuedTerminal(task)
    }

    private inline fun <T> operation(name: String, block: () -> T): T {
        check(!isOperating) { "Cannot recursively call TaskExecutor.$name from a lifecycle callback" }
        isOperating = true
        try { return block() } finally { isOperating = false }
    }

    private inline fun <T> cleanup(block: () -> T): T {
        val previous = isCleaningUp
        isCleaningUp = true
        try { return block() } finally { isCleaningUp = previous }
    }

    /**
     * Suspends execution of all tasks. Time spent suspended does not count against the active
     * task's elapsed duration or timeout: [resume] shifts the active task's start time forward
     * by the suspended interval so waits and deadlines measure execution time, not wall time.
     */
    @Synchronized
    fun suspend() = operation("suspend") {
        if (!isSuspended) {
            isSuspended = true
            suspendedAtMs = com.areslib.util.RobotClock.currentTimeMillis()
            activeTask?.setTimeoutSuspended(true)
        }
    }

    /** Resumes execution of tasks. */
    @Synchronized
    fun resume() = operation("resume") {
        if (isSuspended) {
            isSuspended = false
            activeTask?.setTimeoutSuspended(false)
            activeTaskStartTimeMs += com.areslib.util.RobotClock.currentTimeMillis() - suspendedAtMs
        }
    }

    /**
     * Preempts the active task queue immediately with a high-priority task.
     * The currently active task is paused and pushed to the preemption stack, to be resumed later.
     */
    @Synchronized
    fun preempt(task: Task, state: RobotState, currentTimestampMs: Long): List<RobotAction> = operation("preempt") {
        admit(task) // Reject foreign/duplicate work before pausing the current task.
        var actions: MutableList<RobotAction>? = null

        val currentActive = activeTask
        if (currentActive != null) {
            val elapsed = (if (isSuspended) suspendedAtMs else currentTimestampMs) - activeTaskStartTimeMs
            try {
                if (TaskStateMachine.getStatus(currentActive) == TaskStatus.RUNNING) {
                    actions = addActions(actions, currentActive.pause(state))
                }
                currentActive.setTimeoutSuspended(true)
            } catch (failure: Throwable) {
                actions = addActions(actions, transitionFailureActions(currentActive, "pause", failure))
            }
            when (TaskStateMachine.getStatus(currentActive)) {
                TaskStatus.RUNNING -> preemptedStack.push(Pair(currentActive, elapsed))
                else -> {
                    actions = addActions(actions, if (TaskStateMachine.getStatus(currentActive) == TaskStatus.CANCELLED)
                        handleTaskCancellation(currentActive, state) else handleTaskFailure(currentActive, state))
                    releaseMetadata(task) // Incoming work never initialized and must not survive this fault.
                    return@operation actions ?: emptyList()
                }
            }
        }
        
        if (!canInitialize(task)) {
            actions = addActions(actions, rejectUnstarted(task, state))
            return@operation actions ?: emptyList()
        }
        if (isSuspended) suspendedAtMs = currentTimestampMs
        activeTask = task
        activeTaskStartTimeMs = currentTimestampMs
        try {
            actions = addActions(actions, task.initialize(state))
        } catch (e: Throwable) {
            reportFailure(task, "preempt initialize", e)
            actions = addActions(actions, handleTaskFailure(task, state))
        } finally {
            if (isSuspended) activeTask?.setTimeoutSuspended(true)
        }
        return@operation actions ?: emptyList()
    }

    /**
     * Evaluates the active task queue based on the latest RobotState.
     * Returns a list of actions to dispatch to the Redux store.
     */
    @Synchronized
    fun update(state: RobotState, currentTimestampMs: Long): List<RobotAction> = operation("update") {
        if (isSuspended) return@operation emptyList()
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
                        try {
                            // Preemption never changes RUNNING to another status. Do not revive a fault.
                            if (TaskStateMachine.getStatus(resumedTask) != TaskStatus.RUNNING) {
                                if (TaskStateMachine.getStatus(resumedTask) != TaskStatus.CANCELLED) TaskStateMachine.markFailed(resumedTask)
                                task = resumedTask
                                continue
                            }
                            resumedTask.setTimeoutSuspended(false)
                            if (TaskStateMachine.getStatus(resumedTask) != TaskStatus.RUNNING) {
                                task = resumedTask
                                continue
                            }
                            actions = addActions(actions, resumedTask.resume(state))
                        } catch (e: Throwable) {
                            actions = addActions(actions, transitionFailureActions(resumedTask, "resume", e))
                            actions = addActions(actions, if (TaskStateMachine.getStatus(resumedTask) == TaskStatus.CANCELLED)
                                handleTaskCancellation(resumedTask, state) else handleTaskFailure(resumedTask, state))
                            task = null
                            continue
                        }
                        task = resumedTask
                    }
                    queue.isNotEmpty() -> {
                        // Dequeue the next task
                        val nextTask = queue.poll()
                        if (!canInitialize(nextTask)) {
                            actions = addActions(actions, rejectUnstarted(nextTask, state))
                            break
                        }
                        activeTask = nextTask
                        activeTaskStartTimeMs = currentTimestampMs
                        try {
                            actions = addActions(actions, nextTask.initialize(state))
                        } catch (e: Throwable) {
                            reportFailure(nextTask, "initialize", e)
                            actions = addActions(actions, handleTaskFailure(nextTask, state))
                            break
                        }
                        task = nextTask
                    }
                    else -> break
                }
            }

            if (task != null) {
                when (TaskStateMachine.getStatus(task)) {
                    TaskStatus.FAILED -> {
                        actions = addActions(actions, handleTaskFailure(task, state))
                        break
                    }
                    TaskStatus.CANCELLED -> {
                        actions = addActions(actions, handleTaskCancellation(task, state))
                        break
                    }
                    else -> Unit
                }
                val elapsed = currentTimestampMs - activeTaskStartTimeMs
                val isCompleted = try {
                    task.completionReady(state, elapsed)
                } catch (e: Throwable) {
                    reportFailure(task, "completion check", e)
                    actions = addActions(actions, handleTaskFailure(task, state))
                    break
                }
                
                val terminalStatus = TaskStateMachine.getStatus(task)

                if (terminalStatus == TaskStatus.FAILED) {
                    actions = addActions(actions, handleTaskFailure(task, state))
                    break
                } else if (terminalStatus == TaskStatus.CANCELLED) {
                    actions = addActions(actions, handleTaskCancellation(task, state))
                    break
                } else if (isCompleted) {
                    // Finalize active task
                    try {
                        actions = addActions(actions, task.end(state, interrupted = false))
                    } catch (e: Throwable) {
                        reportFailure(task, "end", e)
                        actions = addActions(actions, handleTaskFailure(task, state))
                        break
                    }
                    when (TaskStateMachine.getStatus(task)) {
                        TaskStatus.FAILED -> {
                            actions = addActions(actions, handleTaskFailure(task, state))
                            break
                        }
                        TaskStatus.CANCELLED -> {
                            actions = addActions(actions, handleTaskCancellation(task, state))
                            break
                        }
                        else -> Unit
                    }
                    activeTask = null
                    if (!releaseMetadata(task) || TaskStateMachine.getStatus(task) == TaskStatus.FAILED) {
                        actions = addActions(actions, cancelAllInternal(state))
                        break
                    }
                    task = null // Continue loop to dequeue/resume instantly
                } else {
                    val execActions = try {
                        task.execute(state, elapsed)
                    } catch (e: Throwable) {
                        reportFailure(task, "execute", e)
                        actions = addActions(actions, handleTaskFailure(task, state))
                        break
                    }
                    actions = addActions(actions, execActions)
                    when (TaskStateMachine.getStatus(task)) {
                        TaskStatus.FAILED -> actions = addActions(actions, handleTaskFailure(task, state))
                        TaskStatus.CANCELLED -> actions = addActions(actions, handleTaskCancellation(task, state))
                        else -> Unit
                    }
                    break // Stop frame update as active task is currently running
                }
            }
        }

        if (loopCount >= maxLoopCount) {
            System.err.println("TaskExecutor: Loop transition threshold reached ($maxLoopCount). Aborting update to prevent lockup.")
        }

        return@operation actions ?: emptyList()
    }

    private fun addActions(existing: MutableList<RobotAction>?, newActions: List<RobotAction>): MutableList<RobotAction>? {
        if (newActions.isEmpty()) return existing
        val list = existing ?: mutableListOf()
        list.addAll(newActions)
        return list
    }

    private fun handleTaskFailure(task: Task, state: RobotState): List<RobotAction> = cleanup {
        TaskStateMachine.markFailed(task)
        activeTask = null
        try {
            // Invocation is one-shot, so this also covers tasks that marked themselves failed.
            TaskCallbacks.invokeFail(task)
        } catch (e: Throwable) {
            reportFailure(task, "failure callback", e)
        }
        val cleanupActions = try {
            task.end(state, interrupted = true)
        } catch (e: Throwable) {
            reportFailure(task, "failed task end", e)
            emptyList()
        } finally {
            releaseMetadata(task)
        }
        val allCleanupActions = cleanupActions.toMutableList()
        allCleanupActions.addAll(cancelAllInternal(state))
        allCleanupActions
    }

    /** Performs interrupted cleanup without converting cancellation into completion or failure. */
    private fun handleTaskCancellation(task: Task, state: RobotState): List<RobotAction> = cleanup {
        activeTask = null
        val cleanupActions = try {
            task.end(state, interrupted = true)
        } catch (error: Throwable) {
            reportFailure(task, "cancelled task end", error)
            emptyList()
        } finally {
            releaseMetadata(task)
        }
        val allCleanupActions = cleanupActions.toMutableList()
        allCleanupActions.addAll(cancelAllInternal(state))
        allCleanupActions
    }

    /**
     * Interrupts initialized tasks, clears the executor, and returns every safe-cleanup action.
     *
     * This method makes cancellation output observable to its caller. Runtime
     * managers must dispatch the returned actions before publishing their cancelled lifecycle
     * event. Tasks that never initialized are released without calling `end`, because their
     * cleanup implementations may depend on initialization-only state.
     */
    @Synchronized
    fun cancelAll(state: RobotState): List<RobotAction> = operation("cancelAll") {
        cancelAllInternal(state)
    }

    private fun cancelAllInternal(state: RobotState): List<RobotAction> = cleanup {
        val actions = mutableListOf<RobotAction>()
        val current = activeTask
        activeTask = null
        fun cancelStarted(task: Task) {
            try {
                actions.addAll(task.end(state, interrupted = true))
            } catch (error: Throwable) {
                reportFailure(task, "cancel end", error)
                TaskStateMachine.markFailed(task)
            } finally {
                releaseMetadata(task)
            }
        }
        current?.let(::cancelStarted)
        while (preemptedStack.isNotEmpty()) cancelStarted(preemptedStack.pop().first)
        while (queue.isNotEmpty()) releaseMetadata(queue.removeFirst())
        actions
    }

    private fun rejectUnstarted(task: Task, state: RobotState): List<RobotAction> = cleanup {
        // External initialization after enqueue is invalid, but any running output still needs end.
        if (TaskStateMachine.getStatus(task) == TaskStatus.RUNNING) {
            return@cleanup handleTaskFailure(task, state)
        }
        if (TaskStateMachine.getStatus(task) != TaskStatus.CANCELLED) {
            TaskStateMachine.markFailed(task)
            try { TaskCallbacks.invokeFail(task) } catch (failure: Throwable) {
                reportFailure(task, "queued task failure callback", failure)
            }
        }
        releaseMetadata(task)
        cancelAllInternal(state)
    }

    private fun releaseMetadata(task: Task): Boolean = cleanup {
        try {
            val ownership = admissions.remove(task)
            if (ownership != null) ownership.releaseAll() else TaskRuntimeOwnership.release(task)
            true
        } catch (failure: Throwable) {
            TaskStateMachine.markFailed(task)
            reportFailure(task, "metadata release", failure)
            false
        }
    }

    private fun reportFailure(task: Task, phase: String, failure: Throwable) {
        retainTaskInterruption(failure)
        val label = try { task.name } catch (_: Throwable) { task.javaClass.name }
        System.err.println("TaskExecutor: $phase failed for $label: $failure")
    }

    private fun transitionFailureActions(task: Task, phase: String, failure: Throwable): List<RobotAction> {
        if (failure is TaskTransitionAbort) {
            if (failure.terminalStatus == TaskStatus.CANCELLED && TaskStateMachine.getStatus(task) != TaskStatus.FAILED)
                TaskStateMachine.transitionTo(task, TaskStatus.CANCELLED)
            else TaskStateMachine.markFailed(task)
            if (failure.terminalStatus != TaskStatus.CANCELLED) reportFailure(task, phase, failure)
            return failure.actions
        }
        TaskStateMachine.markFailed(task)
        reportFailure(task, phase, failure)
        return emptyList()
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
