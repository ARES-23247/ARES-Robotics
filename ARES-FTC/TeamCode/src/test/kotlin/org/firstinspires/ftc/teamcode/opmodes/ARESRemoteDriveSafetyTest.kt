package org.firstinspires.ftc.teamcode.opmodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ARESRemoteDriveSafetyTest {

    @Test
    fun retainedNonzeroV2FrameCannotArmStartup() {
        val gate = RemoteDriveFrameGate()

        assertFalse(gate.observe(frame(session = 41, sequence = 9, vx = 1.5), 5_000L, 4.0, 8.0))
        assertFalse(gate.motionAuthorized)
        assertEquals(0.0, gate.vx, 0.0)
        assertFalse(gate.observe(frame(session = 41, sequence = 9, vx = 1.5), 5_100L, 4.0, 8.0))

        assertTrue(gate.observe(frame(session = 41, sequence = 10), 5_101L, 4.0, 8.0))
        assertFalse("The neutral handshake itself must never authorize motion", gate.motionAuthorized)
        assertTrue(gate.observe(frame(session = 41, sequence = 11, vx = 1.5), 5_102L, 4.0, 8.0))
        assertTrue(gate.motionAuthorized)
        assertEquals(1.5, gate.vx, 0.0)
    }

    @Test
    fun receiverLeaseExpiresAtExactlyTwoHundredMillisecondsAndRequiresNeutralFirst() {
        val gate = RemoteDriveFrameGate()

        assertTrue(gate.observe(frame(session = 7, sequence = 1), 1_000L, 4.0, 8.0))
        val moving = frame(session = 7, sequence = 2, vx = 2.0)
        assertTrue(gate.observe(moving, 1_010L, 4.0, 8.0))
        assertTrue(gate.observe(moving, 1_209L, 4.0, 8.0))
        assertTrue(gate.motionAuthorized)

        assertFalse(gate.observe(moving, 1_210L, 4.0, 8.0))
        assertFalse(gate.motionAuthorized)
        assertEquals(0.0, gate.vx, 0.0)
        assertFalse(gate.observe(frame(session = 8, sequence = 0, vx = 3.0), 1_211L, 4.0, 8.0))
        assertTrue(gate.observe(frame(session = 8, sequence = 1), 1_212L, 4.0, 8.0))
        assertFalse(gate.motionAuthorized)
        assertTrue(gate.observe(frame(session = 8, sequence = 2, vx = 0.75), 1_213L, 4.0, 8.0))
    }

    @Test
    fun readFailureHardZerosAndRequiresAnotherNeutralHandshake() {
        val gate = RemoteDriveFrameGate()

        assertTrue(gate.observe(frame(session = 12, sequence = 1), 100L, 4.0, 8.0))
        assertTrue(gate.observe(frame(session = 12, sequence = 2, omega = 2.5), 110L, 4.0, 8.0))
        assertTrue(gate.motionAuthorized)

        assertFalse(gate.observe(null, 120L, 4.0, 8.0))
        assertFalse(gate.motionAuthorized)
        assertEquals(0.0, gate.omega, 0.0)
        assertFalse(gate.observe(frame(session = 12, sequence = 3, omega = 2.5), 130L, 4.0, 8.0))
        assertTrue(gate.observe(frame(session = 12, sequence = 4), 140L, 4.0, 8.0))
        assertTrue(gate.observe(frame(session = 12, sequence = 5, omega = 1.0), 150L, 4.0, 8.0))
    }

    @Test
    fun sameSequenceMutationRollbackAndMalformedFramesFailClosed() {
        val gate = RemoteDriveFrameGate()

        assertTrue(gate.observe(frame(session = 3, sequence = 10), 1_000L, 4.0, 8.0))
        assertTrue(gate.observe(frame(session = 3, sequence = 11, vy = -1.0), 1_010L, 4.0, 8.0))
        assertFalse(gate.observe(frame(session = 3, sequence = 11, vy = 1.0), 1_011L, 4.0, 8.0))
        assertEquals(0.0, gate.vy, 0.0)

        assertFalse(gate.observe(frame(session = 3, sequence = 10), 1_012L, 4.0, 8.0))
        assertFalse(gate.observe(doubleArrayOf(2.0, 3.0), 1_013L, 4.0, 8.0))
        assertFalse(gate.observe(frame(session = 3, sequence = 12, vx = Double.NaN), 1_014L, 4.0, 8.0))
        assertFalse(gate.observe(frame(session = 3, sequence = 13, vx = 4.1), 1_015L, 4.0, 8.0))
    }

    @Test
    fun metadataAndFlagsAreValidatedExactly() {
        val gate = RemoteDriveFrameGate()

        val modeOnlyFlags = (1L shl 3) or (1L shl 4) or (1L shl 5)
        assertTrue(gate.observe(frame(1, 1, clientMs = 20, flags = modeOnlyFlags), 1_000L, 4.0, 8.0))
        assertFalse(gate.motionAuthorized)
        assertTrue(gate.observe(frame(1, 2, clientMs = 21, vx = 1.0, flags = modeOnlyFlags), 1_010L, 4.0, 8.0))
        assertTrue("Bit 4 must preserve field-relative semantics on the physical robot", gate.isFieldCentric)

        assertTrue(gate.observe(frame(1, 3, clientMs = 22, vx = 1.0, flags = 1L shl 3), 1_015L, 4.0, 8.0))
        assertFalse("Clearing bit 4 must select robot-relative drive", gate.isFieldCentric)

        assertFalse(gate.observe(frame(1, 4, clientMs = 19), 1_020L, 4.0, 8.0))
        val wrongVersion = frame(2, 1).also { it[0] = 1.0 }
        assertFalse(gate.observe(wrongVersion, 1_021L, 4.0, 8.0))
        val zeroSession = frame(1, 1).also { it[1] = 0.0 }
        assertFalse(gate.observe(zeroSession, 1_022L, 4.0, 8.0))
        val fractionalSequence = frame(2, 1).also { it[2] = 1.5 }
        assertFalse(gate.observe(fractionalSequence, 1_023L, 4.0, 8.0))
        val fractionalFlags = frame(2, 2).also { it[7] = 1.5 }
        assertFalse(gate.observe(fractionalFlags, 1_024L, 4.0, 8.0))
        assertFalse(gate.observe(frame(2, 3, flags = 1L shl 10), 1_025L, 4.0, 8.0))
    }

    @Test
    fun receiverClockRollbackFailsClosed() {
        val gate = RemoteDriveFrameGate()
        assertTrue(gate.observe(frame(4, 1), 1_000L, 4.0, 8.0))
        val moving = frame(4, 2, vx = 1.0)
        assertTrue(gate.observe(moving, 1_010L, 4.0, 8.0))

        assertFalse(gate.observe(moving, 1_009L, 4.0, 8.0))
        assertFalse(gate.motionAuthorized)
        assertFalse(gate.observe(frame(5, 1), -1L, 4.0, 8.0))
    }

    @Test
    fun actuatingAndEdgeFlagsCannotServeAsNeutralHandshake() {
        val actuatingBits = listOf(0, 1, 2, 6, 7, 8, 9)
        actuatingBits.forEachIndexed { index, bit ->
            val gate = RemoteDriveFrameGate()
            assertFalse(
                "Bit $bit must not arm a fresh session",
                gate.observe(frame(index + 1L, 1, flags = 1L shl bit), 100L, 4.0, 8.0)
            )
            assertFalse(gate.motionAuthorized)
        }
    }

    @Test
    fun neutralHandshakeRequiresExactZeroAxes() {
        listOf(
            frame(1, 1, vx = 1e-12),
            frame(2, 1, vy = -1e-12),
            frame(3, 1, omega = 1e-12),
        ).forEachIndexed { index, candidate ->
            val gate = RemoteDriveFrameGate()
            assertFalse(gate.observe(candidate, 100L + index, 4.0, 8.0))
            assertFalse(gate.motionAuthorized)
        }
    }

    @Test
    fun `physical receiver enforces shared hard axis limits above runtime tuning limits`() {
        val gate = RemoteDriveFrameGate()
        assertTrue(gate.observe(frame(90, 0), 1_000L, 20.0, 20.0))
        assertTrue(gate.observe(frame(90, 1, vx = 8.0, omega = 4.0 * Math.PI), 1_010L, 20.0, 20.0))
        assertFalse(gate.observe(frame(90, 2, vx = 8.01), 1_020L, 20.0, 20.0))

        val omegaGate = RemoteDriveFrameGate()
        assertTrue(omegaGate.observe(frame(91, 0), 2_000L, 20.0, 20.0))
        assertFalse(omegaGate.observe(frame(91, 1, omega = 4.0 * Math.PI + 0.01), 2_010L, 20.0, 20.0))
    }

    private fun frame(
        session: Long,
        sequence: Long,
        clientMs: Long = sequence,
        vx: Double = 0.0,
        vy: Double = 0.0,
        omega: Double = 0.0,
        flags: Long = 0L,
    ): DoubleArray = doubleArrayOf(
        2.0,
        session.toDouble(),
        sequence.toDouble(),
        clientMs.toDouble(),
        vx,
        vy,
        omega,
        flags.toDouble(),
    )
}
