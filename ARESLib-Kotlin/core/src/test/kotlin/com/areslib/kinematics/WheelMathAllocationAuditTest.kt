package com.areslib.kinematics

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WheelMathAllocationAuditTest {
    @Test fun `buffered wheel conversions and normalization use no periodic scratch allocation`() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!; bean.isThreadAllocatedMemoryEnabled = true
        val dd = DifferentialDriveKinematics(0.155)
        val mecanum = MecanumKinematics(0.4, 0.3)
        val unscaled = MecanumWheelSpeeds(0.4, -0.3, 0.2, 0.1)
        val swerve = SwerveKinematics(emptyList())
        val states = arrayOf(SwerveModuleState(), SwerveModuleState())
        val buffer = DoubleArray(4)
        var iteration = 0
        var checksum = 0.0
        fun tick() {
            val vx = if (iteration++ % 2 == 0) 0.4 else 1.0
            dd.toWheelSpeeds(vx, 1.0, buffer)
            DifferentialDriveKinematics.normalize(buffer, 0.85)
            checksum += buffer[0]
            mecanum.toWheelSpeeds(vx, 0.2, 1.0, buffer)
            MecanumKinematics.normalize(buffer, 0.85)
            checksum += buffer[1]
            states[0].speedMetersPerSecond = 1e200
            states[1].speedMetersPerSecond = -5e199
            swerve.desaturateWheelSpeeds(states, 1e-200)
            checksum += states[0].speedMetersPerSecond
            checksum += unscaled.normalize(1.0).frontLeftMetersPerSecond
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
        assertEquals(2, consecutive, "Last window allocated $bytes bytes")
        assertTrue(checksum.isFinite() && checksum > 0.0)
    }
}
