package com.ares.analytics.desktop

/** A scheduled task that can still be cancelled before or between firings. */
internal fun interface DesktopScheduledTask {
    fun cancel()
}

/**
 * Event-loop abstraction for the desktop lifecycle. All controller work runs on the AWT
 * event thread; this interface exists so controller behavior can be tested against a
 * virtual clock instead of real Swing timers.
 */
internal interface DesktopScheduler {
    /**
     * Schedules [action] after [delayMs]. With [periodMs] greater than zero the task
     * repeats at that period (first firing after [delayMs]); otherwise it fires once.
     */
    fun schedule(delayMs: Long, periodMs: Long, action: () -> Unit): DesktopScheduledTask

    /** Runs [action] on the event loop after the current callback/event completes. */
    fun invokeLater(action: () -> Unit)
}

/** Production scheduler backed by Swing timers and the AWT event queue. */
internal object SwingDesktopScheduler : DesktopScheduler {
    override fun schedule(delayMs: Long, periodMs: Long, action: () -> Unit): DesktopScheduledTask {
        val timer = javax.swing.Timer(delayMs.toInt()) { action() }.apply {
            initialDelay = delayMs.toInt()
            if (periodMs > 0L) {
                isRepeats = true
                delay = periodMs.toInt()
            } else {
                isRepeats = false
            }
        }
        timer.start()
        return DesktopScheduledTask { timer.stop() }
    }

    override fun invokeLater(action: () -> Unit) {
        java.awt.EventQueue.invokeLater(action)
    }
}
