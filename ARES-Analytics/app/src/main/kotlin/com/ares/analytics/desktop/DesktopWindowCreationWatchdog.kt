package com.ares.analytics.desktop

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal const val WINDOW_CREATION_TIMEOUT_MS = 20_000L

internal fun interface DesktopDeadlineScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): DesktopScheduledTask
}

/**
 * Process-level deadline for the interval before Compose creates its sole native window.
 *
 * The presentation controller takes ownership after OPENED. This earlier guard deliberately
 * uses an independent daemon scheduler: if Compose leaves the AWT loop alive without ever
 * producing a peer, the process exits instead of invisibly retaining the single-instance lock.
 */
internal class DesktopWindowCreationWatchdog(
    private val machine: DesktopStartupMachine,
    private val onUnrecoverableWindow: (String) -> Unit,
    private val scheduler: DesktopDeadlineScheduler = DaemonDesktopDeadlineScheduler,
) {
    private val task = AtomicReference<DesktopScheduledTask?>(null)

    fun start() {
        check(task.get() == null) { "Desktop window creation watchdog is already running." }
        task.set(
            scheduler.schedule(WINDOW_CREATION_TIMEOUT_MS) {
                if (machine.state == DesktopStartupState.CREATING) {
                    onUnrecoverableWindow(
                        "Compose did not create the ARES desktop window within " +
                            "${WINDOW_CREATION_TIMEOUT_MS / 1_000} seconds; terminating the windowless process.",
                    )
                }
            },
        )
    }

    fun stop() {
        task.getAndSet(null)?.cancel()
    }
}

private object DaemonDesktopDeadlineScheduler : DesktopDeadlineScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor(
        ThreadFactory { runnable ->
            Thread(runnable, "desktop-window-creation-watchdog").apply { isDaemon = true }
        },
    )

    override fun schedule(delayMs: Long, action: () -> Unit): DesktopScheduledTask {
        val future = executor.schedule(action, delayMs, TimeUnit.MILLISECONDS)
        return DesktopScheduledTask { future.cancel(false) }
    }
}
