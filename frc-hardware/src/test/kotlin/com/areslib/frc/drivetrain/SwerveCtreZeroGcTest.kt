package com.areslib.frc.drivetrain

import com.areslib.state.DriveState
import com.ctre.phoenix6.swerve.SwerveRequest
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SwerveCtreZeroGcTest {
    @Test
    fun `scaled periodic writer reuses mutable CTRE requests`() {
        val allocationBean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return
        if (!allocationBean.isThreadAllocatedMemorySupported) return
        if (!allocationBean.isThreadAllocatedMemoryEnabled) {
            allocationBean.isThreadAllocatedMemoryEnabled = true
        }
        var observed: SwerveRequest? = null
        val writer = SwerveCtreSpeedRequestWriter { request -> observed = request }
        val state = DriveState(
            xVelocityMetersPerSecond = 2.0,
            yVelocityMetersPerSecond = -1.0,
            angularVelocityRadiansPerSecond = 0.5,
            isFieldCentric = false,
        )

        repeat(2_000) { writer.write(state, 0.75) }
        val threadId = Thread.currentThread().id

        // ThreadMXBean includes one-time JVM profiling/OSR bookkeeping on some JDK builds. Compare
        // two differently sized windows: a real per-write allocation scales with call count, while
        // fixed compiler bookkeeping does not.
        val shortBefore = allocationBean.getThreadAllocatedBytes(threadId)
        repeat(10_000) { writer.write(state, 0.75) }
        val shortWindowBytes = allocationBean.getThreadAllocatedBytes(threadId) - shortBefore
        val longBefore = allocationBean.getThreadAllocatedBytes(threadId)
        repeat(100_000) { writer.write(state, 0.75) }
        val longWindowBytes = allocationBean.getThreadAllocatedBytes(threadId) - longBefore

        assertTrue(observed is SwerveRequest.ApplyRobotSpeeds)
        assertTrue(
            longWindowBytes <= shortWindowBytes * 2L + 1_024L,
            "Scaled swerve writes must have zero per-call allocation growth " +
                "(10k=$shortWindowBytes, 100k=$longWindowBytes)",
        )
    }
}
