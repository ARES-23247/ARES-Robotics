package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.routine.RoutineTaskOwnership

/** Task status checks and non-throwing diagnostics used at executor lifecycle boundaries. */
internal object TaskExecutorFailures {
    fun requireRunning(task: Task, ownership: RoutineTaskOwnership?) {
        ownership?.propagateRuntimeTerminal(task)
        val status = TaskStateMachine.getStatus(task)
        if (status != TaskStatus.RUNNING) throw TaskTransitionAbort(emptyList(), null,
            if (status == TaskStatus.CANCELLED) TaskStatus.CANCELLED else TaskStatus.FAILED)
    }

    fun report(task: Task, phase: String, failure: Throwable) {
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

    fun mark(task: Task, failure: Throwable) {
        if (failure is TaskTransitionAbort) {
            if (failure.terminalStatus == TaskStatus.CANCELLED && TaskStateMachine.getStatus(task) != TaskStatus.FAILED)
                TaskStateMachine.transitionTo(task, TaskStatus.CANCELLED)
            else TaskStateMachine.markFailed(task)
        } else TaskStateMachine.markFailed(task)
    }

    fun actions(task: Task, phase: String, failure: Throwable): List<RobotAction> {
        mark(task, failure)
        if (failure is TaskTransitionAbort) {
            if (failure.terminalStatus != TaskStatus.CANCELLED) report(task, phase, failure)
            return failure.actions
        }
        report(task, phase, failure)
        return emptyList()
    }
}

internal fun admitExecutorTask(
    task: Task,
    admissions: java.util.IdentityHashMap<Task, com.areslib.routine.RoutineTaskOwnership>,
) {
    val ownership = com.areslib.routine.RoutineTaskOwnership(allowTerminalReuse = true)
    try {
        ownership.acquire(task)
        ownership.prepareQueuedInvocation()
    } catch (failure: Throwable) {
        ownership.abandonClaims()
        throw failure
    }
    admissions[task] = ownership
}

internal fun releaseExecutorTaskMetadata(
    task: Task,
    admissions: java.util.IdentityHashMap<Task, com.areslib.routine.RoutineTaskOwnership>,
): Boolean = try {
    val ownership = admissions.remove(task)
    if (ownership != null) ownership.releaseAll() else TaskRuntimeOwnership.release(task)
    true
} catch (failure: Throwable) {
    TaskStateMachine.markFailed(task)
    TaskExecutorFailures.report(task, "metadata release", failure)
    false
}
