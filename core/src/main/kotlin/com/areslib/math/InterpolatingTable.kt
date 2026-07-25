package com.areslib.math

import java.util.TreeMap

/**
 * Interface for data types that can be linearly interpolated.
 */
interface Interpolatable<T> {
    /**
     * Interpolates between this value and another value.
     * @param other The other value
     * @param ratio The interpolation ratio (0.0 = this, 1.0 = other)
     */
    fun interpolate(other: T, ratio: Double): T
}

/**
 * A lookup table that performs linear interpolation between calibrated data points.
 * Extremely useful for determining distance-based shooting parameters (flywheel speed, cowl angle, etc.)
 */
class InterpolatingTable<K : Comparable<K>, V : Interpolatable<V>> {
    private val map = TreeMap<K, V>()

    /**
     * put declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun put(key: K, value: V) {
        map[key] = value
    }

    /**
     * get declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
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
