package com.areslib.xrp.hardware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XrpDoublesAuditTest {
    @Test fun `motor command bounds keep invalid efforts from corrupting integration`() {
        val motor = XrpMotorDouble(1)
        for (effort in listOf(-2.0, 2.0, -Double.MAX_VALUE, Double.MAX_VALUE)) {
            motor.effort = effort
            assertEquals(effort.coerceIn(-1.0, 1.0), motor.effort)
        }
        for (effort in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            motor.effort = effort
            motor.update()
            assertEquals(0.0, motor.effort)
            assertEquals(0.0, motor.velocityRadiansPerSecond)
            assertEquals(0.0, motor.positionRadians)
        }
    }

    @Test fun `invalid reflectance cannot assert a detected line`() {
        val sensor = XrpLineSensorDouble()
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 1.1)) {
            sensor.leftReflectance = value
            sensor.rightReflectance = value
            assertFalse(sensor.isLeftOnLine)
            assertFalse(sensor.isRightOnLine)
        }
    }

    @Test fun `nonfinite servo commands reject without replacing the previous position`() {
        val servo = XrpServoDouble(1)
        servo.positionNormalized = 0.7
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { servo.positionNormalized = value }
            assertEquals(0.7, servo.positionNormalized)
        }
    }

    @Test fun `finite servo commands stay in the normalized output interval`() {
        val servo = XrpServoDouble(1)
        servo.positionNormalized = -0.5
        assertEquals(0.0, servo.positionNormalized)
        servo.positionNormalized = 1.5
        assertEquals(1.0, servo.positionNormalized)
    }

    @Test fun `motor fixture advances fixed steps and stop retains displacement`() {
        val motor = XrpMotorDouble(3)
        assertEquals(3, motor.channel)
        motor.effort = 0.5
        motor.update()
        assertEquals(15.0, motor.velocityRadiansPerSecond)
        assertEquals(0.3, motor.positionRadians, 1e-15)
        motor.effort = -0.25
        motor.update()
        assertEquals(-7.5, motor.velocityRadiansPerSecond)
        assertEquals(0.15, motor.positionRadians, 1e-15)
        val position = motor.positionRadians
        motor.stop()
        motor.stop()
        assertEquals(0.0, motor.effort)
        assertEquals(0.0, motor.velocityRadiansPerSecond)
        assertEquals(position, motor.positionRadians)
        motor.update()
        assertEquals(position, motor.positionRadians)
    }

    @Test fun `unknown injected feedback remains observable instead of becoming a valid measurement`() {
        val motor = XrpMotorDouble(1)
        motor.positionRadians = Double.NaN
        motor.velocityRadiansPerSecond = Double.POSITIVE_INFINITY
        assertTrue(motor.positionRadians.isNaN())
        assertEquals(Double.POSITIVE_INFINITY, motor.velocityRadiansPerSecond)
        motor.update()
        motor.stop()
        assertTrue(motor.positionRadians.isNaN())
        val ultrasonic = XrpUltrasonicDouble(Double.NaN)
        ultrasonic.update()
        assertTrue(ultrasonic.distanceMeters.isNaN())
        ultrasonic.distanceMeters = -1.0
        ultrasonic.update()
        assertEquals(-1.0, ultrasonic.distanceMeters)
        val line = XrpLineSensorDouble(Double.POSITIVE_INFINITY, -1.0)
        line.update()
        assertEquals(Double.POSITIVE_INFINITY, line.leftReflectance)
        assertEquals(-1.0, line.rightReflectance)
    }

    @Test fun `line threshold is strict and reads each cached component only once`() {
        var value = 0.0
        var leftReads = 0
        var rightReads = 0
        val sensor = object : XrpLineSensorIO {
            override val leftReflectance: Double get() { leftReads++; return value }
            override val rightReflectance: Double get() { rightReads++; return value }
            override fun update() = Unit
        }
        for ((reading, onLine) in listOf(0.0 to false, 0.5 to false, Math.nextUp(0.5) to true, 1.0 to true, 1.1 to false)) {
            value = reading
            val left = leftReads
            val right = rightReads
            assertEquals(onLine, sensor.isLeftOnLine)
            assertEquals(onLine, sensor.isRightOnLine)
            assertEquals(left + 1, leftReads)
            assertEquals(right + 1, rightReads)
        }
    }

    @Test fun `servo fixture preserves ordinary positions and channel on refresh`() {
        val servo = XrpServoDouble(2)
        assertEquals(2, servo.channel)
        assertEquals(0.5, servo.positionNormalized)
        for (position in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
            servo.positionNormalized = position
            servo.update()
            assertEquals(position, servo.positionNormalized)
        }
    }
}
