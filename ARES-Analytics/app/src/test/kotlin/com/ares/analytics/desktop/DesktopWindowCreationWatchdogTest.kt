package com.ares.analytics.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopWindowCreationWatchdogTest {
    @Test
    fun `windowless creating state terminates at the bounded deadline`() {
        val machine = DesktopStartupMachine()
        val scheduler = RecordingDeadlineScheduler()
        val reasons = mutableListOf<String>()
        val watchdog = DesktopWindowCreationWatchdog(machine, reasons::add, scheduler)

        watchdog.start()
        scheduler.fire()

        assertEquals(1, reasons.size)
        assertTrue(reasons.single().contains("20 seconds"))
        assertTrue(reasons.single().contains("windowless process"))
    }

    @Test
    fun `opened window makes the creation deadline a no-op`() {
        val machine = DesktopStartupMachine().apply { observeOpened() }
        val scheduler = RecordingDeadlineScheduler()
        val reasons = mutableListOf<String>()
        val watchdog = DesktopWindowCreationWatchdog(machine, reasons::add, scheduler)

        watchdog.start()
        scheduler.fire()

        assertTrue(reasons.isEmpty())
    }

    @Test
    fun `disposed application cancels the deadline`() {
        val machine = DesktopStartupMachine()
        val scheduler = RecordingDeadlineScheduler()
        val reasons = mutableListOf<String>()
        val watchdog = DesktopWindowCreationWatchdog(machine, reasons::add, scheduler)

        watchdog.start()
        watchdog.stop()
        scheduler.fire()

        assertTrue(reasons.isEmpty())
    }
}

private class RecordingDeadlineScheduler : DesktopDeadlineScheduler {
    private var cancelled = false
    private var action: (() -> Unit)? = null

    override fun schedule(delayMs: Long, action: () -> Unit): DesktopScheduledTask {
        assertEquals(WINDOW_CREATION_TIMEOUT_MS, delayMs)
        this.action = action
        return DesktopScheduledTask { cancelled = true }
    }

    fun fire() {
        if (!cancelled) action?.invoke()
    }
}
