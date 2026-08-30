package com.areslib.ftc.power

import com.areslib.hardware.HardwareRegistry
import com.areslib.hardware.actuator.MotorIO
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.VoltageSensor
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FtcPowerManagerZeroGcTest {
    @Test
    fun `steady state current budget and power distribution reuse registry views`() {
        val allocationBean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return
        if (!allocationBean.isThreadAllocatedMemorySupported) return
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
        val manager = FtcPowerManager(hardwareMap, hardwareRegistry)

        repeat(2_000) { manager.update(0.02, 100L + it * 20L) }
        val threadId = Thread.currentThread().id
        val profilingBefore = allocationBean.getThreadAllocatedBytes(threadId)
        repeat(10_000) { manager.update(0.02, 100_000L + it * 20L) }
        val profilingBytes = allocationBean.getThreadAllocatedBytes(threadId) - profilingBefore
        val steadyStateBefore = allocationBean.getThreadAllocatedBytes(threadId)
        repeat(10_000) { manager.update(0.02, 300_000L + it * 20L) }
        val steadyStateBytes = allocationBean.getThreadAllocatedBytes(threadId) - steadyStateBefore

        assertTrue(
            profilingBytes <= 64L * 1024L,
            "FTC power profiling must have bounded one-time JVM overhead " +
                "(profiling=$profilingBytes bytes)",
        )
        assertTrue(
            steadyStateBytes == 0L,
            "FTC power updates must allocate zero bytes after profiling stabilization " +
                "(profiling=$profilingBytes, steady-state=$steadyStateBytes)",
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
