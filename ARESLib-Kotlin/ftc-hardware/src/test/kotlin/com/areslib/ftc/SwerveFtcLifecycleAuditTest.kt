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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SwerveFtcLifecycleAuditTest {
    @AfterTest fun resetClock() { RobotClock.useSystemTime() }
    private fun analog() = object : AnalogInput() { override val voltage = 1.65 }

    @Test fun `configuration rejects before outputs or sampler startup`() {
        val drive = AuditSwerveMotor().also { it.effort = 0.3 }
        val steer = AuditSwerveMotor().also { it.effort = 0.4 }
        var reads = 0
        val sensor = object : AnalogInput() { override val voltage: Double get() { reads++; return 1.65 } }
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { SwerveModuleIOFtc(drive, steer, sensor, driveTicksPerRevolution = bad) }
        }
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { SwerveModuleIOFtc(drive, steer, sensor, analogRangeVolts = bad) }
        }
        assertFailsWith<IllegalArgumentException> { SwerveModuleIOFtc(drive, steer, sensor, sampleTimeoutMs = 0) }
        assertFailsWith<IllegalArgumentException> { SwerveModuleIOFtc(drive, drive, sensor) }
        assertEquals(0, reads)
        assertEquals(0.3, drive.effort); assertEquals(0.4, steer.effort)
    }

    @Test fun `constructor neutralizes both motors before a successful sampler starts`() {
        val drive = AuditSwerveMotor().also { it.effort = 0.3 }
        val steer = AuditSwerveMotor().also { it.effort = 0.4 }
        val sample = CountDownLatch(1)
        val observed = AtomicReference<Pair<Double, Double>>()
        val sensor = object : AnalogInput() {
            override val voltage: Double get() {
                observed.compareAndSet(null, Pair(drive.effort, steer.effort)); sample.countDown(); return 1.65
            }
        }
        val io = SwerveModuleIOFtc(drive, steer, sensor)
        try {
            assertTrue(sample.await(2, TimeUnit.SECONDS))
            assertEquals(Pair(0.0, 0.0), observed.get())
        } finally { io.close() }
    }

    @Test fun `failed constructor neutral attempts every motor without starting a sampler`() {
        val first = IllegalStateException("drive stop"); val second = IllegalStateException("steer stop")
        val drive = AuditSwerveMotor().also { it.zeroFailure = first }
        val steer = AuditSwerveMotor().also { it.zeroFailure = second }
        var reads = 0
        val sensor = object : AnalogInput() { override val voltage: Double get() { reads++; return 1.65 } }
        assertSame(first, assertFailsWith<IllegalStateException> { SwerveModuleIOFtc(drive, steer, sensor) })
        assertEquals(listOf(second), first.suppressed.toList())
        assertEquals(0, reads)
    }

    @Test fun `failed command retains its cause when a cleanup write also fails`() {
        val drive = AuditSwerveMotor(); val steer = AuditSwerveMotor()
        val io = SwerveModuleIOFtc(drive, steer, analog())
        try {
            awaitSwerveSample(io)
            val original = IllegalStateException("steer command"); val cleanup = IllegalStateException("drive stop")
            steer.nextFailure = original; drive.zeroFailure = cleanup
            assertSame(original, assertFailsWith<IllegalStateException> { io.setDesiredPower(0.5, 0.4) })
            assertEquals(listOf(cleanup), original.suppressed.toList())
            assertEquals(0.0, steer.effort)
        } finally { drive.zeroFailure = null; io.close() }
    }

    @Test fun `cached motor wrapper forwards SDK calibration metadata`() {
        val drive = AuditSwerveMotor().also { it.velocity = 537.7 }
        val cached = com.areslib.ftc.hardware.CachedDcMotorEx(drive)
        val io = SwerveModuleIOFtc(cached, AuditSwerveMotor(), analog())
        try {
            val inputs = awaitSwerveSample(io)
            assertEquals(2.0 * Math.PI, inputs.driveVelocityRadsPerSec, 1e-12)
            assertEquals(1, drive.metadataReads)
        } finally { io.close() }
    }

    @Test fun `metadata is captured once and each refresh reads drive channels once`() {
        val drive = AuditSwerveMotor().also { it.velocity = 537.7 }
        var rangeReads = 0
        val sensor = object : AnalogInput() {
            override val voltage = 1.65
            override val maxVoltage: Double get() { rangeReads++; return 3.3 }
        }
        val io = SwerveModuleIOFtc(drive, AuditSwerveMotor(), sensor)
        try {
            awaitSwerveSample(io)
            drive.configuration.ticksPerRev = 1000.0
            val beforePosition = drive.positionReads; val beforeVelocity = drive.velocityReads
            val inputs = SwerveModuleInputs(); io.updateInputs(inputs)
            assertEquals(beforePosition + 1, drive.positionReads)
            assertEquals(beforeVelocity + 1, drive.velocityReads)
            assertEquals(2.0 * Math.PI, inputs.driveVelocityRadsPerSec, 1e-12)
            assertEquals(1, drive.metadataReads); assertEquals(1, rangeReads)
        } finally { io.close() }
    }

    @Test fun `commands require a current drive refresh even while analog sampling is fresh`() {
        RobotClock.useMockTime(1000)
        val drive = AuditSwerveMotor(); val steer = AuditSwerveMotor()
        val io = SwerveModuleIOFtc(drive, steer, analog())
        try {
            io.setDesiredPower(0.5, 0.4)
            assertEquals(0.0, drive.effort)
            awaitSwerveSample(io)
            io.setDesiredPower(2.0, -2.0)
            assertEquals(1.0, drive.effort); assertEquals(-1.0, steer.effort)
            RobotClock.useMockTime(1101)
            io.setDesiredPower(0.5, 0.4)
            assertEquals(0.0, drive.effort); assertEquals(0.0, steer.effort)
        } finally { io.close() }
    }

    @Test fun `slow drive acquisition is unavailable and neutralizes previous output`() {
        RobotClock.useMockTime(1000)
        val drive = AuditSwerveMotor(); val steer = AuditSwerveMotor()
        val io = SwerveModuleIOFtc(drive, steer, analog())
        try {
            awaitSwerveSample(io); io.setDesiredPower(0.5, 0.4)
            drive.onVelocityRead = { RobotClock.useMockTime(1101) }
            val inputs = SwerveModuleInputs(); io.updateInputs(inputs)
            assertFalse(inputs.drivePositionValid); assertFalse(inputs.driveVelocityValid)
            assertEquals(0.0, drive.effort); assertEquals(0.0, steer.effort)
        } finally { io.close() }
    }

    @Test fun `read faults preserve last value mark unavailable and neutralize`() {
        val drive = AuditSwerveMotor().also { it.currentPosition = 100; it.velocity = 20.0 }
        val steer = AuditSwerveMotor()
        val io = SwerveModuleIOFtc(drive, steer, analog())
        try {
            val inputs = awaitSwerveSample(io)
            val lastPosition = inputs.drivePositionRads; val lastVelocity = inputs.driveVelocityRadsPerSec
            io.setDesiredPower(0.5, 0.4)
            drive.positionFailure = IllegalStateException("position unavailable"); drive.velocity = Double.NaN
            io.updateInputs(inputs)
            assertEquals(lastPosition, inputs.drivePositionRads); assertEquals(lastVelocity, inputs.driveVelocityRadsPerSec)
            assertFalse(inputs.drivePositionValid); assertFalse(inputs.driveVelocityValid)
            assertEquals(0.0, drive.effort); assertEquals(0.0, steer.effort)
        } finally { io.close() }
    }

    @Test fun `fatal read and repeated cleanup exception preserve original identity`() {
        val drive = AuditSwerveMotor(); val steer = AuditSwerveMotor()
        val io = SwerveModuleIOFtc(drive, steer, analog())
        try {
            awaitSwerveSample(io); io.setDesiredPower(0.5, 0.4)
            val failure = AssertionError("fatal read")
            drive.positionFailure = failure; drive.zeroFailure = failure
            val inputs = SwerveModuleInputs()
            assertSame(failure, assertFailsWith<AssertionError> { io.updateInputs(inputs) })
            assertFalse(inputs.drivePositionValid); assertFalse(inputs.driveVelocityValid); assertFalse(inputs.steerAbsoluteValid)
            assertEquals(0.0, steer.effort)
            assertTrue(failure.suppressed.isEmpty())
        } finally { drive.zeroFailure = null; io.close() }
    }

    @Test fun `close attempts both stops preserves borrowed devices and permits retry after failure`() {
        val drive = AuditSwerveMotor(); val steer = AuditSwerveMotor()
        var sensorCloses = 0
        val sensor = object : AnalogInput() {
            override val voltage = 1.65
            override fun close() { sensorCloses++ }
        }
        val io = SwerveModuleIOFtc(drive, steer, sensor)
        try {
            awaitSwerveSample(io); io.setDesiredPower(0.5, 0.4)
            val failure = IllegalStateException("drive stop failed"); drive.zeroFailure = failure
            assertSame(failure, assertFailsWith<IllegalStateException> { io.close() })
            assertEquals(0.0, steer.effort)
            drive.zeroFailure = null; io.close()
            assertEquals(0.0, drive.effort)
            assertEquals(0, drive.closeCalls); assertEquals(0, steer.closeCalls); assertEquals(0, sensorCloses)
        } finally { drive.zeroFailure = null; io.close() }
    }

    @Test fun `uninterruptible sampler reports join timeout without leaving motors active`() {
        RobotClock.useMockTime(1000)
        val blocked = CountDownLatch(1); val release = CountDownLatch(1)
        val sampler = AtomicReference<Thread>()
        val sensor = object : AnalogInput() {
            var calls = 0
            override val voltage: Double get() {
                sampler.set(Thread.currentThread())
                if (++calls > 1) {
                    blocked.countDown()
                    while (release.count > 0) { try { release.await() } catch (_: InterruptedException) { } }
                }
                return 1.65
            }
        }
        val drive = AuditSwerveMotor(); val steer = AuditSwerveMotor()
        val io = SwerveModuleIOFtc(drive, steer, sensor)
        try {
            awaitSwerveSample(io); assertTrue(blocked.await(2, TimeUnit.SECONDS))
            io.setDesiredPower(0.5, 0.4)
            assertFailsWith<IllegalStateException> { io.close() }
            assertTrue(sampler.get().isAlive)
            assertEquals(0.0, drive.effort); assertEquals(0.0, steer.effort)
        } finally {
            release.countDown(); sampler.get()?.join(2000); io.close()
            assertFalse(sampler.get()?.isAlive == true)
        }
    }
}
