package com.areslib.math.filter

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.*

class PrimitiveFilterAllocationTest {
    @Test fun `updates reads rejection and extreme arithmetic allocate no heap after warmup`() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!; bean.isThreadAllocatedMemoryEnabled = true
        val median = MedianFilter(33); val low = LowPassFilter(0.1); val slew = SlewRateLimiter(2.0)
        val tiny = LowPassFilter(1e300); val wide = SlewRateLimiter(Double.MAX_VALUE)
        var iteration = 0; var checksum = 0.0
        fun update() {
            iteration++
            val input = (iteration and 127).toDouble()
            checksum = median.calculate(input) + median.value + low.calculate(input, 0.02) + slew.calculate(input, 0.02)
            checksum += median.calculate(Double.NaN) + low.calculate(input, 0.0) + slew.calculate(Double.NaN, 0.02)
            tiny.reset(0.0); checksum += tiny.calculate(1e300, 1e-300)
            wide.reset(-Double.MAX_VALUE); checksum += wide.calculate(Double.MAX_VALUE, 1.5) / Double.MAX_VALUE
        }
        repeat(50_000) { update() }
        val thread = Thread.currentThread().id
        var consecutive = 0; var bytes = -1L
        for (window in 0 until 10) {
            val before = bean.getThreadAllocatedBytes(thread)
            repeat(10_000) { update() }
            bytes = bean.getThreadAllocatedBytes(thread) - before
            consecutive = if (bytes == 0L) consecutive + 1 else 0
            if (consecutive == 2) break
        }
        assertEquals(2, consecutive, "last window: $bytes allocated bytes")
        assertTrue(checksum.isFinite())
    }
}
