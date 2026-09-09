package com.areslib.math

import java.math.BigDecimal
import java.math.BigInteger
import org.junit.jupiter.api.Test
import kotlin.test.*

class InterpolatingTableAuditTest {
    private data class Sample(val value: Double) : Interpolatable<Sample> {
        override fun interpolate(other: Sample, ratio: Double) = Sample(value * (1.0 - ratio) + other.value * ratio)
    }
    @Test fun `finite overflowing key span preserves midpoint`() {
        val table = InterpolatingTable<Double, Sample>()
        table.put(-Double.MAX_VALUE, Sample(0.0)); table.put(Double.MAX_VALUE, Sample(1.0))
        assertEquals(0.5, table.get(0.0)!!.value)
        assertEquals(0.75, table.get(Double.MAX_VALUE * 0.5)!!.value, 1e-15)
    }
    @Test fun `adjacent large long keys retain their interval`() {
        val table = InterpolatingTable<Long, Sample>()
        table.put(Long.MAX_VALUE - 2, Sample(0.0)); table.put(Long.MAX_VALUE, Sample(1.0))
        assertEquals(0.5, table.get(Long.MAX_VALUE - 1)!!.value)
    }
    @Test fun `huge integer keys retain a small local offset`() {
        val lower = BigInteger.TEN.pow(400)
        val table = InterpolatingTable<BigInteger, Sample>()
        table.put(lower, Sample(0.0)); table.put(lower + BigInteger.TWO, Sample(1.0))
        assertEquals(0.5, table.get(lower + BigInteger.ONE)!!.value)
    }
    @Test fun `decimal keys retain precision beyond double representation`() {
        val table = InterpolatingTable<BigDecimal, Sample>()
        table.put(BigDecimal("1.00000000000000000000"), Sample(0.0))
        table.put(BigDecimal("1.00000000000000000002"), Sample(1.0))
        assertEquals(0.5, table.get(BigDecimal("1.00000000000000000001"))!!.value)
    }
    @Test fun `nonfinite queries return no calibrated command`() {
        val table = InterpolatingTable<Double, Sample>()
        table.put(0.0, Sample(0.0)); table.put(1.0, Sample(1.0))
        for (invalid in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) assertNull(table.get(invalid))
    }
    @Test fun `nonfinite calibration keys are rejected without changing valid entries`() {
        val table = InterpolatingTable<Double, Sample>()
        table.put(0.0, Sample(0.0)); table.put(1.0, Sample(1.0))
        for (invalid in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { table.put(invalid, Sample(999.0)) }
        }
        assertEquals(0.5, table.get(0.5)!!.value)
    }
    @Test fun `nonnumeric calibration keys fail before becoming partially usable`() {
        val table = InterpolatingTable<String, Sample>()
        assertFailsWith<IllegalArgumentException> { table.put("a", Sample(0.0)) }
        assertNull(table.get("a"))
    }

    @Test fun `insertion order replacement and endpoint queries preserve value identity`() {
        val table = InterpolatingTable<Double, Sample>()
        val low = Sample(0.0); val high = Sample(1.0); val middle = Sample(0.5)
        table.put(1.0, high); table.put(0.0, low); table.put(0.5, middle)
        assertSame(low, table.get(-1.0)); assertSame(high, table.get(2.0)); assertSame(middle, table.get(0.5))
        val replacement = Sample(0.75); table.put(0.5, replacement)
        assertSame(replacement, table.get(0.5)); assertEquals(0.375, table.get(0.25)!!.value)
        assertEquals(0.875, table.get(0.75)!!.value)
    }

    @Test fun `signed zero ordering and decimal numeric equality retain exact-match semantics`() {
        val doubles = InterpolatingTable<Double, Sample>()
        val minus = Sample(-1.0); val plus = Sample(1.0)
        doubles.put(-0.0, minus); doubles.put(0.0, plus)
        assertSame(minus, doubles.get(-0.0)); assertSame(plus, doubles.get(0.0))
        val decimals = InterpolatingTable<BigDecimal, Sample>()
        decimals.put(BigDecimal("1.0"), minus); decimals.put(BigDecimal("1.00"), plus)
        assertSame(plus, decimals.get(BigDecimal.ONE)); assertSame(plus, decimals.get(BigDecimal.TEN))
    }

    @Test fun `all primitive numeric key families interpolate`() {
        fun <K : Comparable<K>> check(lower: K, key: K, upper: K) {
            val table = InterpolatingTable<K, Sample>(); table.put(lower, Sample(0.0)); table.put(upper, Sample(1.0))
            assertEquals(0.5, table.get(key)!!.value)
        }
        check((-2).toByte(), 0.toByte(), 2.toByte())
        check((-2).toShort(), 0.toShort(), 2.toShort())
        check(-2, 0, 2); check(-2L, 0L, 2L); check(-2f, 0f, 2f); check(-2.0, 0.0, 2.0)
    }

    @Test fun `full long span and subnormal double span retain finite ratios`() {
        val longs = InterpolatingTable<Long, Sample>()
        longs.put(Long.MIN_VALUE, Sample(0.0)); longs.put(Long.MAX_VALUE, Sample(1.0))
        assertEquals(0.5, longs.get(0L)!!.value)
        assertEquals(1.0 / Math.scalb(1.0, 64), longs.get(Long.MIN_VALUE + 1)!!.value, 1e-34)
        val doubles = InterpolatingTable<Double, Sample>()
        doubles.put(0.0, Sample(0.0)); doubles.put(2 * Double.MIN_VALUE, Sample(1.0))
        assertEquals(0.5, doubles.get(Double.MIN_VALUE)!!.value)
    }

    @Test fun `invalid float calibration and queries follow the same contract`() {
        val table = InterpolatingTable<Float, Sample>(); table.put(0f, Sample(0.0))
        for (invalid in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { table.put(invalid, Sample(1.0)) }
            assertNull(table.get(invalid))
        }
    }

    @Test fun `interpolation exceptions propagate without corrupting calibration`() {
        class Failing : Interpolatable<Failing> {
            override fun interpolate(other: Failing, ratio: Double): Failing = error("custom value failure")
        }
        val table = InterpolatingTable<Double, Failing>(); val low = Failing(); val high = Failing()
        table.put(0.0, low); table.put(1.0, high)
        assertEquals("custom value failure", assertFailsWith<IllegalStateException> { table.get(0.5) }.message)
        assertSame(low, table.get(0.0)); assertSame(high, table.get(1.0))
    }
}
