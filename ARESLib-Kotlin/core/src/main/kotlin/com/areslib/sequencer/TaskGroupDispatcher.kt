package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Task group that runs a list of tasks sequentially, one after another.
 */
class SequentialTaskGroup(private val tasks: List<Task>) : Task {
    override val name = "Sequential(${tasks.joinToString { it.name }})"
    override val requiredResources: Long = TaskResourceValidator.union(tasks)
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
        while (currentIndex < tasks.size) {
            val currentTask = tasks[currentIndex]
            val currentTaskElapsed = elapsedMs - currentTaskStartTimeMs
            if (handleChildTerminalStatus(this, currentTask, state, handledTasks, pendingActions)) {
                return false
            }
            val childCompleted = currentTask.isCompleted(state, currentTaskElapsed)
            if (handleChildTerminalStatus(this, currentTask, state, handledTasks, pendingActions)) {
                return false
            }
            if (childCompleted) {
                try {
                    pendingActions.addAll(currentTask.end(state, interrupted = false))
                } finally {
                    handledTasks.add(currentTask)
                    currentTask.releaseRuntimeState()
                }
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
        if (interrupted && currentIndex < tasks.size && !handledTasks.contains(tasks[currentIndex])) {
            val current = tasks[currentIndex]
            try {
                actions.addAll(current.end(state, interrupted = true))
            } finally {
                handledTasks.add(current)
                current.releaseRuntimeState()
            }
        }
        super.end(state, interrupted)
        return actions
    }
}

/**
 * Task group that runs multiple tasks simultaneously in parallel.
 */
class ParallelTaskGroup(private val tasks: List<Task>) : Task {
    init {
        TaskResourceValidator.requireNoParallelConflicts("Parallel task group", tasks)
    }
    override val name = "Parallel(${tasks.joinToString { it.name }})"
    override val requiredResources: Long = TaskResourceValidator.union(tasks)
    private val completedTasks = mutableSetOf<Task>()
    private val pendingActions = mutableListOf<RobotAction>()
    private val actionsList = mutableListOf<RobotAction>()
    private val handledTasks = identityTaskSet()

    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        completedTasks.clear()
        pendingActions.clear()
        handledTasks.clear()
        return tasks.flatMap { it.initialize(state) }
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        for (i in 0 until tasks.size) {
            val task = tasks[i]
            if (!completedTasks.contains(task)) {
                if (handleChildTerminalStatus(this, task, state, handledTasks, pendingActions)) {
                    return false
                }
                if (task.isCompleted(state, elapsedMs)) {
                    completedTasks.add(task)
                    try {
                        pendingActions.addAll(task.end(state, interrupted = false))
                    } finally {
                        handledTasks.add(task)
                        task.releaseRuntimeState()
                    }
                } else if (handleChildTerminalStatus(this, task, state, handledTasks, pendingActions)) {
                    return false
                }
            }
        }
        return completedTasks.size == tasks.size
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
            if (!completedTasks.contains(task)) {
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
                if (!handledTasks.contains(task)) {
                    try {
                        actions.addAll(task.end(state, interrupted = true))
                    } catch (failure: Throwable) {
                        System.err.println("TaskGroup: Exception ending task ${task.name}: ${failure.message}")
                    } finally {
                        handledTasks.add(task)
                        task.releaseRuntimeState()
                    }
                }
            }
        }
        super.end(state, interrupted)
        return actions
    }
}

/**
 * Task group that runs multiple tasks simultaneously in parallel.
 * Finishes as soon as ANY of the tasks completes, interrupting the rest.
 */
