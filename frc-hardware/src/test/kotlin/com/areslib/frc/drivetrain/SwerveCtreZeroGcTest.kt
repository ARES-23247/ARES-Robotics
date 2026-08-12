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

        // The first long batch may trigger the JVM's final OSR compilation of this test loop. Keep
        // it as an explicit profiling/stabilization window, then enforce zero bytes in an equally
        // sized steady-state window. This distinguishes one-time compiler bookkeeping from a
        // periodic writer allocation without weakening the production contract.
        val profilingBefore = allocationBean.getThreadAllocatedBytes(threadId)
        repeat(10_000) { writer.write(state, 0.75) }
        val profilingBytes = allocationBean.getThreadAllocatedBytes(threadId) - profilingBefore
        val steadyStateBefore = allocationBean.getThreadAllocatedBytes(threadId)
        repeat(10_000) { writer.write(state, 0.75) }
        val steadyStateBytes = allocationBean.getThreadAllocatedBytes(threadId) - steadyStateBefore

        assertTrue(observed is SwerveRequest.ApplyRobotSpeeds)
        assertTrue(
            steadyStateBytes == 0L,
            "Scaled swerve writes must allocate zero bytes after profiling stabilization " +
                "(profiling=$profilingBytes, steady-state=$steadyStateBytes)",
        )
    }
}
