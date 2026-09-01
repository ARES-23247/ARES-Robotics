package com.ares.analytics.viewmodel.subsystem

import com.ares.analytics.shared.models.League
import com.ares.analytics.viewmodel.SubsystemBuilderStage
import com.ares.analytics.viewmodel.SubsystemEditorDraft
import com.ares.analytics.viewmodel.SubsystemGeneratorState
import com.ares.analytics.viewmodel.SubsystemRecoveryNotice
import com.ares.analytics.viewmodel.SubsystemRemovalRequest
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SubsystemRemovalStateTransitionsTest {
    @Test
    fun `removing a document resets editor-only state and selects the next document`() {
        val removed = document("removed", "Removed")
        val remaining = document("remaining", "Remaining")
        val recovery = SubsystemRecoveryNotice("removed", "Removed", "hash", ".ares/recovery/removed")
        val current = SubsystemGeneratorState(
            projectPath = "project",
            league = League.FTC,
            documents = listOf(removed, remaining),
            selectedDocumentId = removed.documentId,
            draft = SubsystemEditorDraft(removed),
            selectedHardwareUid = "hardware",
            selectedFieldUid = "field",
            selectedLoopUid = "loop",
            selectedInterlockId = "interlock",
            selectedTuningParameterUid = "tuning",
            activeStage = SubsystemBuilderStage.REVIEW,
            visitedStages = SubsystemBuilderStage.entries.toSet(),
            dirty = true,
            aiProposalInProgress = true,
            pendingRemoval = SubsystemRemovalRequest("removed", "Removed", persisted = true),
        )

        val result = SubsystemRemovalStateTransitions.removeDocument(
            current = current,
            documentId = removed.documentId,
            message = "Removed",
            revision = null,
            recovery = recovery,
        )

        assertEquals(listOf(remaining), result.documents)
        assertEquals(remaining.documentId, result.selectedDocumentId)
        assertEquals(remaining, result.draft?.document)
        assertEquals(SubsystemBuilderStage.PURPOSE, result.activeStage)
        assertEquals(setOf(SubsystemBuilderStage.PURPOSE), result.visitedStages)
        assertFalse(result.dirty)
        assertFalse(result.aiProposalInProgress)
        assertNull(result.pendingRemoval)
        assertEquals(recovery, result.recentRecovery)
    }

    @Test
    fun `restoring a document selects it and clears recovery state`() {
        val existing = document("zeta", "Zeta")
        val restored = document("alpha", "Alpha")
        val current = SubsystemGeneratorState(
            projectPath = "project",
            league = League.FTC,
            documents = listOf(existing),
            recentRecovery = SubsystemRecoveryNotice("alpha", "Alpha", "hash", ".ares/recovery/alpha"),
        )

        val result = SubsystemRemovalStateTransitions.restoreDocument(current, restored, revision = null)

        assertEquals(listOf(restored, existing), result.documents)
        assertEquals(restored.documentId, result.selectedDocumentId)
        assertEquals(restored, result.draft?.document)
        assertNull(result.recentRecovery)
        assertEquals("Restored Alpha from the reviewed recovery copy. Kotlin source was unchanged.", result.status)
    }

    private fun document(id: String, name: String) = SubsystemDocument(
        documentId = id,
        displayName = name,
        kotlinTypeName = name,
        platform = SubsystemPlatform.FTC,
    )
}
