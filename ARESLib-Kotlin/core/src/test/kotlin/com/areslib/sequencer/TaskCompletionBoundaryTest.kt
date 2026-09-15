package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class TaskCompletionBoundaryTest {
    private val state = RobotState()
    private val owned = mutableListOf<Task>()
    @BeforeEach fun clock() { RobotClock.useMockTime(0L) }
    @AfterEach fun cleanup() { owned.forEach { it.reset() }; RobotClock.useSystemTime() }

    private open class Probe : Task {
        override val name = "completion-probe"
        var ready = false
        var predicates = 0
        var starts = 0
        var completes = 0
        var failures = 0
        val endings = mutableListOf<Boolean>()
        override fun initialize(state: RobotState): List<RobotAction> {
            starts++
            return super.initialize(state)
        }
        override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
            predicates++
            return ready
        }
        override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
            endings.add(interrupted)
            return super.end(state, interrupted)
        }
    }
    private fun <T : Task> own(task: T): T = task.also { owned.add(it) }
    private fun probe(): Probe = own(Probe()).also { p ->
        p.onComplete { p.completes++ }.onFail { p.failures++ }
    }
    private val factories = listOf<(Task) -> Task>(
        { SequentialTaskGroup(listOf(it)) }, { ParallelTaskGroup(listOf(it)) },
        { ParallelRaceGroup(listOf(it)) }, { ParallelDeadlineGroup(it, emptyList()) }
    )

    @Test fun `normal end removes deadline before invoking completion callback`() {
        val p = probe()
        p.withTimeout(10)
        p.initialize(state)
        p.onComplete {
            assertFalse(Thread.holdsLock(TaskTimeoutManager))
            TaskTimeoutManager.runWatchdogCheck(11)
            assertEquals(TaskStatus.COMPLETED, TaskStateMachine.getStatus(p))
            p.completes++
        }
        p.end(state, false)
        assertEquals(1, p.completes)
        assertFalse(TaskTimeoutManager.isTimedOut(p, Long.MAX_VALUE))
    }

    @Test fun `expired normal end fails even before watchdog has run`() {
        val p = probe()
        p.withTimeout(10)
        p.initialize(state)
        synchronized(TaskTimeoutManager) {
            RobotClock.useMockTime(11)
            p.end(state, false)
        }
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(p))
        assertEquals(0, p.completes)
        assertEquals(1, p.failures)
    }

    @Test fun `normal end preserves existing failure and cancellation`() {
        for (status in listOf(TaskStatus.FAILED, TaskStatus.CANCELLED)) {
            val p = probe()
            p.initialize(state)
            TaskStateMachine.transitionTo(p, status)
            p.end(state, false)
            assertEquals(status, TaskStateMachine.getStatus(p))
            assertEquals(0, p.completes)
        }
    }

    @Test fun `interrupted end disarms watchdog and preserves failure`() {
        for (failed in listOf(false, true)) {
            val p = probe()
            p.withTimeout(10)
            p.initialize(state)
            if (failed) TaskStateMachine.markFailed(p)
            p.end(state, true)
            TaskTimeoutManager.runWatchdogCheck(11)
            assertEquals(if (failed) TaskStatus.FAILED else TaskStatus.CANCELLED, TaskStateMachine.getStatus(p))
            assertFalse(TaskTimeoutManager.isTimedOut(p, Long.MAX_VALUE))
        }
    }

    @Test fun `executor rejects overdue completion without evaluating predicate or starting queue`() {
        val p = probe()
        p.withTimeout(10)
        val queued = probe()
        val e = TaskExecutor()
        e.addTask(p); e.addTask(queued)
        e.update(state, 0)
        p.ready = true
        e.update(state, 11) // Clock remains zero: this exercises executor elapsed, not watchdog timing.
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(p))
        assertEquals(1, p.predicates)
        assertEquals(listOf(true), p.endings)
        assertEquals(1, p.failures)
        assertEquals(0, queued.starts)
        assertEquals(0, e.size)
    }

    @Test fun `sequential child cannot complete after its timeout`() { overdueChild(factories[0]) }
    @Test fun `parallel child cannot complete after its timeout`() { overdueChild(factories[1]) }
    @Test fun `race child cannot win after its timeout`() { overdueChild(factories[2]) }
    @Test fun `deadline child cannot complete after its timeout`() { overdueChild(factories[3]) }
    private fun overdueChild(factory: (Task) -> Task) {
        val p = probe()
        p.withTimeout(10)
        val group = own(factory(p))
        val e = TaskExecutor()
        e.addTask(group)
        e.update(state, 0)
        p.ready = true
        e.update(state, 11)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(group))
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(p))
        assertEquals(0, p.completes)
        assertEquals(1, p.failures)
        assertEquals(listOf(true), p.endings)
        assertEquals(0, e.size)
    }

    @Test fun `predicate failure returning true cannot be promoted to group success`() {
        for (factory in factories) {
            val p = own(object : Probe() {
                override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
                    TaskStateMachine.markFailed(this)
                    return true
                }
            })
            p.onComplete { p.completes++ }
            val group = own(factory(p))
            val e = TaskExecutor()
            e.addTask(group); e.update(state, 0)
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(group))
            assertEquals(0, p.completes)
            assertEquals(listOf(true), p.endings)
        }
    }

    @Test fun `end crossing deadline cannot advance executor or group`() {
        for (factory in listOf<(Task) -> Task>({ it }) + factories) {
            RobotClock.useMockTime(0)
            val p = own(object : Probe() {
                override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
                    if (!interrupted) RobotClock.useMockTime(11)
                    return super.end(state, interrupted)
                }
            })
            p.withTimeout(10); p.ready = true
            val task = own(factory(p))
            val queued = probe()
            val e = TaskExecutor()
            e.addTask(task); e.addTask(queued)
            synchronized(TaskTimeoutManager) { e.update(state, 0) }
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
            assertEquals(listOf(false, true), p.endings, "rejected normal end still needs interrupted cleanup")
            assertEquals(0, queued.starts)
            assertEquals(0, e.size)
        }
    }

    @Test fun `exact timeout equality still allows completion and starts next task`() {
        for (factory in listOf<(Task) -> Task>({ it }) + factories) {
            RobotClock.useMockTime(0)
            val p = probe(); p.withTimeout(10)
            val task = own(factory(p))
            val queued = probe()
            val e = TaskExecutor(); e.addTask(task); e.addTask(queued)
            e.update(state, 0)
            p.ready = true
            RobotClock.useMockTime(10)
            e.update(state, 10)
            assertEquals(TaskStatus.COMPLETED, TaskStateMachine.getStatus(task))
            assertEquals(1, p.completes)
            assertEquals(1, queued.starts)
            e.cancelAll(state)
        }
    }

    @Test fun `predicate crossing deadline fails before normal end`() {
        val p = own(object : Probe() {
            override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
                RobotClock.useMockTime(11)
                return true
            }
        })
        p.withTimeout(10)
        val e = TaskExecutor(); e.addTask(p)
        synchronized(TaskTimeoutManager) { e.update(state, 0) }
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(p))
        assertEquals(listOf(true), p.endings)
    }

    @Test fun `group normal end exception still interrupts child exactly once`() {
        for (factory in factories) {
            val p = own(object : Probe() {
                override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
                    val actions = super.end(state, interrupted)
                    if (!interrupted) error("normal cleanup failed")
                    return actions
                }
            })
            p.ready = true
            val group = own(factory(p))
            val e = TaskExecutor(); e.addTask(group); e.update(state, 0)
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(group))
            assertEquals(listOf(false, true), p.endings)
            assertEquals(0, e.size)
        }
    }

    @Test fun `completion callback exception cannot leave a watchdog registration`() {
        val p = probe(); p.withTimeout(10); p.initialize(state)
        val failure = IllegalStateException("callback")
        p.onComplete { throw failure }
        assertSame(failure, assertFailsWith<IllegalStateException> { p.end(state, false) })
        TaskTimeoutManager.runWatchdogCheck(11)
        assertFalse(TaskTimeoutManager.isTimedOut(p, Long.MAX_VALUE))
        assertEquals(TaskStatus.COMPLETED, TaskStateMachine.getStatus(p))
    }

    @Test fun `parent timeout during child predicate prevents successor initialization`() {
        val child = own(object : Probe() {
            override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
                RobotClock.useMockTime(11)
                return true
            }
        })
        val next = probe()
        val group = own(SequentialTaskGroup(listOf(child, next)))
        group.withTimeout(10)
        val e = TaskExecutor(); e.addTask(group)
        synchronized(TaskTimeoutManager) { e.update(state, 0) }
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(group))
        assertEquals(0, next.starts)
        assertEquals(listOf(true), child.endings)
    }
}
