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
        val gateway = object : ProjectDocumentGateway by actual {
            override fun load(projectPath: String, targetPlatform: ControllerInputPlatform?): AresProjectDocumentSnapshot {
                assertFalse(java.awt.EventQueue.isDispatchThread())
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return actual.load(projectPath, targetPlatform)
            }
        }
        val vm = SubsystemGeneratorViewModel(root.path, League.FTC, documents = gateway, loadOnStart = false)
        try {
            val reload = withContext(Dispatchers.Main) { vm.reloadAsync() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            vm.newSubsystem()
            vm.edit { it.copy(displayName = "Keep this draft") }
            val draft = vm.state.value.draft
            release.countDown()
            reload.join()
            assertEquals(draft, vm.state.value.draft)
            assertTrue(vm.state.value.dirty)
        } finally { release.countDown(); vm.close(); root.deleteRecursively() }
    }
}
