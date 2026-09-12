package com.areslib.hardware.vision

import com.areslib.math.wrapAngle
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.*
import kotlin.random.Random
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class VisionRecoveryConsensusAuditTest {
    @Test fun `sample conversion agrees with exact binary decimal arithmetic`() {
        val values = arrayListOf(Double.MIN_VALUE, Math.nextUp(0.5), 1.0, 2.5, 45.0,
            Int.MAX_VALUE.toDouble(), Math.nextDown(Long.MAX_VALUE.toDouble()),
            Long.MAX_VALUE.toDouble(), Double.MAX_VALUE, Double.NaN, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, 0.0, -0.0, -1.0)
        val random = Random(212)
        repeat(2048) { values.add(Double.fromBits(random.nextLong())) }
        val maximum = BigDecimal(Long.MAX_VALUE)
        for (value in values) for (singleTag in listOf(false, true)) {
            val exact = if (value.isFinite() && value > 0.0) {
                BigDecimal(value).multiply(if (singleTag) BigDecimal(2) else BigDecimal.ONE)
                    .setScale(0, RoundingMode.CEILING)
            } else BigDecimal.ZERO
            val expected = if (exact > maximum) 0L else exact.longValueExact()
            assertEquals(expected, VisionRecoveryConsensus.requiredSamples(value, singleTag),
                "value=$value singleTag=$singleTag")
        }
        println("Recovery sample conversion: ${values.size * 2} exact comparisons")
    }

    @Test fun `large requirements stay positive without an arbitrary int cap`() {
        assertEquals(3L, VisionRecoveryConsensus.requiredSamples(2.5))
        assertEquals(5L, VisionRecoveryConsensus.requiredSamples(2.5, true))
        assertEquals(4294967294L, VisionRecoveryConsensus.requiredSamples(Int.MAX_VALUE.toDouble(), true))
        assertEquals(9223372036854774784L,
            VisionRecoveryConsensus.requiredSamples(Math.nextDown(Long.MAX_VALUE.toDouble())))
        assertEquals(0L, VisionRecoveryConsensus.requiredSamples(Long.MAX_VALUE.toDouble()))
        assertEquals(0L, VisionRecoveryConsensus.requiredSamples(Double.MAX_VALUE, true))
    }

    @Test fun `incremental means agree with independent batch translation and circular means`() {
        val c = VisionRecoveryConsensus()
        var sumX = BigDecimal.ZERO
        var sumY = BigDecimal.ZERO
        var sumSin = 0.0
        var sumCos = 0.0
        repeat(257) { i ->
            val x = 0.8 + 0.05 * sin(i * 0.2)
            val y = 1.2 + 0.04 * cos(i * 0.17)
            val heading = wrapAngle(Math.PI + 0.08 * sin(i * 0.13))
            assertTrue(c.add(x, y, heading))
            sumX = sumX.add(BigDecimal(x))
            sumY = sumY.add(BigDecimal(y))
            sumSin += sin(heading)
            sumCos += cos(heading)
        }
        assertEquals(257L, c.sampleCount)
        assertEquals(sumX.divide(BigDecimal(257), MathContext.DECIMAL128).toDouble(), c.meanX, 1e-14)
        assertEquals(sumY.divide(BigDecimal(257), MathContext.DECIMAL128).toDouble(), c.meanY, 1e-14)
        assertEquals(0.0, wrapAngle(c.meanHeadingRad - atan2(sumSin, sumCos)), 1e-13)
    }

    @Test fun `repeated extreme finite translations never overflow their mean`() {
        for (value in doubleArrayOf(Double.MAX_VALUE, -Double.MAX_VALUE, Double.MIN_VALUE, -Double.MIN_VALUE)) {
            val c = VisionRecoveryConsensus()
            repeat(1024) { assertTrue(c.add(value, -value, 0.1)) }
            assertEquals(1024L, c.sampleCount)
            assertEquals(value, c.meanX, 0.0)
            assertEquals(-value, c.meanY, 0.0)
            assertEquals(0.1, c.meanHeadingRad, 1e-14)
        }
    }

    @Test fun `translation continuity uses an inclusive planar norm`() {
        val inclusive = VisionRecoveryConsensus()
        inclusive.add(0.0, 0.0, 0.0)
        inclusive.add(0.35, 0.0, 0.0)
        assertEquals(2L, inclusive.sampleCount)
        for ((x, y) in listOf(Math.nextUp(0.35) to 0.0, 0.25 to 0.25)) {
            val c = VisionRecoveryConsensus()
            c.add(0.0, 0.0, 0.0)
            assertTrue(c.add(x, y, 0.0))
            assertEquals(1L, c.sampleCount)
            assertEquals(x, c.meanX, 0.0)
        }
    }

    @Test fun `heading continuity wraps and preserves the inclusive twenty degree boundary`() {
        val boundary = Math.toRadians(20.0)
        for ((heading, count) in listOf(boundary to 2L, Math.nextUp(boundary) to 1L)) {
            val c = VisionRecoveryConsensus()
            c.add(0.0, 0.0, 0.0)
            c.add(0.0, 0.0, heading)
            assertEquals(count, c.sampleCount)
        }
        val c = VisionRecoveryConsensus()
        c.add(0.0, 0.0, Math.toRadians(179.0))
        c.add(0.0, 0.0, Math.toRadians(-179.0))
        assertEquals(2L, c.sampleCount)
        assertEquals(Math.PI, abs(c.meanHeadingRad), 1e-14)
    }

    @Test fun `opposite finite extremes restart rather than overflowing differences into a mean`() {
        val c = VisionRecoveryConsensus()
        c.add(Double.MAX_VALUE, Double.MAX_VALUE, 0.0)
        assertTrue(c.add(-Double.MAX_VALUE, -Double.MAX_VALUE, 0.0))
        assertEquals(1L, c.sampleCount)
        assertEquals(-Double.MAX_VALUE, c.meanX, 0.0)
        assertEquals(-Double.MAX_VALUE, c.meanY, 0.0)
    }

    @Test fun `invalid samples clear all evidence and later valid samples start fresh`() {
        for (bad in doubleArrayOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            for (component in 0..2) {
                val c = VisionRecoveryConsensus()
                c.add(0.8, 0.2, 0.1)
                assertFalse(c.add(if (component == 0) bad else 0.8, if (component == 1) bad else 0.2,
                    if (component == 2) bad else 0.1))
                assertEquals(0L, c.sampleCount)
                assertEquals(0.0, c.meanX, 0.0)
                assertEquals(0.0, c.meanY, 0.0)
                assertEquals(0.0, c.meanHeadingRad, 0.0)
                assertTrue(c.add(0.5, 0.4, 0.3))
                assertEquals(1L, c.sampleCount)
            }
        }
    }

    @Test fun `counter exhaustion starts a new window without overflow`() {
        val c = VisionRecoveryConsensus()
        c.add(0.8, 0.0, 0.0)
        // Reach the otherwise impractical boundary without billions of years of loop updates.
        val count = VisionRecoveryConsensus::class.java.getDeclaredField("sampleCount")
        count.isAccessible = true
        count.setLong(c, Long.MAX_VALUE - 1L)
        assertTrue(c.add(0.9, 0.0, 0.0))
        assertEquals(Long.MAX_VALUE, c.sampleCount)
        assertTrue(c.add(1.0, 0.0, 0.0))
        assertEquals(1L, c.sampleCount)
        assertEquals(1.0, c.meanX, 0.0)
    }

    @Test fun `explicit clear removes heading and position evidence`() {
        val c = VisionRecoveryConsensus()
        c.add(0.8, 0.2, 1.0)
        c.clear()
        c.clear()
        assertEquals(0L, c.sampleCount)
        c.add(0.9, 0.4, -1.0)
        assertEquals(1L, c.sampleCount)
        assertEquals(0.9, c.meanX, 0.0)
        assertEquals(-1.0, c.meanHeadingRad, 1e-14)
    }

    @Test fun `warmed accumulation and threshold checks allocate no per-sample storage`() {
        val c = VisionRecoveryConsensus()
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        var valid = 0
        fun loop(count: Int) {
            repeat(count) { i ->
                if (c.add(0.8 + (i and 1) * 0.01, 0.2, 0.1) &&
                    VisionRecoveryConsensus.requiredSamples(2.5, (i and 1) == 0) > 0L) valid++
            }
        }
        loop(100_000)
        val before = bean.getThreadAllocatedBytes(thread)
        val start = System.nanoTime()
        loop(10_000)
        val nanos = System.nanoTime() - start
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        println("Recovery consensus: $allocated bytes / 10,000 samples, ${nanos / 10_000.0} ns/sample (desktop JVM)")
        assertTrue(allocated <= 4096L, "Allocated $allocated bytes")
        assertEquals(110_000, valid)
        assertEquals(110_000L, c.sampleCount)
    }
}
