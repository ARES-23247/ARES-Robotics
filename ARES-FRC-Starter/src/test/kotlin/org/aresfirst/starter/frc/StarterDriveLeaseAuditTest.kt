package org.aresfirst.starter.frc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import com.areslib.input.InputFrame
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory

class StarterDriveLeaseAuditTest {
    private fun frame(sequence: Double, vx: Double = 0.0, session: Double = 7.0, clientTime: Double = sequence,
                      flags: Double = 24.0) =
        doubleArrayOf(2.0, session, sequence, clientTime, vx, 0.0, 0.0, flags)

    @Test fun `expired motion cannot renew itself without an intervening expiry poll`() {
        val gate = FrcStudioDriveFrameGate()
        assertTrue(gate.accept(frame(0.0), 1000L))
        assertTrue(gate.accept(frame(1.0, vx = 2.0), 1020L))
        assertFalse(gate.accept(frame(2.0, vx = 3.0), 1521L))
        assertNull(gate.current(1521L))
        assertTrue(gate.accept(frame(3.0), 1522L))
        assertTrue(gate.accept(frame(4.0, vx = 1.0), 1523L))
        assertEquals(1.0, gate.current(1523L)!!.vxMetersPerSecond)
    }

    @Test fun `receiver rewind cannot be hidden by accepting another motion frame`() {
        val gate = FrcStudioDriveFrameGate()
        assertTrue(gate.accept(frame(0.0), 1000L))
        assertTrue(gate.accept(frame(1.0, vx = 2.0), 1020L))
        assertFalse(gate.accept(frame(2.0, vx = 3.0), 1010L))
        assertNull(gate.current(1010L))
        assertTrue(gate.accept(frame(3.0), 1011L))
        assertTrue(gate.accept(frame(4.0, vx = 1.0), 1012L))
    }

    @Test fun `signed millisecond wrap never looks like a fresh short interval`() {
        val gate = FrcStudioDriveFrameGate()
        assertTrue(gate.accept(frame(0.0), Long.MAX_VALUE - 3L))
        assertTrue(gate.accept(frame(1.0, vx = 2.0), Long.MAX_VALUE - 2L))
        assertNull(gate.current(Long.MIN_VALUE + 2L))
        assertFalse(gate.receiverReady(Long.MIN_VALUE + 2L))
    }

    @Test fun `minimum signed mock timestamp still has real acknowledgement history`() {
        val gate = FrcStudioDriveFrameGate()
        assertTrue(gate.accept(frame(0.0), Long.MIN_VALUE))
        val ack = DoubleArray(9)
        gate.copyAcknowledgement(ack, Long.MIN_VALUE + 20L)
        assertEquals(20.0, ack[4])
        assertEquals(7.0, ack[2])
    }

    @Test fun `unrepresentable forward acknowledgement age saturates instead of looking new`() {
        val gate = FrcStudioDriveFrameGate()
        assertTrue(gate.accept(frame(0.0), Long.MIN_VALUE + 1L))
        val ack = DoubleArray(9)
        gate.copyAcknowledgement(ack, Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE.toDouble(), ack[4])
        assertEquals(4.0, ack[1])
        assertEquals(0.0, ack[5])
    }

    @Test fun `lease boundary is inclusive and repeated reads never renew it`() {
        val gate = FrcStudioDriveFrameGate()
        assertTrue(gate.accept(frame(0.0), -1000L))
        assertTrue(gate.accept(frame(1.0, vx = 2.0), -990L))
        val command = gate.current(-990L)
        for (now in -990L..-490L) assertSame(command, gate.current(now))
        assertNull(gate.current(-489L))
        val ack = DoubleArray(9)
        gate.copyAcknowledgement(ack, -489L)
        assertEquals(1.0, ack[8])
        repeat(10) { gate.copyAcknowledgement(ack, -489L) }
        assertEquals(1.0, ack[8], "An expiry is counted once, not once per poll")
    }

