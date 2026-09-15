package com.ares.analytics.ui

import com.ares.analytics.ui.components.core.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class AresFileChooserAsyncTest {
    @Test fun slowListingDoesNotBlockUiAndCannotReplaceNewerNavigation() = runBlocking {
        val root = createTempDirectory("ares-chooser-async").toFile().canonicalFile
        val slow = File(root, "slow").apply { mkdir() }
        val fast = File(root, "fast").apply { mkdir() }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var state: AresFileChooserState? = null
        try {
            withTimeout(2000) {
                withContext(Dispatchers.Main) {
                    state = AresFileChooserState(AresFileChooserMode.DIRECTORY, "Async", slow, null, null,
                        emptyList(), null, {}, {}, directoryReader = { directory ->
                            assertFalse(java.awt.EventQueue.isDispatchThread())
                            if (directory == slow) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                            readChooserDirectory(directory)
                        })
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            withContext(Dispatchers.Main) {
                val chooser = assertNotNull(state)
                assertTrue(chooser.loading)
                chooser.navigateTo(fast)
                chooser.awaitIdle()
                assertEquals(fast, chooser.currentDirectory)
                release.countDown()
                delay(100)
                assertEquals(fast, chooser.currentDirectory)
                assertFalse(chooser.loading)
                chooser.navigateTo(File(root, "missing"))
                chooser.awaitIdle()
                assertNotNull(chooser.listingError)
                assertTrue(chooser.entries.isEmpty())
            }
        } finally {
            release.countDown()
            withContext(Dispatchers.Main) { state?.close() }
            root.deleteRecursively()
        }
    }
}
