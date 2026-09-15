package com.areslib.kinematics

import com.areslib.math.geometry.ChassisSpeeds

/**
 * Drivetrain Forward and Inverse Kinematics Calculator for Differential (2-Wheel) Drivetrains.
 *
 * Converts robot-frame chassis velocities [v_x, \omega]^T into 2 individual wheel surface speeds (v_L, v_R)
 * (Inverse Kinematics) and resolves measured wheel velocities back into chassis-frame velocities (Forward Kinematics).
 *
 * ### Inverse Kinematics Equation:
 * For track width W:
 * v_L = v_x - \omega * (W / 2)
 * v_R = v_x + \omega * (W / 2)
 *
 * ### Forward Kinematics Equation:
 * v_x = (v_L + v_R) / 2
 * v_y = 0.0
 * \omega = (v_R - v_L) / W
 *
 * ### Physical Units & Coordinate System:
 * - Linear Dimensions: Meters (m)
 * - Linear Velocities: Meters per second (m/s), +X forward
 * - Angular Velocities: Radians per second (rad/s), CCW-positive (\omega > 0 -> left turn)
 *
 * Raw conversions follow IEEE arithmetic: invalid inputs or genuinely unrepresentable
 * components may be nonfinite. Normalization neutralizes invalid coupled wheel vectors.
 * Buffered methods require at least two elements and reject short buffers before mutation;
 * trailing output elements remain untouched. Returned value objects are independently owned.
 *
 * ### Zero-GC Guarantees:
 * High-frequency update loops (50Hz–1000Hz) must call the primitive overload [toWheelSpeeds] passing a pre-allocated
 * `DoubleArray(2)` buffer to eliminate heap allocations in hot paths.
 *
 * @property trackWidthMeters Distance between left and right wheel contact centers in meters (m).
 */
class DifferentialDriveKinematics(
    val trackWidthMeters: Double
) {
    init {
        require(trackWidthMeters.isFinite() && trackWidthMeters > 0.0) {
            "trackWidthMeters must be positive (got trackWidth=$trackWidthMeters)"
        }
    }

    private val halfTrackWidth: Double = trackWidthMeters / 2.0
    private fun rotationalSpeed(omega: Double): Double =
        if (halfTrackWidth >= java.lang.Double.MIN_NORMAL) omega * halfTrackWidth else (omega * 0.5) * trackWidthMeters

    /**
     * Calculates individual wheel surface speeds from robot-centric [ChassisSpeeds].
     *
     * @param speeds Desired robot-frame velocity vector [v_x, v_y, \omega]^T (m/s, rad/s).
     * @return Calculated immutable [DifferentialWheelSpeeds] containing [v_L, v_R] in m/s.
     */
    fun toWheelSpeeds(speeds: ChassisSpeeds): DifferentialWheelSpeeds {
        val vx = speeds.vxMetersPerSecond
        val omega = speeds.omegaRadiansPerSecond

        val rotation = rotationalSpeed(omega)
        val left = vx - rotation
        val right = vx + rotation

        return DifferentialWheelSpeeds(left, right)
    }

    /**
     * Zero-GC overload for high-frequency control loops.
     *
     * @param speeds Input velocity vector [v_x, v_y, \omega]^T.
     * @param output Pre-allocated DoubleArray where output[0] = v_L and output[1] = v_R.
     */
    fun toWheelSpeeds(speeds: ChassisSpeeds, output: DoubleArray) {
        require(output.size >= 2) { "Differential wheel output requires two elements" }
        val vx = speeds.vxMetersPerSecond
        val omega = speeds.omegaRadiansPerSecond

        val rotation = rotationalSpeed(omega)
        output[0] = vx - rotation
        output[1] = vx + rotation
    }

    /**
     * Zero-GC primitive velocity overload.
     *
     * @param vx Forward velocity in m/s.
     * @param omega Angular velocity in rad/s (CCW positive).
     * @param outSpeeds Pre-allocated DoubleArray where outSpeeds[0] = v_L and outSpeeds[1] = v_R.
     */
    fun toWheelSpeeds(vx: Double, omega: Double, outSpeeds: DoubleArray) {
        require(outSpeeds.size >= 2) { "Differential wheel output requires two elements" }
        val rotation = rotationalSpeed(omega)
        outSpeeds[0] = vx - rotation
        outSpeeds[1] = vx + rotation
    }

    /**
     * Resolves individual wheel speeds back into robot-centric [ChassisSpeeds].
     *
     * @param wheelSpeeds Measured wheel speeds [v_L, v_R] in m/s.
     * @return Resulting forward chassis speeds [v_x, 0, \omega]^T.
     */
    fun toChassisSpeeds(wheelSpeeds: DifferentialWheelSpeeds): ChassisSpeeds =
        toChassisSpeeds(wheelSpeeds.leftMetersPerSecond, wheelSpeeds.rightMetersPerSecond)

    /**
     * Resolves raw wheel velocities back into robot-centric [ChassisSpeeds].
     *
     * @param leftMetersPerSecond Left wheel surface velocity in m/s.
     * @param rightMetersPerSecond Right wheel surface velocity in m/s.
     * @return Resulting forward chassis speeds [v_x, 0, \omega]^T.
     */
    fun toChassisSpeeds(leftMetersPerSecond: Double, rightMetersPerSecond: Double): ChassisSpeeds {
        val vx = wheelMean(leftMetersPerSecond, rightMetersPerSecond)
        val omega = wheelDifferenceRatio(rightMetersPerSecond, leftMetersPerSecond, trackWidthMeters)
        return ChassisSpeeds(vx, 0.0, omega)
    }

    companion object {
        /**
         * Normalizes wheel speeds in-place if either wheel speed magnitude exceeds the maximum allowed speed limit.
         *
         * @param speeds 2-element array of wheel speeds [v_L, v_R] (m/s).
         * @param maxSpeedMetersPerSecond Maximum allowed wheel surface speed in m/s.
         */
        fun normalize(speeds: DoubleArray, maxSpeedMetersPerSecond: Double) {
            require(speeds.size >= 2) { "Differential wheel speeds require two elements" }
            val maxMagnitude = kotlin.math.max(kotlin.math.abs(speeds[0]), kotlin.math.abs(speeds[1]))
            val scale = wheelSpeedScale(maxMagnitude, maxSpeedMetersPerSecond)
            speeds[0] = scaledWheelSpeed(speeds[0], maxMagnitude, maxSpeedMetersPerSecond, scale)
            speeds[1] = scaledWheelSpeed(speeds[1], maxMagnitude, maxSpeedMetersPerSecond, scale)
        }
    }
}
