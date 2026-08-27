package com.ares.analytics.viewmodel.project

import com.ares.analytics.service.project.AresProjectDocuments
import com.ares.analytics.service.project.persistence.ProjectDocumentKind
import com.ares.analytics.service.project.persistence.SuperstructureProjectRepository

import com.ares.analytics.viewmodel.superstructure.SuperstructureStudioViewModel
import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import com.areslib.superstructure.StateTransitionEdge
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.SuperstructureFieldReference
import com.areslib.superstructure.SuperstructureStatePreset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SuperstructureProjectRepositoryTest {
    @Test
    fun `save is atomic hash-bound and preserves immutable history`() = withProject { project ->
        val repository = SuperstructureProjectRepository()
        val first = repository.save(project.path, document("Coordinator A"), null, emptyList(), emptySet())
        val second = repository.save(project.path, document("Coordinator B"), first.contentHash, emptyList(), emptySet())
        val currentBytes = second.currentFile.readText()

        val stale = assertFailsWith<IllegalArgumentException> {
            repository.save(project.path, document("Stale overwrite"), first.contentHash, emptyList(), emptySet())
        }

        assertTrue(stale.message.orEmpty().contains("changed on disk"))
        assertEquals("Coordinator B", repository.load(project.path, "main-machine").displayName)
        assertEquals(currentBytes, second.currentFile.readText())
        assertTrue(first.historyFile.isFile)
        assertTrue(second.historyFile.isFile)
        assertFalse(first.historyFile.path == second.historyFile.path)
        assertTrue(project.walkTopDown().none { it.extension == "tmp" })
    }

    @Test
    fun `listing rejects mismatched identities without hiding valid coordinator`() = withProject { project ->
        val repository = SuperstructureProjectRepository()
        repository.save(project.path, document("Valid"), null, emptyList(), emptySet())
        val mismatch = File(project, ".ares/superstructures/wrong-name.aressuperstructure")
        mismatch.writeText(com.areslib.superstructure.SuperstructureDocumentCodec.encode(document("Copy")))

        val listing = repository.list(project.path)

        assertTrue(listing.documents.isEmpty(), "Duplicate decoded IDs must not be selected ambiguously")
        assertTrue(listing.diagnostics.any { it.message.contains("does not match superstructureId") })
        assertEquals(2, listing.diagnostics.count { it.message.contains("Duplicate superstructureId") })
    }

    @Test
    fun `project snapshot reports duplicate action ownership across coordinators`() = withProject { project ->
        val documents = AresProjectDocuments()
        documents.capabilities.save(project.path, catalog())
        val first = actionDocument("first", "coord.enter")
        val second = actionDocument("second", "coord.enter")
        documents.superstructures.save(project.path, first, null, emptyList(), catalog().actions.mapTo(linkedSetOf()) { it.key })
        documents.superstructures.save(project.path, second, null, emptyList(), catalog().actions.mapTo(linkedSetOf()) { it.key })

        val snapshot = documents.load(project.path)

        assertEquals(2, snapshot.query.superstructures.size)
        val ownershipErrors = snapshot.diagnostics.filter {
            it.kind == ProjectDocumentKind.SUPERSTRUCTURE && it.message.contains("more than one superstructure")
        }
        assertEquals(4, ownershipErrors.size)
        assertEquals(setOf("coord.enter", "coord.exit"), ownershipErrors.mapTo(linkedSetOf()) {
            if (it.message.contains("coord.enter")) "coord.enter" else "coord.exit"
        })
    }

    @Test
    fun `project snapshot fails closed when a referenced action catalog is missing`() = withProject { project ->
        val repository = SuperstructureProjectRepository()
        repository.save(project.path, actionDocument("first", "coord.enter"), null, emptyList(), setOf("coord.enter", "coord.exit"))

        val snapshot = AresProjectDocuments(superstructures = repository).load(project.path)

        assertTrue(snapshot.diagnostics.any {
            it.kind == ProjectDocumentKind.SUPERSTRUCTURE && it.message.contains("not present in the project action catalog")
        })
    }

    @Test
    fun `novice studio builds a validated project-backed coordinator without Kotlin ids`() = withProject { project ->
        val documents = AresProjectDocuments()
        val subsystem = SubsystemTemplates.create(
            SubsystemTemplate.POSITION_CONTROLLED_MECHANISM,
            "arm",
            "Arm",
            SubsystemPlatform.FTC,
        )
        documents.subsystems.save(project.path, subsystem)
        documents.capabilities.save(project.path, catalog())

        runBlocking {
            val viewModel = SuperstructureStudioViewModel(project.path, this, documents)
            withTimeout(5_000) { viewModel.state.first { !it.loading } }
            viewModel.create("main-machine", "Main machine")
            val target = subsystem.stateFields.single { it.role == SubsystemFieldRole.TARGET }
            viewModel.addTarget(SuperstructureFieldReference(subsystem.uid, target.uid))
            viewModel.addState("ACTIVE", "Active")
            viewModel.addActionTransition("idle", "ACTIVE", "coord.enter")
            viewModel.addActionTransition("ACTIVE", "idle", "coord.exit")

            assertTrue(viewModel.state.value.validationErrors.isEmpty(), viewModel.state.value.validationErrors.joinToString())
            viewModel.setEditorError("target:ACTIVE:arm.target", "Enter a numeric target.")
            assertFalse(viewModel.state.value.canSave)
            viewModel.reviewSave()
            assertEquals(null, viewModel.state.value.review)
            viewModel.setEditorError("target:ACTIVE:arm.target", null)
            viewModel.reviewSave()
            val review = assertNotNull(viewModel.state.value.review)
            viewModel.confirmSave(review.confirmationToken)
            withTimeout(5_000) { viewModel.state.first { !it.loading && !it.dirty } }

            val saved = documents.superstructures.load(project.path, "main-machine")
            assertEquals(listOf("idle", "fault", "ACTIVE"), saved.states.map { it.stateId })
            assertEquals(setOf("coord.enter", "coord.exit"), saved.transitions.mapNotNullTo(linkedSetOf()) { it.actionKey })
            assertTrue(File(project, ".ares/history/superstructures/main-machine").listFiles().orEmpty().isNotEmpty())
        }
    }

    private fun document(name: String) = SuperstructureDocument(
        superstructureId = "main-machine",
        displayName = name,
        initialStateId = "idle",
        states = listOf(SuperstructureStatePreset("idle"), SuperstructureStatePreset("fault")),
        faultStateId = "fault",
    )

    private fun actionDocument(id: String, action: String) = SuperstructureDocument(
        superstructureId = id,
        initialStateId = "idle",
        states = listOf(
            SuperstructureStatePreset("idle"),
            SuperstructureStatePreset("active"),
            SuperstructureStatePreset("fault"),
        ),
        transitions = listOf(
            StateTransitionEdge("enter-$id", "idle", "active", actionKey = action),
            StateTransitionEdge("exit-$id", "active", "idle", actionKey = "coord.exit"),
        ),
        faultStateId = "fault",
    )

    private fun catalog() = CapabilityCatalogDocument(
        projectId = "test-project",
        actions = listOf(
            ActionDescriptor("coord.enter", "Enter posture", "Requests the active posture."),
            ActionDescriptor("coord.exit", "Exit posture", "Requests the idle posture."),
        ),
    )

    private fun withProject(block: (File) -> Unit) {
        val project = Files.createTempDirectory("ares-superstructure-test").toFile()
        try {
            block(project)
        } finally {
            project.deleteRecursively()
        }
    }
}
