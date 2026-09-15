package org.aresfirst.marvin

/** Owns completed constructor results until the whole factory result is handed to its caller. */
internal class FrcStartupResources {
    private val resources = ArrayList<AutoCloseable>()

    fun <T : AutoCloseable> own(resource: T): T {
        if (resources.none { it === resource }) resources.add(resource)
        return resource
    }

    /** A successfully constructed adapter now owns these previously tracked native resources. */
    fun <T : AutoCloseable> replace(owner: T, vararg children: AutoCloseable): T {
        own(owner)
        for (child in children) {
            if (child !== owner) resources.removeAll { it === child }
        }
        return owner
    }

    fun commit() { resources.clear() }

    fun rollback(original: Throwable) {
        while (resources.isNotEmpty()) {
            val resource = resources.removeAt(resources.lastIndex)
            try { resource.close() } catch (failure: Throwable) { original.addSuppressed(failure) }
        }
    }
}

internal inline fun <T> withFrcStartupResources(create: (FrcStartupResources) -> T): T {
    val resources = FrcStartupResources()
    try {
        val result = create(resources)
        resources.commit()
        return result
    } catch (failure: Throwable) {
        resources.rollback(failure)
        throw failure
    }
}
