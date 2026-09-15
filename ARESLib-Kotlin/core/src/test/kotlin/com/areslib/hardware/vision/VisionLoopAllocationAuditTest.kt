package com.areslib.hardware.vision

import com.areslib.state.VisionMeasurement
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class VisionLoopAllocationAuditTest {
    @Test fun `reused composite selection and filtering allocate no per-observation storage`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id
        var sample = 0L
        val children = List(4) { camera ->
            val observations = List(3) { slot -> VisionMeasurement(
                timestampMs = slot * 20L + camera,
                stdDevXMeters = 0.1 + camera * 0.02,
                stdDevYMeters = 0.2 + camera * 0.03,
                averageTagDistanceMeters = 1.0,
                sourceId = "camera-$camera"
            ) }
            object : VisionIO {
                override fun updateInputs(inputs: VisionIOInputs) {
                    for (slot in observations.indices) {
                        observations[slot].timestampMs = sample * 100L + slot * 20L + camera
                        observations[slot].targetPose.translation.x = if (sample % 2L == 0L) 0.0 else 0.1
                    }
                    inputs.isConnected = sample % 7L != 0L
                    inputs.measurements = observations
                }
            }
        }
        val composite = CompositeVisionIO(children)
        val inputs = VisionIOInputs()
        val config = VisionFilterConfig()
        var accepted = 0L
        fun loop(start: Long, end: Long) {
            for (i in start until end) {
                sample = i
                composite.setImuMode(4)
                composite.setOrientation(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
                composite.updateInputs(inputs)
                for (index in inputs.measurements.indices) {
                    if (VisionOutlierFilter.isValid(config, inputs.measurements[index], 0.0, 0.0, 0.0)) accepted++
                }
            }
        }
        loop(0L, 100_000L)
        val before = bean.getThreadAllocatedBytes(threadId)
        val start = System.nanoTime()
        loop(100_000L, 110_000L)
        val elapsed = System.nanoTime() - start
        val allocated = bean.getThreadAllocatedBytes(threadId) - before
        println("Vision composite/filter: $allocated bytes / 10,000 updates, ${elapsed / 10_000.0} ns/update (desktop JVM)")
        assertTrue(allocated <= 4096L, "Vision loop allocated $allocated bytes")
        assertTrue(accepted > 0L)
        composite.close()
    }
}
