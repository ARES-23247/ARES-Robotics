package com.areslib.control.feedback

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PIDControllerAllocationTest {
    @Test
    fun `valid and rejected PID updates allocate no heap after warmup`() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!
        bean.isThreadAllocatedMemoryEnabled = true
        val pid = PIDController(0.2, 0.1, 0.05)
        pid.enableContinuousInput(-Math.PI, Math.PI)
        pid.setOutputLimits(-1.0, 1.0)
        val invalidPid = PIDController(1.0, 1.0, 1.0)
        var iteration = 0
        var output = 0.0
        var invalidOutput = 1.0
        fun update() {
            iteration++
            pid.i = if ((iteration and 512) == 0) 0.1 else 0.0
            pid.d = if ((iteration and 256) == 0) 0.05 else 0.0
            val measurement = (iteration and 127) * 0.002
            val target = if ((iteration and 256) == 0) 0.1 else -0.1
            output = pid.calculate(measurement, target, 0.02)
            invalidOutput = invalidPid.calculate(Double.NaN, 0.0, 0.02)
        }
        repeat(50_000) { update() }
        val threadId = Thread.currentThread().id
        var consecutiveZeroWindows = 0
        var lastAllocatedBytes = -1L
        for (window in 0 until 10) {
            val before = bean.getThreadAllocatedBytes(threadId)
            repeat(10_000) { update() }
            lastAllocatedBytes = bean.getThreadAllocatedBytes(threadId) - before
            consecutiveZeroWindows = if (lastAllocatedBytes == 0L) consecutiveZeroWindows + 1 else 0
            if (consecutiveZeroWindows == 2) break
        }
        assertEquals(2, consecutiveZeroWindows, "last allocation window: $lastAllocatedBytes bytes")
        assertTrue(output.isFinite())
        assertEquals(0.0, invalidOutput)
    }
}
