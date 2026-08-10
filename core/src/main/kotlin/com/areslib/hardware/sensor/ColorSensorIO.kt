package com.areslib.hardware.sensor

/**
 * Pure abstraction for reading color sensor telemetry.
 * Keeps the sensor logic completely simulation-friendly and decoupled from the FTC SDK.
 */
interface ColorSensorIO {
    /**
     * Raw red value (0 to 255 or native scale)
     */
    val red: Int

    /**
     * Raw green value (0 to 255 or native scale)
     */
    val green: Int

    /**
     * Raw blue value (0 to 255 or native scale)
     */
    val blue: Int

    /**
     * Raw alpha (intensity) value
     */
    val alpha: Int

    /**
     * Normalized RGBA intensity values (0.0 to 1.0)
     * Ordered as: [0] = Red, [1] = Green, [2] = Blue, [3] = Alpha.
     * The returned array is caller-owned; hot paths should use [copyNormalizedRgbInto].
     */
    val normalizedRgb: DoubleArray

    /** Copies normalized RGBA into caller-owned storage without transferring mutable ownership. */
    fun copyNormalizedRgbInto(destination: DoubleArray) {
        require(destination.size >= 4) { "Normalized RGBA destination must contain at least four elements" }
        normalizedRgb.copyInto(destination, endIndex = 4)
    }
}
