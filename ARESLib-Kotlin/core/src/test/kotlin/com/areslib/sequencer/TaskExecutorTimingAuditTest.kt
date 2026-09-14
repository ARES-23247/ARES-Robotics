package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.routine.ownRoutineTaskTree
import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TaskExecutorTimingAuditTest {
    private class Probe(private val label: String = "probe") : Task, TaskTimeoutContainer {
        var starts = 0
        var executions = 0
        var ends = 0
        var releases = 0
        var done = false
        var elapsed = -1L
        var executeFailure: Throwable? = null
        var suspensionFailure: Throwable? = null
        var onPause: (() -> Unit)? = null
        var badName = false
        override val name: String get() = if (badName) throw InterruptedException("name failed") else label
        val neutral = RobotAction.SetIndicatorLight(label, 0.0, 0L)
        override fun initialize(state: RobotState): List<RobotAction> { starts++; return super.initialize(state) }
        override fun isCompleted(state: RobotState, elapsedMs: Long) = done
        override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
            executions++; elapsed = elapsedMs; executeFailure?.let { throw it }; return super.execute(state, elapsedMs)
        }
        override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
            ends++; super.end(state, interrupted); return listOf(neutral)
        }
        override fun releaseRuntimeState() { releases++; super.releaseRuntimeState() }
        override fun pause(state: RobotState): List<RobotAction> { onPause?.invoke(); return emptyList() }
        override fun suspendChildTimeouts(paused: Boolean) { suspensionFailure?.let { throw it } }
    }
    private val state = RobotState()
    private inline fun clock(block: () -> Unit) {
        val mocked = RobotClock.isMocked
        val old = RobotClock.currentTimeMillis()
        RobotClock.useMockTime(1000L)
        try { block() } finally { if (mocked) RobotClock.useMockTime(old) else RobotClock.useSystemTime() }
    }
    private fun finish(executor: TaskExecutor, vararg tasks: Probe) {
        tasks.forEach { it.executeFailure = null; it.suspensionFailure = null; it.badName = false; it.onPause = null }
        executor.cancelAll(state)
        tasks.forEach { it.reset() }
    }

    @Test fun `rollback after a valid frame stops an untimed task instead of delivering decreasing elapsed`() = clock {
        val task = Probe(); val queued = Probe("queued"); val executor = TaskExecutor()
        try {
            executor.addTask(task); executor.addTask(queued)
            executor.update(state, 1000L); executor.update(state, 1100L)
            val actions = executor.update(state, 1050L)
            assertEquals(2, task.executions)
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
            assertTrue(actions.any { it === task.neutral })
            assertEquals(0, queued.starts); assertEquals(1, queued.releases)
            assertEquals(0, executor.size)
        } finally { finish(executor, task, queued) }
    }

    @Test fun `unrepresentable total elapsed cannot wrap into an untimed task callback`() = clock {
        val task = Probe(); val executor = TaskExecutor()
        try {
            RobotClock.useMockTime(Long.MIN_VALUE)
            executor.addTask(task); executor.update(state, Long.MIN_VALUE)
            executor.update(state, -1L)
            assertEquals(Long.MAX_VALUE, task.elapsed)
            val actions = executor.update(state, 0L)
            assertEquals(2, task.executions)
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
            assertTrue(actions.any { it === task.neutral })
        } finally { finish(executor, task) }
    }

    @Test fun `backward preemption cleans current work without starting the incoming task`() = clock {
        val task = Probe(); val incoming = Probe("incoming"); val executor = TaskExecutor()
        try {
            executor.addTask(task); executor.update(state, 1000L); executor.update(state, 1100L)
            val actions = executor.preempt(incoming, state, 1050L)
            assertEquals(0, incoming.starts); assertEquals(1, incoming.releases)
            assertTrue(actions.any { it === task.neutral })
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
            assertEquals(0, executor.size)
        } finally { finish(executor, task, incoming) }
    }

    @Test fun `resume clock rollback latches a fault until stateful cleanup even without a timeout`() = clock {
        val task = Probe(); val executor = TaskExecutor()
        try {
            executor.addTask(task); executor.update(state, 1000L)
            RobotClock.useMockTime(1100L); executor.suspend()
            RobotClock.useMockTime(1050L)
            assertThrows(IllegalArgumentException::class.java) { executor.resume() }
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
            val actions = executor.update(state, 1050L)
            assertTrue(actions.any { it === task.neutral })
            assertEquals(1, task.executions); assertEquals(1, task.ends)
            assertEquals(0, executor.size)
        } finally { finish(executor, task) }
    }

    @Test fun `throwing timeout suspension cannot strand active work behind the suspended early return`() = clock {
        val task = Probe(); val executor = TaskExecutor()
        try {
            executor.addTask(task); executor.update(state, 1000L)
            val failure = IllegalStateException("suspension failed")
            task.suspensionFailure = failure
            assertSame(failure, assertThrows(IllegalStateException::class.java) { executor.suspend() })
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
            val actions = executor.update(state, 1000L)
            assertTrue(actions.any { it === task.neutral })
            assertEquals(1, task.ends); assertEquals(0, executor.size)
        } finally { finish(executor, task) }
    }

    @Test fun `failure formatting cannot interrupt cleanup or lose a diagnostic interruption`() = clock {
        val interrupted = Thread.interrupted()
        val task = Probe(); val queued = Probe("queued"); val executor = TaskExecutor()
        try {
            executor.addTask(task); executor.addTask(queued); executor.update(state, 1000L)
            task.executeFailure = object : RuntimeException() {
                override fun toString(): String = throw IllegalStateException("formatting failed")
            }
            task.badName = true
            val actions = assertDoesNotThrow<List<RobotAction>> { executor.update(state, 1000L) }
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(actions.any { it === task.neutral })
            assertEquals(1, task.ends); assertEquals(1, queued.releases)
            assertEquals(0, executor.size)
        } finally {
            Thread.interrupted(); finish(executor, task, queued)
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    @Test fun `negative origins and representable cross-zero suspension preserve exact elapsed`() = clock {
        val task = Probe(); val executor = TaskExecutor()
        try {
            RobotClock.useMockTime(Long.MIN_VALUE + 100L)
            executor.addTask(task); executor.update(state, RobotClock.currentTimeMillis())
            RobotClock.useMockTime(Long.MIN_VALUE + 200L); executor.suspend()
            RobotClock.useMockTime(100L); executor.resume()
            executor.update(state, 100L)
            assertEquals(100L, task.elapsed)
            assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(task))
        } finally { finish(executor, task) }
    }

    @Test fun `resume hook failure blocks further control calls and preemption drains without initializing incoming work`() = clock {
        val task = Probe(); val incoming = Probe("incoming"); val executor = TaskExecutor()
        try {
            executor.addTask(task); executor.update(state, 1000L); executor.suspend()
            val failure = IllegalStateException("resume hook failed")
            task.suspensionFailure = failure
            RobotClock.useMockTime(1100L)
            assertSame(failure, assertThrows(IllegalStateException::class.java) { executor.resume() })
            assertSame(failure, assertThrows(IllegalStateException::class.java) { executor.suspend() })
            val actions = executor.preempt(incoming, state, 1100L)
            assertTrue(actions.any { it === task.neutral })
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
            assertEquals(0, incoming.starts); assertEquals(1, incoming.releases)
            assertEquals(1, task.ends); assertEquals(0, executor.size)
        } finally { finish(executor, task, incoming) }
    }

    @Test fun `preemptor timeout hook failure while suspended cleans active stacked and queued work once`() = clock {
        val task = Probe(); val incoming = Probe("incoming"); val queued = Probe("queued")
        val executor = TaskExecutor()
        try {
            executor.addTask(task); executor.addTask(queued); executor.update(state, 1000L); executor.suspend()
            incoming.suspensionFailure = IllegalStateException("preemptor pause failed")
            val actions = assertDoesNotThrow<List<RobotAction>> { executor.preempt(incoming, state, 1000L) }
            assertTrue(actions.any { it === task.neutral }); assertTrue(actions.any { it === incoming.neutral })
            assertEquals(1, task.ends); assertEquals(1, incoming.ends)
            assertEquals(0, queued.starts); assertEquals(1, queued.releases)
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(incoming))
            assertEquals(0, executor.size)
        } finally { finish(executor, task, incoming, queued) }
    }

    @Test fun `expired nested child at suspension faults both raw and compiled owners without running dormant work`() = clock {
        for (compiled in listOf(false, true)) {
            RobotClock.useMockTime(1000L)
            val child = Probe("child"); val dormant = Probe("dormant"); val executor = TaskExecutor()
            child.withTimeout(50L)
            val group = ParallelTaskGroup(listOf(SequentialTaskGroup(listOf(child, dormant))))
            val root = if (compiled) ownRoutineTaskTree(group) else group
            try {
                executor.addTask(root); executor.update(state, 1000L)
                RobotClock.useMockTime(1051L)
                assertThrows(TaskTransitionAbort::class.java) { executor.suspend() }
                assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(root))
                val actions = executor.update(state, 1051L)
                assertTrue(actions.any { it === child.neutral })
                assertEquals(1, child.ends); assertEquals(0, dormant.starts)
                assertEquals(1, dormant.releases); assertEquals(0, executor.size)
            } finally { finish(executor, child, dormant); root.reset() }
        }
    }

    @Test fun `suspended frame rollback cleans instead of leaving a paused active invocation`() = clock {
        val task = Probe(); val executor = TaskExecutor()
        try {
            executor.addTask(task); executor.update(state, 1000L)
            RobotClock.useMockTime(1100L); executor.suspend()
            assertTrue(executor.update(state, 1200L).isEmpty())
            val actions = executor.update(state, 1150L)
            assertTrue(actions.any { it === task.neutral })
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
            assertEquals(1, task.executions); assertEquals(1, task.ends)
        } finally { finish(executor, task) }
    }

    @Test fun `cancelled root is ended while suspended and remains cancelled`() = clock {
        val task = Probe(); val executor = TaskExecutor()
        try {
            executor.addTask(task); executor.update(state, 1000L); executor.suspend()
            TaskStateMachine.transitionTo(task, TaskStatus.CANCELLED)
            val actions = executor.update(state, 1000L)
            assertTrue(actions.any { it === task.neutral })
            assertEquals(TaskStatus.CANCELLED, TaskStateMachine.getStatus(task))
            assertEquals(1, task.ends); assertEquals(1, task.executions); assertEquals(0, executor.size)
        } finally { finish(executor, task) }
    }

    @Test fun `unrepresentable suspension interval fails before resuming callbacks`() = clock {
        val task = Probe(); val executor = TaskExecutor()
        try {
            RobotClock.useMockTime(Long.MIN_VALUE)
            executor.addTask(task); executor.update(state, Long.MIN_VALUE); executor.suspend()
            RobotClock.useMockTime(0L)
            assertThrows(IllegalArgumentException::class.java) { executor.resume() }
            val actions = executor.update(state, 0L)
            assertTrue(actions.any { it === task.neutral })
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
            assertEquals(1, task.executions); assertEquals(1, task.ends)
        } finally { finish(executor, task) }
    }

    @Test fun `nested preemption and suspension charge only each invocation active intervals`() = clock {
        val original = Probe("original"); val first = Probe("first"); val second = Probe("second")
        val executor = TaskExecutor()
        try {
            executor.addTask(original); executor.update(state, 1000L)
            executor.preempt(first, state, 1100L)
            executor.update(state, 1125L)
            executor.preempt(second, state, 1150L)
            RobotClock.useMockTime(1175L); executor.suspend()
            RobotClock.useMockTime(2175L); executor.resume()
            executor.update(state, 2200L)
            assertEquals(50L, second.elapsed)
            second.done = true; executor.update(state, 2200L)
            assertEquals(50L, first.elapsed)
            executor.update(state, 2250L); assertEquals(100L, first.elapsed)
            first.done = true; executor.update(state, 2300L)
            assertEquals(100L, original.elapsed)
            executor.update(state, 2350L); assertEquals(150L, original.elapsed)
            assertEquals(1, first.ends); assertEquals(1, second.ends)
        } finally { finish(executor, original, first, second) }
    }

    @Test fun `incoming cancellation during pause cannot end the same stacked task twice`() = clock {
        val task = Probe(); val incoming = Probe("incoming"); val executor = TaskExecutor()
        try {
            executor.addTask(task); executor.update(state, 1000L)
            task.onPause = { TaskStateMachine.transitionTo(incoming, TaskStatus.CANCELLED) }
            val actions = executor.preempt(incoming, state, 1000L)
            assertEquals(1, task.ends); assertEquals(1, actions.count { it === task.neutral })
            assertEquals(0, incoming.starts); assertEquals(1, incoming.releases)
            assertEquals(0, executor.size)
        } finally { finish(executor, task, incoming) }
    }

    @Test fun `fully drained executor accepts a new clock origin after completion and fault cancellation`() = clock {
        val first = Probe("first"); val second = Probe("second"); val third = Probe("third")
        val executor = TaskExecutor()
        try {
            first.done = true; executor.addTask(first); executor.update(state, 1000L)
            RobotClock.useMockTime(-100L)
            executor.addTask(second); executor.update(state, -100L)
            assertEquals(0L, second.elapsed); assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(second))
            second.suspensionFailure = IllegalStateException("suspend failed")
            assertThrows(IllegalStateException::class.java) { executor.suspend() }
            assertTrue(executor.cancelAll(state).any { it === second.neutral })
            executor.resume()
            RobotClock.useMockTime(-200L)
            executor.addTask(third); executor.update(state, -200L)
            assertEquals(0L, third.elapsed); assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(third))
        } finally { finish(executor, first, second, third) }
    }

    @Test fun `a throwing diagnostic sink cannot stop cleanup and preserves its interruption`() = clock {
        val interrupted = Thread.interrupted(); val originalErr = System.err
        val task = Probe(); val executor = TaskExecutor()
        val sink = object : java.io.PrintStream(java.io.ByteArrayOutputStream()) {
            override fun println(value: String?) { throw InterruptedException("diagnostic write failed") }
        }
        try {
            executor.addTask(task); executor.update(state, 1000L)
            task.executeFailure = IllegalStateException("execute failed")
            System.setErr(sink)
            val actions = assertDoesNotThrow<List<RobotAction>> { executor.update(state, 1000L) }
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(actions.any { it === task.neutral })
            assertEquals(1, task.ends); assertEquals(0, executor.size)
        } finally {
            System.setErr(originalErr); sink.close(); Thread.interrupted(); finish(executor, task)
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    @Test fun `latched transition actions are returned once by update or explicit cancellation`() = clock {
        for (cancelDirectly in listOf(false, true)) {
            val task = Probe(); val executor = TaskExecutor()
            val pendingNeutral = RobotAction.SetIndicatorLight("private-child", 0.0, 0L)
            try {
                executor.addTask(task); executor.update(state, 1000L)
                task.suspensionFailure = TaskTransitionAbort(listOf(pendingNeutral), null, TaskStatus.CANCELLED)
                assertThrows(TaskTransitionAbort::class.java) { executor.suspend() }
                val actions = if (cancelDirectly) executor.cancelAll(state) else executor.update(state, 1000L)
                assertEquals(1, actions.count { it === pendingNeutral })
                assertEquals(1, actions.count { it === task.neutral })
                assertEquals(TaskStatus.CANCELLED, TaskStateMachine.getStatus(task))
                assertEquals(1, task.ends); assertEquals(0, executor.size)
                assertTrue(executor.cancelAll(state).isEmpty())
            } finally { finish(executor, task) }
        }
    }
}
