package com.ares.analytics.viewmodel.superstructure

import com.ares.analytics.service.project.persistence.ProjectDocumentDiagnostic
import com.ares.analytics.service.project.ProjectSessionRevision
import com.areslib.action.RobotAction
import com.areslib.catalog.ActionDescriptor
import com.areslib.reducer.rootReducer
import com.areslib.sequencer.Task
import com.areslib.state.RobotState
import com.areslib.Store
import com.areslib.subsystem.InterlockComparison
import com.areslib.subsystem.isAresGenerated
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemStateFieldDocument
import com.areslib.subsystem.SubsystemValueType
import com.areslib.superstructure.StateTransitionEdge
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.SuperstructureFieldReference
import com.areslib.superstructure.SuperstructureInterlockRule
import com.areslib.superstructure.SuperstructureIssueSeverity
import com.areslib.superstructure.SuperstructurePortHealthBits
import com.areslib.superstructure.SuperstructureRuntime
import com.areslib.superstructure.SuperstructureRuntimeBinding
import com.areslib.superstructure.SuperstructureRuntimeState
import com.areslib.superstructure.SuperstructureSubsystemTarget
import com.areslib.superstructure.TransitionGuard
import com.areslib.superstructure.TransitionTriggerKind
import com.areslib.superstructure.validateSuperstructureProject

internal object SuperstructureDocumentMutators {

    fun buildSaveReviewSummary(draft: SuperstructureDocument): List<String> = listOf(
        "${draft.states.size} complete state presets",
        "${draft.transitions.size} transitions (${draft.transitions.count { it.triggerKind == TransitionTriggerKind.ACTION_REQUEST }} driver/autonomous actions)",
        "${draft.interlocks.size} cross-mechanism clamps",
        "${draft.luts.size} lookup tables",
        "${draft.healthFallbacks.size} cached-port health fallbacks",
        "${draft.states.sumOf { it.onEntryActionKeys.size + it.onExitActionKeys.size }} lifecycle actions",
        "Fault destination: ${draft.faultStateId}",
        "Disabled destination: ${draft.disabledStateId} (${draft.disabledPolicy.name.lowercase().replace('_', ' ')})",
    )

    fun validateStudioState(state: SuperstructureStudioState): SuperstructureStudioState {
        val document = state.draft ?: return state.copy(validationErrors = emptyList(), validationWarnings = emptyList())
        val issues = validateSuperstructureProject(
            document,
            state.subsystems,
            state.actions.mapTo(linkedSetOf()) { it.key },
            state.parameterlessActions.mapTo(linkedSetOf()) { it.key },
        )
        return state.copy(
            validationErrors = issues.filter { it.severity == SuperstructureIssueSeverity.ERROR }.map { "${it.path}: ${it.message}" }.distinct(),
            validationWarnings = issues.filter { it.severity == SuperstructureIssueSeverity.WARNING }.map { "${it.path}: ${it.message}" }.distinct(),
        )
    }

    fun neutralTarget(
        subsystems: List<SubsystemDocument>,
        reference: SuperstructureFieldReference,
    ): SuperstructureSubsystemTarget {
        val subsystem = subsystems.firstOrNull { it.uid == reference.subsystemUid }
        val field = subsystem?.stateFields?.firstOrNull { it.uid == reference.fieldUid }
        return when (field?.type) {
            SubsystemValueType.DOUBLE -> SuperstructureSubsystemTarget(reference, constantDoubleValue = field.defaultNumber ?: 0.0)
            SubsystemValueType.INT -> SuperstructureSubsystemTarget(reference, constantDoubleValue = (field.defaultInt ?: 0).toDouble())
            SubsystemValueType.BOOLEAN -> SuperstructureSubsystemTarget(reference, constantBooleanValue = field.defaultBoolean ?: false)
            SubsystemValueType.STRING -> SuperstructureSubsystemTarget(reference, constantStringValue = field.defaultText.orEmpty())
            null -> SuperstructureSubsystemTarget(reference)
        }
    }

    fun typedGuard(
        field: SuperstructureFieldOption,
        uniqueId: (String) -> String,
    ): TransitionGuard = when (field.field.type) {
        SubsystemValueType.DOUBLE, SubsystemValueType.INT -> TransitionGuard(
            guardId = uniqueId("guard-${field.field.fieldId}"),
            source = field.reference,
            comparison = InterlockComparison.GREATER_THAN,
            expectedDoubleValue = 0.0,
        )
        SubsystemValueType.BOOLEAN -> TransitionGuard(
            guardId = uniqueId("guard-${field.field.fieldId}"),
            source = field.reference,
            expectedBooleanValue = true,
        )
        SubsystemValueType.STRING -> TransitionGuard(
            guardId = uniqueId("guard-${field.field.fieldId}"),
            source = field.reference,
            expectedStringValue = field.field.defaultText.orEmpty(),
        )
    }

    fun uniqueId(document: SuperstructureDocument?, base: String): String {
        val normalized = base.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').ifBlank { "item" }
        val used = buildSet {
            document?.transitions?.forEach { add(it.transitionId) }
            document?.interlocks?.forEach { add(it.ruleId) }
            document?.healthFallbacks?.forEach { add(it.policyId) }
            document?.luts?.forEach { add(it.lutId) }
            document?.transitions?.flatMap { it.guards }?.forEach { add(it.guardId) }
        }
        if (normalized !in used) return normalized
        var suffix = 2
        while ("$normalized-$suffix" in used) suffix++
        return "$normalized-$suffix"
    }

