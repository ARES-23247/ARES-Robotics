package com.areslib.control.assist

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SysIdBoundaryAuditTest {
    @Test
    fun `delayed first velocity sample cannot invent motion before observation`() {
        val manager = started()
        manager.update(1100L, 2.0)
        assertEquals(0.0, manager.calculatedAcceleration)
        assertEquals(0.0, manager.accumulatedPosition)
        manager.update(1200L, 3.0)
        assertEquals(10.0, manager.calculatedAcceleration, 1e-12)
        assertEquals(0.3, manager.accumulatedPosition, 1e-12)
    }

    @Test
    fun `omitted current is unavailable rather than an assumed zero amp reading`() {
        val manager = started()
        manager.update(1000L, 0.0)
        assertFalse(manager.checkSafety(0.0, 0.0, 0.0, 1020L))
        assertFalse(manager.isActive())
        assertEquals(0.0, manager.currentVoltage)
    }

    @Test
    fun `routine updates and safety checks do not allocate per sample`() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        org.junit.jupiter.api.Assumptions.assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        val allocationBean = bean!!
        allocationBean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id
        val manager = SysIdManager()
        sample(manager, 10000)
        val before = allocationBean.getThreadAllocatedBytes(threadId)
        val total = sample(manager, 10000)
        val allocated = allocationBean.getThreadAllocatedBytes(threadId) - before
        assertTrue(total.isFinite())
        assertTrue(allocated <= 4096L, "SysId allocated $allocated bytes over 10000 samples")
    }

    private fun sample(manager: SysIdManager, count: Int): Double {
        var total = 0.0
        for (i in 0 until count) {
            val frame = i % 200
            if (frame == 0) manager.start(SysIdMechanism.CUSTOM, SysIdRoutine.QUASISTATIC, 0L, 0.0)
            val timestamp = frame * 20L
            if (manager.checkSafety(0.0, 0.0, 0.0, timestamp, 1.0)) total += manager.update(timestamp, 0.5)
        }
        return total
    }

    private fun started(timestamp: Long = 1000L) = SysIdManager().also {
        it.start(SysIdMechanism.CUSTOM, SysIdRoutine.DYNAMIC, timestamp, 0.0)
    }

    @Test
    fun `invalid supplied current aborts instead of clearing the stall watchdog`() {
        for (current in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
            val manager = started()
            manager.update(1000L, 0.0)
            assertFalse(manager.checkSafety(0.0, 0.0, 0.0, 1020L, current))
            assertFalse(manager.isActive())
            assertEquals(0.0, manager.currentVoltage)
        }
    }

    @Test
    fun `invalid limit configuration refuses start and aborts a running routine`() {
        val invalidSettings: List<(SysIdManager) -> Unit> = listOf(
            { it.maxCurrentAmps = Double.NaN }, { it.maxCurrentAmps = Double.POSITIVE_INFINITY },
            { it.maxCurrentAmps = 0.0 }, { it.stallTimeoutMs = -1L },
            { it.minPosition = Double.NaN }, { it.maxPosition = Double.POSITIVE_INFINITY },
            { it.minPosition = 2.0; it.maxPosition = 1.0 })
        for (configure in invalidSettings) {
            val initial = SysIdManager()
            configure(initial)
            initial.start(SysIdMechanism.CUSTOM, SysIdRoutine.DYNAMIC, 1000L, 0.0)
            assertFalse(initial.isActive())
            val running = started()
            running.update(1000L, 0.0)
            configure(running)
            assertEquals(0.0, running.update(1020L, 0.0))
            assertFalse(running.isActive())
        }
    }

    @Test
    fun `elapsed time overflow cannot bypass the routine timeout`() {
        val update = started(Long.MIN_VALUE)
        assertEquals(0.0, update.update(Long.MAX_VALUE, 0.0))
        assertFalse(update.isActive())
        val safety = started(Long.MIN_VALUE)
        assertFalse(safety.checkSafety(0.0, 0.0, 0.0, Long.MAX_VALUE, 0.0))
    }

    @Test
    fun `safety and update calls share monotonic observation ordering`() {
        val safety = started()
        assertTrue(safety.checkSafety(0.0, 0.0, 0.0, 1200L, 0.0))
        assertFalse(safety.checkSafety(0.0, 0.0, 0.0, 1100L, 0.0))
        val update = started()
        assertTrue(update.checkSafety(0.0, 0.0, 0.0, 1200L, 0.0))
        assertEquals(0.0, update.update(1100L, 0.0))
        assertFalse(update.isActive())
    }

    @Test
    fun `stall timeout works at negative clock epochs and at zero duration`() {
        val negativeEpoch = started(-1000L)
        assertTrue(negativeEpoch.checkSafety(0.0, 0.0, 0.0, -900L, 45.0))
        assertFalse(negativeEpoch.checkSafety(0.0, 0.0, 0.0, -700L, 45.0))
        val immediate = started()
        immediate.stallTimeoutMs = 0L
        assertFalse(immediate.checkSafety(0.0, 0.0, 0.0, 1000L, 45.0))
    }

    @Test
    fun `duplicate timestamps cannot rewrite the velocity used for differentiation`() {
        val manager = started()
        manager.update(1000L, 1.0)
        manager.update(1100L, 2.0)
        val position = manager.accumulatedPosition
        manager.update(1100L, 100.0)
        assertEquals(position, manager.accumulatedPosition)
        manager.update(1200L, 3.0)
        assertEquals(10.0, manager.calculatedAcceleration, 1e-12)
    }

    @Test
    fun `nonrepresentable kinematic samples abort without publishing invalid statistics`() {
        val manager = started()
        manager.update(1000L, 0.0)
        assertEquals(0.0, manager.update(1001L, Double.MAX_VALUE))
        assertFalse(manager.isActive())
        assertEquals(0.0, manager.calculatedAcceleration)
        assertEquals(0.0, manager.accumulatedPosition)
    }
}
