package com.ares.analytics.desktop

import java.awt.event.ComponentListener
import java.awt.event.WindowFocusListener
import java.awt.event.WindowListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Sentinel thrown by the test termination callback; mirrors the Nothing contract. */
private class UnrecoverableWindowLoss(message: String) : RuntimeException(message)

/** Virtual-clock scheduler: fires due tasks in order inside [advanceTime], inline invokeLater. */
private class ManualScheduler : DesktopScheduler {
    private class Task(
        val periodMs: Long,
        val action: () -> Unit,
        var dueAt: Long,
        val seq: Long,
        var cancelled: Boolean = false,
    ) : Comparable<Task> {
        // Explicit member access: selector-style compareValuesBy captured the outer receiver
        // and degenerated the priority queue to insertion order.
        override fun compareTo(other: Task): Int {
            val byDueAt = dueAt.compareTo(other.dueAt)
            return if (byDueAt != 0) byDueAt else seq.compareTo(other.seq)
        }
    }

    private val tasks = java.util.PriorityQueue<Task>()
    private var seq = 0L
    private var nowMs = 0L

    override fun invokeLater(action: () -> Unit) = action()

    override fun schedule(delayMs: Long, periodMs: Long, action: () -> Unit): DesktopScheduledTask {
        val task = Task(periodMs, action, nowMs + delayMs, seq++)
        tasks.add(task)
        return DesktopScheduledTask { task.cancelled = true }
    }

    fun advanceTime(deltaMs: Long) {
        val target = nowMs + deltaMs
        while (true) {
            val next = tasks.peek() ?: break
            if (next.cancelled) {
                tasks.poll()
                continue
            }
            if (next.dueAt > target) break
            tasks.poll()
            nowMs = next.dueAt
            next.action()
            if (next.periodMs > 0L && !next.cancelled) {
                next.dueAt += next.periodMs
                tasks.add(next)
            }
        }
        nowMs = target
    }
}

private class FakeWindowPort : DesktopWindowPort {
    var usable = true
    var presentSucceeds = true
    var alwaysOnTop = true
    var presentCalls = 0
        private set
    var captureCalls = 0
        private set
    var closeRequests = 0
        private set

    override fun attachListeners(
        focusListener: WindowFocusListener,
        lifecycleListener: WindowListener,
        visibilityListener: ComponentListener,
    ) = Unit

    override fun detachListeners(
        focusListener: WindowFocusListener,
        lifecycleListener: WindowListener,
        visibilityListener: ComponentListener,
    ) = Unit

    override fun disposalDiagnostics(): String =
        "displayable=false, visible=false, showing=false"

    override fun isNativeWindowUsable(): Boolean = usable

    override fun presentWindow(): Boolean {
        presentCalls++
        if (presentSucceeds) usable = true
        return presentSucceeds
    }

    override fun isAlwaysOnTop(): Boolean = alwaysOnTop

    override fun nativeWindowHandle(): Long? = 4242L

    override fun windowDiagnostics(): String = "size=1440x900, location=560x246, showing=true"

    override fun windowFocusDiagnostics(): String =
        "alwaysOnTop=$alwaysOnTop, focused=true, active=true, showing=true"

    override fun attemptStartupCapture(): Boolean {
        captureCalls++
        return true
    }

    override fun postCaptureCloseRequest(captureSucceeded: Boolean) {
        if (captureSucceeded) closeRequests++
    }
}

private class ControllerHarness {
    val machine = DesktopStartupMachine()
    val scheduler = ManualScheduler()
    val port = FakeWindowPort()
    val terminationReasons = mutableListOf<String>()
    var releaseInvocations = 0
    var honorTopmostRelease = true
    var shutdownStarted = false

    val controller = DesktopWindowPresentationController(
        windowPort = port,
        machine = machine,
        isShutdownStarted = { shutdownStarted },
        onStartupAlwaysOnTopChange = { value ->
            releaseInvocations++
            if (honorTopmostRelease) port.alwaysOnTop = value
        },
        onFocusLost = {},
        onUnrecoverableWindowLoss = { reason ->
            terminationReasons.add(reason)
            throw UnrecoverableWindowLoss(reason)
        },
        scheduler = scheduler,
    )

    /** attach -> opened -> presented -> topmost release -> settled, with one capture. */
    fun startAndSettle() {
        controller.attach()
        controller.onWindowOpened()
        scheduler.advanceTime(WINDOW_STARTUP_TOPMOST_MS)
        scheduler.advanceTime(WINDOW_TOPMOST_SETTLEMENT_CHECK_MS)
        check(machine.state == DesktopStartupState.SETTLED) {
            "harness expected SETTLED, got ${machine.state}"
        }
    }
}

