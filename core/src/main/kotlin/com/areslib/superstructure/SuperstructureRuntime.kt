package com.areslib.superstructure

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.sequencer.StateActionTask
import com.areslib.sequencer.SequentialTaskGroup
import com.areslib.sequencer.Task
import com.areslib.sequencer.TaskExecutor
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
    /** True while the robot is disabled; generated subsystem controllers still own output neutral. */
    val isDisabled: Boolean = false,
    /** Monotonic event identity incremented for every accepted state transition. */
    val transitionSequence: Long = 0L,
    /** Last transition whose exit/entry action group was submitted to the lifecycle executor. */
    val lifecycleSequenceScheduled: Long = -1L,
    /** Last submitted transition whose lifecycle action group completed successfully. */
    val lifecycleSequenceCompleted: Long = -1L,
    val lastLifecycleError: String? = null,
) : SubsystemState

/**
 * Generated typed boundary between the generic state-machine evaluator and generated subsystem
 * state/action plumbing. Implementations must read only immutable cached Redux fields.
 */
interface SuperstructureRuntimeBinding {
    /** Cached lifecycle state supplied by the robot facade; no hardware access is permitted here. */
    fun isRobotEnabled(): Boolean
    /** Resolves stable descriptor UIDs once during construction; hot paths use only primitive slots. */
    fun resolvePort(subsystemUid: String, fieldUid: String): Int
    fun portType(port: Int): SubsystemValueType?
    fun readNumeric(port: Int, state: RobotState): Double
    fun readBoolean(port: Int, state: RobotState): Boolean?
    fun readString(port: Int, state: RobotState): String?
    /** Returns [SuperstructurePortHealthBits] without allocating or reading hardware. */
    fun readHealthBits(port: Int, state: RobotState, nowMs: Long): Int
    fun createDoubleTargetTask(port: Int, value: Double): Task?
    fun createIntTargetTask(port: Int, value: Int): Task?
    fun createBooleanTargetTask(port: Int, value: Boolean): Task?
    fun createStringTargetTask(port: Int, value: String): Task?
    /** Resolves a parameterless project-catalog action without executing it directly. */
    fun createLifecycleActionTask(actionKey: String, timestampMs: Long): Task?
}

/** Primitive cached-port health flags used by generated bindings. */
object SuperstructurePortHealthBits {
    const val VALID: Int = 1
    const val FRESH: Int = 1 shl 1
    const val CONFIGURED: Int = 1 shl 2
    const val HOMED: Int = 1 shl 3
    const val CALIBRATED: Int = 1 shl 4
    const val CURRENT_VALID: Int = 1 shl 5
    const val OUTPUT_HEALTHY: Int = 1 shl 6

    const val FRESH_VALID_MASK: Int = VALID or FRESH
    const val CONTROL_READY_MASK: Int = FRESH_VALID_MASK or CONFIGURED or HOMED or CALIBRATED or
        CURRENT_VALID or OUTPUT_HEALTHY

    fun requiredMask(requirement: SuperstructurePortHealthRequirement): Int = when (requirement) {
        SuperstructurePortHealthRequirement.VALUE_ONLY -> 0
        SuperstructurePortHealthRequirement.FRESH_VALID -> FRESH_VALID_MASK
        SuperstructurePortHealthRequirement.CONTROL_READY -> CONTROL_READY_MASK
    }
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
    private data class CompiledTarget(
        val definition: SuperstructureSubsystemTarget,
        val targetPort: Int,
        val sourcePort: Int,
    )

    private data class CompiledState(
        val definition: SuperstructureStatePreset,
        val targets: Array<CompiledTarget>,
    )

    private data class CompiledTransition(
        val definition: StateTransitionEdge,
        val guardPorts: IntArray,
        val guardFailureReasons: Array<String>,
    )

    private data class CompiledInterlock(
        val definition: SuperstructureInterlockRule,
        val primaryPort: Int,
        val constrainedPort: Int,
    )

    private data class CompiledHealthFallback(
        val definition: SuperstructureHealthFallbackPolicy,
        val sourcePort: Int,
    )

