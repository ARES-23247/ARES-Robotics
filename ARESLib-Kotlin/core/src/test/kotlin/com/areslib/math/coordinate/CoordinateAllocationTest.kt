package com.areslib.math.coordinate

import com.areslib.math.geometry.Translation2d
import com.areslib.math.kinematics.OdometryMath
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class CoordinateAllocationTest {
    @Volatile private var checksum = 0.0
    @Volatile private var escaped: Translation2d? = null
    private fun scalarBatch() {
        repeat(10_000) { i ->
            checksum = OdometryMath.calculateDeltaX(i * 1e-4, i * 0.1, -i * 0.1)
            checksum = OdometryMath.calculateDeltaY(i * 1e-4, i * 0.1, -i * 0.1)
        }
    }
    private fun objectBatch() {
        repeat(10_000) { i -> escaped = OdometryMath.calculateDeltaPose(i * 1e-4, i * 0.1, -i * 0.1) }
    }
    @Test fun `allocation distinguishes scalar rotation from owned vector results`() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        requireNotNull(bean).isThreadAllocatedMemoryEnabled = true
        val id = Thread.currentThread().id
        repeat(10) { scalarBatch() }; objectBatch()
        val samples = LongArray(5)
        for (i in samples.indices) {
            val before = bean.getThreadAllocatedBytes(id); scalarBatch()
            samples[i] = bean.getThreadAllocatedBytes(id) - before
        }
        val before = bean.getThreadAllocatedBytes(id); objectBatch()
        val objects = bean.getThreadAllocatedBytes(id) - before
        println("[Coordinate allocation audit] Five scalar batches: ${samples.contentToString()}; 10000 escaping vectors: $objects bytes")
        assertTrue(checksum.isFinite()); assertTrue(escaped!!.x.isFinite())
        assertTrue(samples.min() <= 256L && samples.sum() <= 4096L)
        // Two Double fields alone require 16 bytes per escaping result, before object headers.
        assertTrue(objects >= 160_000L)
    }
}
