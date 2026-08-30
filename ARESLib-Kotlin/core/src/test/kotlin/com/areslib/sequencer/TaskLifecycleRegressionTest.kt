package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskLifecycleRegressionTest {
    @Test
    fun `watchdog child failure callback and cleanup run exactly once in every task group`() {
        class WatchdogChild : Task {
            override val name = "watchdog-child"
            var endCalls = 0
            override fun isCompleted(state: RobotState, elapsedMs: Long) = false
            override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
                endCalls++
                return super.end(state, interrupted)
            }
        }

        val groupFactories = listOf<Pair<String, (Task) -> Task>>(
            "sequential" to { child -> SequentialTaskGroup(listOf(child)) },
            "parallel" to { child -> ParallelTaskGroup(listOf(child)) },
            "race" to { child -> ParallelRaceGroup(listOf(child)) },
            "deadline" to { child -> ParallelDeadlineGroup(child, emptyList()) }
        )
        val controlThread = Thread.currentThread()
        com.areslib.util.RobotClock.useMockTime(0L)
        try {
            for ((label, factory) in groupFactories) {
                var failureCalls = 0
                var callbackThread: Thread? = null
                val child = WatchdogChild()
                child.withTimeout(0L).onFail {
                    failureCalls++
                    callbackThread = Thread.currentThread()
                }
                val group = factory(child)
                val executor = TaskExecutor()
                executor.addTask(group)
                executor.update(RobotState(), 0L)

                TaskTimeoutManager.runWatchdogCheck(1L)
                executor.update(RobotState(), 1L)

                assertEquals(1, failureCalls, "$label child failure callback")
                assertEquals(1, child.endCalls, "$label child cleanup")
                assertEquals(controlThread, callbackThread, "$label callback thread")
                assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(child), "$label child status")
                assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(group), "$label group status")
                assertEquals(0, executor.size, "$label executor drained")
                child.reset()
                group.reset()
            }
        } finally {
            com.areslib.util.RobotClock.useSystemTime()
        }
    }

    @Test
    fun `race rejects an empty child set`() {
        assertFailsWith<IllegalArgumentException> { ParallelRaceGroup(emptyList()) }
    }

    @Test
    fun `race and deadline cleanup every unfinished child when one end throws`() {
        class CleanupTask(
            override val name: String,
            private val throwOnEnd: Boolean = false
        ) : Task {
            var endCalls = 0
            override fun isCompleted(state: RobotState, elapsedMs: Long) = false
            override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
                endCalls++
                if (throwOnEnd) error("cleanup failed")
                return super.end(state, interrupted)
            }
        }

        val raceFirst = CleanupTask("race-first", throwOnEnd = true)
        val raceSecond = CleanupTask("race-second")
        val race = ParallelRaceGroup(listOf(raceFirst, raceSecond))
        race.initialize(RobotState())
        race.end(RobotState(), interrupted = true)
        assertEquals(1, raceFirst.endCalls)
        assertEquals(1, raceSecond.endCalls)

        val deadline = CleanupTask("deadline", throwOnEnd = true)
        val deadlineOther = CleanupTask("deadline-other")
        val group = ParallelDeadlineGroup(deadline, listOf(deadlineOther))
        group.initialize(RobotState())
        group.end(RobotState(), interrupted = true)
        assertEquals(1, deadline.endCalls)
        assertEquals(1, deadlineOther.endCalls)
    }

    @Test
    fun `deadline group timeout callback runs synchronously on executor thread`() {
        val callerThread = Thread.currentThread()
        var callbackThread: Thread? = null
        val timed = ParallelDeadlineGroup(TimeWaitTask(Long.MAX_VALUE), emptyList())
            .withTimeout(0L)
            .onFail { callbackThread = Thread.currentThread() }
        val executor = TaskExecutor()
        executor.addTask(timed)
        com.areslib.util.RobotClock.useMockTime(0L)
        try {
            executor.update(RobotState(), 0L)
            executor.update(RobotState(), 1L)
        } finally {
            com.areslib.util.RobotClock.useSystemTime()
        }

        assertEquals(callerThread, callbackThread)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(timed))
        assertEquals(0, executor.size)
        timed.reset()
    }

    @Test
    fun `normal end failure aborts queued tasks and preserves failed status`() {
        var queuedInitialized = false
        var failureCallbacks = 0
        val failingEnd = object : Task {
            override val name = "failing-end"
            override fun isCompleted(state: RobotState, elapsedMs: Long) = true
            override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
                if (!interrupted) error("normal end failed")
                return super.end(state, interrupted)
            }
        }.onFail { failureCallbacks++ }
        val queued = object : Task {
            override val name = "queued-after-end-failure"
            override fun initialize(state: RobotState): List<RobotAction> {
                queuedInitialized = true
                return super.initialize(state)
            }
            override fun isCompleted(state: RobotState, elapsedMs: Long) = true
        }
        val executor = TaskExecutor()
        executor.addTask(failingEnd)
        executor.addTask(queued)

        executor.update(RobotState(), 0L)

        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(failingEnd))
        assertEquals(1, failureCallbacks)
        assertFalse(queuedInitialized)
        assertEquals(0, executor.size)
        failingEnd.reset()
        queued.reset()
    }

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
        executor.cancelAll(RobotState())
        longTask.reset()
    }

    @Test
    fun `timeouts reject nonsensical negative durations`() {
        assertFailsWith<IllegalArgumentException> { TimeWaitTask(1L).withTimeout(-1L) }
        assertFailsWith<IllegalArgumentException> { TimeWaitTask(-1L) }
    }

    @Test
    fun `executor preserves active cancellation and runs interrupted cleanup without callbacks`() {
        var wasInterrupted = false
        var completions = 0
        var failures = 0
        var queuedInitialized = false
        val cancelled = object : Task {
            override val name = "cancelled"
            override fun isCompleted(state: RobotState, elapsedMs: Long) = false
            override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
                cancel()
                return emptyList()
            }
            override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
                wasInterrupted = interrupted
                return super.end(state, interrupted)
            }
        }.onComplete { completions++ }.onFail { failures++ }
        val queued = object : Task {
            override val name = "queued"
            override fun initialize(state: RobotState): List<RobotAction> {
                queuedInitialized = true
                return super.initialize(state)
            }
            override fun isCompleted(state: RobotState, elapsedMs: Long) = true
        }
        val executor = TaskExecutor()
        executor.addTask(cancelled)
        executor.addTask(queued)

        executor.update(RobotState(), 0L)
        executor.update(RobotState(), 20L)

        assertEquals(TaskStatus.CANCELLED, TaskStateMachine.getStatus(cancelled))
        assertEquals(0, executor.size)
        assertEquals(0, completions)
        assertEquals(0, failures)
        assertEquals(true, wasInterrupted)
        assertFalse(queuedInitialized)
        cancelled.reset()
        queued.reset()
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
