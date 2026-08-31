package com.areslib.telemetry.schema

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopDriveFrameGateTest {
    @Test
    fun `retained motion cannot arm and cannot renew the receiver lease`() {
        val gate = DesktopDriveFrameGate(timeoutMs = 200L)
        val retainedMotion = frame(session = 4, sequence = 1, vx = 1.0)

        assertFalse(gate.observe(retainedMotion, 1_000L))
        assertTrue(gate.observe(frame(session = 4, sequence = 2), 1_001L))
        assertFalse(gate.motionAuthorized)
        assertTrue(gate.observe(frame(session = 4, sequence = 3, vx = 1.0), 1_010L))
        assertTrue(gate.observe(frame(session = 4, sequence = 3, vx = 1.0), 1_209L))
        assertFalse(gate.observe(frame(session = 4, sequence = 3, vx = 1.0), 1_210L))
        assertFalse(gate.motionAuthorized)
        assertEquals(0.0, gate.vxMetersPerSecond)
    }

    @Test
    fun `acknowledgement exposes identity age and fail-closed expiry`() {
        val gate = DesktopDriveFrameGate(timeoutMs = 500L)
        val acknowledgement = DoubleArray(DesktopDriveFrameGate.ACK_VALUE_COUNT)

        assertTrue(gate.observe(frame(session = 91, sequence = 7), 5_000L))
        gate.copyAcknowledgement(acknowledgement, 5_050L)
        assertEquals(listOf(1.0, 2.0, 91.0, 7.0, 50.0), acknowledgement.take(5))

        gate.copyAcknowledgement(acknowledgement, 5_500L)
        assertEquals(DesktopDriveReceiverStatus.EXPIRED.code.toDouble(), acknowledgement[1])
        assertEquals(0.0, acknowledgement[5])
    }

    private fun frame(
        session: Long,
        sequence: Long,
        vx: Double = 0.0,
        vy: Double = 0.0,
        omega: Double = 0.0,
        flags: Long = DesktopDriveProtocol.FLAG_TELEOP or DesktopDriveProtocol.FLAG_FIELD_CENTRIC,
    ): DoubleArray = doubleArrayOf(
        DesktopDriveProtocol.VERSION,
        session.toDouble(),
        sequence.toDouble(),
        sequence.toDouble(),
        vx,
        vy,
        omega,
        flags.toDouble(),
    )
}
