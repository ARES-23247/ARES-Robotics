package com.ares.analytics

import java.awt.EventQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopCoroutineDispatcherTest {
    @Test
    fun `main dispatcher targets the Swing event thread`() = runBlocking {
        withContext(Dispatchers.Main) {
            assertTrue(EventQueue.isDispatchThread())
        }
    }
}
