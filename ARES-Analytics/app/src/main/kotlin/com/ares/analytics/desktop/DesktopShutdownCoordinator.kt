package com.ares.analytics.desktop

import com.ares.analytics.di.ServiceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** Bounded window for graceful service disposal before the shutdown watchdog forces exit. */
internal const val SHUTDOWN_TIMEOUT_MS = 15_000L

/**
 * Owns the single shutdown path: idempotent user-initiated close, bounded service
 * disposal on IO, watchdog escalation to [Runtime.halt], and finally exitApplication.
 *
 * A blocking, non-cooperative teardown cannot be cancelled — disposal runs as its own job
 * and the coordinator stops waiting for it instead, then guarantees process exit so a hung
 * service can never leave an unclosable window that also holds the single-instance lock.
 */
internal class DesktopShutdownCoordinator(
    private val machine: DesktopStartupMachine,
    private val hardExitGraceMs: Long = 3_000L,
) {
    private val started = AtomicBoolean(false)

    val isShutdownStarted: Boolean get() = started.get()

    fun requestShutdown(
        scope: CoroutineScope,
        services: ServiceRegistry,
        exitApplication: () -> Unit,
    ) {
        if (!started.compareAndSet(false, true)) return
        machine.beginClosing()
        scope.launch {
            val disposeJob = launch(Dispatchers.IO) {
                try {
                    services.disposeAndJoin()
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
            val finished = withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) { disposeJob.join() }
            if (finished == null) {
                System.err.println(
                    "Shutdown watchdog: disposal did not finish in $SHUTDOWN_TIMEOUT_MS ms; forcing application exit."
                )
                watchHardExit(hardExitGraceMs)
            }
            exitApplication()
        }
    }

    /**
     * Last-resort exit guarantee: exitApplication ends the Compose loop but the JVM only
     * terminates once all non-daemon threads finish — a thread stuck inside a hung service
     * leaves a zombie process holding the single-instance lock. This daemon thread escalates
     * to [Runtime.halt] after a grace period; halt skips shutdown hooks, which is acceptable
     * because the disposal path already had its chance and the OS lock releases on exit.
     */
    private fun watchHardExit(graceMs: Long) {
        thread(isDaemon = true, name = "shutdown-halt-watchdog") {
            Thread.sleep(graceMs)
            System.err.println("Shutdown watchdog: JVM still alive after exitApplication; halting.")
            Runtime.getRuntime().halt(1)
        }
    }

    companion object {
        /** Unexpected loss of the only usable window must not leave a lock-owning zombie JVM. */
        fun terminateForUnusableWindow(reason: String): Nothing {
            System.err.println("[ARES-Analytics] $reason")
            exitProcess(1)
        }
    }
}
