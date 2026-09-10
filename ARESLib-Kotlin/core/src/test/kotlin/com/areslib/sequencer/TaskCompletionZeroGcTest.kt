package com.areslib.sequencer

import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class TaskCompletionZeroGcTest {
    @Test fun `completion gates reuse registry storage on active updates`() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        requireNotNull(bean).isThreadAllocatedMemoryEnabled = true
        val state = RobotState()
        val task = object : Task {
            override val name = "completion-allocation"
            override fun isCompleted(state: RobotState, elapsedMs: Long) = false
        }
        RobotClock.useMockTime(0)
        try {
            task.withTimeout(Long.MAX_VALUE); task.initialize(state)
            repeat(10_000) { task.completionReady(state, 1) }
            val id = Thread.currentThread().id
            val before = bean.getThreadAllocatedBytes(id)
            repeat(10_000) { task.completionReady(state, 1) }
            val allocated = bean.getThreadAllocatedBytes(id) - before
            println("[Completion audit] 10000 completion gates allocated $allocated bytes")
            assertTrue(allocated <= 256L, "Completion gates allocated $allocated bytes")
        } finally {
            task.reset(); RobotClock.useSystemTime()
        }
    }
}
