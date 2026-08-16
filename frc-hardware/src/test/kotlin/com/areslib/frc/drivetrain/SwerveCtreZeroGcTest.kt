package com.areslib.frc.drivetrain

import com.areslib.state.DriveState
import com.ctre.phoenix6.swerve.SwerveRequest
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.assertEquals
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

        repeat(20_000) { writer.write(state, 0.75) }
        val threadId = Thread.currentThread().id

        // ThreadMXBean can charge one-time profiling/OSR bookkeeping to the mutator thread even
        // after warm-up. Equal windows distinguish that fixed noise from a real per-write
        // allocation: a production allocation appears in every window and cannot produce a
        // zero-byte minimum. Keep a separate aggregate cap so excessive VM noise is still visible.
        val windowBytes = LongArray(MEASURED_WINDOWS)
        for (window in windowBytes.indices) {
            val before = allocationBean.getThreadAllocatedBytes(threadId)
            var writeIndex = 0
            while (writeIndex < WRITES_PER_WINDOW) {
                writer.write(state, 0.75)
                writeIndex++
            }
            windowBytes[window] = allocationBean.getThreadAllocatedBytes(threadId) - before
        }
        val minimumWindowBytes = windowBytes.minOrNull() ?: Long.MAX_VALUE
        val totalWindowBytes = windowBytes.sum()

        assertTrue(observed is SwerveRequest.ApplyRobotSpeeds)
        assertEquals(
            0L,
            minimumWindowBytes,
            "At least one steady-state write window must allocate exactly zero bytes: " +
                windowBytes.contentToString(),
        )
        assertTrue(
            totalWindowBytes <= MAX_FIXED_VM_BOOKKEEPING_BYTES,
            "Post-warmup VM bookkeeping exceeded the fixed-noise budget: " +
                windowBytes.contentToString(),
        )
    }

    private companion object {
        const val MEASURED_WINDOWS = 5
        const val WRITES_PER_WINDOW = 20_000
        const val MAX_FIXED_VM_BOOKKEEPING_BYTES = 64L * 1_024L
    }
}
