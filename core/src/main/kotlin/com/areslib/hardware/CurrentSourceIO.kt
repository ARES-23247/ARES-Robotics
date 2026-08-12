package com.areslib.hardware

/**
 * Cached electrical-current observation exposed by robot hardware boundaries.
 *
 * Implementations must never perform hardware IO from these getters. Sensor reads belong in
 * [SubsystemIO.refresh]; power managers consume the resulting coherent loop snapshot.
 */
interface CurrentSourceIO {
    /** Cached supply or best-available aggregate current in amperes. */
    val currentAmps: Double

    /** Validates an already-sampled value without performing a second hardware/cache read. */
    fun isCurrentReadingValid(readingAmps: Double): Boolean = readingAmps.isFinite() && readingAmps >= 0.0

    /** Whether [currentAmps] is a fresh, finite, non-negative observation. */
    val currentReadingValid: Boolean
        get() {
            val reading = currentAmps
            return isCurrentReadingValid(reading)
        }

    /**
     * Returns true when this reading already contains [other]'s current. Power managers use this
     * ownership relation to prefer the aggregate and avoid summing it with its constituents.
     * Composite hardware implementations should override using identity checks only.
     */
    fun includesCurrentFrom(other: CurrentSourceIO): Boolean = this === other
}
