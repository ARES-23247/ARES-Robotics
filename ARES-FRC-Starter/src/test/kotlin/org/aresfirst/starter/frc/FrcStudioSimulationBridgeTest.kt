package org.aresfirst.starter.frc

import com.areslib.input.InputFrame
import com.areslib.state.FieldType
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrcStudioSimulationBridgeTest {
    @Test
    fun `Studio mode command keeps autonomous distinct from leased TeleOp`() {
        assertEquals(FrcStudioRequestedMode.TELEOP, decodeFrcStudioRequestedMode(" ENABLE_TELEOP "))
        assertEquals(FrcStudioRequestedMode.AUTONOMOUS, decodeFrcStudioRequestedMode("enable_autonomous"))
        assertEquals(FrcStudioRequestedMode.DISABLED, decodeFrcStudioRequestedMode("DISABLE"))
        assertEquals(FrcStudioRequestedMode.DISABLED, decodeFrcStudioRequestedMode("unexpected"))
    }

    @Test
    fun `new session requires neutral then accepts fresh motion`() {
        val gate = FrcStudioDriveFrameGate()

        assertFalse(gate.accept(frame(session = 7.0, sequence = 0.0, vx = 2.0), nowMs = 100L))
        assertEquals(1, gate.statusCode())
        assertTrue(gate.accept(frame(session = 7.0, sequence = 1.0), nowMs = 110L))
        assertTrue(gate.accept(frame(session = 7.0, sequence = 2.0, vx = 2.0), nowMs = 120L))
        assertEquals(2.0, gate.current(120L)?.vxMetersPerSecond)
        assertEquals(3, gate.statusCode())
    }

    @Test
    fun `expired and out of order controls fail closed`() {
        val gate = FrcStudioDriveFrameGate()
        assertTrue(gate.accept(frame(session = 8.0, sequence = 0.0), nowMs = 1_000L))
        assertNull(gate.current(1_501L))
        assertEquals(4, gate.statusCode())
        assertFalse(gate.accept(frame(session = 8.0, sequence = 0.0), nowMs = 1_502L))
        assertEquals(6, gate.statusCode())
    }

    @Test
    fun `ack reports accepted identity applied command and lease expiry`() {
        val gate = FrcStudioDriveFrameGate()
        val ack = DoubleArray(FrcStudioDriveFrameGate.ACK_VALUE_COUNT)
        assertTrue(gate.accept(frame(session = 9.0, sequence = 4.0), nowMs = 200L))
        gate.copyAcknowledgement(ack, nowMs = 250L)
        assertEquals(listOf(1.0, 2.0, 9.0, 4.0, 50.0), ack.take(5))

        gate.copyAcknowledgement(ack, nowMs = 701L)
        assertEquals(4.0, ack[1])
        assertEquals(0.0, ack[5])
    }

    @Test
    fun `canonical command maps through the inverted generated Xbox profile`() {
        val frame = InputFrame()
        FrcStudioDriveCommand(
            vxMetersPerSecond = 2.0,
            vyMetersPerSecond = -1.0,
            omegaRadiansPerSecond = Math.PI / 2.0,
            isTeleopMode = true,
            isFieldCentric = true,
            buttonA = true,
            buttonB = false,
            buttonX = true,
            receivedAtMs = 0L,
        ).copyIntoControllerFrame(
            frame = frame,
            nowNanos = 50L,
            maximumTranslationMps = 4.0,
            maximumAngularRps = Math.PI,
        )

        assertTrue(frame.isConnected)
        assertEquals(-0.5, frame.axis(1), 1e-12)
        assertEquals(0.25, frame.axis(0), 1e-12)
        assertEquals(-0.5, frame.axis(4), 1e-12)
        assertTrue(frame.button(0))
        assertFalse(frame.button(1))
        assertTrue(frame.button(2))
    }

    @Test
    fun `field gate accepts exact retry but rejects stale and conflicting revisions`() {
        val gate = FrcStudioFieldGate()
        val revisionFive = RobotFieldConfig(
            id = "practice-field",
            name = "Practice field",
            revision = 5L,
            fieldType = FieldType.FRC,
            widthMeters = 16.541,
            heightMeters = 8.211,
        )
        val payload = RobotFieldDocument.encode(revisionFive)

        assertTrue(gate.accept(payload)?.changed == true)
        assertFalse(gate.accept(payload)?.changed ?: true)
        assertNull(gate.accept(RobotFieldDocument.encode(revisionFive.copy(name = "Conflicting bytes"))))
        assertTrue(gate.rejectionReason?.contains("reused") == true)
        assertNull(gate.accept(RobotFieldDocument.encode(revisionFive.copy(revision = 4L))))
        assertTrue(gate.rejectionReason?.contains("stale") == true)
    }

    @Test
    fun `FRC field receipt uses the shared exact-apply contract`() {
        val config = RobotFieldConfig(
            id = "practice-field",
            name = "Practice field",
            revision = 5L,
            fieldType = FieldType.FRC,
            widthMeters = 16.541,
            heightMeters = 8.211,
        )
        val application = requireNotNull(FrcStudioFieldGate().accept(RobotFieldDocument.encode(config)))

        val receipt = encodeFrcStudioFieldReceipt(application, "frc-sim", 3L)

        assertTrue(receipt.contains("\"session\":\"frc-sim\""))
        assertFalse(receipt.contains("simulatorSession"))
        assertTrue(receipt.contains("\"configId\":\"practice-field\""))
        assertTrue(receipt.contains("\"revision\":5"))
        assertTrue(receipt.contains("\"sequence\":3"))
        assertTrue(receipt.contains("\"sha256\":\"${application.sha256}\""))
    }

    private fun frame(
        session: Double,
        sequence: Double,
        vx: Double = 0.0,
        vy: Double = 0.0,
        omega: Double = 0.0,
    ): DoubleArray = doubleArrayOf(
        2.0,
        session,
        sequence,
        sequence * 20.0,
        vx,
        vy,
        omega,
        ((1L shl 3) or (1L shl 4)).toDouble(),
    )
}
