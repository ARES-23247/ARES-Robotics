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
        require(trackWidthMeters > 0.0) {
            "trackWidthMeters must be positive (got trackWidth=$trackWidthMeters)"
        }
    }

    private val halfTrackWidth: Double = trackWidthMeters / 2.0
    private val invTrackWidth: Double = 1.0 / trackWidthMeters

    /**
     * Calculates individual wheel surface speeds from robot-centric [ChassisSpeeds].
     *
     * @param speeds Desired robot-frame velocity vector [v_x, v_y, \omega]^T (m/s, rad/s).
     * @return Calculated immutable [DifferentialWheelSpeeds] containing [v_L, v_R] in m/s.
     */
    fun toWheelSpeeds(speeds: ChassisSpeeds): DifferentialWheelSpeeds {
        val vx = speeds.vxMetersPerSecond
        val omega = speeds.omegaRadiansPerSecond

        val left = vx - omega * halfTrackWidth
        val right = vx + omega * halfTrackWidth

        return DifferentialWheelSpeeds(left, right)
    }

    /**
     * Zero-GC overload for high-frequency control loops.
     *
     * @param speeds Input velocity vector [v_x, v_y, \omega]^T.
     * @param output Pre-allocated DoubleArray where output[0] = v_L and output[1] = v_R.
     */
    fun toWheelSpeeds(speeds: ChassisSpeeds, output: DoubleArray) {
        if (output.size < 2) return
        val vx = speeds.vxMetersPerSecond
        val omega = speeds.omegaRadiansPerSecond

        output[0] = vx - omega * halfTrackWidth
        output[1] = vx + omega * halfTrackWidth
    }

    /**
     * Zero-GC primitive velocity overload.
     *
     * @param vx Forward velocity in m/s.
     * @param omega Angular velocity in rad/s (CCW positive).
     * @param outSpeeds Pre-allocated DoubleArray where outSpeeds[0] = v_L and outSpeeds[1] = v_R.
     */
    fun toWheelSpeeds(vx: Double, omega: Double, outSpeeds: DoubleArray) {
        if (outSpeeds.size < 2) return
        outSpeeds[0] = vx - omega * halfTrackWidth
        outSpeeds[1] = vx + omega * halfTrackWidth
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
        val vx = (leftMetersPerSecond + rightMetersPerSecond) * 0.5
        val omega = (rightMetersPerSecond - leftMetersPerSecond) * invTrackWidth
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
            if (speeds.size < 2) return
            if (maxSpeedMetersPerSecond <= 0.0 || maxSpeedMetersPerSecond.isNaN()) {
                speeds[0] = 0.0
                speeds[1] = 0.0
                return
            }

            val m0 = kotlin.math.abs(speeds[0])
            val m1 = kotlin.math.abs(speeds[1])
            val maxMagnitude = kotlin.math.max(m0, m1)

            if (maxMagnitude > maxSpeedMetersPerSecond) {
                val scale = maxSpeedMetersPerSecond / maxMagnitude
                speeds[0] *= scale
                speeds[1] *= scale
            }
        }
    }
}
