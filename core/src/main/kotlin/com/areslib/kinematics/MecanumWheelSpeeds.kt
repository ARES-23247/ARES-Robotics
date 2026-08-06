package com.areslib.kinematics

import kotlin.math.abs

/**
 * Pure immutable data container representing the four individual wheel surface velocities of a Mecanum drivetrain.
 *
 * Encapsulates wheel velocities $\mathbf{v}_{wheel} = [v_{FL}, v_{FR}, v_{BL}, v_{BR}]^T$ in physical surface speed units.
 *
 * ### Physical Units & Properties:
 * - Wheel Surface Velocities: Meters per second ($m/s$)
 * - Normalization Bound: Maximum allowable physical wheel velocity $v_{max}$ ($m/s$)
 *
 * @property frontLeftMetersPerSecond Surface speed of front-left wheel in meters per second ($m/s$).
 * @property frontRightMetersPerSecond Surface speed of front-right wheel in meters per second ($m/s$).
 * @property backLeftMetersPerSecond Surface speed of back-left (rear-left) wheel in meters per second ($m/s$).
 * @property backRightMetersPerSecond Surface speed of back-right (rear-right) wheel in meters per second ($m/s$).
 *
 * @see MecanumKinematics
 */
data class MecanumWheelSpeeds(
    val frontLeftMetersPerSecond: Double = 0.0,
    val frontRightMetersPerSecond: Double = 0.0,
    val backLeftMetersPerSecond: Double = 0.0,
    val backRightMetersPerSecond: Double = 0.0
) {
    /**
     * Normalizes wheel velocities if any single wheel magnitude exceeds [maxSpeedMetersPerSecond].
     *
     * Computes the uniform scaling factor:
     * $$\beta = \frac{v_{max}}{\max(|v_{FL}|, |v_{FR}|, |v_{BL}|, |v_{BR}|)}$$
     * and returns a scaled velocity container when $\max(|v_i|) > v_{max}$, preserving the drive trajectory geometry.
     *
     * @param maxSpeedMetersPerSecond Maximum allowable wheel surface speed limit in meters per second ($m/s$).
     * @return Scaled [MecanumWheelSpeeds] instance guaranteed not to exceed physical speed limits.
     * @see MecanumKinematics.normalize
     */
    fun normalize(maxSpeedMetersPerSecond: Double): MecanumWheelSpeeds {
        val maxMagnitude = maxOf(
            abs(frontLeftMetersPerSecond),
            abs(frontRightMetersPerSecond),
            abs(backLeftMetersPerSecond),
            abs(backRightMetersPerSecond)
        )
        
        if (maxMagnitude > maxSpeedMetersPerSecond) {
            val scale = maxSpeedMetersPerSecond / maxMagnitude
            return MecanumWheelSpeeds(
                frontLeftMetersPerSecond * scale,
                frontRightMetersPerSecond * scale,
                backLeftMetersPerSecond * scale,
                backRightMetersPerSecond * scale
            )
        }
        
        return this
    }
}
