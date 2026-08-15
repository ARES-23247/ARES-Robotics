package com.areslib.superstructure

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.sequencer.StateActionTask
import com.areslib.sequencer.Task
import com.areslib.state.RobotState
import com.areslib.state.SubsystemState
import com.areslib.subsystem.InterlockComparison
import com.areslib.subsystem.Subsystem
import com.areslib.subsystem.SubsystemValueType
import com.areslib.util.RobotClock
import kotlin.math.abs

/**
 * Observable immutable state for one generated superstructure state machine.
 *
 * The runtime's transition cursor and pending request are published through Redux so replay,
 * telemetry, and simulator snapshots never share hidden mutable transition state.
 */
data class SuperstructureRuntimeState(
    val currentStateId: String,
    val previousStateId: String = currentStateId,
    val stateEntryTimestampMs: Long = Long.MIN_VALUE,
    val pendingActionKey: String? = null,
    val pendingActionTimestampMs: Long = 0L,
    val requestSequence: Long = 0L,
    val handledRequestSequence: Long = 0L,
    val candidateTransitionId: String? = null,
    val candidateSinceMs: Long = 0L,
    val lastAppliedTargetHash: Long = Long.MIN_VALUE,
    val isFaulted: Boolean = false,
    val faultReason: String? = null,
    val lastRejectionReason: String? = null,
) : SubsystemState

/**
 * Generated typed boundary between the generic state-machine evaluator and generated subsystem
 * state/action plumbing. Implementations must read only immutable cached Redux fields.
 */
interface SuperstructureRuntimeBinding {
    fun targetType(subsystemId: String, fieldId: String): SubsystemValueType?
    fun readNumeric(subsystemId: String, fieldId: String, state: RobotState): Double
    fun readBoolean(subsystemId: String, fieldId: String, state: RobotState): Boolean?
    fun readString(subsystemId: String, fieldId: String, state: RobotState): String?
    fun createDoubleTargetTask(subsystemId: String, fieldId: String, value: Double): Task?
    fun createIntTargetTask(subsystemId: String, fieldId: String, value: Int): Task?
    fun createBooleanTargetTask(subsystemId: String, fieldId: String, value: Boolean): Task?
    fun createStringTargetTask(subsystemId: String, fieldId: String, value: String): Task?
}

/**
 * Deterministic superstructure coordinator generated from a validated project document.
 *
 * It never writes hardware directly. Preset changes become the same typed generated-subsystem
 * Redux tasks used by controller bindings and autonomous routines. Target tasks are preflighted
 * before dispatch, transition guards read cached immutable state, and every failure enters the
 * document's explicit neutral fault preset.
 */
