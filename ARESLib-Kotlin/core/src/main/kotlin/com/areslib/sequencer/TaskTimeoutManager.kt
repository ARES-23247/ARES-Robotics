package com.areslib.sequencer

import com.areslib.util.RobotClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledExecutorService

/**
 * Process-wide timeout registry with both executor-driven and watchdog detection.
 *
 * [isTimedOut] compares executor-supplied elapsed time. A single daemon watchdog also checks
 * configured tasks every 50 ms against [RobotClock] and marks them failed once per start. The owning
 * [TaskExecutor] observes that state and invokes failure callbacks on its control-loop thread.
 * Timeout comparison is strict (`elapsed > timeout`). Weak keys and terminal cleanup keep
 * completed tasks from being retained for the life of the process. Active clock rollback or an
 * unrepresentable elapsed interval fails closed. Pausing accounts active time before freezing;
 * it cannot rescue an overdue task. Expiry is latched until [start] or [reset]; retuning or resuming
 * does not clear it. Negative executor elapsed time is rejected when a timeout is configured.
 * Watchdog traversal uses reusable storage and releases temporary strong references after each scan.
 */
object TaskTimeoutManager {
    private data class TimeoutState(
        val timeoutMs: Long,
        val startTimeMs: Long? = null,
        val elapsedBeforePauseMs: Long = 0L,
        val paused: Boolean = false,
        var expired: Boolean = false,
        var watchdogReported: Boolean = false
    )

