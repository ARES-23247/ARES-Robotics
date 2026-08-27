package com.ares.analytics.desktop

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Explicit lifecycle of the single Compose desktop window, replacing the implicit
 * timer/boolean lattice the startup code previously used.
 *
 * CREATING → OPENED → PRESENTED → SETTLED → CLOSING → CLOSED is the happy path.
 * WINDOW_LOST is the only error state and carries a concrete policy: the window may be
 * lost up to [maxRecoveryAttempts] times since the last fully settled state; beyond that
 * the process must terminate so the single-instance lock cannot be orphaned by a
 * windowless JVM. The budget deliberately does NOT reset on an intermediate recovery —
 * only reaching SETTLED again is full health — so a presentation defect that repeatedly
 * loses the window can never loop forever.
 */
enum class DesktopStartupState {
    CREATING,
    OPENED,
    PRESENTED,
    SETTLED,
    WINDOW_LOST,
    CLOSING,
    CLOSED,
}

/**
 * Single-threaded-by-design state machine (all transitions happen on the AWT event thread).
 * Illegal transitions fail loudly: they indicate a lifecycle bug that silent acceptance
 * would turn into another invisible-startup incident.
 */
class DesktopStartupMachine(private val maxRecoveryAttempts: Int = 3) {
    private val stateRef = AtomicReference(DesktopStartupState.CREATING)
    private val recoveryAttempts = AtomicInteger(0)
    private val stateBeforeLoss = AtomicReference<DesktopStartupState?>(null)

    val state: DesktopStartupState get() = stateRef.get()
    val isTerminal: Boolean get() = state == DesktopStartupState.CLOSED
    val isShuttingDown: Boolean
        get() = state == DesktopStartupState.CLOSING || state == DesktopStartupState.CLOSED
    val attemptsUsed: Int get() = recoveryAttempts.get()
    val maxAttempts: Int get() = maxRecoveryAttempts

    /** True while the window is expected to exist and be usable. */
    val windowExpected: Boolean
        get() = state == DesktopStartupState.OPENED ||
            state == DesktopStartupState.PRESENTED ||
            state == DesktopStartupState.SETTLED

    fun transitionTo(next: DesktopStartupState) {
        val current = stateRef.get()
        require(isLegalTransition(current, next)) {
            "Illegal desktop startup transition: $current -> $next"
        }
        setState(next)
    }

    /**
     * Records one failed presentation of a window that should exist. Returns true when a
     * recovery attempt is still permitted; false means the policy requires termination.
     * Shutdown states are left untouched: [beginClosing] owns the machine from that point.
     */
    fun recordWindowLoss(): Boolean {
        val current = stateRef.get()
        if (current == DesktopStartupState.CLOSING || current == DesktopStartupState.CLOSED) return true
        if (current == DesktopStartupState.PRESENTED || current == DesktopStartupState.SETTLED) {
            stateBeforeLoss.set(current)
        }
        val used = recoveryAttempts.incrementAndGet()
        stateRef.set(DesktopStartupState.WINDOW_LOST)
        return used <= maxRecoveryAttempts
    }

    /**
     * Recovery succeeded: resume from WINDOW_LOST. Without an argument the window resumes
     * the state it was in when lost. Reaching SETTLED through this call resets the loss
     * budget; an intermediate recovery to PRESENTED keeps it accountable.
     */
    fun recordWindowRecovered(
        recovered: DesktopStartupState = stateBeforeLoss.get() ?: DesktopStartupState.PRESENTED,
    ) {
        val current = stateRef.get()
        require(
            current == DesktopStartupState.WINDOW_LOST &&
                (recovered == DesktopStartupState.PRESENTED || recovered == DesktopStartupState.SETTLED),
        ) {
            "Illegal desktop window recovery: $current -> $recovered"
        }
        setState(recovered)
        stateBeforeLoss.set(null)
    }

    /** CLOSING is reachable from every non-terminal state: shutdown may start at any point. */
    fun beginClosing() {
        val current = stateRef.get()
        if (current == DesktopStartupState.CLOSING || current == DesktopStartupState.CLOSED) return
        setState(DesktopStartupState.CLOSING)
    }

    fun markClosed() {
        setState(DesktopStartupState.CLOSED)
    }

    /**
     * Idempotent OPENED observation. Duplicate or late window-opened AWT events must not
     * throw; only the first observation from CREATING performs the transition.
     */
    fun observeOpened() {
        when (stateRef.get()) {
            DesktopStartupState.CREATING -> transitionTo(DesktopStartupState.OPENED)
            else -> Unit
        }
    }

    /**
     * Idempotent PRESENTED observation. A presentation that succeeds without a prior
     * opened observation (the startup fallback after a missed windowOpened) walks through
     * OPENED so the transition table still holds; duplicate observations and observations
     * after shutdown are no-ops.
     */
    fun observePresented() {
        when (stateRef.get()) {
            DesktopStartupState.CREATING -> {
                transitionTo(DesktopStartupState.OPENED)
                transitionTo(DesktopStartupState.PRESENTED)
            }
            DesktopStartupState.OPENED, DesktopStartupState.WINDOW_LOST ->
                transitionTo(DesktopStartupState.PRESENTED)
            else -> Unit
        }
    }

    private fun setState(next: DesktopStartupState) {
        stateRef.set(next)
        if (next == DesktopStartupState.SETTLED) recoveryAttempts.set(0)
    }

    companion object {
        fun isLegalTransition(current: DesktopStartupState, next: DesktopStartupState): Boolean = when (current) {
            DesktopStartupState.CREATING -> next == DesktopStartupState.OPENED || next == DesktopStartupState.CLOSING
            DesktopStartupState.OPENED -> next == DesktopStartupState.PRESENTED || next == DesktopStartupState.CLOSING
            DesktopStartupState.PRESENTED -> next == DesktopStartupState.SETTLED ||
                next == DesktopStartupState.WINDOW_LOST ||
                next == DesktopStartupState.CLOSING
            DesktopStartupState.SETTLED -> next == DesktopStartupState.WINDOW_LOST || next == DesktopStartupState.CLOSING
            DesktopStartupState.WINDOW_LOST ->
                next == DesktopStartupState.PRESENTED || next == DesktopStartupState.SETTLED || next == DesktopStartupState.CLOSING
            DesktopStartupState.CLOSING -> next == DesktopStartupState.CLOSED
            DesktopStartupState.CLOSED -> false
        }
    }
}
