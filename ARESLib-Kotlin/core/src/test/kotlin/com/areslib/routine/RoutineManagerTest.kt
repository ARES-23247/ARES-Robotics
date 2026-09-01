package com.areslib.routine

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.sequencer.ActionDispatchTask
import com.areslib.sequencer.Task
import com.areslib.state.RobotState
import com.areslib.state.RoutineExecutionStatus
import com.areslib.util.RobotClock
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RoutineManagerTest {
    private data class TestAction(
        val value: String,
        override val timestampMs: Long = RobotClock.currentTimeMillis()
    ) : RobotAction

    private lateinit var store: Store
    private lateinit var actions: MutableList<RobotAction>
    private lateinit var manager: RoutineManager

    @BeforeEach
    fun setUp() {
        RobotClock.useMockTime(1_000L)
        actions = mutableListOf()
        store = Store()
        store.actionListener = { action -> actions += action }
        manager = RoutineManager(
            bindings = RoutineRuntimeBindings(
                createActionTask = { key, _ ->
                    when (key) {
                        "test.instant" -> ActionDispatchTask(TestAction("started"))
                        "test.hold" -> HoldTask()
                        "test.emitHold" -> EmittingHoldTask()
                        else -> null
                    }
                },
                createCondition = { key, _ ->
                    when (key) {
                        "always.true" -> { _ -> true }
                        else -> null
                    }
                },
                isActionKnown = { it == "test.instant" || it == "test.hold" || it == "test.emitHold" },
                isConditionKnown = { it == "always.true" },
                resourcesForAction = { key -> if (key == "test.hold") setOf("intake") else emptySet() }
            ),
            stateProvider = { store.state },
            dispatch = store::dispatch
        )
    }

    @AfterEach
    fun tearDown() {
        RobotClock.useSystemTime()
    }

    @Test
    fun `instant routine exposes request step and completion through Redux`() {
        manager.register(
            RoutineDocument(
                documentId = "instant",
                name = "Instant",
                steps = listOf(RoutineStep.action("test.instant"))
            )
        )

        val result = manager.request("instant")
        manager.update()

        assertTrue(result is RoutineRequestResult.Accepted)
        assertTrue(actions.any { it is RobotAction.RoutineRequested })
        assertTrue(actions.any { it is RobotAction.RoutineStepEntered })
        assertTrue(actions.any { it is TestAction && it.value == "started" })
        assertEquals(RoutineExecutionStatus.COMPLETED, store.state.routineState.lastTerminalExecution?.status)
        assertTrue(store.state.routineState.executions.isEmpty())
    }

    @Test
    fun `steady running routine manager update allocates zero bytes after warmup`() {
        val allocationBean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return
        if (!allocationBean.isThreadAllocatedMemorySupported) return
        if (!allocationBean.isThreadAllocatedMemoryEnabled) allocationBean.isThreadAllocatedMemoryEnabled = true
        manager.register(
            RoutineDocument(
                documentId = "allocation-hold",
                name = "Allocation hold",
                steps = listOf(RoutineStep.action("test.hold")),
            )
        )
        manager.request("allocation-hold")
        repeat(2_000) { manager.update() }

        val threadId = Thread.currentThread().id
        var consecutiveZeroWindows = 0
        var attempts = 0
        var lastAllocatedBytes = -1L
        while (attempts < 8 && consecutiveZeroWindows < 2) {
            val before = allocationBean.getThreadAllocatedBytes(threadId)
            repeat(10_000) { manager.update() }
            lastAllocatedBytes = allocationBean.getThreadAllocatedBytes(threadId) - before
            consecutiveZeroWindows = if (lastAllocatedBytes == 0L) consecutiveZeroWindows + 1 else 0
            attempts++
        }

        assertEquals(
            2,
            consecutiveZeroWindows,
            "routine manager never reached two zero-allocation windows; last=$lastAllocatedBytes bytes/10,000 updates",
        )
    }

    @Test
    fun `cancellation dispatches cleanup before its terminal lifecycle action`() {
        manager.register(
            RoutineDocument(
                documentId = "hold",
                name = "Hold",
                steps = listOf(RoutineStep.action("test.hold"))
            )
        )
        val result = manager.request("hold") as RoutineRequestResult.Accepted
        manager.update()
        actions.clear()

        assertTrue(manager.cancel(result.executionId, "manual override"))

        val cleanupIndex = actions.indexOfFirst { it is TestAction && it.value == "cleanup" }
        val cancelledIndex = actions.indexOfFirst { it is RobotAction.RoutineCancelled }
        assertTrue(cleanupIndex >= 0)
        assertTrue(cancelledIndex > cleanupIndex)
        assertEquals(RoutineExecutionStatus.CANCELLED, store.state.routineState.lastTerminalExecution?.status)
    }

    @Test
    fun `queue waits for active work and parallel requests reject resource conflicts`() {
        val hold = RoutineDocument(documentId = "hold", name = "Hold", steps = listOf(RoutineStep.action("test.hold")))
        val second = RoutineDocument(documentId = "second", name = "Second", steps = listOf(RoutineStep.action("test.hold")))
        manager.replaceDocuments(listOf(hold, second))

        manager.request("hold")
        manager.update()
        val queued = manager.request("second", RoutineStartPolicy.QUEUE)
        val rejected = manager.request("second", RoutineStartPolicy.PARALLEL)

        assertEquals(1, manager.activeCount)
        assertEquals(1, manager.queuedCount)
        assertTrue((queued as RoutineRequestResult.Accepted).queued)
        assertTrue(rejected is RoutineRequestResult.Rejected)
        assertTrue(actions.filterIsInstance<RobotAction.RoutineFailed>().any())
    }

    @Test
    fun `branch and bounded repeat compile into fresh task instances`() {
        manager.register(
            RoutineDocument(
                documentId = "repeat",
                name = "Repeat",
                steps = listOf(
                    RoutineStep.branch(
                        "always.true",
                        whenTrue = listOf(
                            RoutineStep.repeat(2, listOf(RoutineStep.action("test.instant")))
                        )
                    )
                )
            )
        )

        manager.request("repeat")
        repeat(6) { manager.update() }

        assertEquals(2, actions.filterIsInstance<TestAction>().count { it.value == "started" })
        assertEquals(RoutineExecutionStatus.COMPLETED, store.state.routineState.lastTerminalExecution?.status)
    }

    @Test
    fun `reentrant subscriber can cancel another active execution without duplicate terminal events`() {
        registerEmittingHolds()
        val first = manager.request("hold-a") as RoutineRequestResult.Accepted
        val second = manager.request("hold-b", RoutineStartPolicy.PARALLEL) as RoutineRequestResult.Accepted
        var reentered = false
        val unsubscribe = store.subscribe {
            if (!reentered && actions.lastOrNull() is TestAction) {
                reentered = true
                manager.cancel(second.executionId, "subscriber cancel")
            }
        }

        assertDoesNotThrow { manager.update() }
        unsubscribe()

        assertTrue(reentered)
        assertEquals(1, manager.activeCount)
        assertEquals(
            1,
            actions.filterIsInstance<RobotAction.RoutineCancelled>()
                .count { it.executionId == second.executionId }
        )
        assertTrue(manager.cancel(first.executionId))
    }

    @Test
    fun `reentrant subscriber can cancelAll during an update snapshot`() {
        registerEmittingHolds()
        val first = manager.request("hold-a") as RoutineRequestResult.Accepted
        val second = manager.request("hold-b", RoutineStartPolicy.PARALLEL) as RoutineRequestResult.Accepted
        var reentered = false
        val unsubscribe = store.subscribe {
            if (!reentered && actions.lastOrNull() is TestAction) {
                reentered = true
                assertEquals(2, manager.cancelAll("subscriber cancelAll"))
            }
        }

        assertDoesNotThrow { manager.update() }
        unsubscribe()

        assertEquals(0, manager.activeCount)
        val cancellations = actions.filterIsInstance<RobotAction.RoutineCancelled>()
        assertEquals(1, cancellations.count { it.executionId == first.executionId })
        assertEquals(1, cancellations.count { it.executionId == second.executionId })
    }

    @Test
    fun `reentrant subscriber can request a new routine during update dispatch`() {
        registerEmittingHolds()
        manager.register(
            RoutineDocument(
                documentId = "instant-reentrant",
                name = "Instant Reentrant",
                steps = listOf(RoutineStep.action("test.instant"))
            )
        )
        manager.request("hold-a")
        manager.request("hold-b", RoutineStartPolicy.PARALLEL)
        var nestedResult: RoutineRequestResult.Accepted? = null
        val unsubscribe = store.subscribe {
            if (nestedResult == null && actions.lastOrNull() is TestAction) {
                nestedResult = manager.request(
                    "instant-reentrant",
                    RoutineStartPolicy.PARALLEL
                ) as RoutineRequestResult.Accepted
            }
        }

        assertDoesNotThrow { manager.update() }
        unsubscribe()
        assertDoesNotThrow { manager.update() }

        val nestedId = requireNotNull(nestedResult).executionId
        assertEquals(
            1,
            actions.filterIsInstance<RobotAction.RoutineCompleted>().count { it.executionId == nestedId }
        )
        manager.cancelAll()
    }

    private fun registerEmittingHolds() {
        manager.replaceDocuments(
            listOf(
                RoutineDocument(
                    documentId = "hold-a",
                    name = "Hold A",
                    steps = listOf(RoutineStep.action("test.emitHold"))
                ),
                RoutineDocument(
                    documentId = "hold-b",
                    name = "Hold B",
                    steps = listOf(RoutineStep.action("test.emitHold"))
                )
            )
        )
    }

    private class HoldTask : Task {
        override val name: String = "Hold"
        override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean = false
        override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
            super.end(state, interrupted)
            return listOf(TestAction("cleanup"))
        }
    }

    private class EmittingHoldTask : Task {
        override val name: String = "EmittingHold"
        override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean = false
        override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
            super.execute(state, elapsedMs)
            return listOf(TestAction("tick"))
        }
        override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
            super.end(state, interrupted)
            return listOf(TestAction("cleanup"))
        }
    }
}
