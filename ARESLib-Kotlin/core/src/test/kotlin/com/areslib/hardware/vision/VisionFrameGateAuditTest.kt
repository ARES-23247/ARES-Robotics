package com.areslib.hardware.vision

import com.areslib.state.VisionMeasurement
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.math.BigInteger
import java.util.Random
import kotlin.test.*

class VisionFrameGateAuditTest {
    @Test fun `freshness agrees with exact integer age arithmetic across Long boundaries`() {
        val random = Random(209L)
        val fixed = longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE + 1L, -1L, 0L, 1L, 50L,
            500L, 1_000L, Long.MAX_VALUE - 50L, Long.MAX_VALUE)
        for (age in longArrayOf(0L, 500L, 1_000L, Long.MAX_VALUE)) {
            val gate = VisionFrameGate(age)
            fun verify(now: Long, capture: Long) {
                gate.beginUpdate(now)
                val delta = BigInteger.valueOf(now).subtract(BigInteger.valueOf(capture))
                val expected = capture > 0L && delta >= BigInteger.valueOf(-50L) &&
                    delta <= BigInteger.valueOf(age)
                assertEquals(expected, gate.isRecent(capture), "now=$now capture=$capture age=$age")
            }
            for (now in fixed) for (capture in fixed) verify(now, capture)
            repeat(5_000) { verify(random.nextLong(), random.nextLong()) }
        }
    }

    @Test fun `age and future skew boundaries are inclusive and independent`() {
        val gate = VisionFrameGate(500L)
        gate.beginUpdate(1_000L)
        assertTrue(gate.isRecent(500L))
        assertFalse(gate.isRecent(499L))
        assertTrue(gate.isRecent(1_050L))
        assertFalse(gate.isRecent(1_051L))
        assertFalse(gate.isRecent(0L))
    }

    @Test fun `source identity includes the unnamed source without aliasing a named default`() {
        val gate = VisionFrameGate(500L)
        gate.beginUpdate(100L)
        assertTrue(gate.accept(VisionMeasurement(timestampMs = 100L, sourceId = "", frameId = 1L)))
        assertTrue(gate.accept(VisionMeasurement(timestampMs = 100L, sourceId = "default", frameId = 1L)))
    }

    @Test fun `history retains all sources across growth and hash collisions`() {
        val gate = VisionFrameGate(500L)
        val frames = List(65) { VisionMeasurement(timestampMs = 100L, sourceId = "source-$it", frameId = 7L) }
        gate.beginUpdate(100L)
        frames.forEach { assertTrue(gate.accept(it)) }
        gate.beginUpdate(120L)
        frames.asReversed().forEach { assertFalse(gate.accept(it)) }
        gate.beginUpdate(140L)
        frames.forEach { it.timestampMs = 140L; it.frameId = 8L; assertTrue(gate.accept(it)) }
    }

    @Test fun `duplicate identity and non-increasing timestamps are both rejected`() {
        val gate = VisionFrameGate(500L)
        gate.beginUpdate(1_000L)
        val frame = VisionMeasurement(timestampMs = 900L, frameId = 20L)
        assertTrue(gate.accept(frame))
        assertFalse(gate.accept(frame.copy(timestampMs = 901L)))
        assertFalse(gate.accept(frame.copy(frameId = 21L)))
        assertFalse(gate.accept(frame.copy(timestampMs = 899L, frameId = 22L)))
        // A camera restart may reduce its ID if its canonical capture time advances.
        assertTrue(gate.accept(frame.copy(timestampMs = 901L, frameId = 1L)))
        assertTrue(gate.accept(frame.copy(timestampMs = 902L, frameId = 0L)))
        assertFalse(gate.accept(frame.copy(timestampMs = 902L, frameId = 0L)))
    }

    @Test fun `invalid timestamps do not consume source history`() {
        val gate = VisionFrameGate(500L)
        val frame = VisionMeasurement(timestampMs = 400L, frameId = Long.MIN_VALUE)
        assertFalse(gate.accept(frame))
        gate.beginUpdate(1_000L)
        assertFalse(gate.accept(frame))
        frame.timestampMs = 1_000L
        assertTrue(gate.accept(frame))
        assertFalse(gate.accept(frame))
    }

    @Test fun `poll continuity rejects rewinds gaps and overflowing intervals`() {
        val gate = VisionFrameGate(500L)
        assertFalse(gate.beginUpdate(100L))
        assertTrue(gate.beginUpdate(100L))
        assertTrue(gate.beginUpdate(600L))
        assertFalse(gate.beginUpdate(1_101L))
        assertFalse(gate.beginUpdate(Long.MIN_VALUE))
        assertFalse(gate.beginUpdate(Long.MAX_VALUE))
    }

    @Test fun `rewind and explicit clear allow a new epoch but a forward gap retains identities`() {
        val gate = VisionFrameGate(500L)
        gate.beginUpdate(100L)
        val frame = VisionMeasurement(timestampMs = 100L, frameId = 1L)
        assertTrue(gate.accept(frame))
        assertFalse(gate.beginUpdate(1_000L))
        assertFalse(gate.accept(frame.copy(timestampMs = 1_000L)))
        assertFalse(gate.beginUpdate(100L))
        assertTrue(gate.accept(frame))
        gate.clear()
        assertFalse(gate.accept(frame))
        gate.beginUpdate(100L)
        assertTrue(gate.accept(frame))
    }

    @Test fun `negative limits fail construction`() {
        assertFailsWith<IllegalArgumentException> { VisionFrameGate(-1L) }
        assertFailsWith<IllegalArgumentException> { VisionFrameGate(500L, -1L) }
    }

    @Test fun `stable camera topology performs no per-frame allocation`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        val gate = VisionFrameGate(500L)
        val frames = Array(12) { VisionMeasurement(sourceId = "camera-$it") }
        var accepted = 0
        fun loop(start: Long, end: Long) {
            for (time in start until end) {
                gate.beginUpdate(time)
                for (frame in frames) {
                    frame.timestampMs = time
                    frame.frameId = time
                    if (gate.accept(frame)) accepted++
                    if (gate.accept(frame)) error("Duplicate accepted")
                }
            }
        }
        loop(1L, 100_001L)
        val before = bean.getThreadAllocatedBytes(thread)
        val start = System.nanoTime()
        loop(100_001L, 110_001L)
        val elapsed = System.nanoTime() - start
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        println("Vision frame gate: $allocated bytes / 10,000 polls of 12 sources, ${elapsed / 10_000.0} ns/poll (desktop JVM)")
        assertTrue(allocated <= 4096L, "Frame gate allocated $allocated bytes")
        assertEquals(110_000 * 12, accepted)
    }
}
