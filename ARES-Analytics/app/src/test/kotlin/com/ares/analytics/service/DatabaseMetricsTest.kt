package com.ares.analytics.service

import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseMetricsTest {
    @Test
    fun `snapshot reports rolling query latency and counts`() {
        val metrics = DatabaseMetrics()
        metrics.recordRead(1_000_000)
        metrics.recordRead(5_000_000)
        metrics.recordRead(10_000_000)
        metrics.recordWrite(2_000_000)

        val snapshot = metrics.snapshot()

        assertEquals(3, snapshot.queryCount)
        assertEquals(1, snapshot.writeCount)
        assertEquals(10.0, snapshot.maxQueryMs)
        assertEquals(5.0, snapshot.p95QueryMs)
    }
}