class SuperstructureRuntime(
    private val document: SuperstructureDocument,
    private val binding: SuperstructureRuntimeBinding,
) : Subsystem {
    private val statesById = document.states.associateBy { it.stateId }
    private val lutsById = document.luts.associateBy { it.lutId }
    private val maximumTargetCount = document.states.maxOfOrNull { it.subsystemTargets.size } ?: 0
    private val doubleTargets = DoubleArray(maximumTargetCount)
    private val intTargets = IntArray(maximumTargetCount)
    private val booleanTargets = BooleanArray(maximumTargetCount)
    private val stringTargets = arrayOfNulls<String>(maximumTargetCount)
    private val targetTypes = arrayOfNulls<SubsystemValueType>(maximumTargetCount)
    private val targetTasks = arrayOfNulls<Task>(maximumTargetCount)
    private val targetActions = arrayOfNulls<List<RobotAction>>(maximumTargetCount)
    private var resolvedTargetHash = FNV_OFFSET

    init {
        val issues = validateSuperstructureDocument(document)
            .filter { it.severity == SuperstructureIssueSeverity.ERROR }
        require(issues.isEmpty()) { issues.joinToString("; ") { "${it.path}: ${it.message}" } }
    }

    override fun readSensors(store: Store, timestampMs: Long) {
        val before = state(store.state, document.superstructureId, document.initialStateId)
        var next = before
        if (next.stateEntryTimestampMs == Long.MIN_VALUE) {
            next = next.copy(stateEntryTimestampMs = timestampMs)
        }
        val requestSourceStateId = next.currentStateId
        next = consumeRequest(next, store.state, timestampMs)
        if (next.currentStateId == requestSourceStateId && next.pendingActionKey == null) {
            next = evaluateAutomaticTransitions(next, store.state, timestampMs)
        }

        val preset = statesById[next.currentStateId]
        if (preset == null) {
            next = enterFault(next, timestampMs, "Current state '${next.currentStateId}' is not declared")
        }
        val activePreset = statesById.getValue(next.currentStateId)
        if (!resolveTargets(activePreset, store.state)) {
            next = enterFault(next, timestampMs, "A required cached target source is missing or invalid")
        } else if (resolvedTargetHash != next.lastAppliedTargetHash) {
            if (applyResolvedTargets(activePreset, store)) {
                next = next.copy(lastAppliedTargetHash = resolvedTargetHash)
            } else {
                next = enterFault(next, timestampMs, "Generated subsystem target plumbing rejected a preset")
            }
        }

        if (next.isFaulted && next.currentStateId == document.faultStateId &&
            next.lastAppliedTargetHash == Long.MIN_VALUE
        ) {
            val faultPreset = statesById.getValue(document.faultStateId)
            if (resolveTargets(faultPreset, store.state) && applyResolvedTargets(faultPreset, store)) {
                next = next.copy(lastAppliedTargetHash = resolvedTargetHash)
            }
        }
        if (next != before) {
            store.dispatch(RobotAction.UpdateNamedSubsystemState(document.superstructureId, next, timestampMs))
        }
    }

    override fun writeOutputs(state: RobotState, scale: Double) = Unit

    private fun consumeRequest(
        initial: SuperstructureRuntimeState,
        robotState: RobotState,
        nowMs: Long,
    ): SuperstructureRuntimeState {
        var state = initial
        if (state.requestSequence != state.handledRequestSequence) {
            state = state.copy(
                handledRequestSequence = state.requestSequence,
                candidateTransitionId = null,
                candidateSinceMs = 0L,
                lastRejectionReason = null,
            )
        }
        val actionKey = state.pendingActionKey ?: return state
        var edge: StateTransitionEdge? = null
        var transitionIndex = 0
        while (transitionIndex < document.transitions.size) {
            val candidate = document.transitions[transitionIndex]
            if (candidate.sourceStateId == state.currentStateId &&
                candidate.triggerKind == TransitionTriggerKind.ACTION_REQUEST &&
                candidate.actionKey == actionKey
            ) {
                edge = candidate
                break
            }
            transitionIndex++
        }
        if (edge == null) {
            return state.copy(
                pendingActionKey = null,
                pendingActionTimestampMs = 0L,
                candidateTransitionId = null,
                candidateSinceMs = 0L,
                lastRejectionReason = "Action '$actionKey' is not available from ${state.currentStateId}",
            )
        }
        if (guardsPass(edge, robotState)) {
            return advanceDebounce(state, edge, nowMs)
        }
        val timeoutMs = edge.timeoutSeconds?.secondsToMillis()
        if (timeoutMs != null && nowMs - state.pendingActionTimestampMs >= timeoutMs) {
            return enterState(
                state,
                requireNotNull(edge.timeoutTargetStateId),
                nowMs,
                faulted = edge.timeoutTargetStateId == document.faultStateId,
                reason = "Action '$actionKey' timed out while waiting for safe guards",
            )
        }
        return state.copy(candidateTransitionId = null, candidateSinceMs = 0L)
    }

    private fun evaluateAutomaticTransitions(
        initial: SuperstructureRuntimeState,
        robotState: RobotState,
        nowMs: Long,
    ): SuperstructureRuntimeState {
        if (initial.currentStateId != document.faultStateId && initial.isFaulted) return initial
        val preset = statesById.getValue(initial.currentStateId)
        val stateTimeoutMs = preset.timeoutSeconds?.secondsToMillis()
        if (stateTimeoutMs != null && nowMs - initial.stateEntryTimestampMs >= stateTimeoutMs) {
            return enterState(initial, requireNotNull(preset.timeoutTargetStateId), nowMs)
        }
        var transitionIndex = 0
        while (transitionIndex < document.transitions.size) {
            val edge = document.transitions[transitionIndex]
            if (edge.sourceStateId != initial.currentStateId) {
                transitionIndex++
                continue
            }
            when (edge.triggerKind) {
                TransitionTriggerKind.ACTION_REQUEST -> Unit
                TransitionTriggerKind.TIME_ELAPSED -> {
                    val elapsedMs = requireNotNull(edge.timeoutSeconds).secondsToMillis()
                    if (nowMs - initial.stateEntryTimestampMs >= elapsedMs) {
                        return advanceDebounce(initial, edge, nowMs)
                    }
                }
                TransitionTriggerKind.SENSOR_CONDITION_AUTO -> {
                    if (guardsPass(edge, robotState)) return advanceDebounce(initial, edge, nowMs)
                }
            }
            transitionIndex++
        }
        return if (initial.pendingActionKey == null && initial.candidateTransitionId != null) {
            initial.copy(candidateTransitionId = null, candidateSinceMs = 0L)
        } else initial
    }

    private fun advanceDebounce(
        state: SuperstructureRuntimeState,
        edge: StateTransitionEdge,
        nowMs: Long,
    ): SuperstructureRuntimeState {
        if (edge.debounceMs == 0L) return enterState(state, edge.targetStateId, nowMs)
        if (state.candidateTransitionId != edge.transitionId) {
            return state.copy(candidateTransitionId = edge.transitionId, candidateSinceMs = nowMs)
        }
        return if (nowMs - state.candidateSinceMs >= edge.debounceMs) {
            enterState(state, edge.targetStateId, nowMs)
        } else state
    }

    private fun enterState(
        state: SuperstructureRuntimeState,
        targetStateId: String,
        nowMs: Long,
        faulted: Boolean = false,
        reason: String? = null,
    ): SuperstructureRuntimeState = state.copy(
        currentStateId = targetStateId,
        previousStateId = state.currentStateId,
        stateEntryTimestampMs = nowMs,
        pendingActionKey = null,
        pendingActionTimestampMs = 0L,
        candidateTransitionId = null,
        candidateSinceMs = 0L,
        lastAppliedTargetHash = Long.MIN_VALUE,
        isFaulted = faulted,
        faultReason = if (faulted) reason else null,
        lastRejectionReason = if (faulted) reason else state.lastRejectionReason,
    )

    private fun enterFault(
        state: SuperstructureRuntimeState,
        nowMs: Long,
        reason: String,
    ): SuperstructureRuntimeState = enterState(
        state = state,
        targetStateId = document.faultStateId,
        nowMs = nowMs,
        faulted = true,
        reason = reason,
    )

    private fun guardsPass(edge: StateTransitionEdge, state: RobotState): Boolean {
        var guardIndex = 0
        while (guardIndex < edge.guards.size) {
            val guard = edge.guards[guardIndex]
            val matches = when {
                guard.expectedDoubleValue != null -> {
                    val actual = binding.readNumeric(guard.source.subsystemId, guard.source.fieldId, state)
                    if (!actual.isFinite()) false else when (guard.comparison) {
                        InterlockComparison.LESS_THAN -> actual < guard.expectedDoubleValue
                        InterlockComparison.GREATER_THAN -> actual > guard.expectedDoubleValue
                        InterlockComparison.EQUALS_STATE -> abs(actual - guard.expectedDoubleValue) <= guard.tolerance
                        InterlockComparison.NOT_EQUALS_STATE -> abs(actual - guard.expectedDoubleValue) > guard.tolerance
                    }
                }
                guard.expectedBooleanValue != null -> {
                    val actual = binding.readBoolean(guard.source.subsystemId, guard.source.fieldId, state)
                    when (guard.comparison) {
                        InterlockComparison.EQUALS_STATE -> actual == guard.expectedBooleanValue
                        InterlockComparison.NOT_EQUALS_STATE -> actual != null && actual != guard.expectedBooleanValue
                        else -> false
                    }
                }
                else -> {
                    val actual = binding.readString(guard.source.subsystemId, guard.source.fieldId, state)
                    when (guard.comparison) {
                        InterlockComparison.EQUALS_STATE -> actual == guard.expectedStringValue
                        InterlockComparison.NOT_EQUALS_STATE -> actual != null && actual != guard.expectedStringValue
                        else -> false
                    }
                }
            }
            if (!matches) return false
            guardIndex++
        }
        return true
    }

    /**
     * Resolves every target into preallocated primitive buffers and stores an exact deterministic
     * fingerprint in [resolvedTargetHash]. False means a required source was missing or non-finite.
     */
    private fun resolveTargets(preset: SuperstructureStatePreset, state: RobotState): Boolean {
        var hash = FNV_OFFSET
        for (index in preset.subsystemTargets.indices) {
            val target = preset.subsystemTargets[index]
            val type = binding.targetType(target.subsystemId, target.fieldId) ?: return false
            targetTypes[index] = type
            when (type) {
                SubsystemValueType.DOUBLE -> {
                    var value = when (target.targetMode) {
                        SuperstructureTargetMode.CONSTANT -> target.constantDoubleValue
                        SuperstructureTargetMode.DYNAMIC_LUT -> target.source?.let {
                            val input = binding.readNumeric(it.subsystemId, it.fieldId, state)
                            if (input.isFinite()) lutsById.getValue(requireNotNull(target.lutId)).sample(input) else null
                        }
                        SuperstructureTargetMode.PASS_THROUGH -> target.source?.let {
                            binding.readNumeric(it.subsystemId, it.fieldId, state)
                        }
                    } ?: return false
                    if (!value.isFinite()) return false
                    var interlockIndex = 0
                    while (interlockIndex < document.interlocks.size) {
                        val interlock = document.interlocks[interlockIndex]
                        if (interlock.constrainedSubsystemId != target.subsystemId ||
                            interlock.constrainedFieldId != target.fieldId
                        ) {
                            interlockIndex++
                            continue
                        }
                        val primary = binding.readNumeric(
                            interlock.primary.subsystemId,
                            interlock.primary.fieldId,
                            state,
                        )
                        if (!primary.isFinite()) return false
                        val active = when (interlock.conditionComparison) {
                            InterlockComparison.LESS_THAN -> primary < interlock.conditionThreshold
                            InterlockComparison.GREATER_THAN -> primary > interlock.conditionThreshold
                            InterlockComparison.EQUALS_STATE -> primary == interlock.conditionThreshold
                            InterlockComparison.NOT_EQUALS_STATE -> primary != interlock.conditionThreshold
                        }
                        if (active) {
                            interlock.clampMinimum?.let { value = kotlin.math.max(value, it) }
                            interlock.clampMaximum?.let { value = kotlin.math.min(value, it) }
                        }
                        interlockIndex++
                    }
                    doubleTargets[index] = value
                    hash = mix(hash, value.toBits())
                }
                SubsystemValueType.INT -> {
                    val value = when (target.targetMode) {
                        SuperstructureTargetMode.CONSTANT -> target.constantDoubleValue?.takeIf {
                            it.isFinite() && it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
                        }?.toInt()
                        SuperstructureTargetMode.PASS_THROUGH -> target.source?.let {
                            binding.readNumeric(it.subsystemId, it.fieldId, state).takeIf { number ->
                                number.isFinite() && number % 1.0 == 0.0 &&
                                    number in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
                            }?.toInt()
                        }
                        SuperstructureTargetMode.DYNAMIC_LUT -> null
                    } ?: return false
                    intTargets[index] = value
                    hash = mix(hash, value.toLong())
                }
                SubsystemValueType.BOOLEAN -> {
                    val value = when (target.targetMode) {
                        SuperstructureTargetMode.CONSTANT -> target.constantBooleanValue
                        SuperstructureTargetMode.PASS_THROUGH -> target.source?.let {
                            binding.readBoolean(it.subsystemId, it.fieldId, state)
                        }
                        SuperstructureTargetMode.DYNAMIC_LUT -> null
                    } ?: return false
                    booleanTargets[index] = value
                    hash = mix(hash, if (value) 1L else 0L)
                }
                SubsystemValueType.STRING -> {
                    val value = when (target.targetMode) {
                        SuperstructureTargetMode.CONSTANT -> target.constantStringValue
                        SuperstructureTargetMode.PASS_THROUGH -> target.source?.let {
                            binding.readString(it.subsystemId, it.fieldId, state)
                        }
                        SuperstructureTargetMode.DYNAMIC_LUT -> null
                    } ?: return false
                    stringTargets[index] = value
                    hash = mix(hash, value.hashCode().toLong())
                }
            }
        }
        resolvedTargetHash = hash
        return true
    }

    private fun applyResolvedTargets(preset: SuperstructureStatePreset, store: Store): Boolean {
        return try {
            for (index in preset.subsystemTargets.indices) {
                val target = preset.subsystemTargets[index]
                val task = when (targetTypes[index]) {
                    SubsystemValueType.DOUBLE -> binding.createDoubleTargetTask(
                        target.subsystemId,
                        target.fieldId,
                        doubleTargets[index],
                    )
                    SubsystemValueType.INT -> binding.createIntTargetTask(
                        target.subsystemId,
                        target.fieldId,
                        intTargets[index],
                    )
                    SubsystemValueType.BOOLEAN -> binding.createBooleanTargetTask(
                        target.subsystemId,
                        target.fieldId,
                        booleanTargets[index],
                    )
                    SubsystemValueType.STRING -> binding.createStringTargetTask(
                        target.subsystemId,
                        target.fieldId,
                        requireNotNull(stringTargets[index]),
                    )
                    null -> null
                } ?: return false
                targetTasks[index] = task
            }
            // Initialize the complete preset before dispatching anything. A bad adapter cannot
            // partially apply a multi-subsystem state and leave the mechanism in a mixed posture.
            for (index in preset.subsystemTargets.indices) {
                targetActions[index] = requireNotNull(targetTasks[index]).initialize(store.state)
            }
            for (index in preset.subsystemTargets.indices) {
                val actions = requireNotNull(targetActions[index])
                for (actionIndex in actions.indices) store.dispatch(actions[actionIndex])
            }
            true
        } catch (_: RuntimeException) {
            false
        } finally {
            for (index in preset.subsystemTargets.indices) {
                targetTasks[index]?.releaseRuntimeState()
                targetTasks[index] = null
                targetActions[index] = null
            }
        }
    }

    companion object {
        private const val FNV_OFFSET = -3750763034362895579L
        private const val FNV_PRIME = 1099511628211L

        fun state(robotState: RobotState, superstructureId: String, initialStateId: String): SuperstructureRuntimeState =
            robotState.superstructure.subsystems[superstructureId] as? SuperstructureRuntimeState
                ?: SuperstructureRuntimeState(currentStateId = initialStateId)

        /** Creates the one-shot action used by routines and controller bindings. */
        fun requestTask(
            superstructureId: String,
            initialStateId: String,
            actionKey: String,
        ): Task = StateActionTask("Request $superstructureId action $actionKey") { robotState ->
            val current = state(robotState, superstructureId, initialStateId)
            if (current.requestSequence == Long.MAX_VALUE) {
                return@StateActionTask RobotAction.UpdateNamedSubsystemState(
                    superstructureId,
                    current.copy(
                        pendingActionKey = null,
                        lastRejectionReason = "Superstructure request sequence is exhausted; restart before retrying",
                    ),
                )
            }
            RobotAction.UpdateNamedSubsystemState(
                superstructureId,
                current.copy(
                    pendingActionKey = actionKey,
                    pendingActionTimestampMs = RobotClock.currentTimeMillis(),
                    requestSequence = current.requestSequence + 1L,
                    lastRejectionReason = null,
                ),
            )
        }

        private fun mix(hash: Long, value: Long): Long = (hash xor value) * FNV_PRIME
        private fun Double.secondsToMillis(): Long = (this * 1000.0).toLong()
    }
}
