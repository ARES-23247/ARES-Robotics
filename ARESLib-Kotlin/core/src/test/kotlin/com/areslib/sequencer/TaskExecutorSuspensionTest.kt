package com.areslib.sequencer

import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Suspension excludes wall-clock time from task elapsed durations: waits measure execution
 * time, not suspension time, and a task started by [TaskExecutor.preempt] mid-suspension is
 * charged only the suspension interval after its own start.
 */
class TaskExecutorSuspensionTest {
    @Test
    fun `watchdog freezes nested groups and starts queued children only when initialized`() {
        val state = RobotState()
        val executor = TaskExecutor()
        RobotClock.useMockTime(0L)
        try {
            val child = TimeWaitTask(100L).withTimeout(150L)
            val queued = TimeWaitTask(500L).withTimeout(150L)
            val root = ParallelTaskGroup(listOf(SequentialTaskGroup(listOf(child, queued))))
            executor.addTask(root)
            executor.update(state, 0)
            RobotClock.useMockTime(50)
            executor.suspend()
            RobotClock.useMockTime(5000)
            TaskTimeoutManager.runWatchdogCheck()
            assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(child))
            executor.resume()
            RobotClock.useMockTime(5050)
            executor.update(state, 5050)
            assertEquals(TaskStatus.COMPLETED, TaskStateMachine.getStatus(child))
            assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(queued))
            RobotClock.useMockTime(5201)
            TaskTimeoutManager.runWatchdogCheck()
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(queued))
        } finally {
            executor.cancelAll(state)
            RobotClock.useSystemTime()
        }
    }

    @Test
    fun `suspended preemption preserves original budget and pauses preemptor watchdog`() {
        val state = RobotState()
        val executor = TaskExecutor()
        RobotClock.useMockTime(0)
        try {
            val original = TimeWaitTask(1000).withTimeout(1500)
            executor.addTask(original)
            executor.update(state, 0)
            RobotClock.useMockTime(100)
            executor.suspend()
            RobotClock.useMockTime(5000)
            val preemptor = TimeWaitTask(100).withTimeout(200)
            executor.preempt(preemptor, state, 5000)
            RobotClock.useMockTime(10000)
            TaskTimeoutManager.runWatchdogCheck()
            assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(original))
            assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(preemptor))
            executor.resume()
            RobotClock.useMockTime(10100)
            executor.update(state, 10100)
            assertEquals(original.name, executor.activeTaskName)
            RobotClock.useMockTime(10999)
            executor.update(state, 10999)
            assertEquals(original.name, executor.activeTaskName)
        } finally {
            executor.cancelAll(state)
            RobotClock.useSystemTime()
        }
    }

    @Test
    fun `suspended wall time does not count toward task duration`() {
        com.areslib.util.RobotClock.useMockTime(0L)
        try {
            val executor = TaskExecutor()
            executor.addTask(TimeWaitTask(1_000L))
            executor.update(RobotState(), 0L) // initializes; elapsed 0

            executor.suspend() // at t=100
            RobotClock.useMockTime(100L)
            RobotClock.useMockTime(5_000L) // 4.9 s of suspended wall time
            executor.resume()

            // Only 100 ms of charged elapsed time has accrued (0 -> start of suspension).
            RobotClock.useMockTime(5_100L)
            executor.update(RobotState(), 5_100L)
            assertEquals("TimeWait(1000 ms)", executor.activeTaskName)

            RobotClock.useMockTime(6_000L)
            executor.update(RobotState(), 6_000L)
            assertEquals(null, executor.activeTaskName, "the wait must complete after ~1 s of execution time")
        } finally {
            RobotClock.useSystemTime()
        }
    }

    @Test
    fun `preempting while suspended charges only post-preempt suspension to the new task`() {
        RobotClock.useMockTime(0L)
        try {
            val executor = TaskExecutor()
            executor.addTask(TimeWaitTask(10_000L))
            executor.update(RobotState(), 0L)

            RobotClock.useMockTime(1_000L)
            executor.suspend()
            RobotClock.useMockTime(9_000L) // 8 s suspended before the preempt
            val preemptor = TimeWaitTask(1_000L)
            executor.preempt(preemptor, RobotState(), 9_000L)

            RobotClock.useMockTime(9_500L) // 0.5 s more suspended after the preempt
            executor.resume()

            RobotClock.useMockTime(10_400L) // 0.9 s of charged execution for the preemptor
            executor.update(RobotState(), 10_400L)
            assertEquals("TimeWait(1000 ms)", executor.activeTaskName, "the preemptor must not be over-credited")

            RobotClock.useMockTime(10_500L)
            executor.update(RobotState(), 10_500L)
            // The preempted original correctly resumes after the preemptor completes.
            assertEquals("TimeWait(10000 ms)", executor.activeTaskName)
            executor.cancelAll(RobotState())
            preemptor.reset()
        } finally {
            RobotClock.useSystemTime()
        }
    }

    @Test
    fun `suspend suppresses updates and resume restarts them`() {
        val executor = TaskExecutor()
        executor.addTask(TimeWaitTask(0L))
        executor.suspend()
        // Suspension suppresses the update entirely: the task is never initialized.
        RobotClock.useMockTime(1L)
        try {
            executor.update(RobotState(), 1L)
            assertEquals(null, executor.activeTaskName)
            assertEquals(1, executor.size, "the queued task is retained, not dropped")
            executor.resume()
            executor.update(RobotState(), 1L)
            assertEquals(null, executor.activeTaskName, "resumed update initializes and completes the wait")
            assertEquals(0, executor.size)
        } finally {
            RobotClock.useSystemTime()
        }
    }
}
