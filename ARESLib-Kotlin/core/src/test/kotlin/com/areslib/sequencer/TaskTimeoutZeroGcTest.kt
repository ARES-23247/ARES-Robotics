package com.areslib.sequencer

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class TaskTimeoutZeroGcTest {
    @Test
    fun `steady state watchdog scan allocates no per-task snapshots`() {
        val allocationBean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return
        if (!allocationBean.isThreadAllocatedMemorySupported) return
        if (!allocationBean.isThreadAllocatedMemoryEnabled) {
            allocationBean.isThreadAllocatedMemoryEnabled = true
        }
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
}
