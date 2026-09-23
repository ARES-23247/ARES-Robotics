package com.ares.analytics.viewmodel.drivebase

import com.ares.analytics.service.drivebase.DriveGeometry
import com.ares.analytics.service.drivebase.DrivebaseKind
import com.areslib.controls.ControllerInputPlatform
import com.areslib.drivetrain.DrivetrainDocumentCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class DrivebaseDiscardRecoveryAuditTest {

    @Test
    fun `failed confirmed reload leaves old draft dirty and saveable and reviewable in repository mode`() = runBlocking {
        val fixture = createDiscardRecoveryAuditFixture()
        try {
            val vm = fixture.createViewModel(customSession = null)
            withTimeout(10_000) { vm.state.first { !it.loading && it.saved != null } }

            val editedGeometry = DriveGeometry(trackWidthMeters = 0.55, wheelBaseMeters = 0.55)
            vm.onIntent(DrivebaseBuilderIntent.UpdateGeometry(editedGeometry))
            assertTrue(vm.state.value.dirty)
            assertEquals(0.55, vm.state.value.draft.geometry.trackWidthMeters)

            vm.onIntent(DrivebaseBuilderIntent.ReviewSave)
            assertNotNull(vm.state.value.saveReview)

            // Corrupt drivetrain file on disk to trigger realistic load failure
            fixture.drivetrainFile.writeText("{ invalid json: not a valid document }")

            vm.onIntent(DrivebaseBuilderIntent.Reload)
            assertEquals(DrivebaseDiscardAction.RELOAD, vm.state.value.pendingDiscardAction)
            vm.onIntent(DrivebaseBuilderIntent.ConfirmDiscard)

            withTimeout(10_000) { vm.state.first { !it.loading && it.error != null } }

            assertTrue(vm.state.value.dirty, "Failed reload must not clear dirty flag")
            assertNull(vm.state.value.saveReview, "Discard must invalidate the old save confirmation")
            assertEquals(0.55, vm.state.value.draft.geometry.trackWidthMeters)
            assertNotNull(vm.state.value.error)

            // Restore disk document so base hash matches for save review
            fixture.drivetrainFile.writeText(DrivetrainDocumentCodec.encode(fixture.initialTemplate))
            vm.onIntent(DrivebaseBuilderIntent.ReviewSave)
            val review = withTimeout(10_000) { vm.state.first { it.saveReview != null } }.saveReview!!
            assertTrue(review.changes.isNotEmpty())

            vm.onIntent(DrivebaseBuilderIntent.ConfirmSave(review.confirmationToken))
            withTimeout(10_000) { vm.state.first { !it.dirty && it.saveReview == null } }
            assertFalse(vm.state.value.dirty)
            assertNull(vm.state.value.error)
            assertEquals(0.55, fixture.repository.load(fixture.projectDir.path).getOrThrow()!!.geometry.trackWidthMeters)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `retry reload after failure successfully replaces draft with clean disk state`() = runBlocking {
        val fixture = createDiscardRecoveryAuditFixture(
            trackWidthMeters = 0.40,
            wheelBaseMeters = 0.40,
            saveReviewed = true,
        )
        try {
            val vm = fixture.createViewModel()
            withTimeout(10_000) { vm.state.first { !it.loading && it.saved != null } }
            assertTrue(vm.state.value.tuningProfileRepairIssues.isEmpty())
            assertFalse(vm.state.value.dirty)

            vm.onIntent(DrivebaseBuilderIntent.UpdateGeometry(DriveGeometry(trackWidthMeters = 0.55, wheelBaseMeters = 0.55)))
            assertTrue(vm.state.value.dirty)

            fixture.drivetrainFile.writeText("{ invalid json }")
            vm.onIntent(DrivebaseBuilderIntent.Reload)
            vm.onIntent(DrivebaseBuilderIntent.ConfirmDiscard)
            withTimeout(10_000) { vm.state.first { !it.loading && it.error != null } }
            assertTrue(vm.state.value.dirty)

            fixture.drivetrainFile.writeText(DrivetrainDocumentCodec.encode(fixture.initialTemplate))

            vm.onIntent(DrivebaseBuilderIntent.Reload)
            if (vm.state.value.pendingDiscardAction != null) {
                vm.onIntent(DrivebaseBuilderIntent.ConfirmDiscard)
            }

            withTimeout(10_000) { vm.state.first { !it.loading && it.error == null && !it.dirty } }
            assertFalse(vm.state.value.dirty)
            assertEquals(0.40, vm.state.value.draft.geometry.trackWidthMeters)
            assertNull(vm.state.value.error)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `later edit while reload is pending is preserved and not overwritten on load success`() = runBlocking {
        val fixture = createDiscardRecoveryAuditFixture(
            trackWidthMeters = 0.40,
            wheelBaseMeters = 0.40,
        )
        val reloadEntered = CompletableDeferred<Unit>()
        val reloadRelease = CountDownLatch(1)
        val firstReloadBarrier = AtomicBoolean(true)

        try {
            val vm = fixture.createViewModel()
            withTimeout(10_000) { vm.state.first { !it.loading && it.saved != null } }

            vm.onIntent(DrivebaseBuilderIntent.UpdateGeometry(DriveGeometry(trackWidthMeters = 0.50, wheelBaseMeters = 0.50)))
            assertTrue(vm.state.value.dirty)

            doAnswer { invocation ->
                val snapshot = invocation.callRealMethod()
                if (invocation.getArgument<String>(0) == fixture.projectDir.path && firstReloadBarrier.compareAndSet(true, false)) {
                    reloadEntered.complete(Unit)
                    check(reloadRelease.await(10, TimeUnit.SECONDS)) { "Reload read was not released" }
                }
                snapshot
            }.`when`(fixture.session).snapshot(
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
            fixture.close()
        }
    }

    @Test
    fun `saved during pending reload preserves newly saved state and revision without downgrade`() = runBlocking {
        val fixture = createDiscardRecoveryAuditFixture(
            trackWidthMeters = 0.40,
            wheelBaseMeters = 0.40,
        )
        val reloadEntered = CompletableDeferred<Unit>()
        val reloadRelease = CountDownLatch(1)
        val reloadBarrier = AtomicBoolean(true)

        try {
            val vm = fixture.createViewModel()
            withTimeout(10_000) { vm.state.first { !it.loading && it.saved != null } }
            val initialRevision = vm.state.value.projectRevision
            assertNotNull(initialRevision)

            doAnswer { invocation ->
                val snapshot = invocation.callRealMethod()
                if (invocation.getArgument<String>(0) == fixture.projectDir.path && reloadBarrier.compareAndSet(true, false)) {
                    reloadEntered.complete(Unit)
                    check(reloadRelease.await(10, TimeUnit.SECONDS)) { "Reload read was not released" }
                }
                snapshot
            }.`when`(fixture.session).snapshot(
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

            val newerRevision = fixture.session.state.value.revision
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

            val diskDoc = fixture.repository.load(fixture.projectDir.path).getOrThrow()!!
            assertEquals(0.55, diskDoc.geometry.trackWidthMeters)
        } finally {
            reloadRelease.countDown()
            fixture.close()
        }
    }

    @Test
    fun `kind switch discard confirmation replaces draft and keeps dirty state`() = runBlocking {
        val fixture = createDiscardRecoveryAuditFixture()
        try {
            val vm = fixture.createViewModel()
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
            fixture.close()
        }
    }
}