    private val states = WeakIdentityMap<Task, TimeoutState>()
    private val timedOutScratch = ArrayList<Task>(16)
    private var watchdogNowMs = 0L
    private val watchdogVisitor = object : WeakIdentityMap.EntryVisitor<Task, TimeoutState> {
        override fun visit(key: Task, value: TimeoutState) {
            val start = value.startTimeMs ?: return
            if (!value.watchdogReported && (value.expired || expiredAt(value, start, watchdogNowMs))) {
                value.expired = true
                value.watchdogReported = true
                timedOutScratch.add(key)
            }
        }
    }
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "TaskTimeoutManager-Watchdog").apply { isDaemon = true }
    }

    init {
        executor.scheduleAtFixedRate(
            { runWatchdogCheck() },
            50,
            50,
            TimeUnit.MILLISECONDS
        )
    }

    /** Samples the clock under the registry monitor, after any queued lifecycle changes. */
    @Synchronized
    internal fun runWatchdogCheck() {
        runWatchdogCheck(RobotClock.currentTimeMillis())
    }

    /** Explicit timestamp for deterministic tests; callbacks and cleanup stay on the control loop. */
    @Synchronized
    internal fun runWatchdogCheck(nowMs: Long) {
        timedOutScratch.clear()
        watchdogNowMs = nowMs
        try {
            states.forEachLive(watchdogVisitor)
            var index = 0
            while (index < timedOutScratch.size) {
                TaskStateMachine.markFailed(timedOutScratch[index])
                index++
            }
        } finally {
            // Never retain completed task objects between watchdog checks.
            timedOutScratch.clear()
        }
    }

    /**
     * Sets/replaces the duration without resetting age. Lowering below elapsed time or extending
     * after the old deadline has already expired marks failure; explicit [start] rearms the task.
     */
    @Synchronized
    fun setTimeout(task: Task, ms: Long) {
        require(ms >= 0L) { "Task timeout must be non-negative" }
        val current = states[task]
        val updated = TimeoutState(
            timeoutMs = ms,
            startTimeMs = current?.startTimeMs,
            elapsedBeforePauseMs = current?.elapsedBeforePauseMs ?: 0L,
            paused = current?.paused ?: false,
            expired = current?.expired ?: false,
            watchdogReported = current?.watchdogReported ?: false
        )
        val now = RobotClock.currentTimeMillis()
        if ((current != null && expiredNow(current, now)) || expiredNow(updated, now)) {
            updated.expired = true
            updated.watchdogReported = true
        }
        states[task] = updated
        if (updated.expired) TaskStateMachine.markFailed(task)
    }

    /** Publishes a fresh status and deadline together relative to watchdog scans. */
    @Synchronized
    internal fun initializeTask(task: Task) {
        TaskStateMachine.transitionTo(task, TaskStatus.RUNNING)
        start(task)
    }

    /** Removes an old deadline before publishing cancellation, including cleanup failures. */
    @Synchronized
    internal fun cancelTask(task: Task) {
        states.remove(task)
        TaskStateMachine.transitionTo(task, TaskStatus.CANCELLED)
    }

    /** Removes an old deadline and its observable status together before virtual cleanup. */
    @Synchronized
    internal fun resetTask(task: Task) {
        states.remove(task)
        TaskStateMachine.reset(task)
    }

    /** Records [RobotClock.currentTimeMillis] as [task]'s watchdog origin. */
    @Synchronized
    fun start(task: Task) {
        val current = states[task] ?: return
        states[task] = current.copy(startTimeMs = RobotClock.currentTimeMillis(), elapsedBeforePauseMs = 0L,
            paused = false, expired = false, watchdogReported = false)
    }

    /** Stops watchdog time while [task] is preempted without discarding its timeout configuration. */
    @Synchronized
    fun pause(task: Task) {
        val current = states[task] ?: return
        val start = current.startTimeMs ?: return
        val now = RobotClock.currentTimeMillis()
        val expired = current.expired || expiredAt(current, start, now)
        // If not expired, the sum is at most the configured nonnegative Long timeout.
        // Test the budget first so neither subtraction nor accumulation can silently wrap.
        val accumulated = if (expired) current.elapsedBeforePauseMs
            else current.elapsedBeforePauseMs + (now - start)
        states[task] = current.copy(startTimeMs = null, elapsedBeforePauseMs = accumulated,
            paused = true, expired = expired, watchdogReported = expired)
        if (expired) TaskStateMachine.markFailed(task)
    }

    /** Restarts watchdog time for a previously preempted [task]. */
    @Synchronized
    fun resume(task: Task) {
        val current = states[task] ?: return
        if (!current.paused) return
        states[task] = current.copy(startTimeMs = RobotClock.currentTimeMillis(), paused = false)
        // Executor preemption restores RUNNING before resuming watchdogs. Restore any latched
        // failure immediately; resuming or retuning is not an explicit fresh task start.
        if (current.expired) TaskStateMachine.markFailed(task)
    }
    
    /** Removes both timeout configuration and watchdog start time for [task]. */
    @Synchronized
    fun reset(task: Task) {
        states.remove(task)
    }

    /** Tests and latches caller-supplied elapsed time; callbacks/status remain owned by the caller. */
    @Synchronized
    fun isTimedOut(task: Task, elapsedMs: Long): Boolean {
        val state = states[task] ?: return false
        if (elapsedMs < 0L || elapsedMs > state.timeoutMs) state.expired = true
        return state.expired
    }

    private fun expiredNow(state: TimeoutState, now: Long): Boolean {
        if (state.expired || state.elapsedBeforePauseMs > state.timeoutMs) return true
        val start = state.startTimeMs ?: return false
        return expiredAt(state, start, now)
    }

    private fun expiredAt(state: TimeoutState, start: Long, now: Long): Boolean {
        if (now < start) return true // Clock epoch changed during an active task.
        val delta = now - start
        if (delta < 0L) return true // Positive exact elapsed time exceeds Long.MAX_VALUE.
        return state.elapsedBeforePauseMs > state.timeoutMs ||
            delta > state.timeoutMs - state.elapsedBeforePauseMs
    }
}

/** Pause only running watchdogs; resuming must never start a queued child's clock. */
internal fun Task.setTimeoutSuspended(paused: Boolean) {
    if (paused) TaskTimeoutManager.pause(this) else TaskTimeoutManager.resume(this)
    when (this) {
        is SequentialTaskGroup -> suspendTimeouts(paused)
        is ParallelTaskGroup -> suspendTimeouts(paused)
        is ParallelRaceGroup -> suspendTimeouts(paused)
        is ParallelDeadlineGroup -> suspendTimeouts(paused)
        is FollowPathTask -> suspendTimeouts(paused)
        is PathfindToPoseTask -> suspendTimeouts(paused)
    }
}
