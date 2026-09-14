package org.aresfirst.starter.frc

/** Release all starter-owned resources, preserving first failure identity and direct interruption. */
internal fun closeStarterResources(resources: Iterable<AutoCloseable>) {
    var first: Throwable? = null
    for (resource in resources) {
        try {
            resource.close()
        } catch (failure: Throwable) {
            first = retainStarterFailure(first, failure)
        }
    }
    first?.let { throw it }
}

/** Keep first failure identity, unique later failures, and direct interruption across cleanup owners. */
internal fun retainStarterFailure(primary: Throwable?, failure: Throwable): Throwable {
    if (failure is InterruptedException) Thread.currentThread().interrupt()
    if (primary == null) return failure
    if (primary !== failure && primary.suppressed.none { it === failure }) primary.addSuppressed(failure)
    return primary
}
