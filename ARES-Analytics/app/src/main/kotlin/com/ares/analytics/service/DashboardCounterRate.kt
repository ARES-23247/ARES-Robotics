package com.ares.analytics.service

/** One sampler's primitive counter baseline; elapsed intervals must be shorter than 2^63 ns. */
internal class DashboardCounterRate {
    private var initialized = false
    private var previousCount = 0uL
    private var previousNanos = 0L
    private var previousEpoch = 0L

    fun reset() { initialized = false }

    fun sample(count: ULong, nowNanos: Long, epoch: Long): Double {
        if (!initialized || epoch != previousEpoch || count < previousCount) {
            initialized = true
            previousCount = count
            previousNanos = nowNanos
            previousEpoch = epoch
            return 0.0
        }
        // Signed subtraction also handles System.nanoTime's ordinary signed wraparound.
        val elapsedNanos = nowNanos - previousNanos
        if (elapsedNanos <= 0L) return 0.0
        val delta = count - previousCount
        previousCount = count
        previousNanos = nowNanos
        return delta.toDouble() / (elapsedNanos.toDouble() / 1_000_000_000.0)
    }
}
