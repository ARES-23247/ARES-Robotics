package com.ares.analytics.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.awaitApplication
import com.ares.analytics.runDesktopServiceBootstrap
import com.ares.analytics.di.ServiceRegistry
import com.ares.analytics.service.DatabaseService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class DesktopDatabaseShutdownRaceTest {
    @Test
    fun `window work finishes cancellation before its database is closed`() = runBlocking {
        val directory = Files.createTempDirectory("ares-ui-db-shutdown-")
        val database = DatabaseService(directory.resolve("telemetry.duckdb").toString())
        val services = registryWithDatabase(database)
        val cleanupFailure = AtomicReference<Throwable?>()
        val entered = CompletableDeferred<Unit>()
        val coordinator = DesktopShutdownCoordinator(DesktopStartupMachine())
        var shutdownFinished = false
        try {
            withTimeout(10_000) {
                awaitApplication {
                    LaunchedEffect(Unit) {
                        entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            // Inspect the real repository during cancellation cleanup, not a mock
                            // connection flag. Its owner must keep dependencies alive until we finish.
                            withContext(NonCancellable) {
                                cleanupFailure.set(runCatching {
                                    database.getSessionsForWorkspace("23247", "2026", "Lightbot")
                                }.exceptionOrNull())
                            }
                        }
                    }
                    LaunchedEffect(Unit) {
                        entered.await()
                        coordinator.requestShutdown(services, ::exitApplication)
                    }
                }
            }
            shutdownFinished = coordinator.finishShutdown(services)
            assertTrue(shutdownFinished)
            assertNull(cleanupFailure.get(), "Window-owned work observed a closed database during shutdown")
        } finally {
            if (!shutdownFinished) coordinator.finishShutdown(services)
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `close during blocking bootstrap joins its IO before disposing the database`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("ares-bootstrap-close-")
        val database = DatabaseService(directory.resolve("telemetry.duckdb").toString())
        val services = registryWithDatabase(database)
        val coordinator = DesktopShutdownCoordinator(DesktopStartupMachine())
        val entered = CompletableDeferred<Unit>()
        val closeRequested = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val bootstrapFailure = AtomicReference<Throwable?>()
        try {
            withTimeout(10_000) {
                val application = async(Dispatchers.Default) {
                    try {
                        awaitApplication {
                            LaunchedEffect(Unit) {
                                runDesktopServiceBootstrap {
                                    entered.complete(Unit)
                                    check(release.await(5, TimeUnit.SECONDS))
                                    bootstrapFailure.set(runCatching {
                                        runBlocking { database.getSessionsForWorkspace("23247", "2026", "Lightbot") }
                                    }.exceptionOrNull())
                                    finished.set(true)
                                }
                            }
                            LaunchedEffect(Unit) {
                                entered.await()
                                coordinator.requestShutdown(services, ::exitApplication)
                                closeRequested.complete(Unit)
                            }
                        }
                    } finally {
                        check(coordinator.finishShutdown(services))
                    }
                }
                try {
                    closeRequested.await()
                    assertFalse(application.isCompleted, "Host must wait for the in-progress bootstrap")
                    assertFalse(finished.get())
                    database.getSessionsForWorkspace("23247", "2026", "Lightbot")
                } finally { release.countDown() }
                application.await()
                assertTrue(finished.get())
                assertNull(bootstrapFailure.get())
                assertFailsWith<SQLException> { database.getSessions() }
            }
        } finally {
            release.countDown()
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun registryWithDatabase(database: DatabaseService): ServiceRegistry = ServiceRegistry().also { services ->
        ServiceRegistry::class.java.getDeclaredField("databaseService\$delegate").apply {
            isAccessible = true
            set(services, lazyOf(database))
        }
        // Prevent unrelated constructors and client/native-device startup in the test registry.
        ServiceRegistry::class.java.declaredFields.filter { Lazy::class.java.isAssignableFrom(it.type) }
            .forEach { field ->
                field.isAccessible = true
                if (!(field.get(services) as Lazy<*>).isInitialized()) {
                    field.set(services, lazy { error("Unused service initialized: ${field.name}") })
                }
            }
    }
}
