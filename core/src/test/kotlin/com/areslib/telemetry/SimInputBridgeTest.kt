package com.areslib.telemetry

import com.areslib.networktables.NT4Server
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SimInputBridgeTest {
    @AfterEach
    fun resetBridgeAndClock() {
        SimInputBridge.reset()
        NT4Server.getInstance()?.stop()
        NT4Server.resetSharedState()
        RobotClock.useSystemTime()
    }

    @Test
    fun `valid frame swaps atomically and expires to neutral`() {
        RobotClock.useMockTime(1_000L)
        val accepted = SimInputBridge.submitFrame(frame(vx = 2.0, vy = -1.0, omega = 0.5, receivedAtMs = 1_000L))

        assertTrue(accepted)
        assertEquals(2.0, SimInputBridge.currentFrame(1_499L).vx)
        val expired = SimInputBridge.currentFrame(1_501L)
        assertEquals(0.0, expired.vx)
        assertEquals(0.0, expired.vy)
        assertEquals(0.0, expired.omega)
        assertFalse(expired.isIntaking)
    }

    @Test
    fun `one invalid field rejects the entire command frame`() {
        SimInputBridge.submitFrame(frame(vx = 1.0, vy = 2.0, omega = 3.0, receivedAtMs = 100L))

        assertFalse(SimInputBridge.submitFrame(frame(vx = Double.NaN, vy = 7.0, omega = 8.0, receivedAtMs = 110L)))
        val retained = SimInputBridge.currentFrame(120L)
        assertEquals(1.0, retained.vx)
        assertEquals(2.0, retained.vy)
        assertEquals(3.0, retained.omega)
    }

    @Test
    fun `atomic drive session requires neutral first frame and increasing sequence`() {
        NT4Server.createInstance("127.0.0.1", 0)

        publishDriveFrame(session = 41L, sequence = 0L, timestampMs = 1_000L, vx = 2.0)
        assertEquals(0.0, SimInputBridge.pollNetworkFrame(1_000L).vx)

        // Once an atomic publisher is observed, legacy scalars cannot bypass its handshake.
        NT4Server.publishTopic("ARES/Input/vx", 3.0)
        NT4Server.publishTopic("ARES/Input/heartbeat", 1L)
        assertEquals(0.0, SimInputBridge.pollNetworkFrame(1_010L).vx)

        publishDriveFrame(session = 41L, sequence = 1L, timestampMs = 1_020L, vx = 0.0)
        assertEquals(0.0, SimInputBridge.pollNetworkFrame(1_020L).vx)

        publishDriveFrame(session = 41L, sequence = 2L, timestampMs = 1_040L, vx = 2.0)
        assertEquals(2.0, SimInputBridge.pollNetworkFrame(1_040L).vx)

        // A retained/replayed sequence never refreshes the command lease.
        assertEquals(0.0, SimInputBridge.pollNetworkFrame(1_541L).vx)
    }

    @Test
    fun `new atomic session fails neutral until its own neutral handshake`() {
        NT4Server.createInstance("127.0.0.1", 0)
        publishDriveFrame(session = 1L, sequence = 0L, timestampMs = 100L, vx = 0.0)
        SimInputBridge.pollNetworkFrame(100L)
        publishDriveFrame(session = 1L, sequence = 1L, timestampMs = 120L, vx = 1.0)
        assertEquals(1.0, SimInputBridge.pollNetworkFrame(120L).vx)

        publishDriveFrame(session = 2L, sequence = 0L, timestampMs = 130L, vx = 4.0)
        assertEquals(0.0, SimInputBridge.pollNetworkFrame(130L).vx)
        publishDriveFrame(session = 2L, sequence = 1L, timestampMs = 140L, vx = 0.0)
        assertEquals(0.0, SimInputBridge.pollNetworkFrame(140L).vx)
        publishDriveFrame(session = 2L, sequence = 2L, timestampMs = 160L, vx = 4.0)
        assertEquals(4.0, SimInputBridge.pollNetworkFrame(160L).vx)
    }

    @Test
    fun `expired same session requires a higher sequence neutral handshake before motion`() {
        NT4Server.createInstance("127.0.0.1", 0)
        publishDriveFrame(session = 7L, sequence = 0L, timestampMs = 100L, vx = 0.0)
        SimInputBridge.pollNetworkFrame(100L)
        publishDriveFrame(session = 7L, sequence = 1L, timestampMs = 120L, vx = 2.0)
        assertEquals(2.0, SimInputBridge.pollNetworkFrame(120L).vx)

        assertEquals(0.0, SimInputBridge.pollNetworkFrame(621L).vx)
        publishDriveFrame(session = 7L, sequence = 2L, timestampMs = 640L, vx = 3.0)
        assertEquals(0.0, SimInputBridge.pollNetworkFrame(640L).vx)
        publishDriveFrame(session = 7L, sequence = 3L, timestampMs = 660L, vx = 0.0)
        assertEquals(0.0, SimInputBridge.pollNetworkFrame(660L).vx)
        publishDriveFrame(session = 7L, sequence = 4L, timestampMs = 680L, vx = 3.0)
        assertEquals(3.0, SimInputBridge.pollNetworkFrame(680L).vx)
    }

    @Test
    fun `malformed atomic frame disarms until a later neutral handshake`() {
        NT4Server.createInstance("127.0.0.1", 0)
        publishDriveFrame(session = 8L, sequence = 0L, timestampMs = 100L, vx = 0.0)
        SimInputBridge.pollNetworkFrame(100L)
        publishDriveFrame(session = 8L, sequence = 1L, timestampMs = 120L, vx = 2.0)
        assertEquals(2.0, SimInputBridge.pollNetworkFrame(120L).vx)

        NT4Server.publishTopic(
            TelemetryTopicConstants.DRIVE_INPUT_FRAME,
            doubleArrayOf(1.0, 8.0, 2.0, 130.0, 4.0, 0.0)
        )
        assertEquals(0.0, SimInputBridge.pollNetworkFrame(130L).vx)
        publishDriveFrame(session = 8L, sequence = 2L, timestampMs = 140L, vx = 4.0)
        assertEquals(0.0, SimInputBridge.pollNetworkFrame(140L).vx)
        publishDriveFrame(session = 8L, sequence = 3L, timestampMs = 160L, vx = 0.0)
        SimInputBridge.pollNetworkFrame(160L)
        publishDriveFrame(session = 8L, sequence = 4L, timestampMs = 180L, vx = 4.0)
        assertEquals(4.0, SimInputBridge.pollNetworkFrame(180L).vx)
    }

    @Test
    fun `out of order atomic frame disarms until a higher sequence neutral handshake`() {
        NT4Server.createInstance("127.0.0.1", 0)
        publishDriveFrame(session = 9L, sequence = 5L, timestampMs = 100L, vx = 0.0)
        SimInputBridge.pollNetworkFrame(100L)
        publishDriveFrame(session = 9L, sequence = 6L, timestampMs = 120L, vx = 2.0)
        assertEquals(2.0, SimInputBridge.pollNetworkFrame(120L).vx)

        publishDriveFrame(session = 9L, sequence = 4L, timestampMs = 130L, vx = 2.0)
        assertEquals(0.0, SimInputBridge.pollNetworkFrame(130L).vx)
        publishDriveFrame(session = 9L, sequence = 7L, timestampMs = 140L, vx = 3.0)
        assertEquals(0.0, SimInputBridge.pollNetworkFrame(140L).vx)
        publishDriveFrame(session = 9L, sequence = 8L, timestampMs = 160L, vx = 0.0)
        SimInputBridge.pollNetworkFrame(160L)
        publishDriveFrame(session = 9L, sequence = 9L, timestampMs = 180L, vx = 3.0)
        assertEquals(3.0, SimInputBridge.pollNetworkFrame(180L).vx)
    }

    private fun publishDriveFrame(
        session: Long,
        sequence: Long,
        timestampMs: Long,
        vx: Double
    ) {
        NT4Server.publishTopic(
            TelemetryTopicConstants.DRIVE_INPUT_FRAME,
            doubleArrayOf(1.0, session.toDouble(), sequence.toDouble(), timestampMs.toDouble(), vx, 0.0, 0.0)
        )
    }

    private fun frame(vx: Double, vy: Double, omega: Double, receivedAtMs: Long) =
        SimInputBridge.CommandFrame(
            vx = vx,
            vy = vy,
            omega = omega,
            isIntaking = true,
            isFlywheelOn = false,
            isTransferring = false,
            isTeleopMode = true,
            isFieldCentric = true,
            isRedAlliance = true,
            isButtonAPressed = false,
            isButtonBPressed = false,
            isButtonXPressed = false,
            isPoseReset = false,
            heartbeat = 1L,
            receivedAtMs = receivedAtMs
        )
}
