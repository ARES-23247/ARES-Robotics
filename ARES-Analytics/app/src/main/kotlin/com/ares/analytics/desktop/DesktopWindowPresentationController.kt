package com.ares.analytics.desktop

import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicBoolean

internal const val WINDOW_HEALTH_CHECK_MS = 1_000L
internal const val WINDOW_INITIAL_PRESENTATION_DELAY_MS = 2_000L
internal const val WINDOW_STARTUP_TOPMOST_MS = 2_500L
internal const val WINDOW_TOPMOST_SETTLEMENT_CHECK_MS = 100L
internal const val WINDOW_TOPMOST_SETTLEMENT_LIMIT = 20
internal const val STARTUP_CAPTURE_ENV = "ARES_ANALYTICS_STARTUP_CAPTURE"
internal const val STARTUP_CAPTURE_CLOSE_ENV = "ARES_ANALYTICS_STARTUP_CAPTURE_CLOSE"
internal const val TEST_CAPTURE_DIRECTORY_ENV = "ARES_ANALYTICS_TEST_CAPTURE_DIR"
internal const val TEST_CONTROL_PORT_ENV = "ARES_ANALYTICS_TEST_CONTROL_PORT"

/** Pure decisions of the presentation policy, separated from scheduling for unit testing. */
internal object DesktopPresentationPolicy {
    /**
     * Windows may deny a foreground request from a Gradle-launched child process, so Compose
     * starts the window topmost; the topmost state is released only after the bounded startup
     * interval, never before.
     */
    fun shouldReleaseStartupTopmost(elapsedMs: Long): Boolean = elapsedMs >= WINDOW_STARTUP_TOPMOST_MS

    /** Settled requires the Compose-owned topmost state to have actually returned to false. */
    fun isSettled(alwaysOnTop: Boolean): Boolean = !alwaysOnTop

    fun settlementExceeded(checks: Int): Boolean = checks >= WINDOW_TOPMOST_SETTLEMENT_LIMIT

    fun captureRequested(envValue: String?): Boolean = !envValue.orEmpty().trim().isEmpty()

    fun closeAfterCaptureRequested(envValue: String?): Boolean = envValue.toBoolean()
}

/**
 * Owns the desktop window's startup presentation, topmost settlement, health recovery, and
 * the opt-in same-process capture/WM_CLOSE verifier. Compose remains the sole owner of
 * visibility and always-on-top state: this controller only observes the native window,
 * calls the AWT-level toFront()/requestFocus(), and drives Compose state via
 * [onStartupAlwaysOnTopChange].
 *
 * The [DesktopStartupMachine] loss budget is the single authoritative recovery counter for
 * both failure kinds this controller handles — native window loss and topmost-settlement
 * timeout — and it resets only when the window is fully settled again.
 *
 * All work runs on the AWT event thread (scheduler callbacks + window events). The
 * scheduler and window ports are injectable so controller sequencing is testable against
 * a virtual clock without native windows.
 */
