package com.ares.analytics.viewmodel

import com.ares.analytics.service.project.*
import com.ares.analytics.shared.models.League
import com.areslib.controls.ControllerInputPlatform
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class SubsystemReloadTest {
    @Test fun delayedReloadCannotDiscardEditsMadeAfterItStarted() = runBlocking {
        val root = createTempDirectory("ares-subsystem-reload").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val actual = AresProjectDocuments()
        var delayReload = false
        val gateway = object : ProjectDocumentGateway by actual {
            override fun load(projectPath: String, targetPlatform: ControllerInputPlatform?): AresProjectDocumentSnapshot {
                assertFalse(java.awt.EventQueue.isDispatchThread())
                if (delayReload) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                return actual.load(projectPath, targetPlatform)
            }
        }
        val vm = SubsystemGeneratorViewModel(root.path, League.FTC, documents = gateway)
        try {
            vm.newSubsystem()
            delayReload = true
            val reload = withContext(Dispatchers.Main) { vm.reloadAsync() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            vm.edit { it.copy(displayName = "Keep this draft") }
            val draft = vm.state.value.draft
            release.countDown()
            reload.join()
            assertEquals(draft, vm.state.value.draft)
            assertTrue(vm.state.value.dirty)
        } finally { release.countDown(); vm.close(); root.deleteRecursively() }
    }
    @Test fun creationWaitsForInitialDocumentIdentityScan() = runBlocking {
        val root = createTempDirectory("ares-subsystem-initial-load").toFile()
        val actual = AresProjectDocuments()
        val existing = com.areslib.subsystem.SubsystemTemplates.create(
            com.areslib.subsystem.SubsystemTemplate.SIMPLE_ACTUATOR, "new-subsystem", "ExistingSubsystem",
            com.areslib.subsystem.SubsystemPlatform.FTC)
        actual.subsystems.save(root.path, existing)
        val vm = SubsystemGeneratorViewModel(root.path, League.FTC, documents = actual, loadOnStart = false)
        try {
            vm.newSubsystem()
            vm.registerHandAuthoredSubsystem()
            assertNull(vm.state.value.draft)
            assertTrue(vm.state.value.documents.isEmpty())
            vm.reloadAsync().join()
            assertTrue(vm.state.value.projectLoaded)
            assertEquals("new-subsystem", vm.state.value.selectedDocumentId)
            vm.newSubsystem()
            assertEquals("new-subsystem-2", vm.state.value.selectedDocumentId)
            assertEquals("ExistingSubsystem", vm.state.value.documents.first().kotlinTypeName)
        } finally { vm.close(); root.deleteRecursively() }
    }

}
