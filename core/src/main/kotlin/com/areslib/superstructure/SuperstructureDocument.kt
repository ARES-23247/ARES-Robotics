package com.areslib.superstructure

import com.areslib.subsystem.InterlockComparison
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemImplementationKind
import com.areslib.subsystem.SubsystemStateFieldDocument
import com.areslib.subsystem.SubsystemValueType
import com.areslib.subsystem.isAresGenerated
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.areslib.util.parseJsonElement
import java.security.MessageDigest
import java.util.ArrayDeque

const val ARES_SUPERSTRUCTURE_SCHEMA_VERSION: Int = 3

enum class SuperstructureTargetMode { CONSTANT, DYNAMIC_LUT, PASS_THROUGH }
enum class TransitionTriggerKind { ACTION_REQUEST, SENSOR_CONDITION_AUTO, TIME_ELAPSED }
enum class LutInterpolationMethod { LINEAR, STEP, SMOOTH_COSINE }
enum class SuperstructureDisabledPolicy {
    /** Enter the declared neutral disabled state and reject requests until the robot is enabled. */
    FORCE_SAFE_AND_REJECT_REQUESTS,

    /** Retain the logical state while subsystem controllers independently enforce neutral output. */
    RETAIN_LOGICAL_STATE_WITH_NEUTRAL_OUTPUT,
}
enum class SuperstructurePortHealthRequirement {
    /** Type and finite-value checks only. Intended for advanced derived values. */
    VALUE_ONLY,
    /** The cached snapshot must be valid and inside its descriptor-defined freshness lease. */
    FRESH_VALID,
    /** Fresh/valid plus configuration, homing, calibration, current, and output-fault health. */
    CONTROL_READY,
}

data class LutControlPoint(val inputX: Double, val outputY: Double)

data class SuperstructureDynamicLut(
    val lutId: String,
    val displayName: String = "",
    val inputUnit: String = "",
    val outputUnit: String = "",
    val interpolation: LutInterpolationMethod = LutInterpolationMethod.LINEAR,
    val controlPoints: List<LutControlPoint> = emptyList(),
) {
    /** Samples a validated, sorted LUT without allocating. */
    fun sample(x: Double): Double {
        if (!x.isFinite() || controlPoints.isEmpty()) return Double.NaN
        if (controlPoints.size == 1 || x <= controlPoints.first().inputX) return controlPoints.first().outputY
        if (x >= controlPoints.last().inputX) return controlPoints.last().outputY
        for (index in 0 until controlPoints.size - 1) {
            val lower = controlPoints[index]
            val upper = controlPoints[index + 1]
            if (x <= upper.inputX) {
                val ratio = (x - lower.inputX) / (upper.inputX - lower.inputX)
                return when (interpolation) {
                    LutInterpolationMethod.STEP -> lower.outputY
                    LutInterpolationMethod.LINEAR -> lower.outputY + ratio * (upper.outputY - lower.outputY)
                    LutInterpolationMethod.SMOOTH_COSINE -> {
                        val factor = (1.0 - kotlin.math.cos(ratio * kotlin.math.PI)) / 2.0
                        lower.outputY + factor * (upper.outputY - lower.outputY)
                    }
                }
            }
        }
        return controlPoints.last().outputY
    }
}

/** A stable typed port reference. Code-facing IDs may be renamed without breaking this link. */
data class SuperstructureFieldReference(
    val subsystemUid: String,
    val fieldUid: String,
    val healthRequirement: SuperstructurePortHealthRequirement = SuperstructurePortHealthRequirement.CONTROL_READY,
)

data class StateNodeLayout(
    val x: Double = 0.0,
    val y: Double = 0.0,
)

data class TransitionGuard(
    val guardId: String,
    val source: SuperstructureFieldReference,
    val comparison: InterlockComparison = InterlockComparison.EQUALS_STATE,
    val expectedDoubleValue: Double? = null,
    val expectedBooleanValue: Boolean? = null,
    val expectedStringValue: String? = null,
    val tolerance: Double = 1e-4,
    val maxStalenessMs: Long? = null,
)

data class StateTransitionEdge(
    val transitionId: String,
    val sourceStateId: String,
    val targetStateId: String,
    val triggerKind: TransitionTriggerKind = TransitionTriggerKind.ACTION_REQUEST,
    val actionKey: String? = null,
    val guards: List<TransitionGuard> = emptyList(),
    /** Lower values run first. Automatic edges leaving one state must have unique priorities. */
    val priority: Int = 0,
    val debounceMs: Long = 0L,
    /** Required for TIME_ELAPSED; optional pending-request deadline for ACTION_REQUEST. */
    val timeoutSeconds: Double? = null,
    /** Fail-closed destination when a pending ACTION_REQUEST times out. */
    val timeoutTargetStateId: String? = null,
)

data class SuperstructureSubsystemTarget(
    val target: SuperstructureFieldReference,
    val targetMode: SuperstructureTargetMode = SuperstructureTargetMode.CONSTANT,
    val constantDoubleValue: Double? = null,
    val constantBooleanValue: Boolean? = null,
    val constantStringValue: String? = null,
    val lutId: String? = null,
    val source: SuperstructureFieldReference? = null,
)

