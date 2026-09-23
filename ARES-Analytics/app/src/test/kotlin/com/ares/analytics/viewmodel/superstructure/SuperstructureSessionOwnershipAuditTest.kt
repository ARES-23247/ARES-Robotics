package com.ares.analytics.viewmodel.superstructure

import com.ares.analytics.service.project.AresProjectDocuments
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.project.ProjectSessionRevision
import com.ares.analytics.shared.models.League
import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.state.RobotFieldConfig
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresFtcRuntimeOptionsDocument
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectIdentityDocument
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.AresRuntimeOptionsDocument
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import com.areslib.superstructure.SuperstructureDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.spy
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SuperstructureSessionOwnershipAuditTest {

    @Test
    fun `cancelled workspace model A does not restore shared selection after replacement model loads`() = fixture { f ->
        val originalStarted = CountDownLatch(1)
        val releaseOriginal = CountDownLatch(1)
        val blockedOriginal = AtomicBoolean(false)

        doAnswer { invocation ->
            val path = invocation.getArgument<String>(0)
            if (path == f.original.path && blockedOriginal.compareAndSet(false, true)) {
                originalStarted.countDown()
                check(releaseOriginal.await(10, TimeUnit.SECONDS)) { "Original snapshot was not released" }
            }
            invocation.callRealMethod()
        }.`when`(f.session).snapshot(
            anyString() ?: "",
            any(ControllerInputPlatform::class.java) ?: ControllerInputPlatform.FTC,
            anyBoolean(),
            any<() -> Unit>() ?: {},
        )

        val scopeA = f.newScope()
        val scopeB = f.newScope()
        try {
            val vmA = SuperstructureStudioViewModel(
                projectPath = f.original.path,
                scope = scopeA,
                projectDocuments = f.documents,
                targetPlatform = ControllerInputPlatform.FTC,
                projectSession = f.session,
            )
            assertTrue(originalStarted.await(10, TimeUnit.SECONDS), "Original load did not start")

            // Cancel old model A while blocked inside snapshot before acquiring the lock
            scopeA.cancel()

            // Replacement model B loads other project
            val vmB = SuperstructureStudioViewModel(
                projectPath = f.other.path,
                scope = scopeB,
                projectDocuments = f.documents,
                targetPlatform = ControllerInputPlatform.FTC,
                projectSession = f.session,
            )
            withTimeout(10_000) {
                vmB.state.first { !it.loading && it.projectRevision != null }
            }

            val snapshotB = f.session.state.value.snapshot
            assertNotNull(snapshotB, "Active project other should have populated session snapshot")
            assertEquals(f.other.path, snapshotB.selection.projectRoot)
            assertEquals("other", snapshotB.documents.query.metadata?.identity?.displayName)

            // Release original and let it attempt to enter lock
            releaseOriginal.countDown()
            f.joinWork()

            // Shared session selection must remain on other, not reverted to original
            val finalSnapshot = f.session.state.value.snapshot
            assertNotNull(finalSnapshot, "Session snapshot must not be null after old work joins")
            assertEquals(
                f.other.path,
                finalSnapshot.selection.projectRoot,
                "Cancelled ViewModel A corrupted shared ProjectSession selection after ViewModel B became active",
            )
            assertEquals("other", finalSnapshot.documents.query.metadata?.identity?.displayName)
        } finally {
            releaseOriginal.countDown()
        }
    }

    @Test
    fun `in flight reload delayed after read does not overwrite subsequent real save`() = fixture { f ->
        val enterRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val blockRead = AtomicBoolean(false)

        doAnswer { invocation ->
            val result = invocation.callRealMethod()
            val path = invocation.getArgument<String>(0)
            if (path == f.original.path && blockRead.compareAndSet(true, false)) {
                enterRead.countDown()
                check(releaseRead.await(10, TimeUnit.SECONDS)) { "Read release was not unblocked" }
            }
            result
        }.`when`(f.session).snapshot(
            anyString() ?: "",
            any(ControllerInputPlatform::class.java) ?: ControllerInputPlatform.FTC,
            anyBoolean(),
            any<() -> Unit>() ?: {},
        )

        val scope = f.newScope()
        try {
            val vm = SuperstructureStudioViewModel(
                projectPath = f.original.path,
                scope = scope,
                projectDocuments = f.documents,
                targetPlatform = ControllerInputPlatform.FTC,
                projectSession = f.session,
            )
            withTimeout(10_000) {
                vm.state.first { !it.loading }
            }

            // Create and save an initial coordinator
            vm.create("main-machine", "Initial Machine")
            vm.reviewSave()
            val initialReview = assertNotNull(vm.state.value.review)
            vm.confirmSave(initialReview.confirmationToken)
            withTimeout(10_000) {
                vm.state.first { !it.loading && !it.dirty }
            }

            val initialRevision = f.session.state.value.snapshot?.revision
            assertNotNull(initialRevision, "ProjectSession should have revision after initial save")
            assertEquals("Initial Machine", vm.state.value.saved?.displayName)

            // Arm barrier to hold next reload AFTER reading from session/disk
            blockRead.set(true)
            vm.reload(force = true)

            assertTrue(enterRead.await(10, TimeUnit.SECONDS), "Timed out waiting for reload to complete read")

            // While old reload read is held, edit the coordinator and perform a real save to disk
            vm.updateMetadata("Updated Machine Real Save", "New description")
            vm.reviewSave()
            val secondReview = assertNotNull(vm.state.value.review)
            vm.confirmSave(secondReview.confirmationToken)
            withTimeout(10_000) {
                vm.state.first { !it.loading && !it.dirty && it.saved?.displayName == "Updated Machine Real Save" }
            }

            val savedRevision = f.session.state.value.snapshot?.revision
            assertNotNull(savedRevision, "Session should have updated revision after second save")
            assertNotEquals(initialRevision, savedRevision, "Revision must advance after real save")
            assertEquals("Updated Machine Real Save", vm.state.value.saved?.displayName)

            // Release old read and join
            releaseRead.countDown()
            f.joinWork()

            // Verify ViewModel state and session retained the saved revision and content
            val finalSaved = vm.state.value.saved
            assertNotNull(finalSaved, "Saved document should not be null after reload completes")
            assertEquals("Updated Machine Real Save", finalSaved.displayName)
            assertEquals(savedRevision, vm.state.value.projectRevision)
            assertEquals(savedRevision, f.session.state.value.snapshot?.revision)
            assertFalse(vm.state.value.dirty, "ViewModel must not be dirty after clean save and old reload join")
        } finally {
            releaseRead.countDown()
        }
    }

    @Test
    fun `reload atomically pairs document snapshot with session revision preventing stale save authorization`() = fixture { f ->
        val snapshotReturned = CountDownLatch(1)
        val releaseSnapshot = CountDownLatch(1)
        val blockSnapshot = AtomicBoolean(false)

        doAnswer { invocation ->
            val result = invocation.callRealMethod()
            val path = invocation.getArgument<String>(0)
            if (path == f.original.path && blockSnapshot.compareAndSet(true, false)) {
                snapshotReturned.countDown()
                check(releaseSnapshot.await(10, TimeUnit.SECONDS)) { "Snapshot was not released" }
            }
            result
        }.`when`(f.session).snapshot(
            anyString() ?: "",
            any(ControllerInputPlatform::class.java) ?: ControllerInputPlatform.FTC,
            anyBoolean(),
            any<() -> Unit>() ?: {},
        )

        val scope = f.newScope()
        try {
            val vm = SuperstructureStudioViewModel(
                projectPath = f.original.path,
                scope = scope,
                projectDocuments = f.documents,
                targetPlatform = ControllerInputPlatform.FTC,
                projectSession = f.session,
            )
            withTimeout(10_000) {
                vm.state.first { !it.loading }
            }

            // Create and save an initial coordinator
            vm.create("main-machine", "Initial Machine")
            vm.reviewSave()
            val initialReview = assertNotNull(vm.state.value.review)
            vm.confirmSave(initialReview.confirmationToken)
            withTimeout(10_000) {
                vm.state.first { !it.loading && !it.dirty }
            }

            val revisionAtSnapshot = f.session.state.value.snapshot?.revision
            assertNotNull(revisionAtSnapshot)

            // Arm barrier to intercept snapshot read for reload
            blockSnapshot.set(true)
            vm.reload(force = true)
            assertTrue(snapshotReturned.await(10, TimeUnit.SECONDS), "Timed out waiting for snapshot read")

            // While reload has read snapshot documents at revisionAtSnapshot, an external mutation advances session revision
            f.session.saveField(
                revisionAtSnapshot,
                League.FTC,
                RobotFieldConfig(name = "Autonomous Testing Field"),
            )
            val advancedRevision = f.session.state.value.snapshot?.revision
            assertNotNull(advancedRevision)
            assertNotEquals(revisionAtSnapshot, advancedRevision, "Session revision must have advanced from external mutation")

            // Release snapshot return
            releaseSnapshot.countDown()
            f.joinWork()

            withTimeout(10_000) {
                vm.state.first { !it.loading }
            }

            // The ViewModel loaded documents from revisionAtSnapshot.
            // Its projectRevision MUST be bound atomically to the snapshot, not read separately from session.state
            assertEquals(
                revisionAtSnapshot,
                vm.state.value.projectRevision,
                "ViewModel projectRevision must be bound atomically to the snapshot, not read separately from session.state",
            )

            // Stale save authorization check: attempting to save based on stale snapshot must fail
            vm.updateMetadata("Attempted Save On Stale Base", "Desc")
            vm.reviewSave()
            val review = assertNotNull(vm.state.value.review)
            vm.confirmSave(review.confirmationToken)
            withTimeout(10_000) {
                vm.state.first { !it.loading && it.error != null }
            }

            assertTrue(
                vm.state.value.error?.contains("The project changed after this coordinator loaded") == true,
                "Expected stale save rejection error, but got: ${vm.state.value.error}",
            )
        } finally {
            releaseSnapshot.countDown()
        }
    }

    @Test
    fun `save completion preserves newer edits made while save is in flight`() = fixture { f ->
        val saveStarted = CountDownLatch(1)
        val releaseSave = CountDownLatch(1)
        val blockSave = AtomicBoolean(false)

        doAnswer { invocation ->
            val result = invocation.callRealMethod()
            if (blockSave.compareAndSet(true, false)) {
                saveStarted.countDown()
                check(releaseSave.await(10, TimeUnit.SECONDS)) { "Save release was not unblocked" }
            }
            result
        }.`when`(f.session).saveSuperstructure(
            any(ProjectSessionRevision::class.java) ?: ProjectSessionRevision(0, ""),
            any(SuperstructureDocument::class.java) ?: SuperstructureDocument(superstructureId = "dummy", initialStateId = "idle", faultStateId = "fault"),
            any(),
        )

        val scope = f.newScope()
        try {
            val vm = SuperstructureStudioViewModel(
                projectPath = f.original.path,
                scope = scope,
                projectDocuments = f.documents,
                targetPlatform = ControllerInputPlatform.FTC,
                projectSession = f.session,
            )
            withTimeout(10_000) {
                vm.state.first { !it.loading }
            }

            vm.create("main-machine", "Initial Machine")
            vm.reviewSave()
            val initialReview = assertNotNull(vm.state.value.review)
            vm.confirmSave(initialReview.confirmationToken)
            withTimeout(10_000) {
                vm.state.first { !it.loading && !it.dirty }
            }

            // Arm barrier to hold inside saveSuperstructure
            blockSave.set(true)
            vm.updateMetadata("First Saved Version", "First description")
            vm.reviewSave()
            val review = assertNotNull(vm.state.value.review)
            vm.confirmSave(review.confirmationToken)

            assertTrue(saveStarted.await(10, TimeUnit.SECONDS), "Timed out waiting for save to start")

            // While save is in flight, user performs further edits in the editor
            vm.updateMetadata("Second Newer Edit During Save", "Second description")
            assertEquals("Second Newer Edit During Save", vm.state.value.draft?.displayName)
            assertTrue(vm.state.value.dirty)

            // Release in-flight save to complete
            releaseSave.countDown()
            f.joinWork()

            withTimeout(10_000) {
                vm.state.first { !it.loading }
            }

            // Post-save completion must preserve the user's newer edits!
            assertEquals("First Saved Version", vm.state.value.saved?.displayName, "Saved baseline on disk should be First Saved Version")
            assertEquals("Second Newer Edit During Save", vm.state.value.draft?.displayName, "Draft must preserve newer edits made during save")
            assertTrue(vm.state.value.dirty, "ViewModel must remain dirty because draft has uncommitted newer edits")
        } finally {
            releaseSave.countDown()
        }
    }

    @Test
    fun `positive control - real file save and reload preserves document and history`() = fixture { f ->
        val scope = f.newScope()
        val vm = SuperstructureStudioViewModel(
            projectPath = f.original.path,
            scope = scope,
            projectDocuments = f.documents,
            targetPlatform = ControllerInputPlatform.FTC,
            projectSession = f.session,
        )
        withTimeout(10_000) {
            vm.state.first { !it.loading }
        }

        vm.create("main-machine", "Main Machine")
        vm.addState("ACTIVE", "Active Posture")
        vm.addActionTransition("idle", "ACTIVE", "coord.enter")
        vm.addActionTransition("ACTIVE", "idle", "coord.exit")

        assertTrue(vm.state.value.validationErrors.isEmpty(), "Validation errors: ${vm.state.value.validationErrors}")
        assertTrue(vm.state.value.dirty)

        vm.reviewSave()
        val review = assertNotNull(vm.state.value.review)
        vm.confirmSave(review.confirmationToken)

        withTimeout(10_000) {
            vm.state.first { !it.loading && !it.dirty }
        }

        val savedDoc = vm.state.value.saved
        assertNotNull(savedDoc)
        assertEquals("Main Machine", savedDoc.displayName)
        assertEquals(listOf("idle", "fault", "ACTIVE"), savedDoc.states.map { it.stateId })
        assertEquals(setOf("coord.enter", "coord.exit"), savedDoc.transitions.mapNotNullTo(linkedSetOf()) { it.actionKey })

        val currentFile = File(f.original, ".ares/superstructures/main-machine.aressuperstructure")
        assertTrue(currentFile.isFile, "Superstructure file should exist on disk")
        val historyDir = File(f.original, ".ares/history/superstructures/main-machine")
        assertTrue(historyDir.isDirectory && historyDir.listFiles().orEmpty().isNotEmpty(), "History file should exist")

        // Reload the project and ensure document matches disk
        vm.reload(force = true)
        f.joinWork()
        withTimeout(10_000) {
            vm.state.first { !it.loading }
        }

        assertEquals("main-machine", vm.state.value.selectedId)
        assertEquals(savedDoc.superstructureId, vm.state.value.saved?.superstructureId)
        assertEquals(savedDoc.displayName, vm.state.value.saved?.displayName)
        assertFalse(vm.state.value.dirty)
    }

    @Test
    fun `forced reload preserves edits made after discard was requested`() = fixture { f ->
        val vm = SuperstructureStudioViewModel(
            f.original.path, f.newScope(), f.documents,
            targetPlatform = ControllerInputPlatform.FTC, projectSession = f.session,
        )
        withTimeout(10_000) { vm.state.first { !it.loading } }
        vm.create("main-machine", "Saved machine")
        vm.reviewSave()
        vm.confirmSave(assertNotNull(vm.state.value.review).confirmationToken)
        f.joinWork()
        val savedRevision = vm.state.value.projectRevision
        vm.updateMetadata("Draft to discard", "Before reload")

        val readReady = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val blockOnce = AtomicBoolean(true)
        doAnswer { invocation ->
            val result = invocation.callRealMethod()
            if (blockOnce.compareAndSet(true, false)) {
                readReady.countDown()
                check(releaseRead.await(10, TimeUnit.SECONDS)) { "Forced reload was not released" }
            }
            result
        }.`when`(f.session).snapshot(
            anyString() ?: "", any(ControllerInputPlatform::class.java) ?: ControllerInputPlatform.FTC,
            anyBoolean(), any<() -> Unit>() ?: {},
        )
        try {
            vm.reload(force = true)
            assertTrue(readReady.await(10, TimeUnit.SECONDS))
            vm.updateMetadata("New edit after reload request", "Keep this edit")
            vm.setEditorError("target:idle:arm.target", "Enter a numeric target.")
            releaseRead.countDown()
            f.joinWork()
            assertEquals("New edit after reload request", vm.state.value.draft?.displayName)
            assertEquals("Saved machine", vm.state.value.saved?.displayName)
            assertEquals(savedRevision, vm.state.value.projectRevision)
            assertTrue(vm.state.value.dirty)
            assertEquals("Enter a numeric target.", vm.state.value.editorErrors["target:idle:arm.target"])
            assertFalse(vm.state.value.canSave)
            assertFalse(vm.state.value.loading)
        } finally {
            releaseRead.countDown()
        }
    }

    @Test
    fun `delayed save completion cannot replace a newer reload result or its revision`() = fixture { f ->
        val vm = SuperstructureStudioViewModel(
            f.original.path, f.newScope(), f.documents,
            targetPlatform = ControllerInputPlatform.FTC, projectSession = f.session,
        )
        withTimeout(10_000) { vm.state.first { !it.loading } }
        vm.create("main-machine", "Initial machine")
        vm.reviewSave()
        vm.confirmSave(assertNotNull(vm.state.value.review).confirmationToken)
        f.joinWork()

        val savedOnDisk = CountDownLatch(1)
        val releaseSave = CountDownLatch(1)
        val blockOnce = AtomicBoolean(true)
        doAnswer { invocation ->
            val result = invocation.callRealMethod()
            if (blockOnce.compareAndSet(true, false)) {
                savedOnDisk.countDown()
                check(releaseSave.await(10, TimeUnit.SECONDS)) { "Save completion was not released" }
            }
            result
        }.`when`(f.session).saveSuperstructure(
            any(ProjectSessionRevision::class.java) ?: ProjectSessionRevision(0, ""),
            any(SuperstructureDocument::class.java) ?: SuperstructureDocument("unused", initialStateId = "idle", faultStateId = "fault"),
            any(),
        )
        try {
            vm.updateMetadata("Delayed saved version", "")
            vm.reviewSave()
            vm.confirmSave(assertNotNull(vm.state.value.review).confirmationToken)
            assertTrue(savedOnDisk.await(10, TimeUnit.SECONDS))
            val saved = f.documents.superstructures.load(f.original.path, "main-machine")
            f.documents.superstructures.save(
                f.original.path, saved.copy(displayName = "Newer external version"),
                com.areslib.superstructure.SuperstructureDocumentCodec.contentHash(saved), emptyList(), emptySet(),
            )
            vm.reload(force = true)
            withTimeout(10_000) { vm.state.first { !it.loading && it.saved?.displayName == "Newer external version" } }
            val newerRevision = assertNotNull(vm.state.value.projectRevision)
            releaseSave.countDown()
            f.joinWork()
            assertEquals("Newer external version", vm.state.value.saved?.displayName)
            assertEquals("Newer external version", vm.state.value.draft?.displayName)
            assertEquals("Newer external version", vm.state.value.documents.single().displayName)
            assertEquals(newerRevision, vm.state.value.projectRevision)
            assertFalse(vm.state.value.dirty)
        } finally {
            releaseSave.countDown()
        }
    }

    private fun metadataDocument(projectId: String) = AresProjectMetadataDocument(
        projectId = projectId,
        identity = AresProjectIdentityDocument("23247", "2026", projectId, projectId),
        league = AresLeague.FTC,
        coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
        robotLengthMeters = 0.46,
        robotWidthMeters = 0.46,
        fieldLengthMeters = 3.6576,
        fieldWidthMeters = 3.6576,
        runtimeOptions = AresRuntimeOptionsDocument(
            ftc = AresFtcRuntimeOptionsDocument(),
        ),
    )

    private fun catalog(projectId: String) = CapabilityCatalogDocument(
        projectId = projectId,
        actions = listOf(
            ActionDescriptor("coord.enter", "Enter posture", "Requests the active posture."),
            ActionDescriptor("coord.exit", "Exit posture", "Requests the idle posture."),
        ),
    )

    private class Fixture(
        val root: File,
        val original: File,
        val other: File,
        val session: ProjectSession,
        val documents: AresProjectDocuments,
    ) {
        val jobs = CopyOnWriteArrayList<Job>()
        val scopes = CopyOnWriteArrayList<CoroutineScope>()

        fun newScope(): CoroutineScope {
            val job = SupervisorJob()
            jobs.add(job)
            val scope = CoroutineScope(job + Dispatchers.Default)
            scopes.add(scope)
            return scope
        }

        suspend fun joinWork() {
            withTimeout(10_000) {
                do {
                    val activeJobs = scopes.flatMap { it.coroutineContext[Job]?.children?.toList().orEmpty() }
                    activeJobs.joinAll()
                } while (scopes.any { it.coroutineContext[Job]?.children?.any() == true })
            }
        }
    }

    private fun fixture(
        block: suspend (Fixture) -> Unit,
    ) = runBlocking {
        val root = Files.createTempDirectory("superstructure-session-audit").toFile()
        val session = spy(ProjectSession())
        val documents = AresProjectDocuments()
        val original = File(root, "original")
        val other = File(root, "other")
        val f = Fixture(root, original, other, session, documents)
        try {
            for (projectDir in listOf(original, other)) {
                File(projectDir, ".ares/project.json").apply {
                    parentFile.mkdirs()
                    writeText(AresProjectMetadataCodec.encode(metadataDocument(projectDir.name)))
                }
                val subsystem = SubsystemTemplates.create(
                    SubsystemTemplate.POSITION_CONTROLLED_MECHANISM,
                    "arm",
                    "Arm",
                    SubsystemPlatform.FTC,
                )
                documents.subsystems.save(projectDir.path, subsystem)
                documents.capabilities.save(projectDir.path, catalog(projectDir.name))
            }
            block(f)
        } finally {
            f.jobs.forEach { it.cancel() }
            withTimeout(10_000) {
                f.jobs.joinAll()
            }
            session.clear()
            assertTrue(root.deleteRecursively(), "Fixture cleanup failed")
        }
    }
}
