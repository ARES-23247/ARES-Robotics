package com.areslib.superstructure

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.reducer.rootReducer
import com.areslib.sequencer.StateActionTask
import com.areslib.sequencer.Task
import com.areslib.state.RobotState
import com.areslib.state.SubsystemState
import com.areslib.state.SuperstructureState
import com.areslib.subsystem.InterlockComparison
import com.areslib.subsystem.SubsystemValueType
import com.areslib.util.RobotClock
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuperstructureRuntimeTest {
    @AfterEach
    fun restoreClock() {
        RobotClock.useSystemTime()
    }

    @Test
    fun `guarded requests debounce once and publish generated subsystem targets`() {
        val binding = FakeBinding()
        val runtime = SuperstructureRuntime(document(), binding)
        val store = store(FakeMechanismState(measured = 0.0))

        runtime.readSensors(store, 1L)
        assertEquals(0.0, mechanism(store).target)

        dispatch(store, SuperstructureRuntime.requestTask(ID, "STOW", "machine.activate"), 10L)
        runtime.readSensors(store, 10L)
        assertEquals("STOW", machine(store).currentStateId)

        store.dispatch(RobotAction.UpdateNamedSubsystemState(MECHANISM_ID, mechanism(store).copy(measured = 1.0)))
        runtime.readSensors(store, 20L)
        assertEquals("STOW", machine(store).currentStateId)
        assertEquals("activate", machine(store).candidateTransitionId)

        runtime.readSensors(store, 45L)
        assertEquals("ACTIVE", machine(store).currentStateId)
        assertEquals(0.75, mechanism(store).target)
        assertEquals(2, binding.createdTargetTasks)
    }

    @Test
    fun `pending request timeout enters and applies neutral fault preset`() {
        val binding = FakeBinding()
        val runtime = SuperstructureRuntime(document(), binding)
        val store = store(FakeMechanismState(target = 0.4, measured = 0.0))
        runtime.readSensors(store, 1L)
        dispatch(store, SuperstructureRuntime.requestTask(ID, "STOW", "machine.activate"), 10L)

        runtime.readSensors(store, 250L)
        assertEquals("FAULT", machine(store).currentStateId)
        assertTrue(machine(store).isFaulted)
        assertTrue(machine(store).faultReason.orEmpty().contains("timed out"))
        assertEquals(0.0, mechanism(store).target)
    }

    @Test
    fun `missing generated target task fails closed before any target dispatch`() {
        val binding = FakeBinding(rejectTargets = true)
        val runtime = SuperstructureRuntime(document(), binding)
        val store = store(FakeMechanismState(target = 0.6, measured = 1.0))

        runtime.readSensors(store, 1L)

        assertEquals("FAULT", machine(store).currentStateId)
        assertTrue(machine(store).isFaulted)
        assertEquals(0.6, mechanism(store).target)
        assertEquals(0, binding.dispatchedTargetTasks)
    }

    @Test
    fun `unavailable action is rejected without changing state or outputs`() {
        val binding = FakeBinding()
        val runtime = SuperstructureRuntime(document(), binding)
        val store = store()
        runtime.readSensors(store, 1L)
        dispatch(store, SuperstructureRuntime.requestTask(ID, "STOW", "machine.unknown"), 2L)

        runtime.readSensors(store, 2L)

        assertEquals("STOW", machine(store).currentStateId)
        assertFalse(machine(store).isFaulted)
        assertTrue(machine(store).lastRejectionReason.orEmpty().contains("not available"))
        assertEquals(0.0, mechanism(store).target)
    }

    @Test
    fun `one loop performs at most one transition`() {
        val base = document()
        val readyGuard = base.transitions.first { it.transitionId == "activate" }.guards.single()
        val immediate = base.copy(
            transitions = base.transitions.map { edge ->
                if (edge.transitionId == "activate") edge.copy(debounceMs = 0L) else edge
            } + StateTransitionEdge(
                transitionId = "automatic-stow",
                sourceStateId = "ACTIVE",
                targetStateId = "STOW",
                triggerKind = TransitionTriggerKind.SENSOR_CONDITION_AUTO,
                guards = listOf(readyGuard),
            ),
        )
        val runtime = SuperstructureRuntime(immediate, FakeBinding())
        val store = store(FakeMechanismState(measured = 1.0))
        runtime.readSensors(store, 1L)
        dispatch(store, SuperstructureRuntime.requestTask(ID, "STOW", "machine.activate"), 10L)

        runtime.readSensors(store, 10L)
        assertEquals("ACTIVE", machine(store).currentStateId)

        runtime.readSensors(store, 11L)
        assertEquals("STOW", machine(store).currentStateId)
    }

    @Test
    fun `multi-target preset is preflighted before any action is initialized`() {
        val twoTargets = document().copy(
            states = document().states.map { preset ->
                preset.copy(
                    subsystemTargets = preset.subsystemTargets + SuperstructureSubsystemTarget(
                        subsystemId = MECHANISM_ID,
                        fieldId = "target2",
                        constantDoubleValue = 0.0,
                    ),
                )
            },
        )
        val binding = FakeBinding(rejectedFieldId = "target2")
        val runtime = SuperstructureRuntime(twoTargets, binding)
        val store = store(FakeMechanismState(target = 0.6, target2 = 0.4))

        runtime.readSensors(store, 1L)

        assertEquals("FAULT", machine(store).currentStateId)
        assertEquals(0, binding.dispatchedTargetTasks)
        assertEquals(0.6, mechanism(store).target)
        assertEquals(0.4, mechanism(store).target2)
    }

    @Test
    fun `steady state evaluation remains allocation free after warmup`() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return
        if (!bean.isThreadAllocatedMemorySupported) return
        if (!bean.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true
        val runtime = SuperstructureRuntime(document(), FakeBinding())
        val store = store()
        runtime.readSensors(store, 1L)
        repeat(2_000) { runtime.readSensors(store, 2L + it) }
        val threadId = Thread.currentThread().id

        fun allocationWindow(startTimestampMs: Long): Long {
            val before = bean.getThreadAllocatedBytes(threadId)
            repeat(10_000) { runtime.readSensors(store, startTimestampMs + it) }
            return bean.getThreadAllocatedBytes(threadId) - before
        }

        // HotSpot may perform a fixed amount of tiered-compilation or allocation-probe
        // bookkeeping after the initial warmup, especially on fresh Linux CI workers. Require two
        // consecutive zero-allocation windows: a per-loop allocation can never satisfy this, while
        // one-time VM bookkeeping does not make an allocation-free periodic path look broken.
        var consecutiveZeroWindows = 0
        var window = 0
        while (window < 10 && consecutiveZeroWindows < 2) {
            val allocatedBytes = allocationWindow(10_000L + window * 20_000L)
            consecutiveZeroWindows = if (allocatedBytes == 0L) consecutiveZeroWindows + 1 else 0
            window++
        }
        assertEquals(2, consecutiveZeroWindows, "steady state never reached two zero-allocation windows")
    }

    private fun document() = SuperstructureDocument(
        superstructureId = ID,
        initialStateId = "STOW",
        faultStateId = "FAULT",
        states = listOf(
            preset("STOW", 0.0),
            preset("ACTIVE", 0.75),
            preset("FAULT", 0.0),
        ),
        transitions = listOf(
            StateTransitionEdge(
                transitionId = "activate",
                sourceStateId = "STOW",
                targetStateId = "ACTIVE",
                actionKey = "machine.activate",
                guards = listOf(
                    TransitionGuard(
                        guardId = "ready",
                        source = SuperstructureFieldReference(MECHANISM_ID, "measured"),
                        comparison = InterlockComparison.GREATER_THAN,
                        expectedDoubleValue = 0.5,
                    ),
                ),
                debounceMs = 20L,
                timeoutSeconds = 0.2,
                timeoutTargetStateId = "FAULT",
            ),
            StateTransitionEdge(
                transitionId = "recover",
                sourceStateId = "FAULT",
                targetStateId = "STOW",
                actionKey = "machine.recover",
            ),
            StateTransitionEdge(
                transitionId = "stop",
                sourceStateId = "ACTIVE",
                targetStateId = "STOW",
                actionKey = "machine.stop",
            ),
        ),
    )

    private fun preset(id: String, target: Double) = SuperstructureStatePreset(
        stateId = id,
        subsystemTargets = listOf(
            SuperstructureSubsystemTarget(
                subsystemId = MECHANISM_ID,
                fieldId = "target",
                constantDoubleValue = target,
            ),
        ),
    )

    private fun store(mechanism: FakeMechanismState = FakeMechanismState()): Store = Store(
        initialState = RobotState(
            superstructure = SuperstructureState(
                subsystems = mapOf(MECHANISM_ID to mechanism),
            ),
        ),
        reducer = ::rootReducer,
    )

    private fun dispatch(store: Store, task: Task, timestampMs: Long) {
        RobotClock.useMockTime(timestampMs)
        task.initialize(store.state).forEach(store::dispatch)
        task.releaseRuntimeState()
    }

    private fun machine(store: Store): SuperstructureRuntimeState =
        store.state.superstructure.subsystems.getValue(ID) as SuperstructureRuntimeState

    private fun mechanism(store: Store): FakeMechanismState =
        store.state.superstructure.subsystems.getValue(MECHANISM_ID) as FakeMechanismState

    private data class FakeMechanismState(
        val target: Double = 0.0,
        val target2: Double = 0.0,
        val measured: Double = 0.0,
    ) : SubsystemState

    private class FakeBinding(
        private val rejectTargets: Boolean = false,
        private val rejectedFieldId: String? = null,
    ) : SuperstructureRuntimeBinding {
        var createdTargetTasks = 0
        var dispatchedTargetTasks = 0

        override fun targetType(subsystemId: String, fieldId: String): SubsystemValueType? =
            if (subsystemId == MECHANISM_ID && (fieldId == "target" || fieldId == "target2")) {
                SubsystemValueType.DOUBLE
            } else null

        override fun readNumeric(subsystemId: String, fieldId: String, state: RobotState): Double {
            val snapshot = state.superstructure.subsystems[MECHANISM_ID] as? FakeMechanismState
                ?: return Double.NaN
            return when (fieldId) {
                "target" -> snapshot.target
                "target2" -> snapshot.target2
                "measured" -> snapshot.measured
                else -> Double.NaN
            }
        }

        override fun readBoolean(
            subsystemId: String,
            fieldId: String,
            state: RobotState,
        ): Boolean? = null

        override fun readString(
            subsystemId: String,
            fieldId: String,
            state: RobotState,
        ): String? = null

        override fun createDoubleTargetTask(
            subsystemId: String,
            fieldId: String,
            value: Double,
        ): Task? {
            if (rejectTargets || fieldId == rejectedFieldId || subsystemId != MECHANISM_ID ||
                (fieldId != "target" && fieldId != "target2")
            ) return null
            createdTargetTasks++
            return StateActionTask("Set fake mechanism") { state ->
                dispatchedTargetTasks++
                val current = state.superstructure.subsystems.getValue(MECHANISM_ID) as FakeMechanismState
                val next = when (fieldId) {
                    "target" -> current.copy(target = value)
                    else -> current.copy(target2 = value)
                }
                RobotAction.UpdateNamedSubsystemState(MECHANISM_ID, next)
            }
        }

        override fun createIntTargetTask(
            subsystemId: String,
            fieldId: String,
            value: Int,
        ): Task? = null

        override fun createBooleanTargetTask(
            subsystemId: String,
            fieldId: String,
            value: Boolean,
        ): Task? = null

        override fun createStringTargetTask(
            subsystemId: String,
            fieldId: String,
            value: String,
        ): Task? = null
    }

    private companion object {
        const val ID = "main-machine"
        const val MECHANISM_ID = "arm"
    }
}
