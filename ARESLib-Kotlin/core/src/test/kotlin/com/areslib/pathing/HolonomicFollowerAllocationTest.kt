package com.areslib.pathing

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class HolonomicFollowerAllocationTest {
    @Volatile private var escaped: ByteArray? = null

    private fun windows(allocateInCallback: Boolean): LongArray {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        val counter = requireNotNull(bean)
        counter.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        val drive = FollowerDriveProbe() // Cached pose and scalar output sink, excluding Redux/IO allocation.
        val follower = HolonomicPathFollower(drive)
        val target = followerTarget()
        val path = Path(emptyList(), List(1_000) { PathEvent("marker-$it", it.toDouble()) })
        var calls = 0
        follower.onEventTriggered = {
            calls++
            if (allocateInCallback) escaped = ByteArray(32)
        }
        val measured = LongArray(5)
        repeat(10) { window ->
            follower.startPath(path) // Snapshot/sort/reset is setup, deliberately outside measurement.
            val before = counter.getThreadAllocatedBytes(thread)
            for (i in 0 until 1_000) {
                target.distanceMeters = i.toDouble()
                follower.update(target, 0.02)
            }
            val bytes = counter.getThreadAllocatedBytes(thread) - before
            if (window >= 5) measured[window - 5] = bytes
        }
        assertEquals(10_000, calls)
        assertEquals(10_000, drive.writes)
        assertTrue(drive.vx > 0.0)
        return measured
    }

    @Test
    fun `crossing unique markers has no recurring bookkeeping allocation`() {
        val bytes = windows(false)
        println("Follower marker windows (1,000 updates each): ${bytes.toList()}")
        assertTrue(bytes.all { it in 0..4_096L })
        assertEquals(0L, bytes.minOrNull())
    }

    @Test
    fun `allocation probe detects an escaping allocation per callback`() {
        val bytes = windows(true)
        println("Follower allocating callback control: ${bytes.toList()}")
        assertTrue(bytes.all { it >= 32_000L })
        assertNotNull(escaped)
    }
}
