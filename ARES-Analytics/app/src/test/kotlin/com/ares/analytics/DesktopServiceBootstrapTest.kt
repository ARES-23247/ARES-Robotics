package com.ares.analytics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopServiceBootstrapTest {
    @Test
    fun `database bootstrap leaves the AWT event thread`() = runBlocking {
        withContext(Dispatchers.Main) {
            assertTrue(EventQueue.isDispatchThread(), "test must begin on the desktop UI thread")
            val initializerRanOnAwt = AtomicBoolean(true)

            runDesktopServiceBootstrap {
                initializerRanOnAwt.set(EventQueue.isDispatchThread())
            }

            assertFalse(initializerRanOnAwt.get(), "database initialization must not block Compose startup")
            assertTrue(EventQueue.isDispatchThread(), "continuation must return to the desktop UI thread")
        }
    }
}
