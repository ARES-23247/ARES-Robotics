package com.areslib.ftc.drivetrain

import com.areslib.ftc.MockDcMotorEx
import com.areslib.util.RobotClock
import com.qualcomm.robotcore.hardware.DcMotorEx
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EstimateMotorTimingTest {
    private class Encoder : DcMotorEx by MockDcMotorEx() {
        var ticks = 0
        var reads = 0
        override val currentPosition: Int get() { reads++; return ticks }
    }

    @AfterEach fun restoreClock() = RobotClock.useSystemTime()

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
        assertEquals(0.0, io.velocity)
        RobotClock.useMockTime(25L)
        encoder.ticks = 210
        io.updateInputs()
        assertEquals(500.0, io.velocity, 1e-9)
    }
}
