package com.areslib.ftc

import com.areslib.ftc.drivetrain.SwerveModuleIOFtc
import com.areslib.hardware.drive.SwerveModuleInputs
import com.areslib.util.RobotClock
import com.qualcomm.robotcore.hardware.AnalogInput
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwerveFtcSamplingAuditTest {
    @AfterTest fun restoreClock() { RobotClock.useSystemTime() }

    @Test fun `a slow returned sample keeps its acquisition-start age`() {
        RobotClock.useMockTime(1000)
        val second = CountDownLatch(1); val releaseSecond = CountDownLatch(1)
        val third = CountDownLatch(1); val releaseThird = CountDownLatch(1)
        val sensor = object : AnalogInput() {
            var calls = 0
            override val voltage: Double get() {
                when (++calls) {
                    2 -> { second.countDown(); releaseSecond.await() }
                    3 -> { third.countDown(); releaseThird.await() }
                }
                return 1.65
            }
        }
        val drive = AuditSwerveMotor(); val steer = AuditSwerveMotor()
        val io = SwerveModuleIOFtc(drive, steer, sensor)
        try {
            awaitSwerveSample(io); assertTrue(second.await(2, TimeUnit.SECONDS))
            io.setDesiredPower(0.5, 0.4)
            RobotClock.useMockTime(1101); releaseSecond.countDown()
            assertTrue(third.await(2, TimeUnit.SECONDS), "Third read proves the slow second result was committed")
            val inputs = SwerveModuleInputs(); io.updateInputs(inputs)
            assertFalse(inputs.steerAbsoluteValid)
            assertEquals(0.0, drive.effort); assertEquals(0.0, steer.effort)
        } finally { releaseSecond.countDown(); releaseThird.countDown(); io.close() }
    }

    @Test fun `receiver time rewind and signed overflow cannot make retained feedback fresh`() {
        for ((initial, later) in listOf(1000L to 999L, (Long.MAX_VALUE - 3) to (Long.MIN_VALUE + 3),
            (Long.MIN_VALUE + 3) to (Long.MAX_VALUE - 3))) {
            RobotClock.useMockTime(initial)
            val blocked = CountDownLatch(1); val release = CountDownLatch(1)
            val sensor = object : AnalogInput() {
                var calls = 0
                override val voltage: Double get() {
                    if (++calls > 1) { blocked.countDown(); release.await() }
                    return 1.65
                }
            }
            val drive = AuditSwerveMotor(); val steer = AuditSwerveMotor()
            val io = SwerveModuleIOFtc(drive, steer, sensor)
            try {
                awaitSwerveSample(io); assertTrue(blocked.await(2, TimeUnit.SECONDS))
                io.setDesiredPower(0.5, 0.4)
                RobotClock.useMockTime(later); io.setDesiredPower(0.5, 0.4)
                assertEquals(0.0, drive.effort); assertEquals(0.0, steer.effort)
            } finally { release.countDown(); io.close() }
        }
    }

    @Test fun `fatal sampler termination invalidates feedback at the next robot refresh`() {
        val nextRead = CountDownLatch(1); val release = CountDownLatch(1); val failed = CountDownLatch(1)
        val sampler = AtomicReference<Thread>()
        val sensor = object : AnalogInput() {
            var calls = 0
            override val voltage: Double get() {
                val thread = Thread.currentThread(); sampler.set(thread)
                thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> failed.countDown() }
                if (++calls > 1) { nextRead.countDown(); release.await(); throw AssertionError("sampler terminated") }
                return 1.65
            }
        }
        val drive = AuditSwerveMotor(); val steer = AuditSwerveMotor()
        val io = SwerveModuleIOFtc(drive, steer, sensor)
        try {
            awaitSwerveSample(io); assertTrue(nextRead.await(2, TimeUnit.SECONDS)); io.setDesiredPower(0.5, 0.4)
            release.countDown(); assertTrue(failed.await(2, TimeUnit.SECONDS))
            val inputs = SwerveModuleInputs(); io.updateInputs(inputs)
            assertFalse(inputs.steerAbsoluteValid)
            assertEquals(0.0, drive.effort); assertEquals(0.0, steer.effort)
        } finally { release.countDown(); io.close(); sampler.get()?.join(2000); assertFalse(sampler.get()?.isAlive == true) }
    }
}
