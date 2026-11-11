package com.areslib.superstructure

import com.areslib.subsystem.InterlockComparison
import com.areslib.subsystem.SubsystemDocument

@DslMarker
annotation class SuperstructureDsl

/**
 * Builds and validates a [SuperstructureDocument] using a concise, text-first Kotlin DSL.
 *
 * ### Example
 * ```kotlin
 * val elevatorHeight = SuperstructureFieldReference("subsystem.elevator", "elevator-target-height")
 * val distanceMeters = SuperstructureFieldReference("subsystem.vision", "vision-distance-meters")
 *
 * val doc = superstructure(
 *     id = "scoring-coordinator",
 *     initialStateId = "STOWED",
 *     faultStateId = "STOWED",
 * ) {
 *     val heightLut = lut("distance-to-height", inputUnit = "m", outputUnit = "m") {
 *         point(1.0, 0.35)
 *         point(3.0, 0.85)
 *     }
 *     state("STOWED") {
 *         targetDouble(elevatorHeight, 0.0)
 *     }
 *     state("AIMING") {
 *         targetLut(elevatorHeight, heightLut, source = distanceMeters)
 *     }
 *     onActionTransition("request-aim", from = "STOWED", to = "AIMING", actionKey = "superstructure.aim")
 *     onActionTransition("request-stow", from = "AIMING", to = "STOWED", actionKey = "superstructure.stow")
 * }
 * ```
 */
fun superstructure(
    id: String,
    displayName: String = defaultSuperstructureDisplayName(id),
    initialStateId: String = "IDLE",
    faultStateId: String = "SAFE_NEUTRAL",
    disabledStateId: String = faultStateId,
    disabledPolicy: SuperstructureDisabledPolicy = SuperstructureDisabledPolicy.FORCE_SAFE_AND_REJECT_REQUESTS,
    block: SuperstructureBuilder.() -> Unit,
): SuperstructureDocument = SuperstructureBuilder(
    superstructureId = id,
    displayName = displayName,
    initialStateId = initialStateId,
    faultStateId = faultStateId,
    disabledStateId = disabledStateId,
    disabledPolicy = disabledPolicy,
).apply(block).build()

