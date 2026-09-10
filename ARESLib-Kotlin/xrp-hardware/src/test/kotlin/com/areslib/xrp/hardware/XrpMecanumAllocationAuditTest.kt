package com.areslib.xrp.hardware

import com.areslib.math.geometry.ChassisSpeeds
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XrpMecanumAllocationAuditTest {
    @Test fun `standard drive refresh and stop reuse storage in warmed periodic windows`() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!; bean.isThreadAllocatedMemoryEnabled = true
        val drive = StandardXrpMecanumHardwareIO()
        val speeds = ChassisSpeeds(0.4, 0.2, 1.0)
        var iteration = 0
        var checksum = 0.0
        fun tick() {
            speeds.vxMetersPerSecond = if (iteration++ % 2 == 0) 0.4 else 1.0
            drive.drive(speeds, 0.85)
            checksum += drive.frontLeftMotor.effort + drive.frontRightMotor.effort +
                drive.backLeftMotor.effort + drive.backRightMotor.effort
            drive.update()
            drive.stop()
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
        assertEquals(0.0, drive.frontLeftMotor.effort)
        assertEquals(0.0, drive.frontRightMotor.effort)
        assertEquals(0.0, drive.backLeftMotor.effort)
        assertEquals(0.0, drive.backRightMotor.effort)
    }
}
