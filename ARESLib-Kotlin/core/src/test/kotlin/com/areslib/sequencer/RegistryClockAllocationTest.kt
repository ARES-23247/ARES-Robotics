package com.areslib.sequencer

import com.areslib.util.RobotClock
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.lang.ref.WeakReference
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class RegistryClockAllocationTest {
    @Volatile private var escaped: ByteArray? = null
    @Volatile private var timeChecksum = 0L
    private fun bean(): ThreadMXBean {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        return requireNotNull(bean).apply { isThreadAllocatedMemoryEnabled = true }
    }
    private class Batch(val map: WeakIdentityMap<Any, Int>, val keys: Array<Any>)
    private fun batch(): Batch {
        val map = WeakIdentityMap<Any, Int>()
        val keys = Array(128) { Any() }
        keys.forEach { map[it] = 1 }
        val field = map.javaClass.getDeclaredField("entries").apply { isAccessible = true }
        val entries = field.get(map) as List<*>
        for (index in entries.indices step 2) {
            val entry = requireNotNull(entries[index])
            val reference = entry.javaClass.getDeclaredField("reference").apply { isAccessible = true }.get(entry) as WeakReference<*>
            reference.clear(); assertTrue(reference.enqueue())
        }
        return Batch(map, keys)
    }

    @Test fun `mixed collection compaction allocates no per-entry cleanup objects`() {
        val bean = bean()
        repeat(200) { val warm = batch(); warm.map.containsKey(warm.keys.last()) }
        val batches = Array(64) { batch() }
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        var survivors = 0
        for (batch in batches) if (batch.map.containsKey(batch.keys.last())) survivors++
        val allocated = bean.getThreadAllocatedBytes(id) - before
        println("[Registry allocation audit] 64 mixed 128-entry collection batches allocated $allocated bytes")
        assertTrue(survivors == batches.size)
        assertTrue(allocated <= 256L, "Collection compaction allocated $allocated bytes")
    }

    @Test fun `live and mock clock reads remain within zero-allocation budget`() {
        val bean = bean()
        val id = Thread.currentThread().id
        fun readClockBatch() {
            repeat(10_000) {
                timeChecksum = RobotClock.nanoTime() xor RobotClock.currentTimeMillis() xor if (RobotClock.isMocked) 1L else 0L
            }
        }
        try {
            for (mocked in listOf(false, true)) {
                if (mocked) RobotClock.useMockTime(1234) else RobotClock.useSystemTime()
                // Warm the same batch method measured below, including its instrumented branches.
                repeat(10) { readClockBatch() }
                val before = bean.getThreadAllocatedBytes(id)
                readClockBatch()
                val allocated = bean.getThreadAllocatedBytes(id) - before
                println("[Clock allocation audit] 10000 three-getter reads mocked=$mocked allocated $allocated bytes")
                assertTrue(allocated <= 256L, "Clock reads allocated $allocated bytes")
            }
        } finally { RobotClock.useSystemTime() }
    }

    @Test fun `allocation counter observes escaped array control`() {
        val bean = bean(); val id = Thread.currentThread().id
        repeat(2000) { escaped = ByteArray(32) }
        val before = bean.getThreadAllocatedBytes(id)
        repeat(1000) { escaped = ByteArray(32) }
        val allocated = bean.getThreadAllocatedBytes(id) - before
        println("[Registry allocation audit] Escaped array control allocated $allocated bytes")
        assertTrue(allocated >= 48_000L)
    }
}
