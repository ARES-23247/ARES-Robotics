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
import org.junit.jupiter.api.Assertions.assertNotNull
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
        assertTrue(machine(store).lastRejectionReason.orEmpty().contains("guard 'ready'"))

        store.dispatch(RobotAction.UpdateNamedSubsystemState(MECHANISM_ID, mechanism(store).copy(measured = 1.0)))
        runtime.readSensors(store, 20L)
        assertEquals("STOW", machine(store).currentStateId)
        assertEquals("activate", machine(store).candidateTransitionId)

        runtime.readSensors(store, 45L)
        assertEquals("ACTIVE", machine(store).currentStateId)
        assertEquals(null, machine(store).lastRejectionReason)
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
    fun `automatic transitions use explicit priority instead of document order`() {
        val ready = TransitionGuard(
            guardId = "ready",
            source = SuperstructureFieldReference(MECHANISM_ID, "measured"),
            comparison = InterlockComparison.GREATER_THAN,
            expectedDoubleValue = 0.5,
        )
        val prioritized = document().copy(
            states = document().states + preset("ALTERNATE", 0.25),
            transitions = document().transitions + listOf(
                StateTransitionEdge(
                    transitionId = "lower-precedence-first-in-json",
                    sourceStateId = "STOW",
                    targetStateId = "ACTIVE",
                    triggerKind = TransitionTriggerKind.SENSOR_CONDITION_AUTO,
                    guards = listOf(ready),
                    priority = 20,
                ),
                StateTransitionEdge(
                    transitionId = "higher-precedence-second-in-json",
                    sourceStateId = "STOW",
                    targetStateId = "ALTERNATE",
                    triggerKind = TransitionTriggerKind.SENSOR_CONDITION_AUTO,
                    guards = listOf(ready.copy(guardId = "also-ready")),
                    priority = 10,
                ),
            ),
        )
        val runtime = SuperstructureRuntime(prioritized, FakeBinding())
        val store = store(FakeMechanismState(measured = 1.0))

        runtime.readSensors(store, 1L)

        assertEquals("ALTERNATE", machine(store).currentStateId)
        assertEquals(0.25, mechanism(store).target)
    }

    @Test
    fun `fault automation preempts a pending normal request`() {
        val unsafe = TransitionGuard(
            guardId = "unsafe",
            source = SuperstructureFieldReference(MECHANISM_ID, "measured"),
            comparison = InterlockComparison.LESS_THAN,
            expectedDoubleValue = -0.5,
        )
        val failSafe = document().copy(
            transitions = document().transitions + StateTransitionEdge(
                transitionId = "unsafe-to-fault",
                sourceStateId = "STOW",
                targetStateId = "FAULT",
                triggerKind = TransitionTriggerKind.SENSOR_CONDITION_AUTO,
                guards = listOf(unsafe),
                priority = 0,
            ),
        )
        val runtime = SuperstructureRuntime(failSafe, FakeBinding())
        val store = store(FakeMechanismState(measured = -1.0))
        dispatch(store, SuperstructureRuntime.requestTask(ID, "STOW", "machine.activate"), 10L)

        runtime.readSensors(store, 10L)

        assertEquals("FAULT", machine(store).currentStateId)
        assertTrue(machine(store).isFaulted)
        assertTrue(machine(store).faultReason.orEmpty().contains("unsafe-to-fault"))
        assertEquals(null, machine(store).pendingActionKey)
    }

    @Test
    fun `state timeout into fault always reports a fault`() {
        val timed = document().copy(
            states = document().states.map { state ->
                if (state.stateId == "STOW") state.copy(timeoutSeconds = 0.01, timeoutTargetStateId = "FAULT")
                else state
            },
        )
        val runtime = SuperstructureRuntime(timed, FakeBinding())
        val store = store()
        runtime.readSensors(store, 1L)

        runtime.readSensors(store, 20L)

        assertEquals("FAULT", machine(store).currentStateId)
        assertTrue(machine(store).isFaulted)
        assertTrue(machine(store).faultReason.orEmpty().contains("timed out"))
    }

    @Test
    fun `disabled robot enters neutral state and rejects requests until enabled`() {
        val binding = FakeBinding(enabled = false)
        val runtime = SuperstructureRuntime(document(), binding)
        val store = store(FakeMechanismState(target = 0.7, measured = 1.0))

        runtime.readSensors(store, 1L)
        assertEquals("FAULT", machine(store).currentStateId)
        assertTrue(machine(store).isDisabled)
        assertFalse(machine(store).isFaulted)
        assertEquals(0.0, mechanism(store).target)

        dispatch(store, SuperstructureRuntime.requestTask(ID, "STOW", "machine.recover"), 2L)
        runtime.readSensors(store, 2L)
        assertTrue(machine(store).lastRejectionReason.orEmpty().contains("disabled"))
        assertEquals(null, machine(store).pendingActionKey)

        binding.enabled = true
        dispatch(store, SuperstructureRuntime.requestTask(ID, "STOW", "machine.recover"), 3L)
        runtime.readSensors(store, 3L)
        assertEquals("STOW", machine(store).currentStateId)
        assertFalse(machine(store).isDisabled)
    }

    @Test
    fun `duplicate automatic priorities fail validation`() {
        val ready = TransitionGuard(
            guardId = "ready",
            source = SuperstructureFieldReference(MECHANISM_ID, "measured"),
            comparison = InterlockComparison.GREATER_THAN,
            expectedDoubleValue = 0.5,
        )
        val invalid = document().copy(
            transitions = document().transitions + listOf(
                StateTransitionEdge("auto-one", "STOW", "ACTIVE", TransitionTriggerKind.SENSOR_CONDITION_AUTO, guards = listOf(ready), priority = 4),
                StateTransitionEdge("auto-two", "STOW", "FAULT", TransitionTriggerKind.SENSOR_CONDITION_AUTO, guards = listOf(ready.copy(guardId = "ready-two")), priority = 4),
            ),
        )

        val issue = validateSuperstructureDocument(invalid).firstOrNull { it.path.endsWith("priority") }

        assertNotNull(issue)
        assertTrue(issue!!.message.contains("unique priorities"))
    }

    @Test
    fun `stale cached evidence cannot satisfy a transition guard`() {
        val binding = FakeBinding(healthBits = SuperstructurePortHealthBits.VALID)
        val runtime = SuperstructureRuntime(document(), binding)
        val store = store(FakeMechanismState(measured = 1.0))
        runtime.readSensors(store, 1L)
        dispatch(store, SuperstructureRuntime.requestTask(ID, "STOW", "machine.activate"), 10L)

        runtime.readSensors(store, 10L)

        assertEquals("STOW", machine(store).currentStateId)
        assertEquals(null, machine(store).candidateTransitionId)
        assertEquals(0.0, mechanism(store).target)
    }

    @Test
    fun `unhealthy port enters its declared latched fallback before requests`() {
        val healthPolicy = SuperstructureHealthFallbackPolicy(
            policyId = "arm-feedback",
            source = SuperstructureFieldReference(MECHANISM_ID, "measured"),
            fallbackStateId = "FAULT",
        )
        val binding = FakeBinding(healthBits = 0)
        val runtime = SuperstructureRuntime(document().copy(healthFallbacks = listOf(healthPolicy)), binding)
        val store = store(FakeMechanismState(target = 0.5, measured = 1.0))

        runtime.readSensors(store, 1L)

        assertEquals("FAULT", machine(store).currentStateId)
        assertTrue(machine(store).isFaulted)
        assertTrue(machine(store).faultReason.orEmpty().contains("arm-feedback"))
        assertEquals(0.0, mechanism(store).target)
    }

    @Test
    fun `every control ready health dimension fails closed`() {
        val requiredBits = listOf(
            SuperstructurePortHealthBits.VALID,
            SuperstructurePortHealthBits.FRESH,
            SuperstructurePortHealthBits.CONFIGURED,
            SuperstructurePortHealthBits.HOMED,
            SuperstructurePortHealthBits.CALIBRATED,
            SuperstructurePortHealthBits.CURRENT_VALID,
            SuperstructurePortHealthBits.OUTPUT_HEALTHY,
        )
        val guarded = document().copy(
            healthFallbacks = listOf(
                SuperstructureHealthFallbackPolicy(
                    policyId = "arm-feedback",
                    source = SuperstructureFieldReference(MECHANISM_ID, "measured"),
                    fallbackStateId = "FAULT",
                )
            ),
        )

        requiredBits.forEach { missing ->
            val runtime = SuperstructureRuntime(
                guarded,
                FakeBinding(healthBits = SuperstructurePortHealthBits.CONTROL_READY_MASK and missing.inv()),
            )
            val store = store(FakeMechanismState(target = 0.5, measured = 1.0))

            runtime.readSensors(store, 1L)

            assertEquals("FAULT", machine(store).currentStateId, "missing health bit $missing")
            assertEquals(0.0, mechanism(store).target, "missing health bit $missing")
        }
    }

    @Test
    fun `multi-target preset is preflighted before any action is initialized`() {
        val twoTargets = document().copy(
            states = document().states.map { preset ->
                preset.copy(
                    subsystemTargets = preset.subsystemTargets + SuperstructureSubsystemTarget(
                        target = SuperstructureFieldReference(MECHANISM_ID, "target2"),
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
    fun `lifecycle actions execute exit before entry exactly once per transition`() {
        val lifecycleDocument = document().copy(
            states = document().states.map { preset ->
                when (preset.stateId) {
                    "STOW" -> preset.copy(onExitActionKeys = listOf("arm.stop"))
                    "ACTIVE" -> preset.copy(onEntryActionKeys = listOf("indicator.active"))
                    else -> preset
                }
            },
        )
        val binding = FakeBinding()
        val runtime = SuperstructureRuntime(lifecycleDocument, binding)
        val store = store(FakeMechanismState(measured = 1.0))
        runtime.readSensors(store, 1L)
        dispatch(store, SuperstructureRuntime.requestTask(ID, "STOW", "machine.activate"), 2L)

        runtime.readSensors(store, 2L)
        runtime.readSensors(store, 22L)
        runtime.readSensors(store, 23L)

        assertEquals(listOf("arm.stop", "indicator.active"), binding.executedLifecycleActions)
        assertEquals(machine(store).transitionSequence, machine(store).lifecycleSequenceCompleted)
    }

    @Test
    fun `unavailable lifecycle action faults without duplicate execution`() {
        val lifecycleDocument = document().copy(
            states = document().states.map { preset ->
                if (preset.stateId == "ACTIVE") preset.copy(onEntryActionKeys = listOf("missing.action")) else preset
            },
        )
        val binding = FakeBinding(unavailableLifecycleAction = "missing.action")
        val runtime = SuperstructureRuntime(lifecycleDocument, binding)
        val store = store(FakeMechanismState(measured = 1.0))
        runtime.readSensors(store, 1L)
        dispatch(store, SuperstructureRuntime.requestTask(ID, "STOW", "machine.activate"), 2L)

        runtime.readSensors(store, 2L)
        runtime.readSensors(store, 22L)
        runtime.readSensors(store, 23L)

        assertEquals("FAULT", machine(store).currentStateId)
        assertTrue(machine(store).isFaulted)
        assertTrue(machine(store).faultReason.orEmpty().contains("missing.action"))
        assertTrue(binding.executedLifecycleActions.isEmpty())
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
                target = SuperstructureFieldReference(MECHANISM_ID, "target"),
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
        private val unavailableLifecycleAction: String? = null,
        var enabled: Boolean = true,
        var healthBits: Int = SuperstructurePortHealthBits.CONTROL_READY_MASK,
    ) : SuperstructureRuntimeBinding {
        var createdTargetTasks = 0
        var dispatchedTargetTasks = 0
        val executedLifecycleActions = mutableListOf<String>()

        override fun isRobotEnabled(): Boolean = enabled

        override fun resolvePort(subsystemUid: String, fieldUid: String): Int = when {
            subsystemUid != MECHANISM_ID -> -1
            fieldUid == "target" -> TARGET_PORT
            fieldUid == "target2" -> TARGET_2_PORT
            fieldUid == "measured" -> MEASURED_PORT
            else -> -1
        }

        override fun portType(port: Int): SubsystemValueType? =
            if (port == TARGET_PORT || port == TARGET_2_PORT) {
                SubsystemValueType.DOUBLE
            } else null

        override fun readNumeric(port: Int, state: RobotState): Double {
            val snapshot = state.superstructure.subsystems[MECHANISM_ID] as? FakeMechanismState
                ?: return Double.NaN
            return when (port) {
                TARGET_PORT -> snapshot.target
                TARGET_2_PORT -> snapshot.target2
                MEASURED_PORT -> snapshot.measured
                else -> Double.NaN
            }
        }

        override fun readBoolean(port: Int, state: RobotState): Boolean? = null

        override fun readString(port: Int, state: RobotState): String? = null

        override fun readHealthBits(port: Int, state: RobotState, nowMs: Long): Int = healthBits

        override fun createDoubleTargetTask(
            port: Int,
            value: Double,
        ): Task? {
            val fieldId = when (port) {
                TARGET_PORT -> "target"
                TARGET_2_PORT -> "target2"
                else -> return null
            }
            if (rejectTargets || fieldId == rejectedFieldId
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

        override fun createIntTargetTask(port: Int, value: Int): Task? = null

        override fun createBooleanTargetTask(port: Int, value: Boolean): Task? = null

        override fun createStringTargetTask(port: Int, value: String): Task? = null

        override fun createLifecycleActionTask(actionKey: String, timestampMs: Long): Task? {
            if (actionKey == unavailableLifecycleAction) return null
            return object : Task {
                override val name: String = "Lifecycle($actionKey)"

                override fun initialize(state: RobotState): List<RobotAction> {
                    super.initialize(state)
                    executedLifecycleActions += actionKey
                    return emptyList()
                }

                override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean = true
            }
        }

        private companion object {
            const val TARGET_PORT = 0
            const val TARGET_2_PORT = 1
            const val MEASURED_PORT = 2
        }
    }

    private companion object {
        const val ID = "main-machine"
        const val MECHANISM_ID = "arm"
    }
}
