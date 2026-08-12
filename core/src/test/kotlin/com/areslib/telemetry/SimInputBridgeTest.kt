package com.areslib.telemetry

import com.areslib.networktables.NT4Server
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SimInputBridgeTest {
    @BeforeEach
    fun startRegistry() {
        SimInputBridge.reset()
        NT4Server.createInstance("127.0.0.1", 0)
    }

    @AfterEach
    fun resetBridgeAndClock() {
        SimInputBridge.reset()
        NT4Server.getInstance()?.stop()
        NT4Server.resetSharedState()
        RobotClock.useSystemTime()
    }

    @Test
    fun `session requires neutral first then accepts exact v2 frame until receiver lease expires`() {
        publish(session = 41, sequence = 0, clientTime = 1_000, vx = 2.0)
        assertNeutral(SimInputBridge.pollNetworkFrame(10_000))

        publish(session = 41, sequence = 1, clientTime = 1_001, flags = MODE_FLAGS)
        val handshake = SimInputBridge.pollNetworkFrame(10_010)
        assertEquals(true, handshake.isTeleopMode)
        assertEquals(true, handshake.isFieldCentric)
        assertEquals(true, handshake.isRedAlliance)

        publish(session = 41, sequence = 2, clientTime = 1_002, vx = 2.0, vy = -1.0,
            omega = 0.5, flags = MODE_FLAGS or INTAKE_FLAG)
        assertEquals(2.0, SimInputBridge.pollNetworkFrame(10_020).vx)
        assertEquals(2.0, SimInputBridge.currentFrame(10_520).vx)
        assertNeutral(SimInputBridge.currentFrame(10_521))
    }

    @Test
    fun `retained duplicate cannot renew lease and same-sequence mutation disarms`() {
        publish(session = 7, sequence = 0, clientTime = 100)
        SimInputBridge.pollNetworkFrame(1_000)
        publish(session = 7, sequence = 1, clientTime = 101, vx = 1.0)
        SimInputBridge.pollNetworkFrame(1_020)

        assertEquals(1.0, SimInputBridge.pollNetworkFrame(1_500).vx)
        assertNeutral(SimInputBridge.pollNetworkFrame(1_521))

        publish(session = 7, sequence = 2, clientTime = 102)
        SimInputBridge.pollNetworkFrame(1_530)
        publish(session = 7, sequence = 3, clientTime = 103, vx = 2.0)
        SimInputBridge.pollNetworkFrame(1_540)
        publish(session = 7, sequence = 3, clientTime = 103, vx = 3.0)
        assertNeutral(SimInputBridge.pollNetworkFrame(1_550))
    }

    @Test
    fun `malformed stale or out-of-order input fully neutralizes and requires another handshake`() {
        publish(session = 9, sequence = 5, clientTime = 500)
        SimInputBridge.pollNetworkFrame(100)
        publish(session = 9, sequence = 6, clientTime = 501, vx = 2.0, flags = ALL_FLAGS)
        assertEquals(2.0, SimInputBridge.pollNetworkFrame(120).vx)

        publish(session = 9, sequence = 7, clientTime = 499, vx = 3.0, flags = ALL_FLAGS)
        assertNeutral(SimInputBridge.pollNetworkFrame(130))
        publish(session = 9, sequence = 8, clientTime = 502, vx = 3.0)
        assertNeutral(SimInputBridge.pollNetworkFrame(140))
        publish(session = 9, sequence = 9, clientTime = 503)
        assertNeutral(SimInputBridge.pollNetworkFrame(150))
        publish(session = 9, sequence = 10, clientTime = 504, vx = 3.0)
        assertEquals(3.0, SimInputBridge.pollNetworkFrame(160).vx)

        publishRaw(doubleArrayOf(2.0, 9.0, 11.0, 505.0, Double.NaN, 0.0, 0.0, 0.0))
        assertNeutral(SimInputBridge.pollNetworkFrame(170))
    }

    @Test
    fun `new session must independently neutral handshake and flags are strict integral bitset`() {
        publish(session = 1, sequence = 0, clientTime = 0)
        SimInputBridge.pollNetworkFrame(100)
        publish(session = 1, sequence = 1, clientTime = 1, vx = 1.0)
        assertEquals(1.0, SimInputBridge.pollNetworkFrame(110).vx)

        publish(session = 2, sequence = 0, clientTime = 0, vx = 4.0)
        assertNeutral(SimInputBridge.pollNetworkFrame(120))
        publish(session = 2, sequence = 1, clientTime = 1, flags = MODE_FLAGS)
        SimInputBridge.pollNetworkFrame(130)
        publish(session = 2, sequence = 2, clientTime = 2, flags = ALL_FLAGS)
        val decoded = SimInputBridge.pollNetworkFrame(140)
        assertTrue(decoded.isIntaking)
        assertTrue(decoded.isFlywheelOn)
        assertTrue(decoded.isTransferring)
        assertTrue(decoded.isButtonAPressed)
        assertTrue(decoded.isButtonBPressed)
        assertTrue(decoded.isButtonXPressed)
        assertTrue(decoded.isPoseReset)

        publishRaw(doubleArrayOf(2.0, 2.0, 3.0, 3.0, 0.0, 0.0, 0.0, 1.5))
        assertNeutral(SimInputBridge.pollNetworkFrame(150))
        publishRaw(doubleArrayOf(2.0, 2.0, 4.0, 4.0, 0.0, 0.0, 0.0, 1024.0))
        assertNeutral(SimInputBridge.pollNetworkFrame(160))
    }

    @Test
    fun `wrong version length nonce sequence and axis bounds are rejected`() {
        val badFrames = listOf(
            doubleArrayOf(2.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            doubleArrayOf(1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            doubleArrayOf(2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            doubleArrayOf(2.0, 1.0, -1.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            doubleArrayOf(2.0, 1.0, 0.0, 0.0, 8.01, 0.0, 0.0, 0.0),
            doubleArrayOf(2.0, 1.0, 0.0, 0.0, 0.0, 0.0, 4.0 * Math.PI + 0.01, 0.0)
        )
        for (bad in badFrames) {
            publishRaw(bad)
            assertNeutral(SimInputBridge.pollNetworkFrame(100))
        }
    }

    private fun publish(
        session: Long,
        sequence: Long,
        clientTime: Long,
        vx: Double = 0.0,
        vy: Double = 0.0,
        omega: Double = 0.0,
        flags: Long = 0L
    ) = publishRaw(
        doubleArrayOf(2.0, session.toDouble(), sequence.toDouble(), clientTime.toDouble(), vx, vy, omega, flags.toDouble())
    )

    private fun publishRaw(values: DoubleArray) {
        NT4Server.publishTopic(TelemetryTopicConstants.DRIVE_INPUT_FRAME, values)
    }

    private fun assertNeutral(frame: SimInputBridge.CommandFrame) {
        assertEquals(0.0, frame.vx)
        assertEquals(0.0, frame.vy)
        assertEquals(0.0, frame.omega)
        assertFalse(frame.isIntaking)
        assertFalse(frame.isFlywheelOn)
        assertFalse(frame.isTransferring)
        assertFalse(frame.isTeleopMode)
        assertFalse(frame.isFieldCentric)
        assertFalse(frame.isRedAlliance)
        assertFalse(frame.isButtonAPressed)
        assertFalse(frame.isButtonBPressed)
        assertFalse(frame.isButtonXPressed)
        assertFalse(frame.isPoseReset)
    }

    private companion object {
        const val INTAKE_FLAG = 1L shl 0
        const val MODE_FLAGS = (1L shl 3) or (1L shl 4) or (1L shl 5)
        const val ALL_FLAGS = (1L shl 10) - 1L
    }
}