    @Test fun `invalid metadata and axes disarm every established lease`() {
        val invalid = mutableListOf<DoubleArray>()
        invalid += doubleArrayOf()
        invalid += DoubleArray(9)
        invalid += frame(2.0).also { it[0] = 1.0 }
        for (index in 1..3) for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.5, 9007199254740992.0)) {
            invalid += frame(2.0).also { it[index] = bad }
        }
        invalid += frame(2.0, session = 0.0)
        for (index in 4..6) for (bad in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            invalid += frame(2.0).also { it[index] = bad }
        }
        for (index in 4..5) for (bad in listOf(Math.nextUp(8.0), Math.nextDown(-8.0))) {
            invalid += frame(2.0).also { it[index] = bad }
        }
        for (bad in listOf(Math.nextUp(4.0 * Math.PI), Math.nextDown(-4.0 * Math.PI))) {
            invalid += frame(2.0).also { it[6] = bad }
        }
        for (bad in listOf(-1.0, 0.5, 1024.0, Double.NaN)) invalid += frame(2.0, flags = bad)
        for ((index, raw) in invalid.withIndex()) {
            val gate = FrcStudioDriveFrameGate()
            assertTrue(gate.accept(frame(0.0), 1000L))
            assertTrue(gate.accept(frame(1.0, vx = 1.0), 1001L))
            assertFalse(gate.accept(raw, 1002L), "Malformed case $index")
            assertNull(gate.current(1002L))
            assertFalse(gate.accept(frame(3.0, vx = 1.0), 1003L))
            assertTrue(gate.accept(frame(4.0), 1004L))
        }
    }

    @Test fun `all actuating flags require neutral while mode and alliance flags do not`() {
        for (bit in listOf(0, 1, 2, 6, 7, 8, 9)) {
            val gate = FrcStudioDriveFrameGate()
            assertFalse(gate.accept(frame(0.0, flags = (24L or (1L shl bit)).toDouble()), 1000L))
            assertTrue(gate.accept(frame(1.0, flags = 56.0), 1001L))
        }
        val gate = FrcStudioDriveFrameGate()
        assertTrue(gate.accept(frame(0.0), 1000L))
        val raw = frame(1.0, vx = 8.0).also { it[5] = -8.0; it[6] = 4.0 * Math.PI }
        assertTrue(gate.accept(raw, 1001L))
        val snapshot = gate.current(1001L)!!
        raw.fill(Double.NaN)
        assertEquals(8.0, snapshot.vxMetersPerSecond)
        assertEquals(-8.0, snapshot.vyMetersPerSecond)
        assertEquals(4.0 * Math.PI, snapshot.omegaRadiansPerSecond)
    }

    @Test fun `duplicate and regressed sender identities fail closed without changing acknowledgement identity`() {
        for (bad in listOf(frame(1.0, vx = 1.0), frame(0.0), frame(2.0, clientTime = 0.0))) {
            val gate = FrcStudioDriveFrameGate()
            assertTrue(gate.accept(frame(0.0), 1000L))
            assertTrue(gate.accept(frame(1.0, vx = 1.0), 1001L))
            assertFalse(gate.accept(bad, 1002L))
            val ack = DoubleArray(11) { 99.0 }
            assertEquals(9, gate.copyAcknowledgement(ack, 1002L))
            assertEquals(listOf(1.0, 6.0, 7.0, 1.0, 1.0, 0.0, 0.0, 0.0, 1.0), ack.take(9))
            assertEquals(99.0, ack[9])
            assertThrows(IllegalArgumentException::class.java) { gate.copyAcknowledgement(DoubleArray(8), 1002L) }
        }
    }

    @Test fun `receiver interval and acknowledgement arithmetic match exact integer reference`() {
        val times = listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MIN_VALUE + 500, -1000L, -1L, 0L,
            1L, 499L, 500L, 501L, 1000L, Long.MAX_VALUE - 500, Long.MAX_VALUE - 1, Long.MAX_VALUE)
        var cases = 0
        for (acceptedAt in times) for (now in times) {
            val gate = FrcStudioDriveFrameGate()
            assertTrue(gate.accept(frame(0.0), acceptedAt))
            val elapsed = java.math.BigInteger.valueOf(now).subtract(java.math.BigInteger.valueOf(acceptedAt))
            val fresh = elapsed.signum() >= 0 && elapsed <= java.math.BigInteger.valueOf(500L)
            assertEquals(fresh, gate.current(now) != null, "accepted=$acceptedAt now=$now")
            val ack = DoubleArray(9)
            gate.copyAcknowledgement(ack, now)
            val age = elapsed.max(java.math.BigInteger.ZERO).min(java.math.BigInteger.valueOf(Long.MAX_VALUE))
            assertEquals(age.toDouble(), ack[4])
            cases++
        }
        assertEquals(196, cases)
    }

    @Test fun `transport age rejects impossible timestamps and retains only the unspent lease`() {
        for ((stamp, transportNow) in listOf(0L to 1000L, -1L to 1000L, 1001L to 1000L,
            1L to Long.MAX_VALUE, Long.MAX_VALUE to Long.MIN_VALUE, 1L to 500002L)) {
            val gate = FrcStudioDriveFrameGate()
            assertTrue(gate.accept(frame(0.0), 1000L))
            assertFalse(gate.acceptQueued(frame(1.0), stamp, transportNow, 1001L))
            assertNull(gate.current(1001L))
        }
        val gate = FrcStudioDriveFrameGate()
        assertFalse(gate.acceptQueued(frame(0.0), 1L, 2L, Long.MIN_VALUE))
        assertTrue(gate.acceptQueued(frame(1.0), 1L, 2L, 1000L))
        val ack = DoubleArray(9)
        gate.copyAcknowledgement(ack, 1000L)
        assertEquals(1.0, ack[4])
        assertNotNull(gate.current(1499L))
        assertNull(gate.current(1500L))
        val boundary = FrcStudioDriveFrameGate()
        assertTrue(boundary.acceptQueued(frame(0.0), 1L, 500001L, 1000L))
        assertNotNull(boundary.current(1000L))
        assertNull(boundary.current(1001L))
    }

    @Test fun `maximum exact wire identities are accepted and diagnostic rejection count saturates`() {
        val gate = FrcStudioDriveFrameGate()
        val maximum = 9007199254740991.0
        assertTrue(gate.accept(frame(maximum, session = maximum, clientTime = maximum), 1000L))
        val ack = DoubleArray(9)
        gate.copyAcknowledgement(ack, 1000L)
        assertEquals(maximum, ack[2])
        assertEquals(maximum, ack[3])
        // Fault injection reaches a lifetime counter boundary without executing billions of rejects.
        val counter = FrcStudioDriveFrameGate::class.java.getDeclaredField("rejectedFrameCount")
        counter.isAccessible = true
        counter.setLong(gate, Long.MAX_VALUE)
        assertFalse(gate.accept(doubleArrayOf(), 1001L))
        gate.copyAcknowledgement(ack, 1001L)
        assertEquals(Long.MAX_VALUE.toDouble(), ack[8])
    }

    @Test fun `controller projection clamps finite commands clears old inputs and rejects invalid limits`() {
        val gate = FrcStudioDriveFrameGate()
        assertTrue(gate.accept(frame(0.0), 1000L))
        val raw = frame(1.0, vx = 8.0, flags = 472.0).also { it[5] = -8.0; it[6] = 4.0 * Math.PI }
        assertTrue(gate.accept(raw, 1001L))
        val command = gate.current(1001L)!!
        val controller = InputFrame()
        command.copyIntoControllerFrame(controller, 10L, 4.0, Math.PI)
        assertEquals(-1.0, controller.axis(1))
        assertEquals(1.0, controller.axis(0))
        assertEquals(-1.0, controller.axis(4))
        assertTrue(controller.button(0))
        assertTrue(controller.button(1))
        assertTrue(controller.button(2))
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { command.copyIntoControllerFrame(controller, 20L, bad, 1.0) }
            assertThrows(IllegalArgumentException::class.java) { command.copyIntoControllerFrame(controller, 20L, 1.0, bad) }
        }
        assertTrue(gate.accept(frame(2.0), 1002L))
        gate.current(1002L)!!.copyIntoControllerFrame(controller, 30L, 4.0, Math.PI)
        assertEquals(0.0, controller.axis(1), 0.0)
        assertEquals(0.0, controller.axis(0), 0.0)
        assertFalse(controller.button(0))
        assertFalse(controller.button(1))
        assertFalse(controller.button(2))
    }

    @Test fun `retained command polling acknowledgement and controller projection do not allocate per tick`() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean?.isThreadAllocatedMemorySupported == true)
        bean!!
        val wasEnabled = bean.isThreadAllocatedMemoryEnabled
        bean.isThreadAllocatedMemoryEnabled = true
        try {
            val gate = FrcStudioDriveFrameGate()
            assertTrue(gate.accept(frame(0.0), 1000L))
            assertTrue(gate.accept(frame(1.0, vx = 2.0), 1001L))
            val ack = DoubleArray(9)
            val controller = InputFrame()
            repeat(50_000) {
                gate.current(1200L)!!.copyIntoControllerFrame(controller, it.toLong(), 4.0, Math.PI)
                gate.copyAcknowledgement(ack, 1200L)
            }
            val threadId = Thread.currentThread().id
            val before = bean.getThreadAllocatedBytes(threadId)
            val started = System.nanoTime()
            repeat(50_000) {
                gate.current(1200L)!!.copyIntoControllerFrame(controller, it.toLong(), 4.0, Math.PI)
                gate.copyAcknowledgement(ack, 1200L)
            }
            val elapsed = System.nanoTime() - started
            val allocated = bean.getThreadAllocatedBytes(threadId) - before
            println("Starter drive receiver probe: 50000 ticks, $allocated allocated bytes, $elapsed elapsed nanoseconds")
            assertTrue(allocated in 0..1024, "$allocated bytes over 50000 retained-command ticks")
            assertEquals(2.0, ack[5])
            assertEquals(-0.5, controller.axis(1))
        } finally { bean.isThreadAllocatedMemoryEnabled = wasEnabled }
    }
}
