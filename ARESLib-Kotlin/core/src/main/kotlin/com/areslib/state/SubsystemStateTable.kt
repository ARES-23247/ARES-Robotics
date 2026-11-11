package com.areslib.state

import java.util.AbstractMap.SimpleImmutableEntry

/**
 * Compact, deeply immutable [Map] implementation for [SuperstructureState.subsystems].
 *
 * Typical robots register 1–12 named subsystems whose key set remains fixed after startup while
 * values update at 50 Hz. Sharing [keyArray] across steady-state updates and cloning only
 * [valueArray] avoids `Pair`, `LinkedHashMap`, `Node[]`, and per-entry node allocations on every
 * periodic tick.
 */
internal class SubsystemStateTable private constructor(
    private val keyArray: Array<String>,
    private val valueArray: Array<SubsystemState>,
) : kotlin.collections.AbstractMap<String, SubsystemState>() {

    override val size: Int
        get() = keyArray.size

    override fun isEmpty(): Boolean = keyArray.isEmpty()

    override fun containsKey(key: String): Boolean = indexOfKey(key) >= 0

    override fun containsValue(value: SubsystemState): Boolean {
        for (i in valueArray.indices) {
            if (valueArray[i] == value) return true
        }
        return false
    }

    override fun get(key: String): SubsystemState? {
        val index = indexOfKey(key)
        return if (index >= 0) valueArray[index] else null
    }

    override val entries: Set<Map.Entry<String, SubsystemState>>
        get() = EntrySet()

    private fun indexOfKey(key: String): Int {
        for (i in keyArray.indices) {
            if (keyArray[i] === key) return i
        }
        for (i in keyArray.indices) {
            if (keyArray[i] == key) return i
        }
        return -1
    }

    private inner class EntrySet : kotlin.collections.AbstractSet<Map.Entry<String, SubsystemState>>() {
        override val size: Int
            get() = keyArray.size

        override fun iterator(): Iterator<Map.Entry<String, SubsystemState>> =
            object : Iterator<Map.Entry<String, SubsystemState>> {
                private var cursor = 0
                override fun hasNext(): Boolean = cursor < keyArray.size
                override fun next(): Map.Entry<String, SubsystemState> {
                    val i = cursor
                    if (i >= keyArray.size) throw NoSuchElementException()
                    cursor = i + 1
                    return SimpleImmutableEntry(keyArray[i], valueArray[i])
                }
            }
    }

    companion object {
        fun withUpdated(
            existing: Map<String, SubsystemState>,
            key: String,
            value: SubsystemState,
        ): Map<String, SubsystemState> {
            if (existing is SubsystemStateTable) {
                val existingIndex = existing.indexOfKey(key)
                if (existingIndex >= 0) {
                    if (existing.valueArray[existingIndex] === value) return existing
                    val nextValues = existing.valueArray.clone()
                    nextValues[existingIndex] = value
                    return SubsystemStateTable(existing.keyArray, nextValues)
                }
                val count = existing.keyArray.size
                val nextKeys = Array(count + 1) { index -> if (index < count) existing.keyArray[index] else key }
                val nextValues = Array(count + 1) { index -> if (index < count) existing.valueArray[index] else value }
                return SubsystemStateTable(nextKeys, nextValues)
            }
            if (existing.isEmpty()) {
                return SubsystemStateTable(arrayOf(key), arrayOf(value))
            }
            if (existing[key] === value) return existing
            val contains = existing.containsKey(key)
            val total = if (contains) existing.size else existing.size + 1
            val keysList = ArrayList<String>(total)
            val valuesList = ArrayList<SubsystemState>(total)
            for ((existingKey, existingValue) in existing) {
                keysList.add(existingKey)
                valuesList.add(if (existingKey == key) value else existingValue)
            }
            if (!contains) {
                keysList.add(key)
                valuesList.add(value)
            }
            return SubsystemStateTable(keysList.toTypedArray(), valuesList.toTypedArray())
        }
    }
}
