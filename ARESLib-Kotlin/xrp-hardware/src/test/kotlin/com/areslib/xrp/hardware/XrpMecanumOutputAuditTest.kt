package com.areslib.xrp.hardware

import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.kinematics.MecanumKinematics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class XrpMecanumOutputAuditTest {
    private class Motor(override val channel: Int) : XrpMotorIO {
        val failure = IllegalStateException("motor $channel failed")
        var rejectWrite = false
        var rejectStop = false
        var rejectUpdate = false
        var stops = 0
        var updates = 0
        override var effort = 0.0
            set(value) {
                if (rejectWrite && value != 0.0) throw failure
                field = value
            }
        override val positionRadians = 0.0
        override val velocityRadiansPerSecond = 0.0
        override fun update() { updates++; if (rejectUpdate) throw failure }
        override fun stop() { stops++; if (rejectStop) throw failure; effort = 0.0 }
    }

    private fun drive(motors: Array<Motor>) = StandardXrpMecanumHardwareIO(
        motors[0], motors[1], motors[2], motors[3]
    )

    @Test fun `saturated translation preserves all four wheel ratios`() {
        val motors = Array(4) { Motor(it + 1) }
        drive(motors).drive(ChassisSpeeds(1.2, 0.4, 0.0), 0.8)
        for (i in motors.indices) assertEquals(if (i == 0 || i == 3) 0.5 else 1.0, motors[i].effort, 1e-15)
    }

    @Test fun `any invalid power neutralizes the complete coupled vector`() {
        for (index in 0..3) for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val motors = Array(4) { Motor(it + 1) }
            val io = drive(motors)
            io.setPowers(0.4, 0.4, 0.4, 0.4)
            val power = DoubleArray(4) { 0.5 }.apply { this[index] = invalid }
            io.setPowers(power[0], power[1], power[2], power[3])
            for (motor in motors) assertEquals(0.0, motor.effort)
        }
    }

    @Test fun `a failed write at any position stops all motors and preserves the failure`() {
        for (index in 0..3) {
            val motors = Array(4) { Motor(it + 1) }
            val io = drive(motors)
            io.setPowers(0.3, 0.3, 0.3, 0.3)
            motors[index].rejectWrite = true
            assertSame(motors[index].failure, assertFailsWith<IllegalStateException> { io.setPowers(0.6, 0.6, 0.6, 0.6) })
            for (motor in motors) { assertEquals(1, motor.stops); assertEquals(0.0, motor.effort) }
        }
    }

    @Test fun `failed early stops do not skip later motors`() {
        val motors = Array(4) { Motor(it + 1).apply { rejectStop = true } }
        val failure = assertFailsWith<IllegalStateException> { drive(motors).stop() }
        assertSame(motors[0].failure, failure)
        for (motor in motors) assertEquals(1, motor.stops)
        assertEquals(3, failure.suppressed.size)
        for (i in 1..3) assertSame(motors[i].failure, failure.suppressed[i - 1])
    }

    @Test fun `an incomplete refresh at any position stops all motors`() {
        for (index in 0..3) {
            val motors = Array(4) { Motor(it + 1) }
            val io = drive(motors)
            io.setPowers(0.5, 0.5, 0.5, 0.5)
            motors[index].rejectUpdate = true
            assertSame(motors[index].failure, assertFailsWith<IllegalStateException> { io.update() })
            for ((i, motor) in motors.withIndex()) {
                assertEquals(if (i <= index) 1 else 0, motor.updates)
                assertEquals(1, motor.stops)
                assertEquals(0.0, motor.effort)
            }
        }
    }

    @Test fun `standard mecanum rejects invalid wheel radius`() {
        for (radius in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { StandardXrpMecanumHardwareIO(wheelRadiusMeters = radius) }
        }
    }

    @Test fun `invalid chassis limits and overflowing wheel vectors neutralize together`() {
        val motors = Array(4) { Motor(it + 1) }
        val io = drive(motors)
        val commands = mutableListOf(ChassisSpeeds(Double.MAX_VALUE, Double.MAX_VALUE, 0.0))
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            commands += ChassisSpeeds(bad, 0.0, 0.0)
            commands += ChassisSpeeds(0.0, bad, 0.0)
            commands += ChassisSpeeds(0.0, 0.0, bad)
        }
        for (speeds in commands) {
            io.setPowers(0.4, 0.4, 0.4, 0.4)
            io.drive(speeds)
            for (motor in motors) assertEquals(0.0, motor.effort)
        }
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            io.setPowers(0.4, 0.4, 0.4, 0.4)
            io.drive(ChassisSpeeds(0.3, 0.2, 0.1), bad)
            for (motor in motors) assertEquals(0.0, motor.effort)
        }
    }

    @Test fun `failed cleanup preserves primary failure and still attempts every motor`() {
        val motors = Array(4) { Motor(it + 1) }
        val io = drive(motors)
        motors[3].rejectWrite = true
        motors[0].rejectStop = true
        motors[1].rejectStop = true
        val failure = assertFailsWith<IllegalStateException> { io.setPowers(0.5, 0.5, 0.5, 0.5) }
        assertSame(motors[3].failure, failure)
        assertSame(motors[0].failure, failure.suppressed.single())
        assertSame(motors[1].failure, failure.suppressed.single().suppressed.single())
        for (motor in motors) assertEquals(1, motor.stops)
        assertEquals(0.0, motors[2].effort)
        assertEquals(0.0, motors[3].effort)
        // The throwing stops cannot prove that these outputs became neutral.
        assertEquals(0.5, motors[0].effort)
        assertEquals(0.5, motors[1].effort)
    }

    @Test fun `repeated same exception does not self suppress or skip later stops`() {
        val failure = IllegalStateException("shared device failure")
        var stops = 0
        fun motor(channel: Int) = object : XrpMotorIO {
            override val channel = channel
            override var effort: Double
                get() = 0.0
                set(value) { throw failure }
            override val positionRadians = 0.0
            override val velocityRadiansPerSecond = 0.0
            override fun update() = Unit
            override fun stop() { stops++; throw failure }
        }
        val io = StandardXrpMecanumHardwareIO(motor(1), motor(2), motor(3), motor(4))
        assertSame(failure, assertFailsWith<IllegalStateException> { io.setPowers(0.5, 0.5, 0.5, 0.5) })
        assertEquals(4, stops)
        assertTrue(failure.suppressed.isEmpty())
    }

    @Test fun `successful refresh reads each motor once and output methods do not refresh`() {
        val motors = Array(4) { Motor(it + 1) }
        val io = drive(motors)
        io.drive(ChassisSpeeds(0.2, 0.1, 0.3))
        io.setPowers(-2.0, -0.2, 0.3, 2.0)
        for (motor in motors) assertEquals(0, motor.updates)
        assertEquals(listOf(-1.0, -0.2, 0.3, 1.0), motors.map { it.effort })
        io.update()
        for (motor in motors) assertEquals(1, motor.updates)
        io.stop()
        for (motor in motors) { assertEquals(1, motor.updates); assertEquals(0.0, motor.effort) }
    }

    @Test fun `interface default drive obeys the same saturation and invalid input contract`() {
        val motors = Array(4) { Motor(it + 1) }
        val io = object : XrpMecanumHardwareIO {
            override val frontLeftMotor = motors[0]
            override val frontRightMotor = motors[1]
            override val backLeftMotor = motors[2]
            override val backRightMotor = motors[3]
            override val kinematics = MecanumKinematics(0.155, 0.140)
            override val wheelRadiusMeters = 0.03
        }
        io.drive(ChassisSpeeds(1.2, 0.4, 0.0), 0.8)
        for (i in motors.indices) assertEquals(if (i == 0 || i == 3) 0.5 else 1.0, motors[i].effort, 1e-15)
        io.drive(ChassisSpeeds(Double.NaN, 0.0, 0.0))
        for (motor in motors) assertEquals(0.0, motor.effort)
    }

    @Test fun `constructor validation does not call uninitialized subclass radius getters`() {
        val io = object : StandardXrpMecanumHardwareIO() {
            override val wheelRadiusMeters = 0.03
        }
        assertEquals(0.03, io.wheelRadiusMeters)
        val differential = object : StandardXrpDifferentialHardwareIO() {
            override val wheelRadiusMeters = 0.03
        }
        assertEquals(0.03, differential.wheelRadiusMeters)
    }

    @Test fun `subnormal maximum speed does not quantize representable mecanum powers`() {
        val io = StandardXrpMecanumHardwareIO()
        io.drive(ChassisSpeeds(1.2, 0.4, 0.0), Double.MIN_VALUE)
        assertEquals(0.5, io.frontLeftMotor.effort, 1e-15)
        assertEquals(1.0, io.frontRightMotor.effort)
        assertEquals(1.0, io.backLeftMotor.effort)
        assertEquals(0.5, io.backRightMotor.effort, 1e-15)
    }

    @Test fun `subnormal maximum speed does not quantize representable differential powers`() {
        val io = StandardXrpDifferentialHardwareIO(trackWidthMeters = 2.0)
        io.drive(ChassisSpeeds(1.5, 0.0, 0.5), Double.MIN_VALUE)
        assertEquals(0.5, io.leftMotor.effort)
        assertEquals(1.0, io.rightMotor.effort)
    }
}
