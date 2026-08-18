package com.areslib.sequencer

import com.areslib.util.RobotClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledExecutorService

/**
 * Process-wide timeout registry with both executor-driven and watchdog detection.
 *
 * [isTimedOut] compares executor-supplied elapsed time. A single daemon watchdog also checks
 * configured tasks every 50 ms against [RobotClock] and marks them failed. The owning
 * [TaskExecutor] observes that state and invokes failure callbacks on its control-loop thread.
 * Timeout comparison is strict (`elapsed > timeout`). Weak keys and terminal cleanup keep
 * completed tasks from being retained for the life of the process.
 */
object TaskTimeoutManager {
    private data class TimeoutState(
        val timeoutMs: Long,
        val startTimeMs: Long? = null,
        val elapsedBeforePauseMs: Long = 0L
    )

    private val states = WeakIdentityMap<Task, TimeoutState>()
    private val timedOutScratch = ArrayList<Task>(16)
    private var watchdogNowMs = 0L
    private val watchdogVisitor = object : WeakIdentityMap.EntryVisitor<Task, TimeoutState> {
        override fun visit(key: Task, value: TimeoutState) {
            val start = value.startTimeMs ?: return
            if (value.elapsedBeforePauseMs + watchdogNowMs - start > value.timeoutMs) {
                timedOutScratch.add(key)
            }
        }
    }
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "TaskTimeoutManager-Watchdog").apply { isDaemon = true }
    }

    init {
        executor.scheduleAtFixedRate(
            { runWatchdogCheck(RobotClock.currentTimeMillis()) },
            50,
            50,
            TimeUnit.MILLISECONDS
        )
    }

    /** Marks expired tasks only; callbacks and cleanup remain owned by the control-loop executor. */
    @Synchronized
    internal fun runWatchdogCheck(nowMs: Long = RobotClock.currentTimeMillis()) {
        timedOutScratch.clear()
        watchdogNowMs = nowMs
        states.forEachLive(watchdogVisitor)
        var index = 0
        while (index < timedOutScratch.size) {
            TaskStateMachine.markFailed(timedOutScratch[index])
            index++
        }
    }

    /** Sets/replaces [task]'s timeout duration in milliseconds. */
    @Synchronized
    fun setTimeout(task: Task, ms: Long) {
        require(ms >= 0L) { "Task timeout must be non-negative" }
        val current = states[task]
        states[task] = TimeoutState(
            timeoutMs = ms,
            startTimeMs = current?.startTimeMs,
            elapsedBeforePauseMs = current?.elapsedBeforePauseMs ?: 0L
        )
    }

    /** Records [RobotClock.currentTimeMillis] as [task]'s watchdog origin. */
    @Synchronized
    fun start(task: Task) {
        val current = states[task] ?: return
        states[task] = current.copy(startTimeMs = RobotClock.currentTimeMillis(), elapsedBeforePauseMs = 0L)
    }

    /** Stops watchdog time while [task] is preempted without discarding its timeout configuration. */
    @Synchronized
    fun pause(task: Task) {
        val current = states[task] ?: return
        val start = current.startTimeMs ?: return
        val elapsed = (RobotClock.currentTimeMillis() - start).coerceAtLeast(0L)
        states[task] = current.copy(
            startTimeMs = null,
            elapsedBeforePauseMs = current.elapsedBeforePauseMs + elapsed
        )
    }

    /** Restarts watchdog time for a previously preempted [task]. */
    @Synchronized
    fun resume(task: Task) {
        val current = states[task] ?: return
        states[task] = current.copy(startTimeMs = RobotClock.currentTimeMillis())
    }
    
    /** Removes both timeout configuration and watchdog start time for [task]. */
    @Synchronized
    fun reset(task: Task) {
        states.remove(task)
    }

    /** Tests caller-supplied [elapsedMs] against [task]'s configured strict timeout. */
    @Synchronized
    fun isTimedOut(task: Task, elapsedMs: Long): Boolean {
        val state = states[task] ?: return false
        return elapsedMs > state.timeoutMs
    }
}
