package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Task group that runs a list of tasks sequentially, one after another.
 * Membership is copied at construction; task instances cannot repeat within built-in task trees.
 */
class SequentialTaskGroup(tasks: List<Task>) : Task {
    private val membership = TaskResourceValidator.snapshot("Sequential task group", tasks, parallel = false)
    internal val tasks: List<Task> get() = membership.tasks
    override val name = "Sequential(${this.tasks.joinToString { it.name }})"
    override val requiredResources: Long = membership.requiredResources
    internal fun suspendTimeouts(paused: Boolean) {
        for (index in tasks.indices) tasks[index].setTimeoutSuspended(paused)
    }
    private var currentIndex = 0
    private var currentTaskStartTimeMs = 0L
    private val pendingActions = mutableListOf<RobotAction>()
    private val actionsList = mutableListOf<RobotAction>()
    private val handledTasks = identityTaskSet()

    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        currentIndex = 0
        currentTaskStartTimeMs = 0L
        pendingActions.clear()
        handledTasks.clear()
        if (tasks.isEmpty()) return emptyList()
        return tasks[0].initialize(state)
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        if (!TaskTimeoutManager.permitsCompletion(this, elapsedMs)) return false
        while (currentIndex < tasks.size) {
            val currentTask = tasks[currentIndex]
            val currentTaskElapsed = elapsedMs - currentTaskStartTimeMs
            if (handleChildTerminalStatus(this, currentTask, state, handledTasks, pendingActions)) {
                return false
            }
            val childCompleted = currentTask.completionReady(state, currentTaskElapsed)
            if (!TaskTimeoutManager.permitsCompletion(this, elapsedMs)) return false
            if (handleChildTerminalStatus(this, currentTask, state, handledTasks, pendingActions)) {
                return false
            }
            if (childCompleted) {
                if (!completeChild(this, currentTask, state, handledTasks, pendingActions)) return false
                if (!TaskTimeoutManager.permitsCompletion(this, elapsedMs)) return false
                currentIndex++
                currentTaskStartTimeMs = elapsedMs
                if (currentIndex < tasks.size) {
                    pendingActions.addAll(tasks[currentIndex].initialize(state))
                }
            } else {
                return false
            }
        }
        return true
    }

    override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        super.execute(state, elapsedMs)
        actionsList.clear()
        if (pendingActions.isNotEmpty()) {
            actionsList.addAll(pendingActions)
            pendingActions.clear()
        }
        if (TaskStateMachine.getStatus(this) == TaskStatus.FAILED) return actionsList
        if (currentIndex < tasks.size) {
            val currentTask = tasks[currentIndex]
            val currentTaskElapsed = elapsedMs - currentTaskStartTimeMs
            actionsList.addAll(currentTask.execute(state, currentTaskElapsed))
            handleChildTerminalStatus(this, currentTask, state, handledTasks, actionsList)
        }
        return actionsList
    }

    override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        val actions = mutableListOf<RobotAction>()
        if (pendingActions.isNotEmpty()) {
            actions.addAll(pendingActions)
            pendingActions.clear()
        }
        if (interrupted && currentIndex < tasks.size) {
            endInterruptedChild(this, tasks[currentIndex], state, handledTasks, actions)
        }
        super.end(state, interrupted)
        return actions
    }
}

/**
 * Task group that runs multiple tasks simultaneously in parallel.
 * Membership is copied at construction; identity, not task equality, owns lifecycle bookkeeping.
 */