class DesktopWindowPresentationControllerTest {
    @Test
    fun `normal startup walks creating through settled and runs the capture verifier`() {
        val harness = ControllerHarness()
        harness.controller.attach()
        assertEquals(DesktopStartupState.CREATING, harness.machine.state)

        harness.controller.onWindowOpened()
        // invokeLater runs inline under the manual scheduler, so the window passes through
        // OPENED to PRESENTED within the onWindowOpened call; only PRESENTED is observable.
        assertEquals(DesktopStartupState.PRESENTED, harness.machine.state)

        harness.scheduler.advanceTime(WINDOW_STARTUP_TOPMOST_MS)
        harness.scheduler.advanceTime(WINDOW_TOPMOST_SETTLEMENT_CHECK_MS)

        assertEquals(DesktopStartupState.SETTLED, harness.machine.state)
        assertEquals(false, harness.port.alwaysOnTop, "alwaysOnTop must be false before SETTLED")
        assertEquals(0, harness.machine.attemptsUsed)
        assertEquals(1, harness.releaseInvocations)
        assertEquals(1, harness.port.captureCalls)
        assertEquals(1, harness.port.closeRequests)
    }

    @Test
    fun `missed windowOpened falls back legally and late duplicates do not crash`() {
        val harness = ControllerHarness()
        harness.controller.attach()

        // No windowOpened event: the bounded startup fallback presents instead.
        harness.scheduler.advanceTime(WINDOW_INITIAL_PRESENTATION_DELAY_MS)
        assertEquals(
            DesktopStartupState.PRESENTED,
            harness.machine.state,
            "fallback must walk CREATING -> OPENED -> PRESENTED through the transition table",
        )

        // Late and duplicated opened events after the fallback presented must be no-ops.
        harness.controller.onWindowOpened()
        harness.controller.onWindowOpened()
        assertEquals(DesktopStartupState.PRESENTED, harness.machine.state)

        harness.scheduler.advanceTime(WINDOW_STARTUP_TOPMOST_MS)
        harness.scheduler.advanceTime(WINDOW_TOPMOST_SETTLEMENT_CHECK_MS)
        assertEquals(DesktopStartupState.SETTLED, harness.machine.state)
        assertEquals(1, harness.port.captureCalls)
    }

    @Test
    fun `failed fallback presentation is picked up by the native watchdog`() {
        val harness = ControllerHarness()
        harness.port.usable = false
        harness.port.presentSucceeds = false
        harness.controller.attach()

        harness.scheduler.advanceTime(WINDOW_INITIAL_PRESENTATION_DELAY_MS)
        assertEquals(DesktopStartupState.CREATING, harness.machine.state, "nothing presented yet")

        harness.scheduler.advanceTime(WINDOW_HEALTH_CHECK_MS)
        assertEquals(
            DesktopStartupState.WINDOW_LOST,
            harness.machine.state,
            "the health watchdog must record the loss even if nothing was ever presented",
        )
        assertEquals(1, harness.machine.attemptsUsed)
    }

    @Test
    fun `temporary native window loss records loss first then recovers`() {
        val harness = ControllerHarness()
        harness.startAndSettle()
        assertEquals(1, harness.port.presentCalls)

        harness.port.usable = false
        harness.port.presentSucceeds = true
        harness.scheduler.advanceTime(WINDOW_HEALTH_CHECK_MS)

        assertEquals(DesktopStartupState.SETTLED, harness.machine.state)
        assertEquals(0, harness.machine.attemptsUsed, "resuming SETTLED is full health and resets the budget")
        assertEquals(2, harness.port.presentCalls, "recovery must re-present the window")
    }

    @Test
    fun `self healed native loss resumes without a controller re-presentation`() {
        val harness = ControllerHarness()
        harness.startAndSettle()

        harness.port.usable = false
        harness.port.presentSucceeds = false
        harness.scheduler.advanceTime(WINDOW_HEALTH_CHECK_MS)
        assertEquals(DesktopStartupState.WINDOW_LOST, harness.machine.state)
        assertEquals(1, harness.machine.attemptsUsed)

        harness.port.usable = true
        harness.scheduler.advanceTime(WINDOW_HEALTH_CHECK_MS)
        assertEquals(DesktopStartupState.SETTLED, harness.machine.state)
        assertEquals(0, harness.machine.attemptsUsed)
    }

    @Test
    fun `exhausted recovery budget terminates instead of looping`() {
        val harness = ControllerHarness()
        harness.startAndSettle()
        harness.port.usable = false
        harness.port.presentSucceeds = false

        val failure = assertFailsWith<UnrecoverableWindowLoss> {
            repeat(4) { harness.scheduler.advanceTime(WINDOW_HEALTH_CHECK_MS) }
        }

        assertTrue(failure.message!!.contains("could not be recovered"))
        assertEquals(4, harness.machine.attemptsUsed)
        assertEquals(DesktopStartupState.WINDOW_LOST, harness.machine.state)
    }

