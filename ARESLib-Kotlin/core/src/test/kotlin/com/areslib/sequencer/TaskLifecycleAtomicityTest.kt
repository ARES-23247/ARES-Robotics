package com.areslib.sequencer

import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class TaskLifecycleAtomicityTest {
    private val task = object : Task {
        override val name = "atomic-lifecycle"
        override fun isCompleted(state: RobotState, elapsedMs: Long) = false
    }
    @AfterEach fun cleanup() { task.reset(); RobotClock.useSystemTime() }

    private fun expireOldDeadlineWhileOperationWaits(operation: () -> Unit) {
        RobotClock.useMockTime(0L)
        task.withTimeout(10L)
        task.initialize(RobotState())
        val error = AtomicReference<Throwable?>()
        val worker = Thread({ try { operation() } catch (failure: Throwable) { error.set(failure) } }, "task-lifecycle-probe")
        try {
            synchronized(TaskTimeoutManager) {
                RobotClock.useMockTime(20L)
                worker.start()
                val deadline = System.nanoTime() + 2_000_000_000L
                while (worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals(Thread.State.BLOCKED, worker.state)
                TaskTimeoutManager.runWatchdogCheck(20L)
            }
        } finally {
            worker.join(2000L)
        }
        assertFalse(worker.isAlive)
        assertNull(error.get())
    }

    @Test fun `old watchdog failure cannot overwrite a queued fresh initialization`() {
        expireOldDeadlineWhileOperationWaits { task.initialize(RobotState()) }
        assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(task))
        assertFalse(TaskTimeoutManager.isTimedOut(task, 0L))
        TaskTimeoutManager.runWatchdogCheck(30L)
        assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(task))
        TaskTimeoutManager.runWatchdogCheck(31L)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
    }
    @Test fun `old watchdog failure cannot resurrect status after reset`() {
        expireOldDeadlineWhileOperationWaits { task.reset() }
        assertEquals(TaskStatus.PENDING, TaskStateMachine.getStatus(task))
        assertFalse(TaskTimeoutManager.isTimedOut(task, Long.MAX_VALUE))
        TaskTimeoutManager.runWatchdogCheck(Long.MAX_VALUE)
        assertEquals(TaskStatus.PENDING, TaskStateMachine.getStatus(task))
    }
    @Test fun `old watchdog failure cannot overwrite cancellation`() {
        expireOldDeadlineWhileOperationWaits { task.cancel() }
        assertEquals(TaskStatus.CANCELLED, TaskStateMachine.getStatus(task))
        assertFalse(TaskTimeoutManager.isTimedOut(task, Long.MAX_VALUE))
        TaskTimeoutManager.runWatchdogCheck(Long.MAX_VALUE)
        assertEquals(TaskStatus.CANCELLED, TaskStateMachine.getStatus(task))
    }

    @Test fun `initialization without a configured timer still publishes running state`() {
        task.initialize(RobotState())
        assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(task))
        assertFalse(TaskTimeoutManager.isTimedOut(task, Long.MAX_VALUE))
        task.reset()
        assertEquals(TaskStatus.PENDING, TaskStateMachine.getStatus(task))
    }

    @Test fun `cancel and reset preserve virtual cleanup without invoking end`() {
        var cleanups = 0
        var ends = 0
        val probe = object : Task {
            override val name = "cleanup-dispatch"
            override fun isCompleted(state: RobotState, elapsedMs: Long) = false
            override fun releaseRuntimeState() { cleanups++; super.releaseRuntimeState() }
            override fun end(state: RobotState, interrupted: Boolean): List<com.areslib.action.RobotAction> {
                ends++; return super.end(state, interrupted)
            }
        }
        probe.cancel()
        assertEquals(TaskStatus.CANCELLED, TaskStateMachine.getStatus(probe))
        probe.reset()
        assertEquals(TaskStatus.PENDING, TaskStateMachine.getStatus(probe))
        assertEquals(2, cleanups)
        assertEquals(0, ends)
    }

    @Test fun `failing virtual cleanup cannot retain an old deadline after cancel or reset`() {
        val failure = IllegalStateException("metadata cleanup")
        val probe = object : Task {
            override val name = "failed-cleanup"
            override fun isCompleted(state: RobotState, elapsedMs: Long) = false
            override fun releaseRuntimeState() { throw failure }
        }
        try {
            for (cancel in listOf(true, false)) {
                RobotClock.useMockTime(0L)
                probe.withTimeout(10L)
                probe.initialize(RobotState())
                assertSame(failure, assertFailsWith<IllegalStateException> {
                    if (cancel) probe.cancel() else probe.reset()
                })
                TaskTimeoutManager.runWatchdogCheck(20L)
                assertEquals(if (cancel) TaskStatus.CANCELLED else TaskStatus.PENDING, TaskStateMachine.getStatus(probe))
                assertFalse(TaskTimeoutManager.isTimedOut(probe, Long.MAX_VALUE))
            }
        } finally {
            TaskTimeoutManager.reset(probe)
            TaskStateMachine.reset(probe)
            TaskCallbacks.reset(probe)
        }
    }
}
