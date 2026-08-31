package com.areslib.routine

import com.areslib.action.RobotAction
import com.areslib.sequencer.Task
import com.areslib.sequencer.TaskExecutor
import com.areslib.sequencer.TaskStateMachine
import com.areslib.sequencer.TaskStatus
import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import java.util.ArrayDeque

/** Behavior when a binding requests a routine that may overlap existing work. */
enum class RoutineStartPolicy {
    /** Return the existing invocation without creating another one. */
    IGNORE_IF_RUNNING,
    /** Safely cancel invocations of this routine, then start a fresh one. */
    RESTART_EXISTING,
    /** Wait until all currently running routines finish. */
    QUEUE,
    /** Run concurrently when declared subsystem resources do not overlap. */
    PARALLEL
}

/** Synchronous outcome returned to controller, auto-selector, or test code. */
sealed interface RoutineRequestResult {
    data class Accepted(val executionId: Long, val queued: Boolean) : RoutineRequestResult
    data class AlreadyRunning(val executionId: Long) : RoutineRequestResult
    data class Rejected(val issues: List<RoutineValidationIssue>) : RoutineRequestResult
}

/**
 * Owns routine invocation, cancellation, and lifecycle dispatch above [TaskExecutor].
 *
 * Call [update] once from the robot loop. Every task-produced [RobotAction] is dispatched before a
 * terminal lifecycle action. Cancellation similarly dispatches task cleanup first, ensuring the
 * Redux desired state reaches a safe value before telemetry reports that the routine stopped.
 */
