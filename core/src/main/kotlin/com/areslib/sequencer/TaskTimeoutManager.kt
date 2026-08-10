package com.areslib.sequencer

import com.areslib.util.RobotClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledExecutorService

/**
 * Process-wide timeout registry with both executor-driven and watchdog detection.
 *
 * [isTimedOut] compares executor-supplied elapsed time. A single daemon watchdog also checks
 * configured tasks every 50 ms against [RobotClock] and marks them failed. Timeout comparison is
 * strict (`elapsed > timeout`). [reset] is required to release task keys and their start times.
 */
object TaskTimeoutManager {
    private val timeouts = ConcurrentHashMap<Task, Long>()
    private val startTimes = ConcurrentHashMap<Task, Long>()
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "TaskTimeoutManager-Watchdog").apply { isDaemon = true }
    }

    init {
        executor.scheduleAtFixedRate({
            val now = RobotClock.currentTimeMillis()
            for ((task, timeout) in timeouts) {
                val start = startTimes[task] ?: continue
                if (now - start > timeout) {
                    TaskStateMachine.transitionTo(task, TaskStatus.FAILED)
                }
            }
        }, 50, 50, TimeUnit.MILLISECONDS)
    }

    /** Sets/replaces [task]'s timeout duration in milliseconds. */
    fun setTimeout(task: Task, ms: Long) {
        timeouts[task] = ms
    }

    /** Records [RobotClock.currentTimeMillis] as [task]'s watchdog origin. */
    fun start(task: Task) {
        startTimes[task] = RobotClock.currentTimeMillis()
    }
    
    /** Removes both timeout configuration and watchdog start time for [task]. */
    fun reset(task: Task) {
        timeouts.remove(task)
        startTimes.remove(task)
    }

    /** Tests caller-supplied [elapsedMs] against [task]'s configured strict timeout. */
    fun isTimedOut(task: Task, elapsedMs: Long): Boolean {
        val timeoutMs = timeouts[task] ?: return false
        return elapsedMs > timeoutMs
    }
}
