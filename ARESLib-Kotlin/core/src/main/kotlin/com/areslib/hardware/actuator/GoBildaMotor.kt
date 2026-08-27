package com.areslib.hardware.actuator

import kotlin.math.PI

/**
 * Represents all common goBILDA Yellow Jacket planetary gear motors
 * and handles the conversion math between raw encoder ticks and physical units
 * (shaft rotations, degrees, radians, and wheel distance).
 */
enum class GoBildaMotor(
    val ratioName: String,
    val ratedRpm: Int,
    val exactRatio: Double,
    val ticksPerRev: Double
) {
    RPM_1150("3.7:1", 1150, 3.705, 103.8),
    RPM_712("5.2:1", 712, 5.197, 145.6),
    RPM_435("13.7:1", 435, 13.687, 383.6),
    RPM_312("19.2:1", 312, 19.203, 537.7),
    RPM_223("26.9:1", 223, 26.851, 751.8),
    RPM_117("50.9:1", 117, 50.897, 1425.1),
    RPM_60("99.5:1", 60, 99.539, 2786.2),
    RPM_43("139:1", 43, 139.130, 3895.9);

    /**
     * Converts raw encoder ticks to output shaft rotations.
     */
    fun ticksToRotations(ticks: Double): Double = ticks / ticksPerRev

    /**
     * Converts output shaft rotations to raw encoder ticks.
     */
    fun rotationsToTicks(rotations: Double): Double = rotations * ticksPerRev

    /**
     * Converts raw encoder ticks to shaft angle in radians.
     */
    fun ticksToRadians(ticks: Double): Double = ticksToRotations(ticks) * 2.0 * PI

    /**
     * Converts shaft angle in radians to raw encoder ticks.
     */
    fun radiansToTicks(radians: Double): Double = rotationsToTicks(radians / (2.0 * PI))

    /**
     * Converts raw encoder ticks to shaft angle in degrees.
     */
    fun ticksToDegrees(ticks: Double): Double = ticksToRotations(ticks) * 360.0

    /**
     * Converts shaft angle in degrees to raw encoder ticks.
     */
    fun degreesToTicks(degrees: Double): Double = rotationsToTicks(degrees / 360.0)

    /**
     * Converts raw encoder ticks to linear distance traveled by a wheel.
     * @param wheelDiameterMeters The diameter of the wheel in meters.
     */
    fun ticksToMeters(ticks: Double, wheelDiameterMeters: Double): Double {
        return ticksToRotations(ticks) * PI * wheelDiameterMeters
    }

    /**
     * Converts linear distance in meters to raw encoder ticks.
     * @param distanceMeters The distance in meters.
     * @param wheelDiameterMeters The diameter of the wheel in meters.
     */
    fun metersToTicks(distanceMeters: Double, wheelDiameterMeters: Double): Double {
        return rotationsToTicks(distanceMeters / (PI * wheelDiameterMeters))
    }
}
