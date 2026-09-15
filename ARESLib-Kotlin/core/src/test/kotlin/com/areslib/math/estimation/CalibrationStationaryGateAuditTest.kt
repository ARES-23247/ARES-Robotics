package com.areslib.math.estimation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalibrationStationaryGateAuditTest {
    @Test
    fun `stationary gate does not allocate during periodic observations`() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean()
        org.junit.jupiter.api.Assumptions.assumeTrue(bean is com.sun.management.ThreadMXBean)
        val allocations = bean as com.sun.management.ThreadMXBean
        org.junit.jupiter.api.Assumptions.assumeTrue(allocations.isThreadAllocatedMemorySupported)
        allocations.isThreadAllocatedMemoryEnabled = true
        val gate = StationaryCalibrationGate()
        var ready = false
        for (i in 0L..9999L) ready = gate.update(i, true, 0.0, 0.0, true, i)
        assertTrue(ready)
        val thread = Thread.currentThread().id
        val before = allocations.getThreadAllocatedBytes(thread)
        for (i in 10000L..19999L) ready = gate.update(i, true, 0.0, 0.0, true, i)
        val allocated = allocations.getThreadAllocatedBytes(thread) - before
        assertTrue(ready)
        assertTrue(allocated <= 4096L, "Gate allocated $allocated bytes across 10000 observations")
    }

    @Test
    fun `requires uninterrupted fresh neutral observations and restarts after interruption`() {
        val gate = StationaryCalibrationGate()
        for (time in 0L..490L step 10L) assertFalse(gate.update(time, true, 0.0, 0.0, true, time))
        assertTrue(gate.update(500L, true, 0.0, 0.0, true, 500L))
        assertFalse(gate.update(510L, false, 0.0, 0.0, true, 510L))
        assertFalse(gate.update(520L, true, 0.0, 0.0, true, 520L))
        assertFalse(gate.update(1020L, true, 0.0, 0.0, true, 1020L)) // unobserved gap
        for (time in 1030L..1510L step 10L) assertFalse(gate.update(time, true, 0.0, 0.0, true, time))
        assertTrue(gate.update(1520L, true, 0.0, 0.0, true, 1520L))
        gate.reset()
        assertFalse(gate.update(1530L, true, 0.0, 0.0, true, 1530L))
    }

    @Test
    fun `unknown stale future moving and invalid feedback fail closed`() {
        val gate = StationaryCalibrationGate(dwellMs = 0L)
        assertFalse(gate.update(1000L, true, 0.0, 0.0, false, 1000L))
        assertFalse(gate.update(1000L, true, 0.0, 0.0, true, 899L))
        assertFalse(gate.update(1000L, true, 0.0, 0.0, true, 1001L))
        assertFalse(gate.update(Long.MAX_VALUE, true, 0.0, 0.0, true, Long.MIN_VALUE))
        assertFalse(gate.update(1000L, true, Double.NaN, 0.0, true, 1000L))
        assertFalse(gate.update(1000L, true, 0.0, Double.POSITIVE_INFINITY, true, 1000L))
        assertFalse(gate.update(1000L, true, 0.031, 0.0, true, 1000L))
        assertFalse(gate.update(1000L, true, 0.0, -0.051, true, 1000L))
        assertTrue(gate.update(1000L, true, 0.03, -0.05, true, 900L))
    }

    @Test
    fun `rollback restarts dwell including clocks near integer boundaries`() {
        for (origin in longArrayOf(0L, Long.MIN_VALUE, Long.MAX_VALUE - 500L)) {
            val gate = StationaryCalibrationGate(dwellMs = 100L)
            assertFalse(gate.update(origin, true, 0.0, 0.0, true, origin))
            assertTrue(gate.update(origin + 100L, true, 0.0, 0.0, true, origin + 100L))
            assertFalse(gate.update(origin + 50L, true, 0.0, 0.0, true, origin + 50L))
            assertTrue(gate.update(origin + 150L, true, 0.0, 0.0, true, origin + 150L))
        }
    }
}
