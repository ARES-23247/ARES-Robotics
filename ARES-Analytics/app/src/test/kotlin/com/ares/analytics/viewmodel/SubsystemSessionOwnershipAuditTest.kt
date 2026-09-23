package com.ares.analytics.viewmodel

import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.shared.models.League
import com.areslib.controls.ControllerInputPlatform
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresFtcRuntimeOptionsDocument
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectIdentityDocument
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.AresRuntimeOptionsDocument
import com.areslib.subsystem.SubsystemTemplate
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.eq
import org.mockito.Mockito.spy
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SubsystemSessionOwnershipAuditTest {

    @Test
    fun `closed old view model does not revert shared session selection when replacement model loads`() = fixture { f ->
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
            eq(ControllerInputPlatform.FTC) ?: ControllerInputPlatform.FTC,
            anyBoolean(),
            any<() -> Unit>() ?: {},
        )

        var vmA: SubsystemGeneratorViewModel? = null
        var vmB: SubsystemGeneratorViewModel? = null
        var jobA: Job? = null
        var jobB: Job? = null

        try {
            vmA = SubsystemGeneratorViewModel(f.original.path, League.FTC, projectSession = f.session, loadOnStart = false)
            jobA = vmA.reloadAsync()

            assertTrue(originalStarted.await(10, TimeUnit.SECONDS), "Original load did not start")

            // Close old model while blocked inside snapshot before acquiring the lock
            vmA.close()

            // Replacement model loads other project
            vmB = SubsystemGeneratorViewModel(f.other.path, League.FTC, projectSession = f.session, loadOnStart = false)
            jobB = vmB.reloadAsync()
            withTimeout(10_000) { jobB.join() }

            val snapshotB = f.session.state.value.snapshot
            assertNotNull(snapshotB, "Active project other should have populated session snapshot")
            assertEquals(f.other.path, snapshotB.selection.projectRoot)
            assertEquals("other", snapshotB.documents.query.metadata?.identity?.displayName)

            // Release original and let it attempt to enter lock
            releaseOriginal.countDown()
            withTimeout(10_000) { jobA.join() }

            // Shared session selection must remain on other, not reverted to original
            val finalSnapshot = f.session.state.value.snapshot
            assertNotNull(finalSnapshot, "Session snapshot must not be null after old work joins")
            assertEquals(
                f.other.path,
                finalSnapshot.selection.projectRoot,
                "Closed ViewModel A corrupted shared ProjectSession selection after ViewModel B became active",
            )
            assertEquals("other", finalSnapshot.documents.query.metadata?.identity?.displayName)
        } finally {
            releaseOriginal.countDown()
            vmA?.close()
            vmB?.close()
            jobA?.cancel()
            jobB?.cancel()
            withTimeout(10_000) {
                listOfNotNull(jobA, jobB).joinAll()
            }
        }
    }

    @Test
    fun `cancelled reloadAsync job on open model does not revert shared session selection when replacement model loads`() = fixture { f ->
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
            eq(ControllerInputPlatform.FTC) ?: ControllerInputPlatform.FTC,
            anyBoolean(),
            any<() -> Unit>() ?: {},
        )

        var vmA: SubsystemGeneratorViewModel? = null
        var vmB: SubsystemGeneratorViewModel? = null
        var jobA: Job? = null
        var jobB: Job? = null

        try {
            vmA = SubsystemGeneratorViewModel(f.original.path, League.FTC, projectSession = f.session, loadOnStart = false)
            jobA = vmA.reloadAsync()

            assertTrue(originalStarted.await(10, TimeUnit.SECONDS), "Original load did not start")

            // Cancel the specific in-flight reloadAsync job while model A remains open
            jobA.cancel()

            // Replacement model loads other project
            vmB = SubsystemGeneratorViewModel(f.other.path, League.FTC, projectSession = f.session, loadOnStart = false)
            jobB = vmB.reloadAsync()
            withTimeout(10_000) { jobB.join() }

            val snapshotB = f.session.state.value.snapshot
            assertNotNull(snapshotB, "Active project other should have populated session snapshot")
            assertEquals(f.other.path, snapshotB.selection.projectRoot)
            assertEquals("other", snapshotB.documents.query.metadata?.identity?.displayName)

            // Release original and let it attempt to enter lock
            releaseOriginal.countDown()
            withTimeout(10_000) { jobA.join() }

            // Shared session selection must remain on other, not overwritten by cancelled reload job
            val finalSnapshot = f.session.state.value.snapshot
            assertNotNull(finalSnapshot, "Session snapshot must not be null after old work joins")
            assertEquals(
                f.other.path,
                finalSnapshot.selection.projectRoot,
                "Cancelled reloadAsync job on ViewModel A reverted shared ProjectSession selection",
            )
            assertEquals("other", finalSnapshot.documents.query.metadata?.identity?.displayName)
        } finally {
            releaseOriginal.countDown()
            vmA?.close()
            vmB?.close()
            jobA?.cancel()
            jobB?.cancel()
            withTimeout(10_000) {
                listOfNotNull(jobA, jobB).joinAll()
            }
        }
    }

    @Test
    fun `positive control - in flight reload delayed after read does not overwrite subsequent real save`() = fixture { f ->
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
            eq(ControllerInputPlatform.FTC) ?: ControllerInputPlatform.FTC,
            anyBoolean(),
            any<() -> Unit>() ?: {},
        )

        var vm: SubsystemGeneratorViewModel? = null
        var reloadJob: Job? = null

        try {
            vm = SubsystemGeneratorViewModel(f.original.path, League.FTC, projectSession = f.session, loadOnStart = false)
            vm.reload()

            vm.newSubsystem(SubsystemTemplate.SIMPLE_ACTUATOR)
            vm.save()

            val initialRevision = f.session.state.value.snapshot?.revision
            assertNotNull(initialRevision, "ProjectSession should have revision after initial save")

            // Arm barrier to hold next reload AFTER reading from disk/session
            blockRead.set(true)
            reloadJob = vm.reloadAsync()

            assertTrue(enterRead.await(10, TimeUnit.SECONDS), "Timed out waiting for reload to complete read")

            // While old reload read is held, edit and perform a real save to disk
            vm.edit { it.copy(displayName = "Updated Actuator Real Save") }
            vm.save()

            val savedRevision = f.session.state.value.snapshot?.revision
            assertNotNull(savedRevision, "Session should have updated revision after second save")
            assertNotEquals(initialRevision, savedRevision, "Revision must advance after real save")
            assertEquals("Updated Actuator Real Save", vm.state.value.draft?.document?.displayName)
            assertFalse(vm.state.value.dirty, "Draft must be clean after save")

            // Release old read and join
            releaseRead.countDown()
            withTimeout(10_000) { reloadJob.join() }

            // Verify ViewModel state and session retained the saved revision and content
            val finalDraft = vm.state.value.draft
            assertNotNull(finalDraft, "Draft should not be null after reload job completes")
            assertEquals("Updated Actuator Real Save", finalDraft.document.displayName)
            assertEquals(savedRevision, vm.state.value.projectRevision)
            assertEquals(savedRevision, f.session.state.value.snapshot?.revision)
        } finally {
            releaseRead.countDown()
            vm?.close()
            reloadJob?.cancel()
            withTimeout(10_000) {
                listOfNotNull(reloadJob).joinAll()
            }
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

    private class Fixture(
        val root: File,
        val session: ProjectSession,
    ) {
        val original = File(root, "original")
        val other = File(root, "other")
    }

    private fun fixture(
        block: suspend (Fixture) -> Unit,
    ) = runBlocking {
        val root = Files.createTempDirectory("subsystem-session-audit").toFile()
        try {
            for (project in listOf("original", "other")) {
                val projectDir = File(root, project)
                File(projectDir, ".ares/project.json").apply {
                    parentFile.mkdirs()
                    writeText(AresProjectMetadataCodec.encode(metadataDocument(project)))
                }
            }
            val session = spy(ProjectSession())
            val f = Fixture(root, session)
            try {
                block(f)
            } finally {
                session.clear()
            }
        } finally {
            assertTrue(root.deleteRecursively(), "Fixture cleanup failed")
        }
    }
}
