package com.areslib.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertFailsWith

class DriveFrameReceiverAuditTest {
    private fun frame(sequence: Int, vx: Double = 0.0) =
        doubleArrayOf(2.0, 46.0, sequence.toDouble(), sequence.toDouble(), vx, 0.0, 0.0, 8.0)

    @Test fun `acknowledgement expires authority without a network poll`() {
        val receiver = DriveFrameReceiver()
        receiver.acceptFrame(frame(0), 1000)
        receiver.acceptFrame(frame(1, 1.0), 1020)
        val ack = DoubleArray(10) { -99.0 }
        receiver.copyAcknowledgement(ack, 1521)
        assertEquals(4.0, ack[1], "Expired status must agree with neutral applied axes")
        assertEquals(0.0, ack[5])
        assertEquals(1.0, ack[8])
        assertEquals(-99.0, ack[9])
        receiver.copyAcknowledgement(ack, 1522)
        assertEquals(1.0, ack[8], "Repeated acknowledgement does not reject twice")
        assertEquals(0.0, receiver.acceptFrame(frame(2, 1.0), 1522).vx)
    }

    @Test fun `receivers and accepted payloads have independent ownership`() {
        val first = DriveFrameReceiver()
        val second = DriveFrameReceiver()
        first.acceptFrame(frame(0), 1000)
        val payload = frame(1, 1.0)
        val accepted = first.acceptFrame(payload, 1020)
        payload[4] = 7.0
        assertSame(accepted, first.currentFrame(1100))
        assertEquals(1.0, accepted.vx)
        assertEquals(0.0, second.acceptFrame(frame(1, 1.0), 1020).vx)
        second.reset()
        assertSame(accepted, first.acceptFrame(frame(1, 1.0), 1520))
        assertEquals(0.0, first.currentFrame(1521).vx)
    }

    @Test fun `strict numeric metadata rejects fractions infinities and unsafe integers`() {
        for (index in listOf(1, 2, 3, 7)) {
            for (invalid in listOf(-1.0, 0.5, Double.NaN, Double.POSITIVE_INFINITY, 9_007_199_254_740_992.0)) {
                val receiver = DriveFrameReceiver()
                receiver.acceptFrame(frame(0), 1000)
                receiver.acceptFrame(frame(1, 1.0), 1001)
                val bad = frame(2, 1.0).also { it[index] = invalid }
                assertEquals(0.0, receiver.acceptFrame(bad, 1002).vx, "index=$index value=$invalid")
                assertEquals(0.0, receiver.acceptFrame(frame(3, 1.0), 1003).vx)
            }
        }
    }

    @Test fun `acknowledgement rejects short destination before mutation and saturates elapsed overflow`() {
        val receiver = DriveFrameReceiver()
        receiver.acceptFrame(frame(0), Long.MIN_VALUE)
        val short = DoubleArray(8) { -7.0 }
        assertFailsWith<IllegalArgumentException> { receiver.copyAcknowledgement(short, Long.MAX_VALUE) }
        for (value in short) assertEquals(-7.0, value)
        val ack = DoubleArray(9)
        receiver.copyAcknowledgement(ack, Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE.toDouble(), ack[4])
        assertEquals(4.0, ack[1])
    }

    @Test fun `retained input and acknowledgement allocate no bytes after warmup`() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        val receiver = DriveFrameReceiver()
        receiver.acceptFrame(frame(0), 1000)
        val retained = frame(1, 1.0)
        receiver.acceptFrame(retained, 1001)
        val ack = DoubleArray(9)
        fun tick() {
            receiver.acceptFrame(retained, 1100)
            receiver.acceptFrame(null, 1100)
            receiver.copyAcknowledgement(ack, 1100)
        }
        repeat(50_000) { tick() }
        repeat(2) {
            val before = bean.getThreadAllocatedBytes(thread)
            repeat(10_000) { tick() }
            val bytes = bean.getThreadAllocatedBytes(thread) - before
            assertEquals(0L, bytes, "Retained-poll allocation window $it")
        }
        assertEquals(1.0, ack[5])
    }
}