data class SuperstructureStatePreset(
    val stateId: String,
    val displayName: String = "",
    val description: String = "",
    val subsystemTargets: List<SuperstructureSubsystemTarget> = emptyList(),
    /** Parameterless project-catalog tasks run once after leaving this state. */
    val onExitActionKeys: List<String> = emptyList(),
    /** Parameterless project-catalog tasks run once after entering this state. */
    val onEntryActionKeys: List<String> = emptyList(),
    val timeoutSeconds: Double? = null,
    val timeoutTargetStateId: String? = null,
)

data class SuperstructureInterlockRule(
    val ruleId: String,
    val description: String = "",
    val primary: SuperstructureFieldReference,
    val conditionComparison: InterlockComparison = InterlockComparison.LESS_THAN,
    val conditionThreshold: Double = 0.0,
    val constrained: SuperstructureFieldReference,
    val clampMinimum: Double? = null,
    val clampMaximum: Double? = null,
)

/** Supervisory fallback evaluated before requested and ordinary automatic transitions. */
data class SuperstructureHealthFallbackPolicy(
    val policyId: String,
    val source: SuperstructureFieldReference,
    val fallbackStateId: String,
    /** Latched fallbacks require an explicit legal recovery transition after health is restored. */
    val latchFault: Boolean = true,
    val description: String = "",
)

data class SuperstructureDocument(
    val superstructureId: String,
    val displayName: String = "",
    val description: String = "",
    val schemaVersion: Int = ARES_SUPERSTRUCTURE_SCHEMA_VERSION,
    val initialStateId: String,
    val states: List<SuperstructureStatePreset> = emptyList(),
    val transitions: List<StateTransitionEdge> = emptyList(),
    val interlocks: List<SuperstructureInterlockRule> = emptyList(),
    val healthFallbacks: List<SuperstructureHealthFallbackPolicy> = emptyList(),
    val luts: List<SuperstructureDynamicLut> = emptyList(),
    /** Required fail-closed preset used when generated target application cannot be completed. */
    val faultStateId: String,
    /** Disabled behavior is explicit so a logical posture cannot arm unexpectedly on re-enable. */
    val disabledPolicy: SuperstructureDisabledPolicy = SuperstructureDisabledPolicy.FORCE_SAFE_AND_REJECT_REQUESTS,
    /** Required neutral preset when [disabledPolicy] forces safe state. */
    val disabledStateId: String = faultStateId,
    /** Optional 2D Stateflow visual studio node positions. */
    val nodeLayouts: Map<String, StateNodeLayout> = emptyMap(),
)

enum class SuperstructureIssueSeverity { ERROR, WARNING }

data class SuperstructureValidationIssue(
    val severity: SuperstructureIssueSeverity,
    val path: String,
    val message: String,
)