    @Test
    fun `settlement timeout is a recovery condition and never settles or captures`() {
        val harness = ControllerHarness()
        harness.controller.attach()
        harness.controller.onWindowOpened()
        assertEquals(DesktopStartupState.PRESENTED, harness.machine.state)

        // Compose ignores every topmost release: settlement must fail, record a loss,
        // re-issue the release, and keep retrying — never SETTLED, never captured.
        harness.honorTopmostRelease = false
        harness.scheduler.advanceTime(WINDOW_INITIAL_PRESENTATION_DELAY_MS)

        // t=4500: the first 20 settlement checks elapsed at t=2500+2000.
        harness.scheduler.advanceTime(WINDOW_STARTUP_TOPMOST_MS + 20L * WINDOW_TOPMOST_SETTLEMENT_CHECK_MS - WINDOW_INITIAL_PRESENTATION_DELAY_MS)
        assertEquals(DesktopStartupState.WINDOW_LOST, harness.machine.state)
        assertEquals(1, harness.machine.attemptsUsed)
        assertEquals(2, harness.releaseInvocations, "the initial release plus one bounded re-release")
        assertEquals(0, harness.port.captureCalls)

        val failure = assertFailsWith<UnrecoverableWindowLoss> {
            harness.scheduler.advanceTime(6_000L)
        }
        assertTrue(failure.message!!.contains("never released always-on-top"))
        assertEquals(DesktopStartupState.WINDOW_LOST, harness.machine.state)
        assertEquals(0, harness.port.captureCalls, "a failed settlement must never be captured as success")
        assertEquals(4, harness.releaseInvocations, "the final exhausted round terminates before another release")
    }

    @Test
    fun `native loss at settlement recovers and re-enters settlement verification`() {
        val harness = ControllerHarness()
        harness.controller.attach()
        harness.controller.onWindowOpened()

        harness.scheduler.advanceTime(WINDOW_STARTUP_TOPMOST_MS)
        harness.port.usable = false
        harness.port.presentSucceeds = false
        harness.scheduler.advanceTime(WINDOW_TOPMOST_SETTLEMENT_CHECK_MS)

        assertEquals(DesktopStartupState.WINDOW_LOST, harness.machine.state)
        assertEquals(1, harness.machine.attemptsUsed)
        assertEquals(0, harness.port.captureCalls)

        harness.port.usable = true
        harness.scheduler.advanceTime(WINDOW_HEALTH_CHECK_MS)

        assertEquals(DesktopStartupState.SETTLED, harness.machine.state)
        assertEquals(0, harness.machine.attemptsUsed)
        assertEquals(1, harness.port.captureCalls)
        assertEquals(1, harness.port.closeRequests)
    }

    @Test
    fun `shutdown during startup cancels pending settlement without settling`() {
        val harness = ControllerHarness()
        harness.controller.attach()
        harness.controller.onWindowOpened()
        harness.scheduler.advanceTime(WINDOW_STARTUP_TOPMOST_MS) // release fired; settlement pending

        harness.shutdownStarted = true
        harness.machine.beginClosing()
        harness.scheduler.advanceTime(5_000L)

        assertEquals(DesktopStartupState.CLOSING, harness.machine.state)
        assertEquals(0, harness.port.captureCalls)
        assertEquals(1, harness.releaseInvocations)

        harness.controller.detach(expectedShutdown = true)
        assertTrue(harness.terminationReasons.isEmpty())
    }

    @Test
    fun `shutdown during recovery stops the watchdog without terminating`() {
        val harness = ControllerHarness()
        harness.startAndSettle()
        harness.port.usable = false
        harness.port.presentSucceeds = false
        harness.scheduler.advanceTime(WINDOW_HEALTH_CHECK_MS)
        assertEquals(DesktopStartupState.WINDOW_LOST, harness.machine.state)

        harness.shutdownStarted = true
        harness.machine.beginClosing()
        harness.scheduler.advanceTime(2 * WINDOW_HEALTH_CHECK_MS)

        assertEquals(DesktopStartupState.CLOSING, harness.machine.state)
        assertEquals(1, harness.machine.attemptsUsed, "no further loss accounting after shutdown began")
        assertTrue(harness.terminationReasons.isEmpty())

        harness.controller.detach(expectedShutdown = true)
        assertTrue(harness.terminationReasons.isEmpty())
    }

    @Test
    fun `unexpected composition disposal terminates so the lock cannot be orphaned`() {
        val harness = ControllerHarness()
        harness.controller.attach()
        harness.controller.onWindowOpened()

        val failure = assertFailsWith<UnrecoverableWindowLoss> {
            harness.controller.detach(expectedShutdown = false)
        }
        assertTrue(failure.message!!.contains("without a shutdown request"))
    }
}
