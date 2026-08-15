package com.areslib.ftc.hardware

import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotorSimple
import com.qualcomm.robotcore.hardware.Servo
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CachedHardwareContractTest {

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

    private class CountingMotor(initialPower: Double) : DcMotorEx {
        var readCount = 0
        var writeCount = 0
        var rawPower = initialPower

        override var power: Double
            get() {
                readCount++
                return rawPower
            }
            set(value) {
                writeCount++
                rawPower = value
            }

        override var direction: DcMotorSimple.Direction = DcMotorSimple.Direction.FORWARD
        override var mode: DcMotor.RunMode = DcMotor.RunMode.RUN_WITHOUT_ENCODER
        override var zeroPowerBehavior: DcMotor.ZeroPowerBehavior = DcMotor.ZeroPowerBehavior.FLOAT
        override val currentPosition: Int = 0
        override var velocity: Double = 0.0
        override fun getCurrent(unit: CurrentUnit): Double = 0.0
    }

    private class CountingServo(initialPosition: Double) : Servo {
        var readCount = 0
        var writeCount = 0
        var rawPosition = initialPosition

        override var position: Double
            get() {
                readCount++
                return rawPosition
            }
            set(value) {
                writeCount++
                rawPosition = value
            }
    }
}
