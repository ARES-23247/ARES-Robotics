package com.areslib.routine

import com.areslib.action.RobotAction
import com.areslib.sequencer.*
import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RoutineFactoryTreeAuditTest {
    private class Leaf : Task {
        override val name = "Factory leaf"
        var releases = 0
        var pauses = 0
        var resumes = 0
        var releaseFailure: Throwable? = null
        val neutral = RobotAction.JoystickDriveIntent(0.0, 0.0, 0.0)
        override fun isCompleted(state: RobotState, elapsedMs: Long) = false
        override fun releaseRuntimeState() { releases++; releaseFailure?.let { throw it }; super.releaseRuntimeState() }
        override fun pause(state: RobotState): List<RobotAction> { pauses++; return listOf(neutral) }
        override fun resume(state: RobotState): List<RobotAction> { resumes++; return emptyList() }
    }
    private fun compile(task: Task): RoutineCompilationResult {
        val document = routine("factory", "Factory") { action("tree") }
        return RoutineCompiler(mapOf(document.documentId to document), RoutineRuntimeBindings(
            createActionTask = { _, _ -> task }, createCondition = { _, _ -> null }))
            .compile(document.documentId, 1L)
    }

    @Test fun `rejected nested tree releases later fresh siblings without disturbing a pending owner`() {
        val foreign = Leaf()
        val owner = requireNotNull(compile(foreign).task)
        val fresh = Leaf().also { it.withTimeout(1L) }
        var foreignCallbacks = 0
        var freshCallbacks = 0
        foreign.onComplete { foreignCallbacks++ }
        fresh.onComplete { freshCallbacks++ }
        try {
            val result = compile(SequentialTaskGroup(listOf(ParallelTaskGroup(listOf(foreign, fresh)))))
            assertFalse(result.isSuccess)
            assertEquals(1, fresh.releases)
            assertEquals(0, foreign.releases)
            assertFalse(TaskTimeoutManager.isTimedOut(fresh, 100L))
            TaskCallbacks.invokeComplete(fresh)
            TaskCallbacks.invokeComplete(foreign)
            assertEquals(0, freshCallbacks)
            assertEquals(1, foreignCallbacks)
        } finally { owner.releaseRuntimeState(); foreign.reset(); fresh.reset() }
    }

    @Test fun `a running child cannot prevent cleanup of the fresh tail of a rejected tree`() {
        val running = Leaf()
        val tail = Leaf()
        var callback = 0
        running.onComplete { callback++ }
        running.initialize(RobotState())
        try {
            val result = compile(SequentialTaskGroup(listOf(running, tail)))
            assertFalse(result.isSuccess)
            assertEquals(1, tail.releases)
            assertEquals(0, running.releases)
            assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(running))
            TaskCallbacks.invokeComplete(running)
            assertEquals(1, callback)
        } finally { running.reset(); tail.reset() }
    }

    @Test fun `executor preemption forwards neutral and freezes both running leaves of a compiled group`() {
        val wasMocked = RobotClock.isMocked
        val old = RobotClock.currentTimeMillis()
        RobotClock.useMockTime(1000L)
        val first = Leaf().also { it.withTimeout(10L) }
        val second = Leaf().also { it.withTimeout(10L) }
        val compiled = requireNotNull(compile(ParallelTaskGroup(listOf(first, second))).task)
        val urgent = TimeWaitTask(1000L)
        val executor = TaskExecutor().also { it.addTask(compiled) }
        try {
            executor.update(RobotState(), 1000L)
            RobotClock.useMockTime(1005L)
            val neutral = executor.preempt(urgent, RobotState(), 1005L)
            assertTrue(neutral.any { it === first.neutral })
            assertTrue(neutral.any { it === second.neutral })
            RobotClock.useMockTime(2005L)
            TaskTimeoutManager.runWatchdogCheck(2005L)
            assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(first))
            assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(second))
            executor.update(RobotState(), 2005L)
            assertEquals(1, first.pauses); assertEquals(1, second.pauses)
            assertEquals(1, first.resumes); assertEquals(1, second.resumes)
            RobotClock.useMockTime(2010L)
            TaskTimeoutManager.runWatchdogCheck(2010L)
            assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(first))
        } finally {
            try { executor.cancelAll(RobotState()); compiled.releaseRuntimeState() }
            finally {
                first.reset(); second.reset(); urgent.reset()
                if (wasMocked) RobotClock.useMockTime(old) else RobotClock.useSystemTime()
            }
        }
    }

    @Test fun `factory ownership rejects a foreign subtree but cleans fresh siblings for every builtin group`() {
        val groups: List<(List<Task>) -> Task> = listOf(
            { SequentialTaskGroup(it) }, { ParallelTaskGroup(it) }, { ParallelRaceGroup(it) },
            { ParallelDeadlineGroup(it.first(), it.drop(1)) })
        for (group in groups) {
            val foreign = Leaf()
            val original = ownRoutineTaskTree(foreign)
            val prefix = Leaf()
            val tail = Leaf()
            try {
                assertThrows(IllegalStateException::class.java) {
                    ownRoutineTaskTree(group(listOf(prefix, SequentialTaskGroup(listOf(foreign)), tail)))
                }
                assertEquals(1, prefix.releases)
                assertEquals(1, tail.releases)
                assertEquals(0, foreign.releases)
                assertFalse(compile(foreign).isSuccess)
            } finally { original.releaseRuntimeState(); foreign.reset(); prefix.reset(); tail.reset() }
        }
    }

    @Test fun `failed factory ownership retains acquisition error and unique cleanup errors after all nodes`() {
        val foreign = Leaf()
        val original = ownRoutineTaskTree(foreign)
        val cleanup = IllegalArgumentException("cleanup failure")
        val first = Leaf().also { it.releaseFailure = cleanup; it.withTimeout(1L) }
        val last = Leaf().also { it.releaseFailure = cleanup; it.withTimeout(1L) }
        var callbacks = 0
        first.onComplete { callbacks++ }; last.onFail { callbacks++ }
        try {
            val error = assertThrows(IllegalStateException::class.java) {
                ownRoutineTaskTree(SequentialTaskGroup(listOf(first, foreign, last)))
            }
            assertEquals(listOf(cleanup), error.suppressed.toList())
            assertEquals(1, first.releases); assertEquals(1, last.releases)
            assertFalse(TaskTimeoutManager.isTimedOut(first, 100L))
            assertFalse(TaskTimeoutManager.isTimedOut(last, 100L))
            TaskCallbacks.invokeComplete(first); TaskCallbacks.invokeFail(last)
            assertEquals(0, callbacks)
        } finally {
            first.releaseFailure = null; last.releaseFailure = null
            original.releaseRuntimeState(); foreign.reset(); first.reset(); last.reset()
        }
    }

    @Test fun `factory metadata cleanup attempts all nodes preserves interruption and is not repeated`() {
        val wasInterrupted = Thread.interrupted()
        val first = Leaf()
        val last = Leaf()
        val owner = ownRoutineTaskTree(SequentialTaskGroup(listOf(first, last)))
        val interrupted = InterruptedException("metadata interrupted")
        last.releaseFailure = interrupted
        try {
            assertSame(interrupted, assertThrows(InterruptedException::class.java) { owner.releaseRuntimeState() })
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, first.releases); assertEquals(1, last.releases)
            owner.releaseRuntimeState()
            assertEquals(1, first.releases); assertEquals(1, last.releases)
        } finally {
            Thread.interrupted(); last.releaseFailure = null
            first.reset(); last.reset(); owner.reset()
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    @Test fun `a returned factory owner can enter compilation while retaining exclusive descendant claims`() {
        val leaf = Leaf()
        val owned = ownRoutineTaskTree(ParallelTaskGroup(listOf(leaf)))
        val result = compile(owned)
        try {
            assertTrue(result.isSuccess)
            assertFalse(compile(leaf).isSuccess)
            result.task!!.releaseRuntimeState()
            assertEquals(1, leaf.releases)
            val next = ownRoutineTaskTree(leaf)
            next.releaseRuntimeState()
            assertEquals(2, leaf.releases)
        } finally { result.task?.releaseRuntimeState(); owned.reset(); leaf.reset() }
    }

    @Test fun `claiming a running root does not touch its dormant descendants`() {
        val active = Leaf()
        val dormant = Leaf()
        val group = SequentialTaskGroup(listOf(active, dormant))
        group.initialize(RobotState())
        try {
            assertThrows(IllegalStateException::class.java) { ownRoutineTaskTree(group) }
            assertEquals(0, active.releases); assertEquals(0, dormant.releases)
            assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(active))
            assertEquals(TaskStatus.PENDING, TaskStateMachine.getStatus(dormant))
        } finally { group.end(RobotState(), true); active.reset(); dormant.reset(); group.reset() }
    }

    @Test fun `factory owner preserves priority resources callbacks and normal completion`() {
        var completed = 0
        val leaf = object : Task {
            override val name = "Owned mechanism"
            override val priority = 17
            override val requiredResources = TaskResources.DRIVE
            override fun isCompleted(state: RobotState, elapsedMs: Long) = true
        }.onComplete { completed++ }
        val owner = ownRoutineTaskTree(leaf)
        val executor = TaskExecutor().also { it.addTask(owner) }
        try {
            assertEquals(leaf.name, owner.name)
            assertEquals(17, owner.priority)
            assertEquals(TaskResources.DRIVE, owner.requiredResources)
            executor.update(RobotState(), 1000L)
            assertEquals(1, completed)
            assertEquals(TaskStatus.COMPLETED, TaskStateMachine.getStatus(owner))
            assertEquals(TaskStatus.COMPLETED, TaskStateMachine.getStatus(leaf))
            assertEquals(0, executor.size)
        } finally { executor.cancelAll(RobotState()); owner.reset(); leaf.reset() }
    }
}
