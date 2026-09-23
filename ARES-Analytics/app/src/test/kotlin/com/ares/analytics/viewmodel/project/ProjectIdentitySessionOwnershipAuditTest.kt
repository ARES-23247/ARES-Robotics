package com.ares.analytics.viewmodel.project

import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.areslib.controls.ControllerInputPlatform
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresFtcRuntimeOptionsDocument
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectIdentityDocument
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.AresRuntimeOptionsDocument
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.mockito.Mockito.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ProjectIdentitySessionOwnershipAuditTest {

    @Test
    fun `same-model project switch during load preserves second project session selection`() = fixture { f ->
        val originalStarted = CountDownLatch(1)
        val releaseOriginal = CountDownLatch(1)

        doAnswer { invocation ->
            val path = invocation.getArgument<String>(0)
            if (path == f.original.path) {
                originalStarted.countDown()
                check(releaseOriginal.await(10, TimeUnit.SECONDS)) { "Original snapshot was not released" }
            }
            invocation.callRealMethod()
        }.`when`(f.session).snapshot(
            anyString() ?: "",
            eq(ControllerInputPlatform.FTC) ?: ControllerInputPlatform.FTC,
            anyBoolean(),
            any<() -> Unit>() ?: {},
        )

        try {
            f.vm.load(f.workspace(f.original))
            assertTrue(originalStarted.await(10, TimeUnit.SECONDS), "Original load did not start")

            // Switch to other project on the same model while original snapshot is blocked
            f.vm.load(f.workspace(f.other))
            withTimeout(10_000) {
                f.vm.state.first { !it.loading && it.projectPath == f.other.path && it.projectRevision != null }
            }
            assertEquals(f.other.path, f.session.state.value.snapshot?.selection?.projectRoot)

            // Release original load and wait for background work to complete
            releaseOriginal.countDown()
            f.joinWork()

            // Session must remain on other project, not reverted to original
            assertEquals(f.other.path, f.session.state.value.snapshot?.selection?.projectRoot)
            assertEquals(f.other.path, f.vm.state.value.projectPath)
        } finally {
            releaseOriginal.countDown()
        }
    }

    @Test
    fun `cancelled old model does not revert shared session selection when replacement model loads`() = fixture { f ->
        val originalStarted = CountDownLatch(1)
        val releaseOriginal = CountDownLatch(1)
        val replacementScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val replacementVm = ProjectIdentityViewModel(
            scope = replacementScope,
            projectSession = f.session,
        )

        doAnswer { invocation ->
            val path = invocation.getArgument<String>(0)
            if (path == f.original.path) {
                originalStarted.countDown()
                check(releaseOriginal.await(10, TimeUnit.SECONDS)) { "Original snapshot was not released" }
            }
            invocation.callRealMethod()
        }.`when`(f.session).snapshot(
            anyString() ?: "",
            eq(ControllerInputPlatform.FTC) ?: ControllerInputPlatform.FTC,
            anyBoolean(),
            any<() -> Unit>() ?: {},
        )

        try {
            f.vm.load(f.workspace(f.original))
            assertTrue(originalStarted.await(10, TimeUnit.SECONDS), "Original load did not start")

            // Cancel old model's scope while its snapshot call is blocked
            f.scope.cancel()

            // Replacement model loads other project
            replacementVm.load(f.workspace(f.other))
            withTimeout(10_000) {
                replacementVm.state.first { !it.loading && it.projectRevision != null }
            }
            assertEquals(f.other.path, f.session.state.value.snapshot?.selection?.projectRoot)

            // Release original and let it attempt to enter lock
            releaseOriginal.countDown()
            f.joinWork()

            // Shared session selection must remain on other, not overwritten by cancelled model
            assertEquals(f.other.path, f.session.state.value.snapshot?.selection?.projectRoot)
        } finally {
            releaseOriginal.countDown()
            replacementScope.cancel()
            withTimeout(10_000) { replacementScope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `workspace switch during initial project creation preserves second project selection and keeps first project disk write`() = fixture { f ->
        // Start original project without .ares/project.json so it exercises initial creation & fallback snapshot
        val originalMetadataFile = File(f.original, ".ares/project.json")
        assertTrue(originalMetadataFile.delete(), "Failed to remove metadata file for initial creation test")
        assertFalse(originalMetadataFile.exists())

        val saveSnapshotStarted = CountDownLatch(1)
        val releaseSaveSnapshot = CountDownLatch(1)

        doAnswer { invocation ->
            val path = invocation.getArgument<String>(0)
            if (path == f.original.path) {
                saveSnapshotStarted.countDown()
                check(releaseSaveSnapshot.await(10, TimeUnit.SECONDS)) { "Original save snapshot was not released" }
            }
            invocation.callRealMethod()
        }.`when`(f.session).snapshot(
            anyString() ?: "",
            eq(ControllerInputPlatform.FTC) ?: ControllerInputPlatform.FTC,
            anyBoolean(),
            any<() -> Unit>() ?: {},
        )

        try {
            // Load original project with no metadata
            f.vm.load(f.workspace(f.original))
            withTimeout(10_000) {
                f.vm.state.first { !it.loading && it.currentDocument == null }
            }
            assertTrue(f.vm.state.value.canReview)

            // Prepare review proposal for initial creation
            f.vm.update(ProjectIdentityField.DISPLAY_NAME, "Initial Saved Robot")
            f.vm.review()
            assertNotNull(f.vm.state.value.proposal)

            // Apply reviewed save: disk write completes first, then fallback snapshot reaches barrier
            f.vm.applyReviewed()
            assertTrue(saveSnapshotStarted.await(10, TimeUnit.SECONDS), "Save snapshot refresh did not start")

            // Disk write on original must already be complete
            assertTrue(originalMetadataFile.isFile, "Original metadata was not written to disk before session refresh")

            // Switch to other project while save refresh is blocked
            f.vm.load(f.workspace(f.other))
            withTimeout(10_000) {
                f.vm.state.first { !it.loading && it.projectPath == f.other.path && it.projectRevision != null }
            }
            assertEquals(f.other.path, f.session.state.value.snapshot?.selection?.projectRoot)

            // Release blocked save refresh and let it run
            releaseSaveSnapshot.countDown()
            f.joinWork()

            // Session must remain selected on other project
            assertEquals(f.other.path, f.session.state.value.snapshot?.selection?.projectRoot)

            // Original project on-disk file must remain preserved and valid
            assertTrue(originalMetadataFile.isFile)
            val writtenDoc = AresProjectMetadataCodec.decode(originalMetadataFile.readText())
            assertEquals("Initial Saved Robot", writtenDoc.identity.displayName)
        } finally {
            releaseSaveSnapshot.countDown()
        }
    }

    @Test
    fun `normal initial creation and save end to end updates session and persists metadata to disk`() = fixture { f ->
        val originalMetadataFile = File(f.original, ".ares/project.json")
        assertTrue(originalMetadataFile.delete(), "Failed to remove metadata file for initial creation test")
        assertFalse(originalMetadataFile.exists())

        f.vm.load(f.workspace(f.original))
        withTimeout(10_000) {
            f.vm.state.first { !it.loading && it.currentDocument == null }
        }
        assertTrue(f.vm.state.value.canReview)

        f.vm.update(ProjectIdentityField.DISPLAY_NAME, "Direct Created Robot")
        f.vm.review()
        assertNotNull(f.vm.state.value.proposal)

        f.vm.applyReviewed()
        f.joinWork()

        assertFalse(f.vm.state.value.messageIsError, f.vm.state.value.message)
        assertTrue(f.vm.state.value.message.orEmpty().contains("Created .ares/project.json"))
        assertTrue(originalMetadataFile.isFile)
        val writtenDoc = AresProjectMetadataCodec.decode(originalMetadataFile.readText())
        assertEquals("Direct Created Robot", writtenDoc.identity.displayName)
        assertEquals(f.original.path, f.session.state.value.snapshot?.selection?.projectRoot)
        assertNotNull(f.session.state.value.revision)
        assertEquals(f.session.state.value.revision, f.vm.state.value.projectRevision)
    }

    private fun metadataDocument(projectId: String) = AresProjectMetadataDocument(
        projectId = projectId,
        identity = AresProjectIdentityDocument("99999", "2026", projectId, projectId),
        league = AresLeague.FTC,
        coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
        robotLengthMeters = 0.45,
        robotWidthMeters = 0.43,
        fieldLengthMeters = 3.6576,
        fieldWidthMeters = 3.6576,
        runtimeOptions = AresRuntimeOptionsDocument(
            ftc = AresFtcRuntimeOptionsDocument(),
        ),
    )

    private inner class Fixture(
        val root: File,
        val scope: CoroutineScope,
        val vm: ProjectIdentityViewModel,
        val session: ProjectSession,
    ) {
        val original = File(root, "original")
        val other = File(root, "other")
        val permanentJobs = scope.coroutineContext[Job]!!.children.toSet()

        fun workspace(project: File) = WorkspaceConfig(
            id = project.name,
            teamId = "99999",
            seasonId = "2026",
            robotId = "robot",
            projectPath = project.path,
            league = League.FTC,
            robotLengthMeters = 0.45,
            robotWidthMeters = 0.43,
        )

        suspend fun joinWork() = withTimeout(10_000) {
            do {
                val jobs = scope.coroutineContext[Job]!!.children.filter { it !in permanentJobs }.toList()
                jobs.joinAll()
            } while (scope.coroutineContext[Job]!!.children.any { it !in permanentJobs })
        }
    }

    private fun fixture(
        block: suspend (Fixture) -> Unit,
    ) = runBlocking {
        val root = Files.createTempDirectory("project-identity-session-audit").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = spy(ProjectSession())
        try {
            for (projectName in listOf("original", "other")) {
                val projectDir = File(root, projectName)
                File(projectDir, "TeamCode/src/main/java/Robot.kt").apply {
                    parentFile.mkdirs()
                    writeText("class Robot")
                }
                File(projectDir, ".ares/project.json").apply {
                    parentFile.mkdirs()
                    writeText(AresProjectMetadataCodec.encode(metadataDocument(projectName)))
                }
            }
            val vm = ProjectIdentityViewModel(
                scope = scope,
                projectSession = session,
            )
            val f = Fixture(root, scope, vm, session)
            try {
                block(f)
            } finally {
                scope.cancel()
                try {
                    withTimeout(10_000) { scope.coroutineContext[Job]!!.join() }
                } finally {
                    session.clear()
                }
            }
        } finally {
            assertTrue(root.deleteRecursively(), "Fixture cleanup failed")
        }
    }
}
