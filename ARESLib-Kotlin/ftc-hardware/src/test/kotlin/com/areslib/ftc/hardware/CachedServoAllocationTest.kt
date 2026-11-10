package com.areslib.ftc.hardware

import com.qualcomm.robotcore.hardware.Servo
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CachedServoAllocationTest {
    private class Device : Servo {
        var writes = 0
        override var position: Double = 0.0
            set(value) { field = value; writes++ }
        override fun setDirection(value: Servo.Direction) = Unit
    }

    private fun commandPattern(cached: CachedServo) {
        cached.position = 0.25
        cached.position = 0.251 // Suppressed below epsilon.
        cached.position = 0.75
    }

    @Test fun `mixed servo writes stay allocation free after configuration invalidation`() {
        val bean = ManagementFactory.getThreadMXBean()
        assumeTrue(bean is ThreadMXBean && bean.isThreadAllocatedMemorySupported)
        val allocation = bean as ThreadMXBean
        allocation.isThreadAllocatedMemoryEnabled = true
        val device = Device()
        val cached = CachedServo(device)
        cached.position = 0.75
        cached.setDirection(Servo.Direction.REVERSE)
        cached.position = 0.75
        assertEquals(2, device.writes)
        repeat(50_000) { commandPattern(cached) }
        val threadId = Thread.currentThread().id
        var consecutiveZero = 0
        var windows = 0
        var totalBytes = 0L
        while (windows < 10 && consecutiveZero < 2) {
            val priorWrites = device.writes
            val before = allocation.getThreadAllocatedBytes(threadId)
            repeat(20_000) { commandPattern(cached) }
            val bytes = allocation.getThreadAllocatedBytes(threadId) - before
            assertEquals(40_000, device.writes - priorWrites)
            totalBytes += bytes
            consecutiveZero = if (bytes == 0L) consecutiveZero + 1 else 0
            windows++
        }
        assertTrue(totalBytes <= 64L * 1024L, "JVM warmup overhead: $totalBytes bytes")
        assertEquals(2, consecutiveZero, "Two zero-allocation windows required")
        println("CachedServo: 60000 calls/window, 40000 writes/window; windows=$windows, overhead=$totalBytes bytes, final two windows=0 bytes")
    }
}
