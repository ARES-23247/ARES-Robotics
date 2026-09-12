package com.ares.analytics.viewmodel.project

import com.ares.analytics.service.project.*
import com.ares.analytics.service.project.persistence.ProjectMetadataRepository
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.areslib.controls.ControllerInputPlatform
import com.areslib.project.*
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectIdentityConcurrencyAuditTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun document(length: Double = 0.45) = AresProjectMetadataDocument(
        projectId = "test-project", identity = AresProjectIdentityDocument("99999", "2026", "robot", "Robot"),
        league = AresLeague.FTC, coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
        robotLengthMeters = length, robotWidthMeters = 0.43, fieldLengthMeters = 3.6576, fieldWidthMeters = 3.6576,
        runtimeOptions = AresRuntimeOptionsDocument(ftc = AresFtcRuntimeOptionsDocument()),
    )
    private fun project() = temporary.newFolder().also { root ->
        File(root, "TeamCode/src/main/java/Robot.kt").apply { parentFile.mkdirs(); writeText("class Robot") }
    }
    private fun workspace(root: File) = WorkspaceConfig(
        id = root.name, teamId = "99999", seasonId = "2026", robotId = "robot", projectPath = root.path,
        league = League.FTC, robotLengthMeters = 0.45, robotWidthMeters = 0.43,
    )
    /** Runs real synchronous file I/O only when the test releases that phase. No sleeps or races. */
    private class QueuedIo : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        val pending: Boolean get() = tasks.isNotEmpty()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runAll() {
            var count = 0
            while (tasks.isNotEmpty()) { check(++count < 100); tasks.removeFirst().run() }
        }
    }
    private inner class Fixture(val test: TestScope, gateway: ProjectDocumentGateway? = null, managed: Boolean = false) {
        val root = project()
        val repository = ProjectMetadataRepository()
        val initialHash = repository.save(root.path, document())
        val owner = SupervisorJob(test.coroutineContext[Job])
        val io = QueuedIo()
        val session = if (managed) ProjectSession(gateway ?: AresProjectDocuments()) else null
        val model = ProjectIdentityViewModel(CoroutineScope(test.coroutineContext + owner), repository, io, session)
        fun drain() {
            test.runCurrent()
            var count = 0
            while (io.pending) { check(++count < 100); io.runAll(); test.runCurrent() }
        }
        fun load() { model.load(workspace(root)); drain(); assertFalse(model.state.value.loading) }
        fun review() { model.update(ProjectIdentityField.ROBOT_LENGTH, "0.48"); model.review(); assertNotNull(model.state.value.proposal) }
        fun queueSave() { review(); model.applyReviewed(); test.runCurrent(); assertTrue(model.state.value.saving) }
        fun close() { owner.cancel(); drain(); assertTrue(owner.isCompleted) }
    }
    private fun TestScope.withFixture(managed: Boolean = false, block: Fixture.() -> Unit) {
        val fixture = Fixture(this, managed = managed)
        try { fixture.load(); fixture.block() } finally { fixture.close() }
    }

    @Test fun `editor document and revision come from the same session snapshot`() = runTest {
        val actual = AresProjectDocuments()
        var change = true
        val gateway = object : ProjectDocumentGateway by actual {
            override fun load(projectPath: String, targetPlatform: ControllerInputPlatform?): AresProjectDocumentSnapshot {
                if (change) {
                    change = false
                    val old = actual.metadata.load(projectPath).getOrThrow()
                    actual.metadata.saveReviewed(projectPath, AresProjectMetadataCodec.contentHash(old), document(0.51))
                }
                return actual.load(projectPath, targetPlatform)
            }
        }
        val f = Fixture(this, gateway, managed = true)
        try {
            f.load()
            val snapshot = assertNotNull(f.session?.state?.value?.snapshot)
            assertEquals(snapshot.documents.query.metadata, f.model.state.value.currentDocument)
            assertEquals("0.51", f.model.state.value.draft.robotLengthMeters)
            f.model.update(ProjectIdentityField.DISPLAY_NAME, "Reviewed name"); f.model.review(); f.model.applyReviewed(); f.drain()
            assertEquals(0.51, f.repository.load(f.root.path).getOrThrow().robotLengthMeters)
        } finally { f.close() }
    }

    @Test fun `invalid metadata in the session cannot reuse the earlier valid editor document`() = runTest {
        val actual = AresProjectDocuments()
        var change = true
        val gateway = object : ProjectDocumentGateway by actual {
            override fun load(projectPath: String, targetPlatform: ControllerInputPlatform?): AresProjectDocumentSnapshot {
                if (change) { change = false; actual.metadata.file(projectPath).writeText("concurrent corruption") }
                return actual.load(projectPath, targetPlatform)
            }
        }
        val f = Fixture(this, gateway, managed = true)
        try {
            f.load()
            assertNull(f.model.state.value.currentDocument)
            assertFalse(f.model.state.value.canReview)
            assertNull(f.model.state.value.protectedContentHash)
            assertEquals("concurrent corruption", f.repository.file(f.root.path).readText())
        } finally { f.close() }
    }

    @Test fun `edits made during a successful save remain as an unsaved draft`() = runTest {
        withFixture {
            queueSave()
            model.update(ProjectIdentityField.ROBOT_LENGTH, "0.52")
            model.update(ProjectIdentityField.DISPLAY_NAME, "Newer draft")
            model.updateFtcHubCommandTransport(AresFtcHubCommandTransport.ARES_PHOTON)
            drain()
            assertEquals(0.48, repository.load(root.path).getOrThrow().robotLengthMeters)
            assertEquals(0.48, model.state.value.currentDocument?.robotLengthMeters)
            assertEquals("0.52", model.state.value.draft.robotLengthMeters)
            assertEquals("Newer draft", model.state.value.draft.displayName)
            assertEquals(AresFtcHubCommandTransport.ARES_PHOTON, model.state.value.draft.ftcHubCommandTransport)
            assertFalse(model.state.value.saving)
            assertNull(model.state.value.proposal)
            assertTrue(model.state.value.message.orEmpty().contains("unsaved"))
            model.review()
            assertEquals(AresProjectMetadataCodec.contentHash(document(0.48)), model.state.value.proposal?.expectedContentHash)
        }
    }

    @Test fun `failed save preserves newer draft validation and file contents`() = runTest {
        withFixture {
            queueSave()
            repository.saveReviewed(root.path, initialHash, document(0.51))
            model.update(ProjectIdentityField.ROBOT_LENGTH, "-1")
            model.update(ProjectIdentityField.DISPLAY_NAME, "Newer draft")
            drain()
            assertEquals(0.51, repository.load(root.path).getOrThrow().robotLengthMeters)
            assertEquals("-1", model.state.value.draft.robotLengthMeters)
            assertEquals("Newer draft", model.state.value.draft.displayName)
            assertNotNull(model.state.value.fieldErrors[ProjectIdentityField.ROBOT_LENGTH])
            assertTrue(model.state.value.messageIsError)
            assertFalse(model.state.value.saving)
        }
    }

    @Test fun `duplicate apply during an active save cannot replace success with a stale failure`() = runTest {
        withFixture {
            queueSave(); model.applyReviewed(); drain()
            assertEquals(0.48, repository.load(root.path).getOrThrow().robotLengthMeters)
            assertEquals(0.48, model.state.value.currentDocument?.robotLengthMeters)
            assertFalse(model.state.value.messageIsError, model.state.value.message)
            assertFalse(model.state.value.saving)
        }
    }

    @Test fun `save completion keeps its returned revision when the shared session advances`() = runTest {
        withFixture(managed = true) {
            queueSave(); io.runAll()
            val savedRevision = assertNotNull(session?.state?.value?.revision)
            val saved = repository.load(root.path).getOrThrow()
            repository.saveReviewed(root.path, AresProjectMetadataCodec.contentHash(saved), document(0.53))
            session?.snapshot(root.path, ControllerInputPlatform.FTC, forceReload = true)
            test.runCurrent()
            assertEquals(savedRevision, model.state.value.projectRevision)
            model.update(ProjectIdentityField.DISPLAY_NAME, "Another draft"); model.review(); model.applyReviewed(); drain()
            assertEquals(0.53, repository.load(root.path).getOrThrow().robotLengthMeters)
            assertTrue(model.state.value.messageIsError)
        }
    }

    @Test fun `cancelled queued save releases busy state without claiming a write failure`() = runTest {
        withFixture {
            queueSave(); owner.cancel(); drain()
            assertFalse(model.state.value.saving)
            assertFalse(model.state.value.messageIsError, model.state.value.message)
            assertNull(model.state.value.proposal)
            assertEquals(document(), repository.load(root.path).getOrThrow())
        }
    }

    @Test fun `cancelled queued load releases loading and cannot offer an uninspected identity`() = runTest {
        val f = Fixture(this)
        try {
            f.model.load(workspace(f.root)); runCurrent()
            f.owner.cancel(); f.drain()
            assertFalse(f.model.state.value.loading)
            assertFalse(f.model.state.value.canReview)
            assertFalse(f.model.state.value.messageIsError)
        } finally { f.close() }
    }

    @Test fun `switching project while a save completes keeps the new editor selection`() = runTest {
        withFixture {
            queueSave()
            val nextRoot = project()
            repository.save(nextRoot.path, document(0.54))
            model.load(workspace(nextRoot)); drain()
            assertEquals(nextRoot.path, model.state.value.projectPath)
            assertEquals(0.54, model.state.value.currentDocument?.robotLengthMeters)
            assertEquals(0.48, repository.load(root.path).getOrThrow().robotLengthMeters)
            assertFalse(model.state.value.saving)
        }
    }

    @Test fun `creation binds the selected project session and locks stable IDs while saving`() = runTest {
        val f = Fixture(this, managed = true)
        try {
            assertTrue(f.repository.file(f.root.path).delete())
            val otherRoot = project()
            f.repository.save(otherRoot.path, document(0.54))
            f.session?.snapshot(otherRoot.path, ControllerInputPlatform.FTC)
            f.load()
            f.model.review()
            val proposal = assertNotNull(f.model.state.value.proposal)
            f.model.applyReviewed(); runCurrent()
            for (field in STABLE_IDENTITY_FIELDS) f.model.update(field, "new-stable-id")
            f.model.update(ProjectIdentityField.DISPLAY_NAME, "Newer friendly name")
            f.drain()
            val state = f.model.state.value
            val saved = f.repository.load(f.root.path).getOrThrow()
            assertEquals(proposal.document.projectId, state.draft.projectId)
            assertEquals(proposal.document.identity.teamId, state.draft.teamId)
            assertEquals(proposal.document.identity.seasonId, state.draft.seasonId)
            assertEquals(proposal.document.identity.robotId, state.draft.robotId)
            assertEquals("Newer friendly name", state.draft.displayName)
            assertEquals(saved, state.currentDocument)
            val snapshot = assertNotNull(f.session?.state?.value?.snapshot)
            assertEquals(f.root.canonicalPath, snapshot.selection.projectRoot)
            assertEquals(saved, snapshot.documents.query.metadata)
            assertEquals(snapshot.revision, state.projectRevision)
            assertEquals(0.54, f.repository.load(otherRoot.path).getOrThrow().robotLengthMeters)
        } finally { f.close() }
    }

    @Test fun `changed metadata during post-save refresh cannot authorize a stale saved document`() = runTest {
        val actual = AresProjectDocuments()
        var change = false
        val gateway = object : ProjectDocumentGateway by actual {
            override fun load(projectPath: String, targetPlatform: ControllerInputPlatform?): AresProjectDocumentSnapshot {
                if (change) {
                    change = false
                    val old = actual.metadata.load(projectPath).getOrThrow()
                    actual.metadata.saveReviewed(projectPath, AresProjectMetadataCodec.contentHash(old), document(0.56))
                }
                return actual.load(projectPath, targetPlatform)
            }
        }
        val f = Fixture(this, gateway, managed = true)
        try {
            f.load(); f.queueSave(); change = true
            f.model.update(ProjectIdentityField.DISPLAY_NAME, "Newer draft")
            f.drain()
            assertEquals(0.56, f.repository.load(f.root.path).getOrThrow().robotLengthMeters)
            assertEquals(0.45, f.model.state.value.currentDocument?.robotLengthMeters)
            assertEquals("Newer draft", f.model.state.value.draft.displayName)
            assertTrue(f.model.state.value.messageIsError)
            assertTrue(f.model.state.value.message.orEmpty().contains("was saved"))
            assertTrue(f.model.state.value.message.orEmpty().contains("Reload"))
        } finally { f.close() }
    }

    @Test fun `cancellation before the launch dispatcher runs still releases saving`() = runTest {
        withFixture {
            review(); model.applyReviewed(); owner.cancel(); drain()
            assertFalse(model.state.value.saving)
            assertFalse(model.state.value.canReview)
            assertEquals(document(), repository.load(root.path).getOrThrow())
        }
    }

    @Test fun `cancellation after commit requires reload and does not claim rollback`() = runTest {
        withFixture {
            queueSave(); io.runAll(); owner.cancel(); drain()
            assertEquals(0.48, repository.load(root.path).getOrThrow().robotLengthMeters)
            assertFalse(model.state.value.saving)
            assertFalse(model.state.value.canReview)
            assertTrue(model.state.value.protectedError.orEmpty().contains("Reload"))
            assertFalse(model.state.value.messageIsError)
        }
    }

    @Test fun `superseded queued load cannot install a cancellation error over the new project`() = runTest {
        val f = Fixture(this)
        try {
            f.model.load(workspace(f.root)); runCurrent()
            val nextRoot = project()
            f.repository.save(nextRoot.path, document(0.54))
            f.model.load(workspace(nextRoot)); f.drain()
            assertEquals(nextRoot.path, f.model.state.value.projectPath)
            assertEquals(0.54, f.model.state.value.currentDocument?.robotLengthMeters)
            assertNull(f.model.state.value.protectedError)
            assertFalse(f.model.state.value.loading)
        } finally { f.close() }
    }
}
