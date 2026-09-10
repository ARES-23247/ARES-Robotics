package com.areslib.sequencer

import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import java.math.BigInteger
import java.util.Random
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class TaskTimeoutBoundaryTest {
    private val task = object : Task {
        override val name = "timeout-boundary"
        override fun isCompleted(state: RobotState, elapsedMs: Long) = false
    }
    @AfterEach fun cleanup() { task.reset(); RobotClock.useSystemTime() }
    private fun start(at: Long, timeout: Long) {
        task.reset()
        RobotClock.useMockTime(at)
        task.withTimeout(timeout)
        task.initialize(RobotState())
    }
    @Test fun `watchdog compares elapsed intervals larger than Long MAX without wrap`() {
        start(Long.MIN_VALUE, Long.MAX_VALUE)
        TaskTimeoutManager.runWatchdogCheck(0L)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
    }
    @Test fun `accumulated active intervals across pauses cannot wrap under the timeout`() {
        start(Long.MIN_VALUE, Long.MAX_VALUE)
        RobotClock.useMockTime(-1L)
        TaskTimeoutManager.pause(task)
        assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(task))
        RobotClock.useMockTime(0L)
        TaskTimeoutManager.resume(task)
        TaskTimeoutManager.runWatchdogCheck(1L)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
    }
    @Test fun `pause cannot rescue an already expired task`() {
        start(100L, 10L)
        RobotClock.useMockTime(111L)
        TaskTimeoutManager.pause(task)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
    }
    @Test fun `negative executor elapsed time is rejected when a timeout is configured`() {
        start(0L, 100L)
        assertTrue(TaskTimeoutManager.isTimedOut(task, -1L))
    }
    @Test fun `rollback of an active clock fails instead of extending the deadline`() {
        start(100L, 10L)
        TaskTimeoutManager.runWatchdogCheck(99L)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
    }
    @Test fun `strict deadline equality survives pause and duplicate pause resume calls`() {
        start(0L, 100L)
        RobotClock.useMockTime(40L)
        TaskTimeoutManager.pause(task)
        RobotClock.useMockTime(1000L)
        TaskTimeoutManager.pause(task)
        TaskTimeoutManager.runWatchdogCheck(1000L)
        assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(task))
        TaskTimeoutManager.resume(task)
        TaskTimeoutManager.resume(task)
        TaskTimeoutManager.runWatchdogCheck(1060L)
        assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(task))
        TaskTimeoutManager.runWatchdogCheck(1061L)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
    }
    @Test fun `expiry remains latched for executor checks until explicit restart`() {
        start(0L, 0L)
        TaskTimeoutManager.runWatchdogCheck(1L)
        assertTrue(TaskTimeoutManager.isTimedOut(task, 0L))
        TaskTimeoutManager.setTimeout(task, 100L)
        assertTrue(TaskTimeoutManager.isTimedOut(task, 0L))
        task.initialize(RobotState())
        assertFalse(TaskTimeoutManager.isTimedOut(task, 0L))
        assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(task))
    }
    @Test fun `exact integer oracle covers signed timestamp boundaries and strict deadlines`() {
        val random = Random(6001)
        repeat(1000) {
            val origin = random.nextLong()
            val now = random.nextLong()
            val timeout = random.nextLong().ushr(1)
            start(origin, timeout)
            TaskTimeoutManager.runWatchdogCheck(now)
            val elapsed = BigInteger.valueOf(now).subtract(BigInteger.valueOf(origin))
            val invalidOrExpired = now < origin || elapsed > BigInteger.valueOf(timeout)
            assertEquals(invalidOrExpired, TaskStateMachine.getStatus(task) == TaskStatus.FAILED,
                "origin=$origin now=$now timeout=$timeout exactElapsed=$elapsed")
        }
    }

    @Test fun `deadline extension cannot rescue a task already overdue before retuning`() {
        start(0L, 100L)
        RobotClock.useMockTime(101L)
        TaskTimeoutManager.setTimeout(task, 200L)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
        assertTrue(TaskTimeoutManager.isTimedOut(task, 0L))
    }
    @Test fun `retuning a paused budget below accumulated time fails immediately`() {
        start(0L, 100L)
        RobotClock.useMockTime(50L)
        TaskTimeoutManager.pause(task)
        TaskTimeoutManager.setTimeout(task, 49L)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
    }
    @Test fun `extension before expiry preserves original age and queued tasks stay unstarted`() {
        start(0L, 100L)
        RobotClock.useMockTime(50L)
        TaskTimeoutManager.setTimeout(task, 200L)
        TaskTimeoutManager.runWatchdogCheck(200L)
        assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(task))
        TaskTimeoutManager.runWatchdogCheck(201L)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
        task.reset()
        task.withTimeout(0L)
        TaskTimeoutManager.resume(task)
        TaskTimeoutManager.runWatchdogCheck(Long.MAX_VALUE)
        assertEquals(TaskStatus.PENDING, TaskStateMachine.getStatus(task))
    }
    @Test fun `executor timeout check still allows watchdog to publish the latched failure`() {
        start(0L, 100L)
        assertTrue(TaskTimeoutManager.isTimedOut(task, 101L))
        TaskTimeoutManager.runWatchdogCheck(0L)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
        TaskStateMachine.transitionTo(task, TaskStatus.CANCELLED)
        TaskTimeoutManager.runWatchdogCheck(500L)
        assertEquals(TaskStatus.CANCELLED, TaskStateMachine.getStatus(task), "One expiry must not repeatedly rewrite status")
    }
    @Test fun `resume restores a latched failure after executor restores running status`() {
        start(0L, 0L)
        RobotClock.useMockTime(1L)
        TaskTimeoutManager.pause(task)
        TaskStateMachine.transitionTo(task, TaskStatus.RUNNING)
        TaskTimeoutManager.resume(task)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
    }
    @Test fun `rollback during pause fails and negative replacement leaves timeout intact`() {
        start(100L, 10L)
        assertFailsWith<IllegalArgumentException> { TaskTimeoutManager.setTimeout(task, -1L) }
        RobotClock.useMockTime(99L)
        TaskTimeoutManager.pause(task)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
        task.reset()
        assertFalse(TaskTimeoutManager.isTimedOut(task, Long.MAX_VALUE))
    }

    @Test fun `watchdog reads the clock after acquiring the registry monitor`() {
        start(100L, 100L)
        val worker = Thread({ TaskTimeoutManager.runWatchdogCheck() }, "timeout-monitor-probe")
        try {
            synchronized(TaskTimeoutManager) {
                worker.start()
                val deadline = System.nanoTime() + 2_000_000_000L
                while (worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals(Thread.State.BLOCKED, worker.state)
                RobotClock.useMockTime(200L)
                task.initialize(RobotState())
            }
        } finally {
            worker.join(2000L)
        }
        assertFalse(worker.isAlive)
        assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(task),
            "A timestamp captured before the monitor wait must not appear to be clock rollback")
    }
}
