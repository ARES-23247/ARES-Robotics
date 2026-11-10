package com.areslib.ftc

import com.areslib.ftc.drivetrain.SparkFunOtosIO
import com.areslib.hardware.drive.OdometryInputs
import com.areslib.util.RobotClock
import com.qualcomm.hardware.sparkfun.SparkFunOTOS
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Primitive SDK fixture isolates ARES wrapper allocations from the real driver's allocations. */
class SparkFunOtosInputAllocationTest {
    private class Driver : SparkFunOTOS() {
        private val position = Pose2D()
        private val velocity = Pose2D(0.1, 0.2, 0.3)
        var reads = 0
        override fun getPosition(): Pose2D { reads++; return position }
        override fun getVelocity(): Pose2D { reads++; return velocity }
    }

    @Test fun `odometry inputs refresh avoids intermediate action allocation`() {
        val bean = ManagementFactory.getThreadMXBean()
        assumeTrue(bean is ThreadMXBean && bean.isThreadAllocatedMemorySupported)
        val allocation = bean as ThreadMXBean
        allocation.isThreadAllocatedMemoryEnabled = true
        RobotClock.useMockTime(1_000L)
        try {
            val driver = Driver()
            val io = SparkFunOtosIO(driver)
            val inputs = OdometryInputs()
            repeat(50_000) { io.updateInputs(inputs) }
            val thread = Thread.currentThread().id
            val beforeReads = driver.reads
            val before = allocation.getThreadAllocatedBytes(thread)
            repeat(20_000) { io.updateInputs(inputs) }
            val bytes = allocation.getThreadAllocatedBytes(thread) - before
            println("OTOS wrapper: $bytes bytes / 20000 input refreshes; SDK internals excluded")
            assertEquals(40_000, driver.reads - beforeReads)
            assertEquals(0L, bytes)
            assertEquals(0.1, inputs.velX)
        } finally { RobotClock.useSystemTime() }
    }
}
