package com.areslib.frc.drivetrain

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Same five-window policy as the existing CTRE writer probe; transient allocations remain visible. */
internal fun measureAllocationWindows(writesPerWindow: Int = 10_000, work: () -> Unit): LongArray {
    val candidate = ManagementFactory.getThreadMXBean()
    assumeTrue(candidate is ThreadMXBean && candidate.isThreadAllocatedMemorySupported,
        "Thread allocation measurement is unavailable on this JVM")
    val bean = candidate as ThreadMXBean
    bean.isThreadAllocatedMemoryEnabled = true
    fun window() { repeat(writesPerWindow) { work() } }
    repeat(5) { window() }
    val bytes = LongArray(5)
    val thread = Thread.currentThread().id
    for (index in bytes.indices) {
        val before = bean.getThreadAllocatedBytes(thread)
        window()
        bytes[index] = bean.getThreadAllocatedBytes(thread) - before
    }
    return bytes
}

internal fun assertSteadyStateAllocationWindows(bytes: LongArray) {
    val description = bytes.contentToString()
    println("Allocation bytes per window: $description")
    assertEquals(5, bytes.size)
    assertTrue(bytes.all { it >= 0L }, "Allocation instrumentation must remain valid: $description")
    assertEquals(0L, bytes.minOrNull(), "At least one complete window must allocate zero bytes: $description")
    assertTrue(bytes.sum() <= 64L * 1024L, "Transient allocation exceeds the existing 64 KiB cap: $description")
}

class SteadyStateAllocationProbeTest {
    @Volatile private var escaped: Any? = null

    @Test fun `empty work passes the window criterion`() {
        assertSteadyStateAllocationWindows(measureAllocationWindows { })
    }

    @Test fun `escaped allocation on every iteration fails the window criterion`() {
        try {
            val bytes = measureAllocationWindows { escaped = ByteArray(32) }
            assertTrue(bytes.all { it >= 10_000L * 32L })
            assertThrows(AssertionError::class.java) { assertSteadyStateAllocationWindows(bytes) }
        } finally { escaped = null }
    }
}