/** Validates document-local invariants without assuming a project or action catalog. */
fun validateSuperstructureDocument(document: SuperstructureDocument): List<SuperstructureValidationIssue> {
    val issues = mutableListOf<SuperstructureValidationIssue>()
    fun error(path: String, message: String) {
        issues += SuperstructureValidationIssue(SuperstructureIssueSeverity.ERROR, path, message)
    }

    if (!document.superstructureId.matches(ID_PATTERN)) error("superstructureId", "Use lowercase letters, digits, and hyphens")
    if (document.schemaVersion != ARES_SUPERSTRUCTURE_SCHEMA_VERSION) {
        error("schemaVersion", "Unsupported schema version ${document.schemaVersion}")
    }
    if (document.states.isEmpty()) error("states", "Declare at least one state preset")
    duplicateValues(document.states.map { it.stateId }).forEach { error("states", "State ID '$it' is duplicated") }
    duplicateValues(document.transitions.map { it.transitionId }).forEach { error("transitions", "Transition ID '$it' is duplicated") }
    duplicateValues(document.interlocks.map { it.ruleId }).forEach { error("interlocks", "Interlock ID '$it' is duplicated") }
    duplicateValues(document.healthFallbacks.map { it.policyId }).forEach { error("healthFallbacks", "Health fallback ID '$it' is duplicated") }
    duplicateValues(document.luts.map { it.lutId }).forEach { error("luts", "LUT ID '$it' is duplicated") }

    val stateIds = document.states.mapTo(linkedSetOf()) { it.stateId }
    if (document.initialStateId !in stateIds) error("initialStateId", "Initial state '${document.initialStateId}' is not declared")
    if (document.faultStateId !in stateIds) error("faultStateId", "Fault state '${document.faultStateId}' is not declared")
    if (document.disabledStateId !in stateIds) error("disabledStateId", "Disabled state '${document.disabledStateId}' is not declared")
    val lutIds = document.luts.mapTo(linkedSetOf()) { it.lutId }

    document.luts.forEachIndexed { index, lut ->
        val path = "luts[$index]"
        if (!lut.lutId.matches(ID_PATTERN)) error("$path.lutId", "Use lowercase letters, digits, and hyphens")
        if (lut.controlPoints.size < 2) error("$path.controlPoints", "A LUT requires at least two control points")
        lut.controlPoints.forEachIndexed { pointIndex, point ->
            if (!point.inputX.isFinite() || !point.outputY.isFinite()) {
                error("$path.controlPoints[$pointIndex]", "LUT coordinates must be finite")
            }
            if (pointIndex > 0 && point.inputX <= lut.controlPoints[pointIndex - 1].inputX) {
                error("$path.controlPoints", "inputX values must be strictly increasing")
            }
        }
    }

    val canonicalTargetSet = document.states.firstOrNull()?.subsystemTargets
        ?.map { it.target.subsystemUid to it.target.fieldUid }?.toSet().orEmpty()
    document.states.forEachIndexed { index, state ->
        val path = "states[$index]"
        if (!state.stateId.matches(TYPE_ID_PATTERN)) error("$path.stateId", "Use letters, digits, and underscores")
        val targetKeys = state.subsystemTargets.map { it.target.subsystemUid to it.target.fieldUid }
        duplicateValues(targetKeys).forEach { error("$path.subsystemTargets", "Target '${it.first}.${it.second}' is duplicated") }
        if (targetKeys.toSet() != canonicalTargetSet) {
            error("$path.subsystemTargets", "Every state must explicitly command the same target fields so outputs cannot remain stale")
        }
        duplicateValues(state.onExitActionKeys).forEach { error("$path.onExitActionKeys", "Lifecycle action '$it' is duplicated") }
        duplicateValues(state.onEntryActionKeys).forEach { error("$path.onEntryActionKeys", "Lifecycle action '$it' is duplicated") }
        state.onExitActionKeys.forEachIndexed { actionIndex, actionKey ->
            if (!actionKey.matches(CAPABILITY_KEY_PATTERN)) error("$path.onExitActionKeys[$actionIndex]", "Invalid action key '$actionKey'")
        }
        state.onEntryActionKeys.forEachIndexed { actionIndex, actionKey ->
            if (!actionKey.matches(CAPABILITY_KEY_PATTERN)) error("$path.onEntryActionKeys[$actionIndex]", "Invalid action key '$actionKey'")
        }
        validateTimeout(state.timeoutSeconds, "$path.timeoutSeconds", ::error)
        if ((state.timeoutSeconds == null) != (state.timeoutTargetStateId == null)) {
            error(path, "State timeoutSeconds and timeoutTargetStateId must be supplied together")
        }
        state.timeoutTargetStateId?.takeIf { it !in stateIds }?.let {
            error("$path.timeoutTargetStateId", "Timeout state '$it' is not declared")
        }
        state.subsystemTargets.forEachIndexed { targetIndex, target ->
            val targetPath = "$path.subsystemTargets[$targetIndex]"
            if (target.target.subsystemUid.isBlank() || target.target.fieldUid.isBlank()) error(targetPath, "Stable subsystem and field UIDs are required")
            val constants = listOfNotNull(target.constantDoubleValue, target.constantBooleanValue, target.constantStringValue)
            when (target.targetMode) {
                SuperstructureTargetMode.CONSTANT -> if (constants.size != 1) error(targetPath, "A constant target requires exactly one typed value")
                SuperstructureTargetMode.DYNAMIC_LUT -> {
                    if (constants.isNotEmpty()) error(targetPath, "A dynamic LUT target cannot also declare a constant")
                    if (target.lutId !in lutIds) error("$targetPath.lutId", "Referenced LUT '${target.lutId}' is not declared")
                    if (target.source == null) error("$targetPath.source", "A dynamic LUT target requires a typed source field")
                }
                SuperstructureTargetMode.PASS_THROUGH -> {
                    if (constants.isNotEmpty() || target.lutId != null) error(targetPath, "A pass-through target cannot declare a constant or LUT")
                    if (target.source == null) error("$targetPath.source", "A pass-through target requires a typed source field")
                }
            }
        }
    }

    val actionEdges = mutableSetOf<Pair<String, String>>()
    val automaticPriorities = mutableSetOf<Pair<String, Int>>()
    document.transitions.forEachIndexed { index, transition ->
        val path = "transitions[$index]"
        if (!transition.transitionId.matches(ID_PATTERN)) error("$path.transitionId", "Use lowercase letters, digits, and hyphens")
        if (transition.sourceStateId !in stateIds) error("$path.sourceStateId", "Unknown source state '${transition.sourceStateId}'")
        if (transition.targetStateId !in stateIds) error("$path.targetStateId", "Unknown target state '${transition.targetStateId}'")
        if (transition.priority !in 0..10_000) error("$path.priority", "Priority must be from 0 to 10000; lower values run first")
        if (transition.sourceStateId == transition.targetStateId) error(path, "A transition must change state")
        if (transition.debounceMs !in 0L..60_000L) error("$path.debounceMs", "Debounce must be from 0 to 60000 ms")
        validateTimeout(transition.timeoutSeconds, "$path.timeoutSeconds", ::error)
        transition.timeoutTargetStateId?.takeIf { it !in stateIds }?.let {
            error("$path.timeoutTargetStateId", "Timeout state '$it' is not declared")
        }
        when (transition.triggerKind) {
            TransitionTriggerKind.ACTION_REQUEST -> {
                if (transition.actionKey.isNullOrBlank()) error("$path.actionKey", "Action-request transitions require an action key")
                val key = transition.sourceStateId to transition.actionKey.orEmpty()
                if (!actionEdges.add(key)) error(path, "Only one outgoing transition may use action '${transition.actionKey}' from ${transition.sourceStateId}")
                if ((transition.timeoutSeconds == null) != (transition.timeoutTargetStateId == null)) {
                    error(path, "Pending action timeoutSeconds and timeoutTargetStateId must be supplied together")
                }
                if (transition.guards.isNotEmpty() && transition.timeoutSeconds == null) {
                    error(path, "Guarded action requests require a fail-closed timeout and timeout target")
                }
            }
            TransitionTriggerKind.SENSOR_CONDITION_AUTO -> {
                if (!automaticPriorities.add(transition.sourceStateId to transition.priority)) {
                    error("$path.priority", "Automatic transitions leaving ${transition.sourceStateId} require unique priorities")
                }
                if (transition.actionKey != null) error("$path.actionKey", "Automatic sensor transitions cannot declare an action key")
                if (transition.guards.isEmpty()) error("$path.guards", "Automatic sensor transitions require at least one guard")
                if (transition.timeoutTargetStateId != null) error(path, "Sensor transitions do not use a pending-request timeout target")
            }
            TransitionTriggerKind.TIME_ELAPSED -> {
                if (!automaticPriorities.add(transition.sourceStateId to transition.priority)) {
                    error("$path.priority", "Automatic transitions leaving ${transition.sourceStateId} require unique priorities")
                }
                if (transition.actionKey != null || transition.guards.isNotEmpty()) error(path, "Time transitions cannot declare action keys or guards")
                if (transition.timeoutSeconds == null) error("$path.timeoutSeconds", "Time transitions require timeoutSeconds")
                if (transition.timeoutTargetStateId != null) error(path, "The transition target is the elapsed-time destination")
            }
        }
        duplicateValues(transition.guards.map { it.guardId }).forEach { error("$path.guards", "Guard ID '$it' is duplicated") }
        transition.guards.forEachIndexed { guardIndex, guard ->
            val guardPath = "$path.guards[$guardIndex]"
            if (!guard.guardId.matches(ID_PATTERN)) error("$guardPath.guardId", "Use lowercase letters, digits, and hyphens")
            if (guard.source.subsystemUid.isBlank() || guard.source.fieldUid.isBlank()) error("$guardPath.source", "A stable typed source is required")
            if (!guard.tolerance.isFinite() || guard.tolerance < 0.0) error("$guardPath.tolerance", "Tolerance must be finite and non-negative")
            if (guard.maxStalenessMs != null && guard.maxStalenessMs <= 0L) error("$guardPath.maxStalenessMs", "maxStalenessMs must be greater than 0")
            if (listOfNotNull(guard.expectedDoubleValue, guard.expectedBooleanValue, guard.expectedStringValue).size != 1) {
                error(guardPath, "A guard requires exactly one typed expected value")
            }
        }
    }

    document.interlocks.forEachIndexed { index, interlock ->
        val path = "interlocks[$index]"
        if (!interlock.ruleId.matches(ID_PATTERN)) error("$path.ruleId", "Use lowercase letters, digits, and hyphens")
        if (!interlock.conditionThreshold.isFinite()) error("$path.conditionThreshold", "Threshold must be finite")
        if (interlock.clampMinimum == null && interlock.clampMaximum == null) error(path, "An interlock must define a clamp")
        if (interlock.clampMinimum?.isFinite() == false || interlock.clampMaximum?.isFinite() == false) error(path, "Clamp values must be finite")
        if (interlock.clampMinimum != null && interlock.clampMaximum != null && interlock.clampMinimum > interlock.clampMaximum) {
            error(path, "Clamp minimum cannot exceed clamp maximum")
        }
    }
    document.healthFallbacks.forEachIndexed { index, policy ->
        val path = "healthFallbacks[$index]"
        if (!policy.policyId.matches(ID_PATTERN)) error("$path.policyId", "Use lowercase letters, digits, and hyphens")
        if (policy.source.subsystemUid.isBlank() || policy.source.fieldUid.isBlank()) error("$path.source", "A stable typed source is required")
        if (policy.fallbackStateId !in stateIds) error("$path.fallbackStateId", "Fallback state '${policy.fallbackStateId}' is not declared")
    }

    // Check dead-end trap states (states with no outgoing transitions, excluding terminal fault/disabled states)
    if (document.states.size > 1) {
        val statesWithOutgoing = document.transitions.mapTo(mutableSetOf()) { it.sourceStateId }
        document.states.map { it.stateId }.forEach { stateId ->
            if (stateId != document.faultStateId && stateId != document.disabledStateId && stateId !in statesWithOutgoing) {
                issues += SuperstructureValidationIssue(
                    SuperstructureIssueSeverity.WARNING,
                    "states",
                    "State '$stateId' has no outgoing transitions (dead-end state)",
                )
            }
        }
    }

    // The runtime can enter the fault preset from any state when target preflight/application
    // fails. It therefore has an implicit safety edge and must not require a fabricated student
    // transition merely to satisfy graph reachability.
    val reachable = linkedSetOf(document.initialStateId, document.faultStateId)
    val queue = ArrayDeque<String>().apply {
        add(document.initialStateId)
        if (document.faultStateId != document.initialStateId) add(document.faultStateId)
    }
    while (queue.isNotEmpty()) {
        val source = queue.removeFirst()
        document.transitions.filter { it.sourceStateId == source }.forEach { edge ->
            if (reachable.add(edge.targetStateId)) queue.add(edge.targetStateId)
            edge.timeoutTargetStateId?.let { if (reachable.add(it)) queue.add(it) }
        }
    }
    (stateIds - reachable).forEach { error("states", "State '$it' is unreachable from the initial state") }

    return issues
}

