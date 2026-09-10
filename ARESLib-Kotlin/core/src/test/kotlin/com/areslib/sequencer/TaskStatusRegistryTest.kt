package com.areslib.sequencer

import com.areslib.state.RobotState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test
import kotlin.test.*

class TaskStatusRegistryTest {
    private data class EqualTask(var key: Int) : Task {
        override val name = "equal-task"
        override fun isCompleted(state: RobotState, elapsedMs: Long) = false
    }

    @Test fun `unknown status is pending and explicit transitions remain unrestricted`() {
        val task = EqualTask(1)
        try {
            assertEquals(TaskStatus.PENDING, TaskStateMachine.getStatus(task))
            for (status in TaskStatus.entries) {
                TaskStateMachine.transitionTo(task, status)
                assertEquals(status, TaskStateMachine.getStatus(task))
            }
        } finally { task.reset() }
        assertEquals(TaskStatus.PENDING, TaskStateMachine.getStatus(task))
    }

    @Test fun `equal instances and mutable hash codes keep independent identity state`() {
        val first = EqualTask(1)
        val second = EqualTask(1)
        assertEquals(first, second)
        try {
            TaskStateMachine.transitionTo(first, TaskStatus.RUNNING)
            TaskStateMachine.transitionTo(second, TaskStatus.COMPLETED)
            first.key = 2
            assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(first))
            assertEquals(TaskStatus.COMPLETED, TaskStateMachine.getStatus(second))
            first.reset()
            assertEquals(TaskStatus.PENDING, TaskStateMachine.getStatus(first))
            assertEquals(TaskStatus.COMPLETED, TaskStateMachine.getStatus(second))
        } finally { first.reset(); second.reset() }
    }

    @Test fun `concurrent failure calls have one winner until an explicit transition`() {
        val task = EqualTask(2)
        val start = CountDownLatch(1)
        val winners = AtomicInteger()
        val workers = List(8) {
            Thread({
                start.await()
                repeat(100) { if (TaskStateMachine.markFailed(task)) winners.incrementAndGet() }
            }, "status-failure-probe-$it")
        }
        try {
            workers.forEach { it.start() }
            start.countDown()
            workers.forEach { it.join(2000L) }
            assertTrue(workers.none { it.isAlive })
            assertEquals(1, winners.get())
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
            TaskStateMachine.transitionTo(task, TaskStatus.RUNNING)
            assertTrue(TaskStateMachine.markFailed(task))
            assertFalse(TaskStateMachine.markFailed(task))
        } finally {
            start.countDown()
            workers.forEach { it.join(2000L) }
            task.reset()
        }
    }
}
