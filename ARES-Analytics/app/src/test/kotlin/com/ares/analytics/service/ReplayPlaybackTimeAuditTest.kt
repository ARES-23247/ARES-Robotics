package com.ares.analytics.service

import java.math.BigInteger
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.test.*

class ReplayPlaybackTimeAuditTest {
    @Test fun `fractional milliseconds accumulate across ticks`() {
        val time = ReplayPlaybackTime(); time.reset(0)
        var position = 1000L
        for (now in 1L..4L) position = time.advance(now, position, 1000, 1100, 0.25, false)
        assertEquals(1001L, position); assertTrue(time.advanced); assertFalse(time.ended)
    }
    @Test fun `backward clock readings cannot be counted again on recovery`() {
        val time = ReplayPlaybackTime(); time.reset(0)
        var position = time.advance(100, 0, 0, 1000, 1.0, false)
        for (now in listOf(90L,100L)) position = time.advance(now, position, 0, 1000, 1.0, false)
        assertEquals(100, position)
        assertEquals(110, time.advance(110, position, 0, 1000, 1.0, false))
    }
    @Test fun `huge scaled interval clamps rather than overflowing the playhead`() {
        val time = ReplayPlaybackTime(); time.reset(0)
        assertEquals(1100, time.advance(Long.MAX_VALUE, 1000, 1000, 1100, 8.0, false))
        assertTrue(time.ended)
    }
    @Test fun `huge looping interval keeps its exact whole-millisecond remainder`() {
        val time = ReplayPlaybackTime(); time.reset(0)
        assertEquals(1056, time.advance(Long.MAX_VALUE, 1000, 1000, 1100, 8.0, true))
        assertFalse(time.ended)
    }
    @Test fun `clock difference across the signed Long range cannot overflow`() {
        val time = ReplayPlaybackTime(); time.reset(Long.MIN_VALUE)
        assertEquals(1020, time.advance(Long.MAX_VALUE, 1000, 1000, 1100, 8.0, true))
    }
    @Test fun `whole loops wrap to the start and non-looping end is inclusive`() {
        val time = ReplayPlaybackTime(); time.reset(0)
        assertEquals(1000, time.advance(300, 1000, 1000, 1100, 1.0, true)); assertTrue(time.advanced)
        time.reset(0)
        assertEquals(1100, time.advance(100, 1000, 1000, 1100, 1.0, false)); assertTrue(time.ended)
    }
    @Test fun `zero duration and already ended positions do not divide by zero or restart`() {
        val time = ReplayPlaybackTime(); time.reset(0)
        assertEquals(10, time.advance(0, 10, 10, 10, 1.0, true)); assertTrue(time.ended)
        time.reset(0)
        assertEquals(20, time.advance(0, 20, 10, 20, 1.0, false)); assertTrue(time.ended)
    }
    @Test fun `invalid inputs cannot corrupt the next valid interval`() {
        val time = ReplayPlaybackTime(); time.reset(0)
        for (speed in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, 8.01)) {
            assertFailsWith<IllegalArgumentException> { time.advance(10, 0, 0, 100, speed, false) }
        }
        assertFailsWith<IllegalArgumentException> { time.advance(10, 0, -1, 100, 1.0, false) }
        assertFailsWith<IllegalArgumentException> { time.advance(10, 101, 0, 100, 1.0, false) }
        assertFailsWith<IllegalArgumentException> { time.advance(10, 0, 100, 0, 1.0, false) }
        assertEquals(10, time.advance(10, 0, 0, 100, 1.0, false))
    }
    @Test fun `reset discards the prior navigation fraction and clock anchor`() {
        val time = ReplayPlaybackTime(); time.reset(0)
        assertEquals(0, time.advance(1, 0, 0, 100, 0.5, false))
        time.reset(1)
        assertEquals(0, time.advance(2, 0, 0, 100, 0.5, false))
    }
    @Test fun `near Long maximum recording bounds remain bounded`() {
        val time = ReplayPlaybackTime(); time.reset(0)
        assertEquals(Long.MAX_VALUE, time.advance(20, Long.MAX_VALUE-100, Long.MAX_VALUE-100, Long.MAX_VALUE, 8.0, false))
    }
    @Test fun `random clock spans agree with an independent integer rational oracle`() {
        val random = Random(20260909)
        val numerators = listOf(1L, 2L, 4L, 8L, 32L)
        repeat(500) {
            val a = random.nextLong(); val b = random.nextLong()
            val previous = minOf(a,b); val now = maxOf(a,b)
            val start = random.nextLong(0, 1_000_000)
            val duration = random.nextLong(1, 1_000_000)
            val current = start + random.nextLong(duration)
            val numerator = numerators.random(random)
            val looping = random.nextBoolean()
            val whole = BigInteger.valueOf(now).subtract(BigInteger.valueOf(previous))
                .multiply(BigInteger.valueOf(numerator)).divide(BigInteger.valueOf(4))
            val offset = BigInteger.valueOf(current-start).add(whole)
            val expected = if (looping) start + offset.mod(BigInteger.valueOf(duration)).longValueExact()
                else start + offset.min(BigInteger.valueOf(duration)).longValueExact()
            val time = ReplayPlaybackTime(); time.reset(previous)
            assertEquals(expected, time.advance(now, current, start, start+duration, numerator/4.0, looping))
        }
    }
    @Test fun `ordinary playback arithmetic has no per-tick allocation after warmup`() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        org.junit.Assume.assumeTrue(bean?.isThreadAllocatedMemorySupported == true)
        val meter = requireNotNull(bean); meter.isThreadAllocatedMemoryEnabled = true
        val time = ReplayPlaybackTime(); time.reset(0)
        var position = 0L
        repeat(30_000) { i -> position = time.advance((i+1)*20L, position, 0, 10_000_000, 1.0, true) }
        time.reset(0); position = 0
        val thread = Thread.currentThread().id
        val before = meter.getThreadAllocatedBytes(thread)
        repeat(100_000) { i -> position = time.advance((i+1)*20L, position, 0, 10_000_000, 1.0, true) }
        val bytes = meter.getThreadAllocatedBytes(thread) - before
        assertEquals(2_000_000, position)
        assertTrue(bytes <= 8192, "Playback arithmetic allocated $bytes bytes for 100,000 ticks")
    }
}
