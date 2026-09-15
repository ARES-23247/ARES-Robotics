package com.areslib.kinematics

import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.math.geometry.Translation2d
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwerveAllocationAuditTest {
    @Test fun `buffered four-module steering and optimization allocate no bytes after warmup`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val kinematics = SwerveKinematics(Translation2d(0.3, 0.2), Translation2d(0.3, -0.2),
            Translation2d(-0.3, 0.2), Translation2d(-0.3, -0.2))
        val output = Array(4) { SwerveModuleState() }
        val commands = arrayOf(ChassisSpeeds(1.0, 0.3, 0.4), ChassisSpeeds(-0.5, 1.0, -1.0), ChassisSpeeds())
        var iteration = 0
        var checksum = 0.0
        fun tick() {
            kinematics.toSwerveModuleStates(commands[iteration++ % commands.size], 0.02, output)
            kinematics.optimizeModuleState(output[0], output[1].angle, output[0])
            kinematics.desaturateWheelSpeeds(output, 0.8)
            checksum += output[0].angle.radians + output[1].speedMetersPerSecond
        }
        repeat(50_000) { tick() }
        val thread = Thread.currentThread().id
        repeat(2) { window ->
            val before = bean.getThreadAllocatedBytes(thread)
            repeat(10_000) { tick() }
            assertEquals(0L, bean.getThreadAllocatedBytes(thread) - before, "window=$window")
        }
        assertTrue(checksum.isFinite() && checksum != 0.0)
    }
}
