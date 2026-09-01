package com.ares.analytics.viewmodel.controls

import com.ares.analytics.service.AresGenerationPhase
import com.ares.analytics.service.ControlsDesignProposal
import com.ares.analytics.service.project.ProjectSessionRevision
import com.ares.analytics.shared.models.League
import com.areslib.catalog.ActionDescriptor
import com.areslib.controls.ControlBindingDocument
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControlTargetKind
import com.areslib.controls.ControllerAssignment
import com.areslib.controls.ControllerControlDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.controls.ControllerProfileDocument
import com.areslib.controls.ControllerSurfaceDocument
import com.areslib.project.AresProjectMetadataDocument

enum class ControlsProblemSeverity { INFO, WARNING, ERROR }

data class ControlsProblem(
    val severity: ControlsProblemSeverity,
    val message: String,
    val bindingId: String? = null,
)

data class ControlLearningSession(
    val controlId: String,
    val baselineButtons: List<Boolean>,
    val baselineAxes: List<Float>,
)

data class ControlsAiProposalReview(
    val proposal: ControlsDesignProposal,
    val changes: List<String>,
    val problems: List<ControlsProblem>,
    val baseContentHash: String,
) {
    val canApply: Boolean get() = problems.none { it.severity == ControlsProblemSeverity.ERROR }
}

data class ControlsEditorState(
    val projectPath: String,
    val league: League,
    val targetPlatform: ControllerInputPlatform,
    val profiles: List<ControllerProfileDocument> = emptyList(),
    val schemes: List<ControlSchemeDocument> = emptyList(),
    val routineIds: List<String> = emptyList(),
    val actions: List<ActionDescriptor> = emptyList(),
    val selectedSchemeId: String? = null,
    val selectedControllerSlot: String? = null,
    val selectedControlId: String? = null,
    val selectedBindingId: String? = null,
    val surface: ControllerSurfaceDocument = ControllerSurfaceDocument.FRONT,
    val search: String = "",
    val draftBinding: ControlBindingDocument? = null,
    val learning: ControlLearningSession? = null,
    val problems: List<ControlsProblem> = emptyList(),
    val projectProblems: List<ControlsProblem> = emptyList(),
    val dirty: Boolean = false,
    val dirtySchemeIds: Set<String> = emptySet(),
    val dirtyProfileIds: Set<String> = emptySet(),
    val draftHasUnappliedChanges: Boolean = false,
    val generationPhase: AresGenerationPhase = AresGenerationPhase.IDLE,
    val generationMessage: String? = null,
    val generatedContentHash: String? = null,
    val projectMetadata: AresProjectMetadataDocument? = null,
    val projectRevision: ProjectSessionRevision? = null,
    val status: String? = null,
    val loadError: String? = null,
    val aiProposalInProgress: Boolean = false,
    val aiProposal: ControlsAiProposalReview? = null,
    val aiProposalError: String? = null,
) {
    val selectedScheme: ControlSchemeDocument?
        get() = schemes.firstOrNull { it.documentId == selectedSchemeId }

    val selectedController: ControllerAssignment?
        get() = selectedScheme?.controllers?.firstOrNull { it.slot == selectedControllerSlot }

    val selectedProfile: ControllerProfileDocument?
        get() = profiles.firstOrNull { it.documentId == selectedController?.profileId }

    val selectedControl: ControllerControlDocument?
        get() = selectedProfile?.controls?.firstOrNull { it.controlId == selectedControlId }

    val selectedAction: ActionDescriptor?
        get() = draftBinding?.target?.takeIf { it.kind == ControlTargetKind.ACTION }
            ?.let { target -> actions.firstOrNull { it.key == target.key } }

    val coverage: ControlsCoverage
        get() = controlsCoverage(actions, selectedScheme)

    val canSave: Boolean
        get() = dirty && !draftHasUnappliedChanges && canGenerate

    val canGenerate: Boolean
        get() = loadError == null && generationPhase != AresGenerationPhase.RUNNING &&
            problems.none { it.severity == ControlsProblemSeverity.ERROR }
}
