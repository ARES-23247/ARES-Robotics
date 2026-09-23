package com.ares.analytics.viewmodel.drivebase

import com.ares.analytics.service.drivebase.DriveGeometry
import com.ares.analytics.service.drivebase.DrivebaseKind
import com.ares.analytics.service.drivebase.DrivebaseProjectRepository
import com.ares.analytics.service.drivebase.canonicalTemplate
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

class DrivebaseSessionOwnershipAuditTest {

    @Test
    fun `cancelled old model does not overwrite replacement model session selection`() = runBlocking {
        val root = Files.createTempDirectory("drivebase-session-audit").toFile()
        try {
            val original = File(root, "original")
            val other = File(root, "other")
            for (project in listOf("original", "other")) {
                File(root, "$project/.ares/project.json").apply {
                    parentFile.mkdirs()
                    writeText(AresProjectMetadataCodec.encode(metadataDocument(project)))
                }
                val template = canonicalTemplate(project, DrivebaseKind.FTC_MECANUM, League.FTC)
                File(root, "$project/.ares/drivetrains/primary.aresdrivetrain").apply {
                    parentFile.mkdirs()
                    writeText(DrivetrainDocumentCodec.encode(template))
                }
            }
            val repository = spy(DrivebaseProjectRepository())
            val session = spy(ProjectSession(drivebaseRepository = repository))

            val firstCallBarrier = AtomicBoolean(true)
            val aEntered = CompletableDeferred<Unit>()
            val aRelease = CountDownLatch(1)

            doAnswer { invocation ->
                if (invocation.getArgument<String>(0) == original.path && firstCallBarrier.compareAndSet(true, false)) {
                    aEntered.complete(Unit)
                    check(aRelease.await(10, TimeUnit.SECONDS)) { "Model A session read was not released" }
                }
                invocation.callRealMethod()
            }.`when`(session).snapshot(
                anyString(),
                eq(ControllerInputPlatform.FTC) ?: ControllerInputPlatform.FTC,
                anyBoolean(),
                any<() -> Unit>() ?: {},
            )

            val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val vmA = DrivebaseBuilderViewModel(
                    projectPath = original.path,
                    projectId = "original",
                    league = League.FTC,
                    scope = scopeA,
                    repository = repository,
                    projectSession = session,
                )

                withTimeout(10_000) { aEntered.await() }

                val vmB = DrivebaseBuilderViewModel(
                    projectPath = other.path,
                    projectId = "other",
                    league = League.FTC,
                    scope = scopeB,
                    repository = repository,
                    projectSession = session,
                )
                withTimeout(10_000) { vmB.state.first { !it.loading && it.saved != null } }
                assertEquals(other.path, session.state.value.snapshot?.selection?.projectRoot)

                scopeA.cancel()
                aRelease.countDown()
                withTimeout(10_000) { scopeA.coroutineContext[Job]!!.join() }

                assertEquals(other.path, session.state.value.snapshot?.selection?.projectRoot)
                assertNull(vmA.state.value.error)
            } finally {
                aRelease.countDown()
                scopeA.cancel()
                withTimeout(10_000) { scopeA.coroutineContext[Job]!!.join() }
                scopeB.cancel()
                withTimeout(10_000) { scopeB.coroutineContext[Job]!!.join() }
            }
        } finally {
            assertTrue(root.deleteRecursively(), "Fixture cleanup failed")
        }
    }

    @Test
    fun `superseded repeated reload does not overwrite newer session revision or draft`() = runBlocking {
        val root = Files.createTempDirectory("drivebase-session-audit").toFile()
        try {
            val original = File(root, "original")
            File(root, "original/.ares/project.json").apply {
                parentFile.mkdirs()
                writeText(AresProjectMetadataCodec.encode(metadataDocument("original")))
            }
            val initialTemplate = canonicalTemplate("original", DrivebaseKind.FTC_MECANUM, League.FTC)
            val drivetrainFile = File(root, "original/.ares/drivetrains/primary.aresdrivetrain").apply {
                parentFile.mkdirs()
                writeText(DrivetrainDocumentCodec.encode(initialTemplate))
            }

            val repository = spy(DrivebaseProjectRepository())
            val session = spy(ProjectSession(drivebaseRepository = repository))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            val reloadRelease = CountDownLatch(1)
            try {
                val vm = DrivebaseBuilderViewModel(
                    projectPath = original.path,
                    projectId = "original",
                    league = League.FTC,
                    scope = scope,
                    repository = repository,
                    projectSession = session,
                )
                withTimeout(10_000) { vm.state.first { !it.loading && it.saved != null } }

                val firstReloadBarrier = AtomicBoolean(true)
                val reloadEntered = CompletableDeferred<Unit>()

                doAnswer { invocation ->
                    val snapshot = invocation.callRealMethod()
                    if (invocation.getArgument<String>(0) == original.path && firstReloadBarrier.compareAndSet(true, false)) {
                        // Hold delivery of the old document after its real read. A newer reload
                        // can now observe changed disk contents before the old result returns.
                        reloadEntered.complete(Unit)
                        check(reloadRelease.await(10, TimeUnit.SECONDS)) { "First reload read was not released" }
                    }
                    snapshot
                }.`when`(session).snapshot(
                    anyString(),
                    eq(ControllerInputPlatform.FTC) ?: ControllerInputPlatform.FTC,
                    anyBoolean(),
                    any<() -> Unit>() ?: {},
                )

                vm.onIntent(DrivebaseBuilderIntent.Reload)
                if (vm.state.value.pendingDiscardAction != null) {
                    vm.onIntent(DrivebaseBuilderIntent.ConfirmDiscard)
                }
                withTimeout(10_000) { reloadEntered.await() }

                val updatedTemplate = initialTemplate.copy(
                    geometry = initialTemplate.geometry.copy(trackWidthMeters = 0.42)
                )
                drivetrainFile.writeText(DrivetrainDocumentCodec.encode(updatedTemplate))

                vm.onIntent(DrivebaseBuilderIntent.Reload)
                if (vm.state.value.pendingDiscardAction != null) {
                    vm.onIntent(DrivebaseBuilderIntent.ConfirmDiscard)
                }
                withTimeout(10_000) {
                    vm.state.first { !it.loading && it.draft.geometry.trackWidthMeters == 0.42 }
                }
                val newerRevision = session.state.value.revision
                assertNotNull(newerRevision)
                assertEquals(newerRevision, vm.state.value.projectRevision)

                reloadRelease.countDown()
                withTimeout(10_000) {
                    do {
                        val active = scope.coroutineContext[Job]!!.children.toList()
                        if (active.isEmpty()) break
                        active.joinAll()
                    } while (scope.coroutineContext[Job]!!.children.any())
                }

                assertEquals(newerRevision, session.state.value.revision)
                assertEquals(newerRevision, vm.state.value.projectRevision)
                assertEquals(0.42, vm.state.value.draft.geometry.trackWidthMeters)
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
    fun `normal load edit review and save with project session succeeds end to end`() = runBlocking {
        val root = Files.createTempDirectory("drivebase-session-audit").toFile()
        try {
            val original = File(root, "original")
            File(root, "original/.ares/project.json").apply {
                parentFile.mkdirs()
                writeText(AresProjectMetadataCodec.encode(metadataDocument("original")))
            }
            val template = canonicalTemplate("original", DrivebaseKind.FTC_MECANUM, League.FTC)
            File(root, "original/.ares/drivetrains/primary.aresdrivetrain").apply {
                parentFile.mkdirs()
                writeText(DrivetrainDocumentCodec.encode(template))
            }

            val repository = spy(DrivebaseProjectRepository())
            val session = spy(ProjectSession(drivebaseRepository = repository))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            try {
                val vm = DrivebaseBuilderViewModel(
                    projectPath = original.path,
                    projectId = "original",
                    league = League.FTC,
                    scope = scope,
                    repository = repository,
                    projectSession = session,
                )
                withTimeout(10_000) { vm.state.first { !it.loading && it.saved != null } }
                assertEquals(original.path, session.state.value.snapshot?.selection?.projectRoot)
                val initialRevision = vm.state.value.projectRevision
                assertNotNull(initialRevision)

                val updatedGeometry = DriveGeometry(trackWidthMeters = 0.42, wheelBaseMeters = 0.42)
                vm.onIntent(DrivebaseBuilderIntent.UpdateGeometry(updatedGeometry))
                assertTrue(vm.state.value.dirty)

                vm.onIntent(DrivebaseBuilderIntent.ReviewSave)
                val token = withTimeout(10_000) { vm.state.first { it.saveReview != null } }.saveReview!!.confirmationToken
                vm.onIntent(DrivebaseBuilderIntent.ConfirmSave(token))

                withTimeout(10_000) { vm.state.first { !it.dirty && it.saveReview == null } }

                assertNull(vm.state.value.error)
                assertFalse(vm.state.value.dirty)
                val diskDoc = repository.load(original.path).getOrThrow()!!
                assertEquals(0.42, diskDoc.geometry.trackWidthMeters)
                assertEquals(0.42, diskDoc.geometry.wheelBaseMeters)
                assertEquals(session.state.value.revision, vm.state.value.projectRevision)
                assertNotEquals(initialRevision, vm.state.value.projectRevision)
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
