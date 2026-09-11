package com.areslib.ftc.hardware

import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotorSimple
import com.qualcomm.robotcore.hardware.Servo
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CachedHardwareContractTest {

    @Test
    fun `cached commands reflect clipping at the device boundary`() {
        val motor = CountingMotor(0.0)
        val cachedMotor = CachedDcMotorEx(motor)
        cachedMotor.power = 2.0
        cachedMotor.power = 3.0
        assertEquals(1.0, cachedMotor.power)
        assertEquals(1.0, motor.rawPower)
        assertEquals(1, motor.writeCount)
        val servo = CountingServo(0.0)
        val cachedServo = CachedServo(servo)
        cachedServo.position = -1.0
        cachedServo.position = -2.0
        assertEquals(0.0, servo.rawPosition)
        assertEquals(0.0, cachedServo.position)
        assertEquals(1, servo.writeCount)
    }

    @Test
    fun `servo rejects invalid positions without poisoning its cache`() {
        val delegate = CountingServo(0.0)
        val cached = CachedServo(delegate)
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { cached.position = invalid }
        }
        assertEquals(0, delegate.writeCount)
        cached.position = 0.4
        cached.resetDeviceConfigurationForOpMode()
        cached.position = 0.4
        assertEquals(2, delegate.writeCount)
    }

    @Test
    fun `non finite motor commands neutralize instead of retaining prior output`() {
        val delegate = CountingMotor(0.0)
        val cached = CachedDcMotorEx(delegate)
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            cached.power = 0.5
            cached.power = invalid
            assertEquals(0.0, delegate.rawPower)
            assertEquals(0.0, cached.power)
        }
    }

    @Test
    fun `zero epsilon still suppresses identical commands`() {
        val motor = CountingMotor(0.0)
        val cachedMotor = CachedDcMotorEx(motor, 0.0)
        repeat(10) { cachedMotor.power = 0.4 }
        assertEquals(1, motor.writeCount)
        val servo = CountingServo(0.0)
        val cachedServo = CachedServo(servo, 0.0)
        repeat(10) { cachedServo.position = 0.4 }
        assertEquals(1, servo.writeCount)
    }

    @Test
    fun `resetting motor configuration invalidates the command cache`() {
        val delegate = CountingMotor(0.0)
        val cached = CachedDcMotorEx(delegate)
        cached.power = 0.5
        cached.resetDeviceConfigurationForOpMode()
        cached.power = 0.5
        assertEquals(0.5, delegate.rawPower)
        assertEquals(2, delegate.writeCount)
        cached.mode = DcMotor.RunMode.STOP_AND_RESET_ENCODER
        cached.mode = DcMotor.RunMode.RUN_WITHOUT_ENCODER
        cached.power = 0.5
        assertEquals(0.5, delegate.rawPower)
        assertEquals(3, delegate.writeCount)
    }

    @Test
    fun `invalid cache thresholds are rejected`() {
        for (invalid in listOf(-0.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { CachedDcMotorEx(CountingMotor(0.0), invalid) }
            assertFailsWith<IllegalArgumentException> { CachedServo(CountingServo(0.0), invalid) }
        }
    }

    @Test
    fun `motor suppresses sub-epsilon writes but never suppresses a changed hard stop`() {
        val delegate = CountingMotor(initialPower = 0.25)
        val cached = CachedDcMotorEx(delegate, epsilon = 0.05)

        assertEquals(0.25, cached.power)
        assertEquals(1, delegate.readCount)

        cached.power = 0.40
        cached.power = 0.44
        assertEquals(1, delegate.writeCount, "sub-epsilon update must not touch the bus")
        assertEquals(0.40, cached.power, "getter must expose the last accepted command")
        assertEquals(1, delegate.readCount, "getter must remain cached after the first command")

        cached.power = 0.0
        cached.power = 0.0
        assertEquals(2, delegate.writeCount, "changed zero command is written exactly once")
        assertEquals(0.0, delegate.rawPower)

        cached.power = -0.10
        assertEquals(3, delegate.writeCount)
        assertEquals(-0.10, delegate.rawPower)
    }

    @Test
    fun `servo first command is written and later reads never poll hardware`() {
        val delegate = CountingServo(initialPosition = 0.2)
        val cached = CachedServo(delegate, epsilon = 0.01)

        assertEquals(0.2, cached.position)
        assertEquals(1, delegate.readCount)

        cached.position = 0.6
        cached.position = 0.605
        assertEquals(1, delegate.writeCount)
        assertEquals(0.6, cached.position)
        assertEquals(1, delegate.readCount)

        cached.position = 0.62
        assertEquals(2, delegate.writeCount)
        assertEquals(0.62, delegate.rawPosition)
    }

    @Test
    fun `failed motor write forces retry even when returning to the cached command`() {
        val delegate = CountingMotor(0.0)
        val cached = CachedDcMotorEx(delegate)
        cached.power = 0.0
        delegate.failAfterWrite = true
        assertFailsWith<IllegalStateException> { cached.power = 0.6 }
        assertEquals(0.0, cached.power)
        assertEquals(0, delegate.readCount)
        delegate.failAfterWrite = false
        cached.power = 0.0
        assertEquals(0.0, delegate.rawPower)
        assertEquals(3, delegate.writeCount)
    }

    @Test
    fun `failed servo write forces retry even when returning to the cached command`() {
        val delegate = CountingServo(0.2)
        val cached = CachedServo(delegate)
        cached.position = 0.2
        delegate.failAfterWrite = true
        assertFailsWith<IllegalStateException> { cached.position = 0.6 }
        assertEquals(0.2, cached.position)
        assertEquals(0, delegate.readCount)
        delegate.failAfterWrite = false
        cached.position = 0.2
        assertEquals(0.2, delegate.rawPosition)
        assertEquals(3, delegate.writeCount)
    }

    private class CountingMotor(initialPower: Double) : DcMotorEx {
        var readCount = 0
        var writeCount = 0
        var failAfterWrite = false
        var rawPower = initialPower

        override var power: Double
            get() {
                readCount++
                return rawPower
            }
            set(value) {
                writeCount++
                rawPower = value
                check(!failAfterWrite) { "write acknowledgement lost" }
            }

        override var direction: DcMotorSimple.Direction = DcMotorSimple.Direction.FORWARD
        override var mode: DcMotor.RunMode = DcMotor.RunMode.RUN_WITHOUT_ENCODER
            set(value) {
                field = value
                if (value == DcMotor.RunMode.STOP_AND_RESET_ENCODER) rawPower = 0.0
            }
        override fun resetDeviceConfigurationForOpMode() { rawPower = 0.0 }
        override var zeroPowerBehavior: DcMotor.ZeroPowerBehavior = DcMotor.ZeroPowerBehavior.FLOAT
        override val currentPosition: Int = 0
        override var velocity: Double = 0.0
        override fun getCurrent(unit: CurrentUnit): Double = 0.0
    }

    private class CountingServo(initialPosition: Double) : Servo {
        var readCount = 0
        var writeCount = 0
        var failAfterWrite = false
        var rawPosition = initialPosition

        override var position: Double
            get() {
                readCount++
                return rawPosition
            }
            set(value) {
                writeCount++
                rawPosition = value
                check(!failAfterWrite) { "write acknowledgement lost" }
            }
    }
}
