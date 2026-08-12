package com.areslib.sequencer

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/**
 * Small synchronized weak map whose keys use object identity rather than `equals`.
 *
 * Task registries are deliberately small, so a compact linear table avoids allocating temporary
 * lookup weak references and `HashMap` iterators in 20 Hz watchdog scans. Entries allocate only
 * when a new task is registered; lookup, removal, and [forEachLive] are allocation-free.
 */
internal class WeakIdentityMap<K : Any, V> {
    internal fun interface EntryVisitor<K : Any, V> {
        fun visit(key: K, value: V)
    }

    private class Entry<K : Any, V>(
        val reference: WeakReference<K>,
        var value: V,
    )

    private val referenceQueue = ReferenceQueue<K>()
    private val entries = ArrayList<Entry<K, V>>(16)

    @Synchronized
    operator fun get(key: K): V? {
        removeCollectedKeys()
        val index = findIndex(key)
        return if (index >= 0) entries[index].value else null
    }

    @Synchronized
    operator fun set(key: K, value: V) {
        removeCollectedKeys()
        val index = findIndex(key)
        if (index >= 0) {
            entries[index].value = value
        } else {
            entries.add(Entry(WeakReference(key, referenceQueue), value))
        }
    }

    @Synchronized
    fun remove(key: K): V? {
        removeCollectedKeys()
        val index = findIndex(key)
        return if (index >= 0) entries.removeAt(index).value else null
    }

    @Synchronized
    fun containsKey(key: K): Boolean {
        removeCollectedKeys()
        return findIndex(key) >= 0
    }

    /** Visits live entries directly without constructing a snapshot, iterator, pair, or lambda. */
    @Synchronized
    fun forEachLive(visitor: EntryVisitor<K, V>) {
        removeCollectedKeys()
        var index = 0
        while (index < entries.size) {
            val entry = entries[index]
            val key = entry.reference.get()
            if (key != null) visitor.visit(key, entry.value)
            index++
        }
    }

    private fun findIndex(key: K): Int {
        var index = 0
        while (index < entries.size) {
            if (entries[index].reference.get() === key) return index
            index++
        }
        return -1
    }

    @Suppress("UNCHECKED_CAST")
    private fun removeCollectedKeys() {
        while (true) {
            val collected = referenceQueue.poll() as WeakReference<K>? ?: return
            var index = 0
            while (index < entries.size) {
                if (entries[index].reference === collected) {
                    entries.removeAt(index)
                    break
                }
                index++
            }
        }
    }
}
