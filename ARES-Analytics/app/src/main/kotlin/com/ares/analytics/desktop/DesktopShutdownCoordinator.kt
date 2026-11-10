package com.ares.analytics.desktop

import com.ares.analytics.di.ServiceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** Bounded window for graceful service disposal before the shutdown watchdog forces exit. */
internal const val SHUTDOWN_TIMEOUT_MS = 15_000L

/**
 * Closes Compose first, then disposes its dependencies after the application loop has joined
 * window effects and bootstrap IO. Owned child processes stop immediately while cancellation
 * progresses. One watchdog bounds the entire close, including a non-cooperative UI/native task.
 *
 * The host must call [finishShutdown] after `application(exitProcessOnExit = false)` returns,
 * before exiting the process. No storage dependency is closed from the window's close callback.
 */
internal class DesktopShutdownCoordinator(
    private val machine: DesktopStartupMachine,
    private val hardExitGraceMs: Long = 3_000L,
    private val shutdownTimeoutMs: Long = SHUTDOWN_TIMEOUT_MS,
    private val forceExit: (Int) -> Unit = { Runtime.getRuntime().halt(it) },
) {
    private val started = AtomicBoolean(false)
    private val completed = CountDownLatch(1)
    private val processCleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var processCleanup: Job? = null
    private var startedAtNanos = 0L

    val isShutdownStarted: Boolean get() = started.get()

    fun requestShutdown(
        services: ServiceRegistry,
        exitApplication: () -> Unit,
    ) {
        if (!beginClosing(services)) return
        exitApplication()
    }

    private fun beginClosing(services: ServiceRegistry): Boolean {
        if (!started.compareAndSet(false, true)) return false
        startedAtNanos = System.nanoTime()
        machine.beginClosing()
        services.beginShutdown()
        watchHardExit(shutdownTimeoutMs + hardExitGraceMs)
        // Independent of Compose: a native query delaying UI cancellation must not keep an
        // owned simulator/build alive until the process watchdog terminates the desktop.
        processCleanup = processCleanupScope.launch {
            runCatching { services.stopOwnedProcessesAndJoin() }.onFailure(Throwable::printStackTrace)
        }
        return true
    }

    /** Called by the host only after all Compose-owned work has stopped. */
    fun finishShutdown(services: ServiceRegistry): Boolean {
        beginClosing(services)
        val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
        val remainingMs = (shutdownTimeoutMs - elapsedMs).coerceAtLeast(1L)
        val disposed = runBlocking {
            awaitBoundedEmergencyDisposal(remainingMs) {
                processCleanup?.join()
                services.disposeAndJoin()
            }
        }
        processCleanupScope.cancel()
        if (disposed) {
            completed.countDown()
        } else {
            System.err.println("Shutdown watchdog: service disposal failed or exceeded the graceful close deadline.")
        }
        return disposed
    }

    /**
     * Last-resort exit guarantee: exitApplication ends the Compose loop but the JVM only
     * terminates once all non-daemon threads finish — a thread stuck inside a hung service
     * leaves a zombie process holding the single-instance lock. This daemon thread escalates
     * to [Runtime.halt] after a grace period; halt skips shutdown hooks, which is acceptable
     * because the disposal path already had its chance and the OS lock releases on exit.
     */
    private fun watchHardExit(timeoutMs: Long) {
        thread(isDaemon = true, name = "shutdown-halt-watchdog") {
            if (!completed.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                System.err.println("Shutdown watchdog: UI cancellation or service disposal did not finish; halting.")
                forceExit(1)
            }
        }
    }

    companion object {
        /**
         * Unexpected loss of the only usable window must not leave either a lock-owning Studio JVM
         * or a simulator/build child process. Normal Compose shutdown is unavailable at this point,
         * so give the same service registry one bounded cleanup opportunity before exiting nonzero.
         */
        fun terminateForUnusableWindow(reason: String, services: ServiceRegistry): Nothing {
            System.err.println("[ARES-Analytics] $reason")
            services.beginShutdown()
            val disposed = runBlocking {
                awaitBoundedEmergencyDisposal(SHUTDOWN_TIMEOUT_MS) {
                    services.disposeAndJoin()
                }
            }
            if (!disposed) {
                System.err.println(
                    "[ARES-Analytics] Emergency service disposal exceeded $SHUTDOWN_TIMEOUT_MS ms; exiting.",
                )
            }
            exitProcess(1)
        }
    }
}

/** Testable emergency boundary used before the process exits after unrecoverable window loss. */
internal suspend fun awaitBoundedEmergencyDisposal(
    timeoutMs: Long,
    dispose: suspend () -> Unit,
): Boolean {
    val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val cleanupJob = cleanupScope.async {
        runCatching { dispose() }
            .onFailure { failure ->
                System.err.println("[ARES-Analytics] Service disposal failed: ${failure.message}")
            }
            .isSuccess
    }
    return try {
        withTimeoutOrNull(timeoutMs) {
            cleanupJob.await()
        } ?: false
    } finally {
        cleanupScope.cancel()
    }
}
