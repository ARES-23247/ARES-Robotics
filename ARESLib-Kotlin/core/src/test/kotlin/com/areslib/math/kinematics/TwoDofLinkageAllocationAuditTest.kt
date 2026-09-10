package com.areslib.math.kinematics

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TwoDofLinkageAllocationAuditTest {
    @Test
    fun `plant and buffered geometry allocate no scratch after warmup`() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!
        bean.isThreadAllocatedMemoryEnabled = true
        val p = TwoDofLinkageParameters(0.4, 0.3, 1.0, 0.5)
        val arm = TwoDofLinkageKinematics(p)
        val plant = TwoDofLinkagePlant(TwoDofLinkagePlantParameters(p, 1.0, 1.0))
        val output = DoubleArray(4)
        var checksum = 0.0
        var iterations = 0
        fun tick() {
            if (iterations++ % 100 == 0) plant.reset(0.4, -0.3)
            plant.step(2.0, 0.5, 0.02)
            val q1 = plant.joint1PositionRad
            val q2 = plant.joint2PositionRad
            arm.forwardKinematics(q1, q2, output)
            if (arm.isReachable(output[0], output[1])) checksum += output[0]
            arm.jacobian(q1, q2, output)
            checksum += output[3]
            arm.gravityTorque(q1, q2, output)
            checksum += output[0]
            if (arm.isNearSingularity(q1, q2)) checksum += 1.0
        }
        repeat(50_000) { tick() }
        val thread = Thread.currentThread().id
        var consecutive = 0
        var bytes = -1L
        for (window in 0 until 10) {
            val before = bean.getThreadAllocatedBytes(thread)
            repeat(10_000) { tick() }
            bytes = bean.getThreadAllocatedBytes(thread) - before
            consecutive = if (bytes == 0L) consecutive + 1 else 0
            if (consecutive == 2) break
        }
        assertEquals(2, consecutive, "Last 10,000-tick window allocated $bytes bytes")
        assertTrue(checksum.isFinite() && checksum != 0.0)
    }
}
