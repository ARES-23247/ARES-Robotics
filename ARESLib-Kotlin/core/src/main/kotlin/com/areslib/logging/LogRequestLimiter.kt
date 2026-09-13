package com.areslib.logging

import com.areslib.util.RobotClock

/** Bounded per-client credits: ten requests initially, then one credit per 100 ms. */
internal class LogRequestLimiter(private val maxClients: Int = 256) {
    init { require(maxClients > 0) }
    private class Bucket(var creditsNanos: Long, var updatedAt: Long, var lastAccess: Long)
    private val clients = LinkedHashMap<String, Bucket>(16, 0.75f, true)

    @Synchronized
    fun tryConsume(client: String): Boolean {
        val now = RobotClock.nanoTime()
        var bucket = clients[client]
        if (bucket == null) {
            // Expire only idle clients. Evicting an active client would reset its burst budget.
            val iterator = clients.entries.iterator()
            while (iterator.hasNext()) {
                val oldest = iterator.next().value
                if (now - oldest.lastAccess < IDLE_NANOS) break
                iterator.remove()
            }
            if (clients.size >= maxClients) return false
            bucket = Bucket(MAX_CREDITS_NANOS, now, now)
            clients[client] = bucket
        }
        val elapsed = now - bucket.updatedAt
        if (elapsed > 0L) {
            bucket.creditsNanos += minOf(elapsed, MAX_CREDITS_NANOS - bucket.creditsNanos)
            bucket.updatedAt = now
        }
        // A replay rewind neither destroys credits nor mints credits when time returns.
        if (now - bucket.lastAccess >= 0L) bucket.lastAccess = now
        if (bucket.creditsNanos < CREDIT_NANOS) return false
        bucket.creditsNanos -= CREDIT_NANOS
        return true
    }

    @Synchronized
    fun clear() = clients.clear()

    private companion object {
        const val CREDIT_NANOS = 100_000_000L
        const val MAX_CREDITS_NANOS = 10L * CREDIT_NANOS
        const val IDLE_NANOS = 60_000_000_000L
    }
}
