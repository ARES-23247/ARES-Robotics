package com.areslib.control.profile

import com.areslib.control.feedback.ProfiledPIDController
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrapezoidProfileAllocationTest {
    @Test
    fun `overspeed and profiled feedback updates allocate no heap after warmup`() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!
        bean.isThreadAllocatedMemoryEnabled = true
        val profile = TrapezoidProfile()
        val state = TrapezoidProfile.State()
        val goal = TrapezoidProfile.State(100.0, 0.0)
        val constraints = TrapezoidProfile.Constraints(1.0, 1.0)
        val controller = ProfiledPIDController(1.0, 0.0, 0.0, constraints)
        controller.reset(0.0)
        controller.setGoal(10.0)
        var output = 0.0
        fun update() {
            state.position = 0.0
            state.velocity = 2.0
            profile.calculate(0.02, state, goal, constraints, state)
            output = controller.calculate(controller.currentState.position, 0.02)
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
        assertEquals(1.98, state.velocity, 1e-12)
        assertTrue(output.isFinite())
    }
}
