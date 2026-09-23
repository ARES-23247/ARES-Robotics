package com.ares.analytics.viewmodel.field

import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.project.persistence.FieldDocumentStore
import com.ares.analytics.shared.Obstacle
import com.ares.analytics.shared.models.League
import com.ares.analytics.viewmodel.FieldEditorIntent
import com.ares.analytics.viewmodel.FieldEditorViewModel
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
import org.mockito.Mockito.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class FieldEditorSessionOwnershipAuditTest {

    @Test
    fun `same-model project switch preserves second project session selection and save`() = fixture { f ->
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
            f.vm.onIntent(FieldEditorIntent.LoadConfig(f.original.path, League.FTC))
            assertTrue(originalStarted.await(10, TimeUnit.SECONDS), "Original load did not start")

            // While original is blocked inside snapshot before acquiring the lock, switch to other
            f.vm.onIntent(FieldEditorIntent.LoadConfig(f.other.path, League.FTC))
            withTimeout(10_000) {
                f.vm.state.first { !it.isLoading && it.projectRevision != null }
            }
            assertEquals(f.other.path, f.session.state.value.snapshot?.selection?.projectRoot)

            // Release original and let it run to completion
            releaseOriginal.countDown()
            f.joinWork()

            // Session must remain selected on other, not reverted to original
            assertEquals(f.other.path, f.session.state.value.snapshot?.selection?.projectRoot)

            // Positive edit and save on other succeeds and persists to disk
            val obstacle = Obstacle.Rectangle("audit-barrier", "Barrier", 0.1, 0.2, 0.5, 0.3)
            f.vm.onIntent(FieldEditorIntent.AddObstacle(obstacle))
            f.vm.onIntent(FieldEditorIntent.SaveDocument)
            f.joinWork()

            assertNull(f.vm.state.value.errorMessage)
            assertFalse(f.vm.state.value.isDirty)
            assertTrue(f.vm.state.value.saveStatus.startsWith("Saved field revision"))
            val onDisk = FieldDocumentStore.load(f.other.path, League.FTC)
            assertEquals(1, onDisk.obstacles.size)
            assertEquals("audit-barrier", onDisk.obstacles.single().id)
            assertEquals(f.session.state.value.revision, f.vm.state.value.projectRevision)
        } finally {
            releaseOriginal.countDown()
        }
    }

    @Test
    fun `cancelled old model does not revert shared session selection when replacement model loads`() = fixture { f ->
        val originalStarted = CountDownLatch(1)
        val releaseOriginal = CountDownLatch(1)
        val replacementScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val replacementVm = FieldEditorViewModel(
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
            f.vm.onIntent(FieldEditorIntent.LoadConfig(f.original.path, League.FTC))
            assertTrue(originalStarted.await(10, TimeUnit.SECONDS), "Original load did not start")

            // Cancel old model's scope while its snapshot call is blocked
            f.scope.cancel()

            // Replacement model loads other project
            replacementVm.onIntent(FieldEditorIntent.LoadConfig(f.other.path, League.FTC))
            withTimeout(10_000) {
                replacementVm.state.first { !it.isLoading && it.projectRevision != null }
            }
            assertEquals(f.other.path, f.session.state.value.snapshot?.selection?.projectRoot)

            // Release original and let it attempt to enter lock
            releaseOriginal.countDown()
            f.joinWork()

            // Shared session selection must remain on other, not overwritten by cancelled model
            assertEquals(f.other.path, f.session.state.value.snapshot?.selection?.projectRoot)

            // Positive edit and save on replacement model B persists to disk
            val piece = Obstacle.Circle("repl-circle", "Circle", 1.0, 1.0, 0.2)
            replacementVm.onIntent(FieldEditorIntent.AddObstacle(piece))
            replacementVm.onIntent(FieldEditorIntent.SaveDocument)
            withTimeout(10_000) {
                do {
                    val jobs = replacementScope.coroutineContext[Job]!!.children.toList()
                    jobs.joinAll()
                } while (replacementScope.coroutineContext[Job]!!.children.any())
            }

            assertNull(replacementVm.state.value.errorMessage)
            assertFalse(replacementVm.state.value.isDirty)
            assertTrue(replacementVm.state.value.saveStatus.startsWith("Saved field revision"))
            val onDisk = FieldDocumentStore.load(f.other.path, League.FTC)
            assertEquals(1, onDisk.obstacles.size)
            assertEquals("repl-circle", onDisk.obstacles.single().id)
            assertEquals(f.session.state.value.revision, replacementVm.state.value.projectRevision)
        } finally {
            releaseOriginal.countDown()
            replacementScope.cancel()
            withTimeout(10_000) { replacementScope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `normal load edit and save with project session succeeds end to end`() = fixture { f ->
        f.load(f.original)
        assertEquals(f.original.path, f.session.state.value.snapshot?.selection?.projectRoot)
        assertNotNull(f.vm.state.value.projectRevision)

        val obstacle = Obstacle.Circle("circle-1", "Center Circle", 1.0, 1.0, 0.25)
        f.vm.onIntent(FieldEditorIntent.AddObstacle(obstacle))
        f.vm.onIntent(FieldEditorIntent.SaveDocument)
        f.joinWork()

        assertNull(f.vm.state.value.errorMessage)
        assertFalse(f.vm.state.value.isDirty)
        assertTrue(f.vm.state.value.saveStatus.startsWith("Saved field revision"))
        val onDisk = FieldDocumentStore.load(f.original.path, League.FTC)
        assertEquals(1, onDisk.obstacles.size)
        assertEquals("circle-1", onDisk.obstacles.single().id)
        assertEquals(f.session.state.value.revision, f.vm.state.value.projectRevision)
    }

    @Test
    fun `cancelled load coroutine does not surface false cancellation error in state`() = fixture { f ->
        val loadStarted = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)

        doAnswer { invocation ->
            loadStarted.countDown()
            check(releaseLoad.await(10, TimeUnit.SECONDS)) { "Load was not released" }
            invocation.callRealMethod()
        }.`when`(f.session).snapshot(
            anyString() ?: "",
            eq(ControllerInputPlatform.FTC) ?: ControllerInputPlatform.FTC,
            anyBoolean(),
            any<() -> Unit>() ?: {},
        )

        try {
            f.vm.onIntent(FieldEditorIntent.LoadConfig(f.original.path, League.FTC))
            assertTrue(loadStarted.await(10, TimeUnit.SECONDS), "Load did not start")
            f.scope.cancel()
            releaseLoad.countDown()
            f.joinWork()

            assertNull(f.vm.state.value.errorMessage)
        } finally {
            releaseLoad.countDown()
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

    private inner class Fixture(
        val root: File,
        val scope: CoroutineScope,
        val vm: FieldEditorViewModel,
        val session: ProjectSession,
    ) {
        val original = File(root, "original")
        val other = File(root, "other")
        val permanentJobs = scope.coroutineContext[Job]!!.children.toSet()

        suspend fun load(project: File) {
            vm.onIntent(FieldEditorIntent.LoadConfig(project.path, League.FTC))
            withTimeout(10_000) {
                vm.state.first { it.projectRevision != null && !it.isLoading }
            }
        }

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
        val root = Files.createTempDirectory("field-session-audit").toFile()
        try {
            for (project in listOf("original", "other")) {
                val projectDir = File(root, project)
                File(projectDir, ".ares/project.json").apply {
                    parentFile.mkdirs()
                    writeText(AresProjectMetadataCodec.encode(metadataDocument(project)))
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = spy(ProjectSession())
            val vm = FieldEditorViewModel(
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
