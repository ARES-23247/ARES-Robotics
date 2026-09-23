package com.ares.analytics.viewmodel.superstructure

import com.ares.analytics.service.project.AresProjectDocuments
import com.areslib.controls.ControllerInputPlatform
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.SuperstructureStatePreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.spy
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SuperstructureDiscardRecoveryAuditTest {

    @Test
    fun `read failure on confirmed discard retains dirty state and retry succeeds`() = withProject { project, documents ->
        documents.superstructures.save(project.path, document("main-machine", "Main Machine"), null, emptyList(), emptySet())
        val shouldFail = AtomicBoolean(false)
        doAnswer { invocation ->
            if (shouldFail.get()) throw IOException("Injected disk read failure on discard reload")
            invocation.callRealMethod()
        }.`when`(documents).load(anyString() ?: "", nullable(ControllerInputPlatform::class.java))

        val viewModel = SuperstructureStudioViewModel(project.path, this, documents)
        withTimeout(5_000) { viewModel.state.first { !it.loading && it.selectedId == "main-machine" } }

        viewModel.updateMetadata("Edited Machine", "Unsaved draft")
        assertTrue(viewModel.state.value.dirty)
        assertTrue(viewModel.state.value.canSave)

        viewModel.reload()
        assertEquals("main-machine", viewModel.state.value.pendingSelectionId)

        shouldFail.set(true)
        viewModel.confirmDiscard()
        withTimeout(5_000) { viewModel.state.first { !it.loading && it.error != null } }

        assertTrue(viewModel.state.value.dirty, "Dirty flag must remain true when discard reload fails")
        assertTrue(viewModel.state.value.canSave, "canSave must remain true so user can recover unsaved edits")
        assertEquals("Edited Machine", viewModel.state.value.draft?.displayName)
        assertNotNull(viewModel.state.value.error)

        shouldFail.set(false)
        viewModel.reload()
        viewModel.confirmDiscard()
        withTimeout(5_000) { viewModel.state.first { !it.loading && !it.dirty && it.draft == it.saved } }

        assertFalse(viewModel.state.value.dirty)
        assertNull(viewModel.state.value.error)
        assertEquals("Main Machine", viewModel.state.value.draft?.displayName)
    }

    @Test
    fun `read failure on unsaved new draft retains dirty and prevents silent replacement`() = withProject { project, documents ->
        documents.superstructures.save(project.path, document("existing-machine", "Existing Machine"), null, emptyList(), emptySet())
        val shouldFail = AtomicBoolean(false)
        doAnswer { invocation ->
            if (shouldFail.get()) throw IOException("Injected filesystem error during reload")
            invocation.callRealMethod()
        }.`when`(documents).load(anyString() ?: "", nullable(ControllerInputPlatform::class.java))

        val viewModel = SuperstructureStudioViewModel(project.path, this, documents)
        withTimeout(5_000) { viewModel.state.first { !it.loading && it.selectedId == "existing-machine" } }

        viewModel.create("new-machine", "New Machine")
        assertEquals("new-machine", viewModel.state.value.selectedId)
        assertTrue(viewModel.state.value.dirty)
        assertTrue(viewModel.state.value.canSave)

        viewModel.reload()
        assertEquals("new-machine", viewModel.state.value.pendingSelectionId)

        shouldFail.set(true)
        viewModel.confirmDiscard()
        withTimeout(5_000) { viewModel.state.first { !it.loading && it.error != null } }

        assertTrue(viewModel.state.value.dirty, "Unsaved draft must remain dirty on read failure")
        assertTrue(viewModel.state.value.canSave, "Unsaved draft must be saveable after load failure")
        assertEquals("new-machine", viewModel.state.value.selectedId)

        viewModel.create("other-machine", "Other Machine")
        assertEquals("Save or discard the current draft before creating another coordinator.", viewModel.state.value.error)
        assertEquals("new-machine", viewModel.state.value.selectedId)
        assertEquals("New Machine", viewModel.state.value.draft?.displayName)
    }

    @Test
    fun `edits made while discard reload is pending are preserved when read completes`() = withProject { project, documents ->
        documents.superstructures.save(project.path, document("main-machine", "Main Machine"), null, emptyList(), emptySet())
        val readStarted = CountDownLatch(1)
        val allowRead = CountDownLatch(1)
        val interceptRead = AtomicBoolean(false)

        doAnswer { invocation ->
            val result = invocation.callRealMethod()
            if (interceptRead.compareAndSet(true, false)) {
                readStarted.countDown()
                check(allowRead.await(10, TimeUnit.SECONDS)) { "Timed out waiting for test unblock" }
            }
            result
        }.`when`(documents).load(anyString() ?: "", nullable(ControllerInputPlatform::class.java))

        val viewModel = SuperstructureStudioViewModel(project.path, this, documents)
        withTimeout(5_000) { viewModel.state.first { !it.loading && it.selectedId == "main-machine" } }

        viewModel.updateMetadata("First Edit", "Initial description")
        assertTrue(viewModel.state.value.dirty)

        viewModel.reload()
        assertEquals("main-machine", viewModel.state.value.pendingSelectionId)

        try {
            interceptRead.set(true)
            viewModel.confirmDiscard()
            assertTrue(withContext(Dispatchers.IO) { readStarted.await(10, TimeUnit.SECONDS) }, "Discard reload did not reach read interceptor")
            viewModel.updateMetadata("Second Edit", "Subsequent description")
        } finally {
            allowRead.countDown()
        }

        withTimeout(5_000) { viewModel.state.first { !it.loading } }

        assertEquals("Second Edit", viewModel.state.value.draft?.displayName)
        assertTrue(viewModel.state.value.dirty)
        assertTrue(viewModel.state.value.canSave)
    }

    @Test
    fun `successful selection discard switches target coordinator and clears dirty`() = withProject { project, documents ->
        documents.superstructures.save(project.path, document("machine-a", "Machine A"), null, emptyList(), emptySet())
        documents.superstructures.save(project.path, document("machine-b", "Machine B"), null, emptyList(), emptySet())

        val viewModel = SuperstructureStudioViewModel(project.path, this, documents)
        withTimeout(5_000) { viewModel.state.first { !it.loading && it.selectedId == "machine-a" } }

        viewModel.updateMetadata("Machine A Modified", "Pending changes")
        assertTrue(viewModel.state.value.dirty)

        viewModel.select("machine-b")
        assertEquals("machine-b", viewModel.state.value.pendingSelectionId)
        assertEquals("machine-a", viewModel.state.value.selectedId)

        viewModel.confirmDiscard()
        withTimeout(5_000) { viewModel.state.first { !it.loading && !it.dirty && it.selectedId == "machine-b" } }

        assertEquals("machine-b", viewModel.state.value.selectedId)
        assertEquals("Machine B", viewModel.state.value.draft?.displayName)
        assertFalse(viewModel.state.value.dirty)
        assertNull(viewModel.state.value.pendingSelectionId)
    }

    private fun document(id: String, name: String) = SuperstructureDocument(
        superstructureId = id,
        displayName = name,
        initialStateId = "idle",
        states = listOf(SuperstructureStatePreset("idle"), SuperstructureStatePreset("fault")),
        faultStateId = "fault",
    )

    private fun withProject(block: suspend CoroutineScope.(File, AresProjectDocuments) -> Unit) {
        val project = Files.createTempDirectory("ares-discard-recovery-audit-test").toFile()
        try {
            val documents = spy(AresProjectDocuments())
            runBlocking {
                block(project, documents)
            }
        } finally {
            project.deleteRecursively()
        }
    }
}
