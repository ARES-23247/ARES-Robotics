package com.areslib.math.filter

import java.math.BigDecimal
import java.math.MathContext
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.*

class PrimitiveFilterOracleTest {
    private val precision = MathContext(80)
    private fun near(expected: Double, actual: Double, scale: Double = expected) {
        assertTrue(actual.isFinite(), "nonfinite output for expected $expected")
        assertTrue(actual == expected || abs(actual - expected) <= maxOf(Math.ulp(expected), Math.ulp(scale)) * 4,
            "expected $expected, actual $actual, scale $scale")
    }
    @Test fun `incremental median agrees with an independent sorted-window oracle through resets`() {
        val random = Random(3901)
        val special = doubleArrayOf(-Double.MAX_VALUE, Double.MAX_VALUE, -0.0, 0.0, Double.MIN_VALUE, -Double.MIN_VALUE)
        for (capacity in listOf(1, 2, 5, 16, 33)) {
            val filter = MedianFilter(capacity); val history = ArrayDeque<Double>()
            repeat(1_000) { index ->
                val sample = if (index % 4 == 0) special[random.nextInt(special.size)] else random.nextDouble(-1e100, 1e100)
                when {
                    index % 97 == 0 -> { filter.clear(); history.clear() }
                    index % 71 == 0 -> { filter.reset(sample); history.clear(); repeat(capacity) { history.addLast(sample) } }
                    index % 19 == 0 -> filter.calculate(Double.NaN)
                    else -> { filter.calculate(sample); if (history.size == capacity) history.removeFirst(); history.addLast(sample) }
                }
                val ordered = history.sorted()
                val expected = if (ordered.isEmpty()) 0.0 else if (ordered.size % 2 == 1) ordered[ordered.size / 2]
                    else (BigDecimal(ordered[ordered.size / 2 - 1]) + BigDecimal(ordered[ordered.size / 2])).divide(BigDecimal(2)).toDouble()
                repeat(3) { near(expected, filter.value) }
            }
        }
    }
    @Test fun `low-pass agrees with high precision RC weighting across extreme scales`() {
        val signals = listOf(0.0 to 10.0, 1e300 to 0.0, 0.0 to 1e300,
            -Double.MAX_VALUE to Double.MAX_VALUE, 1e-310 to 2e-310, 1e100 to -2e100)
        val times = listOf(1.0 to 1.0, Double.MAX_VALUE to Double.MAX_VALUE,
            1e-300 to 1e300, 1e300 to 1e-300, 0.1 to 0.02, 2.0 to Double.MIN_VALUE)
        for ((previous, sample) in signals) for ((rc, dt) in times) {
            val filter = LowPassFilter(rc); filter.reset(previous)
            val expected = (BigDecimal(previous) * BigDecimal(rc) + BigDecimal(sample) * BigDecimal(dt))
                .divide(BigDecimal(rc) + BigDecimal(dt), precision).toDouble()
            val actual = filter.calculate(sample, dt)
            near(expected, actual, if (previous * sample < 0.0) maxOf(abs(previous), abs(sample)) else expected)
            assertTrue(actual in minOf(previous, sample)..maxOf(previous, sample))
        }
    }
    @Test fun `slew agrees with high precision clipping when differences and products overflow`() {
        val states = listOf(-Double.MAX_VALUE, -1e200, -Double.MIN_VALUE, 0.0, Double.MIN_VALUE, 1e200, Double.MAX_VALUE)
        val budgets = listOf(0.0 to 1.0, Double.MAX_VALUE to 1.0, Double.MAX_VALUE to 1.5,
            Double.MAX_VALUE to 3.0, 1e200 to 1e200, 2.0 to 0.02)
        for (previous in states) for (target in states) for ((rate, dt) in budgets) {
            val filter = SlewRateLimiter(rate, initialValue = previous)
            val difference = BigDecimal(target) - BigDecimal(previous)
            val budget = BigDecimal(rate) * BigDecimal(dt)
            val step = difference.max(budget.negate()).min(budget)
            val expected = (BigDecimal(previous) + step).toDouble()
            val actual = filter.calculate(target, dt)
            near(expected, actual, maxOf(abs(previous), abs(target)))
            assertTrue(actual in minOf(previous, target)..maxOf(previous, target))
        }
    }
}
