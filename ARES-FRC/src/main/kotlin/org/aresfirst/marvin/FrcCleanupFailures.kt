package org.aresfirst.marvin

/** Lifecycle-only failure collection; every cleanup step is attempted before rethrowing. */
internal class FrcCleanupFailures {
    private var failure: Throwable? = null

    inline fun attempt(action: () -> Unit) {
        try { action() } catch (error: Throwable) { capture(error) }
    }

    @PublishedApi
    internal fun capture(error: Throwable) {
        failure?.addSuppressed(error) ?: run { failure = error }
    }

    fun throwIfAny() { failure?.let { throw it } }
}

internal inline fun runFrcDisableCleanup(
    cancelControls: () -> Unit,
    stopAuto: () -> Unit,
    stopSysId: () -> Unit,
    inhibitMechanisms: () -> Unit,
    stopDriverRumble: () -> Unit,
    stopOperatorRumble: () -> Unit,
) {
    val failures = FrcCleanupFailures()
    failures.attempt(cancelControls)
    failures.attempt(stopAuto)
    failures.attempt(stopSysId)
    failures.attempt(inhibitMechanisms)
    failures.attempt(stopDriverRumble)
    failures.attempt(stopOperatorRumble)
    failures.throwIfAny()
}
