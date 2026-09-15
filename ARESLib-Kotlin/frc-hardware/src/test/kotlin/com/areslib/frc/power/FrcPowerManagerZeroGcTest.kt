package com.areslib.frc.power

import com.areslib.hardware.HardwareRegistry
import com.areslib.hardware.CurrentSourceIO
import com.areslib.hardware.SubsystemIO
import com.areslib.hardware.actuator.MotorIO
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

class FrcPowerManagerZeroGcTest {
    @Test
    fun `steady state power distribution uses indexed registry traversal`() {
        val bean = ManagementFactory.getThreadMXBean()
        assumeTrue(bean is ThreadMXBean, "JVM allocation instrumentation is required")
        val allocationBean = bean as ThreadMXBean
        assumeTrue(allocationBean.isThreadAllocatedMemorySupported, "Thread allocation counters are required")
        if (!allocationBean.isThreadAllocatedMemoryEnabled) {
            allocationBean.isThreadAllocatedMemoryEnabled = true
        }
        val hardwareRegistry = HardwareRegistry()
        repeat(4) { index -> hardwareRegistry.registerMotor("probe-$index", ProbeMotor()) }
        val motors = hardwareRegistry.getRegisteredMotors()
        repeat(2) { branch ->
            val first = motors[branch * 2]
            val second = motors[branch * 2 + 1]
            hardwareRegistry.registerDevice("branch-$branch", object : SubsystemIO, CurrentSourceIO {
                override val currentAmps = 2.0
                override fun includesCurrentFrom(other: CurrentSourceIO) =
                    other === this || other === first || other === second
            })
        }
        var usePdh = true
        val manager = FrcPowerManager(hardwareRegistry).apply {
            batteryVoltageSupplier = java.util.function.DoubleSupplier { 12.0 }
            totalCurrentSupplier = java.util.function.DoubleSupplier { if (usePdh) 8.0 else Double.NaN }
            brownedOutSupplier = java.util.function.BooleanSupplier { false }
        }

        try {
            repeat(50_000) { usePdh = !usePdh; manager.update(0.02, it * 20L) }
            val threadId = Thread.currentThread().id
            var consecutiveZero = 0
            var windows = 0
            var totalBytes = 0L
            while (windows < 10 && consecutiveZero < 2) {
                val before = allocationBean.getThreadAllocatedBytes(threadId)
                repeat(10_000) { usePdh = !usePdh; manager.update(0.02, it * 20L) }
                val bytes = allocationBean.getThreadAllocatedBytes(threadId) - before
                totalBytes += bytes
                consecutiveZero = if (bytes == 0L) consecutiveZero + 1 else 0
                windows++
            }
            assertTrue(totalBytes <= 64L * 1024L, "One-time overhead must be bounded: $totalBytes bytes")
            assertTrue(consecutiveZero == 2, "PDH/fallback loops require two consecutive zero-allocation windows")
            println("FRC PDH/fallback: two consecutive zero-allocation 10,000-update windows; windows=$windows, overhead=$totalBytes bytes")
        } finally { hardwareRegistry.clear() }
    }

    private class ProbeMotor : MotorIO {
        override var power: Double = 0.0
        override val velocity: Double = 0.0
        override val position: Double = 0.0
        override val currentAmps: Double = 0.0
        override fun resetEncoder() = Unit
    }
}
