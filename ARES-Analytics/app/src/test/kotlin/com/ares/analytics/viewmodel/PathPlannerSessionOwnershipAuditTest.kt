package com.ares.analytics.viewmodel

import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.project.persistence.ProjectMetadataRepository
import com.ares.analytics.shared.models.League
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.areslib.controls.ControllerInputPlatform
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresFtcRuntimeOptionsDocument
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectIdentityDocument
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.AresRuntimeOptionsDocument
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class PathPlannerSessionOwnershipAuditTest {

    @get:Rule val temporary = TemporaryFolder()

    private fun metadata(
        id: String,
        name: String,
        length: Double = 0.45,
        width: Double = 0.43,
    ) = AresProjectMetadataDocument(
        projectId = id,
        identity = AresProjectIdentityDocument("99999", "2026", id, name),
        league = AresLeague.FTC,
        coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
        robotLengthMeters = length,
        robotWidthMeters = width,
        fieldLengthMeters = 3.6576,
        fieldWidthMeters = 3.6576,
        runtimeOptions = AresRuntimeOptionsDocument(ftc = AresFtcRuntimeOptionsDocument()),
    )

    private class Fixture(
        val original: File,
        val other: File,
        val session: ProjectSession,
        val scope: CoroutineScope,
        val vm: PathPlannerViewModel,
        val metadataRepo: ProjectMetadataRepository,
    ) {
        suspend fun joinWork() {
            withTimeout(10_000) {
                do {
                    val jobs = scope.coroutineContext[Job]!!.children.toList()
                    jobs.joinAll()
                } while (scope.coroutineContext[Job]!!.children.any())
            }
        }
    }

    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val original = temporary.newFolder("orig-project")
        val other = temporary.newFolder("other-project")
        val metadataRepo = ProjectMetadataRepository()
        metadataRepo.save(original.path, metadata("orig-project", "Original Project", 0.45, 0.43))
        metadataRepo.save(other.path, metadata("other-project", "Other Project", 0.48, 0.41))

        val session = spy(ProjectSession())
        val job = SupervisorJob()
        val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(job + Dispatchers.Default + CoroutineExceptionHandler { _, error -> failures += error })
        val vm = PathPlannerViewModel(
            scope = scope,
            projectSession = session,
        )
        val f = Fixture(original, other, session, scope, vm, metadataRepo)
        try {
            block(f)
            assertTrue(failures.isEmpty(), "Unhandled failures escaped scope: $failures")
        } finally {
            job.cancel()
            withTimeout(10_000) { job.join() }
        }
    }

    @Test
    fun `same-model project switch preserves second project session selection and save`() = fixture { f ->
        val originalStarted = CountDownLatch(1)
        val releaseOriginal = CountDownLatch(1)
        val blockedOnce = AtomicBoolean(false)

        doAnswer { invocation ->
            val path = invocation.getArgument<String>(0)
            if (path == f.original.path && blockedOnce.compareAndSet(false, true)) {
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
            f.vm.onIntent(PathPlannerIntent.RefreshProject(f.original.path, League.FTC))
            assertTrue(originalStarted.await(10, TimeUnit.SECONDS), "Original load did not start")

            // While original is blocked inside snapshot before acquiring the lock, switch to other
            f.vm.onIntent(PathPlannerIntent.RefreshProject(f.other.path, League.FTC))
            withTimeout(10_000) {
                f.vm.state.first { !it.projectLoading && it.projectMetadata?.projectId == "other-project" && it.projectRevision != null }
            }
            assertEquals(f.other.path, f.session.state.value.snapshot?.selection?.projectRoot)

            // Release original and let it run to completion
            releaseOriginal.countDown()
            f.joinWork()

            // Session must remain selected on other, not reverted to original
            assertEquals(f.other.path, f.session.state.value.snapshot?.selection?.projectRoot)

            // Positive footprint update and save on other succeeds and persists to disk
            f.vm.onIntent(PathPlannerIntent.UpdateCanonicalRobotDimensions(f.other.path, RobotDimensions(0.52, 0.44)))
            val savedState = withTimeout(10_000) {
                f.vm.state.first { it.saveStatus.startsWith("Saved canonical robot footprint") }
            }
            f.joinWork()

            assertEquals(0.52, f.metadataRepo.load(f.other.path).getOrThrow().robotLengthMeters)
            assertEquals(0.44, f.metadataRepo.load(f.other.path).getOrThrow().robotWidthMeters)
            assertEquals(0.52, savedState.projectMetadata?.robotLengthMeters)
            assertEquals(f.session.state.value.revision, savedState.projectRevision)

            // Verify original project on disk was NOT mutated
            assertEquals(0.45, f.metadataRepo.load(f.original.path).getOrThrow().robotLengthMeters)
        } finally {
            releaseOriginal.countDown()
        }
    }

    @Test
    fun `cancelled old model does not revert shared session selection when replacement model loads`() = fixture { f ->
        val originalStarted = CountDownLatch(1)
        val releaseOriginal = CountDownLatch(1)
        val blockedOnce = AtomicBoolean(false)
        val replacementJob = SupervisorJob()
        val replacementScope = CoroutineScope(replacementJob + Dispatchers.Default)
        val replacementVm = PathPlannerViewModel(
            scope = replacementScope,
            projectSession = f.session,
        )

        doAnswer { invocation ->
            val path = invocation.getArgument<String>(0)
            if (path == f.original.path && blockedOnce.compareAndSet(false, true)) {
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
            f.vm.onIntent(PathPlannerIntent.RefreshProject(f.original.path, League.FTC))
            assertTrue(originalStarted.await(10, TimeUnit.SECONDS), "Original load did not start")

            // Cancel old model's scope while its snapshot call is blocked
            f.scope.cancel()

            // Replacement model loads other project
            replacementVm.onIntent(PathPlannerIntent.RefreshProject(f.other.path, League.FTC))
            withTimeout(10_000) {
                replacementVm.state.first { !it.projectLoading && it.projectMetadata?.projectId == "other-project" && it.projectRevision != null }
            }
            assertEquals(f.other.path, f.session.state.value.snapshot?.selection?.projectRoot)

            // Release original and let it attempt to enter lock
            releaseOriginal.countDown()
            f.joinWork()

            // Shared session selection must remain on other, not overwritten by cancelled model
            assertEquals(f.other.path, f.session.state.value.snapshot?.selection?.projectRoot)

            // Positive footprint update and save on replacement model B persists to disk
            replacementVm.onIntent(PathPlannerIntent.UpdateCanonicalRobotDimensions(f.other.path, RobotDimensions(0.55, 0.42)))
            val savedState = withTimeout(10_000) {
                replacementVm.state.first { it.saveStatus.startsWith("Saved canonical robot footprint") }
            }
            withTimeout(10_000) {
                do {
                    val jobs = replacementScope.coroutineContext[Job]!!.children.toList()
                    jobs.joinAll()
                } while (replacementScope.coroutineContext[Job]!!.children.any())
            }

            assertEquals(0.55, f.metadataRepo.load(f.other.path).getOrThrow().robotLengthMeters)
            assertEquals(0.42, f.metadataRepo.load(f.other.path).getOrThrow().robotWidthMeters)
            assertEquals(0.55, savedState.projectMetadata?.robotLengthMeters)
            assertEquals(f.session.state.value.revision, savedState.projectRevision)

            // Original project on disk was untouched
            assertEquals(0.45, f.metadataRepo.load(f.original.path).getOrThrow().robotLengthMeters)
        } finally {
            releaseOriginal.countDown()
            replacementJob.cancel()
            withTimeout(10_000) { replacementJob.join() }
        }
    }

    @Test
    fun `normal load and footprint save with project session succeeds end to end`() = fixture { f ->
        f.vm.onIntent(PathPlannerIntent.RefreshProject(f.original.path, League.FTC))
        withTimeout(10_000) {
            f.vm.state.first { !it.projectLoading && it.projectMetadata?.projectId == "orig-project" && it.projectRevision != null }
        }
        assertEquals(f.original.path, f.session.state.value.snapshot?.selection?.projectRoot)
        assertNotNull(f.vm.state.value.projectRevision)

        f.vm.onIntent(PathPlannerIntent.UpdateCanonicalRobotDimensions(f.original.path, RobotDimensions(0.50, 0.40)))
        val savedState = withTimeout(10_000) {
            f.vm.state.first { it.saveStatus.startsWith("Saved canonical robot footprint") }
        }
        f.joinWork()

        assertEquals(0.50, f.metadataRepo.load(f.original.path).getOrThrow().robotLengthMeters)
        assertEquals(0.40, f.metadataRepo.load(f.original.path).getOrThrow().robotWidthMeters)
        assertEquals(f.session.state.value.revision, savedState.projectRevision)
    }

    @Test
    fun `cancelled project refresh does not surface false error in viewmodel state`() = fixture { f ->
        val loadStarted = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val blockedOnce = AtomicBoolean(false)

        doAnswer { invocation ->
            val path = invocation.getArgument<String>(0)
            if (path == f.original.path && blockedOnce.compareAndSet(false, true)) {
                loadStarted.countDown()
                check(releaseLoad.await(10, TimeUnit.SECONDS)) { "Load was not released" }
            }
            invocation.callRealMethod()
        }.`when`(f.session).snapshot(
            anyString() ?: "",
            eq(ControllerInputPlatform.FTC) ?: ControllerInputPlatform.FTC,
            anyBoolean(),
            any<() -> Unit>() ?: {},
        )

        try {
            f.vm.onIntent(PathPlannerIntent.RefreshProject(f.original.path, League.FTC))
            assertTrue(loadStarted.await(10, TimeUnit.SECONDS), "Original load did not start")

            // Cancel the load by immediately switching to other project
            f.vm.onIntent(PathPlannerIntent.RefreshProject(f.other.path, League.FTC))
            withTimeout(10_000) {
                f.vm.state.first { !it.projectLoading && it.projectMetadata?.projectId == "other-project" }
            }

            releaseLoad.countDown()
            f.joinWork()

            val finalState = f.vm.state.value
            assertFalse(finalState.projectLoading)
            assertFalse(
                finalState.capabilityStatus.contains("Could not read project documents", ignoreCase = true),
                "Cancelled load must not surface read failure in capabilityStatus: ${finalState.capabilityStatus}"
            )
        } finally {
            releaseLoad.countDown()
        }
    }
}
