package com.areslib.ftc.power

import com.areslib.hardware.HardwareRegistry
import com.areslib.hardware.actuator.MotorIO
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.VoltageSensor
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FtcPowerManagerZeroGcTest {
    @AfterEach
    fun clearRegistry() = HardwareRegistry.clear()

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
        repeat(4) { index -> HardwareRegistry.registerMotor("probe-$index", ProbeMotor()) }
        val manager = FtcPowerManager(hardwareMap)

        repeat(2_000) { manager.update(0.02, 100L + it * 20L) }
        val threadId = Thread.currentThread().id
        val before = allocationBean.getThreadAllocatedBytes(threadId)
        repeat(10_000) { manager.update(0.02, 100_000L + it * 20L) }
        val allocatedBytes = allocationBean.getThreadAllocatedBytes(threadId) - before

        assertTrue(
            allocatedBytes <= 256L,
            "FTC power updates must not allocate registry iterators (allocated $allocatedBytes bytes)",
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
