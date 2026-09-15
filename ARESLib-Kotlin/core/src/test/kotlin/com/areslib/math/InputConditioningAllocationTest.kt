package com.areslib.math

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.*

class InputConditioningAllocationTest {
    @Test fun `scalar and buffer processing allocate no heap after warmup`() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!; bean.isThreadAllocatedMemoryEnabled = true
        val output = DoubleArray(2); var iteration = 0; var checksum = 0.0
        fun update() {
            val value = (iteration++ and 127) / 127.0
            val exponent = when (iteration % 3) { 0 -> 1.0; 1 -> 2.0; else -> 0.5 }
            InputMath.processJoystickVectorInto(value, -value, output, exponent = exponent)
            checksum = output[0] + InputMath.applyCurve(InputMath.applyDeadband(value, 0.05), exponent)
            InputMath.processJoystickVectorInto(Double.NaN, value, output)
            checksum += output[1] + InputMath.applyCurve(value, 0.0)
        }
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
