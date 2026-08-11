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

    private val documents = LinkedHashMap<String, RoutineDocument>()
    private val pending = ArrayDeque<PendingExecution>()
    private val active = LinkedHashMap<Long, ActiveExecution>()
    private val finishedIds = mutableListOf<Long>()
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
    @Synchronized
    fun request(
        routineId: String,
        policy: RoutineStartPolicy = RoutineStartPolicy.RESTART_EXISTING
    ): RoutineRequestResult {
        if (policy == RoutineStartPolicy.IGNORE_IF_RUNNING) {
            findExecution(routineId)?.let { return RoutineRequestResult.AlreadyRunning(it) }
        }

        val executionId = nextExecutionId++
        val compilation = RoutineCompiler(documents.toMap(), bindings).compile(routineId, executionId)
        dispatch(RobotAction.RoutineRequested(executionId, routineId, RobotClock.currentTimeMillis()))
        if (!compilation.isSuccess) {
            dispatch(
                RobotAction.RoutineFailed(
                    executionId,
                    routineId,
                    compilation.issues.joinToString(separator = "; ") { it.message },
                    RobotClock.currentTimeMillis()
                )
            )
            return RoutineRequestResult.Rejected(compilation.issues)
        }

        if (policy == RoutineStartPolicy.RESTART_EXISTING) {
            cancelRoutine(routineId, "Restarted by a new request")
        }
        val pendingExecution = PendingExecution(
            executionId,
            routineId,
            requireNotNull(compilation.task),
            compilation.resourceKeys
        )
        if (policy == RoutineStartPolicy.QUEUE && active.isNotEmpty()) {
            pending.addLast(pendingExecution)
            return RoutineRequestResult.Accepted(executionId, queued = true)
        }

        val conflict = findResourceConflict(pendingExecution.resourceKeys)
        if (conflict != null) {
            val issue = compileConflict(routineId, conflict)
            dispatch(RobotAction.RoutineFailed(executionId, routineId, issue.message, RobotClock.currentTimeMillis()))
            return RoutineRequestResult.Rejected(listOf(issue))
        }
        start(pendingExecution)
        return RoutineRequestResult.Accepted(executionId, queued = false)
    }

    /** Advances active tasks and starts the next queued invocation when the manager becomes idle. */
    @Synchronized
    fun update() {
        val now = RobotClock.currentTimeMillis()
        finishedIds.clear()
        active.values.forEach { execution ->
            execution.executor.update(stateProvider(), now).forEach(dispatch)
            if (execution.executor.size == 0) {
                val status = TaskStateMachine.getStatus(execution.task)
                if (status == TaskStatus.FAILED) {
                    dispatch(
                        RobotAction.RoutineFailed(
                            execution.executionId,
                            execution.routineId,
                            "Routine task '${execution.task.name}' failed",
                            now
                        )
                    )
                } else {
                    dispatch(RobotAction.RoutineCompleted(execution.executionId, execution.routineId, now))
                }
                finishedIds += execution.executionId
            }
        }
        finishedIds.forEach(active::remove)
        if (active.isEmpty() && pending.isNotEmpty()) {
            start(pending.removeFirst())
        }
    }

    /** Cancels one running or queued invocation and dispatches safe task cleanup first. */
    @Synchronized
    fun cancel(executionId: Long, reason: String = "Cancelled by operator"): Boolean {
        val running = active.remove(executionId)
        if (running != null) {
            running.executor.cancelAll(stateProvider()).forEach(dispatch)
            dispatch(
                RobotAction.RoutineCancelled(
                    running.executionId,
                    running.routineId,
                    reason,
                    RobotClock.currentTimeMillis()
                )
            )
            return true
        }
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val queued = iterator.next()
            if (queued.executionId == executionId) {
                iterator.remove()
                queued.task.releaseRuntimeState()
                dispatch(
                    RobotAction.RoutineCancelled(
                        queued.executionId,
                        queued.routineId,
                        reason,
                        RobotClock.currentTimeMillis()
                    )
                )
                return true
            }
        }
        return false
    }

    /** Cancels every invocation of [routineId], returning the number cancelled. */
    @Synchronized
    fun cancelRoutine(routineId: String, reason: String = "Cancelled by operator"): Int {
        val ids = buildList {
            active.values.forEach { if (it.routineId == routineId) add(it.executionId) }
            pending.forEach { if (it.routineId == routineId) add(it.executionId) }
        }
        ids.forEach { cancel(it, reason) }
        return ids.size
    }

    /** Disable/disconnect safety hook. No queued routine survives this call. */
    @Synchronized
    fun cancelAll(reason: String = "Robot disabled"): Int {
        val ids = active.keys.toList() + pending.map { it.executionId }
        ids.forEach { cancel(it, reason) }
        return ids.size
    }

    val activeCount: Int
        @Synchronized get() = active.size

    val queuedCount: Int
        @Synchronized get() = pending.size

    private fun start(execution: PendingExecution) {
        val executor = TaskExecutor().also { it.addTask(execution.task) }
        active[execution.executionId] = ActiveExecution(
            execution.executionId,
            execution.routineId,
            execution.task,
            execution.resourceKeys,
            executor
        )
        dispatch(
            RobotAction.RoutineStarted(
                execution.executionId,
                execution.routineId,
                RobotClock.currentTimeMillis()
            )
        )
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
