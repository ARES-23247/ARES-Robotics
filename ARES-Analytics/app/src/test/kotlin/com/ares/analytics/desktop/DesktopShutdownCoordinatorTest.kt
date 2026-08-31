package com.ares.analytics.desktop

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopShutdownCoordinatorTest {
    @Test
    fun `emergency window-loss cleanup waits for service disposal`() = runBlocking {
        var disposed = false

        val finished = awaitBoundedEmergencyDisposal(timeoutMs = 1_000L) {
            delay(10L)
            disposed = true
        }

        assertTrue(finished)
        assertTrue(disposed)
    }

    @Test
    fun `emergency window-loss cleanup remains bounded`() = runBlocking {
        val finished = awaitBoundedEmergencyDisposal(timeoutMs = 20L) {
            delay(5_000L)
        }

        assertFalse(finished)
    }
}