internal class DesktopWindowPresentationController(
    private val windowPort: DesktopWindowPort,
    private val machine: DesktopStartupMachine,
    private val isShutdownStarted: () -> Boolean,
    private val onStartupAlwaysOnTopChange: (Boolean) -> Unit,
    private val onFocusLost: () -> Unit,
    private val onUnrecoverableWindowLoss: (reason: String) -> Nothing,
    private val scheduler: DesktopScheduler = SwingDesktopScheduler,
) {
    private val initialPresentationScheduled = AtomicBoolean(false)
    private var topmostSettlementChecks = 0

    private var initialPresentationFallbackTask: DesktopScheduledTask? = null
    private var topmostReleaseTask: DesktopScheduledTask? = null
    private var topmostSettlementTask: DesktopScheduledTask? = null
    private var healthTask: DesktopScheduledTask? = null

    private val focusListener = object : WindowAdapter() {
        override fun windowGainedFocus(event: WindowEvent?) {
            println("[ARES-Analytics] Desktop window focused")
        }

        override fun windowLostFocus(event: WindowEvent?) {
            onFocusLost()
        }
    }

    private val lifecycleListener = object : WindowAdapter() {
        override fun windowOpened(event: WindowEvent?) {
            onWindowOpened()
        }

        override fun windowClosing(event: WindowEvent?) {
            println("[ARES-Analytics] Desktop window closing")
        }

        override fun windowClosed(event: WindowEvent?) {
            onWindowClosed()
        }
    }

    private val visibilityListener = object : ComponentAdapter() {
        override fun componentShown(event: ComponentEvent?) {
            println("[ARES-Analytics] Desktop window shown")
        }

        override fun componentHidden(event: ComponentEvent?) {
            println("[ARES-Analytics] Desktop window hidden")
        }
    }

    fun attach() {
        windowPort.attachListeners(focusListener, lifecycleListener, visibilityListener)
        initialPresentationFallbackTask = scheduler.schedule(WINDOW_INITIAL_PRESENTATION_DELAY_MS, 0L) {
            scheduleInitialPresentation("startup fallback")
        }
    }

    fun detach(expectedShutdown: Boolean) {
        println(
            "[ARES-Analytics] Desktop window composition disposed: " +
                "${windowPort.disposalDiagnostics()}, shutdownStarted=$expectedShutdown"
        )
        windowPort.detachListeners(focusListener, lifecycleListener, visibilityListener)
        initialPresentationFallbackTask?.cancel()
        topmostReleaseTask?.cancel()
        stopTopmostSettlementChecks()
        healthTask?.cancel()
        if (!expectedShutdown) {
            onUnrecoverableWindowLoss(
                "Desktop window disappeared without a shutdown request; " +
                    "terminating so the next launch can acquire app.lock."
            )
        }
    }

    /** windowOpened observation; safe to receive late or duplicated. */
    internal fun onWindowOpened() {
        println("[ARES-Analytics] Desktop window opened")
        machine.observeOpened()
        scheduleInitialPresentation("windowOpened")
    }

    internal fun onWindowClosed() {
        println("[ARES-Analytics] Desktop window closed")
        machine.markClosed()
    }

    private fun scheduleInitialPresentation(reason: String) {
        if (!initialPresentationScheduled.compareAndSet(false, true)) return

        // Let the lifecycle event finish before touching focus/Z-order. windowOpened proves
        // that Compose's real AWT peer reached its opened lifecycle instead of exposing a
        // transient HWND that a generic startup invokeLater could validate too early. The
        // bounded fallback covers listeners attached after the opened event fired.
        scheduler.invokeLater {
            if (isShutdownStarted() || machine.isShuttingDown) return@invokeLater

            if (windowPort.presentWindow()) {
                // Tolerates a missed windowOpened: observation walks CREATING -> OPENED ->
                // PRESENTED through the transition table instead of assuming OPENED happened.
                machine.observePresented()
                println(
                    "[ARES-Analytics] Desktop window presented after $reason: " +
                        "${windowPort.windowDiagnostics()}, nativeVisible=true, " +
                        "hwnd=${windowPort.nativeWindowHandle()}"
                )
            } else {
                System.err.println(
                    "[ARES-Analytics] Desktop window was not usable after $reason; " +
                        "the native recovery watchdog is active."
                )
            }
            if (topmostReleaseTask == null) {
                topmostReleaseTask = scheduler.schedule(WINDOW_STARTUP_TOPMOST_MS, 0L) {
                    releaseStartupTopmost()
                }
            }
            ensureHealthTaskStarted()
        }
    }

    /** Releases the Compose-owned startup topmost state, then verifies it actually settled. */
    private fun releaseStartupTopmost() {
        if (isShutdownStarted() || machine.isShuttingDown) return
        onStartupAlwaysOnTopChange(false)
        topmostSettlementChecks = 0
        startTopmostSettlementChecks()
    }

    private fun startTopmostSettlementChecks() {
        topmostSettlementTask?.cancel()
        topmostSettlementTask =
            scheduler.schedule(WINDOW_TOPMOST_SETTLEMENT_CHECK_MS, WINDOW_TOPMOST_SETTLEMENT_CHECK_MS) {
                checkTopmostSettlement()
            }
    }

    private fun stopTopmostSettlementChecks() {
        topmostSettlementTask?.cancel()
        topmostSettlementTask = null
    }

    /**
     * SETTLED means the Compose-owned topmost state actually returned to false. A topmost
     * release that never settles is a recovery condition, not a settled launch: each
     * bounded timeout round records a window loss, re-issues the release, and escalates to
     * termination when the machine's loss budget is exhausted. Capture and close never run
     * from the timeout path.
     */
    private fun checkTopmostSettlement() {
        if (isShutdownStarted() || machine.isShuttingDown) {
            stopTopmostSettlementChecks()
            return
        }
        topmostSettlementChecks++
        when {
            DesktopPresentationPolicy.isSettled(windowPort.isAlwaysOnTop()) -> {
                stopTopmostSettlementChecks()
                if (windowPort.isNativeWindowUsable()) {
                    onStartupSettled()
                } else {
                    System.err.println(
                        "[ARES-Analytics] Desktop window became unusable at settlement; " +
                            "the native recovery watchdog is active."
                    )
                    recoverMissingNativeWindow()
                }
            }
            DesktopPresentationPolicy.settlementExceeded(topmostSettlementChecks) -> {
                topmostSettlementChecks = 0
                val attemptPermitted = machine.recordWindowLoss()
                System.err.println(
                    "[ARES-Analytics] Desktop startup topmost release did not settle after " +
                        "$WINDOW_TOPMOST_SETTLEMENT_LIMIT checks (alwaysOnTop=true); " +
                        "recovery attempt ${machine.attemptsUsed}/${machine.maxAttempts}."
                )
                if (!attemptPermitted) {
                    onUnrecoverableWindowLoss(
                        "Desktop startup presentation never released always-on-top after " +
                            "${machine.maxAttempts} recovery attempts; " +
                            "terminating so the next launch can acquire app.lock."
                    )
                }
                // Compose-owned re-release; the repeating settlement check continues.
                onStartupAlwaysOnTopChange(false)
            }
        }
    }

    /** Runs only after alwaysOnTop returned to false and the exact native window is usable. */
    private fun onStartupSettled() {
        machine.observePresented()
        machine.transitionTo(DesktopStartupState.SETTLED)
        println(
            "[ARES-Analytics] Desktop startup presentation settled: " +
                windowPort.windowFocusDiagnostics()
        )
        val captureSucceeded = windowPort.attemptStartupCapture()
        windowPort.postCaptureCloseRequest(captureSucceeded)
    }

    private fun ensureHealthTaskStarted() {
        if (healthTask != null) return
        healthTask = scheduler.schedule(WINDOW_HEALTH_CHECK_MS, WINDOW_HEALTH_CHECK_MS) {
            checkNativeWindowHealth()
        }
    }

    private fun checkNativeWindowHealth() {
        when {
            isShutdownStarted() || machine.isShuttingDown -> healthTask?.cancel()
            !windowPort.isNativeWindowUsable() -> recoverMissingNativeWindow()
            machine.state == DesktopStartupState.WINDOW_LOST -> resumeRecoveredNativeWindow()
            else -> Unit
        }
    }

    /**
     * Records the loss first — [DesktopStartupMachine.recordWindowRecovered] is only legal
     * from WINDOW_LOST — then re-presents. Escalates to termination once the machine's
     * budget is exhausted; there is no separate controller-side failure counter.
     */
    private fun recoverMissingNativeWindow() {
        val attemptPermitted = machine.recordWindowLoss()
        System.err.println(
            "[ARES-Analytics] Native desktop window is missing; " +
                "recovery attempt ${machine.attemptsUsed}/${machine.maxAttempts}."
        )
        if (!attemptPermitted) {
            onUnrecoverableWindowLoss(
                "Native desktop window could not be recovered after " +
                    "${machine.maxAttempts} attempts; " +
                    "terminating so the next launch can acquire app.lock."
            )
        }
        if (windowPort.presentWindow()) {
            machine.recordWindowRecovered()
            println(
                "[ARES-Analytics] Native desktop window recovered: " +
                    "${windowPort.windowDiagnostics()}, hwnd=${windowPort.nativeWindowHandle()}"
            )
            resumeSettlementVerificationIfNeeded()
        }
    }

    /**
     * The window is natively usable again while marked lost — either it healed without a
     * controller re-presentation or a settlement retry is still pending. Resuming restores
     * the state the window was lost from; the loss budget only resets once the window is
     * fully settled again, so repeated settlement failures stay bounded.
     */
    private fun resumeRecoveredNativeWindow() {
        machine.recordWindowRecovered()
        println(
            "[ARES-Analytics] Native desktop window is usable again; " +
                "resuming ${machine.state}."
        )
        resumeSettlementVerificationIfNeeded()
    }

    /** A pre-SETTLED native recovery must re-enter the settlement verifier. */
    private fun resumeSettlementVerificationIfNeeded() {
        if (isShutdownStarted() || machine.isShuttingDown) return
        if (machine.state == DesktopStartupState.PRESENTED && !windowPort.isAlwaysOnTop()) {
            topmostSettlementChecks = 0
            startTopmostSettlementChecks()
        }
    }
}
