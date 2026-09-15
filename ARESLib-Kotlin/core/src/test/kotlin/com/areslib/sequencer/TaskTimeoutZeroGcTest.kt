package com.areslib.sequencer

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class TaskTimeoutZeroGcTest {
    @Volatile private var escaped: ByteArray? = null

    private fun allocationBean(): ThreadMXBean {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        return requireNotNull(bean).apply { isThreadAllocatedMemoryEnabled = true }
    }

    @Test
    fun `steady state watchdog scan allocates no per-task snapshots`() {
        val allocationBean = allocationBean()
        val tasks = Array(8) { index ->
            object : Task {
                override val name = "allocation-probe-$index"
                override fun isCompleted(state: com.areslib.state.RobotState, elapsedMs: Long) = false
            }
        }

        com.areslib.util.RobotClock.useMockTime(0L)
        try {
            var index = 0
            while (index < tasks.size) {
                TaskTimeoutManager.setTimeout(tasks[index], Long.MAX_VALUE)
                TaskTimeoutManager.start(tasks[index])
                index++
            }
            repeat(2_000) { TaskTimeoutManager.runWatchdogCheck(1L) }

            val threadId = Thread.currentThread().id
            val before = allocationBean.getThreadAllocatedBytes(threadId)
            repeat(10_000) { TaskTimeoutManager.runWatchdogCheck(1L) }
            val allocatedBytes = allocationBean.getThreadAllocatedBytes(threadId) - before
            println("[Timeout audit] 10000 watchdog scans over 8 active tasks allocated $allocatedBytes bytes")

            assertTrue(
                allocatedBytes <= 256L,
                "Watchdog scans must reuse weak-map traversal storage (allocated $allocatedBytes bytes)",
            )
        } finally {
            var index = 0
            while (index < tasks.size) {
                TaskTimeoutManager.reset(tasks[index])
                tasks[index].reset()
                index++
            }
            com.areslib.util.RobotClock.useSystemTime()
        }
    }

    @Test
    fun `allocation counter detects escaped arrays`() {
        val bean = allocationBean()
        val id = Thread.currentThread().id
        repeat(2000) { escaped = ByteArray(32) }
        val before = bean.getThreadAllocatedBytes(id)
        repeat(1000) { escaped = ByteArray(32) }
        val allocated = bean.getThreadAllocatedBytes(id) - before
        println("[Timeout audit] Escaped allocation calibration: $allocated bytes")
        assertTrue(allocated >= 48000L)
    }
}
