package com.areslib.math

import java.util.TreeMap

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
 * @param K Comparable numeric key type (e.g., [Double]).
 * @param V Interpolatable output value type.
 */
class InterpolatingTable<K : Comparable<K>, V : Interpolatable<V>> {
    private val map = TreeMap<K, V>()

    /**
     * Inserts or updates a calibrated key-value pair in the table.
     *
     * @param key Calibrated key point (e.g., target distance in meters).
     * @param value Associated physical output setpoint at [key].
     */
    fun put(key: K, value: V) {
        map[key] = value
    }

    /**
     * Queries the table for [key], performing linear interpolation if [key] falls between two calibrated entries.
     *
     * @param key Query key value.
     * @return Interpolated value [V], exact matched value if present, boundary endpoint if out of bounds, or `null` if empty.
     */
    fun get(key: K): V? {
        val exact = map[key]
        if (exact != null) return exact

        val floorKey = map.floorKey(key)
        val ceilingKey = map.ceilingKey(key)

        if (floorKey == null && ceilingKey == null) return null
        if (floorKey == null) return map[ceilingKey]
        if (ceilingKey == null) return map[floorKey]

        val floorVal = map[floorKey] ?: return null
        val ceilingVal = map[ceilingKey] ?: return null

        val k = key.toDouble()
        val fK = floorKey.toDouble()
        val cK = ceilingKey.toDouble()

        if (cK == fK) return floorVal

        val ratio = (k - fK) / (cK - fK)
        return floorVal.interpolate(ceilingVal, ratio)
    }

    private fun K.toDouble(): Double {
        return when (this) {
            is Number -> this.toDouble()
            else -> throw IllegalArgumentException("Key must be a Number to interpolate")
        }
    }
}

