package com.ares.analytics.viewmodel.drivebase

import com.ares.analytics.service.drivebase.DriveGeometry
import com.ares.analytics.service.drivebase.DrivebaseKind
import com.ares.analytics.service.drivebase.DrivebaseProjectRepository
import com.ares.analytics.service.drivebase.canonicalTemplate
import com.ares.analytics.service.drivebase.toUiDrivebase
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.shared.models.League
import com.areslib.controls.ControllerInputPlatform
import com.areslib.drivetrain.DrivetrainDocumentCodec
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresFtcRuntimeOptionsDocument
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectIdentityDocument
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.AresRuntimeOptionsDocument
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class DrivebaseDiscardRecoveryAuditTest {

    @Test
    fun `failed confirmed reload leaves old draft dirty and saveable and reviewable in repository mode`() = runBlocking {
        val root = Files.createTempDirectory("drivebase-discard-recovery-audit").toFile()
        try {
            val project = "test-project"
            val projectDir = File(root, project)
            File(projectDir, ".ares/project.json").apply {
                parentFile.mkdirs()
                writeText(AresProjectMetadataCodec.encode(metadataDocument(project)))
            }
            val initialTemplate = canonicalTemplate(project, DrivebaseKind.FTC_MECANUM, League.FTC)
            val drivetrainFile = File(projectDir, ".ares/drivetrains/primary.aresdrivetrain").apply {
                parentFile.mkdirs()
                writeText(DrivetrainDocumentCodec.encode(initialTemplate))
            }

            val repository = spy(DrivebaseProjectRepository())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            try {
                val vm = DrivebaseBuilderViewModel(
                    projectPath = projectDir.path,
                    projectId = project,
                    league = League.FTC,
                    scope = scope,
                    repository = repository,
                    projectSession = null,
                )
                withTimeout(10_000) { vm.state.first { !it.loading && it.saved != null } }

                val editedGeometry = DriveGeometry(trackWidthMeters = 0.55, wheelBaseMeters = 0.55)
                vm.onIntent(DrivebaseBuilderIntent.UpdateGeometry(editedGeometry))
                assertTrue(vm.state.value.dirty)
                assertEquals(0.55, vm.state.value.draft.geometry.trackWidthMeters)

                vm.onIntent(DrivebaseBuilderIntent.ReviewSave)
                assertNotNull(vm.state.value.saveReview)

                // Corrupt drivetrain file on disk to trigger realistic load failure
                drivetrainFile.writeText("{ invalid json: not a valid document }")

                vm.onIntent(DrivebaseBuilderIntent.Reload)
                assertEquals(DrivebaseDiscardAction.RELOAD, vm.state.value.pendingDiscardAction)
                vm.onIntent(DrivebaseBuilderIntent.ConfirmDiscard)

                withTimeout(10_000) { vm.state.first { !it.loading && it.error != null } }

                assertTrue(vm.state.value.dirty, "Failed reload must not clear dirty flag")
                assertNull(vm.state.value.saveReview, "Discard must invalidate the old save confirmation")
                assertEquals(0.55, vm.state.value.draft.geometry.trackWidthMeters)
                assertNotNull(vm.state.value.error)

                // Restore disk document so base hash matches for save review
                drivetrainFile.writeText(DrivetrainDocumentCodec.encode(initialTemplate))
                vm.onIntent(DrivebaseBuilderIntent.ReviewSave)
                val review = withTimeout(10_000) { vm.state.first { it.saveReview != null } }.saveReview!!
                assertTrue(review.changes.isNotEmpty())

                vm.onIntent(DrivebaseBuilderIntent.ConfirmSave(review.confirmationToken))
                withTimeout(10_000) { vm.state.first { !it.dirty && it.saveReview == null } }
                assertFalse(vm.state.value.dirty)
                assertNull(vm.state.value.error)
                assertEquals(0.55, repository.load(projectDir.path).getOrThrow()!!.geometry.trackWidthMeters)
            } finally {
                scope.cancel()
                withTimeout(10_000) { scope.coroutineContext[Job]!!.join() }
            }
        } finally {
            assertTrue(root.deleteRecursively(), "Fixture cleanup failed")
        }
    }

    @Test
    fun `retry reload after failure successfully replaces draft with clean disk state`() = runBlocking {
        val root = Files.createTempDirectory("drivebase-discard-recovery-audit").toFile()
        try {
            val project = "test-project"
            val projectDir = File(root, project)
            File(projectDir, ".ares/project.json").apply {
                parentFile.mkdirs()
                writeText(AresProjectMetadataCodec.encode(metadataDocument(project)))
            }
            var initialTemplate = canonicalTemplate(project, DrivebaseKind.FTC_MECANUM, League.FTC).let { template ->
                template.copy(geometry = template.geometry.copy(trackWidthMeters = 0.40, wheelBaseMeters = 0.40))
            }
            val drivetrainFile = File(projectDir, ".ares/drivetrains/primary.aresdrivetrain").apply {
                parentFile.mkdirs()
                writeText(DrivetrainDocumentCodec.encode(initialTemplate))
            }

            val repository = spy(DrivebaseProjectRepository())
            initialTemplate = requireNotNull(repository.saveReviewed(
                projectDir.path,
                DrivetrainDocumentCodec.contentHash(initialTemplate),
                initialTemplate.toUiDrivebase(),
            ).canonical)
            val session = spy(ProjectSession(drivebaseRepository = repository))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            try {
                val vm = DrivebaseBuilderViewModel(
                    projectPath = projectDir.path,
                    projectId = project,
                    league = League.FTC,
                    scope = scope,
                    repository = repository,
                    projectSession = session,
                )
                withTimeout(10_000) { vm.state.first { !it.loading && it.saved != null } }
                assertTrue(vm.state.value.tuningProfileRepairIssues.isEmpty())
                assertFalse(vm.state.value.dirty)

                vm.onIntent(DrivebaseBuilderIntent.UpdateGeometry(DriveGeometry(trackWidthMeters = 0.55, wheelBaseMeters = 0.55)))
                assertTrue(vm.state.value.dirty)

                drivetrainFile.writeText("{ invalid json }")
                vm.onIntent(DrivebaseBuilderIntent.Reload)
                vm.onIntent(DrivebaseBuilderIntent.ConfirmDiscard)
                withTimeout(10_000) { vm.state.first { !it.loading && it.error != null } }
                assertTrue(vm.state.value.dirty)

                drivetrainFile.writeText(DrivetrainDocumentCodec.encode(initialTemplate))

                vm.onIntent(DrivebaseBuilderIntent.Reload)
                if (vm.state.value.pendingDiscardAction != null) {
                    vm.onIntent(DrivebaseBuilderIntent.ConfirmDiscard)
                }

                withTimeout(10_000) { vm.state.first { !it.loading && it.error == null && !it.dirty } }
                assertFalse(vm.state.value.dirty)
                assertEquals(0.40, vm.state.value.draft.geometry.trackWidthMeters)
                assertNull(vm.state.value.error)
            } finally {
                scope.cancel()
                withTimeout(10_000) { scope.coroutineContext[Job]!!.join() }
            }
        } finally {
            assertTrue(root.deleteRecursively(), "Fixture cleanup failed")
        }
    }

    @Test
    fun `later edit while reload is pending is preserved and not overwritten on load success`() = runBlocking {
        val root = Files.createTempDirectory("drivebase-discard-recovery-audit").toFile()
        try {
            val project = "test-project"
            val projectDir = File(root, project)
            File(projectDir, ".ares/project.json").apply {
                parentFile.mkdirs()
                writeText(AresProjectMetadataCodec.encode(metadataDocument(project)))
            }
            val initialTemplate = canonicalTemplate(project, DrivebaseKind.FTC_MECANUM, League.FTC).let { template ->
                template.copy(geometry = template.geometry.copy(trackWidthMeters = 0.40, wheelBaseMeters = 0.40))
            }
            File(projectDir, ".ares/drivetrains/primary.aresdrivetrain").apply {
                parentFile.mkdirs()
                writeText(DrivetrainDocumentCodec.encode(initialTemplate))
            }

            val repository = spy(DrivebaseProjectRepository())
            val session = spy(ProjectSession(drivebaseRepository = repository))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            val reloadEntered = CompletableDeferred<Unit>()
            val reloadRelease = CountDownLatch(1)
            val firstReloadBarrier = AtomicBoolean(true)

            try {
                val vm = DrivebaseBuilderViewModel(
                    projectPath = projectDir.path,
                    projectId = project,
                    league = League.FTC,
                    scope = scope,
                    repository = repository,
                    projectSession = session,
                )
                withTimeout(10_000) { vm.state.first { !it.loading && it.saved != null } }

                vm.onIntent(DrivebaseBuilderIntent.UpdateGeometry(DriveGeometry(trackWidthMeters = 0.50, wheelBaseMeters = 0.50)))
                assertTrue(vm.state.value.dirty)

                doAnswer { invocation ->
                    val snapshot = invocation.callRealMethod()
                    if (invocation.getArgument<String>(0) == projectDir.path && firstReloadBarrier.compareAndSet(true, false)) {
                        reloadEntered.complete(Unit)
                        check(reloadRelease.await(10, TimeUnit.SECONDS)) { "Reload read was not released" }
                    }
                    snapshot
                }.`when`(session).snapshot(
                    anyString(),
                    eq(ControllerInputPlatform.FTC) ?: ControllerInputPlatform.FTC,
                    anyBoolean(),
                    any<() -> Unit>() ?: {},
                )

                vm.onIntent(DrivebaseBuilderIntent.Reload)
                vm.onIntent(DrivebaseBuilderIntent.ConfirmDiscard)

                withTimeout(10_000) { reloadEntered.await() }
                assertTrue(vm.state.value.loading)

                // Later edit while reload is pending in snapshot read
                vm.onIntent(DrivebaseBuilderIntent.UpdateGeometry(DriveGeometry(trackWidthMeters = 0.65, wheelBaseMeters = 0.65)))
                assertEquals(0.65, vm.state.value.draft.geometry.trackWidthMeters)

                reloadRelease.countDown()
                withTimeout(10_000) { vm.state.first { !it.loading } }

                assertEquals(0.65, vm.state.value.draft.geometry.trackWidthMeters)
                assertTrue(vm.state.value.dirty)
                assertNull(vm.state.value.error)
            } finally {
                reloadRelease.countDown()
                scope.cancel()
                withTimeout(10_000) { scope.coroutineContext[Job]!!.join() }
            }
        } finally {
            assertTrue(root.deleteRecursively(), "Fixture cleanup failed")
        }
    }

    @Test
    fun `saved during pending reload preserves newly saved state and revision without downgrade`() = runBlocking {
        val root = Files.createTempDirectory("drivebase-discard-recovery-audit").toFile()
        try {
            val project = "test-project"
            val projectDir = File(root, project)
            File(projectDir, ".ares/project.json").apply {
                parentFile.mkdirs()
                writeText(AresProjectMetadataCodec.encode(metadataDocument(project)))
            }
            val initialTemplate = canonicalTemplate(project, DrivebaseKind.FTC_MECANUM, League.FTC).let { template ->
                template.copy(geometry = template.geometry.copy(trackWidthMeters = 0.40, wheelBaseMeters = 0.40))
            }
            File(projectDir, ".ares/drivetrains/primary.aresdrivetrain").apply {
                parentFile.mkdirs()
                writeText(DrivetrainDocumentCodec.encode(initialTemplate))
            }

            val repository = spy(DrivebaseProjectRepository())
            val session = spy(ProjectSession(drivebaseRepository = repository))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            val reloadEntered = CompletableDeferred<Unit>()
            val reloadRelease = CountDownLatch(1)
            val reloadBarrier = AtomicBoolean(true)

            try {
                val vm = DrivebaseBuilderViewModel(
                    projectPath = projectDir.path,
                    projectId = project,
                    league = League.FTC,
                    scope = scope,
                    repository = repository,
                    projectSession = session,
                )
                withTimeout(10_000) { vm.state.first { !it.loading && it.saved != null } }
                val initialRevision = vm.state.value.projectRevision
                assertNotNull(initialRevision)

                doAnswer { invocation ->
                    val snapshot = invocation.callRealMethod()
                    if (invocation.getArgument<String>(0) == projectDir.path && reloadBarrier.compareAndSet(true, false)) {
                        reloadEntered.complete(Unit)
                        check(reloadRelease.await(10, TimeUnit.SECONDS)) { "Reload read was not released" }
                    }
                    snapshot
                }.`when`(session).snapshot(
                    anyString(),
                    eq(ControllerInputPlatform.FTC) ?: ControllerInputPlatform.FTC,
                    anyBoolean(),
                    any<() -> Unit>() ?: {},
                )

                vm.onIntent(DrivebaseBuilderIntent.Reload)
                if (vm.state.value.pendingDiscardAction != null) vm.onIntent(DrivebaseBuilderIntent.ConfirmDiscard)
                withTimeout(10_000) { reloadEntered.await() }
                assertTrue(vm.state.value.loading)

                // While reload read is blocked, edit, review, and save to revision R2
                vm.onIntent(DrivebaseBuilderIntent.UpdateGeometry(DriveGeometry(trackWidthMeters = 0.55, wheelBaseMeters = 0.55)))
                vm.onIntent(DrivebaseBuilderIntent.ReviewSave)
                val token = withTimeout(10_000) { vm.state.first { it.saveReview != null } }.saveReview!!.confirmationToken
                vm.onIntent(DrivebaseBuilderIntent.ConfirmSave(token))
                withTimeout(10_000) { vm.state.first { !it.dirty && it.saved?.geometry?.trackWidthMeters == 0.55 } }

                val newerRevision = session.state.value.revision
                assertNotNull(newerRevision)
                assertEquals(newerRevision, vm.state.value.projectRevision)
                assertNotEquals(initialRevision, newerRevision)

                // Release the blocked reload
                reloadRelease.countDown()
                withTimeout(10_000) { vm.state.first { !it.loading } }

                // The newly saved 0.55 state and newer revision must not be downgraded by the old reload
                assertEquals(0.55, vm.state.value.saved?.geometry?.trackWidthMeters)
                assertEquals(newerRevision, vm.state.value.projectRevision)
                assertEquals(0.55, vm.state.value.draft.geometry.trackWidthMeters)
                assertFalse(vm.state.value.dirty)
                assertNull(vm.state.value.error)

                val diskDoc = repository.load(projectDir.path).getOrThrow()!!
                assertEquals(0.55, diskDoc.geometry.trackWidthMeters)
            } finally {
                reloadRelease.countDown()
                scope.cancel()
                withTimeout(10_000) { scope.coroutineContext[Job]!!.join() }
            }
        } finally {
            assertTrue(root.deleteRecursively(), "Fixture cleanup failed")
        }
    }

    @Test
    fun `kind switch discard confirmation replaces draft and keeps dirty state`() = runBlocking {
        val root = Files.createTempDirectory("drivebase-discard-recovery-audit").toFile()
        try {
            val project = "test-project"
            val projectDir = File(root, project)
            File(projectDir, ".ares/project.json").apply {
                parentFile.mkdirs()
                writeText(AresProjectMetadataCodec.encode(metadataDocument(project)))
            }
            val initialTemplate = canonicalTemplate(project, DrivebaseKind.FTC_MECANUM, League.FTC)
            File(projectDir, ".ares/drivetrains/primary.aresdrivetrain").apply {
                parentFile.mkdirs()
                writeText(DrivetrainDocumentCodec.encode(initialTemplate))
            }

            val repository = spy(DrivebaseProjectRepository())
            val session = spy(ProjectSession(drivebaseRepository = repository))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            try {
                val vm = DrivebaseBuilderViewModel(
                    projectPath = projectDir.path,
                    projectId = project,
                    league = League.FTC,
                    scope = scope,
                    repository = repository,
                    projectSession = session,
                )
                withTimeout(10_000) { vm.state.first { !it.loading && it.saved != null } }

                vm.onIntent(DrivebaseBuilderIntent.UpdateGeometry(DriveGeometry(trackWidthMeters = 0.50, wheelBaseMeters = 0.50)))
                assertTrue(vm.state.value.dirty)

                vm.onIntent(DrivebaseBuilderIntent.SelectKind(DrivebaseKind.DIFFERENTIAL))
                assertEquals(DrivebaseDiscardAction.CHANGE_KIND, vm.state.value.pendingDiscardAction)
                assertEquals(DrivebaseKind.DIFFERENTIAL, vm.state.value.pendingKind)

                vm.onIntent(DrivebaseBuilderIntent.ConfirmDiscard)

                assertEquals(DrivebaseKind.DIFFERENTIAL, vm.state.value.draft.kind)
                assertTrue(vm.state.value.dirty)
                assertNull(vm.state.value.pendingDiscardAction)
                assertNull(vm.state.value.pendingKind)
            } finally {
                scope.cancel()
                withTimeout(10_000) { scope.coroutineContext[Job]!!.join() }
            }
        } finally {
            assertTrue(root.deleteRecursively(), "Fixture cleanup failed")
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
}
