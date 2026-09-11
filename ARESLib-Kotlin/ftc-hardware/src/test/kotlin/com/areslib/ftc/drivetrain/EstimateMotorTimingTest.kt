package com.areslib.ftc.drivetrain

import com.areslib.ftc.MockDcMotorEx
import com.areslib.util.RobotClock
import com.qualcomm.robotcore.hardware.DcMotorEx
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EstimateMotorTimingTest {
    private class Encoder : DcMotorEx by MockDcMotorEx() {
        var ticks = 0
        var reads = 0
        override val currentPosition: Int get() { reads++; return ticks }
    }

    @AfterEach fun restoreClock() = RobotClock.useSystemTime()

    @Test fun `read only reset contract preserves the reference and performs no device read`() {
        val encoder = Encoder().apply { ticks = 123 }
        val io = EstimateMotorIO(encoder)
        RobotClock.useMockTime(1000L)
        io.updateInputs()
        io.resetEncoder()
        assertEquals(123.0, io.position)
        assertEquals(1, encoder.reads)
        encoder.ticks = 133
        RobotClock.useMockTime(1020L)
        io.updateInputs()
        assertEquals(133.0, io.position)
        assertEquals(500.0, io.velocity)
    }

    @Test fun `forward signed counter rollover preserves displacement and velocity`() {
        val encoder = Encoder().apply { ticks = Int.MAX_VALUE - 5 }
        val io = EstimateMotorIO(encoder)
        RobotClock.useMockTime(1000L)
        io.updateInputs()
        val before = io.position
        encoder.ticks = Int.MIN_VALUE + 4
        RobotClock.useMockTime(1020L)
        io.updateInputs()
        assertEquals(before + 10.0, io.position)
        assertEquals(500.0, io.velocity)
    }

    @Test fun `reverse signed counter rollover preserves displacement and velocity`() {
        val encoder = Encoder().apply { ticks = Int.MIN_VALUE + 5 }
        val io = EstimateMotorIO(encoder)
        RobotClock.useMockTime(1000L)
        io.updateInputs()
        val before = io.position
        encoder.ticks = Int.MAX_VALUE - 4
        RobotClock.useMockTime(1020L)
        io.updateInputs()
        assertEquals(before - 10.0, io.position)
        assertEquals(-500.0, io.velocity)
    }

    @Test fun `same timestamp rollover does not discard the derivative displacement`() {
        val encoder = Encoder().apply { ticks = Int.MAX_VALUE - 5 }
        val io = EstimateMotorIO(encoder)
        RobotClock.useMockTime(1000L)
        io.updateInputs()
        val before = io.position
        encoder.ticks = Int.MIN_VALUE + 4
        io.updateInputs()
        encoder.ticks = Int.MIN_VALUE + 14
        RobotClock.useMockTime(1020L)
        io.updateInputs()
        assertEquals(before + 20.0, io.position)
        assertEquals(1000.0, io.velocity)
    }

    @Test
    fun `same millisecond samples do not discard displacement from velocity`() {
        val encoder = Encoder()
        val io = EstimateMotorIO(encoder)
        RobotClock.useMockTime(1000L)
        io.updateInputs()
        encoder.ticks = 10
        io.updateInputs()
        assertEquals(10.0, io.position)
        RobotClock.useMockTime(1020L)
        encoder.ticks = 20
        io.updateInputs()
        assertEquals(1000.0, io.velocity, 1e-9)
        repeat(100) { io.position; io.velocity }
        assertEquals(3, encoder.reads, "Getters must consume the cached sample")
    }

    @Test
    fun `time zero is a valid first sample and replay rewind rebases velocity`() {
        val encoder = Encoder()
        val io = EstimateMotorIO(encoder)
        RobotClock.useMockTime(0L)
        io.updateInputs()
        RobotClock.useMockTime(20L)
        encoder.ticks = 20
        io.updateInputs()
        assertEquals(1000.0, io.velocity, 1e-9)
        RobotClock.useMockTime(5L)
        encoder.ticks = 200
        io.updateInputs()
        assertTrue(io.velocity.isNaN(), "A replay rewind requires a new pair of observations")
        RobotClock.useMockTime(25L)
        encoder.ticks = 210
        io.updateInputs()
        assertEquals(500.0, io.velocity, 1e-9)
    }
}
