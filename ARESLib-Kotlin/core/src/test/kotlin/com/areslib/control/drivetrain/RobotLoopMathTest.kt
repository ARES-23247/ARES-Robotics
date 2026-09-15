package com.areslib.control.drivetrain

import com.areslib.control.feedback.PIDController
import com.areslib.math.geometry.ChassisSpeeds
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RobotLoopMathTest {
    @Volatile private var retainedOutput = ChassisSpeeds()

    @Test
    fun `reused output is neutral after an invalid frame and old results retain ownership`() {
        val controller = HolonomicDriveController(PIDController(1.0, 0.0, 0.0),
            PIDController(1.0, 0.0, 0.0), PIDController(1.0, 0.0, 0.0))
        val previous = controller.calculateDirect(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.02)
        val out = ChassisSpeeds(5.0, 6.0, 7.0)
        controller.calculateInto(out, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0, Double.NaN)
        assertEquals(ChassisSpeeds(), out)
        controller.calculateDirect(0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.02)
        assertEquals(1.0, previous.vxMetersPerSecond)
    }

    @Test
    fun `trajectory control output reuse has a bounded allocation budget`() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!
        bean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id
        val controller = HolonomicDriveController(PIDController(1.0, 0.0, 0.0),
            PIDController(1.0, 0.0, 0.0), PIDController(1.0, 0.0, 0.0))
        val out = ChassisSpeeds()
        repeat(30_000) {
            retainedOutput = controller.calculateDirect(0.0, 0.0, 0.2, 1.0, 0.5, 0.3, 1.0, 0.02, 0.1)
            controller.calculateInto(out, 0.0, 0.0, 0.2, 1.0, 0.5, 0.3, 1.0, 0.02, 0.1)
            retainedOutput = out
        }
        val samples = LongArray(20_000)
        val allocatedBefore = bean.getThreadAllocatedBytes(threadId)
        for (i in samples.indices) {
            val start = System.nanoTime()
            controller.calculateInto(out, 0.0, 0.0, 0.2, 1.0, 0.5, 0.3, 1.0, 0.02, 0.1)
            retainedOutput = out
            samples[i] = System.nanoTime() - start
        }
        val reusedBytes = bean.getThreadAllocatedBytes(threadId) - allocatedBefore
        val owningBefore = bean.getThreadAllocatedBytes(threadId)
        repeat(samples.size) {
            retainedOutput = controller.calculateDirect(0.0, 0.0, 0.2, 1.0, 0.5, 0.3, 1.0, 0.02, 0.1)
        }
        val owningBytes = bean.getThreadAllocatedBytes(threadId) - owningBefore
        samples.sort()
        println("[Loop audit] ${samples.size} controller calls: reused=$reusedBytes bytes, " +
            "owned=$owningBytes bytes; reused latency ns p50=${samples[samples.size / 2]}, " +
            "p95=${samples[samples.size * 95 / 100]}, p99=${samples[samples.size * 99 / 100]}")
        assertTrue(reusedBytes <= 4096L, "Reused output allocated $reusedBytes bytes")
        assertTrue(out.vxMetersPerSecond.isFinite())
    }

    @Test
    fun `curve speed limit preserves direction and bounds reverse speed`() {
        for (curvature in doubleArrayOf(-2.0, 2.0, -1e-5, 1e-5)) {
            for (velocity in doubleArrayOf(-4.0, 4.0)) {
                val controller = HolonomicDriveController(PIDController(0.0, 0.0, 0.0),
                    PIDController(0.0, 0.0, 0.0), PIDController(0.0, 0.0, 0.0))
                val output = controller.calculateDirect(0.0, 0.0, 0.0, 1.0, 0.0, 0.0,
                    velocity, 0.02, 0.0, curvature, kotlin.math.abs(curvature))
                assertEquals(if (velocity < 0.0) -1.0 else 1.0, output.vxMetersPerSecond, 1e-12)
            }
        }
    }

    @Test
    fun `discretized translation stays within the controller output limit while turning`() {
        val controller = HolonomicDriveController(PIDController(0.0, 0.0, 0.0),
            PIDController(0.0, 0.0, 0.0), PIDController(10.0, 0.0, 0.0))
        val output = controller.calculateDirect(0.0, 0.0, 0.0, 1.0, 0.0, 1.0,
            4.0, 0.02, 0.0)
        assertTrue(hypot(output.vxMetersPerSecond, output.vyMetersPerSecond) <= 4.0 + 1e-12)
        assertEquals(10.0, output.omegaRadiansPerSecond, 1e-12)
    }
}