class ParallelRaceGroup(private val tasks: List<Task>) : Task {
    init {
        require(tasks.isNotEmpty()) { "Parallel race requires at least one task" }
        TaskResourceValidator.requireNoParallelConflicts("Parallel race group", tasks)
    }
    override val name = "ParallelRace(${tasks.joinToString { it.name }})"
    override val requiredResources: Long = TaskResourceValidator.union(tasks)
    private val completedTasks = mutableSetOf<Task>()
    private val pendingActions = mutableListOf<RobotAction>()
    private val actionsList = mutableListOf<RobotAction>()
    private var isCompleted = false
    private val handledTasks = identityTaskSet()

    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        completedTasks.clear()
        pendingActions.clear()
        isCompleted = false
        handledTasks.clear()
        return tasks.flatMap { it.initialize(state) }
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        if (isCompleted) return true
        for (i in 0 until tasks.size) {
            val task = tasks[i]
            if (handleChildTerminalStatus(this, task, state, handledTasks, pendingActions)) {
                return false
            }
            if (task.isCompleted(state, elapsedMs)) {
                completedTasks.add(task)
                try {
                    pendingActions.addAll(task.end(state, interrupted = false))
                } finally {
                    handledTasks.add(task)
                    task.releaseRuntimeState()
                }
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
            if (!completedTasks.contains(task)) {
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
            if (!handledTasks.contains(task)) {
                try {
                    actions.addAll(task.end(state, interrupted = true))
                } catch (failure: Throwable) {
                    System.err.println("ParallelRaceGroup: failed to stop ${task.name}: ${failure.message}")
                } finally {
                    handledTasks.add(task)
                    task.releaseRuntimeState()
                }
            }
        }
        super.end(state, interrupted)
        return actions
    }
}

/**
 * Task group that runs multiple tasks simultaneously in parallel.
 * Finishes as soon as a specific "deadline" task completes, interrupting the rest.
 */
class ParallelDeadlineGroup(
    private val deadline: Task,
    private val otherTasks: List<Task>
) : Task {
    private val tasks = listOf(deadline) + otherTasks
    init {
        TaskResourceValidator.requireNoParallelConflicts("Parallel deadline group", tasks)
    }
    override val name = "ParallelDeadline(deadline=${deadline.name}, others=${otherTasks.joinToString { it.name }})"
    override val requiredResources: Long = TaskResourceValidator.union(tasks)
    private val completedTasks = mutableSetOf<Task>()
    private val pendingActions = mutableListOf<RobotAction>()
    private val actionsList = mutableListOf<RobotAction>()
    private val handledTasks = identityTaskSet()

    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        completedTasks.clear()
        pendingActions.clear()
        handledTasks.clear()
        return tasks.flatMap { it.initialize(state) }
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        for (i in 0 until tasks.size) {
            val task = tasks[i]
            if (!completedTasks.contains(task)) {
                if (handleChildTerminalStatus(this, task, state, handledTasks, pendingActions)) {
                    return false
                }
                if (task.isCompleted(state, elapsedMs)) {
                    completedTasks.add(task)
                    try {
                        pendingActions.addAll(task.end(state, interrupted = false))
                    } finally {
                        handledTasks.add(task)
                        task.releaseRuntimeState()
                    }
                } else if (handleChildTerminalStatus(this, task, state, handledTasks, pendingActions)) {
                    return false
                }
            }
        }
        return completedTasks.contains(deadline)
    }

    override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        super.execute(state, elapsedMs)
        actionsList.clear()
        if (pendingActions.isNotEmpty()) {
            actionsList.addAll(pendingActions)
            pendingActions.clear()
        }
        if (TaskStateMachine.getStatus(this) == TaskStatus.FAILED) return actionsList
        if (completedTasks.contains(deadline)) return actionsList

        for (i in 0 until tasks.size) {
            val task = tasks[i]
            if (!completedTasks.contains(task)) {
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
            if (!handledTasks.contains(task)) {
                try {
                    actions.addAll(task.end(state, interrupted = true))
                } catch (failure: Throwable) {
                    System.err.println("ParallelDeadlineGroup: failed to stop ${task.name}: ${failure.message}")
                } finally {
                    handledTasks.add(task)
                    task.releaseRuntimeState()
                }
            }
        }
        super.end(state, interrupted)
        return actions
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
            if (handledTasks.add(child)) {
                try {
                    TaskCallbacks.invokeFail(child)
                } catch (failure: Throwable) {
                    System.err.println("TaskGroup: Exception in failure callback for ${child.name}: ${failure.message}")
                }
                try {
                    actions.addAll(child.end(state, interrupted = true))
                } catch (failure: Throwable) {
                    System.err.println("TaskGroup: Exception cleaning failed child ${child.name}: ${failure.message}")
                } finally {
                    child.releaseRuntimeState()
                }
            }
            TaskStateMachine.markFailed(parent)
            true
        }
        TaskStatus.CANCELLED -> {
            if (handledTasks.add(child)) {
                try {
                    actions.addAll(child.end(state, interrupted = true))
                } catch (failure: Throwable) {
                    System.err.println("TaskGroup: Exception cleaning cancelled child ${child.name}: ${failure.message}")
                } finally {
                    child.releaseRuntimeState()
                }
            }
            TaskStateMachine.transitionTo(parent, TaskStatus.CANCELLED)
            true
        }
        else -> false
    }
}
