package com.areslib.math

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.*

class InterpolatingTableOracleTest {
    private data class Sample(val value: Double) : Interpolatable<Sample> {
        override fun interpolate(other: Sample, ratio: Double) = Sample(value * (1 - ratio) + other.value * ratio)
    }
    private fun near(expected: Double, actual: Double) {
        assertTrue(actual.isFinite() && actual in 0.0..1.0)
        assertEquals(expected, actual, Math.ulp(expected) * 4)
    }
    @Test fun `floating key ratios agree with high precision differences across scales`() {
        val random = Random(4101)
        repeat(5_000) {
            val keys = DoubleArray(3) { Math.scalb(random.nextDouble(-1.0, 1.0), random.nextInt(-1074, 1024)) }.sorted()
            val lower = keys[0]; val query = keys[1]; val upper = keys[2]
            if (lower < upper) {
                val table = InterpolatingTable<Double, Sample>(); table.put(lower, Sample(0.0)); table.put(upper, Sample(1.0))
                val expected = (BigDecimal(query) - BigDecimal(lower))
                    .divide(BigDecimal(upper) - BigDecimal(lower), MathContext(80)).toDouble()
                near(expected, table.get(query)!!.value)
            }
        }
    }
    @Test fun `long key ratios agree with exact integer differences over the signed domain`() {
        val random = Random(4102)
        repeat(5_000) {
            val first = random.nextLong(); val second = random.nextLong()
            val lower = minOf(first, second); val upper = maxOf(first, second)
            if (lower < upper) {
                val start = BigInteger.valueOf(lower); val span = BigInteger.valueOf(upper) - start
                val query = (start + span * BigInteger.valueOf(random.nextLong(0, 1025)) / BigInteger.valueOf(1024)).longValueExact()
                val table = InterpolatingTable<Long, Sample>(); table.put(lower, Sample(0.0)); table.put(upper, Sample(1.0))
                val expected = BigDecimal(BigInteger.valueOf(query) - start).divide(BigDecimal(span), MathContext(80)).toDouble()
                near(expected, table.get(query)!!.value)
            }
        }
    }
    private class CountingDecimal(value: Int) : BigDecimal(value) {
        override fun toByte(): Byte = toInt().toByte()
        override fun toShort(): Short = toInt().toShort()
        override fun compareTo(other: BigDecimal): Int { comparisons++; return super.compareTo(other) }
        companion object { var comparisons = 0 }
    }
    @Test fun `one bounded search locates both interior endpoints`() {
        val table = InterpolatingTable<BigDecimal, Sample>()
        repeat(4096) { table.put(CountingDecimal(it), Sample(it.toDouble())) }
        CountingDecimal.comparisons = 0
        assertEquals(2048.5, table.get(BigDecimal("2048.5"))!!.value)
        assertTrue(CountingDecimal.comparisons <= 13, "comparisons: ${CountingDecimal.comparisons}")
    }
}
