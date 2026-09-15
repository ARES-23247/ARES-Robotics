package com.ares.analytics.service

data class DatabaseMetricsSnapshot(
    val queryCount: Long = 0,
    val writeCount: Long = 0,
    val averageQueryMs: Double = 0.0,
    val p95QueryMs: Double = 0.0,
    val maxQueryMs: Double = 0.0
)

/**
 * Coherent database metrics. Counts, mean and maximum cover the lifetime of this tracker;
 * nearest-rank p95 covers the latest 512 reads. Recording uses primitive storage without boxing.
 */
class DatabaseMetrics(private val clock: MonotonicClock = SystemMonotonicClock) {
    private val lock = Any()
    private var queryCount = 0L
    private var writeCount = 0L
    private var meanQueryNanos = 0.0
    private var maxQueryNanos = 0L
    private val recentQueryNanos = LongArray(ROLLING_SAMPLE_COUNT)
    private var recentCount = 0
    private var nextSlot = 0

    fun recordRead(elapsedNanos: Long) {
        val elapsed = elapsedNanos.coerceAtLeast(0)
        synchronized(lock) {
            if (queryCount < Long.MAX_VALUE) queryCount += 1
            // Online mean avoids overflowing an accumulated Long nanosecond total.
            meanQueryNanos += (elapsed.toDouble() - meanQueryNanos) / queryCount.toDouble()
            maxQueryNanos = maxOf(maxQueryNanos, elapsed)
            recentQueryNanos[nextSlot] = elapsed
            nextSlot = (nextSlot + 1) % ROLLING_SAMPLE_COUNT
            if (recentCount < ROLLING_SAMPLE_COUNT) recentCount += 1
        }
    }

    /** Write latency is not currently exposed; retain the argument for caller compatibility. */
    @Suppress("UNUSED_PARAMETER")
    fun recordWrite(elapsedNanos: Long) {
        synchronized(lock) { if (writeCount < Long.MAX_VALUE) writeCount += 1 }
    }

    internal fun nowNanos(): Long = clock.nowNanos()

    fun snapshot(): DatabaseMetricsSnapshot {
        val count: Long
        val writes: Long
        val mean: Double
        val maximum: Long
        val sorted: LongArray
        synchronized(lock) {
            count = queryCount
            writes = writeCount
            mean = meanQueryNanos
            maximum = maxQueryNanos
            sorted = recentQueryNanos.copyOf(recentCount)
        }
        // Sort the owned snapshot outside the recording lock.
        sorted.sort()
        val p95 = if (sorted.isEmpty()) 0L else sorted[(sorted.size * 95 + 99) / 100 - 1]
        return DatabaseMetricsSnapshot(
            queryCount = count,
            writeCount = writes,
            averageQueryMs = mean / 1_000_000.0,
            p95QueryMs = p95 / 1_000_000.0,
            maxQueryMs = maximum / 1_000_000.0
        )
    }

    private companion object {
        const val ROLLING_SAMPLE_COUNT = 512
    }
}
