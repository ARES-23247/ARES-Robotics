package com.areslib.math

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext

/**
 * Interface for data types that support 1D linear interpolation between spatial or temporal calibrated keypoints.
 *
 * @param T The concrete data class implementing interpolation.
 */
interface Interpolatable<T> {
    /**
     * Linearly interpolates between this value ($v_0$) and another target value ($v_1$) by ratio $t \in [0.0, 1.0]$.
     *
     * ### Mathematical Formulation:
     * $$v(t) = (1 - t) \cdot v_0 + t \cdot v_1 = v_0 + t \cdot (v_1 - v_0)$$
     *
     * @param other The ceiling value ($v_1$) at $t = 1.0$.
     * @param ratio The normalized interpolation ratio $t \in [0.0, 1.0]$.
     * @return Interpolated result of type [T].
     */
    fun interpolate(other: T, ratio: Double): T
}

/**
 * A calibrated 1D lookup table performing linear interpolation between ordered data points.
 *
 * Solves non-linear target mapping curves (e.g. target distance in meters $m$ to shooter flywheel RPM,
 * hood angle in degrees, or turret elevation).
 *
 * ### Mathematical Formulation:
 * For query key $k$ bounded by floor key $k_f$ and ceiling key $k_c$ ($k_f \le k \le k_c$):
 * $$t = \frac{k - k_f}{k_c - k_f}$$
 * $$v(k) = \text{interpolate}(v(k_f), v(k_c), t)$$
 *
 * ### Physical Units:
 * - Key ($K$): Distance ($m$), Time ($s$), or arbitrary numeric parameter
 * - Value ($V$): Output setpoint (e.g., $rad/s$, $m/s$, $deg$, $V$)
 *
 * Keys support finite Double/Float, Byte/Short/Int/Long, BigInteger and BigDecimal. Natural
 * ordering determines exact matches, including distinct Double signed zeros and numerically equal
 * BigDecimals with different scales. Other Comparable/Number implementations are unsupported.
 *
 * Calibration insertion is O(N), with one binary search and an array shift for a new key.
 * Reads use one O(log N) search without temporary map entries. Primitive-key ratio arithmetic
 * allocates no internal scratch objects; caller boxing and the interpolated value may allocate.
 * Big-number interpolation uses allocating decimal arithmetic and is not a zero-GC hot path.
 * Configure and query from a single owner; neither concurrent mutation nor mutable keys are supported.
 *
 * @param K Supported naturally ordered numeric key type (e.g., [Double]).
 * @param V Interpolatable output value type.
 */
class InterpolatingTable<K : Comparable<K>, V : Interpolatable<V>> {
    private class Knot<K, V>(val key: K, var value: V)
    private val knots = ArrayList<Knot<K, V>>()

    /**
     * Inserts or updates a calibrated key-value pair in the table.
     *
     * @param key Supported finite calibration key. Invalid keys throw before changing the table.
     * @param value Associated physical output setpoint at [key].
     */
    fun put(key: K, value: V) {
        require(validKey(key)) { "Calibration keys must be finite supported numeric values" }
        val found = search(key)
        if (found >= 0) knots[found].value = value
        else knots.add(-found - 1, Knot(key, value))
    }

    /**
     * Queries the table for [key], performing linear interpolation if [key] falls between two calibrated entries.
     *
     * @param key Query key value.
     * @return Exact/boundary value by identity, interpolated interior value, or `null` if empty or the
     * query is invalid/unsupported. [Interpolatable.interpolate] owns output validity and may allocate or throw.
     */
    fun get(key: K): V? {
        if (!validKey(key) || knots.isEmpty()) return null
        val found = search(key)
        if (found >= 0) return knots[found].value
        val upperIndex = -found - 1
        if (upperIndex == 0) return knots[0].value
        if (upperIndex == knots.size) return knots[upperIndex - 1].value
        val lower = knots[upperIndex - 1]
        val upper = knots[upperIndex]
        val ratio = ratio(key, lower.key, upper.key).coerceIn(0.0, 1.0)
        return lower.value.interpolate(upper.value, ratio)
    }

    private fun search(key: K): Int {
        var low = 0
        var high = knots.size - 1
        while (low <= high) {
            val middle = low + (high - low) / 2
            val comparison = knots[middle].key.compareTo(key)
            when {
                comparison < 0 -> low = middle + 1
                comparison > 0 -> high = middle - 1
                else -> return middle
            }
        }
        return -low - 1
    }

    private fun validKey(key: K): Boolean = when (key) {
        is Double -> key.isFinite()
        is Float -> key.isFinite()
        is Byte, is Short, is Int, is Long, is BigInteger, is BigDecimal -> true
        else -> false
    }

    private fun ratio(key: K, lower: K, upper: K): Double = when (key) {
        is BigInteger -> {
            val start = lower as BigInteger
            BigDecimal(key - start).divide(BigDecimal((upper as BigInteger) - start), MathContext.DECIMAL128).toDouble()
        }
        is BigDecimal -> {
            val start = lower as BigDecimal
            (key - start).divide((upper as BigDecimal) - start, MathContext.DECIMAL128).toDouble()
        }
        is Byte, is Short, is Int, is Long -> {
            // Ordered signed endpoints give exact nonnegative distances modulo 2^64.
            // Subtract before conversion so adjacent large integers keep their interval.
            val start = (lower as Number).toLong()
            val offset = ((key as Number).toLong() - start).toULong()
            val span = ((upper as Number).toLong() - start).toULong()
            offset.toDouble() / span.toDouble()
        }
        else -> floatingRatio((key as Number).toDouble(), (lower as Number).toDouble(), (upper as Number).toDouble())
    }

    private fun floatingRatio(key: Double, lower: Double, upper: Double): Double {
        val span = upper - lower
        return if (span.isFinite()) (key - lower) / span
        else (key * 0.5 - lower * 0.5) / (upper * 0.5 - lower * 0.5)
    }
}

