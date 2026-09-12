package com.areslib.telemetry

import com.areslib.telemetry.schema.DesktopDriveFrameGate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DriveFrameDiagnosticBoundaryAuditTest {
    @Test fun `simulator rejection diagnostics saturate without negative rollover`() {
        val receiver = DriveFrameReceiver()
        receiver.javaClass.getDeclaredField("rejectedFrameCount").apply { isAccessible = true }
            .setLong(receiver, Long.MAX_VALUE - 1L)
        repeat(3) { receiver.acceptFrame(DoubleArray(0), 1000) }
        val ack = DoubleArray(9)
        receiver.copyAcknowledgement(ack, 1000)
        assertEquals(Long.MAX_VALUE.toDouble(), ack[8])
    }

    @Test fun `both receivers preserve every canonical v2 flag mapping`() {
        for (flags in 0L..1023L) {
            val receiver = DriveFrameReceiver()
            val gate = DesktopDriveFrameGate()
            val neutral = doubleArrayOf(2.0, 7.0, 0.0, 0.0, 0.0, 0.0, 0.0, 56.0)
            receiver.acceptFrame(neutral, 1000)
            assertTrue(gate.observe(neutral, 1000))
            val payload = doubleArrayOf(2.0, 7.0, 1.0, 1.0, 1.0, -2.0, 3.0, flags.toDouble())
            val received = receiver.acceptFrame(payload, 1001)
            assertTrue(gate.observe(payload, 1001))
            val expected = List(10) { flags and (1L shl it) != 0L }
            assertEquals(expected, listOf(received.isIntaking, received.isFlywheelOn, received.isTransferring,
                received.isTeleopMode, received.isFieldCentric, received.isRedAlliance, received.isButtonAPressed,
                received.isButtonBPressed, received.isButtonXPressed, received.isPoseReset))
            assertEquals(flags, gate.flags)
            assertEquals(listOf(1.0, -2.0, 3.0), listOf(received.vx, received.vy, received.omega))
        }
    }
}
