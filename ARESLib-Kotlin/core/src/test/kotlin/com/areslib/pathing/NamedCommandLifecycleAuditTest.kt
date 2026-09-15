package com.areslib.pathing

import com.areslib.action.RobotAction
import com.areslib.sequencer.*
import com.areslib.state.Alliance
import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class NamedCommandLifecycleAuditTest {
    private val state = RobotState()
    private val owned = mutableListOf<Task>()
    private val key = CommandKey("audit.named")
    private open class Probe : Task {
        override val name = "probe"
        override fun isCompleted(state: RobotState, elapsedMs: Long) = false
    }
    private fun wrap(task: Task): Task {
        owned += task
        NamedCommands.register(key, "Probe", requiredResources = task.requiredResources) { task }
        return NamedCommands.task(key).also(owned::add)
    }
    @BeforeEach fun start() { NamedCommands.clear(); RobotClock.useMockTime(1000) }
    @AfterEach fun cleanup() {
        owned.asReversed().forEach { runCatching { it.reset() } }
        NamedCommands.clear(); RobotClock.useSystemTime()
    }

    @Test fun `preemption forwards pause resume actions and suspends child deadline`() {
        val calls = mutableListOf<String>()
        val marker = RobotAction.SetAlliance(Alliance.RED, 1000)
        val child = object : Probe() {
            override fun pause(state: RobotState): List<RobotAction> { calls += "pause"; return listOf(marker) }
            override fun resume(state: RobotState): List<RobotAction> { calls += "resume"; return listOf(marker) }
        }.withTimeout(100)
        val task = wrap(child)
        val executor = TaskExecutor()
        try {
            executor.addTask(task); executor.update(state, 1000)
            assertEquals(listOf(marker), executor.preempt(TimeWaitTask(200).also(owned::add), state, 1000))
            RobotClock.useMockTime(1200); TaskTimeoutManager.runWatchdogCheck(1200)
            assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(child))
            assertEquals(listOf(marker), executor.update(state, 1200))
            assertEquals(listOf("pause", "resume"), calls)
        } finally { executor.cancelAll(state) }
    }

    @Test fun `executor suspension reaches deferred child watchdog`() {
        val child = Probe().withTimeout(100)
        val task = wrap(child); val executor = TaskExecutor()
        try {
            executor.addTask(task); executor.update(state, 1000); executor.suspend()
            RobotClock.useMockTime(5000); TaskTimeoutManager.runWatchdogCheck(5000)
            assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(child))
            executor.resume(); RobotClock.useMockTime(5101); TaskTimeoutManager.runWatchdogCheck(5101)
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(child))
        } finally { executor.cancelAll(state) }
    }

    @Test fun `failure during initialize is propagated before any execute`() {
        val task = wrap(object : Probe() {
            override fun initialize(state: RobotState): List<RobotAction> {
                super.initialize(state); TaskStateMachine.markFailed(this); return emptyList()
            }
        })
        task.initialize(state)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
    }

    @Test fun `failure discovered by completion cannot become successful completion`() {
        val task = wrap(object : Probe() {
            override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
                TaskStateMachine.markFailed(this); return true
            }
        })
        task.initialize(state)
        assertFalse(task.isCompleted(state, 0))
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
    }

    @Test fun `completion must respect child elapsed timeout without watchdog scheduling`() {
        val task = wrap(object : Probe() {
            override fun isCompleted(state: RobotState, elapsedMs: Long) = true
        }.withTimeout(10))
        task.initialize(state)
        assertFalse(task.isCompleted(state, 11))
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
    }

    @Test fun `cancelled child cannot execute or complete its parent`() {
        var executes = 0
        val child = object : Probe() {
            override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
                executes++; return super.execute(state, elapsedMs)
            }
        }
        val task = wrap(child); task.initialize(state); child.cancel()
        assertTrue(task.execute(state, 0).isEmpty())
        assertEquals(0, executes)
        assertEquals(TaskStatus.CANCELLED, TaskStateMachine.getStatus(task))
    }

    @Test fun `child failure at end prevents parent success callback`() {
        var completed = 0; var failed = 0
        val task = wrap(object : Probe() {
            override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
                TaskStateMachine.markFailed(this); return super.end(state, interrupted)
            }
        }).onComplete { completed++ }.onFail { failed++ }
        task.initialize(state); task.end(state, false)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
        assertEquals(0, completed); assertEquals(1, failed)
    }

    @Test fun `throwing child cleanup still releases parent metadata and reference`() {
        val failure = IllegalStateException("cleanup")
        var releases = 0; var callbacks = 0
        val task = wrap(object : Probe() {
            override fun releaseRuntimeState() { releases++; super.releaseRuntimeState(); throw failure }
        }).withTimeout(100).onFail { callbacks++ }
        task.initialize(state)
        assertSame(failure, assertFailsWith<IllegalStateException> { task.releaseRuntimeState() })
        task.releaseRuntimeState()
        RobotClock.useMockTime(2000); TaskTimeoutManager.runWatchdogCheck(2000); TaskCallbacks.invokeFail(task)
        assertEquals(1, releases); assertEquals(0, callbacks)
    }

    @Test fun `throwing child end still terminates parent without changing exception identity`() {
        val failure = AssertionError("child end")
        val task = wrap(object : Probe() {
            override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> = throw failure
        }).withTimeout(100)
        task.initialize(state)
        assertSame(failure, assertFailsWith<AssertionError> { task.end(state, false) })
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
        RobotClock.useMockTime(2000); TaskTimeoutManager.runWatchdogCheck(2000)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
    }

    @Test fun `catalog snapshots preserve revision ordering and lazy creation`() {
        var createdAt = -1L
        val before = NamedCommands.catalogRevision
        NamedCommands.register(key, "Probe") { createdAt = it; Probe().also(owned::add) }
        val catalog = NamedCommands.catalog()
        val task = NamedCommands.task(key).also(owned::add)
        assertEquals(-1L, createdAt)
        task.initialize(state); assertEquals(1000L, createdAt)
        NamedCommands.register(CommandKey("audit.second"), "Second") { Probe() }
        assertEquals(listOf(key), catalog.map { it.key })
        assertEquals(before + 2, NamedCommands.catalogRevision)
        assertNull(NamedCommands.getCommand("../invalid", 0))
    }

    @Test fun `failed child callback cannot prevent end cleanup actions`() {
        var failed = 0
        val marker = RobotAction.SetAlliance(Alliance.RED, 1000)
        val child = object : Probe() {
            override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
                assertTrue(interrupted); super.end(state, interrupted); return listOf(marker)
            }
        }.onFail { failed++; throw IllegalStateException("diagnostic callback") }
        val task = wrap(child); task.initialize(state); TaskStateMachine.markFailed(child)
        assertEquals(listOf(marker), task.end(state, false))
        assertEquals(1, failed)
        TaskCallbacks.invokeFail(child); assertEquals(1, failed)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
    }

    @Test fun `resource replacement rejects deferred task before child initialization`() {
        val task = wrap(Probe())
        var initialized = false
        NamedCommands.register(key, "Changed resources", requiredResources = TaskResources.DRIVE) {
            object : Probe() {
                override val requiredResources = TaskResources.DRIVE
                override fun initialize(state: RobotState): List<RobotAction> {
                    initialized = true; return super.initialize(state)
                }
            }.also(owned::add)
        }
        assertFailsWith<IllegalArgumentException> { task.initialize(state) }
        assertFalse(initialized)
    }
}