@SuperstructureDsl
class SuperstructureBuilder internal constructor(
    private val superstructureId: String,
    var displayName: String,
    var initialStateId: String,
    var faultStateId: String,
    var disabledStateId: String,
    var disabledPolicy: SuperstructureDisabledPolicy,
) {
    var description: String = "$displayName coordinated state machine."
    private val stateEntries = mutableListOf<SuperstructureStatePreset>()
    private val transitionEntries = mutableListOf<StateTransitionEdge>()
    private val interlockEntries = mutableListOf<SuperstructureInterlockRule>()
    private val healthFallbackEntries = mutableListOf<SuperstructureHealthFallbackPolicy>()
    private val lutEntries = mutableListOf<SuperstructureDynamicLut>()
    private val layoutEntries = linkedMapOf<String, StateNodeLayout>()

    /** Creates a typed [SuperstructureFieldReference] from explicit subsystem and field UIDs. */
    fun port(
        subsystemUid: String,
        fieldUid: String,
        healthRequirement: SuperstructurePortHealthRequirement = SuperstructurePortHealthRequirement.CONTROL_READY,
    ): SuperstructureFieldReference = SuperstructureFieldReference(
        subsystemUid = subsystemUid,
        fieldUid = fieldUid,
        healthRequirement = healthRequirement,
    )

    /**
     * Creates a typed [SuperstructureFieldReference] from a [SubsystemDocument] and its field ID or UID.
     */
    fun port(
        subsystem: SubsystemDocument,
        fieldIdOrUid: String,
        healthRequirement: SuperstructurePortHealthRequirement = SuperstructurePortHealthRequirement.CONTROL_READY,
    ): SuperstructureFieldReference {
        val field = subsystem.stateFields.firstOrNull {
            it.fieldId == fieldIdOrUid || it.uid == fieldIdOrUid
        } ?: throw IllegalArgumentException(
            "Field '$fieldIdOrUid' is not declared in subsystem '${subsystem.documentId}'"
        )
        return SuperstructureFieldReference(
            subsystemUid = subsystem.uid,
            fieldUid = field.uid,
            healthRequirement = healthRequirement,
        )
    }

    /** Declares a coordinated state preset and returns its `stateId`. */
    fun state(
        id: String,
        displayName: String = id,
        description: String = "",
        block: SuperstructureStateBuilder.() -> Unit,
    ): String {
        val builder = SuperstructureStateBuilder(id, displayName, description).apply(block)
        stateEntries += builder.build()
        return id
    }

    /** Declares a state transition edge with a custom trigger configuration. */
    fun transition(
        id: String,
        from: String,
        to: String,
        block: SuperstructureTransitionBuilder.() -> Unit,
    ): StateTransitionEdge {
        val edge = SuperstructureTransitionBuilder(id, from, to).apply(block).build()
        transitionEntries += edge
        return edge
    }

    /** Declares an action-requested transition edge from [from] to [to]. */
    fun onActionTransition(
        id: String,
        from: String,
        to: String,
        actionKey: String,
        block: SuperstructureTransitionBuilder.() -> Unit = {},
    ): StateTransitionEdge = transition(id, from, to) {
        triggerKind = TransitionTriggerKind.ACTION_REQUEST
        this.actionKey = actionKey
        block()
    }

    /** Declares an automatic sensor-guarded transition edge from [from] to [to]. */
    fun sensorTransition(
        id: String,
        from: String,
        to: String,
        priority: Int = 0,
        block: SuperstructureTransitionBuilder.() -> Unit,
    ): StateTransitionEdge = transition(id, from, to) {
        triggerKind = TransitionTriggerKind.SENSOR_CONDITION_AUTO
        this.priority = priority
        block()
    }

    /** Declares an automatic elapsed-time transition edge from [from] to [to]. */
    fun timedTransition(
        id: String,
        from: String,
        to: String,
        timeoutSeconds: Double,
        priority: Int = 0,
        debounceMs: Long = 0L,
    ): StateTransitionEdge = transition(id, from, to) {
        triggerKind = TransitionTriggerKind.TIME_ELAPSED
        this.timeoutSeconds = timeoutSeconds
        this.priority = priority
        this.debounceMs = debounceMs
    }

    /** Declares a cross-subsystem numeric clamp interlock rule. */
    fun interlock(
        id: String,
        primary: SuperstructureFieldReference,
        constrained: SuperstructureFieldReference,
        comparison: InterlockComparison = InterlockComparison.LESS_THAN,
        threshold: Double = 0.0,
        clampMinimum: Double? = null,
        clampMaximum: Double? = null,
        description: String = "",
    ): SuperstructureInterlockRule {
        val rule = SuperstructureInterlockRule(
            ruleId = id,
            description = description,
            primary = primary,
            conditionComparison = comparison,
            conditionThreshold = threshold,
            constrained = constrained,
            clampMinimum = clampMinimum,
            clampMaximum = clampMaximum,
        )
        interlockEntries += rule
        return rule
    }

    /** Declares a supervisory health fallback policy that routes unhealthy ports to [fallbackStateId]. */
    fun healthFallback(
        id: String,
        source: SuperstructureFieldReference,
        fallbackStateId: String = this.faultStateId,
        latchFault: Boolean = true,
        description: String = "",
    ): SuperstructureHealthFallbackPolicy {
        val policy = SuperstructureHealthFallbackPolicy(
            policyId = id,
            source = source,
            fallbackStateId = fallbackStateId,
            latchFault = latchFault,
            description = description,
        )
        healthFallbackEntries += policy
        return policy
    }

    /** Declares a 1D lookup table (LUT) and returns its `lutId`. */
    fun lut(
        id: String,
        displayName: String = defaultSuperstructureDisplayName(id),
        inputUnit: String = "",
        outputUnit: String = "",
        interpolation: LutInterpolationMethod = LutInterpolationMethod.LINEAR,
        block: SuperstructureLutBuilder.() -> Unit,
    ): String {
        val lutDoc = SuperstructureLutBuilder(id, displayName, inputUnit, outputUnit, interpolation).apply(block).build()
        lutEntries += lutDoc
        return id
    }

    /** Assigns an optional 2D visual layout coordinate for [stateId]. */
    fun layout(stateId: String, x: Double, y: Double) {
        layoutEntries[stateId] = StateNodeLayout(x = x, y = y)
    }

    internal fun build(): SuperstructureDocument {
        val document = SuperstructureDocument(
            superstructureId = superstructureId,
            displayName = displayName,
            description = description,
            initialStateId = initialStateId,
            states = stateEntries.toList(),
            transitions = transitionEntries.toList(),
            interlocks = interlockEntries.toList(),
            healthFallbacks = healthFallbackEntries.toList(),
            luts = lutEntries.toList(),
            faultStateId = faultStateId,
            disabledPolicy = disabledPolicy,
            disabledStateId = disabledStateId,
            nodeLayouts = layoutEntries.toMap(),
        )
        val errors = validateSuperstructureDocument(document).filter {
            it.severity == SuperstructureIssueSeverity.ERROR
        }
        require(errors.isEmpty()) {
            formatSuperstructureValidationReport(errors)
        }
        return document
    }
}