    private val statesById = document.states.associate { preset ->
        preset.stateId to CompiledState(
            preset,
            Array(preset.subsystemTargets.size) { index ->
                val target = preset.subsystemTargets[index]
                CompiledTarget(
                    definition = target,
                    targetPort = resolvePort(target.target),
                    sourcePort = target.source?.let(::resolvePort) ?: NO_PORT,
                )
            },
        )
    }
    private val lutsById = document.luts.associateBy { it.lutId }
    private val compiledTransitions = document.transitions.map { edge ->
        CompiledTransition(
            edge,
            IntArray(edge.guards.size) { index -> resolvePort(edge.guards[index].source) },
            Array(edge.guards.size) { index ->
                val guard = edge.guards[index]
                "Waiting for guard '${guard.guardId}' on ${guard.source.subsystemUid}.${guard.source.fieldUid} to become healthy and true"
            },
        )
    }
    private val automaticTransitions = compiledTransitions
        .filter { it.definition.triggerKind != TransitionTriggerKind.ACTION_REQUEST }
        .sortedWith(compareBy<CompiledTransition> { it.definition.priority }.thenBy { it.definition.transitionId })
    private val faultTransitionIds = automaticTransitions
        .filter { it.definition.targetStateId == document.faultStateId }
        .mapTo(hashSetOf()) { it.definition.transitionId }
    private val compiledInterlocks = document.interlocks.map { rule ->
        CompiledInterlock(rule, resolvePort(rule.primary), resolvePort(rule.constrained))
    }
    private val compiledHealthFallbacks = document.healthFallbacks.map { policy ->
        CompiledHealthFallback(policy, resolvePort(policy.source))
    }
    private val maximumTargetCount = document.states.maxOfOrNull { it.subsystemTargets.size } ?: 0
    private val doubleTargets = DoubleArray(maximumTargetCount)
    private val intTargets = IntArray(maximumTargetCount)
    private val booleanTargets = BooleanArray(maximumTargetCount)
    private val stringTargets = arrayOfNulls<String>(maximumTargetCount)
    private val targetTypes = arrayOfNulls<SubsystemValueType>(maximumTargetCount)
    private val targetTasks = arrayOfNulls<Task>(maximumTargetCount)
    private val targetActions = arrayOfNulls<List<RobotAction>>(maximumTargetCount)
    private val lifecycleExecutor = TaskExecutor()
    private var completedLifecycleSequence = -1L
    private var failedLifecycleSequence = -1L
    private var lifecycleFailureReason: String? = null
    private var failedGuardIndex: Int = -1
    private var resolvedTargetHash = FNV_OFFSET

    private fun resolvePort(reference: SuperstructureFieldReference): Int =
        binding.resolvePort(reference.subsystemUid, reference.fieldUid).also { port ->
            require(port >= 0) { "Generated binding cannot resolve ${reference.subsystemUid}.${reference.fieldUid}" }
        }

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
        if (!binding.isRobotEnabled()) {
            if (lifecycleExecutor.size > 0) {
                dispatchAll(store, lifecycleExecutor.cancelAll(store.state))
                completedLifecycleSequence = next.lifecycleSequenceScheduled
            }
            next = handleDisabled(next, timestampMs)
        } else {
            if (next.isDisabled) next = next.copy(isDisabled = false)
            val unhealthy = firstUnhealthyFallback(store.state, timestampMs)
            if (unhealthy != null) {
                next = enterHealthFallback(next, unhealthy, timestampMs)
            } else {
                val safetySourceStateId = next.currentStateId
                next = evaluateAutomaticTransitions(next, store.state, timestampMs, faultOnly = true)
                val safetyTransitionPending = next.candidateTransitionId?.let(faultTransitionIds::contains) == true
                if (next.currentStateId == safetySourceStateId && !safetyTransitionPending) {
                    val requestSourceStateId = next.currentStateId
                    next = consumeRequest(next, store.state, timestampMs)
                    if (next.currentStateId == requestSourceStateId && next.pendingActionKey == null) {
                        next = evaluateAutomaticTransitions(next, store.state, timestampMs, faultOnly = false)
                    }
                }
            }
        }

