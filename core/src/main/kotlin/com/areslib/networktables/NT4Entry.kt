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
    @Volatile var value: NT4Value,
    /** Whether a publisher has supplied a value, rather than only declaring the topic type. */
    @Volatile var hasValue: Boolean = true,
    /** Timestamp of [value] in the server time base, in microseconds. */
    @Volatile var timestampUs: Long = Long.MIN_VALUE
) {
    private val listeners = CopyOnWriteArrayList<NT4EventListener>()

    /**
     * Applies [newValue] when [timestampUs] is not older than the retained value.
     *
     * A newly declared client topic has a type placeholder but no value. Its first update must
     * therefore be observable even when it equals that placeholder (for example, the first
     * published double is `0.0`). NT4 also requires the retained value to be the update with the
     * greatest timestamp, so delayed packets cannot roll state backwards.
     */
    @Synchronized
    fun update(
        newValue: NT4Value,
        timestampUs: Long = com.areslib.util.RobotClock.currentTimeMillis() * 1_000L
    ): Boolean {
        if (hasValue && timestampUs < this.timestampUs) return false
        val changed = !hasValue || this.value != newValue
        this.value = newValue
        this.timestampUs = timestampUs
        hasValue = true
        if (changed) notifyListeners(NT4EventType.TOPIC_UPDATED, newValue)
        return changed
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