@SuperstructureDsl
class SuperstructureStateBuilder internal constructor(
    private val stateId: String,
    var displayName: String,
    var description: String,
) {
    var timeoutSeconds: Double? = null
    var timeoutTargetStateId: String? = null
    private val targets = mutableListOf<SuperstructureSubsystemTarget>()
    private val onEntryKeys = mutableListOf<String>()
    private val onExitKeys = mutableListOf<String>()

    /** Commands [target] to a constant numeric [value] while in this state. */
    fun targetDouble(target: SuperstructureFieldReference, value: Double) {
        targets += SuperstructureSubsystemTarget(
            target = target,
            targetMode = SuperstructureTargetMode.CONSTANT,
            constantDoubleValue = value,
        )
    }

    /** Commands [target] to a constant boolean [value] while in this state. */
    fun targetBoolean(target: SuperstructureFieldReference, value: Boolean) {
        targets += SuperstructureSubsystemTarget(
            target = target,
            targetMode = SuperstructureTargetMode.CONSTANT,
            constantBooleanValue = value,
        )
    }

    /** Commands [target] to a constant string/enum [value] while in this state. */
    fun targetString(target: SuperstructureFieldReference, value: String) {
        targets += SuperstructureSubsystemTarget(
            target = target,
            targetMode = SuperstructureTargetMode.CONSTANT,
            constantStringValue = value,
        )
    }

    /** Commands [target] dynamically by evaluating [lutId] at [source]. */
    fun targetLut(target: SuperstructureFieldReference, lutId: String, source: SuperstructureFieldReference) {
        targets += SuperstructureSubsystemTarget(
            target = target,
            targetMode = SuperstructureTargetMode.DYNAMIC_LUT,
            lutId = lutId,
            source = source,
        )
    }

    /** Commands [target] by passing through the live value of [source]. */
    fun targetPassThrough(target: SuperstructureFieldReference, source: SuperstructureFieldReference) {
        targets += SuperstructureSubsystemTarget(
            target = target,
            targetMode = SuperstructureTargetMode.PASS_THROUGH,
            source = source,
        )
    }

    /** Registers parameterless catalog action keys to run once upon entering this state. */
    fun onEntry(vararg actionKeys: String) {
        onEntryKeys += actionKeys
    }

    /** Registers parameterless catalog action keys to run once upon exiting this state. */
    fun onExit(vararg actionKeys: String) {
        onExitKeys += actionKeys
    }

    /** Configures a state-level dwell timeout and fail-closed destination state. */
    fun timeout(seconds: Double, targetStateId: String) {
        this.timeoutSeconds = seconds
        this.timeoutTargetStateId = targetStateId
    }

    internal fun build(): SuperstructureStatePreset = SuperstructureStatePreset(
        stateId = stateId,
        displayName = displayName,
        description = description,
        subsystemTargets = targets.toList(),
        onExitActionKeys = onExitKeys.toList(),
        onEntryActionKeys = onEntryKeys.toList(),
        timeoutSeconds = timeoutSeconds,
        timeoutTargetStateId = timeoutTargetStateId,
    )
}

