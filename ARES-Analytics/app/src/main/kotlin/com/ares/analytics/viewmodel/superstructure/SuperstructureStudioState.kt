package com.ares.analytics.viewmodel.superstructure

import com.ares.analytics.service.project.persistence.ProjectDocumentDiagnostic
import com.ares.analytics.service.project.ProjectSessionRevision
import com.areslib.catalog.ActionDescriptor
import com.areslib.subsystem.InterlockComparison
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemStateFieldDocument
import com.areslib.subsystem.isAresGenerated
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.SuperstructureFieldReference
import com.areslib.superstructure.SuperstructureInterlockRule

enum class SuperstructureStudioStep {
    POSTURES,
    TRANSITIONS,
    REVIEW,
}

data class SuperstructureFieldOption(
    val subsystem: SubsystemDocument,
    val field: SubsystemStateFieldDocument,
) {
    val reference: SuperstructureFieldReference = SuperstructureFieldReference(subsystem.uid, field.uid)
    val label: String = "${subsystem.displayName} · ${field.displayName}${field.unit?.let { " ($it)" }.orEmpty()}"
}

data class SuperstructureSaveReview(
    val expectedContentHash: String?,
    val candidateContentHash: String,
    val confirmationToken: String,
    val summary: List<String>,
)

data class SuperstructureStudioState(
    val projectPath: String,
    val documents: List<SuperstructureDocument> = emptyList(),
    val selectedId: String? = null,
    val saved: SuperstructureDocument? = null,
    val savedContentHash: String? = null,
    val projectRevision: ProjectSessionRevision? = null,
    val draft: SuperstructureDocument? = null,
    val subsystems: List<SubsystemDocument> = emptyList(),
    val actions: List<ActionDescriptor> = emptyList(),
    val diagnostics: List<ProjectDocumentDiagnostic> = emptyList(),
    val step: SuperstructureStudioStep = SuperstructureStudioStep.POSTURES,
    val selectedStateId: String? = null,
    val validationErrors: List<String> = emptyList(),
    val validationWarnings: List<String> = emptyList(),
    val editorErrors: Map<String, String> = emptyMap(),
    val review: SuperstructureSaveReview? = null,
    val loading: Boolean = true,
    val dirty: Boolean = false,
    val status: String = "",
    val error: String? = null,
    val pendingSelectionId: String? = null,
    val preview: SuperstructurePreviewSnapshot? = null,
    val stateflowGraphMode: Boolean = true,
    val graphPanX: Float = 0f,
    val graphPanY: Float = 0f,
    val graphZoom: Float = 1f,
) {
    val generatedSubsystems: List<SubsystemDocument>
        get() = subsystems.filter { it.implementation.kind.isAresGenerated() }
    val targetFields: List<SuperstructureFieldOption>
        get() = generatedSubsystems.flatMap { subsystem ->
            subsystem.stateFields.filter { it.role == SubsystemFieldRole.TARGET }
                .map { SuperstructureFieldOption(subsystem, it) }
        }
    val sourceFields: List<SuperstructureFieldOption>
        get() = generatedSubsystems.flatMap { subsystem ->
            subsystem.stateFields.map { SuperstructureFieldOption(subsystem, it) }
        }
    val parameterlessActions: List<ActionDescriptor>
        get() = actions.filter { it.parameters.isEmpty() }
    val canSave: Boolean
        get() = draft != null && dirty && validationErrors.isEmpty() && editorErrors.isEmpty() && review == null
}

internal fun automaticInterlockDescription(
    rule: SuperstructureInterlockRule,
    sourceFields: List<SuperstructureFieldOption>,
    targetFields: List<SuperstructureFieldOption>,
): String? {
    val source = sourceFields.firstOrNull { it.reference == rule.primary }?.label ?: return null
    val constrained = targetFields.firstOrNull { it.reference == rule.constrained }?.label ?: return null
    val comparison = when (rule.conditionComparison) {
        InterlockComparison.LESS_THAN -> "below"
        InterlockComparison.GREATER_THAN -> "above"
        InterlockComparison.EQUALS_STATE -> "equal to"
        InterlockComparison.NOT_EQUALS_STATE -> "not equal to"
    }
    return "Clamp $constrained when $source is $comparison ${rule.conditionThreshold}."
}

internal fun isAutomaticInterlockDescription(
    rule: SuperstructureInterlockRule,
    sourceFields: List<SuperstructureFieldOption>,
    targetFields: List<SuperstructureFieldOption>,
): Boolean {
    if (rule.description.isBlank()) return true
    val automatic = automaticInterlockDescription(rule, sourceFields, targetFields)
    if (rule.description == automatic) return true
    val source = sourceFields.firstOrNull { it.reference == rule.primary }?.label ?: return false
    val constrained = targetFields.firstOrNull { it.reference == rule.constrained }?.label ?: return false
    return rule.description == "Clamp $constrained while $source is below the reviewed threshold."
}
