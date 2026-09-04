package com.areslib.ftc

import com.areslib.ftc.hardware.CompositeMotorIO
import com.areslib.ftc.hardware.FtcCRServo
import com.areslib.hardware.actuator.MotorIO
import com.qualcomm.robotcore.hardware.CRServo
import com.qualcomm.robotcore.hardware.DcMotorSimple
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CompositeMotorLifecycleTest {
    private class Device : MotorIO, AutoCloseable {
        override var power = 0.0
        override var powerScale = 1.0
        var raw = 0.0
        override var position = 0.0
        override val velocity get() = position * 2
        var refreshes = 0
        var closes = 0
        var failClose = false
        override fun refresh() { refreshes++; position = raw }
        override fun resetEncoder() { position = 0.0 }
        override fun close() { closes++; if (failClose) error("disconnected") }
    }
    @Test fun `composite alone owns scale feedback and unique delegate lifetime`() {
        val actuator = Device()
        val sensor = Device()
        val composite = CompositeMotorIO(actuator, sensor)
        composite.power = 0.8
        composite.powerScale = 0.0
        assertEquals(0.0, actuator.power * actuator.powerScale)
        sensor.raw = 42.0
        composite.refresh()
        assertEquals(42.0, composite.position)
        assertEquals(84.0, composite.velocity)
        assertEquals(1, actuator.refreshes)
        assertEquals(1, sensor.refreshes)
        actuator.failClose = true
        assertFailsWith<IllegalStateException> { composite.close() }
        assertEquals(1, sensor.closes)
        val same = Device()
        val paired = CompositeMotorIO(same, same)
        paired.refresh(); paired.close()
        assertEquals(1, same.refreshes)
        assertEquals(1, same.closes)
    }
    @Test fun `CR servo refreshes the externally owned encoder through its facade`() {
        val servo = object : CRServo {
            override var power = 0.0
            override var direction = DcMotorSimple.Direction.FORWARD
        }
        val encoder = Device()
        val motor = FtcCRServo(servo, encoder)
        encoder.raw = 7.0
        motor.refresh()
        assertEquals(7.0, motor.position)
        assertEquals(1, encoder.refreshes)
        motor.power = 0.8
        motor.powerScale = 0.0
        assertEquals(0.0, servo.power)
    }
}
