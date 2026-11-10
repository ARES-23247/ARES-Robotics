package com.ares.analytics.desktop

import com.ares.analytics.di.ServiceRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.IOException

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

    @Test
    fun `a teardown failure is not reported as successful disposal`() = runBlocking {
        assertFalse(awaitBoundedEmergencyDisposal(1_000) { throw IOException("storage flush failed") })
    }

    @Test
    fun `the watchdog also bounds a stalled application cancellation`() {
        val timeout = CountDownLatch(1)
        val services = ServiceRegistry()
        val coordinator = DesktopShutdownCoordinator(
            DesktopStartupMachine(), hardExitGraceMs = 20, shutdownTimeoutMs = 50,
            forceExit = { timeout.countDown() },
        )
        try {
            coordinator.requestShutdown(services, exitApplication = { /* simulate a stuck UI */ })
            assertTrue(timeout.await(2, TimeUnit.SECONDS), "watchdog must cover UI cancellation before service disposal")
        } finally {
            coordinator.finishShutdown(services)
        }
    }
}
