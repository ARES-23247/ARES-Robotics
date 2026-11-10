package com.areslib.ftc

import com.areslib.ftc.drivetrain.SparkFunOtosIO
import com.areslib.hardware.drive.OdometryInputs
import com.areslib.math.geometry.Pose2d
import com.areslib.util.RobotClock
import com.qualcomm.hardware.sparkfun.SparkFunOTOS
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class SparkFunOtosLifecycleTest {
    private class Driver : SparkFunOTOS() {
        val samplePosition = Pose2D(0.1, 0.2, 0.3)
        val sampleVelocity = Pose2D(1.0, 0.5, 0.2)
        var reads = 0
        var resets = 0
        var failRead = false
        var failResetAfterApply = false
        override fun getPosition(): Pose2D { reads++; check(!failRead); return samplePosition }
        override fun getVelocity(): Pose2D { reads++; check(!failRead); return sampleVelocity }
        override fun resetTracking() { resets++; samplePosition.x = 0.0; check(!failResetAfterApply) }
    }

    @BeforeEach fun clock() = RobotClock.useMockTime(1_000L)
    @AfterEach fun restoreClock() = RobotClock.useSystemTime()

    @Test fun `reset followed by failed read cannot publish previous motion as fresh feedback`() {
        val driver = Driver()
        val io = SparkFunOtosIO(driver)
        val inputs = OdometryInputs()
        io.updateInputs(inputs)
        assertEquals(1.0, inputs.velX)
        RobotClock.useMockTime(1_020L)
        io.initialize(Pose2d(2.0, 3.0))
        driver.failRead = true
        io.updateInputs(inputs)
        assertEquals(0.0, inputs.velX)
        assertEquals(0.0, inputs.velY)
        assertEquals(0.0, inputs.headingVelocity)
        assertEquals(0L, inputs.timestampMs, "Reset pose is not a newly observed sample")
        assertEquals(SparkFunOtosIO.HealthStatus.COMMUNICATION_FAILURE, io.healthStatus)
        driver.failRead = false
        io.updateInputs(inputs)
        assertEquals(1_020L, inputs.timestampMs)
        assertEquals(SparkFunOtosIO.HealthStatus.HEALTHY, io.healthStatus)
    }

    @Test fun `uncertain reset requires a successful initialization before sampling resumes`() {
        val driver = Driver()
        val io = SparkFunOtosIO(driver)
        val inputs = OdometryInputs()
        io.updateInputs(inputs)
        driver.failResetAfterApply = true
        assertFailsWith<IllegalStateException> { io.initialize(Pose2d(2.0, 3.0)) }
        val before = driver.reads
        io.updateInputs(inputs)
        assertEquals(before, driver.reads)
        assertEquals(0L, inputs.timestampMs)
        assertEquals(0.0, inputs.velX)
        driver.failResetAfterApply = false
        io.initialize(Pose2d(2.0, 3.0))
        io.updateInputs(inputs)
        assertEquals(2.0, inputs.posX)
        assertEquals(1_000L, inputs.timestampMs)
    }

    @Test fun `closed odometry performs no more SDK reads or resets and publishes no fresh motion`() {
        val driver = Driver()
        val io = SparkFunOtosIO(driver)
        val inputs = OdometryInputs()
        io.updateInputs(inputs)
        val before = driver.reads
        val resets = driver.resets
        io.close()
        io.close()
        io.updateInputs(inputs)
        val action = io.getPoseUpdate()
        assertEquals(before, driver.reads)
        assertEquals(0L, inputs.timestampMs)
        assertEquals(0.0, inputs.velX)
        assertEquals(0L, action.timestampMs)
        assertFailsWith<IllegalStateException> { io.initialize(Pose2d()) }
        assertEquals(resets, driver.resets)
    }

    @Test fun `legacy pose updates remain owned snapshots and each entry point reads once`() {
        val driver = Driver()
        val io = SparkFunOtosIO(driver)
        val first = io.getPoseUpdate()
        assertEquals(2, driver.reads)
        driver.samplePosition.x = 0.05
        RobotClock.useMockTime(1_020L)
        val second = io.getPoseUpdate()
        assertNotSame(first, second)
        assertEquals(0.0, first.xMeters)
        assertEquals(0.05, second.xMeters)
        assertEquals(4, driver.reads)
        io.updateInputs(OdometryInputs())
        assertEquals(6, driver.reads)
    }
}
