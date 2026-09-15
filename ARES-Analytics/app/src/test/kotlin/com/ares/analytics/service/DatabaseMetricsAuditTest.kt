package com.ares.analytics.service

import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DatabaseMetricsAuditTest {
    @Test fun `empty and negative durations produce finite zero metrics`() {
        val metrics = DatabaseMetrics()
        assertEquals(DatabaseMetricsSnapshot(), metrics.snapshot())
        metrics.recordRead(-1); metrics.recordWrite(-1)
        assertEquals(DatabaseMetricsSnapshot(queryCount = 1, writeCount = 1), metrics.snapshot())
    }
    @Test fun `small sample p95 includes the upper tail`() {
        val metrics = DatabaseMetrics()
        metrics.recordRead(1_000_000); metrics.recordRead(100_000_000)
        assertEquals(100.0, metrics.snapshot().p95QueryMs)
    }
    @Test fun `nearest rank p95 has at least 95 percent of samples at or below it`() {
        for (size in listOf(1, 3, 19, 20, 21, 100, 512)) {
            val metrics = DatabaseMetrics()
            for (i in size downTo 1) metrics.recordRead(i * 1_000_000L)
            val p95 = metrics.snapshot().p95QueryMs.toInt()
            assertTrue(p95 * 100 >= size * 95)
            assertTrue((p95 - 1) * 100 < size * 95)
        }
    }
    @Test fun `huge durations cannot overflow the mean`() {
        val metrics = DatabaseMetrics()
        repeat(4) { metrics.recordRead(Long.MAX_VALUE) }
        val result = metrics.snapshot()
        assertEquals(Long.MAX_VALUE.toDouble()/1_000_000, result.averageQueryMs)
        assertTrue(result.averageQueryMs.isFinite())
        assertEquals(result.maxQueryMs, result.averageQueryMs)
    }
    @Test fun `mean retains subnanosecond averages rather than truncating the sum quotient`() {
        val metrics = DatabaseMetrics()
        metrics.recordRead(0); metrics.recordRead(1)
        assertEquals(0.0000005, metrics.snapshot().averageQueryMs, 1e-16)
    }
    @Test fun `rolling percentile evicts old samples while mean and maximum remain lifetime metrics`() {
        val metrics = DatabaseMetrics()
        metrics.recordRead(1_000_000_000)
        repeat(512) { metrics.recordRead(1_000_000) }
        val result = metrics.snapshot()
        assertEquals(1.0, result.p95QueryMs)
        assertEquals(1000.0, result.maxQueryMs)
        assertEquals(1512.0/513, result.averageQueryMs, 1e-12)
        assertEquals(513, result.queryCount)
    }
    @Test fun `clock delegation preserves injected monotonic readings`() {
        val metrics = DatabaseMetrics(MonotonicClock { 42L })
        assertEquals(42L, metrics.nowNanos())
    }
    @Test fun `concurrent snapshots cannot mix count mean maximum and percentile generations`() {
        val metrics = DatabaseMetrics()
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val jobs = (0..2).map { pool.submit { start.await(); repeat(30_000) { metrics.recordRead(1_000_000) } } }
            val observer = pool.submit {
                start.await()
                repeat(3000) {
                    val result = metrics.snapshot()
                    val expected = if (result.queryCount == 0L) 0.0 else 1.0
                    assertEquals(expected, result.averageQueryMs)
                    assertEquals(expected, result.maxQueryMs)
                    assertEquals(expected, result.p95QueryMs)
                }
            }
            start.countDown()
            jobs.forEach { it.get(20, TimeUnit.SECONDS) }; observer.get(20, TimeUnit.SECONDS)
            assertEquals(90_000, metrics.snapshot().queryCount)
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS)) }
    }
    @Test fun `recording metrics avoids allocation per sample after warmup`() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        org.junit.Assume.assumeTrue(bean?.isThreadAllocatedMemorySupported == true)
        val meter = requireNotNull(bean); meter.isThreadAllocatedMemoryEnabled = true
        val metrics = DatabaseMetrics()
        repeat(30_000) { metrics.recordRead(1_000_000); metrics.recordWrite(1_000_000) }
        val id = Thread.currentThread().id
        val before = meter.getThreadAllocatedBytes(id)
        repeat(100_000) { metrics.recordRead(1_000_000); metrics.recordWrite(1_000_000) }
        val allocated = meter.getThreadAllocatedBytes(id) - before
        assertTrue(allocated <= 8192, "Allocated $allocated bytes for 100,000 read/write pairs")
    }
}
