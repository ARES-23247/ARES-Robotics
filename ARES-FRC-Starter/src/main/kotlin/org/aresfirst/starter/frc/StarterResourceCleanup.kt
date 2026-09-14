package org.aresfirst.starter.frc

/** Release all starter-owned resources, preserving first failure identity and direct interruption. */
internal fun closeStarterResources(resources: Iterable<AutoCloseable>) {
    var first: Throwable? = null
    for (resource in resources) {
        try {
            resource.close()
        } catch (failure: Throwable) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            val primary = first
            if (primary == null) first = failure
            else if (primary !== failure && primary.suppressed.none { it === failure }) primary.addSuppressed(failure)
        }
    }
    first?.let { throw it }
}
