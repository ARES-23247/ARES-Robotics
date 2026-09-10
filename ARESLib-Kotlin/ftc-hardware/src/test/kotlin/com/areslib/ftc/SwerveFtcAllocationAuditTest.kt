package com.areslib.ftc

import com.areslib.ftc.drivetrain.SwerveModuleIOFtc
import com.areslib.hardware.drive.SwerveModuleInputs
import com.areslib.util.RobotClock
import com.qualcomm.robotcore.hardware.AnalogInput
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwerveFtcAllocationAuditTest {
    @Test fun `cached input conversion and paired commands allocate no robot-loop bytes`() {
        RobotClock.useMockTime(1000)
        val drive = AuditSwerveMotor(); val steer = AuditSwerveMotor()
        val sensor = object : AnalogInput() { override val voltage = 1.65 }
        val io = SwerveModuleIOFtc(drive, steer, sensor)
        try {
            awaitSwerveSample(io)
            val inputs = SwerveModuleInputs()
            val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
            bean.isThreadAllocatedMemoryEnabled = true
            var iteration = 0
            var checksum = 0.0
            fun tick() {
                drive.currentPosition = iteration++
                drive.velocity = if (iteration % 2 == 0) 100.0 else -100.0
                io.updateInputs(inputs)
                io.setDesiredPower(if (iteration % 3 == 0) 0.0 else 0.4, if (iteration % 3 == 0) 0.0 else -0.3)
                checksum += inputs.drivePositionRads + inputs.driveVelocityRadsPerSec
            }
            repeat(50_000) { tick() }
            val thread = Thread.currentThread().id
            repeat(2) { window ->
                val before = bean.getThreadAllocatedBytes(thread)
                repeat(10_000) { tick() }
                assertEquals(0L, bean.getThreadAllocatedBytes(thread) - before, "window=$window")
            }
            assertTrue(checksum.isFinite() && checksum > 0.0)
            assertEquals(1, drive.metadataReads)
        } finally { io.close(); RobotClock.useSystemTime() }
    }
}