/** Validates every reference against generated subsystem plumbing and the action catalog. */
fun validateSuperstructureProject(
    document: SuperstructureDocument,
    subsystems: List<SubsystemDocument>,
    actionKeys: Set<String>,
    parameterlessActionKeys: Set<String> = actionKeys,
): List<SuperstructureValidationIssue> {
    val issues = validateSuperstructureDocument(document).toMutableList()
    fun error(path: String, message: String) {
        issues += SuperstructureValidationIssue(SuperstructureIssueSeverity.ERROR, path, message)
    }
    val byUid = subsystems.associateBy { it.uid }
    fun resolve(reference: SuperstructureFieldReference, path: String): Pair<SubsystemDocument, SubsystemStateFieldDocument>? {
        val subsystem = byUid[reference.subsystemUid]
        if (subsystem == null) {
            error(path, "Subsystem UID '${reference.subsystemUid}' is not declared in .ares/subsystems")
            return null
        }
        if (!subsystem.implementation.kind.isAresGenerated()) {
            error(path, "Hand-authored subsystem '${subsystem.documentId}' requires an explicit typed superstructure adapter")
            return null
        }
        val field = subsystem.stateFields.singleOrNull { it.uid == reference.fieldUid }
        if (field == null) error(path, "Field UID '${reference.fieldUid}' is not declared by subsystem '${subsystem.documentId}'")
        return field?.let { subsystem to it }
    }

    document.transitions.forEachIndexed { edgeIndex, edge ->
        if (edge.triggerKind == TransitionTriggerKind.ACTION_REQUEST && edge.actionKey !in actionKeys) {
            error("transitions[$edgeIndex].actionKey", "Action '${edge.actionKey}' is not present in the project action catalog")
        } else if (edge.triggerKind == TransitionTriggerKind.ACTION_REQUEST && edge.actionKey !in parameterlessActionKeys) {
            error("transitions[$edgeIndex].actionKey", "Superstructure request actions must be parameterless")
        }
        edge.guards.forEachIndexed guardLoop@ { guardIndex, guard ->
            val path = "transitions[$edgeIndex].guards[$guardIndex]"
            val field = resolve(guard.source, "$path.source")?.second ?: return@guardLoop
            val correctType = when (field.type) {
                SubsystemValueType.DOUBLE, SubsystemValueType.INT -> guard.expectedDoubleValue != null
                SubsystemValueType.BOOLEAN -> guard.expectedBooleanValue != null
                SubsystemValueType.STRING -> guard.expectedStringValue != null
            }
            if (!correctType) error(path, "Guard expected value must match ${field.type}")
        }
    }
    document.states.forEachIndexed { stateIndex, state ->
        state.subsystemTargets.forEachIndexed targetLoop@ { targetIndex, target ->
            val path = "states[$stateIndex].subsystemTargets[$targetIndex]"
            val field = resolve(target.target, path)?.second
                ?: return@targetLoop
            if (field.role != SubsystemFieldRole.TARGET) error(path, "Superstructure targets may command only TARGET fields")
            val constantTypeMatches = when (field.type) {
                SubsystemValueType.DOUBLE, SubsystemValueType.INT -> target.constantDoubleValue != null
                SubsystemValueType.BOOLEAN -> target.constantBooleanValue != null
                SubsystemValueType.STRING -> target.constantStringValue != null
            }
            if (target.targetMode == SuperstructureTargetMode.CONSTANT && !constantTypeMatches) {
                error(path, "Constant value must match ${field.type}")
            }
            if (target.targetMode == SuperstructureTargetMode.DYNAMIC_LUT && field.type !in setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)) {
                error(path, "Dynamic LUT outputs may command only numeric TARGET fields")
            }
            target.source?.let { source ->
                val sourceField = resolve(source, "$path.source")?.second ?: return@let
                if (target.targetMode == SuperstructureTargetMode.DYNAMIC_LUT && sourceField.type !in setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)) {
                    error("$path.source", "Dynamic LUT inputs must be numeric")
                }
                if (target.targetMode == SuperstructureTargetMode.PASS_THROUGH && sourceField.type != field.type) {
                    error("$path.source", "Pass-through source type ${sourceField.type} does not match target type ${field.type}")
                }
                if (target.targetMode == SuperstructureTargetMode.PASS_THROUGH && sourceField.unit.orEmpty() != field.unit.orEmpty()) {
                    error("$path.source", "Pass-through units '${sourceField.unit.orEmpty()}' and '${field.unit.orEmpty()}' do not match")
                }
                if (target.targetMode == SuperstructureTargetMode.DYNAMIC_LUT) {
                    val lut = document.luts.singleOrNull { it.lutId == target.lutId }
                    if (lut != null && lut.inputUnit != sourceField.unit.orEmpty()) {
                        error("$path.lutId", "LUT input unit '${lut.inputUnit}' must match source unit '${sourceField.unit.orEmpty()}'")
                    }
                    if (lut != null && lut.outputUnit != field.unit.orEmpty()) {
                        error("$path.lutId", "LUT output unit '${lut.outputUnit}' must match target unit '${field.unit.orEmpty()}'")
                    }
                }
            }
        }
    }
    document.interlocks.forEachIndexed { index, interlock ->
        val path = "interlocks[$index]"
        val primary = resolve(interlock.primary, "$path.primary")?.second
        if (primary != null && primary.type !in setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)) {
            error("$path.primary", "Interlock source must be numeric")
        }
        val constrained = resolve(interlock.constrained, "$path.constrained")?.second
        if (constrained != null && (constrained.role != SubsystemFieldRole.TARGET || constrained.type !in setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT))) {
            error(path, "Interlocks may clamp only numeric TARGET fields")
        }
    }
    fun validateNeutralPreset(stateId: String, path: String, label: String) {
        document.states.singleOrNull { it.stateId == stateId }?.subsystemTargets?.forEachIndexed { index, target ->
            val targetPath = "$path.targets[$index]"
            val field = resolve(target.target, targetPath)?.second
            if (field != null && target.targetMode == SuperstructureTargetMode.CONSTANT) {
                val neutral = when (field.type) {
                    SubsystemValueType.DOUBLE, SubsystemValueType.INT -> target.constantDoubleValue == field.numericDefault()
                    SubsystemValueType.BOOLEAN -> target.constantBooleanValue == field.defaultBoolean
                    SubsystemValueType.STRING -> target.constantStringValue == field.defaultText
                }
                if (!neutral) error(targetPath, "$label targets must equal the subsystem field's declared safe default")
            } else if (field != null) {
                error(targetPath, "$label targets must be constants")
            }
        }
    }
    document.healthFallbacks.forEachIndexed { index, policy ->
        resolve(policy.source, "healthFallbacks[$index].source")
    }
    document.states.forEachIndexed { stateIndex, state ->
        state.onExitActionKeys.forEachIndexed { actionIndex, actionKey ->
            if (actionKey !in actionKeys) {
                error("states[$stateIndex].onExitActionKeys[$actionIndex]", "Action '$actionKey' is not declared by the project catalog")
            } else if (actionKey !in parameterlessActionKeys) {
                error("states[$stateIndex].onExitActionKeys[$actionIndex]", "Lifecycle actions must be parameterless")
            }
        }
        state.onEntryActionKeys.forEachIndexed { actionIndex, actionKey ->
            if (actionKey !in actionKeys) {
                error("states[$stateIndex].onEntryActionKeys[$actionIndex]", "Action '$actionKey' is not declared by the project catalog")
            } else if (actionKey !in parameterlessActionKeys) {
                error("states[$stateIndex].onEntryActionKeys[$actionIndex]", "Lifecycle actions must be parameterless")
            }
        }
    }
    validateNeutralPreset(document.faultStateId, "faultStateId", "Fault-state")
    if (document.disabledPolicy == SuperstructureDisabledPolicy.FORCE_SAFE_AND_REJECT_REQUESTS) {
        validateNeutralPreset(document.disabledStateId, "disabledStateId", "Disabled-state")
    }
    return issues
}

