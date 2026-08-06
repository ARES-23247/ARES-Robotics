package com.areslib.control.assist

/**
 * Interface representing an executable modular robot action or autonomous state machine node.
 *
 * Designed to compose complex multi-subsystem behavior sequences (sequential, parallel, timed, or event-driven)
 * with deterministic startup, periodic updates, finished condition checking, and cleanup/interruption hooks.
 *
 * ### Physical Units & Properties:
 * - Timestep (`dtSeconds`): Seconds ($s$)
 */
interface Action {
    /**
     * Initializes state and triggers immediate startup logic when the action is scheduled.
     */
    fun start() {}

    /**
     * Periodic update loop executed every robot frame cycle while the action is active.
     *
     * @param dtSeconds Elapsed time since the last iteration step in seconds ($s$).
     */
    fun update(dtSeconds: Double)

    /**
     * Checks if the termination criteria for this action have been satisfied.
     *
     * @return `true` if the action has completed successfully; `false` otherwise.
     */
    fun isFinished(): Boolean

    /**
     * Called when the action terminates, either naturally upon finishing or when aborted by an emergency interlock.
     *
     * @param interrupted `true` if terminated early due to preemptive cancellation or safety abort; `false` if completed naturally.
     */
    fun end(interrupted: Boolean) {}
}

/**
 * High-performance Coordinated Action Executor for autonomous and telemetry-assisted state machines.
 *
 * Manages active action lifecycle and enforces safety interlocks dynamically to prevent mechanical damage or unsafe motion.
 *
 * @param safetyCheck Dynamic safety interlock lambda returning `false` if an active hazard is detected.
 *
 * @see Action
 */
class CoordinatedActionExecutor(
    private val safetyCheck: () -> Boolean = { true }
) {
    /** Currently active running action instance, or `null` if idle. */
    var activeAction: Action? = null
        private set

    /** Emergency abort state flag. When `true`, prevents action dispatch until explicitly reset via [resetAbort]. */
    var isEmergencyAborted: Boolean = false
        private set

    /**
     * Schedules a new [action] for execution. Interrupts and terminates the previously active action if one was running.
     *
     * @param action Target [Action] to initialize and execute.
     */
    fun startAction(action: Action) {
        if (isEmergencyAborted) return
        activeAction?.end(true)
        activeAction = action
        action.start()
    }

    /**
     * Periodic update loop. Must be invoked inside the robot's main 50Hz periodic loop.
     *
     * Evaluates dynamic safety interlocks; if a safety check fails, aborts active execution immediately.
     *
     * @param dtSeconds Elapsed cycle loop time in seconds ($s$).
     */
    fun update(dtSeconds: Double) {
        if (isEmergencyAborted) return

        // Dynamic safety interlock check
        if (!safetyCheck()) {
            abort()
            return
        }

        activeAction?.let { action ->
            action.update(dtSeconds)
            if (action.isFinished()) {
                action.end(false)
                activeAction = null
            }
        }
    }

    /**
     * Aborts the currently active action with `interrupted = true` and latches [isEmergencyAborted].
     */
    fun abort() {
        activeAction?.end(true)
        activeAction = null
        isEmergencyAborted = true
    }

    /**
     * Clears the emergency abort flag [isEmergencyAborted], restoring normal action scheduling capabilities.
     */
    fun resetAbort() {
        isEmergencyAborted = false
    }

    /**
     * Checks if the executor is currently running an active action sequence.
     *
     * @return `true` if [activeAction] is non-null; `false` if idle.
     */
    fun isRunning(): Boolean = activeAction != null
}

/**
 * Composite composite action that executes a sequence of sub-actions in strict linear series order.
 *
 * @param actions Variadic array of child [Action] components executed sequentially.
 */
class SequentialAction(private vararg val actions: Action) : Action {
    private var currentIndex = 0

    /**
     * Initializes the sequence and starts the first action if present.
     */
    override fun start() {
        currentIndex = 0
        if (actions.isNotEmpty()) {
            actions[0].start()
        }
    }

