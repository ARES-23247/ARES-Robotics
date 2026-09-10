package com.areslib.math.geometry

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class Geometry3dAllocationTest {
    private val rotation = Rotation3d()
    private val vector = Translation3d()
    private val pose = Pose3d(rotation = rotation)
    private val transform = Transform3d(Translation3d(1.0, 2.0, 3.0), Rotation3d(0.1, 0.2, 0.3))
    @Volatile private var checksum = 0.0
    @Volatile private var escaped: Pose3d? = null

    private fun inPlaceBatch() {
        repeat(10_000) { i ->
            rotation.setEulerAngles(i * 1e-4, i * 2e-4, i * 3e-4)
            checksum = rotation.x; checksum = rotation.y; checksum = rotation.z
            vector.x = if (i % 2 == 0) i * 0.1 else 1e200
            vector.y = i * -0.2
            checksum = vector.norm
        }
    }

    private fun ownedResultBatch() {
        repeat(10_000) { i ->
            pose.translation.x = i * 0.1
            escaped = pose.transformBy(transform)
        }
    }

    @Test fun `Euler updates and scalar reads reuse storage while transform results remain owned`() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        requireNotNull(bean).isThreadAllocatedMemoryEnabled = true
        val id = Thread.currentThread().id
        repeat(10) { inPlaceBatch(); ownedResultBatch() }
        val samples = LongArray(5)
        for (i in samples.indices) {
            val before = bean.getThreadAllocatedBytes(id); inPlaceBatch()
            samples[i] = bean.getThreadAllocatedBytes(id) - before
        }
        val before = bean.getThreadAllocatedBytes(id); ownedResultBatch()
        val objects = bean.getThreadAllocatedBytes(id) - before
        println("[3D geometry allocation audit] Scalar batches: ${samples.contentToString()}; 10000 escaping poses: $objects bytes")
        assertTrue(checksum.isFinite()); assertTrue(escaped!!.x.isFinite())
        assertTrue(samples.min() <= 256 && samples.sum() <= 4096)
        // Owned translation and quaternion alone carry seven doubles, before headers/wrappers.
        assertTrue(objects >= 560_000)
    }
}
