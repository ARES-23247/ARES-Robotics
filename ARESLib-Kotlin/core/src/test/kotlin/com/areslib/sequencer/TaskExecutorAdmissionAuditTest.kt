package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.routine.ownRoutineTaskTree
import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TaskExecutorAdmissionAuditTest {
    private class Probe(override val name: String) : Task {
        var starts = 0
        var executions = 0
        var ends = 0
        var releases = 0
        var pauses = 0
        var done = false
        var onExecute: (() -> Unit)? = null
        var onEnd: (() -> Unit)? = null
        var onRelease: (() -> Unit)? = null
        val neutral = RobotAction.SetIndicatorLight(name, 0.0, 1000L)
        override fun initialize(state: RobotState): List<RobotAction> { starts++; return super.initialize(state) }
        override fun isCompleted(state: RobotState, elapsedMs: Long) = done
        override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
            executions++; super.execute(state, elapsedMs); onExecute?.invoke(); return emptyList()
        }
        override fun pause(state: RobotState): List<RobotAction> { pauses++; return listOf(neutral) }
        override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
            ends++; onEnd?.invoke(); super.end(state, interrupted); return listOf(neutral)
        }
        override fun releaseRuntimeState() { releases++; onRelease?.invoke(); super.releaseRuntimeState() }
        fun unhook() { onExecute = null; onEnd = null; onRelease = null }
    }
    private val state = RobotState()
    private inline fun clock(block: () -> Unit) {
        val mocked = RobotClock.isMocked
        val old = RobotClock.currentTimeMillis()
        RobotClock.useMockTime(1000L)
        try { block() } finally { if (mocked) RobotClock.useMockTime(old) else RobotClock.useSystemTime() }
    }

    @Test fun `duplicate queued and active task identities cannot be admitted again`() = clock {
        val task = Probe("same")
        val executor = TaskExecutor()
        try {
            executor.addTask(task)
            assertThrows(IllegalStateException::class.java) { executor.addTask(task) }
            executor.update(state, 1000L)
            assertThrows(IllegalStateException::class.java) { executor.preempt(task, state, 1000L) }
            assertEquals(1, task.starts)
            assertEquals(0, task.pauses)
            assertEquals(1, executor.size)
        } finally { executor.cancelAll(state); task.reset() }
    }

    @Test fun `pending task ownership excludes another executor and routine factory`() = clock {
        val leaf = Probe("owned")
        val first = TaskExecutor()
        val second = TaskExecutor()
        var callback = 0
        leaf.onComplete { callback++ }
        try {
            first.addTask(leaf)
            assertThrows(IllegalStateException::class.java) { second.addTask(leaf) }
            assertThrows(IllegalStateException::class.java) { ownRoutineTaskTree(leaf) }
            assertEquals(0, second.size)
            assertEquals(0, leaf.releases)
            TaskCallbacks.invokeComplete(leaf)
            assertEquals(1, callback)
        } finally { first.cancelAll(state); second.cancelAll(state); leaf.reset() }
    }

    @Test fun `queued uncompiled trees release dormant child metadata without lifecycle calls`() = clock {
        val first = Probe("first")
        val last = Probe("last")
        val root = SequentialTaskGroup(listOf(first, ParallelTaskGroup(listOf(last))))
        var callbacks = 0
        first.onComplete { callbacks++ }; last.onComplete { callbacks++ }
        val executor = TaskExecutor()
        try {
            executor.addTask(root)
            assertTrue(executor.cancelAll(state).isEmpty())
            for (task in listOf(first, last)) {
                assertEquals(0, task.starts); assertEquals(0, task.ends); assertEquals(1, task.releases)
                TaskCallbacks.invokeComplete(task)
            }
            assertEquals(0, callbacks)
        } finally { executor.cancelAll(state); root.reset(); first.reset(); last.reset() }
    }

    @Test fun `foreign routine task rejection cannot pause existing work`() = clock {
        val foreign = Probe("foreign")
        val owner = ownRoutineTaskTree(foreign)
        val active = Probe("active")
        val executor = TaskExecutor()
        try {
            executor.addTask(active); executor.update(state, 1000L)
            assertThrows(IllegalStateException::class.java) { executor.preempt(foreign, state, 1000L) }
            assertEquals(0, active.pauses); assertEquals(1, executor.size)
            assertEquals(0, foreign.starts); assertEquals(0, foreign.releases)
        } finally { executor.cancelAll(state); owner.releaseRuntimeState(); owner.reset(); active.reset(); foreign.reset() }
    }

    @Test fun `task cancelled while queued is not initialized or ended`() = clock {
        val task = Probe("cancelled")
        val queued = Probe("following")
        val executor = TaskExecutor()
        try {
            executor.addTask(task); executor.addTask(queued)
            TaskStateMachine.transitionTo(task, TaskStatus.CANCELLED)
            executor.update(state, 1000L)
            assertEquals(0, task.starts); assertEquals(0, task.ends)
            assertEquals(TaskStatus.CANCELLED, TaskStateMachine.getStatus(task))
            assertEquals(0, queued.starts); assertEquals(1, queued.releases)
            assertEquals(0, executor.size)
        } finally { executor.cancelAll(state); task.reset(); queued.reset() }
    }

    @Test fun `completion callback cannot orphan a recursively preempted task`() = clock {
        val completed = Probe("completed").also { it.done = true }
        val incoming = Probe("incoming")
        val executor = TaskExecutor()
        completed.onComplete { executor.preempt(incoming, state, 1000L) }
        try {
            executor.addTask(completed)
            executor.update(state, 1000L)
            assertEquals(0, incoming.starts)
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(completed))
            assertEquals(0, executor.size)
        } finally { executor.cancelAll(state); completed.reset(); incoming.reset() }
    }

    @Test fun `recursive update fails closed instead of executing a second frame`() = clock {
        val task = Probe("recursive")
        val executor = TaskExecutor()
        task.onExecute = { if (task.executions == 1) executor.update(state, 1000L) }
        try {
            executor.addTask(task)
            val actions = executor.update(state, 1000L)
            assertEquals(1, task.executions)
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
            assertTrue(actions.any { it === task.neutral })
            assertEquals(0, executor.size)
        } finally { task.unhook(); executor.cancelAll(state); task.reset() }
    }

    @Test fun `completion end hook cannot orphan recursively preempted work`() = clock {
        val completed = Probe("completed").also { it.done = true }
        val incoming = Probe("incoming")
        val executor = TaskExecutor()
        completed.onEnd = { if (completed.ends == 1) executor.preempt(incoming, state, 1000L) }
        try {
            executor.addTask(completed)
            executor.update(state, 1000L)
            assertEquals(0, incoming.starts)
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(completed))
            assertEquals(0, executor.size)
        } finally { completed.unhook(); executor.cancelAll(state); completed.reset(); incoming.reset() }
    }

    @Test fun `cleanup callbacks cannot replenish the executor with fresh work`() = clock {
        val active = Probe("active")
        val incoming = Probe("fresh")
        val executor = TaskExecutor()
        var rejected = false
        active.onEnd = {
            try { executor.addTask(incoming) } catch (_: IllegalStateException) { rejected = true }
        }
        try {
            executor.addTask(active); executor.update(state, 1000L)
            val actions = executor.cancelAll(state)
            assertTrue(rejected)
            assertTrue(actions.any { it === active.neutral })
            assertEquals(0, incoming.starts); assertEquals(0, incoming.releases)
            assertEquals(0, executor.size)
        } finally { active.unhook(); executor.cancelAll(state); active.reset(); incoming.reset() }
    }

    @Test fun `failed nested admission preserves every caller callback and releases only its claims`() = clock {
        val foreign = Probe("foreign")
        val fresh = Probe("fresh")
        val tail = Probe("tail")
        val group = ParallelTaskGroup(listOf(fresh, foreign, tail))
        val owner = TaskExecutor()
        val rejected = TaskExecutor()
        var callbacks = 0
        listOf(foreign, fresh, tail).forEach { it.onComplete { callbacks++ } }
        try {
            owner.addTask(foreign)
            assertThrows(IllegalStateException::class.java) { rejected.addTask(group) }
            assertEquals(0, rejected.size)
            listOf(foreign, fresh, tail).forEach {
                assertEquals(0, it.releases)
                TaskCallbacks.invokeComplete(it)
            }
            assertEquals(3, callbacks)
            rejected.addTask(fresh); rejected.addTask(tail)
            assertEquals(2, rejected.size)
        } finally { owner.cancelAll(state); rejected.cancelAll(state); group.reset(); foreign.reset(); fresh.reset(); tail.reset() }
    }

    @Test fun `normal completion callback can append fresh queued work and released tasks can be reset`() = clock {
        val first = Probe("first").also { it.done = true }
        val following = Probe("following")
        val executor = TaskExecutor()
        first.onComplete { executor.addTask(following) }
        try {
            executor.addTask(first)
            executor.update(state, 1000L)
            assertEquals(TaskStatus.COMPLETED, TaskStateMachine.getStatus(first))
            assertEquals(1, following.starts); assertEquals(1, executor.size)
            assertEquals(1, first.releases)
            executor.cancelAll(state)
            first.reset()
            executor.addTask(first); executor.update(state, 1000L)
            assertEquals(2, first.starts); assertEquals(0, executor.size)
        } finally { executor.cancelAll(state); first.reset(); following.reset() }
    }

    @Test fun `metadata cleanup cannot enqueue new tasks and cannot leave its guard latched`() = clock {
        val done = Probe("done").also { it.done = true }
        val fresh = Probe("fresh")
        val executor = TaskExecutor()
        var rejected = false
        done.onRelease = {
            try { executor.addTask(fresh) } catch (_: IllegalStateException) { rejected = true }
        }
        try {
            executor.addTask(done); executor.update(state, 1000L)
            assertTrue(rejected)
            assertEquals(0, fresh.releases); assertEquals(0, executor.size)
            executor.addTask(fresh); executor.update(state, 1000L)
            assertEquals(1, fresh.starts)
        } finally { done.unhook(); executor.cancelAll(state); done.reset(); fresh.reset() }
    }

    @Test fun `raw group completion then cancellation releases each admitted child once`() = clock {
        val done = Probe("done").also { it.done = true }
        val running = Probe("running")
        val group = SequentialTaskGroup(listOf(done, running))
        val executor = TaskExecutor()
        try {
            executor.addTask(group); executor.update(state, 1000L)
            assertEquals(1, done.ends); assertEquals(1, done.releases)
            val actions = executor.cancelAll(state)
            assertTrue(actions.any { it === running.neutral })
            assertEquals(1, done.releases); assertEquals(1, running.releases)
            assertEquals(1, running.ends)
        } finally { executor.cancelAll(state); group.reset(); done.reset(); running.reset() }
    }

    @Test fun `cancellation of a queued descendant prevents its siblings from initializing`() = clock {
        for (compiled in listOf(false, true)) for (terminal in listOf(TaskStatus.CANCELLED, TaskStatus.FAILED)) {
            val first = Probe("first")
            val cancelled = Probe("cancelled")
            val group = ParallelTaskGroup(listOf(first, cancelled))
            val root = if (compiled) ownRoutineTaskTree(group) else group
            val executor = TaskExecutor()
            try {
                executor.addTask(root)
                TaskStateMachine.transitionTo(cancelled, terminal)
                executor.update(state, 1000L)
                assertEquals(0, first.starts)
                assertEquals(0, cancelled.starts)
                assertEquals(terminal, TaskStateMachine.getStatus(root))
                assertEquals(1, first.releases); assertEquals(1, cancelled.releases)
                assertEquals(0, executor.size)
            } finally { executor.cancelAll(state); root.reset(); group.reset(); first.reset(); cancelled.reset() }
        }
    }

    @Test fun `warm active executor frames do not allocate task ownership storage`() = clock {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        org.junit.jupiter.api.Assumptions.assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        requireNotNull(bean).isThreadAllocatedMemoryEnabled = true
        val task = Probe("steady")
        val executor = TaskExecutor()
        try {
            executor.addTask(task)
            repeat(20_000) { executor.update(state, 1000L) }
            val threadId = Thread.currentThread().id
            val before = bean.getThreadAllocatedBytes(threadId)
            repeat(10_000) { executor.update(state, 1000L) }
            val allocated = bean.getThreadAllocatedBytes(threadId) - before
            println("[Executor admission audit] 10000 warm updates allocated $allocated bytes")
            assertTrue(allocated <= 256L, "Warm updates allocated $allocated bytes")
        } finally { executor.cancelAll(state); task.reset() }
    }

    @Test fun `completed raw groups can be resubmitted without resetting newly configured callbacks`() = clock {
        val first = Probe("first").also { it.done = true }
        val last = Probe("last").also { it.done = true }
        val group = ParallelTaskGroup(listOf(first, last))
        val executor = TaskExecutor()
        var callbacks = 0
        try {
            repeat(2) { invocation ->
                first.onComplete { callbacks++ }; last.onComplete { callbacks++ }
                executor.addTask(group); executor.update(state, 1000L)
                assertEquals(TaskStatus.COMPLETED, TaskStateMachine.getStatus(group))
                assertEquals(0, executor.size)
                assertEquals(invocation + 1, first.starts); assertEquals(invocation + 1, last.starts)
                assertEquals(invocation + 1, first.releases); assertEquals(invocation + 1, last.releases)
            }
            assertEquals(4, callbacks)
        } finally { executor.cancelAll(state); group.reset(); first.reset(); last.reset() }
    }

    @Test fun `released compiled wrappers cannot disguise missing ownership by resetting their status`() = clock {
        for (execute in listOf(false, true)) {
            val leaf = Probe("leaf").also { it.done = true }
            val root = ownRoutineTaskTree(leaf)
            val executor = TaskExecutor()
            var callbacks = 0
            try {
                if (execute) { executor.addTask(root); executor.update(state, 1000L) }
                else root.releaseRuntimeState()
                root.reset()
                leaf.onComplete { callbacks++ }
                assertThrows(IllegalStateException::class.java) { executor.addTask(root) }
                assertThrows(IllegalStateException::class.java) { ownRoutineTaskTree(root) }
                assertEquals(0, executor.size)
                TaskCallbacks.invokeComplete(leaf)
                assertEquals(1, callbacks)
            } finally { executor.cancelAll(state); root.reset(); leaf.reset() }
        }
    }

    @Test fun `terminal raw resubmission is explicit and a later queued cancellation still wins`() = clock {
        for (terminal in listOf(TaskStatus.CANCELLED, TaskStatus.FAILED)) {
            val task = Probe("restart")
            val executor = TaskExecutor()
            var callbacks = 0
            try {
                executor.addTask(task); executor.update(state, 1000L)
                if (terminal == TaskStatus.CANCELLED) executor.cancelAll(state)
                else { TaskStateMachine.markFailed(task); executor.update(state, 1000L) }
                assertEquals(terminal, TaskStateMachine.getStatus(task))
                executor.addTask(task)
                assertEquals(TaskStatus.PENDING, TaskStateMachine.getStatus(task))
                task.cancel()
                executor.update(state, 1000L)
                assertEquals(1, task.starts); assertEquals(1, task.ends)
                assertEquals(0, executor.size)
                task.done = true
                task.onComplete { callbacks++ }
                executor.addTask(task); executor.update(state, 1000L)
                assertEquals(2, task.starts); assertEquals(2, task.ends)
                assertEquals(1, callbacks)
                assertEquals(TaskStatus.COMPLETED, TaskStateMachine.getStatus(task))
            } finally { executor.cancelAll(state); task.reset() }
        }
    }
}
