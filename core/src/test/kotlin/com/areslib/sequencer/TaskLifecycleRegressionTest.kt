package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TaskLifecycleRegressionTest {
    @Test
    fun `executor preserves failed status and invokes failure callback once`() {
        var failures = 0
        val task = object : Task {
            override val name = "throws"
            override fun isCompleted(state: RobotState, elapsedMs: Long) = false
            override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
                super.execute(state, elapsedMs)
                error("boom")
            }
        }.onFail { failures++ }
        val executor = TaskExecutor()
        executor.addTask(task)

        executor.update(RobotState(), 100L)
        executor.update(RobotState(), 120L)

        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
        assertEquals(1, failures)
        task.reset()
    }

    @Test
    fun `preemption keeps paused task running and resumes it`() {
        var executions = 0
        var pauses = 0
        var resumes = 0
        var endings = 0
        val longTask = object : Task {
            override val name = "long"
            override fun isCompleted(state: RobotState, elapsedMs: Long) = false
            override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
                super.execute(state, elapsedMs)
                executions++
                return emptyList()
            }
            override fun pause(state: RobotState): List<RobotAction> {
                pauses++
                return emptyList()
            }
            override fun resume(state: RobotState): List<RobotAction> {
                resumes++
                return emptyList()
            }
            override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
                endings++
                return super.end(state, interrupted)
            }
        }
        val executor = TaskExecutor()
        executor.addTask(longTask)
        executor.update(RobotState(), 0L)

        executor.preempt(TimeWaitTask(0L), RobotState(), 10L)
        assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(longTask))
        assertEquals(1, pauses)
        assertEquals(0, endings, "preemption is not a terminal lifecycle transition")

        executor.update(RobotState(), 20L)
        assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(longTask))
        assertEquals(2, executions)
        assertEquals(1, resumes)
        executor.clear(RobotState())
        longTask.reset()
    }

    @Test
    fun `timeouts reject nonsensical negative durations`() {
        assertFailsWith<IllegalArgumentException> { TimeWaitTask(1L).withTimeout(-1L) }
        assertFailsWith<IllegalArgumentException> { TimeWaitTask(-1L) }
    }

    @Test
    fun `task registries use identity when task equality collides`() {
        class EqualTask(private val id: Int) : Task {
            override val name = "equal-$id"
            override fun isCompleted(state: RobotState, elapsedMs: Long) = true
            override fun equals(other: Any?): Boolean = other is EqualTask
            override fun hashCode(): Int = 1
        }

        val first = EqualTask(1)
        val second = EqualTask(2)
        var firstCompletions = 0
        var secondCompletions = 0
        first.onComplete { firstCompletions++ }
        second.onComplete { secondCompletions++ }

        first.initialize(RobotState())
        assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(first))
        assertEquals(TaskStatus.PENDING, TaskStateMachine.getStatus(second))
        first.end(RobotState(), interrupted = false)
        second.initialize(RobotState())
        second.end(RobotState(), interrupted = false)

        assertEquals(1, firstCompletions)
        assertEquals(1, secondCompletions)
        first.reset()
        second.reset()
    }
}
