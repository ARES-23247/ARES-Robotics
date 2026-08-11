package com.areslib.routine

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.sequencer.ActionDispatchTask
import com.areslib.sequencer.Task
import com.areslib.state.RobotState
import com.areslib.state.RoutineExecutionStatus
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
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
        store.actionListener = { action -> actions.add(action); Unit }
        manager = RoutineManager(
            bindings = RoutineRuntimeBindings(
                createActionTask = { key, _ ->
                    when (key) {
                        "test.instant" -> ActionDispatchTask(TestAction("started"))
                        "test.hold" -> HoldTask()
                        else -> null
                    }
                },
                createCondition = { key, _ ->
                    when (key) {
                        "always.true" -> { _ -> true }
                        else -> null
                    }
                },
                isActionKnown = { it == "test.instant" || it == "test.hold" },
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

    private class HoldTask : Task {
        override val name: String = "Hold"
        override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean = false
        override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
            super.end(state, interrupted)
            return listOf(TestAction("cleanup"))
        }
    }
}
