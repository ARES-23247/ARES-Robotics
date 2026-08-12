package com.areslib.frc.power

import com.areslib.hardware.HardwareRegistry
import com.areslib.hardware.actuator.MotorIO
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrcPowerManagerZeroGcTest {
    @AfterEach
    fun clearRegistry() = HardwareRegistry.clear()

    @Test
    fun `steady state power distribution uses indexed registry traversal`() {
        val allocationBean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return
        if (!allocationBean.isThreadAllocatedMemorySupported) return
        if (!allocationBean.isThreadAllocatedMemoryEnabled) {
            allocationBean.isThreadAllocatedMemoryEnabled = true
        }
        repeat(4) { index -> HardwareRegistry.registerMotor("probe-$index", ProbeMotor()) }
        val manager = FrcPowerManager().apply {
            batteryVoltageSupplier = java.util.function.DoubleSupplier { 12.0 }
            totalCurrentSupplier = java.util.function.DoubleSupplier { 0.0 }
            brownedOutSupplier = java.util.function.BooleanSupplier { false }
        }

        repeat(2_000) { manager.update(0.02, it * 20L) }
        val threadId = Thread.currentThread().id
        val before = allocationBean.getThreadAllocatedBytes(threadId)
        repeat(10_000) { manager.update(0.02, it * 20L) }
        val allocatedBytes = allocationBean.getThreadAllocatedBytes(threadId) - before

        assertTrue(
            allocatedBytes <= 256L,
            "FRC power updates must not allocate motor-list iterators (allocated $allocatedBytes bytes)",
        )
    }

    private class ProbeMotor : MotorIO {
        override var power: Double = 0.0
        override val velocity: Double = 0.0
        override val position: Double = 0.0
        override val currentAmps: Double = 0.0
        override fun resetEncoder() = Unit
    }
}