    fun appendInterlock(
        document: SuperstructureDocument,
        source: SuperstructureFieldOption,
        constrained: SuperstructureFieldOption,
        sourceFields: List<SuperstructureFieldOption>,
        targetFields: List<SuperstructureFieldOption>,
        uniqueId: (String) -> String,
    ): SuperstructureDocument {
        require(source.field.type in setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)) { "Interlock evidence must be numeric." }
        require(constrained.field.role == SubsystemFieldRole.TARGET && constrained.field.type in setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)) {
            "Interlocks can clamp only numeric target fields."
        }
        val rule = SuperstructureInterlockRule(
            ruleId = uniqueId("limit-${constrained.subsystem.documentId}-${constrained.field.fieldId}"),
            description = "",
            primary = source.reference,
            constrained = constrained.reference,
            conditionThreshold = 0.0,
            clampMinimum = constrained.field.minimum,
            clampMaximum = constrained.field.defaultNumber ?: constrained.field.defaultInt?.toDouble() ?: 0.0,
        )
        return document.copy(
            interlocks = document.interlocks + rule.copy(
                description = automaticInterlockDescription(rule, sourceFields, targetFields)
                    ?: "Clamp the selected target while the reviewed safety condition is active.",
            ),
        )
    }

    fun updateInterlockPreservingDescription(
        document: SuperstructureDocument,
        rule: SuperstructureInterlockRule,
        sourceFields: List<SuperstructureFieldOption>,
        targetFields: List<SuperstructureFieldOption>,
    ): SuperstructureDocument {
        val existing = document.interlocks.firstOrNull { it.ruleId == rule.ruleId }
        val descriptionWasEdited = existing != null && rule.description != existing.description
        val preserveStudentDescription = descriptionWasEdited || existing?.let { current ->
            !isAutomaticInterlockDescription(current, sourceFields, targetFields)
        } == true
        val updated = if (preserveStudentDescription) {
            rule
        } else {
            rule.copy(
                description = automaticInterlockDescription(rule, sourceFields, targetFields)
                    ?: rule.description,
            )
        }
        return document.copy(interlocks = document.interlocks.map { if (it.ruleId == rule.ruleId) updated else it })
    }

    fun addPrioritizedTransition(
        document: SuperstructureDocument,
        edge: StateTransitionEdge,
    ): SuperstructureDocument {
        val prioritized = if (edge.triggerKind == TransitionTriggerKind.ACTION_REQUEST) edge else {
            val next = document.transitions.asSequence()
                .filter { it.sourceStateId == edge.sourceStateId && it.triggerKind != TransitionTriggerKind.ACTION_REQUEST }
                .map { it.priority }
                .maxOrNull()?.plus(10) ?: 0
            edge.copy(priority = next)
        }
        return document.copy(transitions = document.transitions + prioritized)
    }

    fun appendHealthFallback(
        document: SuperstructureDocument,
        source: SuperstructureFieldOption,
        uniqueId: (String) -> String,
    ): SuperstructureDocument {
        require(document.healthFallbacks.none { it.source == source.reference }) {
            "That cached port already has a health fallback."
        }
        return document.copy(
            healthFallbacks = document.healthFallbacks + com.areslib.superstructure.SuperstructureHealthFallbackPolicy(
                policyId = uniqueId("health-${source.subsystem.documentId}-${source.field.fieldId}"),
                source = source.reference,
                fallbackStateId = document.faultStateId,
                description = "Enter the reviewed neutral fault posture when ${source.label} is unhealthy.",
            ),
        )
    }

    fun appendLut(
        document: SuperstructureDocument,
        uniqueId: (String) -> String,
    ): SuperstructureDocument = document.copy(
        luts = document.luts + com.areslib.superstructure.SuperstructureDynamicLut(
            lutId = uniqueId("lookup"),
            displayName = "New lookup table",
            interpolation = com.areslib.superstructure.LutInterpolationMethod.LINEAR,
            controlPoints = listOf(
                com.areslib.superstructure.LutControlPoint(0.0, 0.0),
                com.areslib.superstructure.LutControlPoint(1.0, 1.0),
            ),
        ),
    )

    fun evaluateReviewSave(rawState: SuperstructureStudioState): SuperstructureStudioState {
        val state = validateStudioState(rawState)
        val draft = state.draft ?: return state
        if (state.validationErrors.isNotEmpty() || state.editorErrors.isNotEmpty()) {
            return state.copy(error = "Resolve the listed errors before reviewing a save.")
        }
        val candidateHash = com.areslib.superstructure.SuperstructureDocumentCodec.contentHash(draft)
        val token = "${state.savedContentHash.orEmpty()}:$candidateHash"
        val summary = buildSaveReviewSummary(draft)
        return state.copy(
            step = SuperstructureStudioStep.REVIEW,
            review = SuperstructureSaveReview(state.savedContentHash, candidateHash, token, summary),
            error = null,
        )
    }
}