        val compiledState = statesById[next.currentStateId]
        if (compiledState == null) {
            next = enterFault(next, timestampMs, "Current state '${next.currentStateId}' is not declared")
        }
        val activePreset = statesById.getValue(next.currentStateId)
        if (!resolveTargets(activePreset, store.state, timestampMs)) {
            next = enterFault(next, timestampMs, "A required cached target source is missing or invalid")
        } else if (resolvedTargetHash != next.lastAppliedTargetHash) {
            if (applyResolvedTargets(activePreset, store)) {
                next = next.copy(lastAppliedTargetHash = resolvedTargetHash)
            } else {
                next = enterFault(next, timestampMs, "Generated subsystem target plumbing rejected a preset")
            }
        }

        next = applyFaultPresetIfNeeded(next, store, timestampMs)

        next = scheduleLifecycleIfNeeded(next, timestampMs)
        next = applyFaultPresetIfNeeded(next, store, timestampMs)
        if (lifecycleExecutor.size > 0) {
            dispatchAll(store, lifecycleExecutor.update(store.state, timestampMs))
        }
        if (failedLifecycleSequence >= 0L && failedLifecycleSequence > next.lifecycleSequenceCompleted) {
            val failed = next.copy(
                    lifecycleSequenceCompleted = failedLifecycleSequence,
                    lastLifecycleError = lifecycleFailureReason ?: "A lifecycle action failed",
                )
            next = if (next.currentStateId == document.faultStateId && next.isFaulted) failed else enterFault(
                failed,
                timestampMs,
                lifecycleFailureReason ?: "A lifecycle action failed",
            )
        } else if (completedLifecycleSequence > next.lifecycleSequenceCompleted) {
            next = next.copy(
                lifecycleSequenceCompleted = completedLifecycleSequence,
                lastLifecycleError = null,
            )
        }
        next = applyFaultPresetIfNeeded(next, store, timestampMs)
        if (next != before) {
            store.dispatch(RobotAction.UpdateNamedSubsystemState(document.superstructureId, next, timestampMs))
        }
    }

    override fun writeOutputs(state: RobotState, scale: Double) = Unit

    private fun handleDisabled(initial: SuperstructureRuntimeState, nowMs: Long): SuperstructureRuntimeState {
        val rejectedRequest = initial.pendingActionKey
        val base = when (document.disabledPolicy) {
            SuperstructureDisabledPolicy.FORCE_SAFE_AND_REJECT_REQUESTS -> {
                if (initial.currentStateId == document.disabledStateId && initial.isDisabled) initial
                else enterState(initial, document.disabledStateId, nowMs, faulted = false).copy(isDisabled = true)
            }
            SuperstructureDisabledPolicy.RETAIN_LOGICAL_STATE_WITH_NEUTRAL_OUTPUT -> {
                if (initial.isDisabled) initial else initial.copy(isDisabled = true)
            }
        }
        if (rejectedRequest == null && base.requestSequence == base.handledRequestSequence) return base
        return base.copy(
            pendingActionKey = null,
            pendingActionTimestampMs = 0L,
            handledRequestSequence = base.requestSequence,
            candidateTransitionId = null,
            candidateSinceMs = 0L,
            lastRejectionReason = rejectedRequest?.let { "Action '$it' was rejected while the robot was disabled" },
        )
    }

    private fun firstUnhealthyFallback(
        state: RobotState,
        nowMs: Long,
    ): CompiledHealthFallback? {
        var index = 0
        while (index < compiledHealthFallbacks.size) {
            val policy = compiledHealthFallbacks[index]
            if (!portHealthy(policy.sourcePort, policy.definition.source.healthRequirement, state, nowMs)) {
                return policy
            }
            index++
        }
        return null
    }

    private fun enterHealthFallback(
        initial: SuperstructureRuntimeState,
        fallback: CompiledHealthFallback,
        nowMs: Long,
    ): SuperstructureRuntimeState {
        val policy = fallback.definition
        val faulted = policy.latchFault || policy.fallbackStateId == document.faultStateId
        val reason = "Health policy '${policy.policyId}' rejected ${policy.source.subsystemUid}.${policy.source.fieldUid}"
        if (initial.currentStateId == policy.fallbackStateId && initial.isFaulted == faulted) {
            if (initial.pendingActionKey == null && initial.requestSequence == initial.handledRequestSequence) return initial
            return initial.copy(
                pendingActionKey = null,
                pendingActionTimestampMs = 0L,
                handledRequestSequence = initial.requestSequence,
                candidateTransitionId = null,
                candidateSinceMs = 0L,
                lastRejectionReason = reason,
            )
        }
        return enterState(initial, policy.fallbackStateId, nowMs, faulted = faulted, reason = reason)
    }

    private fun portHealthy(
        port: Int,
        requirement: SuperstructurePortHealthRequirement,
        state: RobotState,
        nowMs: Long,
    ): Boolean {
        val mask = SuperstructurePortHealthBits.requiredMask(requirement)
        return mask == 0 || binding.readHealthBits(port, state, nowMs) and mask == mask
    }

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
        var compiledEdge: CompiledTransition? = null
        var transitionIndex = 0
        while (transitionIndex < compiledTransitions.size) {
            val candidate = compiledTransitions[transitionIndex].definition
            if (candidate.sourceStateId == state.currentStateId &&
                candidate.triggerKind == TransitionTriggerKind.ACTION_REQUEST &&
                candidate.actionKey == actionKey
            ) {
                compiledEdge = compiledTransitions[transitionIndex]
                break
            }
            transitionIndex++
        }
        if (compiledEdge == null) {
            return state.copy(
                pendingActionKey = null,
                pendingActionTimestampMs = 0L,
                candidateTransitionId = null,
                candidateSinceMs = 0L,
                lastRejectionReason = "Action '$actionKey' is not available from ${state.currentStateId}",
            )
        }
        val edge = compiledEdge.definition
        if (guardsPass(compiledEdge, robotState, nowMs)) {
            val ready = if (state.lastRejectionReason != null) state.copy(lastRejectionReason = null) else state
            return advanceDebounce(ready, compiledEdge, nowMs)
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
        val waitingReason = compiledEdge.guardFailureReasons.getOrNull(failedGuardIndex)
        return if (state.candidateTransitionId != null || state.candidateSinceMs != 0L ||
            state.lastRejectionReason != waitingReason
        ) {
            state.copy(
                candidateTransitionId = null,
                candidateSinceMs = 0L,
                lastRejectionReason = waitingReason,
            )
        } else state
    }

    private fun evaluateAutomaticTransitions(
        initial: SuperstructureRuntimeState,
        robotState: RobotState,
        nowMs: Long,
        faultOnly: Boolean,
    ): SuperstructureRuntimeState {
        if (initial.currentStateId != document.faultStateId && initial.isFaulted) return initial
        val preset = statesById.getValue(initial.currentStateId).definition
        val stateTimeoutMs = preset.timeoutSeconds?.secondsToMillis()
        val timeoutTarget = preset.timeoutTargetStateId
        if (stateTimeoutMs != null && timeoutTarget != null &&
            (timeoutTarget == document.faultStateId) == faultOnly &&
            nowMs - initial.stateEntryTimestampMs >= stateTimeoutMs
        ) {
            return enterState(
                initial,
                timeoutTarget,
                nowMs,
                reason = if (timeoutTarget == document.faultStateId) "State '${preset.stateId}' timed out" else null,
            )
        }
        var transitionIndex = 0
        while (transitionIndex < automaticTransitions.size) {
            val compiledEdge = automaticTransitions[transitionIndex]
            val edge = compiledEdge.definition
            if (edge.sourceStateId != initial.currentStateId ||
                (edge.targetStateId == document.faultStateId) != faultOnly
            ) {
                transitionIndex++
                continue
            }
            when (edge.triggerKind) {
                TransitionTriggerKind.ACTION_REQUEST -> Unit
                TransitionTriggerKind.TIME_ELAPSED -> {
                    val elapsedMs = requireNotNull(edge.timeoutSeconds).secondsToMillis()
                    if (nowMs - initial.stateEntryTimestampMs >= elapsedMs) {
                        return advanceDebounce(initial, compiledEdge, nowMs)
                    }
                }
                TransitionTriggerKind.SENSOR_CONDITION_AUTO -> {
                    if (guardsPass(compiledEdge, robotState, nowMs)) return advanceDebounce(initial, compiledEdge, nowMs)
                }
            }
            transitionIndex++
        }
        val candidateBelongsToThisPass = if (faultOnly) {
            initial.candidateTransitionId?.let(faultTransitionIds::contains) == true
        } else {
            initial.candidateTransitionId != null && !faultTransitionIds.contains(initial.candidateTransitionId)
        }
        return if (candidateBelongsToThisPass && (!faultOnly || initial.pendingActionKey == null)) {
            initial.copy(candidateTransitionId = null, candidateSinceMs = 0L)
        } else initial
    }

    private fun advanceDebounce(
        state: SuperstructureRuntimeState,
        compiledEdge: CompiledTransition,
        nowMs: Long,
    ): SuperstructureRuntimeState {
        val edge = compiledEdge.definition
        val reason = if (edge.targetStateId == document.faultStateId) {
            "Transition '${edge.transitionId}' entered the fault state"
        } else null
        if (edge.debounceMs == 0L) return enterState(state, edge.targetStateId, nowMs, reason = reason)
        if (state.candidateTransitionId != edge.transitionId) {
            return state.copy(candidateTransitionId = edge.transitionId, candidateSinceMs = nowMs)
        }
        return if (nowMs - state.candidateSinceMs >= edge.debounceMs) {
            enterState(state, edge.targetStateId, nowMs, reason = reason)
        } else state
    }

    private fun enterState(
        state: SuperstructureRuntimeState,
        targetStateId: String,
        nowMs: Long,
        faulted: Boolean = targetStateId == document.faultStateId,
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
        faultReason = if (faulted) reason ?: "Entered the declared fault state" else null,
        lastRejectionReason = if (faulted) reason ?: "Entered the declared fault state" else null,
        isDisabled = false,
        transitionSequence = if (state.transitionSequence == Long.MAX_VALUE) Long.MAX_VALUE else state.transitionSequence + 1L,
    )

    private fun scheduleLifecycleIfNeeded(
        state: SuperstructureRuntimeState,
        timestampMs: Long,
    ): SuperstructureRuntimeState {
        if (state.lifecycleSequenceScheduled >= state.transitionSequence) return state
        val keys = ArrayList<String>()
        // Sequence zero is the initial entry event; it has no source exit action.
        if (state.transitionSequence > 0L) {
            statesById[state.previousStateId]?.definition?.onExitActionKeys?.let(keys::addAll)
        }
        statesById.getValue(state.currentStateId).definition.onEntryActionKeys.let(keys::addAll)
        if (keys.isEmpty()) {
            completedLifecycleSequence = state.transitionSequence
            return state.copy(
                lifecycleSequenceScheduled = state.transitionSequence,
                lifecycleSequenceCompleted = state.transitionSequence,
                lastLifecycleError = null,
            )
        }
        val tasks = ArrayList<Task>(keys.size)
        for (index in keys.indices) {
            val key = keys[index]
            val task = binding.createLifecycleActionTask(key, timestampMs)
                ?: return if (state.currentStateId == document.faultStateId && state.isFaulted) {
                    state.copy(
                        lifecycleSequenceScheduled = state.transitionSequence,
                        lifecycleSequenceCompleted = state.transitionSequence,
                        lastLifecycleError = "Lifecycle action '$key' is unavailable at runtime",
                    )
                } else enterFault(state, timestampMs, "Lifecycle action '$key' is unavailable at runtime")
            tasks.add(task)
        }
        val sequence = state.transitionSequence
        val group = SequentialTaskGroup(tasks)
            .onComplete { completedLifecycleSequence = sequence }
            .onFail {
                failedLifecycleSequence = sequence
                lifecycleFailureReason = "Lifecycle actions failed for transition sequence $sequence"
            }
        lifecycleExecutor.addTask(group)
        return state.copy(lifecycleSequenceScheduled = sequence, lastLifecycleError = null)
    }

    private fun dispatchAll(store: Store, actions: List<RobotAction>) {
        for (index in actions.indices) store.dispatch(actions[index])
    }

    private fun applyFaultPresetIfNeeded(
        state: SuperstructureRuntimeState,
        store: Store,
        timestampMs: Long,
    ): SuperstructureRuntimeState {
        if (!state.isFaulted || state.currentStateId != document.faultStateId ||
            state.lastAppliedTargetHash != Long.MIN_VALUE
        ) return state
        val faultPreset = statesById.getValue(document.faultStateId)
        return if (resolveTargets(faultPreset, store.state, timestampMs) && applyResolvedTargets(faultPreset, store)) {
            state.copy(lastAppliedTargetHash = resolvedTargetHash)
        } else state
    }

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

    private fun guardsPass(edge: CompiledTransition, state: RobotState, nowMs: Long): Boolean {
        failedGuardIndex = -1
        var guardIndex = 0
        while (guardIndex < edge.definition.guards.size) {
            val guard = edge.definition.guards[guardIndex]
            val port = edge.guardPorts[guardIndex]
            if (!portHealthy(port, guard.source.healthRequirement, state, nowMs)) {
                failedGuardIndex = guardIndex
                return false
            }
            val matches = when {
                guard.expectedDoubleValue != null -> {
                    val actual = binding.readNumeric(port, state)
                    if (!actual.isFinite()) false else when (guard.comparison) {
                        InterlockComparison.LESS_THAN -> actual < guard.expectedDoubleValue
                        InterlockComparison.GREATER_THAN -> actual > guard.expectedDoubleValue
                        InterlockComparison.EQUALS_STATE -> abs(actual - guard.expectedDoubleValue) <= guard.tolerance
                        InterlockComparison.NOT_EQUALS_STATE -> abs(actual - guard.expectedDoubleValue) > guard.tolerance
                    }
                }
                guard.expectedBooleanValue != null -> {
                    val actual = binding.readBoolean(port, state)
                    when (guard.comparison) {
                        InterlockComparison.EQUALS_STATE -> actual == guard.expectedBooleanValue
                        InterlockComparison.NOT_EQUALS_STATE -> actual != null && actual != guard.expectedBooleanValue
                        else -> false
                    }
                }
                else -> {
                    val actual = binding.readString(port, state)
                    when (guard.comparison) {
                        InterlockComparison.EQUALS_STATE -> actual == guard.expectedStringValue
                        InterlockComparison.NOT_EQUALS_STATE -> actual != null && actual != guard.expectedStringValue
                        else -> false
                    }
                }
            }
            if (!matches) {
                failedGuardIndex = guardIndex
                return false
            }
            guardIndex++
        }
        return true
    }

    /**
     * Resolves every target into preallocated primitive buffers and stores an exact deterministic
     * fingerprint in [resolvedTargetHash]. False means a required source was missing or non-finite.
     */
    private fun resolveTargets(preset: CompiledState, state: RobotState, nowMs: Long): Boolean {
        var hash = FNV_OFFSET
        for (index in preset.targets.indices) {
            val compiledTarget = preset.targets[index]
            val target = compiledTarget.definition
            val type = binding.portType(compiledTarget.targetPort) ?: return false
            targetTypes[index] = type
            when (type) {
                SubsystemValueType.DOUBLE -> {
                    var value = when (target.targetMode) {
                        SuperstructureTargetMode.CONSTANT -> target.constantDoubleValue
                        SuperstructureTargetMode.DYNAMIC_LUT -> target.source?.let {
                            if (!portHealthy(compiledTarget.sourcePort, it.healthRequirement, state, nowMs)) return false
                            val input = binding.readNumeric(compiledTarget.sourcePort, state)
                            if (input.isFinite()) lutsById.getValue(requireNotNull(target.lutId)).sample(input) else null
                        }
                        SuperstructureTargetMode.PASS_THROUGH -> target.source?.let {
                            if (!portHealthy(compiledTarget.sourcePort, it.healthRequirement, state, nowMs)) return false
                            binding.readNumeric(compiledTarget.sourcePort, state)
                        }
                    } ?: return false
                    if (!value.isFinite()) return false
                    var interlockIndex = 0
                    while (interlockIndex < compiledInterlocks.size) {
                        val compiledInterlock = compiledInterlocks[interlockIndex]
                        val interlock = compiledInterlock.definition
                        if (compiledInterlock.constrainedPort != compiledTarget.targetPort) {
                            interlockIndex++
                            continue
                        }
                        if (!portHealthy(compiledInterlock.primaryPort, interlock.primary.healthRequirement, state, nowMs)) return false
                        val primary = binding.readNumeric(compiledInterlock.primaryPort, state)
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
                            if (!portHealthy(compiledTarget.sourcePort, it.healthRequirement, state, nowMs)) return false
                            binding.readNumeric(compiledTarget.sourcePort, state).takeIf { number ->
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
                            if (!portHealthy(compiledTarget.sourcePort, it.healthRequirement, state, nowMs)) return false
                            binding.readBoolean(compiledTarget.sourcePort, state)
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
                            if (!portHealthy(compiledTarget.sourcePort, it.healthRequirement, state, nowMs)) return false
                            binding.readString(compiledTarget.sourcePort, state)
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

    private fun applyResolvedTargets(preset: CompiledState, store: Store): Boolean {
        return try {
            for (index in preset.targets.indices) {
                val target = preset.targets[index]
                val task = when (targetTypes[index]) {
                    SubsystemValueType.DOUBLE -> binding.createDoubleTargetTask(
                        target.targetPort,
                        doubleTargets[index],
                    )
                    SubsystemValueType.INT -> binding.createIntTargetTask(
                        target.targetPort,
                        intTargets[index],
                    )
                    SubsystemValueType.BOOLEAN -> binding.createBooleanTargetTask(
                        target.targetPort,
                        booleanTargets[index],
                    )
                    SubsystemValueType.STRING -> binding.createStringTargetTask(
                        target.targetPort,
                        requireNotNull(stringTargets[index]),
                    )
                    null -> null
                } ?: return false
                targetTasks[index] = task
            }
            // Initialize the complete preset before dispatching anything. A bad adapter cannot
            // partially apply a multi-subsystem state and leave the mechanism in a mixed posture.
            for (index in preset.targets.indices) {
                targetActions[index] = requireNotNull(targetTasks[index]).initialize(store.state)
            }
            for (index in preset.targets.indices) {
                val actions = requireNotNull(targetActions[index])
                for (actionIndex in actions.indices) store.dispatch(actions[actionIndex])
            }
            true
        } catch (_: RuntimeException) {
            false
        } finally {
            for (index in preset.targets.indices) {
                targetTasks[index]?.releaseRuntimeState()
                targetTasks[index] = null
                targetActions[index] = null
            }
        }
    }

    companion object {
        private const val FNV_OFFSET = -3750763034362895579L
        private const val FNV_PRIME = 1099511628211L
        private const val NO_PORT = -1

        fun state(robotState: RobotState, superstructureId: String, initialStateId: String): SuperstructureRuntimeState =
            robotState.superstructure.subsystems[superstructureId] as? SuperstructureRuntimeState
                ?: SuperstructureRuntimeState(currentStateId = initialStateId)

        /** Creates the one-shot action used by routines and controller bindings. */
        fun requestTask(
            superstructureId: String,
            initialStateId: String,
            actionKey: String,
        ): Task = StateActionTask("Request $superstructureId action $actionKey") { robotState ->
            requestAction(
                robotState = robotState,
                superstructureId = superstructureId,
                initialStateId = initialStateId,
                actionKey = actionKey,
                timestampMs = RobotClock.currentTimeMillis(),
            )
        }

        /** Deterministic request seam used by replay, simulators, and editor previews. */
        fun requestAction(
            robotState: RobotState,
            superstructureId: String,
            initialStateId: String,
            actionKey: String,
            timestampMs: Long,
        ): RobotAction.UpdateNamedSubsystemState {
            val current = state(robotState, superstructureId, initialStateId)
            if (current.requestSequence == Long.MAX_VALUE) {
                return RobotAction.UpdateNamedSubsystemState(
                    superstructureId,
                    current.copy(
                        pendingActionKey = null,
                        lastRejectionReason = "Superstructure request sequence is exhausted; restart before retrying",
                    ),
                )
            }
            return RobotAction.UpdateNamedSubsystemState(
                superstructureId,
                current.copy(
                    pendingActionKey = actionKey,
                    pendingActionTimestampMs = timestampMs,
                    requestSequence = current.requestSequence + 1L,
                    lastRejectionReason = null,
                ),
                timestampMs,
            )
        }

        private fun mix(hash: Long, value: Long): Long = (hash xor value) * FNV_PRIME
        private fun Double.secondsToMillis(): Long = (this * 1000.0).toLong()
    }
}
