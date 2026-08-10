package com.areslib.hardware.sensor

/**
 * Pure abstraction for reading a multizone Time-of-Flight rangefinder (like the VL53L5CX 8x8 or 4x4 sensor).
 * This makes multizone distance tracking mockable, simulation-friendly, and decoupled.
 */
interface MultizoneDistanceSensorIO {
    /**
     * Number of grid rows (e.g., 4 or 8).
     */
    val rows: Int

    /**
     * Number of grid columns (e.g., 4 or 8).
     */
    val columns: Int

    /**
     * Flattened array of distances in meters for each zone.
     * The length of this array is [rows] * [columns] and the returned array is caller-owned.
     * Hot paths should use [copyDistancesMetersInto].
     */
    val distancesMeters: DoubleArray

    /** Copies the latest flattened zone frame into caller-owned [destination]. */
    fun copyDistancesMetersInto(destination: DoubleArray): Int {
        val source = distancesMeters
        val count = minOf(source.size, destination.size)
        source.copyInto(destination, endIndex = count)
        return count
    }
}