@SuperstructureDsl
class SuperstructureTransitionBuilder internal constructor(
    private val transitionId: String,
    private val sourceStateId: String,
    private val targetStateId: String,
) {
    var triggerKind: TransitionTriggerKind = TransitionTriggerKind.ACTION_REQUEST
    var actionKey: String? = null
    var priority: Int = 0
    var debounceMs: Long = 0L
    var timeoutSeconds: Double? = null
    var timeoutTargetStateId: String? = null
    private val guards = mutableListOf<TransitionGuard>()

    /** Configures this edge as an action-request transition with optional pending-request timeout. */
    fun onAction(key: String, timeoutSeconds: Double? = null, timeoutTargetStateId: String? = null) {
        triggerKind = TransitionTriggerKind.ACTION_REQUEST
        actionKey = key
        this.timeoutSeconds = timeoutSeconds
        this.timeoutTargetStateId = timeoutTargetStateId
    }

    /** Configures this edge as an automatic sensor-condition transition. */
    fun onSensorCondition(priority: Int = 0) {
        triggerKind = TransitionTriggerKind.SENSOR_CONDITION_AUTO
        actionKey = null
        this.priority = priority
    }

    /** Configures this edge as an automatic elapsed-time transition. */
    fun afterSeconds(seconds: Double, priority: Int = 0) {
        triggerKind = TransitionTriggerKind.TIME_ELAPSED
        actionKey = null
        timeoutSeconds = seconds
        this.priority = priority
    }

    /** Adds a numeric guard condition that must hold before the transition completes. */
    fun guardDouble(
        id: String,
        source: SuperstructureFieldReference,
        comparison: InterlockComparison = InterlockComparison.GREATER_THAN,
        expected: Double,
        tolerance: Double = 1e-4,
        maxStalenessMs: Long? = null,
    ) {
        guards += TransitionGuard(
            guardId = id,
            source = source,
            comparison = comparison,
            expectedDoubleValue = expected,
            tolerance = tolerance,
            maxStalenessMs = maxStalenessMs,
        )
    }

    /** Adds a boolean guard condition that must hold before the transition completes. */
    fun guardBoolean(
        id: String,
        source: SuperstructureFieldReference,
        expected: Boolean = true,
        maxStalenessMs: Long? = null,
    ) {
        guards += TransitionGuard(
            guardId = id,
            source = source,
            comparison = InterlockComparison.EQUALS_STATE,
            expectedBooleanValue = expected,
            maxStalenessMs = maxStalenessMs,
        )
    }

    /** Adds a string/enum guard condition that must hold before the transition completes. */
    fun guardString(
        id: String,
        source: SuperstructureFieldReference,
        expected: String,
        maxStalenessMs: Long? = null,
    ) {
        guards += TransitionGuard(
            guardId = id,
            source = source,
            comparison = InterlockComparison.EQUALS_STATE,
            expectedStringValue = expected,
            maxStalenessMs = maxStalenessMs,
        )
    }

    internal fun build(): StateTransitionEdge = StateTransitionEdge(
        transitionId = transitionId,
        sourceStateId = sourceStateId,
        targetStateId = targetStateId,
        triggerKind = triggerKind,
        actionKey = actionKey,
        guards = guards.toList(),
        priority = priority,
        debounceMs = debounceMs,
        timeoutSeconds = timeoutSeconds,
        timeoutTargetStateId = timeoutTargetStateId,
    )
}

@SuperstructureDsl
class SuperstructureLutBuilder internal constructor(
    private val lutId: String,
    var displayName: String,
    var inputUnit: String,
    var outputUnit: String,
    var interpolation: LutInterpolationMethod,
) {
    private val points = mutableListOf<LutControlPoint>()

    /** Appends a control point `(inputX, outputY)` in strictly increasing `inputX` order. */
    fun point(inputX: Double, outputY: Double) {
        points += LutControlPoint(inputX = inputX, outputY = outputY)
    }

    internal fun build(): SuperstructureDynamicLut = SuperstructureDynamicLut(
        lutId = lutId,
        displayName = displayName,
        inputUnit = inputUnit,
        outputUnit = outputUnit,
        interpolation = interpolation,
        controlPoints = points.toList(),
    )
}

/** Formats [SuperstructureValidationIssue] entries into an actionable multi-line diagnostic report. */
fun formatSuperstructureValidationReport(issues: List<SuperstructureValidationIssue>): String {
    if (issues.isEmpty()) return "Superstructure document is valid."
    val errors = issues.filter { it.severity == SuperstructureIssueSeverity.ERROR }
    val warnings = issues.filter { it.severity == SuperstructureIssueSeverity.WARNING }
    return buildString {
        appendLine("Superstructure validation found ${errors.size} error(s) and ${warnings.size} warning(s):")
        errors.forEach { issue ->
            appendLine("  [ERROR] ${issue.path}: ${issue.message}")
        }
        warnings.forEach { issue ->
            appendLine("  [WARN]  ${issue.path}: ${issue.message}")
        }
    }.trimEnd()
}

internal fun defaultSuperstructureDisplayName(id: String): String =
    id.trim()
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .split(Regex("[^A-Za-z0-9]+"))
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token.lowercase().replaceFirstChar { it.titlecase() }
        }
        .ifBlank { id }
