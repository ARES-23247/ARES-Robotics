package com.areslib.kinematics

import kotlin.math.abs

/**
 * Pure immutable data container representing the two individual wheel surface velocities of a Differential drivetrain.
 *
 * Encapsulates wheel velocities \mathbf{v}_{wheel} = [v_L, v_R]^T in physical surface speed units.
 *
 * @property leftMetersPerSecond Surface speed of left wheel in meters per second (m/s).
 * @property rightMetersPerSecond Surface speed of right wheel in meters per second (m/s).
 *
 * @see DifferentialDriveKinematics
 */
data class DifferentialWheelSpeeds(
    val leftMetersPerSecond: Double = 0.0,
    val rightMetersPerSecond: Double = 0.0,
) {
    /**
     * Normalizes wheel velocities if either wheel magnitude exceeds [maxSpeedMetersPerSecond].
     *
     * @param maxSpeedMetersPerSecond Maximum allowable wheel surface speed limit in meters per second (m/s).
     * @return Scaled [DifferentialWheelSpeeds] instance guaranteed not to exceed physical speed limits.
     */
    fun normalize(maxSpeedMetersPerSecond: Double): DifferentialWheelSpeeds {
        val maxMagnitude = maxOf(abs(leftMetersPerSecond), abs(rightMetersPerSecond))
        if (maxMagnitude > maxSpeedMetersPerSecond && maxMagnitude > 0.0) {
            val scale = maxSpeedMetersPerSecond / maxMagnitude
            return DifferentialWheelSpeeds(
                leftMetersPerSecond * scale,
                rightMetersPerSecond * scale,
            )
        }
        return this
    }
}
