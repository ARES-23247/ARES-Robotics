package com.areslib.sequencer

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/** Small synchronized weak map whose keys use object identity rather than `equals`. */
internal class WeakIdentityMap<K : Any, V> {
    private val referenceQueue = ReferenceQueue<K>()
    private val values = HashMap<IdentityReference<K>, V>()

    @Synchronized
    operator fun get(key: K): V? {
        removeCollectedKeys()
        return values[IdentityReference.lookup(key)]
    }

    @Synchronized
    operator fun set(key: K, value: V) {
        removeCollectedKeys()
        values[IdentityReference(key, referenceQueue)] = value
    }

    @Synchronized
    fun remove(key: K): V? {
        removeCollectedKeys()
        return values.remove(IdentityReference.lookup(key))
    }

    @Synchronized
    fun containsKey(key: K): Boolean {
        removeCollectedKeys()
        return values.containsKey(IdentityReference.lookup(key))
    }

    @Synchronized
    fun entriesSnapshot(): List<Pair<K, V>> {
        removeCollectedKeys()
        return values.mapNotNull { (reference, value) -> reference.get()?.let { it to value } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun removeCollectedKeys() {
        while (true) {
            val reference = referenceQueue.poll() as IdentityReference<K>? ?: return
            values.remove(reference)
        }
    }

    private class IdentityReference<T : Any>(
        referent: T,
        queue: ReferenceQueue<T>? = null
    ) : WeakReference<T>(referent, queue) {
        private val identityHash = System.identityHashCode(referent)

        override fun hashCode(): Int = identityHash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IdentityReference<*>) return false
            val left = get() ?: return false
            return left === other.get()
        }

        companion object {
            fun <T : Any> lookup(referent: T): IdentityReference<T> = IdentityReference(referent)
        }
    }
}
