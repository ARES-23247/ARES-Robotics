package com.ares.analytics.viewmodel.subsystem

import com.ares.analytics.service.project.ProjectSessionRevision
import com.ares.analytics.service.project.persistence.ProjectDocumentRemovalPlan
import com.ares.analytics.viewmodel.SubsystemBuilderStage
import com.ares.analytics.viewmodel.SubsystemEditorDraft
import com.ares.analytics.viewmodel.SubsystemGeneratorState
import com.ares.analytics.viewmodel.SubsystemRecoveryNotice
import com.ares.analytics.viewmodel.SubsystemRemovalRequest
import com.areslib.subsystem.SubsystemDocument
import java.io.File

/** Pure state transitions for reviewed subsystem removal and recovery. */
internal object SubsystemRemovalStateTransitions {
    fun prepareRemoval(
        current: SubsystemGeneratorState,
        draft: SubsystemDocument,
        plan: ProjectDocumentRemovalPlan?,
        projectRoot: File?,
    ): SubsystemGeneratorState = current.copy(
        pendingRemoval = SubsystemRemovalRequest(
            documentId = draft.documentId,
            displayName = draft.displayName,
            persisted = plan != null,
            contentHash = plan?.contentHash,
            canonicalPath = plan?.currentFile?.projectRelativeTo(projectRoot),
            recoveryPath = plan?.recoveryFile?.projectRelativeTo(projectRoot),
            sourceFilesPreserved = draft.implementation.sourceFiles.sorted(),
            discardsUnsavedChanges = current.dirty,
        ),
        status = null,
    )

    fun removeDocument(
        current: SubsystemGeneratorState,
        documentId: String,
        message: String,
        revision: ProjectSessionRevision?,
        recovery: SubsystemRecoveryNotice? = null,
    ): SubsystemGeneratorState {
        val remaining = current.documents.filterNot { it.documentId == documentId }
        val next = remaining.firstOrNull()
        return current.copy(
            documents = remaining,
            selectedDocumentId = next?.documentId,
            draft = next?.let(::SubsystemEditorDraft),
            selectedHardwareUid = null,
            selectedFieldUid = null,
            selectedLoopUid = null,
            selectedInterlockId = null,
            selectedTuningParameterUid = null,
            activeStage = SubsystemBuilderStage.PURPOSE,
            visitedStages = setOf(SubsystemBuilderStage.PURPOSE),
            selectedTemplate = next?.template ?: current.selectedTemplate,
            dirty = false,
            projectRevision = revision,
            pendingRemoval = null,
            recentRecovery = recovery,
            status = message,
            aiProposalInProgress = false,
            aiProposal = null,
            aiProposalError = null,
        )
    }

    fun restoreDocument(
        current: SubsystemGeneratorState,
        restored: SubsystemDocument,
        revision: ProjectSessionRevision?,
    ): SubsystemGeneratorState {
        val restoredDocuments = (current.documents + restored)
            .distinctBy(SubsystemDocument::documentId)
            .sortedWith(compareBy<SubsystemDocument> { it.displayName.lowercase() }.thenBy { it.documentId })
        return current.copy(
            documents = restoredDocuments,
            selectedDocumentId = restored.documentId,
            draft = SubsystemEditorDraft(restored),
            selectedHardwareUid = restored.hardware.firstOrNull()?.uid,
            selectedFieldUid = restored.stateFields.firstOrNull()?.uid,
            selectedLoopUid = restored.controlLoops.firstOrNull()?.uid,
            selectedInterlockId = restored.interlocks.firstOrNull()?.interlockId,
            selectedTuningParameterUid = restored.tuningParameters.firstOrNull()?.uid,
            activeStage = SubsystemBuilderStage.PURPOSE,
            visitedStages = setOf(SubsystemBuilderStage.PURPOSE),
            selectedTemplate = restored.template,
            dirty = false,
            projectRevision = revision,
            recentRecovery = null,
            status = "Restored ${restored.displayName} from the reviewed recovery copy. Kotlin source was unchanged.",
        )
    }

    private fun File.projectRelativeTo(root: File?): String? = root?.let { projectRoot ->
        runCatching { relativeTo(projectRoot).invariantSeparatorsPath }.getOrNull()
    }
}
