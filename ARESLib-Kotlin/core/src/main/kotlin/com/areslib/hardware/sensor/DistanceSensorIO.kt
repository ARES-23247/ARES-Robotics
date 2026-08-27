package com.areslib.hardware.sensor

/**
 * Pure abstraction for reading a distance/range sensor (e.g. ToF, LiDAR, ultrasonic).
 * Keeps the sensor logic completely simulation-friendly and decoupled from the FTC SDK.
 */
interface DistanceSensorIO {
    /**
     * Returns the measured distance in meters.
     * Returns Double.NaN or Double.POSITIVE_INFINITY if out of range or sensor is offline.
     */
    val distanceMeters: Double
}
