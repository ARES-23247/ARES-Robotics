package com.areslib.ftc.power

import com.areslib.hardware.HardwareRegistry
import com.areslib.hardware.CurrentSourceIO
import com.areslib.hardware.SubsystemIO
import com.areslib.hardware.actuator.MotorIO
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.VoltageSensor
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

class FtcPowerManagerZeroGcTest {
    @Test
    fun `steady state current budget and power distribution reuse registry views`() {
        val bean = ManagementFactory.getThreadMXBean()
        assumeTrue(bean is ThreadMXBean, "JVM thread allocation instrumentation is required")
        val allocationBean = bean as ThreadMXBean
        assumeTrue(allocationBean.isThreadAllocatedMemorySupported, "Thread allocation counters are required")
        if (!allocationBean.isThreadAllocatedMemoryEnabled) {
            allocationBean.isThreadAllocatedMemoryEnabled = true
        }
        val sensor = object : VoltageSensor { override var voltage: Double = 12.0 }
        val sensorList = listOf(sensor)
        val hardwareMap = object : HardwareMap() {
            @Suppress("UNCHECKED_CAST")
            override fun <T> getAll(classOrType: Class<out T>): List<T> = sensorList as List<T>
        }
        val hardwareRegistry = HardwareRegistry()
        repeat(4) { index -> hardwareRegistry.registerMotor("probe-$index", ProbeMotor()) }
        val motors = hardwareRegistry.getRegisteredMotors()
        repeat(2) { branch ->
            val first = motors[branch * 2]
            val second = motors[branch * 2 + 1]
            hardwareRegistry.registerDevice("branch-$branch", object : SubsystemIO, CurrentSourceIO {
                override val currentAmps = 2.0
                override fun includesCurrentFrom(other: CurrentSourceIO): Boolean =
                    other === this || other === first || other === second
            })
        }
        val manager = FtcPowerManager(hardwareMap, hardwareRegistry)

        try {
            repeat(50_000) { manager.update(0.02, 100L + it * 20L) }
            val threadId = Thread.currentThread().id
            var consecutiveZeroWindows = 0
            var timestamp = 1_000_100L
            var window = 0
            var totalBytes = 0L
            while (window < 10 && consecutiveZeroWindows < 2) {
                val before = allocationBean.getThreadAllocatedBytes(threadId)
                repeat(10_000) { manager.update(0.02, timestamp); timestamp += 20L }
                val bytes = allocationBean.getThreadAllocatedBytes(threadId) - before
                totalBytes += bytes
                consecutiveZeroWindows = if (bytes == 0L) consecutiveZeroWindows + 1 else 0
                window++
            }
            assertTrue(totalBytes <= 64L * 1024L, "One-time JVM overhead must remain bounded: $totalBytes bytes")
            assertTrue(
                consecutiveZeroWindows == 2,
                "FTC power updates require two consecutive zero-allocation 10,000-update windows (bytes=$totalBytes)",
            )
            println("FTC power: two consecutive zero-allocation windows; windows=$window, warmup-following overhead=$totalBytes bytes")
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
