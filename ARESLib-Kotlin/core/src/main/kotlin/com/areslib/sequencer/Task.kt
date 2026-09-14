package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.state.RobotState

/**
 * Synchronous lifecycle unit executed by [TaskExecutor].
 *
 * Lifecycle order is [initialize], repeated [isCompleted]/[execute], then [end]. Methods return
 * Redux actions for the caller to dispatch; tasks do not mutate the store directly. Overrides of
 * [initialize], [execute], and [end] must call their default implementation to preserve status,
 * timeout, and callback behavior.
 *
 * Statuses, timeouts, and callbacks live in process-wide registries. Executors call
 * [releaseRuntimeState] after true terminal transitions; [reset] additionally removes observable
 * status before an instance is configured for reuse.
 */
interface Task {
    val name: String
    val priority: Int get() = 0
    /** Primitive ownership mask, fixed for this instance and checked when task trees are built. */
    val requiredResources: Long get() = TaskResources.NONE

    /**
     * Marks the task running and records its timeout origin atomically with respect to watchdog
     * scans. Returns no actions by default. Overrides must call this before publishing work.
     */
    fun initialize(state: RobotState): List<RobotAction> {
        TaskTimeoutManager.initializeTask(this)
        return emptyList()
    }

    /**
     * Returns whether the task's domain-specific completion condition is satisfied.
     * [elapsedMs] is supplied by the executor and uses its timestamp domain.
     */
    fun isCompleted(state: RobotState, elapsedMs: Long): Boolean

    /**
     * Runs once per active update. The default detects elapsed-time timeout, marks failure, and
     * invokes the failure callback; overrides must call it before producing their own actions.
     */
    fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        if (TaskTimeoutManager.isTimedOut(this, elapsedMs)) {
            if (TaskStateMachine.markFailed(this)) {
                TaskCallbacks.invokeFail(this)
            }
        }
        return emptyList()
    }

    /**
     * Pauses this task for higher-priority work without performing terminal cleanup.
     * Override to stop transient outputs that must not remain active while preempted.
     */
    fun pause(state: RobotState): List<RobotAction> = emptyList()

    /** Restores transient outputs after a preemption; elapsed time resumes where it paused. */
    fun resume(state: RobotState): List<RobotAction> = emptyList()

    /**
     * Finalizes the task. Interrupted endings become cancelled unless the task already failed;
     * normal endings complete only if no failure, cancellation or expired deadline has won.
     * The deadline is removed with the status transition before any callback runs. Callbacks run
     * outside the watchdog monitor; owners still perform virtual cleanup and inspect final status.
     */
    fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        val status = TaskTimeoutManager.finishTask(this, interrupted)
        if (!interrupted) {
            when (status) {
                TaskStatus.COMPLETED -> TaskCallbacks.invokeComplete(this)
                TaskStatus.FAILED -> TaskCallbacks.invokeFail(this)
                else -> Unit
            }
        }
        return emptyList()
    }

    /**
     * Atomically removes the deadline and marks cancellation, then releases runtime metadata.
     * Does not invoke [end] or dispatch hardware cleanup actions. An overriding metadata cleanup
     * failure cannot leave this task's old watchdog deadline armed.
     */
    fun cancel() {
        TaskTimeoutManager.cancelTask(this)
        releaseRuntimeState()
    }

    /** Releases timeout and callback state while retaining the terminal status for diagnostics. */
    fun releaseRuntimeState() {
        TaskTimeoutManager.reset(this)
        TaskCallbacks.reset(this)
    }

    /**
     * Atomically removes this task's status and deadline, then invokes virtual metadata cleanup
     * (including callback removal). A cleanup exception propagates without rearming the old deadline.
     */
    fun reset() {
        TaskTimeoutManager.resetTask(this)
        releaseRuntimeState()
    }

    /** Registers/replaces a synchronous completion callback and returns this task for chaining. */
    fun onComplete(callback: () -> Unit): Task {
        TaskCallbacks.registerComplete(this, callback)
        return this
    }

    /** Registers/replaces a synchronous timeout/failure callback and returns this task for chaining. */
    fun onFail(callback: () -> Unit): Task {
        TaskCallbacks.registerFail(this, callback)
        return this
    }

    /** Associates a strict `elapsedMs > ms` timeout and returns this task for chaining. */
    fun withTimeout(ms: Long): Task {
        TaskTimeoutManager.setTimeout(this, ms)
        return this
    }
}

/**
 * Task to wait for a specific duration of time.
 */
class TimeWaitTask(
    private val durationMs: Long
) : Task {
    init {
        require(durationMs >= 0L) { "Wait duration must be non-negative" }
    }

    override val name = "TimeWait($durationMs ms)"

    /** Completes when executor elapsed time reaches [durationMs]. */
    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        return elapsedMs >= durationMs
    }
}

/**
 * Task to wait until a specified condition is true.
 */
class WaitUntilTask(
    private val predicate: (RobotState) -> Boolean
) : Task {
    override val name = "WaitUntil"

    /** Evaluates [predicate] against the most recent immutable robot-state snapshot. */
    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        return predicate(state)
    }
}

/**
 * Task to block execution until path progress reaches a certain distance.
 */
class PathProgressWaitTask(
    private val targetDistanceMeters: Double,
    private val timeoutMs: Long = 10000L
) : Task {
    init {
        require(targetDistanceMeters.isFinite() && targetDistanceMeters >= 0.0) {
            "Target path distance must be finite and non-negative"
        }
        require(timeoutMs >= 0L) { "Fallback timeout must be non-negative" }
    }

    override val name = "PathProgressWait($targetDistanceMeters m)"

    /** Completes at the target path distance or after the built-in fallback timeout. */
    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        return state.pathState.currentDistanceMeters >= targetDistanceMeters || elapsedMs >= timeoutMs
    }
}

/**
 * Task to instantly dispatch a single Redux action and finish.
 */
class ActionDispatchTask @kotlin.jvm.JvmOverloads constructor(
    private val action: RobotAction,
    override val requiredResources: Long = TaskResources.NONE
) : Task {
    override val name = "ActionDispatch(${action::class.simpleName})"

    private var dispatched = false

    /** Marks the task running and returns [action] exactly once for this initialization. */
    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        dispatched = true
        return listOf(action)
    }

    /** Completes immediately after [initialize] has produced the action. */
    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        return dispatched
    }
}

/**
 * Creates one Redux action from the state observed when the task starts.
 *
 * This is useful for season actions that must preserve unrelated immutable subsystem fields. The
 * factory runs once during initialization, so auto-registration never captures a stale state.
 */
class StateActionTask(
    override val name: String,
    override val requiredResources: Long = TaskResources.NONE,
    private val actionFactory: (RobotState) -> RobotAction
) : Task {
    private var dispatched = false

    init {
        require(name.isNotBlank()) { "State action task name must not be blank" }
    }

    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        dispatched = true
        return listOf(actionFactory(state))
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean = dispatched
}
