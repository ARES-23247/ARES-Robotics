package com.areslib.ftc

import com.areslib.ftc.drivetrain.SparkFunOtosIO
import com.areslib.hardware.drive.OdometryInputs
import com.areslib.math.geometry.Pose2d
import com.areslib.util.RobotClock
import com.qualcomm.hardware.sparkfun.SparkFunOTOS
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SparkFunOtosRaceTest {
    private class Driver : SparkFunOTOS() {
        val reads = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var blockRead = false
        @Volatile var blockReset = false
        @Volatile var failAfterWait = false
        override fun getPosition(): Pose2D {
            reads.incrementAndGet()
            if (blockRead) waitForRelease()
            check(!failAfterWait)
            return Pose2D()
        }
        override fun getVelocity(): Pose2D { reads.incrementAndGet(); return Pose2D(0.25, 0.0, 0.0) }
        override fun resetTracking() { if (blockReset) waitForRelease() }
        private fun waitForRelease() {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Fixture was not released" }
        }
    }

    @BeforeEach fun clock() = RobotClock.useMockTime(1_000L)
    @AfterEach fun restoreClock() = RobotClock.useSystemTime()

    @Test fun `read completion or failure after close cannot republish or replace closed health`() {
        for (fail in listOf(false, true)) {
            val driver = Driver()
            val io = SparkFunOtosIO(driver)
            io.updateInputs(OdometryInputs())
            driver.blockRead = true
            driver.failAfterWait = fail
            val output = OdometryInputs()
            val reader = thread(isDaemon = true) { io.updateInputs(output) }
            try {
                assertTrue(driver.entered.await(5, TimeUnit.SECONDS))
                io.close()
                driver.release.countDown()
                reader.join(5_000)
                assertFalse(reader.isAlive)
                assertEquals(3, driver.reads.get(), "No new velocity read after cancellation")
                assertEquals(0L, output.timestampMs)
                assertEquals(0.0, output.velX)
                assertEquals(SparkFunOtosIO.HealthStatus.STALE, io.healthStatus)
            } finally { driver.release.countDown(); reader.join(5_000) }
        }
    }

    @Test fun `read from an earlier frame cannot become the first sample after reset`() {
        val driver = Driver()
        val io = SparkFunOtosIO(driver)
        io.updateInputs(OdometryInputs())
        driver.blockRead = true
        val output = OdometryInputs()
        val reader = thread(isDaemon = true) { io.updateInputs(output) }
        try {
            assertTrue(driver.entered.await(5, TimeUnit.SECONDS))
            RobotClock.useMockTime(1_020L)
            io.initialize(Pose2d(3.0, 4.0))
            driver.release.countDown()
            reader.join(5_000)
            assertFalse(reader.isAlive)
            assertEquals(0L, output.timestampMs)
            assertEquals(0.0, output.velX)
            assertEquals(3.0, output.posX)
            assertEquals(SparkFunOtosIO.HealthStatus.STARTING, io.healthStatus)
            driver.blockRead = false
            io.updateInputs(output)
            assertEquals(1_020L, output.timestampMs)
            assertEquals(3.0, output.posX)
        } finally { driver.release.countDown(); reader.join(5_000) }
    }

    @Test fun `close does not await SDK reset and its late completion cannot reopen sampling`() {
        val driver = Driver()
        val io = SparkFunOtosIO(driver)
        driver.blockReset = true
        val failure = AtomicReference<Throwable?>()
        val initializer = thread(isDaemon = true) {
            try { io.initialize(Pose2d(3.0, 4.0)) } catch (caught: Throwable) { failure.set(caught) }
        }
        val closer = thread(start = false, isDaemon = true) { io.close() }
        try {
            assertTrue(driver.entered.await(5, TimeUnit.SECONDS))
            closer.start()
            closer.join(1_000)
            assertFalse(closer.isAlive, "Close must not wait for the SDK reset")
            driver.release.countDown()
            initializer.join(5_000)
            assertFalse(initializer.isAlive)
            assertIs<IllegalStateException>(failure.get())
            val output = OdometryInputs()
            io.updateInputs(output)
            assertEquals(0L, output.timestampMs)
            assertEquals(0, driver.reads.get())
            assertEquals(SparkFunOtosIO.HealthStatus.STALE, io.healthStatus)
        } finally { driver.release.countDown(); initializer.join(5_000); if (closer.isAlive) closer.join(5_000) }
    }
}
