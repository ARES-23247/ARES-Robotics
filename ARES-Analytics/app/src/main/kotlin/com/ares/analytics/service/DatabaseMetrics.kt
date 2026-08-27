package com.ares.analytics.service

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

data class DatabaseMetricsSnapshot(
    val queryCount: Long = 0,
    val writeCount: Long = 0,
    val averageQueryMs: Double = 0.0,
    val p95QueryMs: Double = 0.0,
    val maxQueryMs: Double = 0.0
)

/** Low-overhead rolling latency tracker shared by the DuckDB repository and health UI. */
class DatabaseMetrics(private val clock: MonotonicClock = SystemMonotonicClock) {
    private val queryCount = AtomicLong()
    private val writeCount = AtomicLong()
    private val queryTotalNanos = AtomicLong()
    private val queryMaxNanos = AtomicLong()
    private val recentQueryNanos = ArrayDeque<Long>(ROLLING_SAMPLE_COUNT)

    fun recordRead(elapsedNanos: Long) {
        val elapsed = elapsedNanos.coerceAtLeast(0)
        queryCount.incrementAndGet()
        queryTotalNanos.addAndGet(elapsed)
        queryMaxNanos.accumulateAndGet(elapsed, ::maxOf)
        synchronized(recentQueryNanos) {
            recentQueryNanos.addLast(elapsed)
            if (recentQueryNanos.size > ROLLING_SAMPLE_COUNT) recentQueryNanos.removeFirst()
        }
    }

    fun recordWrite(elapsedNanos: Long) {
        elapsedNanos.coerceAtLeast(0)
        writeCount.incrementAndGet()
    }

    internal fun nowNanos(): Long = clock.nowNanos()

    fun snapshot(): DatabaseMetricsSnapshot {
        val count = queryCount.get()
        val sorted = synchronized(recentQueryNanos) { recentQueryNanos.sorted() }
        val p95 = if (sorted.isEmpty()) 0L else sorted[((sorted.size - 1) * 0.95).toInt()]
        return DatabaseMetricsSnapshot(
            queryCount = count,
            writeCount = writeCount.get(),
            averageQueryMs = if (count == 0L) 0.0 else queryTotalNanos.get() / count / 1_000_000.0,
            p95QueryMs = p95 / 1_000_000.0,
            maxQueryMs = queryMaxNanos.get() / 1_000_000.0
        )
    }

    private companion object {
        const val ROLLING_SAMPLE_COUNT = 512
    }
}
