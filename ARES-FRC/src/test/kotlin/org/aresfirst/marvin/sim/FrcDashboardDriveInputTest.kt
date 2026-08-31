package org.aresfirst.marvin.sim

import com.areslib.telemetry.GamepadState
import com.areslib.telemetry.schema.DesktopDriveFrameGate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrcDashboardDriveInputTest {
    @Test
    fun `new session requires neutral before motion and maps FRC field axes`() {
        val gate = DesktopDriveFrameGate(timeoutMs = 500L)

        assertFalse(gate.observe(frame(sequence = 1, vx = 4.0), timestampMs = 1_000))
        assertTrue(gate.observe(frame(sequence = 2), timestampMs = 1_020))
        assertTrue(gate.observe(frame(sequence = 3, vx = 4.0, vy = -2.0, omega = Math.PI), timestampMs = 1_040))

        val command = requireNotNull(gate.takeIf { it.receiverReady(1_040) })
        val controller = GamepadState()
        command.applyTo(controller)

        assertEquals((-4.0 / 4.5).toFloat(), controller.leftStickY)
        assertEquals((2.0 / 4.5).toFloat(), controller.leftStickX)
        assertEquals(-1.0f, controller.rightStickX)
    }

    @Test
    fun `receiver lease expires and requires a new neutral handshake`() {
        val gate = DesktopDriveFrameGate(timeoutMs = 500L)
        assertTrue(gate.observe(frame(sequence = 1), timestampMs = 2_000))
        assertTrue(gate.observe(frame(sequence = 2, vx = 1.0), timestampMs = 2_020))

        assertFalse(gate.receiverReady(2_521))
        assertFalse(gate.observe(frame(sequence = 3, vx = 1.0), timestampMs = 2_540))
        assertTrue(gate.observe(frame(sequence = 4), timestampMs = 2_560))
        assertTrue(gate.observe(frame(sequence = 5, vx = 1.0), timestampMs = 2_580))
    }

    @Test
    fun `mode and alliance flags remain explicit`() {
        val gate = DesktopDriveFrameGate(timeoutMs = 500L)
        val flags = TELEOP or FIELD_CENTRIC or RED_ALLIANCE
        assertTrue(gate.observe(frame(sequence = 1, flags = flags), timestampMs = 3_000))

        val command = requireNotNull(gate.takeIf { it.receiverReady(3_000) })
        assertTrue(command.isTeleopMode)
        assertTrue(command.isFieldCentric)
        assertTrue(command.isRedAlliance)
    }

    @Test
    fun `invalid or unknown frame fails closed`() {
        val gate = DesktopDriveFrameGate(timeoutMs = 500L)
        assertTrue(gate.observe(frame(sequence = 1), timestampMs = 4_000))
        assertFalse(gate.observe(frame(sequence = 2, vx = Double.NaN), timestampMs = 4_020))
        assertFalse(gate.receiverReady(4_020))
        assertFalse(gate.observe(frame(sequence = 3, flags = 1L shl 12), timestampMs = 4_040))
    }

    @Test
    fun `receiver acknowledgement reports identity freshness and fail closed expiry`() {
        val gate = DesktopDriveFrameGate(timeoutMs = 500L)
        val acknowledgement = DoubleArray(DesktopDriveFrameGate.ACK_VALUE_COUNT)
        assertTrue(gate.observe(frame(session = 91, sequence = 7), timestampMs = 5_000))

        gate.copyAcknowledgement(acknowledgement, timestampMs = 5_050)
        assertEquals(listOf(1.0, 2.0, 91.0, 7.0, 50.0), acknowledgement.take(5))

        gate.copyAcknowledgement(acknowledgement, timestampMs = 5_501)
        assertEquals(4.0, acknowledgement[1])
        assertEquals(0.0, acknowledgement[5])
    }

    private fun frame(
        session: Long = 42,
        sequence: Long,
        vx: Double = 0.0,
        vy: Double = 0.0,
        omega: Double = 0.0,
        flags: Long = TELEOP or FIELD_CENTRIC,
    ): DoubleArray = doubleArrayOf(
        2.0,
        session.toDouble(),
        sequence.toDouble(),
        (10_000 + sequence).toDouble(),
        vx,
        vy,
        omega,
        flags.toDouble(),
    )

    companion object {
        private const val TELEOP = 1L shl 3
        private const val FIELD_CENTRIC = 1L shl 4
        private const val RED_ALLIANCE = 1L shl 5
    }
}
