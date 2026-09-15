package com.areslib.math

import com.areslib.control.feedback.GravityFeedforward
import com.areslib.math.kinematics.KinematicsMath
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class ScalarMotionAllocationTest {
    @Volatile private var checksum = 0.0
    @Volatile private var escaped: ByteArray? = null
    private fun bean(): ThreadMXBean {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        return requireNotNull(bean).apply { isThreadAllocatedMemoryEnabled = true }
    }
    private fun batch() {
        repeat(10_000) { i ->
            if (i % 2 == 0) {
                checksum = KinematicsMath.finalVelocity(3.0 + i * 1e-6, 2.0, 4.0)
                checksum = GravityFeedforward.calculateAdaptiveElevator(1.0, i, 0.1)
                checksum = GravityFeedforward.calculateArm(i * 1e-4, 1.0)
            } else {
                checksum = KinematicsMath.finalVelocity(1e200 + i * 1e185, -1e200, 0.25e200)
                checksum = GravityFeedforward.calculateAdaptiveElevator(1e-300, i, Double.MAX_VALUE)
                checksum = GravityFeedforward.calculateArm(Math.scalb(i + 1.0, 1010), 1.0, -Double.MAX_VALUE)
            }
        }
    }

    @Test fun `ordinary and scaled scalar paths have no sustained allocation`() {
        val bean = bean(); val id = Thread.currentThread().id
        repeat(10) { batch() }
        val samples = LongArray(5)
        for (i in samples.indices) {
            val before = bean.getThreadAllocatedBytes(id); batch()
            samples[i] = bean.getThreadAllocatedBytes(id) - before
        }
        println("[Scalar allocation audit] Five batches of 10000 mixed calculations: ${samples.contentToString()} bytes")
        assertTrue(checksum.isFinite())
        assertTrue(samples.min() <= 256L)
        assertTrue(samples.sum() <= 4096L)
    }

    @Test fun `allocation counter sees escaped array control`() {
        val bean = bean(); val id = Thread.currentThread().id
        repeat(2000) { escaped = ByteArray(32) }
        val before = bean.getThreadAllocatedBytes(id)
        repeat(1000) { escaped = ByteArray(32) }
        val allocated = bean.getThreadAllocatedBytes(id) - before
        println("[Scalar allocation audit] Escaped array calibration: $allocated bytes")
        assertTrue(allocated >= 48_000L)
    }
}
