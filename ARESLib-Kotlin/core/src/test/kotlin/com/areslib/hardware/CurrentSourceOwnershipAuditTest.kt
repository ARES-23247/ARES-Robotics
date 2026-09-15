package com.areslib.hardware

import org.junit.jupiter.api.Test
import kotlin.test.*

class CurrentSourceOwnershipAuditTest {
    @Test
    fun `duplicate identity is sampled once and counted once`() {
        val source = Source(8.0)
        val sampler = CurrentSourceSampler(1)
        assertEquals(8.0, sampler.sample(listOf(source, source)), 1e-10)
        assertEquals(1, source.reads)
        assertEquals(1, sampler.validSelectedSourceCount)
    }

    @Test
    fun `mutual coverage cannot erase all current observations`() {
        val first = Source(8.0)
        val second = Source(10.0)
        first.covered = second
        second.covered = first
        val sampler = CurrentSourceSampler()
        assertTrue(sampler.sample(listOf(first, second)) >= 10.0)
        assertTrue(sampler.hasValidReading)
    }

    @Test
    fun `coverage cycle cannot disappear beside an independent source`() {
        val first = Source(8.0)
        val second = Source(10.0)
        val third = Source(12.0)
        first.covered = second
        second.covered = third
        third.covered = first
        val sampler = CurrentSourceSampler()
        assertTrue(sampler.sample(listOf(first, second, third, Source(2.0))) >= 14.0)
    }

    @Test
    fun `custom validity cannot bless nonfinite or negative current`() {
        val sampler = CurrentSourceSampler()
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            val source = object : CurrentSourceIO {
                override val currentAmps = invalid
                override fun isCurrentReadingValid(readingAmps: Double) = true
            }
            sampler.sample(listOf(source))
            assertFalse(sampler.hasValidReading)
            assertFalse(sampler.hasCompleteCoverage)
        }
    }

    @Test
    fun `growth shrink and empty frames expose only the current selection`() {
        val sources = List(33) { Source(1.0) }
        val sampler = CurrentSourceSampler(0)
        assertEquals(33.0, sampler.sample(sources))
        assertEquals(33, sampler.validSelectedSourceCount)
        assertTrue(sampler.hasCompleteCoverage)
        assertEquals(1.0, sampler.sample(listOf(sources[0])))
        assertEquals(1, sampler.size)
        assertSame(sources[0], sampler.sourceAt(0))
        assertEquals(1.0, sampler.readingAt(0))
        assertFalse(sampler.isSelected(-1))
        assertFalse(sampler.isSelected(1))
        assertTrue(sampler.sample(emptyList()).isNaN())
        assertFalse(sampler.hasValidReading)
        assertFalse(sampler.hasCompleteCoverage)
        assertEquals(0, sampler.size)
    }

    @Test
    fun `validator and ownership exceptions do not escape the sampler`() {
        val badValidator = object : CurrentSourceIO {
            override val currentAmps = 2.0
            override fun isCurrentReadingValid(readingAmps: Double): Boolean = error("offline validator")
        }
        val badOwnership = object : CurrentSourceIO {
            override val currentAmps = 3.0
            override fun includesCurrentFrom(other: CurrentSourceIO): Boolean = error("unavailable topology")
        }
        val sampler = CurrentSourceSampler()
        assertEquals(3.0, sampler.sample(listOf(badValidator, badOwnership)))
        assertFalse(sampler.hasCompleteCoverage)
        assertEquals(1, sampler.validSelectedSourceCount)
    }

    private class Source(private val amps: Double) : CurrentSourceIO {
        var reads = 0
        var covered: CurrentSourceIO? = null
        override val currentAmps: Double get() { reads++; return amps }
        override fun includesCurrentFrom(other: CurrentSourceIO) = other === this || other === covered
    }
}
