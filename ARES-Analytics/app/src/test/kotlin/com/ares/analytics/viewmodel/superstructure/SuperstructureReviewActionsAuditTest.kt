package com.ares.analytics.viewmodel.superstructure

import com.ares.analytics.service.project.AresProjectDocuments
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.SuperstructureStatePreset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SuperstructureReviewActionsAuditTest {

    @Test
    fun `successful reviewSave switches step to REVIEW while validation error preserves current step`() = withProject { project, documents ->
        documents.superstructures.save(project.path, document("main-machine", "Main Machine"), null, emptyList(), emptySet())
        val viewModel = SuperstructureStudioViewModel(project.path, this, documents)
        withTimeout(5_000) { viewModel.state.first { !it.loading && it.selectedId == "main-machine" } }

        assertEquals(SuperstructureStudioStep.POSTURES, viewModel.state.value.step)

        // Induce an editor error
        viewModel.setEditorError("test:error", "Invalid configuration")
        viewModel.reviewSave()
        assertNull(viewModel.state.value.review)
        assertEquals(SuperstructureStudioStep.POSTURES, viewModel.state.value.step)

        // Clear error and run successful reviewSave
        viewModel.setEditorError("test:error", null)
        viewModel.reviewSave()
        assertNotNull(viewModel.state.value.review)
        assertEquals(SuperstructureStudioStep.REVIEW, viewModel.state.value.step)
    }

    @Test
    fun `reload on dirty saved draft populates pendingSelectionId and confirmDiscard restores saved draft`() = withProject { project, documents ->
        documents.superstructures.save(project.path, document("main-machine", "Main Machine"), null, emptyList(), emptySet())
        val viewModel = SuperstructureStudioViewModel(project.path, this, documents)
        withTimeout(5_000) { viewModel.state.first { !it.loading && it.selectedId == "main-machine" } }

        viewModel.updateMetadata("Edited Machine", "Unsaved description")
        assertTrue(viewModel.state.value.dirty)

        viewModel.reviewSave()
        val draftBefore = viewModel.state.value.draft
        val reviewBefore = assertNotNull(viewModel.state.value.review)

        // Reload while dirty: pendingSelectionId is set, no self-referential error
        viewModel.reload()
        assertEquals("main-machine", viewModel.state.value.pendingSelectionId)
        assertTrue(viewModel.state.value.dirty)
        assertNull(viewModel.state.value.error)

        // Cancel preserves dirty draft
        viewModel.cancelDiscard()
        assertNull(viewModel.state.value.pendingSelectionId)
        assertTrue(viewModel.state.value.dirty)
        assertEquals("Edited Machine", viewModel.state.value.draft?.displayName)

        assertEquals(draftBefore, viewModel.state.value.draft)
        assertEquals(reviewBefore, viewModel.state.value.review)

        // Confirm discard reverts to saved draft
        viewModel.reload()
        assertEquals("main-machine", viewModel.state.value.pendingSelectionId)
        viewModel.confirmDiscard()
        withTimeout(5_000) { viewModel.state.first { !it.loading && !it.dirty && it.draft == it.saved } }

        assertFalse(viewModel.state.value.dirty)
        assertNull(viewModel.state.value.pendingSelectionId)
        assertEquals("Main Machine", viewModel.state.value.draft?.displayName)
    }

    @Test
    fun `discard on unsaved draft removes draft and resets state`() = withProject { project, documents ->
        documents.superstructures.save(project.path, document("existing-machine", "Existing Machine"), null, emptyList(), emptySet())
        val viewModel = SuperstructureStudioViewModel(project.path, this, documents)
        withTimeout(5_000) { viewModel.state.first { !it.loading && it.selectedId == "existing-machine" } }

        viewModel.create("new-machine", "New Machine")
        assertEquals("new-machine", viewModel.state.value.selectedId)
        assertTrue(viewModel.state.value.dirty)

        viewModel.reload()
        assertEquals("new-machine", viewModel.state.value.pendingSelectionId)

        viewModel.cancelDiscard()
        assertEquals("new-machine", viewModel.state.value.selectedId)
        assertTrue(viewModel.state.value.dirty)

        viewModel.reload()
        viewModel.confirmDiscard()
        withTimeout(5_000) { viewModel.state.first { !it.loading && !it.dirty && it.selectedId == "existing-machine" } }

        assertEquals("existing-machine", viewModel.state.value.selectedId)
        assertFalse(viewModel.state.value.dirty)
    }

    @Test
    fun `discard on selection change switches to target coordinator`() = withProject { project, documents ->
        documents.superstructures.save(project.path, document("first-machine", "First Machine"), null, emptyList(), emptySet())
        documents.superstructures.save(project.path, document("second-machine", "Second Machine"), null, emptyList(), emptySet())
        val viewModel = SuperstructureStudioViewModel(project.path, this, documents)
        withTimeout(5_000) { viewModel.state.first { !it.loading } }

        viewModel.select("first-machine")
        viewModel.addState("EXTRA", "Extra")
        assertTrue(viewModel.state.value.dirty)

        viewModel.select("second-machine")
        assertEquals("second-machine", viewModel.state.value.pendingSelectionId)
        assertEquals("first-machine", viewModel.state.value.selectedId)

        viewModel.cancelDiscard()
        assertNull(viewModel.state.value.pendingSelectionId)
        assertEquals("first-machine", viewModel.state.value.selectedId)
        assertTrue(viewModel.state.value.dirty)

        viewModel.select("second-machine")
        viewModel.confirmDiscard()
        withTimeout(5_000) { viewModel.state.first { !it.loading && !it.dirty && it.selectedId == "second-machine" } }

        assertEquals("second-machine", viewModel.state.value.selectedId)
        assertFalse(viewModel.state.value.dirty)
    }

    private fun document(id: String, name: String) = SuperstructureDocument(
        superstructureId = id,
        displayName = name,
        initialStateId = "idle",
        states = listOf(SuperstructureStatePreset("idle"), SuperstructureStatePreset("fault")),
        faultStateId = "fault",
    )

    private fun withProject(block: suspend kotlinx.coroutines.CoroutineScope.(File, AresProjectDocuments) -> Unit) {
        val project = Files.createTempDirectory("ares-superstructure-review-test").toFile()
        try {
            val documents = AresProjectDocuments()
            runBlocking {
                block(project, documents)
            }
        } finally {
            project.deleteRecursively()
        }
    }
}
