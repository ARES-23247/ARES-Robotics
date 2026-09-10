package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

class PathQueryAllocationTest {
    @Volatile private var escaped: ByteArray? = null
    @Volatile private var observed = 0.0

    private fun windows(allocate: Boolean): LongArray {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        val counter = requireNotNull(bean)
        counter.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        val path = Path(List(1_001) { index ->
            val distance = index * 0.01
            PathPoint(Pose2d(distance, 0.2 * sin(distance)), 1.0, distance)
        })
        val out = MutablePathPoint()
        val allocated = LongArray(5)
        var maximumError = 0.0
        repeat(10) { window ->
            val before = counter.getThreadAllocatedBytes(thread)
            for (i in 0 until 1_000) {
                val distance = ((i * 31) % 1_000) * 0.01 + if (i % 4 == 0) 0.0 else 0.005
                path.sampleAtDistance(distance, out)
                val projected = path.findClosestDistance(out.x, out.y, max(0.0, distance - 0.02), distance + 0.02)
                maximumError = max(maximumError, abs(projected - distance))
                observed = projected + out.x + out.velocityMps
                if (allocate) escaped = ByteArray(32)
            }
            val bytes = counter.getThreadAllocatedBytes(thread) - before
            if (window >= 5) allocated[window - 5] = bytes
        }
        assertTrue(maximumError < 1e-12, "projection error=$maximumError")
        assertTrue(observed > 0.0)
        return allocated
    }

    @Test
    fun `random access sampling and narrow projection reuse primitive output storage`() {
        val bytes = windows(false)
        println("Path query allocation windows (1,000 sampling/projection pairs): ${bytes.toList()}")
        assertTrue(bytes.all { it in 0..4_096L })
        assertEquals(0L, bytes.minOrNull())
    }

    @Test
    fun `query allocation probe detects an escaping allocation per pair`() {
        val bytes = windows(true)
        println("Path query allocating control: ${bytes.toList()}")
        assertTrue(bytes.all { it >= 32_000L })
        assertNotNull(escaped)
    }
}
