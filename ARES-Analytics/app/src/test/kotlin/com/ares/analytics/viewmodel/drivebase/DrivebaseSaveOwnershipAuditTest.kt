package com.ares.analytics.viewmodel.drivebase

import com.ares.analytics.service.drivebase.DriveGeometry
import com.ares.analytics.service.drivebase.DrivebaseDocument
import com.ares.analytics.service.drivebase.toCanonicalDrivebase
import com.ares.analytics.service.project.ProjectSessionMutationResult
import com.ares.analytics.service.project.ProjectSessionRevision
import com.ares.analytics.service.versioncontrol.ProjectCheckpointRecorder
import com.areslib.drivetrain.DrivetrainDocumentCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class DrivebaseSaveOwnershipAuditTest {

    @Test
    fun `later edit survives a successful reviewed save with true saved revision and second reviewed save works`() = runBlocking {
        val fixture = createSaveOwnershipAuditFixture()
        val saveEntered = CompletableDeferred<Unit>()
        val saveRelease = CountDownLatch(1)
        try {
            val vm = fixture.createViewModel()
            withTimeout(10_000) { vm.state.first { !it.loading && it.saved != null } }
            assertFalse(vm.state.value.dirty, "Initial load must be clean and not dirty")
            assertEquals(0.40, vm.state.value.saved!!.geometry.trackWidthMeters)

            // Make first edit (0.50)
            vm.onIntent(DrivebaseBuilderIntent.UpdateGeometry(DriveGeometry(trackWidthMeters = 0.50, wheelBaseMeters = 0.50)))
            assertTrue(vm.state.value.dirty)
            assertEquals(0.50, vm.state.value.draft.geometry.trackWidthMeters)

            vm.onIntent(DrivebaseBuilderIntent.ReviewSave)
            val review1 = withTimeout(10_000) { vm.state.first { it.saveReview != null } }.saveReview!!
            val token1 = review1.confirmationToken

            // Arm barrier AFTER initial load
            val barrierArmed = AtomicBoolean(true)
            doAnswer { invocation ->
                if (barrierArmed.compareAndSet(true, false)) {
                    saveEntered.complete(Unit)
                    check(saveRelease.await(10, TimeUnit.SECONDS)) { "Save barrier was not released" }
                }
                invocation.callRealMethod()
            }.`when`(fixture.repository).saveReviewed(
                eq(fixture.projectDir.path) ?: fixture.projectDir.path,
                any(),
                any<DrivebaseDocument>() ?: vm.state.value.draft,
            )

            // Launch save 1
            vm.onIntent(DrivebaseBuilderIntent.ConfirmSave(token1))
            withTimeout(10_000) { saveEntered.await() }

            // While save 1 is blocked in IO, make a later edit (0.60)
            vm.onIntent(DrivebaseBuilderIntent.UpdateGeometry(DriveGeometry(trackWidthMeters = 0.60, wheelBaseMeters = 0.60)))
            assertEquals(0.60, vm.state.value.draft.geometry.trackWidthMeters)
            assertTrue(vm.state.value.dirty)

            vm.onIntent(DrivebaseBuilderIntent.ReviewSave)
            assertNotNull(vm.state.value.saveReview)

            // Release save 1
            saveRelease.countDown()

            // Await save 1 completion predicate: saved document matches 0.50 on disk
            withTimeout(10_000) {
                vm.state.first { it.saved?.geometry?.trackWidthMeters == 0.50 }
            }

            // Verify:
            // 1. Later edit (0.60) survived in draft
            assertEquals(0.60, vm.state.value.draft.geometry.trackWidthMeters, "Later edit must not be overwritten by save result")
            // 2. Draft is still dirty because 0.60 is unsaved
            assertTrue(vm.state.value.dirty, "Draft must remain dirty after later edit")
            // 3. Saved is accurate (0.50)
            assertEquals(0.50, vm.state.value.saved!!.geometry.trackWidthMeters, "Saved must reflect completed disk write")
            // 4. Project revision is accurate
            assertEquals(fixture.session.state.value.revision, vm.state.value.projectRevision)
            assertNull(vm.state.value.error)

            assertNull(vm.state.value.saveReview, "Review against the old saved base must be invalidated")

            // Now perform a second reviewed save for the later edit (0.60)
            vm.onIntent(DrivebaseBuilderIntent.ReviewSave)
            val review2 = withTimeout(10_000) { vm.state.first { it.saveReview != null } }.saveReview!!
            val token2 = review2.confirmationToken
            assertEquals(
                DrivetrainDocumentCodec.contentHash(vm.state.value.saved!!.toCanonicalDrivebase()),
                review2.baseContentHash,
                "Second review base content hash must match the newly saved drivebase",
            )

            vm.onIntent(DrivebaseBuilderIntent.ConfirmSave(token2))
            withTimeout(10_000) {
                vm.state.first { !it.dirty && it.saved?.geometry?.trackWidthMeters == 0.60 }
            }

            assertFalse(vm.state.value.dirty)
            assertEquals(0.60, vm.state.value.saved!!.geometry.trackWidthMeters)
            assertEquals(0.60, vm.state.value.draft.geometry.trackWidthMeters)
            assertNull(vm.state.value.error)
            val diskDoc = fixture.repository.load(fixture.projectDir.path).getOrThrow()!!
            assertEquals(0.60, diskDoc.geometry.trackWidthMeters, "Disk must contain final 0.60 geometry")
        } finally {
            saveRelease.countDown()
            fixture.close()
        }
    }

    @Test
    fun `deterministic duplicate confirmation keeps disk and session accurate with no false error`() = runBlocking {
        val fixture = createSaveOwnershipAuditFixture()
        val saveEntered = CompletableDeferred<Unit>()
        val saveRelease = CountDownLatch(1)
        try {
            val vm = fixture.createViewModel()
            withTimeout(10_000) { vm.state.first { !it.loading && it.saved != null } }

            vm.onIntent(DrivebaseBuilderIntent.UpdateGeometry(DriveGeometry(trackWidthMeters = 0.50, wheelBaseMeters = 0.50)))
            assertTrue(vm.state.value.dirty)

            vm.onIntent(DrivebaseBuilderIntent.ReviewSave)
            val token = withTimeout(10_000) { vm.state.first { it.saveReview != null } }.saveReview!!.confirmationToken

            val barrierArmed = AtomicBoolean(true)
            doAnswer { invocation ->
                if (barrierArmed.compareAndSet(true, false)) {
                    saveEntered.complete(Unit)
                    check(saveRelease.await(10, TimeUnit.SECONDS)) { "Save barrier was not released" }
                }
                invocation.callRealMethod()
            }.`when`(fixture.repository).saveReviewed(
                eq(fixture.projectDir.path) ?: fixture.projectDir.path,
                any(),
                any<DrivebaseDocument>() ?: vm.state.value.draft,
            )

            // First confirmation
            vm.onIntent(DrivebaseBuilderIntent.ConfirmSave(token))
            withTimeout(10_000) { saveEntered.await() }

            // Duplicate confirmation while first save is actively in flight
            vm.onIntent(DrivebaseBuilderIntent.ConfirmSave(token))

            // Release save
            saveRelease.countDown()

            withTimeout(10_000) {
                vm.state.first { !it.dirty && it.saved?.geometry?.trackWidthMeters == 0.50 }
            }
            fixture.joinScopeChildren()

            assertEquals(1, mockingDetails(fixture.session).invocations.count { it.method.name == "saveDrivebase" },
                "Duplicate confirmation must not dispatch a second stale write attempt")
            assertNull(vm.state.value.error, "Duplicate confirmation must not result in a false Stale/Conflict error")
            assertFalse(vm.state.value.dirty)
            assertEquals(0.50, vm.state.value.saved!!.geometry.trackWidthMeters)
            assertEquals(fixture.session.state.value.revision, vm.state.value.projectRevision)
        } finally {
            saveRelease.countDown()
            fixture.close()
        }
    }

    @Test
    fun `later invalid edit or review save retains validation message while prior save is pending`() = runBlocking {
        val fixture = createSaveOwnershipAuditFixture()
        val saveEntered = CompletableDeferred<Unit>()
        val saveRelease = CountDownLatch(1)
        try {
            val vm = fixture.createViewModel()
            withTimeout(10_000) { vm.state.first { !it.loading && it.saved != null } }

            vm.onIntent(DrivebaseBuilderIntent.UpdateGeometry(DriveGeometry(trackWidthMeters = 0.50, wheelBaseMeters = 0.50)))
            vm.onIntent(DrivebaseBuilderIntent.ReviewSave)
            val token = withTimeout(10_000) { vm.state.first { it.saveReview != null } }.saveReview!!.confirmationToken

            val barrierArmed = AtomicBoolean(true)
            doAnswer { invocation ->
                if (barrierArmed.compareAndSet(true, false)) {
                    saveEntered.complete(Unit)
                    check(saveRelease.await(10, TimeUnit.SECONDS)) { "Save barrier was not released" }
                }
                invocation.callRealMethod()
            }.`when`(fixture.repository).saveReviewed(
                eq(fixture.projectDir.path) ?: fixture.projectDir.path,
                any(),
                any<DrivebaseDocument>() ?: vm.state.value.draft,
            )

            vm.onIntent(DrivebaseBuilderIntent.ConfirmSave(token))
            withTimeout(10_000) { saveEntered.await() }

            // A temporary zero width is reachable while editing the geometry field.
            vm.onIntent(DrivebaseBuilderIntent.UpdateGeometry(DriveGeometry(trackWidthMeters = 0.0, wheelBaseMeters = 0.60)))
            vm.onIntent(DrivebaseBuilderIntent.ReviewSave)
            val expectedValidationMessage = assertNotNull(vm.state.value.error)
            assertTrue(vm.state.value.issues.any { it.severity == com.ares.analytics.service.drivebase.DrivebaseIssueSeverity.ERROR })

            // Release save
            saveRelease.countDown()

            withTimeout(10_000) {
                vm.state.first { it.saved?.geometry?.trackWidthMeters == 0.50 }
            }
            fixture.joinScopeChildren()

            // The validation error must NOT be erased just because the save succeeded
            assertEquals(expectedValidationMessage, vm.state.value.error, "Validation message must be retained after save completion")
            assertEquals(0.50, vm.state.value.saved!!.geometry.trackWidthMeters)
        } finally {
            saveRelease.countDown()
            fixture.close()
        }
    }

    @Test
    fun `reload with late save result preserves reload outcome and distinct later draft`() = runBlocking {
        val fixture = createSaveOwnershipAuditFixture()
        val saveEntered = CompletableDeferred<Unit>()
        val saveRelease = CountDownLatch(1)
        try {
            val vm = fixture.createViewModel()
            withTimeout(10_000) { vm.state.first { !it.loading && it.saved != null } }

            vm.onIntent(DrivebaseBuilderIntent.UpdateGeometry(DriveGeometry(trackWidthMeters = 0.50, wheelBaseMeters = 0.50)))
            assertTrue(vm.state.value.dirty)

            vm.onIntent(DrivebaseBuilderIntent.ReviewSave)
            val token = withTimeout(10_000) { vm.state.first { it.saveReview != null } }.saveReview!!.confirmationToken

            // Spy session.saveDrivebase: call real method then withhold returned result AFTER lock is released
            val barrierArmed = AtomicBoolean(true)
            doAnswer { invocation ->
                val result = invocation.callRealMethod()
                if (barrierArmed.compareAndSet(true, false)) {
                    saveEntered.complete(Unit)
                    check(saveRelease.await(10, TimeUnit.SECONDS)) { "Save release barrier was not released" }
                }
                result
            }.`when`(fixture.session).saveDrivebase(
                any<ProjectSessionRevision>() ?: vm.state.value.projectRevision!!,
                any(),
                any<DrivebaseDocument>() ?: vm.state.value.draft,
            )

            vm.onIntent(DrivebaseBuilderIntent.ConfirmSave(token))
            withTimeout(10_000) { saveEntered.await() }

            // Reload to actual written state while result return is held
            vm.onIntent(DrivebaseBuilderIntent.Reload)
            if (vm.state.value.pendingDiscardAction != null) {
                vm.onIntent(DrivebaseBuilderIntent.ConfirmDiscard)
            }
            withTimeout(10_000) {
                vm.state.first { !it.loading && it.saved?.geometry?.trackWidthMeters == 0.50 }
            }

            // Make distinct later draft (0.70)
            vm.onIntent(DrivebaseBuilderIntent.UpdateGeometry(DriveGeometry(trackWidthMeters = 0.70, wheelBaseMeters = 0.70)))
            assertTrue(vm.state.value.dirty)

            // Release old return
            saveRelease.countDown()
            fixture.joinScopeChildren()

            // Verify late save return did not clobber newer reload or distinct later draft
            assertEquals(0.70, vm.state.value.draft.geometry.trackWidthMeters)
            assertTrue(vm.state.value.dirty)
            assertEquals(0.50, vm.state.value.saved!!.geometry.trackWidthMeters)
            assertEquals(fixture.session.state.value.revision, vm.state.value.projectRevision)
            assertNull(vm.state.value.error)
        } finally {
            saveRelease.countDown()
            fixture.close()
        }
    }

    @Test
    fun `reload with late save failure does not clobber completed reload`() = runBlocking {
        val fixture = createSaveOwnershipAuditFixture()
        val saveEntered = CompletableDeferred<Unit>()
        val saveRelease = CountDownLatch(1)
        try {
            val vm = fixture.createViewModel()
            withTimeout(10_000) { vm.state.first { !it.loading && it.saved != null } }

            vm.onIntent(DrivebaseBuilderIntent.UpdateGeometry(DriveGeometry(trackWidthMeters = 0.50, wheelBaseMeters = 0.50)))
            assertTrue(vm.state.value.dirty)

            vm.onIntent(DrivebaseBuilderIntent.ReviewSave)
            val token = withTimeout(10_000) { vm.state.first { it.saveReview != null } }.saveReview!!.confirmationToken

            // Withhold failure outside session lock until newer reload has completed
            val barrierArmed = AtomicBoolean(true)
            doAnswer { invocation ->
                if (barrierArmed.compareAndSet(true, false)) {
                    saveEntered.complete(Unit)
                    check(saveRelease.await(10, TimeUnit.SECONDS)) { "Save release barrier was not released" }
                }
                ProjectSessionMutationResult.Failed("Simulated disk failure")
            }.`when`(fixture.session).saveDrivebase(
                any<ProjectSessionRevision>() ?: vm.state.value.projectRevision!!,
                any(),
                any<DrivebaseDocument>() ?: vm.state.value.draft,
            )

            vm.onIntent(DrivebaseBuilderIntent.ConfirmSave(token))
            withTimeout(10_000) { saveEntered.await() }

            // Trigger reload while save failure is withheld
            vm.onIntent(DrivebaseBuilderIntent.Reload)
            if (vm.state.value.pendingDiscardAction != null) {
                vm.onIntent(DrivebaseBuilderIntent.ConfirmDiscard)
            }
            withTimeout(10_000) { vm.state.first { !it.loading && it.saved != null } }

            // Release failure outside session lock AFTER reload completed
            saveRelease.countDown()
            fixture.joinScopeChildren()

            // Verify late failure did not clobber reload
            assertNull(vm.state.value.error, "Late failed save error must not clobber completed reload")
            assertEquals(0.40, vm.state.value.saved!!.geometry.trackWidthMeters)
            assertEquals(0.40, vm.state.value.draft.geometry.trackWidthMeters)
            assertFalse(vm.state.value.dirty)
        } finally {
            saveRelease.countDown()
            fixture.close()
        }
    }

    @Test
    fun `controlled history callback does not clobber later action or status`() = runBlocking {
        val checkpointEntered = CompletableDeferred<Unit>()
        val checkpointRelease = CountDownLatch(1)
        val checkpointRecorder = ProjectCheckpointRecorder { _, _, _ ->
            checkpointEntered.complete(Unit)
            check(checkpointRelease.await(10, TimeUnit.SECONDS)) { "Checkpoint release barrier was not released" }
            throw IllegalStateException("Simulated git checkpoint failure")
        }
        val fixture = createSaveOwnershipAuditFixture(checkpointRecorder = checkpointRecorder)
        try {
            val vm = fixture.createViewModel()
            withTimeout(10_000) { vm.state.first { !it.loading && it.saved != null } }

            vm.onIntent(DrivebaseBuilderIntent.UpdateGeometry(DriveGeometry(trackWidthMeters = 0.50, wheelBaseMeters = 0.50)))
            vm.onIntent(DrivebaseBuilderIntent.ReviewSave)
            val token = withTimeout(10_000) { vm.state.first { it.saveReview != null } }.saveReview!!.confirmationToken

            vm.onIntent(DrivebaseBuilderIntent.ConfirmSave(token))
            withTimeout(10_000) { vm.state.first { it.saved?.geometry?.trackWidthMeters == 0.50 } }
            withTimeout(10_000) { checkpointEntered.await() }

            // While checkpoint is in flight, a new edit clears the earlier saved status.
            vm.onIntent(DrivebaseBuilderIntent.UpdateGeometry(DriveGeometry(trackWidthMeters = 0.60, wheelBaseMeters = 0.60)))
            val statusBeforeCheckpointFailure = vm.state.value.status

            // Now release checkpoint failure
            checkpointRelease.countDown()
            fixture.joinScopeChildren()

            // Verify that the checkpoint failure did not overwrite the newer status
            assertEquals(statusBeforeCheckpointFailure, vm.state.value.status)
            assertEquals(0.60, vm.state.value.draft.geometry.trackWidthMeters)
            assertTrue(vm.state.value.dirty)
        } finally {
            checkpointRelease.countDown()
            fixture.close()
        }
    }

    @Test
    fun `normal successful save and history failure control still shows its warning`() = runBlocking {
        val checkpointRecorder = ProjectCheckpointRecorder { _, _, _ ->
            throw IllegalStateException("Git lock busy")
        }
        val fixture = createSaveOwnershipAuditFixture(checkpointRecorder = checkpointRecorder)
        try {
            val vm = fixture.createViewModel()
            withTimeout(10_000) { vm.state.first { !it.loading && it.saved != null } }

            vm.onIntent(DrivebaseBuilderIntent.UpdateGeometry(DriveGeometry(trackWidthMeters = 0.50, wheelBaseMeters = 0.50)))
            vm.onIntent(DrivebaseBuilderIntent.ReviewSave)
            val token = withTimeout(10_000) { vm.state.first { it.saveReview != null } }.saveReview!!.confirmationToken

            vm.onIntent(DrivebaseBuilderIntent.ConfirmSave(token))
            withTimeout(10_000) {
                vm.state.first { it.status.contains("automatic Project History checkpoint failed: Git lock busy") }
            }

            assertTrue(vm.state.value.status.contains("Git lock busy"))
            assertEquals(0.50, vm.state.value.saved!!.geometry.trackWidthMeters)
            assertFalse(vm.state.value.dirty)
            assertNull(vm.state.value.error)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `cancelled save does not report stale cancellation error`() = runBlocking {
        val fixture = createSaveOwnershipAuditFixture()
        val saveEntered = CompletableDeferred<Unit>()
        val saveRelease = CountDownLatch(1)
        try {
            val vm = fixture.createViewModel()
            withTimeout(10_000) { vm.state.first { !it.loading && it.saved != null } }

            vm.onIntent(DrivebaseBuilderIntent.UpdateGeometry(DriveGeometry(trackWidthMeters = 0.50, wheelBaseMeters = 0.50)))
            vm.onIntent(DrivebaseBuilderIntent.ReviewSave)
            val token = withTimeout(10_000) { vm.state.first { it.saveReview != null } }.saveReview!!.confirmationToken

            val barrierArmed = AtomicBoolean(true)
            doAnswer { invocation ->
                if (barrierArmed.compareAndSet(true, false)) {
                    saveEntered.complete(Unit)
                    check(saveRelease.await(10, TimeUnit.SECONDS)) { "Save barrier was not released" }
                }
                invocation.callRealMethod()
            }.`when`(fixture.repository).saveReviewed(
                eq(fixture.projectDir.path) ?: fixture.projectDir.path,
                any(),
                any<DrivebaseDocument>() ?: vm.state.value.draft,
            )

            vm.onIntent(DrivebaseBuilderIntent.ConfirmSave(token))
            withTimeout(10_000) { saveEntered.await() }

            // Cancel the ViewModel scope while save is in flight
            fixture.scope.cancel()
            saveRelease.countDown()
            withTimeout(10_000) { fixture.scope.coroutineContext[Job]!!.join() }

            // State error must not be populated with CancellationException
            assertNull(vm.state.value.error, "Cancelled save must not publish a cancellation error")
        } finally {
            saveRelease.countDown()
            fixture.close()
        }
    }
}
