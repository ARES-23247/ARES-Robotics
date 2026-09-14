package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.routine.RoutineTaskOwnership
import com.areslib.state.RobotState
import com.areslib.util.RobotClock
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
 *
 * Timestamps must be monotonic within an active queue and use RobotClock's millisecond domain when
 * suspend/resume is used. Negative origins are valid. Rollback or unrepresentable elapsed time
 * fails the invocation. A void suspension operation rethrows its failure; the next update/preempt
 * drains the fault and returns cleanup actions, even while suspended. Callers must dispatch them.
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
    private var hasTimestamp = false
    private var lastTimestampMs = 0L
    private var controlFailure: Throwable? = null

    // Stack of (Task, elapsedMsBeforePreemption) to enable nested preemption & resumption
    private val preemptedStack = ArrayDeque<Pair<Task, Long>>()

    /**
     * Claims an idle task tree and appends it to the standard queue. Terminal raw tasks may be
     * explicitly resubmitted; released compiled invocations must be rebuilt. Successful admission
     * marks reused nodes PENDING while preserving newly configured callbacks/timeouts. A subsequent
     * failure or cancellation while queued prevents initialization.
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
        val ownership = RoutineTaskOwnership(allowTerminalReuse = true)
        try { ownership.acquire(task); ownership.prepareQueuedInvocation() }
        catch (failure: Throwable) {
            ownership.abandonClaims()
            throw failure
        }
        admissions[task] = ownership
    }

    private fun canInitialize(task: Task): Boolean {
        val ownership = checkNotNull(admissions[task])
        return TaskStateMachine.getStatus(task) == TaskStatus.PENDING && !ownership.propagateQueuedTerminal(task)
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
        controlFailure?.let { throw it }
        if (!isSuspended) {
            try {
                val task = activeTask
                // Idle suspension has no bound clock epoch or elapsed time to preserve.
                if (task != null) {
                    val now = RobotClock.currentTimeMillis()
                    observeTimestamp(now)
                    elapsedSince(activeTaskStartTimeMs, now)
                    suspendedAtMs = now
                }
                isSuspended = true
                if (task != null) {
                    task.setTimeoutSuspended(true)
                    requireRunning(task)
                }
            } catch (failure: Throwable) { latchControlFailure(failure); throw failure }
        }
    }

    /** Resumes execution of tasks. */
    @Synchronized
    fun resume() = operation("resume") {
        controlFailure?.let { throw it }
        if (isSuspended) {
            try {
                val task = activeTask
                if (task != null) {
                    val now = RobotClock.currentTimeMillis()
                    observeTimestamp(now)
                    val shiftedStart = Math.addExact(activeTaskStartTimeMs, elapsedSince(suspendedAtMs, now))
                    requireRunning(task)
                    task.setTimeoutSuspended(false)
                    requireRunning(task)
                    activeTaskStartTimeMs = shiftedStart
                }
                isSuspended = false
            } catch (failure: Throwable) { latchControlFailure(failure); throw failure }
        }
    }

    private fun observeTimestamp(timestampMs: Long) {
        require(!hasTimestamp || timestampMs >= lastTimestampMs) { "Task executor clock moved backward" }
        lastTimestampMs = timestampMs
        hasTimestamp = true
    }

    private fun elapsedSince(startMs: Long, timestampMs: Long): Long {
        require(timestampMs >= startMs) { "Task executor clock moved backward" }
        val elapsed = timestampMs - startMs
        require(elapsed >= 0L) { "Task executor elapsed time exceeds Long.MAX_VALUE" }
        return elapsed
    }

    private fun requireRunning(task: Task) {
        admissions[task]?.propagateRuntimeTerminal(task)
        val status = TaskStateMachine.getStatus(task)
        if (status != TaskStatus.RUNNING) throw TaskTransitionAbort(emptyList(), null,
            if (status == TaskStatus.CANCELLED) TaskStatus.CANCELLED else TaskStatus.FAILED)
    }

    private fun latchControlFailure(failure: Throwable) {
        retainTaskInterruption(failure)
        controlFailure = failure
        activeTask?.let { markTransitionFailure(it, failure) }
    }

    private fun drainFault(state: RobotState, phase: String, failure: Throwable): List<RobotAction> {
        // This drain owns the failure's actions; nested cancelAllInternal must not append them again.
        controlFailure = null
        val task = activeTask ?: return cancelAllInternal(state)
        var actions = addActions(null, transitionFailureActions(task, phase, failure))
        actions = addActions(actions, if (TaskStateMachine.getStatus(task) == TaskStatus.CANCELLED)
            handleTaskCancellation(task, state) else handleTaskFailure(task, state))
        return actions ?: emptyList()
    }

    /**
     * Preempts the active task queue immediately with a high-priority task.
     * The currently active task is paused and pushed to the preemption stack, to be resumed later.
     */
    @Synchronized
    fun preempt(task: Task, state: RobotState, currentTimestampMs: Long): List<RobotAction> = operation("preempt") {
        admit(task) // Reject foreign/duplicate work before pausing the current task.
        var actions: MutableList<RobotAction>? = null

        val pending = controlFailure
        if (pending != null) {
            releaseMetadata(task)
            return@operation drainFault(state, "suspension", pending)
        }
        try { observeTimestamp(currentTimestampMs) } catch (failure: Throwable) {
            releaseMetadata(task)
            return@operation drainFault(state, "preempt timestamp", failure)
        }
        if (!canInitialize(task)) return@operation rejectUnstarted(task, state)

        val currentActive = activeTask
        if (currentActive != null) {
            var elapsed = 0L
            try {
                elapsed = elapsedSince(activeTaskStartTimeMs, if (isSuspended) suspendedAtMs else currentTimestampMs)
                requireRunning(currentActive)
                if (TaskStateMachine.getStatus(currentActive) == TaskStatus.RUNNING) {
                    actions = addActions(actions, currentActive.pause(state))
                }
                currentActive.setTimeoutSuspended(true)
                requireRunning(currentActive)
            } catch (failure: Throwable) {
                actions = addActions(actions, transitionFailureActions(currentActive, "pause", failure))
            }
            when (TaskStateMachine.getStatus(currentActive)) {
                TaskStatus.RUNNING -> {
                    preemptedStack.push(Pair(currentActive, elapsed))
                    activeTask = null
                }
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
            if (isSuspended) {
                task.setTimeoutSuspended(true)
                requireRunning(task)
            }
        } catch (e: Throwable) {
            actions = addActions(actions, drainFault(state, "preempt initialize/suspension", e))
        }
        return@operation actions ?: emptyList()
    }

    /**
     * Evaluates the active task queue based on the latest RobotState.
     * Returns a list of actions to dispatch to the Redux store.
     */
    @Synchronized
    fun update(state: RobotState, currentTimestampMs: Long): List<RobotAction> = operation("update") {
        controlFailure?.let { return@operation drainFault(state, "suspension", it) }
        if (hasTimestamp) {
            try { observeTimestamp(currentTimestampMs) } catch (failure: Throwable) {
                return@operation drainFault(state, "update timestamp", failure)
            }
        }
        if (isSuspended) {
            val pausedTask = activeTask
            if (pausedTask != null) {
                // A root can also be failed/cancelled externally while its watchdog is paused.
                when (TaskStateMachine.getStatus(pausedTask)) {
                    TaskStatus.FAILED -> return@operation handleTaskFailure(pausedTask, state)
                    TaskStatus.CANCELLED -> return@operation handleTaskCancellation(pausedTask, state)
                    else -> Unit
                }
            }
            return@operation emptyList()
        }
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
                        try {
                            activeTaskStartTimeMs = Math.subtractExact(currentTimestampMs, priorElapsed)
                            // Inspect owned descendants before any resume callback can reactivate output.
                            requireRunning(resumedTask)
                            resumedTask.setTimeoutSuspended(false)
                            requireRunning(resumedTask)
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
                        observeTimestamp(currentTimestampMs)
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
                val elapsed = try { elapsedSince(activeTaskStartTimeMs, currentTimestampMs) }
                catch (failure: Throwable) {
                    actions = addActions(actions, drainFault(state, "elapsed time", failure))
                    break
                }
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

        if (activeTask == null && preemptedStack.isEmpty() && queue.isEmpty()) hasTimestamp = false
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
        (controlFailure as? TaskTransitionAbort)?.let { actions.addAll(it.actions) }
        controlFailure = null
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
        hasTimestamp = false
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
        val label = try { task.name } catch (diagnostic: Throwable) {
            retainTaskInterruption(diagnostic); task.javaClass.name
        }
        val description = try { failure.toString() } catch (diagnostic: Throwable) {
            retainTaskInterruption(diagnostic); failure.javaClass.name
        }
        try { System.err.println("TaskExecutor: $phase failed for $label: $description") }
        catch (diagnostic: Throwable) { retainTaskInterruption(diagnostic) }
    }

    private fun markTransitionFailure(task: Task, failure: Throwable) {
        if (failure is TaskTransitionAbort) {
            if (failure.terminalStatus == TaskStatus.CANCELLED && TaskStateMachine.getStatus(task) != TaskStatus.FAILED)
                TaskStateMachine.transitionTo(task, TaskStatus.CANCELLED)
            else TaskStateMachine.markFailed(task)
        } else TaskStateMachine.markFailed(task)
    }

    private fun transitionFailureActions(task: Task, phase: String, failure: Throwable): List<RobotAction> {
        markTransitionFailure(task, failure)
        if (failure is TaskTransitionAbort) {
            if (failure.terminalStatus != TaskStatus.CANCELLED) reportFailure(task, phase, failure)
            return failure.actions
        }
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
