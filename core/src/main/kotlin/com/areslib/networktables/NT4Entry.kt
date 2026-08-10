package com.areslib.networktables

import java.util.concurrent.CopyOnWriteArrayList

/** Lifecycle event emitted for a topic entry. */
enum class NT4EventType {
    TOPIC_PUBLISHED,
    TOPIC_UNPUBLISHED,
    TOPIC_UPDATED
}

/** Synchronous entry listener invoked on the thread performing the server mutation. */
fun interface NT4EventListener {
    fun onEvent(entry: NT4Entry, eventType: NT4EventType, value: NT4Value)
}

/**
 * Mutable value and listener set for one normalized NT4 topic.
 *
 * [value] is volatile for cross-thread visibility, and listener registration is safe during
 * dispatch. [update] is a compare-then-set operation rather than an atomic transaction, so topic
 * mutations should remain serialized through [NT4Server]. Listener failures are isolated and do
 * not prevent subsequent listeners from running.
 */
class NT4Entry(
    var id: Int,
    val topic: String,
    @Volatile var value: NT4Value
) {
    private val listeners = CopyOnWriteArrayList<NT4EventListener>()

    /** Replaces the value and emits [NT4EventType.TOPIC_UPDATED] only when equality changes. */
    fun update(newValue: NT4Value): Boolean {
        if (this.value == newValue) return false
        this.value = newValue
        notifyListeners(NT4EventType.TOPIC_UPDATED, newValue)
        return true
    }

    /** Adds [listener]; duplicate registrations receive duplicate callbacks. */
    fun addListener(listener: NT4EventListener) {
        listeners.add(listener)
    }

    /** Removes one matching listener registration, if present. */
    fun removeListener(listener: NT4EventListener) {
        listeners.remove(listener)
    }

    /** Dispatches an event synchronously using the copy-on-write listener snapshot. */
    fun notifyListeners(eventType: NT4EventType, value: NT4Value) {
        for (listener in listeners) {
            try {
                listener.onEvent(this, eventType, value)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
