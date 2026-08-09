package com.areslib.sequencer

import com.areslib.util.RobotClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledExecutorService

/**
 * Object implementation for Task Timeout Manager.
 *
 * Asynchronous superstructure task sequence execution unit.
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

    /**
     * setTimeout declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun setTimeout(task: Task, ms: Long) {
        timeouts[task] = ms
    }

    /**
     * start declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun start(task: Task) {
        startTimes[task] = RobotClock.currentTimeMillis()
    }
    
    /**
     * reset declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun reset(task: Task) {
        timeouts.remove(task)
        startTimes.remove(task)
    }

    /**
     * isTimedOut declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun isTimedOut(task: Task, elapsedMs: Long): Boolean {
        val timeoutMs = timeouts[task] ?: return false
        return elapsedMs > timeoutMs
    }
}