class ParallelTaskGroup(tasks: List<Task>) : Task {
    private val membership = TaskResourceValidator.snapshot("Parallel task group", tasks, parallel = true)
    internal val tasks: List<Task> get() = membership.tasks
    override val name = "Parallel(${this.tasks.joinToString { it.name }})"
    override val requiredResources: Long = membership.requiredResources
    internal fun suspendTimeouts(paused: Boolean) {
        for (index in tasks.indices) tasks[index].setTimeoutSuspended(paused)
    }
    private val pendingActions = mutableListOf<RobotAction>()
    private val actionsList = mutableListOf<RobotAction>()
    private val handledTasks = identityTaskSet()

    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        pendingActions.clear()
        handledTasks.clear()
        return tasks.flatMap { it.initialize(state) }
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        if (!TaskTimeoutManager.permitsCompletion(this, elapsedMs)) return false
        for (i in 0 until tasks.size) {
            val task = tasks[i]
            if (!handledTasks.contains(task)) {
                if (handleChildTerminalStatus(this, task, state, handledTasks, pendingActions)) {
                    return false
                }
                val childCompleted = task.completionReady(state, elapsedMs)
                if (!TaskTimeoutManager.permitsCompletion(this, elapsedMs)) return false
                if (childCompleted) {
                    if (!completeChild(this, task, state, handledTasks, pendingActions)) return false
                    if (!TaskTimeoutManager.permitsCompletion(this, elapsedMs)) return false
                } else if (handleChildTerminalStatus(this, task, state, handledTasks, pendingActions)) {
                    return false
                }
            }
        }
        return handledTasks.size == tasks.size
    }

    override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        super.execute(state, elapsedMs)
        actionsList.clear()
        if (pendingActions.isNotEmpty()) {
            actionsList.addAll(pendingActions)
            pendingActions.clear()
        }
        if (TaskStateMachine.getStatus(this) == TaskStatus.FAILED) return actionsList
        for (i in 0 until tasks.size) {
            val task = tasks[i]
            if (!handledTasks.contains(task)) {
                actionsList.addAll(task.execute(state, elapsedMs))
                if (handleChildTerminalStatus(this, task, state, handledTasks, actionsList)) break
            }
        }
        return actionsList
    }

    override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        val actions = mutableListOf<RobotAction>()
        if (pendingActions.isNotEmpty()) {
            actions.addAll(pendingActions)
            pendingActions.clear()
        }
        if (interrupted) {
            for (i in 0 until tasks.size) {
                val task = tasks[i]
                endInterruptedChild(this, task, state, handledTasks, actions)
            }
        }
        super.end(state, interrupted)
        return actions
    }
}

/**
 * Task group that runs multiple tasks simultaneously in parallel.
 * Membership is copied at construction; identity, not task equality, owns lifecycle bookkeeping.
 * Finishes as soon as ANY of the tasks completes, interrupting the rest.
 */
class ParallelRaceGroup(tasks: List<Task>) : Task {
    private val membership = TaskResourceValidator.snapshot("Parallel race group", tasks, parallel = true)
    internal val tasks: List<Task> get() = membership.tasks
    init {
        require(tasks.isNotEmpty()) { "Parallel race requires at least one task" }
    }
    override val name = "ParallelRace(${this.tasks.joinToString { it.name }})"
    override val requiredResources: Long = membership.requiredResources
    internal fun suspendTimeouts(paused: Boolean) {
        for (index in tasks.indices) tasks[index].setTimeoutSuspended(paused)
    }
    private val pendingActions = mutableListOf<RobotAction>()
    private val actionsList = mutableListOf<RobotAction>()
    private var isCompleted = false
    private val handledTasks = identityTaskSet()

    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        pendingActions.clear()
        isCompleted = false
        handledTasks.clear()
        return tasks.flatMap { it.initialize(state) }
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        if (!TaskTimeoutManager.permitsCompletion(this, elapsedMs)) return false
        if (isCompleted) return true
        for (i in 0 until tasks.size) {
            val task = tasks[i]
            if (handleChildTerminalStatus(this, task, state, handledTasks, pendingActions)) {
                return false
            }
            val childCompleted = task.completionReady(state, elapsedMs)
            if (!TaskTimeoutManager.permitsCompletion(this, elapsedMs)) return false
            if (childCompleted) {
                if (!completeChild(this, task, state, handledTasks, pendingActions)) return false
                if (!TaskTimeoutManager.permitsCompletion(this, elapsedMs)) return false
                // The first finisher wins the race for this whole pass: stop evaluating
                // siblings immediately so a later-iterated failed sibling cannot retroactively
                // turn a finished race into a failure, and stragglers cannot "complete
                // normally" in the same tick they should have been interrupted.
                isCompleted = true
                break
            } else if (handleChildTerminalStatus(this, task, state, handledTasks, pendingActions)) {
                return false
            }
        }
        return isCompleted
    }

    override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        super.execute(state, elapsedMs)
        actionsList.clear()
        if (pendingActions.isNotEmpty()) {
            actionsList.addAll(pendingActions)
            pendingActions.clear()
        }
        if (TaskStateMachine.getStatus(this) == TaskStatus.FAILED) return actionsList
        if (isCompleted) return actionsList

        for (i in 0 until tasks.size) {
            val task = tasks[i]
            if (!handledTasks.contains(task)) {
                actionsList.addAll(task.execute(state, elapsedMs))
                if (handleChildTerminalStatus(this, task, state, handledTasks, actionsList)) break
            }
        }
        return actionsList
    }

    override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        val actions = mutableListOf<RobotAction>()
        if (pendingActions.isNotEmpty()) {
            actions.addAll(pendingActions)
            pendingActions.clear()
        }
        for (i in 0 until tasks.size) {
            val task = tasks[i]
            endInterruptedChild(this, task, state, handledTasks, actions)
        }
        super.end(state, interrupted)
        return actions
    }
}

