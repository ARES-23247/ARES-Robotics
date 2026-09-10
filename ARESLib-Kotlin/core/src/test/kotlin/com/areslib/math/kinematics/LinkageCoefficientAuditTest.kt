package com.areslib.math.kinematics

import java.math.BigDecimal
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkageCoefficientAuditTest {
    @Test
    fun `scaled products match exact decimal oracle across binary exponents`() {
        val random = Random(4210)
        repeat(2_000) {
            fun number() = Math.scalb(random.nextDouble(1.0, 2.0), random.nextInt(-1074, 1024))
            val a = number()
            val b = number()
            val c = number()
            val d = number()
            val weight = random.nextDouble(-1.0, 1.0)
            val exact = BigDecimal(a).multiply(BigDecimal(b)).multiply(BigDecimal(c))
                .multiply(BigDecimal(d)).multiply(BigDecimal(weight)).toDouble()
            val actual = LinkageCoefficient.product(a, b, c, d).times(weight)
            if (exact.isInfinite()) assertEquals(exact, actual)
            else assertEquals(exact, actual, 6 * Math.ulp(exact), "case $it")
        }
    }

    @Test
    fun `sum retains small residual after ordinary and overflowing cancellation`() {
        for (large in listOf(LinkageCoefficient.product(1e200, 1.0), LinkageCoefficient.product(1e200, 1e200))) {
            val small = LinkageCoefficient.product(1e-200, 1.0)
            assertEquals(1e-200, LinkageCoefficient.sum(small, 1.0, large, 1.0, large, -1.0))
            assertEquals(1e-200, LinkageCoefficient.sum(large, -1.0, small, 1.0, large, 1.0))
        }
    }

    @Test
    fun `individually underflowed terms combine into a representable result`() {
        val halfMinimum = LinkageCoefficient.product(Double.MIN_VALUE, 0.5)
        val zero = LinkageCoefficient.product(0.0, 1.0)
        assertEquals(Double.MIN_VALUE, LinkageCoefficient.sum(halfMinimum, 1.0, halfMinimum, 1.0, zero, 0.0))
    }

    @Test
    fun `zero coefficients and invalid weights preserve unknown output semantics`() {
        val zero = LinkageCoefficient.product(0.0, Double.MAX_VALUE, Double.MAX_VALUE)
        val huge = LinkageCoefficient.product(Double.MAX_VALUE, Double.MAX_VALUE)
        assertEquals(0.0, huge.times(0.0))
        assertEquals(0.0, zero.times(1.0))
        assertTrue(zero.times(Double.NaN).isNaN())
        assertTrue(LinkageCoefficient.sum(zero, 0.0, huge, Double.NaN, zero, 0.0).isNaN())
    }

    @Test
    fun `three term sums agree with exact oracle within floating component error`() {
        val random = Random(4211)
        repeat(1_000) {
            val factors = Array(3) { DoubleArray(3) { Math.scalb(random.nextDouble(1.0, 2.0), random.nextInt(-350, 351)) } }
            val weights = DoubleArray(3) { random.nextDouble(-1.0, 1.0) }
            val terms = Array(3) { i -> factors[i].fold(BigDecimal.ONE) { value, factor -> value.multiply(BigDecimal(factor)) }.multiply(BigDecimal(weights[i])) }
            val exact = terms.reduce(BigDecimal::add).toDouble()
            val coefficients = Array(3) { i -> LinkageCoefficient.product(factors[i][0], factors[i][1], factors[i][2]) }
            val actual = LinkageCoefficient.sum(coefficients[0], weights[0], coefficients[1], weights[1], coefficients[2], weights[2])
            val magnitude = terms.maxOf { it.abs().toDouble() }
            if (exact.isInfinite()) assertEquals(exact, actual)
            else if (magnitude.isFinite()) assertEquals(exact, actual, 12 * Math.ulp(maxOf(abs(exact), magnitude)))
        }
    }
}
