package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.routine.*
import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TaskPreemptionCleanupAuditTest {
    private class Probe(override val name: String) : Task {
        var starts = 0; var pauses = 0; var resumes = 0; var ends = 0; var releases = 0
        var done = false
        var initializeFailure: Throwable? = null
        var pauseFailure: Throwable? = null
        var resumeFailure: Throwable? = null
        var endFailure: Throwable? = null
        var releaseFailure: Throwable? = null
        val pauseNeutral = RobotAction.SetIndicatorLight(name, 0.0, 1000L)
        val endNeutral = RobotAction.SetIndicatorLight(name, 0.0, 1000L)
        override fun initialize(state: RobotState): List<RobotAction> {
            starts++; val actions = super.initialize(state); initializeFailure?.let { throw it }; return actions
        }
        override fun isCompleted(state: RobotState, elapsedMs: Long) = done
        override fun pause(state: RobotState): List<RobotAction> { pauses++; pauseFailure?.let { throw it }; return listOf(pauseNeutral) }
        override fun resume(state: RobotState): List<RobotAction> { resumes++; resumeFailure?.let { throw it }; return emptyList() }
        override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
            ends++; endFailure?.let { throw it }; super.end(state, interrupted); return listOf(endNeutral)
        }
        override fun releaseRuntimeState() { releases++; releaseFailure?.let { throw it }; super.releaseRuntimeState() }
        fun clearFailures() { initializeFailure = null; pauseFailure = null; resumeFailure = null; endFailure = null; releaseFailure = null }
    }
    private fun compile(task: Task): Task {
        val document = routine("preempt", "Preempt") { action("tree") }
        return requireNotNull(RoutineCompiler(mapOf(document.documentId to document), RoutineRuntimeBindings(
            createActionTask = { _, _ -> task }, createCondition = { _, _ -> null })).compile(document.documentId, 1L).task)
    }
    private inline fun withClock(block: () -> Unit) {
        val wasMocked = RobotClock.isMocked
        val old = RobotClock.currentTimeMillis()
        RobotClock.useMockTime(1000L)
        try { block() } finally { if (wasMocked) RobotClock.useMockTime(old) else RobotClock.useSystemTime() }
    }
    private fun cleanup(executor: TaskExecutor, root: Task, vararg tasks: Probe) {
        tasks.forEach(Probe::clearFailures)
        try { executor.cancelAll(RobotState()) } finally { root.releaseRuntimeState(); tasks.forEach { it.reset() }; root.reset() }
    }

    @Test fun `a failed compiled pause preserves sibling neutral actions and does not start the incoming task`() = withClock {
        val first = Probe("first")
        val broken = Probe("broken")
        val last = Probe("last")
        val incoming = Probe("incoming")
        val queued = Probe("queued")
        val root = compile(ParallelTaskGroup(listOf(first, broken, last)))
        val executor = TaskExecutor().also { it.addTask(root); it.addTask(queued) }
        try {
            executor.update(RobotState(), 1000L)
            broken.pauseFailure = IllegalStateException("pause failed")
            first.endFailure = IllegalArgumentException("end cannot return first neutral")
            val actions = executor.preempt(incoming, RobotState(), 1000L)
            assertEquals(0, incoming.starts)
            assertEquals(1, last.pauses)
            assertTrue(actions.any { it === first.pauseNeutral })
            assertTrue(actions.any { it === last.pauseNeutral })
            assertTrue(actions.any { it === broken.endNeutral })
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(root))
            assertEquals(0, executor.size)
            assertEquals(1, queued.releases); assertEquals(0, queued.ends)
            assertEquals(1, incoming.releases); assertEquals(0, incoming.ends)
        } finally { cleanup(executor, root, first, broken, last, incoming, queued) }
    }

    @Test fun `resume failure still stops every child when one metadata cleanup hook also throws`() = withClock {
        val first = Probe("first")
        val broken = Probe("broken")
        val last = Probe("last")
        val urgent = Probe("urgent").also { it.done = true }
        val root = compile(ParallelTaskGroup(listOf(first, broken, last)))
        val executor = TaskExecutor().also { it.addTask(root) }
        try {
            executor.update(RobotState(), 1000L)
            executor.preempt(urgent, RobotState(), 1000L)
            broken.resumeFailure = IllegalStateException("resume failed")
            broken.releaseFailure = IllegalArgumentException("release failed")
            val actions = assertDoesNotThrow<List<RobotAction>> { executor.update(RobotState(), 1000L) }
            for (task in listOf(first, broken, last)) {
                assertTrue(actions.any { it === task.endNeutral }, "Lost neutral for ${task.name}")
                assertEquals(1, task.ends)
                assertEquals(1, task.releases)
            }
            assertEquals(0, last.resumes)
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(root))
            assertEquals(0, executor.size)
        } finally { cleanup(executor, root, first, broken, last, urgent) }
    }

    @Test fun `terminal preempted tasks cannot be restored to running or receive resume callbacks`() = withClock {
        for (terminal in listOf(TaskStatus.FAILED, TaskStatus.CANCELLED)) {
            val leaf = Probe("leaf")
            val root = compile(leaf)
            val urgent = Probe("urgent").also { it.done = true }
            val executor = TaskExecutor().also { it.addTask(root) }
            try {
                executor.update(RobotState(), 1000L)
                executor.preempt(urgent, RobotState(), 1000L)
                TaskStateMachine.transitionTo(root, terminal)
                val actions = executor.update(RobotState(), 1000L)
                assertEquals(0, leaf.resumes)
                assertEquals(terminal, TaskStateMachine.getStatus(root))
                assertTrue(actions.any { it === leaf.endNeutral })
                assertEquals(0, executor.size)
            } finally { cleanup(executor, root, leaf, urgent) }
        }
    }

    @Test fun `plain task pause interruption is retained while the executor aborts safely`() = withClock {
        val wasInterrupted = Thread.interrupted()
        val task = Probe("interrupted")
        val incoming = Probe("incoming")
        val executor = TaskExecutor().also { it.addTask(task) }
        try {
            executor.update(RobotState(), 1000L)
            task.pauseFailure = InterruptedException("pause interrupted")
            val actions = executor.preempt(incoming, RobotState(), 1000L)
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(actions.any { it === task.endNeutral })
            assertEquals(0, incoming.starts)
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
            assertEquals(0, executor.size)
        } finally {
            Thread.interrupted(); cleanup(executor, task, task, incoming)
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    @Test fun `plain task pause Error also reaches neutral cleanup`() = withClock {
        val task = Probe("broken")
        val incoming = Probe("incoming")
        val executor = TaskExecutor().also { it.addTask(task) }
        try {
            executor.update(RobotState(), 1000L)
            task.pauseFailure = AssertionError("pause error")
            val actions = assertDoesNotThrow<List<RobotAction>> { executor.preempt(incoming, RobotState(), 1000L) }
            assertTrue(actions.any { it === task.endNeutral })
            assertEquals(0, incoming.starts)
            assertEquals(0, executor.size)
        } finally { cleanup(executor, task, task, incoming) }
    }

    @Test fun `compiled builtin groups invoke each acquired metadata hook once across completion and cancellation`() = withClock {
        val groups: List<(List<Task>) -> Task> = listOf(
            { SequentialTaskGroup(it) }, { ParallelTaskGroup(it) }, { ParallelRaceGroup(it) },
            { ParallelDeadlineGroup(it.first(), it.drop(1)) })
        for (group in groups) for (complete in listOf(false, true)) {
            val first = Probe("first").also { it.done = complete }
            val second = Probe("second").also { it.done = complete }
            val root = compile(group(listOf(first, second)))
            val executor = TaskExecutor().also { it.addTask(root) }
            try {
                executor.update(RobotState(), 1000L)
                if (!complete) executor.cancelAll(RobotState())
                assertEquals(1, first.releases)
                assertEquals(1, second.releases)
                root.releaseRuntimeState()
                assertEquals(1, first.releases); assertEquals(1, second.releases)
            } finally { cleanup(executor, root, first, second) }
        }
    }

    @Test fun `cancelAll retains every neutral action and clears queued metadata despite a throwing hook`() = withClock {
        val paused = Probe("paused")
        val active = Probe("active")
        val queued = Probe("queued")
        var callbacks = 0
        listOf(paused, active, queued).forEach { it.onComplete { callbacks++ } }
        val executor = TaskExecutor().also { it.addTask(paused); it.addTask(queued) }
        try {
            executor.update(RobotState(), 1000L)
            executor.preempt(active, RobotState(), 1000L)
            active.releaseFailure = IllegalStateException("active release failed")
            val actions = assertDoesNotThrow<List<RobotAction>> { executor.cancelAll(RobotState()) }
            assertTrue(actions.any { it === paused.endNeutral })
            assertTrue(actions.any { it === active.endNeutral })
            assertEquals(0, executor.size)
            assertEquals(1, paused.ends); assertEquals(1, active.ends); assertEquals(0, queued.ends)
            for (task in listOf(paused, active, queued)) {
                assertEquals(1, task.releases)
                TaskCallbacks.invokeComplete(task)
            }
            assertEquals(0, callbacks)
        } finally { cleanup(executor, paused, paused, active, queued) }
    }

    @Test fun `group initialization failure ends initialized children but only releases dormant metadata`() = withClock {
        val first = Probe("first")
        val broken = Probe("broken").also { it.initializeFailure = AssertionError("initialization failed") }
        val dormant = Probe("dormant")
        val root = compile(ParallelTaskGroup(listOf(first, broken, dormant)))
        val executor = TaskExecutor().also { it.addTask(root) }
        try {
            val actions = assertDoesNotThrow<List<RobotAction>> { executor.update(RobotState(), 1000L) }
            assertEquals(1, first.ends); assertEquals(1, broken.ends)
            assertEquals(0, dormant.starts); assertEquals(0, dormant.ends)
            assertEquals(1, dormant.releases)
            assertTrue(actions.any { it === first.endNeutral })
            assertTrue(actions.any { it === broken.endNeutral })
            assertEquals(0, executor.size)
        } finally { cleanup(executor, root, first, broken, dormant) }
    }

    @Test fun `normal task metadata failure preserves end actions and prevents queued work from starting`() = withClock {
        val finished = Probe("finished").also { it.done = true; it.releaseFailure = IllegalStateException("release failed") }
        val queued = Probe("queued")
        val executor = TaskExecutor().also { it.addTask(finished); it.addTask(queued) }
        try {
            val actions = assertDoesNotThrow<List<RobotAction>> { executor.update(RobotState(), 1000L) }
            assertEquals(1, finished.ends); assertEquals(1, finished.releases)
            assertTrue(actions.any { it === finished.endNeutral })
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(finished))
            assertEquals(0, queued.starts); assertEquals(0, queued.ends); assertEquals(1, queued.releases)
            assertEquals(0, executor.size)
        } finally { cleanup(executor, finished, finished, queued) }
    }

    @Test fun `completed child metadata failure cannot advance a compiled sequence`() = withClock {
        val finished = Probe("finished").also { it.done = true; it.releaseFailure = IllegalStateException("release failed") }
        val following = Probe("following")
        val root = compile(SequentialTaskGroup(listOf(finished, following)))
        val executor = TaskExecutor().also { it.addTask(root) }
        try {
            val actions = executor.update(RobotState(), 1000L)
            assertEquals(1, finished.ends); assertEquals(1, finished.releases)
            assertTrue(actions.any { it === finished.endNeutral })
            assertEquals(0, following.starts); assertEquals(0, following.ends); assertEquals(1, following.releases)
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(root))
            assertEquals(0, executor.size)
        } finally { cleanup(executor, root, finished, following) }
    }

    @Test fun `compiled leaf failure callback preserves direct interruption and neutral cleanup`() = withClock {
        val wasInterrupted = Thread.interrupted()
        val leaf = Probe("leaf")
        leaf.onFail { throw InterruptedException("diagnostic interrupted") }
        val root = compile(leaf)
        val executor = TaskExecutor().also { it.addTask(root) }
        try {
            executor.update(RobotState(), 1000L)
            TaskStateMachine.markFailed(leaf)
            val actions = executor.update(RobotState(), 1000L)
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(actions.any { it === leaf.endNeutral })
            assertEquals(1, leaf.ends); assertEquals(1, leaf.releases)
            assertEquals(0, executor.size)
        } finally {
            Thread.interrupted(); cleanup(executor, root, leaf)
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    @Test fun `cancel metadata interruption cannot prevent remaining owners cleanup`() = withClock {
        val wasInterrupted = Thread.interrupted()
        val active = Probe("active")
        val queued = Probe("queued")
        val executor = TaskExecutor().also { it.addTask(active); it.addTask(queued) }
        try {
            executor.update(RobotState(), 1000L)
            active.releaseFailure = InterruptedException("cleanup interrupted")
            val actions = executor.cancelAll(RobotState())
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(actions.any { it === active.endNeutral })
            assertEquals(1, queued.releases); assertEquals(0, queued.ends)
            assertEquals(0, executor.size)
        } finally {
            Thread.interrupted(); cleanup(executor, active, active, queued)
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    @Test fun `released compiled group and children can be reused after explicit reset`() = withClock {
        val first = Probe("first").also { it.done = true }
        val second = Probe("second").also { it.done = true }
        val group = ParallelTaskGroup(listOf(first, second))
        repeat(2) {
            val beforeFirst = first.releases; val beforeSecond = second.releases
            val root = compile(group)
            val executor = TaskExecutor().also { it.addTask(root) }
            try {
                executor.update(RobotState(), 1000L)
                assertEquals(TaskStatus.COMPLETED, TaskStateMachine.getStatus(root))
                assertEquals(beforeFirst + 1, first.releases); assertEquals(beforeSecond + 1, second.releases)
            } finally { cleanup(executor, root, first, second); group.reset() }
        }
    }

    @Test fun `a terminal child prevents all resume callbacks and preserves its status`() = withClock {
        for (terminal in listOf(TaskStatus.FAILED, TaskStatus.CANCELLED)) {
            val first = Probe("first")
            val stopped = Probe("stopped")
            val urgent = Probe("urgent").also { it.done = true }
            val root = compile(ParallelTaskGroup(listOf(first, stopped)))
            val executor = TaskExecutor().also { it.addTask(root) }
            try {
                executor.update(RobotState(), 1000L)
                executor.preempt(urgent, RobotState(), 1000L)
                TaskStateMachine.transitionTo(stopped, terminal)
                val actions = executor.update(RobotState(), 1000L)
                assertEquals(0, first.resumes); assertEquals(0, stopped.resumes)
                assertEquals(terminal, TaskStateMachine.getStatus(stopped))
                assertEquals(terminal, TaskStateMachine.getStatus(root))
                assertTrue(actions.any { it === first.endNeutral })
                assertTrue(actions.any { it === stopped.endNeutral })
                assertEquals(0, executor.size)
            } finally { cleanup(executor, root, first, stopped, urgent) }
        }
    }
}
