package com.areslib.ftc

import com.areslib.ftc.drivetrain.SwerveModuleIOFtc
import com.areslib.hardware.drive.SwerveModuleInputs
import com.areslib.util.RobotClock
import com.qualcomm.robotcore.hardware.AnalogInput
import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotorSimple
import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType
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

internal class AuditSwerveMotor : DcMotorEx {
    val configuration = MotorConfigurationType().also { it.ticksPerRev = 537.7 }
    var metadataReads = 0
    override val motorType: MotorConfigurationType get() { metadataReads++; return configuration }
    var positionReads = 0
    var velocityReads = 0
    var positionFailure: Throwable? = null
    var velocityFailure: Throwable? = null
    var onVelocityRead: (() -> Unit)? = null
    private var position = 0
    private var speed = 0.0
    override var currentPosition: Int
        get() { positionReads++; positionFailure?.let { throw it }; return position }
        set(value) { position = value }
    override var velocity: Double
        get() { velocityReads++; velocityFailure?.let { throw it }; onVelocityRead?.invoke(); return speed }
        set(value) { speed = value }
    override var direction = DcMotorSimple.Direction.FORWARD
    override var mode = DcMotor.RunMode.RUN_WITHOUT_ENCODER
    override var zeroPowerBehavior = DcMotor.ZeroPowerBehavior.FLOAT
    var effort = 0.0
    var nextFailure: Throwable? = null
    var zeroFailure: Throwable? = null
    var closeCalls = 0
    override var power: Double
        get() = effort
        set(value) {
            nextFailure?.let { nextFailure = null; throw it }
            if (value == 0.0) zeroFailure?.let { throw it }
            effort = value
        }
    override fun getCurrent(unit: org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit) = 0.0
    override fun close() { closeCalls++ }
}

internal fun awaitSwerveSample(io: SwerveModuleIOFtc): SwerveModuleInputs {
    val inputs = SwerveModuleInputs()
    val deadline = System.nanoTime() + 2_000_000_000L
    do {
        io.updateInputs(inputs)
        if (inputs.steerAbsoluteValid) return inputs
        Thread.sleep(1)
    } while (System.nanoTime() < deadline)
    error("Analog sample did not become valid")
}

class SwerveFtcBoundaryAuditTest {
    @AfterTest fun resetClock() { RobotClock.useSystemTime() }
    private fun analog() = object : AnalogInput() {
        override val voltage = 2.5
        override val maxVoltage = 5.0
    }

    @Test fun `encoder conversion follows configured SDK metadata`() {
        val drive = AuditSwerveMotor().also { it.velocity = 537.7; it.currentPosition = 538 }
        val io = SwerveModuleIOFtc(drive, AuditSwerveMotor(), analog())
        try {
            val inputs = awaitSwerveSample(io)
            assertEquals(2.0 * Math.PI, inputs.driveVelocityRadsPerSec, 1e-12)
            assertEquals(538.0 / 537.7 * 2.0 * Math.PI, inputs.drivePositionRads, 1e-12)
            assertEquals(Math.PI, inputs.steerAbsolutePositionRads, 1e-12)
        } finally { io.close() }
    }

    @Test fun `large finite encoder velocity avoids intermediate unit-conversion overflow`() {
        val drive = AuditSwerveMotor().also { it.velocity = Double.MAX_VALUE }
        val io = SwerveModuleIOFtc(drive, AuditSwerveMotor(), analog())
        try {
            val inputs = awaitSwerveSample(io)
            val expected = java.math.BigDecimal(Double.MAX_VALUE).multiply(java.math.BigDecimal(2.0 * Math.PI))
                .divide(java.math.BigDecimal(537.7), java.math.MathContext(50)).toDouble()
            assertTrue(inputs.driveVelocityValid)
            assertEquals(expected, inputs.driveVelocityRadsPerSec, 4.0 * Math.ulp(expected))
        } finally { io.close() }
    }

    @Test fun `invalid member neutralizes the coupled motor command`() {
        val drive = AuditSwerveMotor(); val steer = AuditSwerveMotor()
        val io = SwerveModuleIOFtc(drive, steer, analog())
        try {
            awaitSwerveSample(io)
            io.setDesiredPower(0.7, Double.NaN)
            assertEquals(0.0, drive.effort)
            assertEquals(0.0, steer.effort)
        } finally { io.close() }
    }

    @Test fun `paired write failure neutralizes both motors and reaches the caller`() {
        val drive = AuditSwerveMotor(); val steer = AuditSwerveMotor()
        val io = SwerveModuleIOFtc(drive, steer, analog())
        try {
            awaitSwerveSample(io)
            val failure = IllegalStateException("steer write")
            steer.nextFailure = failure
            assertSame(failure, assertFailsWith<IllegalStateException> { io.setDesiredPower(0.7, 0.6) })
            assertEquals(0.0, drive.effort)
            assertEquals(0.0, steer.effort)
        } finally { io.close() }
    }

    @Test fun `close neutralizes invalidates feedback and prevents later actuation`() {
        val drive = AuditSwerveMotor(); val steer = AuditSwerveMotor()
        val io = SwerveModuleIOFtc(drive, steer, analog())
        try {
            awaitSwerveSample(io)
            io.setDesiredPower(0.7, 0.6)
            io.close()
            assertEquals(0.0, drive.effort)
            assertEquals(0.0, steer.effort)
            io.setDesiredPower(0.7, 0.6)
            assertEquals(0.0, drive.effort)
            assertEquals(0.0, steer.effort)
            val inputs = SwerveModuleInputs()
            io.updateInputs(inputs)
            assertFalse(inputs.drivePositionValid)
            assertFalse(inputs.driveVelocityValid)
            assertFalse(inputs.steerAbsoluteValid)
        } finally { io.close() }
    }

    @Test fun `blocked sampler cannot keep an old observation fresh`() {
        RobotClock.useMockTime(1000)
        val block = CountDownLatch(1); val release = CountDownLatch(1)
        val sampleThread = AtomicReference<Thread>()
        val analog = object : AnalogInput() {
            var calls = 0
            override val voltage: Double get() {
                sampleThread.set(Thread.currentThread())
                if (++calls > 1) { block.countDown(); release.await() }
                return 1.65
            }
        }
        val drive = AuditSwerveMotor(); val steer = AuditSwerveMotor()
        val io = SwerveModuleIOFtc(drive, steer, analog)
        try {
            awaitSwerveSample(io)
            assertTrue(block.await(2, TimeUnit.SECONDS))
            io.setDesiredPower(0.7, 0.6)
            RobotClock.useMockTime(1101)
            val inputs = SwerveModuleInputs()
            io.updateInputs(inputs)
            assertFalse(inputs.steerAbsoluteValid)
            assertEquals(0.0, drive.effort)
            assertEquals(0.0, steer.effort)
        } finally {
            release.countDown()
            io.close()
            sampleThread.get()?.join(2000)
            assertFalse(sampleThread.get()?.isAlive == true)
        }
    }
}
