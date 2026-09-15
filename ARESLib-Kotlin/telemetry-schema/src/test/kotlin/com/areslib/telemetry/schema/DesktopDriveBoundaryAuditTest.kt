package com.areslib.telemetry.schema

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DesktopDriveBoundaryAuditTest {
    @Test fun `expiry preserves the last accepted frame age`() {
        val gate = DesktopDriveFrameGate(200)
        assertTrue(gate.observe(frame(0), 5000))
        assertTrue(gate.observe(frame(1, vx = 1.0), 5010))
        val ack = DoubleArray(9)
        gate.copyAcknowledgement(ack, 5210)
        assertEquals(4.0, ack[1])
        assertEquals(200.0, ack[4])
        assertEquals(0.0, ack[5])
        gate.copyAcknowledgement(ack, 5300)
        assertEquals(290.0, ack[4])
        assertFalse(gate.motionAuthorized)
    }

    @Test fun `unaccepted sessions never replace accepted acknowledgement identity`() {
        val gate = DesktopDriveFrameGate()
        val ack = DoubleArray(9)
        assertFalse(gate.observe(frame(0, vx = 1.0), 1000))
        gate.copyAcknowledgement(ack, 1001)
        assertEquals(listOf(-1.0, -1.0, -1.0), ack.slice(2..4))
        assertTrue(gate.observe(frame(1), 1002))
        assertFalse(gate.observe(frame(0, session = 99, vx = 2.0), 1010))
        gate.copyAcknowledgement(ack, 1011)
        assertEquals(listOf(7.0, 1.0, 9.0), ack.slice(2..4))
        assertEquals(0.0, ack[5])
        assertTrue(gate.observe(frame(1, session = 99), 1012))
        gate.copyAcknowledgement(ack, 1013)
        assertEquals(listOf(99.0, 1.0, 1.0), ack.slice(2..4))
    }

    @Test fun `malformed input invalidates motion without erasing accepted diagnostics`() {
        for (bad in listOf<DoubleArray?>(null, DoubleArray(7), DoubleArray(9))) {
            val gate = DesktopDriveFrameGate()
            gate.observe(frame(0), 1000)
            gate.observe(frame(1, vx = 1.0), 1010)
            assertFalse(gate.observe(bad, 1020))
            val ack = DoubleArray(9)
            gate.copyAcknowledgement(ack, 1021)
            assertEquals(listOf(7.0, 1.0, 11.0), ack.slice(2..4))
            assertFalse(gate.motionAuthorized)
            assertEquals(0.0, gate.flags.toDouble())
            assertFalse(gate.observe(frame(2, vx = 1.0), 1022))
        }
    }

    @Test fun `time zero and receiver clock rewind keep diagnostics and handshake distinct`() {
        val gate = DesktopDriveFrameGate()
        val ack = DoubleArray(9)
        assertTrue(gate.observe(frame(0), 0))
        gate.copyAcknowledgement(ack, 0)
        assertEquals(listOf(7.0, 0.0, 0.0), ack.slice(2..4))
        assertTrue(gate.observe(frame(1), 100))
        assertFalse(gate.receiverReady(50))
        gate.copyAcknowledgement(ack, 50)
        assertEquals(0.0, ack[4], "Rewound receiver time must not turn into uptime")
        assertTrue(gate.observe(frame(2), 50))
        gate.copyAcknowledgement(ack, 60)
        assertEquals(10.0, ack[4])
        assertFalse(gate.observe(frame(3, vx = 1.0), 49))
        gate.copyAcknowledgement(ack, 49)
        assertEquals(6.0, ack[1])
        assertEquals(listOf(7.0, 2.0, 0.0), ack.slice(2..4))
        assertTrue(gate.observe(frame(3), 49))
        assertFalse(gate.observe(frame(4, vx = 1.0), -1))
        assertEquals(0.0, gate.vxMetersPerSecond)
    }

    @Test fun `numeric metadata rejects fractional nonfinite negative and unsafe values`() {
        for (index in intArrayOf(1, 2, 3, 7)) {
            for (bad in doubleArrayOf(-1.0, 0.5, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 9_007_199_254_740_992.0)) {
                val gate = DesktopDriveFrameGate()
                gate.observe(frame(0), 1000)
                gate.observe(frame(1, vx = 1.0), 1001)
                assertFalse(gate.observe(frame(2, vx = 1.0).also { it[index] = bad }, 1002))
                assertFalse(gate.motionAuthorized)
                assertEquals(0.0, gate.vxMetersPerSecond)
            }
        }
        assertFalse(DesktopDriveFrameGate().observe(frame(0, session = 0), 1000))
    }

    @Test fun `axis limits remain per-axis and reject invalid configuration`() {
        val gate = DesktopDriveFrameGate()
        assertTrue(gate.observe(frame(0), 1000))
        assertTrue(gate.observe(frame(1, vx = 8.0, vy = -8.0, omega = 4.0 * Math.PI), 1001))
        assertFalse(gate.observe(frame(2, vx = 8.0), 1002, maxTranslationMetersPerSecond = 4.0))
        for (index in 4..6) {
            val boundary = if (index == 6) 4.0 * Math.PI else 8.0
            for (sign in doubleArrayOf(-1.0, 1.0)) {
                val capped = DesktopDriveFrameGate()
                assertTrue(capped.observe(frame(0), 1000))
                assertFalse(capped.observe(frame(1).also { it[index] = sign * Math.nextUp(boundary) },
                    1001, Double.MAX_VALUE, Double.MAX_VALUE))
                assertFalse(capped.motionAuthorized)
            }
        }
        for (bad in doubleArrayOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFalse(DesktopDriveFrameGate().observe(frame(0), 1000, maxTranslationMetersPerSecond = bad))
            assertFalse(DesktopDriveFrameGate().observe(frame(0), 1000, maxOmegaRadiansPerSecond = bad))
        }
        for (index in 4..6) for (bad in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFalse(DesktopDriveFrameGate().observe(frame(0).also { it[index] = bad }, 1000))
        }
        assertFalse(DesktopDriveFrameGate().observe(frame(0).also { it[0] = 1.0 }, 1000))
        assertFalse(DesktopDriveFrameGate().observe(frame(0, flags = 1024), 1000))
    }

    @Test fun `all known flag combinations preserve neutral handshake and bit meaning`() {
        for (flags in 0L..1023L) {
            val gate = DesktopDriveFrameGate()
            val onlyModeBits = flags and 967L == 0L
            assertEquals(onlyModeBits, gate.observe(frame(0, flags = flags), 1000))
            assertFalse(gate.motionAuthorized)
            assertTrue(gate.observe(frame(1), 1001))
            assertTrue(gate.observe(frame(2, vx = 1.0, flags = flags), 1002))
            assertEquals(flags, gate.flags)
            assertEquals(listOf(3, 4, 5, 6, 7, 8).map { flags and (1L shl it) != 0L },
                listOf(gate.isTeleopMode, gate.isFieldCentric, gate.isRedAlliance, gate.buttonA, gate.buttonB, gate.buttonX))
        }
    }

    @Test fun `retention mutation rollback and exact expiry never renew motion authority`() {
        val gate = DesktopDriveFrameGate(200)
        gate.observe(frame(0), 1000)
        val motion = frame(1, vx = 1.0)
        assertTrue(gate.observe(motion, 1010))
        assertTrue(gate.observe(motion.copyOf(), 1209))
        assertFalse(gate.observe(motion, 1210))
        assertFalse(gate.observe(frame(2, vx = 1.0), 1211))
        assertTrue(gate.observe(frame(3), 1212))
        assertTrue(gate.observe(frame(4, vx = 1.0), 1213))
        assertFalse(gate.observe(frame(4, vx = 2.0), 1214))
        assertFalse(gate.observe(frame(3), 1215))
        assertTrue(gate.observe(frame(5), 1216))
        assertFalse(gate.observe(frame(6).also { it[3] = 0.0 }, 1217))
    }

    @Test fun `short acknowledgement storage fails before expiry mutation and preserves tail`() {
        val gate = DesktopDriveFrameGate()
        gate.observe(frame(0), 1000)
        gate.observe(frame(1, vx = 1.0), 1001)
        val short = DoubleArray(8) { -7.0 }
        assertThrows(IllegalArgumentException::class.java) { gate.copyAcknowledgement(short, 2000) }
        assertTrue(gate.motionAuthorized)
        assertArrayEquals(DoubleArray(8) { -7.0 }, short)
        val destination = DoubleArray(11) { -7.0 }
        assertEquals(9, gate.copyAcknowledgement(destination, 1002))
        assertEquals(-7.0, destination[9])
        assertEquals(-7.0, destination[10])
    }

    @Test fun `fresh noncached integer frames and acknowledgement have bounded host allocation`() {
        val gate = DesktopDriveFrameGate()
        val payload = frame(1_000_000, session = 9_000_000)
        val ack = DoubleArray(9)
        gate.observe(payload, 1000)
        payload[4] = 1.25
        var accepted = 0
        repeat(100_000) {
            payload[2]++; payload[3]++
            if (gate.observe(payload, 1000)) accepted++
            gate.copyAcknowledgement(ack, 1000)
        }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(threadId)
        repeat(10_000) {
            payload[2]++; payload[3]++
            if (gate.observe(payload, 1000)) accepted++
            gate.copyAcknowledgement(ack, 1000)
        }
        val bytes = bean.getThreadAllocatedBytes(threadId) - before
        assertEquals(110_000, accepted)
        assertEquals(1_110_000.0, ack[3])
        assertEquals(1.25, ack[5])
        println("Desktop drive gate: $bytes bytes / 10,000 new-frame + acknowledgement pairs (desktop JVM)")
        assertTrue(bytes <= 4096L, "Fresh gate observations allocated $bytes bytes")
    }

    @Test fun `rejection count saturates instead of overflowing negative`() {
        val gate = DesktopDriveFrameGate()
        gate.javaClass.getDeclaredField("rejectedFrameCount").apply { isAccessible = true }
            .setLong(gate, Long.MAX_VALUE - 1L)
        repeat(3) { assertFalse(gate.observe(null, 1000)) }
        val ack = DoubleArray(9)
        gate.copyAcknowledgement(ack, 1000)
        assertEquals(Long.MAX_VALUE.toDouble(), ack[8])
    }

    @Test fun `no accepted frame reports unknown identity and age`() {
        val gate = DesktopDriveFrameGate()
        val ack = DoubleArray(9)
        gate.copyAcknowledgement(ack, Long.MAX_VALUE)
        assertEquals(listOf(1.0, 0.0, -1.0, -1.0, -1.0, 0.0, 0.0, 0.0, 0.0), ack.toList())
    }

    @Test fun `timeout and maximum exact integer boundaries remain explicit`() {
        assertThrows(IllegalArgumentException::class.java) { DesktopDriveFrameGate(0) }
        assertThrows(IllegalArgumentException::class.java) { DesktopDriveFrameGate(-1) }
        val gate = DesktopDriveFrameGate(Long.MAX_VALUE)
        val max = 9_007_199_254_740_991L
        assertTrue(gate.observe(frame(max, session = max), 0))
        assertTrue(gate.receiverReady(Long.MAX_VALUE - 1L))
        assertFalse(gate.receiverReady(Long.MAX_VALUE))
    }

    private fun frame(sequence: Long, session: Long = 7L, vx: Double = 0.0, vy: Double = 0.0,
        omega: Double = 0.0, flags: Long = 56L) =
        doubleArrayOf(2.0, session.toDouble(), sequence.toDouble(), sequence.toDouble(), vx, vy, omega, flags.toDouble())
}
