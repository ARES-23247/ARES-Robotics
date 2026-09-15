package com.areslib.subsystem

/**
 * Startup transaction support used by generated subsystem registries.
 *
 * Ordinary optional factory exceptions are isolated and skipped. Required factory failures,
 * serious errors and failed list insertions abort startup, close acquired subsystems in reverse
 * order and clear the incomplete result. Serious errors retain their identity; cleanup failures
 * are suppressed on the startup failure after every close has been attempted.
 *
 * The caller exclusively owns the mutable list during startup; factories and close callbacks must
 * not mutate it. A factory owns partial construction until it returns a subsystem. This helper
 * owns returned subsystems until the completed registry transfers them to the robot lifecycle.
 */
object GeneratedSubsystemRegistrySupport {
    @JvmStatic
    fun install(
        target: MutableList<Subsystem>,
        documentId: String,
        required: Boolean,
        factory: () -> Subsystem?,
    ) {
        val subsystem = try {
            factory()
        } catch (error: Throwable) {
            if (required || error !is Exception) rollbackFailure(target, documentId, error)
            System.err.println("Optional generated subsystem '$documentId' was skipped: ${error.message}")
            return
        }
        if (subsystem == null) {
            if (required) {
                rollbackFailure(
                    target,
                    documentId,
                    IllegalStateException("Required factory returned no subsystem"),
                )
            }
            return
        }
        try {
            check(target.add(subsystem)) { "Registry rejected subsystem insertion" }
        } catch (error: Throwable) {
            // The factory has transferred ownership even if the list cannot accept the result.
            rollbackFailure(target, documentId, error, subsystem)
        }
    }

    private fun rollbackFailure(
        target: MutableList<Subsystem>,
        documentId: String,
        cause: Throwable,
        pending: Subsystem? = null,
    ): Nothing {
        val failure = if (cause is Exception) {
            IllegalStateException("Generated subsystem '$documentId' failed to initialize", cause)
        } else cause
        // A custom list may insert and then throw. Do not close that returned instance twice.
        if (pending != null && target.none { it === pending }) closeAfterFailure(pending, failure)
        for (index in target.lastIndex downTo 0) {
            closeAfterFailure(target[index], failure)
        }
        try {
            target.clear()
        } catch (cleanupError: Throwable) {
            suppressCleanupFailure(failure, cleanupError)
        }
        throw failure
    }

    private fun closeAfterFailure(subsystem: Subsystem, failure: Throwable) {
        try {
            subsystem.close()
        } catch (cleanupError: Throwable) {
            suppressCleanupFailure(failure, cleanupError)
        }
    }

    private fun suppressCleanupFailure(failure: Throwable, cleanupError: Throwable) {
        if (failure !== cleanupError && failure.suppressed.none { it === cleanupError }) {
            failure.addSuppressed(cleanupError)
        }
    }
}