/**
 * Task group that runs multiple tasks simultaneously in parallel.
 * Membership is copied at construction; identity, not task equality, owns lifecycle bookkeeping.
 * Finishes as soon as a specific "deadline" task completes, interrupting the rest.
 */
class ParallelDeadlineGroup(
    private val deadline: Task,
    otherTasks: List<Task>
) : Task {
    private val membership = TaskResourceValidator.snapshot(
        "Parallel deadline group", listOf(deadline) + otherTasks, parallel = true
    )
    internal val tasks: List<Task> get() = membership.tasks
    override val name = "ParallelDeadline(deadline=${deadline.name}, others=${otherTasks.joinToString { it.name }})"
    override val requiredResources: Long = membership.requiredResources
    internal fun suspendTimeouts(paused: Boolean) {
        for (index in tasks.indices) tasks[index].setTimeoutSuspended(paused)
    }
    private val pendingActions = mutableListOf<RobotAction>()
    private val actionsList = mutableListOf<RobotAction>()
    private val handledTasks = identityTaskSet()

    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        pendingActions.clear()
        handledTasks.clear()
        return tasks.flatMap { it.initialize(state) }
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        if (!TaskTimeoutManager.permitsCompletion(this, elapsedMs)) return false
        for (i in 0 until tasks.size) {
            val task = tasks[i]
            if (!handledTasks.contains(task)) {
                if (handleChildTerminalStatus(this, task, state, handledTasks, pendingActions)) {
                    return false
                }
                val childCompleted = task.completionReady(state, elapsedMs)
                if (!TaskTimeoutManager.permitsCompletion(this, elapsedMs)) return false
                if (childCompleted) {
                    if (!completeChild(this, task, state, handledTasks, pendingActions)) return false
                    if (!TaskTimeoutManager.permitsCompletion(this, elapsedMs)) return false
                } else if (handleChildTerminalStatus(this, task, state, handledTasks, pendingActions)) {
                    return false
                }
            }
        }
        return handledTasks.contains(deadline)
    }

    override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        super.execute(state, elapsedMs)
        actionsList.clear()
        if (pendingActions.isNotEmpty()) {
            actionsList.addAll(pendingActions)
            pendingActions.clear()
        }
        if (TaskStateMachine.getStatus(this) == TaskStatus.FAILED) return actionsList
        if (handledTasks.contains(deadline)) return actionsList

        for (i in 0 until tasks.size) {
            val task = tasks[i]
            if (!handledTasks.contains(task)) {
                actionsList.addAll(task.execute(state, elapsedMs))
                if (handleChildTerminalStatus(this, task, state, handledTasks, actionsList)) break
            }
        }
        return actionsList
    }

    override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        val actions = mutableListOf<RobotAction>()
        if (pendingActions.isNotEmpty()) {
            actions.addAll(pendingActions)
            pendingActions.clear()
        }
        for (i in 0 until tasks.size) {
            val task = tasks[i]
            endInterruptedChild(this, task, state, handledTasks, actions)
        }
        super.end(state, interrupted)
        return actions
    }
}

