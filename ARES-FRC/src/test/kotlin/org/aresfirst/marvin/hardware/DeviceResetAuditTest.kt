package org.aresfirst.marvin.hardware

import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class DeviceResetAuditTest {
    private class Device(var reset: Boolean = false) {
        var reads = 0
        fun consumeReset(): Boolean {
            reads++
            val result = reset
            reset = false
            return result
        }
    }

    @Test fun `every reset indicator is consumed exactly once even after an earlier reset`() {
        for (count in listOf(1, 2, 4)) for (mask in 0 until (1 shl count)) {
            val devices = Array(count) { Device((mask and (1 shl it)) != 0) }
            assertEquals(mask != 0, anyDeviceResetOccurred(devices) { it.consumeReset() })
            assertTrue(devices.all { it.reads == 1 && !it.reset })
            assertFalse(anyDeviceResetOccurred(devices) { it.consumeReset() })
            assertTrue(devices.all { it.reads == 2 })
        }
    }

    @Test fun `empty group does no sampling and device failures reach the caller`() {
        assertFalse(anyDeviceResetOccurred(emptyArray<Device>()) { error("must not sample") })
        val failure = IllegalStateException("device read failed")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            anyDeviceResetOccurred(arrayOf(Device())) { throw failure }
        })
    }

    @Test fun `reused four-device reset group allocates no bytes in steady state`() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val devices = Array(4) { Device() }
        val thread = Thread.currentThread().id
        // Warm the same sampling/profiler call sites that are measured, not a separate inline loop.
        repeat(5) { allocatedForResetBatch(bean, thread, devices) }
        repeat(2) { batch ->
            assertEquals(0L, allocatedForResetBatch(bean, thread, devices), "Steady-state batch $batch")
        }
        assertTrue(devices.all { it.reads == 700_000 })
    }

    private fun allocatedForResetBatch(
        bean: com.sun.management.ThreadMXBean, thread: Long, devices: Array<Device>,
    ): Long {
        val before = bean.getThreadAllocatedBytes(thread)
        repeat(100_000) { anyDeviceResetOccurred(devices) { it.consumeReset() } }
        return bean.getThreadAllocatedBytes(thread) - before
    }
}
