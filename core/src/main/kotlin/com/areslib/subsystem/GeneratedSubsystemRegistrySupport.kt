package com.areslib.subsystem

/**
 * Startup transaction support used by generated subsystem registries.
 *
 * Optional factories are isolated and skipped. A required failure closes every subsystem already
 * installed by the current registry in reverse order, clears the incomplete result, and rethrows.
 * This prevents a failed `buildList` from trapping live hardware resources in an unreachable list.
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
        } catch (error: Exception) {
            if (required) rollbackRequiredFailure(target, documentId, error)
            System.err.println("Optional generated subsystem '$documentId' was skipped: ${error.message}")
            return
        }
        if (subsystem == null) {
            if (required) {
                rollbackRequiredFailure(
                    target,
                    documentId,
                    IllegalStateException("Required factory returned no subsystem"),
                )
            }
            return
        }
        target.add(subsystem)
    }

    private fun rollbackRequiredFailure(
        target: MutableList<Subsystem>,
        documentId: String,
        cause: Exception,
    ): Nothing {
        val failure = IllegalStateException(
            "Required generated subsystem '$documentId' failed to initialize",
            cause,
        )
        for (index in target.lastIndex downTo 0) {
            try {
                target[index].close()
            } catch (cleanupError: Exception) {
                failure.addSuppressed(cleanupError)
            }
        }
        target.clear()
        throw failure
    }
}
