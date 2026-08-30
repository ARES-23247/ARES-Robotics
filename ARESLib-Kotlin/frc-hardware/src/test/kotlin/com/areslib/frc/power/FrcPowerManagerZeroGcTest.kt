package com.areslib.frc.power

import com.areslib.hardware.HardwareRegistry
import com.areslib.hardware.actuator.MotorIO
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrcPowerManagerZeroGcTest {
    @Test
    fun `steady state power distribution uses indexed registry traversal`() {
        val allocationBean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return
        if (!allocationBean.isThreadAllocatedMemorySupported) return
        if (!allocationBean.isThreadAllocatedMemoryEnabled) {
            allocationBean.isThreadAllocatedMemoryEnabled = true
        }
        val hardwareRegistry = HardwareRegistry()
        repeat(4) { index -> hardwareRegistry.registerMotor("probe-$index", ProbeMotor()) }
        val manager = FrcPowerManager(hardwareRegistry).apply {
            batteryVoltageSupplier = java.util.function.DoubleSupplier { 12.0 }
            totalCurrentSupplier = java.util.function.DoubleSupplier { 0.0 }
            brownedOutSupplier = java.util.function.BooleanSupplier { false }
        }

        repeat(2_000) { manager.update(0.02, it * 20L) }
        val threadId = Thread.currentThread().id
        val shortBefore = allocationBean.getThreadAllocatedBytes(threadId)
        repeat(10_000) { manager.update(0.02, it * 20L) }
        val shortWindowBytes = allocationBean.getThreadAllocatedBytes(threadId) - shortBefore
        val longBefore = allocationBean.getThreadAllocatedBytes(threadId)
        repeat(100_000) { manager.update(0.02, it * 20L) }
        val longWindowBytes = allocationBean.getThreadAllocatedBytes(threadId) - longBefore

        assertTrue(
            longWindowBytes <= shortWindowBytes * 2L + 1_024L,
            "FRC power updates must have zero per-call allocation growth " +
                "(10k=$shortWindowBytes, 100k=$longWindowBytes)",
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
