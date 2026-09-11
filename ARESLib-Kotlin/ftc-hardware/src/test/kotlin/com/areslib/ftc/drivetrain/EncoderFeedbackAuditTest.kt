package com.areslib.ftc.drivetrain

import com.areslib.ftc.MockDcMotorEx
import com.areslib.util.RobotClock
import com.qualcomm.robotcore.hardware.DcMotorEx
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EncoderFeedbackAuditTest {
    private class Encoder(val motor: MockDcMotorEx = MockDcMotorEx()) : DcMotorEx by motor {
        var ticks = 0
        var fail = false
        var reads = 0
        var currentReads = 0
        override val currentPosition: Int get() {
            reads++
            check(!fail) { "encoder unavailable" }
            return ticks
        }
        override fun getCurrent(unit: CurrentUnit): Double { currentReads++; return 1.0 }
    }

    @AfterEach fun restoreClock() = RobotClock.useSystemTime()

    @Test fun `unobserved encoder is unavailable rather than stationary`() {
        val io = EstimateMotorIO(Encoder())
        assertTrue(io.position.isNaN())
        assertTrue(io.velocity.isNaN())
    }

    @Test fun `read failure invalidates both cached measurements`() {
        val encoder = Encoder()
        val io = EstimateMotorIO(encoder)
        RobotClock.useMockTime(1000L)
        io.updateInputs()
        encoder.fail = true
        io.updateInputs()
        assertTrue(io.position.isNaN())
        assertTrue(io.velocity.isNaN())
    }

    @Test fun `first position sample alone cannot establish measured velocity`() {
        val io = EstimateMotorIO(Encoder())
        RobotClock.useMockTime(1000L)
        io.updateInputs()
        assertEquals(0.0, io.position)
        assertTrue(io.velocity.isNaN())
    }

    @Test fun `failed read recovery establishes a new derivative baseline`() {
        val encoder = Encoder()
        val io = EstimateMotorIO(encoder)
        RobotClock.useMockTime(1000L)
        io.updateInputs()
        encoder.fail = true
        RobotClock.useMockTime(1020L)
        io.updateInputs()
        encoder.fail = false
        encoder.ticks = 500
        RobotClock.useMockTime(1040L)
        io.updateInputs()
        assertEquals(500.0, io.position)
        assertTrue(io.velocity.isNaN())
        encoder.ticks = 520
        RobotClock.useMockTime(1060L)
        io.updateInputs()
        assertEquals(1000.0, io.velocity)
    }

    @Test fun `cached encoder expires without additional device reads`() {
        val encoder = Encoder()
        val io = EstimateMotorIO(encoder)
        RobotClock.useMockTime(1000L)
        io.updateInputs()
        RobotClock.useMockTime(1100L)
        assertEquals(0.0, io.position)
        RobotClock.useMockTime(1101L)
        assertTrue(io.position.isNaN())
        assertTrue(io.velocity.isNaN())
        assertEquals(1, encoder.reads)
    }

    @Test fun `future cached encoder is invalid after a clock rewind`() {
        val io = EstimateMotorIO(Encoder())
        RobotClock.useMockTime(1000L)
        io.updateInputs()
        RobotClock.useMockTime(999L)
        assertTrue(io.position.isNaN())
        assertTrue(io.velocity.isNaN())
    }

    @Test fun `long gap establishes baseline rather than averaging unobserved motion`() {
        val encoder = Encoder()
        val io = EstimateMotorIO(encoder)
        RobotClock.useMockTime(1000L)
        io.updateInputs()
        encoder.ticks = 1000
        RobotClock.useMockTime(1500L)
        io.updateInputs()
        assertEquals(1000.0, io.position)
        assertTrue(io.velocity.isNaN())
    }

    @Test fun `closed cache cannot repoll or resurrect measurements`() {
        val encoder = Encoder()
        val io = EstimateMotorIO(encoder)
        RobotClock.useMockTime(1000L)
        io.updateInputs()
        io.pollSync()
        io.close()
        io.updateInputs()
        io.pollSync()
        assertTrue(io.position.isNaN())
        assertTrue(io.velocity.isNaN())
        assertTrue(io.currentAmps.isNaN())
        assertEquals(1, encoder.reads)
        assertEquals(1, encoder.currentReads)
    }

    private fun powers(controller: MecanumDriveFeedforward, feedback: Double, ticks: Double = 2000.0,
                       hubClosedLoop: Boolean = false): DoubleArray = DoubleArray(4).also {
        controller.calculateMotorPowers(doubleArrayOf(1.0, 1.0, 1.0, 1.0), 3.5, 12.0, 0.02,
            hubClosedLoop, ticks, feedback, 0.0, 0.0, 0.0, it)
    }

    @Test fun `software feedback cannot replace an invalid wheel with zero speed`() {
        val controller = MecanumDriveFeedforward(motorKp = 0.1).apply { kV = 0.2 }
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            assertTrue(powers(controller, bad).all { it == 0.0 })
        }
        assertTrue(powers(controller, 0.0).all { it > 0.0 })
    }

    @Test fun `software feedback rejects unavailable or reversed conversion scales`() {
        val controller = MecanumDriveFeedforward(motorKp = 0.1).apply { kV = 0.2 }
        for (bad in listOf(Double.NaN, 0.0, -2000.0, Double.POSITIVE_INFINITY)) {
            assertTrue(powers(controller, 0.0, bad).all { it == 0.0 })
        }
    }

    @Test fun `software feedback uses measured ticks with one positive unit conversion`() {
        val controller = MecanumDriveFeedforward(motorKp = 0.1).apply { kV = 0.2 }
        val output = powers(controller, 1000.0)
        assertEquals(0.25, output[0], 1e-12)
        assertEquals(0.3, output[1], 1e-12)
    }

    @Test fun `hub velocity mode also requires available encoder feedback`() {
        val controller = MecanumDriveFeedforward().apply { kV = 0.2 }
        assertTrue(powers(controller, Double.NaN, hubClosedLoop = true).all { it == 0.0 })
    }

    @Test fun `feedforward only mode does not invent an encoder dependency`() {
        val controller = MecanumDriveFeedforward().apply { kV = 0.2 }
        assertTrue(powers(controller, Double.NaN).all { it == 0.2 })
    }

    @Test fun `failed encoder neutralizes the real four wheel facade without output reads`() {
        val encoders = Array(4) { Encoder() }
        val map = object : com.qualcomm.robotcore.hardware.HardwareMap() {
            @Suppress("UNCHECKED_CAST")
            override fun <T> get(type: Class<out T>, name: String): T =
                encoders[listOf("fl", "fr", "rl", "rr").indexOf(name)] as T
            override fun <T> getAll(type: Class<out T>): List<T> = emptyList()
        }
        val registry = com.areslib.hardware.HardwareRegistry()
        try {
            val io = MecanumHardwareIO(map, registry, motorKp = 0.1)
            io.kV = 0.2
            RobotClock.useMockTime(1000L)
            io.updateInputs()
            RobotClock.useMockTime(1020L)
            io.updateInputs()
            val targets = doubleArrayOf(1.0, 1.0, 1.0, 1.0)
            io.apply(targets)
            assertTrue(encoders.all { it.motor.currentPower > 0.0 })
            encoders[1].fail = true
            RobotClock.useMockTime(1040L)
            io.updateInputs()
            val reads = encoders.sumOf { it.reads }
            io.apply(targets)
            assertTrue(encoders.all { it.motor.currentPower == 0.0 })
            assertEquals(reads, encoders.sumOf { it.reads })
        } finally {
            registry.closeAll()
        }
    }
}