class RoutineManager(
    private val bindings: RoutineRuntimeBindings,
    private val stateProvider: () -> RobotState,
    private val dispatch: (RobotAction) -> Unit
) {
    private data class PendingExecution(
        val executionId: Long,
        val routineId: String,
        val task: Task,
        val resourceKeys: Set<String>
    )

    private data class ActiveExecution(
        val executionId: Long,
        val routineId: String,
        val task: Task,
        val resourceKeys: Set<String>,
        val executor: TaskExecutor
    )

    private data class DetachedExecution(
        val executionId: Long,
        val routineId: String,
        val task: Task,
        val executor: TaskExecutor?
    )

    private val documents = LinkedHashMap<String, RoutineDocument>()
    private val pending = ArrayDeque<PendingExecution>()
    private val active = LinkedHashMap<Long, ActiveExecution>()
    private val activeInStartOrder = ArrayList<ActiveExecution>()
    private val dispatchQueue = ArrayDeque<RobotAction>()
    private val updateLock = Any()
    private val updateSnapshot = ArrayList<ActiveExecution>()
    private val updateActionBatches = ArrayList<List<RobotAction>>()
    private val updateTerminalStatuses = ArrayList<TaskStatus?>()
    private val updateEmissions = ArrayList<RobotAction>()
    private var dispatching = false
    private var nextExecutionId = 1L

    /** Replaces one document for subsequent invocations; active runs retain their compiled task. */
    @Synchronized
    fun register(document: RoutineDocument) {
        val errors = validateRoutine(document).filter { it.severity == RoutineValidationSeverity.ERROR }
        require(errors.isEmpty()) { errors.joinToString(separator = "; ") { it.message } }
        documents[document.documentId] = document
    }

    /** Atomically replaces the available project routine set after full cross-reference validation. */
    @Synchronized
    fun replaceDocuments(newDocuments: Collection<RoutineDocument>) {
        val issues = validateRoutineSet(
            newDocuments,
            RoutineValidationContext(
                hasAction = bindings.isActionKnown,
                hasCondition = bindings.isConditionKnown,
                resourcesForAction = bindings.resourcesForAction,
                resourcesForDrive = bindings.resourcesForDrive
            )
        )
        val errors = issues.filter { it.severity == RoutineValidationSeverity.ERROR }
        require(errors.isEmpty()) { errors.joinToString(separator = "; ") { it.message } }
        documents.clear()
        newDocuments.forEach { documents[it.documentId] = it }
    }

    /** Returns project diagnostics without creating runtime tasks. */
    @Synchronized
    fun validateProject(): List<RoutineValidationIssue> = validateRoutineSet(
        documents.values,
        RoutineValidationContext(
            hasAction = bindings.isActionKnown,
            hasCondition = bindings.isConditionKnown,
            resourcesForAction = bindings.resourcesForAction,
            resourcesForDrive = bindings.resourcesForDrive
        )
    )

    /** Requests a routine using the restart/queue/concurrency behavior selected by its trigger. */
    fun request(
        routineId: String,
        policy: RoutineStartPolicy = RoutineStartPolicy.RESTART_EXISTING
    ): RoutineRequestResult {
        val now = RobotClock.currentTimeMillis()
        val detached = mutableListOf<DetachedExecution>()
        val tailActions = mutableListOf<RobotAction>()
        lateinit var requestedAction: RobotAction.RoutineRequested
        lateinit var result: RoutineRequestResult

        synchronized(this) {
            if (policy == RoutineStartPolicy.IGNORE_IF_RUNNING) {
                findExecution(routineId)?.let { return RoutineRequestResult.AlreadyRunning(it) }
            }

            val executionId = nextExecutionId++
            requestedAction = RobotAction.RoutineRequested(executionId, routineId, now)
            val compilation = RoutineCompiler(documents.toMap(), bindings).compile(routineId, executionId)
            if (!compilation.isSuccess) {
                tailActions.add(
                    RobotAction.RoutineFailed(
                        executionId,
                        routineId,
                        compilation.issues.joinToString(separator = "; ") { it.message },
                        now
                    )
                )
                result = RoutineRequestResult.Rejected(compilation.issues)
                return@synchronized
            }

            if (policy == RoutineStartPolicy.RESTART_EXISTING) {
                detachMatchingLocked({ it == routineId }, detached)
            }
            val pendingExecution = PendingExecution(
                executionId,
                routineId,
                requireNotNull(compilation.task),
                compilation.resourceKeys
            )
            if (policy == RoutineStartPolicy.QUEUE && active.isNotEmpty()) {
                pending.addLast(pendingExecution)
                result = RoutineRequestResult.Accepted(executionId, queued = true)
                return@synchronized
            }

            val conflict = findResourceConflict(pendingExecution.resourceKeys)
            if (conflict != null) {
                val issue = compileConflict(routineId, conflict)
                pendingExecution.task.releaseRuntimeState()
                tailActions.add(RobotAction.RoutineFailed(executionId, routineId, issue.message, now))
                result = RoutineRequestResult.Rejected(listOf(issue))
                return@synchronized
            }
            tailActions.add(startLocked(pendingExecution, now))
            result = RoutineRequestResult.Accepted(executionId, queued = false)
        }

        val actions = mutableListOf<RobotAction>(requestedAction)
        appendCancellationActions(detached, "Restarted by a new request", now, actions)
        actions.addAll(tailActions)
        emit(actions)
        return result
    }

    /** Advances active tasks and starts the next queued invocation when the manager becomes idle. */
    fun update() = synchronized(updateLock) {
        updateSnapshot.clear()
        updateActionBatches.clear()
        updateTerminalStatuses.clear()
        updateEmissions.clear()
        try {
            val now = RobotClock.currentTimeMillis()
            synchronized(this) {
                var activeIndex = 0
                while (activeIndex < activeInStartOrder.size) {
                    updateSnapshot.add(activeInStartOrder[activeIndex])
                    activeIndex++
                }
            }
            val state = stateProvider()
            var index = 0
            while (index < updateSnapshot.size) {
                val execution = updateSnapshot[index]
                updateActionBatches.add(execution.executor.update(state, now))
                updateTerminalStatuses.add(
                    if (execution.executor.size == 0) TaskStateMachine.getStatus(execution.task) else null
                )
                index++
            }

            synchronized(this) {
                index = 0
                while (index < updateSnapshot.size) {
                    val execution = updateSnapshot[index]
                    if (active[execution.executionId] === execution) {
                        updateEmissions.addAll(updateActionBatches[index])
                        val status = updateTerminalStatuses[index]
                        if (status != null) {
                            removeActiveLocked(execution.executionId)
                            updateEmissions.add(terminalAction(execution, status, now))
                        }
                    }
                    index++
                }
                if (active.isEmpty() && pending.isNotEmpty()) {
                    updateEmissions.add(startLocked(pending.removeFirst(), now))
                }
            }
            emit(updateEmissions)
        } finally {
            updateSnapshot.clear()
            updateActionBatches.clear()
            updateTerminalStatuses.clear()
            updateEmissions.clear()
        }
    }

    /** Cancels one running or queued invocation and dispatches safe task cleanup first. */
    fun cancel(executionId: Long, reason: String = "Cancelled by operator"): Boolean {
        val detached = synchronized(this) { detachExecutionLocked(executionId) } ?: return false
        val actions = mutableListOf<RobotAction>()
        appendCancellationActions(listOf(detached), reason, RobotClock.currentTimeMillis(), actions)
        emit(actions)
        return true
    }

    /** Cancels every invocation of [routineId], returning the number cancelled. */
    fun cancelRoutine(routineId: String, reason: String = "Cancelled by operator"): Int {
        val detached = mutableListOf<DetachedExecution>()
        synchronized(this) {
            detachMatchingLocked({ it == routineId }, detached)
        }
        val actions = mutableListOf<RobotAction>()
        appendCancellationActions(detached, reason, RobotClock.currentTimeMillis(), actions)
        emit(actions)
        return detached.size
    }

    /** Disable/disconnect safety hook. No queued routine survives this call. */
    fun cancelAll(reason: String = "Robot disabled"): Int {
        val detached = mutableListOf<DetachedExecution>()
        synchronized(this) {
            detachMatchingLocked({ true }, detached)
        }
        val actions = mutableListOf<RobotAction>()
        appendCancellationActions(detached, reason, RobotClock.currentTimeMillis(), actions)
        emit(actions)
        return detached.size
    }

    val activeCount: Int
        @Synchronized get() = active.size

    val queuedCount: Int
        @Synchronized get() = pending.size

    private fun startLocked(execution: PendingExecution, timestampMs: Long): RobotAction.RoutineStarted {
        val executor = TaskExecutor().also { it.addTask(execution.task) }
        val activeExecution = ActiveExecution(
            execution.executionId,
            execution.routineId,
            execution.task,
            execution.resourceKeys,
            executor
        )
        active[execution.executionId] = activeExecution
        activeInStartOrder.add(activeExecution)
        return RobotAction.RoutineStarted(
            execution.executionId,
            execution.routineId,
            timestampMs
        )
    }

    private fun terminalAction(
        execution: ActiveExecution,
        status: TaskStatus,
        timestampMs: Long
    ): RobotAction = when (status) {
        TaskStatus.FAILED -> RobotAction.RoutineFailed(
            execution.executionId,
            execution.routineId,
            "Routine task '${execution.task.name}' failed",
            timestampMs
        )
        TaskStatus.CANCELLED -> RobotAction.RoutineCancelled(
            execution.executionId,
            execution.routineId,
            "Routine task '${execution.task.name}' cancelled",
            timestampMs
        )
        else -> RobotAction.RoutineCompleted(execution.executionId, execution.routineId, timestampMs)
    }

    /** Removes matching state under the manager lock without invoking task or subscriber code. */
    private fun detachMatchingLocked(
        matchesRoutineId: (String) -> Boolean,
        destination: MutableList<DetachedExecution>
    ) {
        var activeIndex = 0
        while (activeIndex < activeInStartOrder.size) {
            val execution = activeInStartOrder[activeIndex]
            if (matchesRoutineId(execution.routineId)) {
                activeInStartOrder.removeAt(activeIndex)
                active.remove(execution.executionId)
                destination.add(
                    DetachedExecution(
                        execution.executionId,
                        execution.routineId,
                        execution.task,
                        execution.executor
                    )
                )
            } else {
                activeIndex++
            }
        }
        val pendingIterator = pending.iterator()
        while (pendingIterator.hasNext()) {
            val execution = pendingIterator.next()
            if (matchesRoutineId(execution.routineId)) {
                pendingIterator.remove()
                destination.add(
                    DetachedExecution(execution.executionId, execution.routineId, execution.task, executor = null)
                )
            }
        }
    }

    private fun detachExecutionLocked(executionId: Long): DetachedExecution? {
        removeActiveLocked(executionId)?.let { execution ->
            return DetachedExecution(
                execution.executionId,
                execution.routineId,
                execution.task,
                execution.executor
            )
        }
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val execution = iterator.next()
            if (execution.executionId == executionId) {
                iterator.remove()
                return DetachedExecution(execution.executionId, execution.routineId, execution.task, executor = null)
            }
        }
        return null
    }

    private fun removeActiveLocked(executionId: Long): ActiveExecution? {
        val execution = active.remove(executionId) ?: return null
        var index = 0
        while (index < activeInStartOrder.size) {
            if (activeInStartOrder[index] === execution) {
                activeInStartOrder.removeAt(index)
                break
            }
            index++
        }
        return execution
    }

    private fun appendCancellationActions(
        executions: Collection<DetachedExecution>,
        reason: String,
        timestampMs: Long,
        destination: MutableList<RobotAction>
    ) {
        for (execution in executions) {
            if (execution.executor != null) {
                destination.addAll(execution.executor.cancelAll(stateProvider()))
            } else {
                execution.task.releaseRuntimeState()
            }
            destination.add(
                RobotAction.RoutineCancelled(
                    execution.executionId,
                    execution.routineId,
                    reason,
                    timestampMs
                )
            )
        }
    }

    /**
     * Serializes subscriber delivery without holding the manager monitor.
     *
     * A synchronous Store subscriber may call back into this manager. Nested emissions append to
     * the queue and are delivered after the already-committed snapshot, preventing recursive map
     * iteration and duplicate terminal transitions.
     */
    private fun emit(actions: Collection<RobotAction>) {
        if (actions.isEmpty()) return
        val shouldDrain = synchronized(this) {
            dispatchQueue.addAll(actions)
            if (dispatching) {
                false
            } else {
                dispatching = true
                true
            }
        }
        if (!shouldDrain) return

        try {
            while (true) {
                val action = synchronized(this) {
                    if (dispatchQueue.isEmpty()) {
                        dispatching = false
                        null
                    } else {
                        dispatchQueue.removeFirst()
                    }
                } ?: break
                dispatch(action)
            }
        } catch (failure: Throwable) {
            synchronized(this) {
                dispatchQueue.clear()
                dispatching = false
            }
            throw failure
        }
    }

    private fun findExecution(routineId: String): Long? =
        active.values.firstOrNull { it.routineId == routineId }?.executionId
            ?: pending.firstOrNull { it.routineId == routineId }?.executionId

    private fun findResourceConflict(requested: Set<String>): String? {
        if (requested.isEmpty()) return null
        active.values.forEach { running ->
            requested.firstOrNull(running.resourceKeys::contains)?.let { return it }
        }
        return null
    }

    private fun compileConflict(routineId: String, resource: String): RoutineValidationIssue =
        RoutineValidationIssue(
            RoutineValidationSeverity.ERROR,
            routineId,
            "routine",
            "active_resource_conflict",
            "Cannot start routine because another routine currently owns '$resource'"
        )
}
