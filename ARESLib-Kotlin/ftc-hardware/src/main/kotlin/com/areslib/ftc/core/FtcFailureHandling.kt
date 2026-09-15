package com.areslib.ftc.core

/** Preserve cancellation while completing the remaining safety/cleanup operations. */
internal fun preserveFtcInterrupt(failure: Throwable) {
    if (failure is InterruptedException || failure.cause is InterruptedException) Thread.currentThread().interrupt()
}

/** Keep the first failure and attach each different secondary instance at most once. */
internal fun retainFtcFailure(primary: Throwable?, failure: Throwable): Throwable {
    preserveFtcInterrupt(failure)
    if (primary == null) return failure
    if (primary !== failure && primary.suppressed.none { it === failure }) primary.addSuppressed(failure)
    return primary
}