object SuperstructureDocumentCodec {
    private val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

    fun encode(document: SuperstructureDocument): String {
        requireValid(document)
        return gson.toJson(document)
    }

    fun decode(json: String): SuperstructureDocument {
        val document = try {
            val root = parseJsonElement(json)
            require(root.isJsonObject) { "Superstructure document must be an object" }
            validateJsonShape(root.asJsonObject)
            val parsed = gson.fromJson(root, SuperstructureDocument::class.java)
                ?: error("Superstructure document is empty")
            normalize(parsed)
        } catch (error: Exception) {
            throw IllegalArgumentException("Superstructure document is not valid: ${error.message}", error)
        }
        requireValid(document)
        return document
    }

    fun contentHash(document: SuperstructureDocument): String = MessageDigest.getInstance("SHA-256")
        .digest(encode(document).toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    // Gson can populate Kotlin non-null fields with null when older or hand-edited JSON omits a
    // value. These Elvis branches are therefore a deliberate untrusted-data boundary even though
    // Kotlin's static types make them look redundant to the compiler.
    @Suppress("USELESS_ELVIS")
    private fun normalize(document: SuperstructureDocument): SuperstructureDocument = SuperstructureDocument(
        superstructureId = document.superstructureId ?: "",
        displayName = document.displayName ?: document.superstructureId ?: "",
        description = document.description ?: "",
        schemaVersion = document.schemaVersion ?: ARES_SUPERSTRUCTURE_SCHEMA_VERSION,
        initialStateId = document.initialStateId ?: "",
        states = document.states.orEmpty().map { s ->
            SuperstructureStatePreset(
                stateId = s.stateId ?: "",
                displayName = s.displayName ?: s.stateId ?: "",
                description = s.description ?: "",
                subsystemTargets = s.subsystemTargets.orEmpty().map { t ->
                    SuperstructureSubsystemTarget(
                        target = t.target ?: SuperstructureFieldReference("", ""),
                        targetMode = t.targetMode ?: SuperstructureTargetMode.CONSTANT,
                        constantDoubleValue = t.constantDoubleValue,
                        constantBooleanValue = t.constantBooleanValue,
                        constantStringValue = t.constantStringValue,
                        lutId = t.lutId,
                        source = t.source,
                    )
                },
                onExitActionKeys = s.onExitActionKeys.orEmpty(),
                onEntryActionKeys = s.onEntryActionKeys.orEmpty(),
                timeoutSeconds = s.timeoutSeconds,
                timeoutTargetStateId = s.timeoutTargetStateId,
            )
        },
        transitions = document.transitions.orEmpty().map { t ->
            StateTransitionEdge(
                transitionId = t.transitionId ?: "",
                sourceStateId = t.sourceStateId ?: "",
                targetStateId = t.targetStateId ?: "",
                triggerKind = t.triggerKind ?: TransitionTriggerKind.ACTION_REQUEST,
                actionKey = t.actionKey,
                guards = t.guards.orEmpty().map { g ->
                    TransitionGuard(
                        guardId = g.guardId ?: "",
                        source = g.source ?: SuperstructureFieldReference("", ""),
                        comparison = g.comparison ?: InterlockComparison.EQUALS_STATE,
                        expectedDoubleValue = g.expectedDoubleValue,
                        expectedBooleanValue = g.expectedBooleanValue,
                        expectedStringValue = g.expectedStringValue,
                        tolerance = g.tolerance ?: 1e-4,
                        maxStalenessMs = g.maxStalenessMs,
                    )
                },
                priority = t.priority ?: 0,
                debounceMs = t.debounceMs ?: 0L,
                timeoutSeconds = t.timeoutSeconds,
                timeoutTargetStateId = t.timeoutTargetStateId,
            )
        },
        interlocks = document.interlocks.orEmpty().map { i ->
            SuperstructureInterlockRule(
                ruleId = i.ruleId ?: "",
                description = i.description ?: "",
                primary = i.primary ?: SuperstructureFieldReference("", ""),
                conditionComparison = i.conditionComparison ?: InterlockComparison.LESS_THAN,
                conditionThreshold = i.conditionThreshold ?: 0.0,
                constrained = i.constrained ?: SuperstructureFieldReference("", ""),
                clampMinimum = i.clampMinimum,
                clampMaximum = i.clampMaximum,
            )
        },
        healthFallbacks = document.healthFallbacks.orEmpty().map { h ->
            SuperstructureHealthFallbackPolicy(
                policyId = h.policyId ?: "",
                source = h.source ?: SuperstructureFieldReference("", ""),
                fallbackStateId = h.fallbackStateId ?: "",
                latchFault = h.latchFault ?: true,
                description = h.description ?: "",
            )
        },
        luts = document.luts.orEmpty().map { l ->
            SuperstructureDynamicLut(
                lutId = l.lutId ?: "",
                displayName = l.displayName ?: l.lutId ?: "",
                inputUnit = l.inputUnit ?: "",
                outputUnit = l.outputUnit ?: "",
                interpolation = l.interpolation ?: LutInterpolationMethod.LINEAR,
                controlPoints = l.controlPoints.orEmpty().map { cp ->
                    LutControlPoint(
                        inputX = cp.inputX ?: 0.0,
                        outputY = cp.outputY ?: 0.0,
                    )
                },
            )
        },
        faultStateId = document.faultStateId ?: "",
        disabledPolicy = document.disabledPolicy ?: SuperstructureDisabledPolicy.FORCE_SAFE_AND_REJECT_REQUESTS,
        disabledStateId = document.disabledStateId ?: document.faultStateId ?: "",
        nodeLayouts = document.nodeLayouts.orEmpty(),
    )

    private fun requireValid(document: SuperstructureDocument) {
        val errors = validateSuperstructureDocument(document).filter { it.severity == SuperstructureIssueSeverity.ERROR }
        require(errors.isEmpty()) { errors.joinToString("; ") { "${it.path}: ${it.message}" } }
    }

    private fun validateJsonShape(root: JsonObject) {
        exact(root, ROOT_FIELDS, "$")
        requireInteger(root, "schemaVersion", "$")
        require(root.get("schemaVersion").asInt == ARES_SUPERSTRUCTURE_SCHEMA_VERSION) {
            "Unsupported superstructure schema version ${root.get("schemaVersion").asInt}"
        }
        arrayObjects(root, "states", "$").forEachIndexed { index, state ->
            exact(state, STATE_FIELDS, "$.states[$index]")
            arrayObjects(state, "subsystemTargets", "$.states[$index]").forEachIndexed { targetIndex, target ->
                exact(target, TARGET_FIELDS, "$.states[$index].subsystemTargets[$targetIndex]")
                exact(
                    requiredObject(target, "target", "$.states[$index].subsystemTargets[$targetIndex]"),
                    REFERENCE_FIELDS,
                    "$.states[$index].subsystemTargets[$targetIndex].target",
                )
                optionalObject(target, "source", "$.states[$index].subsystemTargets[$targetIndex]")?.let {
                    exact(it, REFERENCE_FIELDS, "$.states[$index].subsystemTargets[$targetIndex].source")
                }
            }
        }
        arrayObjects(root, "transitions", "$").forEachIndexed { index, edge ->
            exact(edge, TRANSITION_FIELDS, "$.transitions[$index]")
            arrayObjects(edge, "guards", "$.transitions[$index]").forEachIndexed { guardIndex, guard ->
                exact(guard, GUARD_FIELDS, "$.transitions[$index].guards[$guardIndex]")
                exact(
                    requiredObject(guard, "source", "$.transitions[$index].guards[$guardIndex]"),
                    REFERENCE_FIELDS,
                    "$.transitions[$index].guards[$guardIndex].source",
                )
            }
        }
        arrayObjects(root, "interlocks", "$").forEachIndexed { index, interlock ->
            exact(interlock, INTERLOCK_FIELDS, "$.interlocks[$index]")
            exact(requiredObject(interlock, "primary", "$.interlocks[$index]"), REFERENCE_FIELDS, "$.interlocks[$index].primary")
            exact(requiredObject(interlock, "constrained", "$.interlocks[$index]"), REFERENCE_FIELDS, "$.interlocks[$index].constrained")
        }
        arrayObjects(root, "healthFallbacks", "$").forEachIndexed { index, policy ->
            exact(policy, HEALTH_FALLBACK_FIELDS, "$.healthFallbacks[$index]")
            exact(requiredObject(policy, "source", "$.healthFallbacks[$index]"), REFERENCE_FIELDS, "$.healthFallbacks[$index].source")
        }
        arrayObjects(root, "luts", "$").forEachIndexed { index, lut ->
            exact(lut, LUT_FIELDS, "$.luts[$index]")
            arrayObjects(lut, "controlPoints", "$.luts[$index]").forEachIndexed { pointIndex, point ->
                exact(point, POINT_FIELDS, "$.luts[$index].controlPoints[$pointIndex]")
            }
        }
        optionalObject(root, "nodeLayouts", "$")?.let { layoutsObj ->
            layoutsObj.entrySet().forEach { (stateKey, layoutEl) ->
                require(layoutEl.isJsonObject) { "$.nodeLayouts.$stateKey must be an object" }
                exact(layoutEl.asJsonObject, NODE_LAYOUT_FIELDS, "$.nodeLayouts.$stateKey")
            }
        }
    }

    private fun exact(value: JsonObject, fields: Set<String>, path: String) {
        val unknown = value.entrySet().mapTo(linkedSetOf()) { it.key } - fields
        require(unknown.isEmpty()) { "Unknown fields at $path: ${unknown.sorted().joinToString()}" }
    }

    private fun requireInteger(value: JsonObject, field: String, path: String) {
        val element = value.get(field)
        require(element != null && element.isJsonPrimitive && element.asJsonPrimitive.isNumber) { "$path.$field must be an integer" }
        val number = element.asBigDecimal
        require(number.stripTrailingZeros().scale() <= 0) { "$path.$field must be an integer" }
    }

    private fun arrayObjects(value: JsonObject, field: String, path: String): List<JsonObject> {
        val element = value.get(field) ?: return emptyList()
        require(element.isJsonArray) { "$path.$field must be an array" }
        return element.asJsonArray.mapIndexed { index, child ->
            require(child.isJsonObject) { "$path.$field[$index] must be an object" }
            child.asJsonObject
        }
    }

    private fun requiredObject(value: JsonObject, field: String, path: String): JsonObject {
        val element = value.get(field)
        require(element != null && element.isJsonObject) { "$path.$field must be an object" }
        return element.asJsonObject
    }

    private fun optionalObject(value: JsonObject, field: String, path: String): JsonObject? {
        val element: JsonElement = value.get(field) ?: return null
        if (element.isJsonNull) return null
        require(element.isJsonObject) { "$path.$field must be an object" }
        return element.asJsonObject
    }

    private val ROOT_FIELDS = setOf("superstructureId", "displayName", "description", "schemaVersion", "initialStateId", "states", "transitions", "interlocks", "healthFallbacks", "luts", "faultStateId", "disabledPolicy", "disabledStateId", "nodeLayouts")
    private val NODE_LAYOUT_FIELDS = setOf("x", "y")
    private val STATE_FIELDS = setOf("stateId", "displayName", "description", "subsystemTargets", "onExitActionKeys", "onEntryActionKeys", "timeoutSeconds", "timeoutTargetStateId")
    private val TARGET_FIELDS = setOf("target", "targetMode", "constantDoubleValue", "constantBooleanValue", "constantStringValue", "lutId", "source")
    private val TRANSITION_FIELDS = setOf("transitionId", "sourceStateId", "targetStateId", "triggerKind", "actionKey", "guards", "priority", "debounceMs", "timeoutSeconds", "timeoutTargetStateId")
    private val GUARD_FIELDS = setOf("guardId", "source", "comparison", "expectedDoubleValue", "expectedBooleanValue", "expectedStringValue", "tolerance", "maxStalenessMs")
    private val REFERENCE_FIELDS = setOf("subsystemUid", "fieldUid", "healthRequirement")
    private val INTERLOCK_FIELDS = setOf("ruleId", "description", "primary", "conditionComparison", "conditionThreshold", "constrained", "clampMinimum", "clampMaximum")
    private val HEALTH_FALLBACK_FIELDS = setOf("policyId", "source", "fallbackStateId", "latchFault", "description")
    private val LUT_FIELDS = setOf("lutId", "displayName", "inputUnit", "outputUnit", "interpolation", "controlPoints")
    private val POINT_FIELDS = setOf("inputX", "outputY")
}

private fun validateTimeout(value: Double?, path: String, issue: (String, String) -> Unit) {
    if (value != null && (!value.isFinite() || value <= 0.0 || value > 3600.0)) {
        issue(path, "Timeout must be finite and in (0, 3600] seconds")
    }
}

private fun <T> duplicateValues(values: List<T>): Set<T> = values.groupingBy { it }.eachCount()
    .filterValues { it > 1 }.keys

private fun SubsystemStateFieldDocument.numericDefault(): Double? = when (type) {
    SubsystemValueType.DOUBLE -> defaultNumber
    SubsystemValueType.INT -> defaultInt?.toDouble()
    else -> null
}

private val ID_PATTERN = Regex("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")
private val TYPE_ID_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*")
private val CAPABILITY_KEY_PATTERN = Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")