    /**
     * Updates the currently active child action in the sequence and transitions when completed.
     *
     * @param dtSeconds Timestep in seconds ($s$).
     */
    override fun update(dtSeconds: Double) {
        if (currentIndex >= actions.size) return

        val currentAction = actions[currentIndex]
        currentAction.update(dtSeconds)

        val finished = currentAction.isFinished()
        when {
            finished -> {
                currentAction.end(false)
                currentIndex++
                when {
                    currentIndex < actions.size -> actions[currentIndex].start()
                }
            }
        }
    }

    /**
     * Checks if all child actions in the sequence have completed.
     *
     * @return `true` when [currentIndex] reaches the end of the array; `false` otherwise.
     */
    override fun isFinished(): Boolean {
        return currentIndex >= actions.size
    }

    /**
     * Cleans up the active child action if the composite sequence is interrupted.
     *
     * @param interrupted `true` if the sequence was aborted prior to natural completion.
     */
    override fun end(interrupted: Boolean) {
        if (interrupted && currentIndex < actions.size) {
            actions[currentIndex].end(true)
        }
    }
}

/**
 * Composite action that executes multiple sub-actions in parallel concurrently.
 * Completes only when ALL child actions have finished.
 *
 * @param actions Variadic array of child [Action] components executed in parallel.
 */
class ParallelAction(private vararg val actions: Action) : Action {
    private val activeActions = mutableListOf<Action>()

    /**
     * Starts all child actions concurrently.
     */
    override fun start() {
        activeActions.clear()
        activeActions.addAll(actions)
        for (action in activeActions) {
            action.start()
        }
    }

    /**
     * Updates all active parallel child actions and cleans up finished sub-actions.
     *
     * @param dtSeconds Timestep in seconds ($s$).
     */
    override fun update(dtSeconds: Double) {
        var i = 0
        while (i < activeActions.size) {
            val action = activeActions[i]
            action.update(dtSeconds)
            if (action.isFinished()) {
                action.end(false)
                activeActions.removeAt(i)
            } else {
                i++
            }
        }
    }

    /**
     * Checks if all parallel child actions have finished.
     *
     * @return `true` when [activeActions] list becomes empty; `false` otherwise.
     */
    override fun isFinished(): Boolean {
        return activeActions.isEmpty()
    }

    /**
     * Cleans up all currently running parallel child actions if interrupted.
     *
     * @param interrupted `true` if the parallel group was cancelled early.
     */
    override fun end(interrupted: Boolean) {
        if (interrupted) {
            for (action in activeActions) {
                action.end(true)
            }
        }
    }
}

/**
 * Utility action that pauses execution for a specified duration before finishing.
 *
 * @param durationSeconds Total delay time in seconds ($s$).
 */
class WaitAction(private val durationSeconds: Double) : Action {
    private var elapsedSeconds = 0.0

    /**
     * Resets internal timer accumulator to 0.0 seconds.
     */
    override fun start() {
        elapsedSeconds = 0.0
    }

    /**
     * Accumulates elapsed time $\Delta t$.
     *
     * @param dtSeconds Timestep in seconds ($s$).
     */
    override fun update(dtSeconds: Double) {
        elapsedSeconds += dtSeconds
    }

    /**
     * Checks if the accumulated time has reached [durationSeconds].
     *
     * @return `true` when $t_{elapsed} \ge t_{duration}$; `false` otherwise.
     */
    override fun isFinished(): Boolean {
        return elapsedSeconds >= durationSeconds
    }
}

/**
 * Action wrapper that executes a single synchronous block/lambda instantly and completes immediately.
 *
 * @param runnable Closure function to execute upon startup.
 */
class InstantAction(private val runnable: () -> Unit) : Action {
    /**
     * No-op update for instant action execution.
     *
     * @param dtSeconds Timestep in seconds ($s$).
     */
    override fun update(dtSeconds: Double) {}

    /**
     * Instantly returns `true` since instant actions finish on frame 0.
     *
     * @return Always `true`.
     */
    override fun isFinished(): Boolean = true

    /**
     * Executes the wrapped [runnable] lambda block.
     */
    override fun start() {
        runnable()
    }
}