private fun endInterruptedChild(
    parent: Task,
    child: Task,
    state: RobotState,
    handledTasks: MutableSet<Task>,
    actions: MutableList<RobotAction>,
) {
    if (!handledTasks.add(child)) return
    try {
        // A failed group initialization may leave later children entirely unstarted.
        if (TaskStateMachine.getStatus(child) != TaskStatus.PENDING) {
            actions.addAll(child.end(state, interrupted = true))
        }
    } catch (failure: Throwable) {
        reportChildFailure(parent, child, "end", failure)
    } finally {
        releaseChildMetadata(parent, child)
    }
}

private fun releaseChildMetadata(parent: Task, child: Task) {
    try { TaskRuntimeOwnership.release(child) }
    catch (failure: Throwable) { reportChildFailure(parent, child, "metadata release", failure) }
}

private fun reportChildFailure(parent: Task, child: Task, phase: String, failure: Throwable) {
    retainTaskInterruption(failure)
    TaskStateMachine.markFailed(parent)
    TaskStateMachine.markFailed(child)
    val label = try { child.name } catch (_: Throwable) { child.javaClass.name }
    System.err.println("TaskGroup: $phase failed for $label: $failure")
}

/** Normal completion must still run interrupted cleanup if end rejects success or throws. */
private fun completeChild(
    parent: Task,
    child: Task,
    state: RobotState,
    handledTasks: MutableSet<Task>,
    actions: MutableList<RobotAction>
): Boolean {
    try {
        try {
            actions.addAll(child.end(state, interrupted = false))
        } catch (failure: Throwable) {
            retainTaskInterruption(failure)
            TaskStateMachine.markFailed(child)
            handleChildTerminalStatus(parent, child, state, handledTasks, actions)
            throw failure
        }
        return !handleChildTerminalStatus(parent, child, state, handledTasks, actions)
    } finally {
        if (handledTasks.add(child)) releaseChildMetadata(parent, child)
    }
}

private fun identityTaskSet(): MutableSet<Task> =
    Collections.newSetFromMap(IdentityHashMap<Task, Boolean>())

/**
 * Consumes a child's terminal failure/cancellation on the control-loop thread.
 *
 * The watchdog only marks status. Group execution owns callback delivery and interrupted cleanup,
 * records that cleanup before propagating to the parent, and therefore cannot end the same child a
 * second time when the parent executor performs its own terminal cleanup.
 */
private fun handleChildTerminalStatus(
    parent: Task,
    child: Task,
    state: RobotState,
    handledTasks: MutableSet<Task>,
    actions: MutableList<RobotAction>
): Boolean {
    return when (TaskStateMachine.getStatus(child)) {
        TaskStatus.FAILED -> {
            if (!handledTasks.contains(child)) {
                try {
                    TaskCallbacks.invokeFail(child)
                } catch (failure: Throwable) {
                    reportChildFailure(parent, child, "failure callback", failure)
                }
                endInterruptedChild(parent, child, state, handledTasks, actions)
            }
            TaskStateMachine.markFailed(parent)
            true
        }
        TaskStatus.CANCELLED -> {
            endInterruptedChild(parent, child, state, handledTasks, actions)
            if (TaskStateMachine.getStatus(parent) != TaskStatus.FAILED) {
                TaskStateMachine.transitionTo(parent, TaskStatus.CANCELLED)
            }
            true
        }
        else -> false
    }
}
