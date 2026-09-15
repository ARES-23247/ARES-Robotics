package com.areslib.math

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.*

class InterpolatingTableAllocationTest {
    private class ReusableSample : Interpolatable<ReusableSample> {
        var ratio = 0.0
        override fun interpolate(other: ReusableSample, ratio: Double): ReusableSample { this.ratio = ratio; return this }
    }
    // Generic storage preserves the already boxed key; caller-side boxing is outside the measured API work.
    private class Query<K : Comparable<K>>(val table: InterpolatingTable<K, ReusableSample>, val key: K) {
        fun run() = table.get(key)
    }
    @Test fun `primitive-key reads allocate no internal scratch after warmup`() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!; bean.isThreadAllocatedMemoryEnabled = true
        val doubles = InterpolatingTable<Double, ReusableSample>()
        doubles.put(-Double.MAX_VALUE, ReusableSample()); doubles.put(0.0, ReusableSample()); doubles.put(Double.MAX_VALUE, ReusableSample())
        val longs = InterpolatingTable<Long, ReusableSample>()
        longs.put(Long.MIN_VALUE, ReusableSample()); longs.put(Long.MAX_VALUE, ReusableSample())
        val queries: Array<Query<*>> = arrayOf(Query(doubles, 0.5), Query(doubles, 0.0), Query(doubles, Double.NaN),
            Query(longs, 0L), Query(longs, Long.MAX_VALUE - 1))
        var iteration = 0; var checksum = 0.0
        fun update() { checksum += queries[iteration++ % queries.size].run()?.ratio ?: 0.0 }
        repeat(50_000) { update() }
        val thread = Thread.currentThread().id; var consecutive = 0; var bytes = -1L
        for (window in 0 until 10) {
            val before = bean.getThreadAllocatedBytes(thread)
            repeat(10_000) { update() }
            bytes = bean.getThreadAllocatedBytes(thread) - before
            consecutive = if (bytes == 0L) consecutive + 1 else 0
            if (consecutive == 2) break
        }
        assertEquals(2, consecutive, "last window: $bytes bytes"); assertTrue(checksum.isFinite())
    }
}
