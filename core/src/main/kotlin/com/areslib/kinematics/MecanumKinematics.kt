package com.areslib.kinematics

import com.areslib.math.geometry.ChassisSpeeds

/**
 * Drivetrain Forward and Inverse Kinematics Calculator for 4-Wheel Mecanum Drivetrains.
 *
 * Converts robot-frame chassis velocities $[v_x, v_y, \omega]^T$ into 4 individual wheel surface speeds ($v_{FL}, v_{FR}, v_{BL}, v_{BR}$)
 * (Inverse Kinematics) and resolves measured wheel velocities back into chassis-frame velocities (Forward Kinematics).
 *
 * ### Inverse Kinematics Matrix Equation:
 * For effective rotation moment arm $k = \frac{\text{trackWidth}}{2} + \frac{\text{wheelBase}}{2}$:
 * $$\begin{bmatrix} v_{FL} \\ v_{FR} \\ v_{BL} \\ v_{BR} \end{bmatrix} = \begin{bmatrix} 1 & -1 & -k \\ 1 & 1 & k \\ 1 & 1 & -k \\ 1 & -1 & k \end{bmatrix} \begin{bmatrix} v_x \\ v_y \\ \omega \end{bmatrix}$$
 *
 * ### Forward Kinematics Matrix Equation:
 * $$\begin{bmatrix} v_x \\ v_y \\ \omega \end{bmatrix} = \frac{1}{4} \begin{bmatrix} 1 & 1 & 1 & 1 \\ -1 & 1 & 1 & -1 \\ -\frac{1}{k} & \frac{1}{k} & -\frac{1}{k} & \frac{1}{k} \end{bmatrix} \begin{bmatrix} v_{FL} \\ v_{FR} \\ v_{BL} \\ v_{BR} \end{bmatrix}$$
 *
 * ### Physical Units & Coordinate System:
 * - Linear Dimensions: Meters ($m$)
 * - Linear Velocities: Meters per second ($m/s$), +X forward, +Y left
 * - Angular Velocities: Radians per second ($rad/s$), counter-clockwise positive
 * - Rotational Moment Arm Constant ($k$): Meters ($m$)
 *
 * ### Zero-GC Guarantees:
 * High-frequency update loops (50Hz–1000Hz) must call the primitive overload [toWheelSpeeds] passing a pre-allocated
 * `DoubleArray(4)` buffer to ensure zero heap allocations in hot paths.
 *
 * @property trackWidthMeters Distance between left and right wheel contact centers in meters ($m$).
 * @property wheelBaseMeters Distance between front and rear wheel contact centers in meters ($m$).
 * @see MecanumWheelSpeeds
 */
class MecanumKinematics(
    private val trackWidthMeters: Double,
    private val wheelBaseMeters: Double
) {
    init {
        require(trackWidthMeters > 0.0 && wheelBaseMeters > 0.0) {
            "trackWidthMeters and wheelBaseMeters must both be positive (got trackWidth=$trackWidthMeters, wheelBase=$wheelBaseMeters)"
        }
    }

    /** The effective rotational moment arm constant $k = \frac{W}{2} + \frac{L}{2}$ in meters ($m$). */
    val k: Double = (trackWidthMeters / 2.0) + (wheelBaseMeters / 2.0)

    /**
     * Calculates individual wheel surface speeds from robot-centric [ChassisSpeeds].
     *
     * @param speeds Desired robot-frame velocity vector $[v_x, v_y, \omega]^T$ (m/s, rad/s).
     * @return Calculated immutable [MecanumWheelSpeeds] containing $[v_{FL}, v_{FR}, v_{BL}, v_{BR}]$ in m/s.
     * @see toWheelSpeeds
     */
    fun toWheelSpeeds(speeds: ChassisSpeeds): MecanumWheelSpeeds {
        val vx = speeds.vxMetersPerSecond
        val vy = speeds.vyMetersPerSecond
        val omega = speeds.omegaRadiansPerSecond

        val fl = vx - vy - omega * k
        val fr = vx + vy + omega * k
        val bl = vx + vy - omega * k
        val br = vx - vy + omega * k

        return MecanumWheelSpeeds(fl, fr, bl, br)
    }

    /**
     * Converts four individual wheel surface velocities into robot-frame chassis velocities [ChassisSpeeds].
     *
     * @param fl Front-left wheel surface velocity in meters per second ($m/s$).
     * @param fr Front-right wheel surface velocity in meters per second ($m/s$).
     * @param bl Back-left (rear-left) wheel surface velocity in meters per second ($m/s$).
     * @param br Back-right (rear-right) wheel surface velocity in meters per second ($m/s$).
     * @return Calculated robot-frame [ChassisSpeeds] velocity vector $[v_x, v_y, \omega]^T$ (m/s, rad/s).
     */
    fun toChassisSpeeds(fl: Double, fr: Double, bl: Double, br: Double): ChassisSpeeds {
        val vx = (fl + fr + bl + br) / 4.0
        val vy = (-fl + fr + bl - br) / 4.0
        val denom = 4.0 * k
        val omega = if (k > 0.0 && denom > 0.0) (-fl + fr - bl + br) / denom else 0.0
        return ChassisSpeeds(vx, vy, omega)
    }

    /**
     * Zero-GC inverse kinematics variant that populates a pre-allocated 4-element output array.
     *
     * Eliminates heap allocations completely in 50Hz–1000Hz robot control loops.
     *
     * @param vx Robot forward linear velocity in meters per second ($m/s$).
     * @param vy Robot strafe linear velocity (left positive) in meters per second ($m/s$).
     * @param omega Robot rotational velocity (CCW positive) in radians per second ($rad/s$).
     * @param outSpeeds Pre-allocated 4-element array receiving $[v_{FL}, v_{FR}, v_{BL}, v_{BR}]$ in m/s.
     */
    fun toWheelSpeeds(vx: Double, vy: Double, omega: Double, outSpeeds: DoubleArray) {
        if (outSpeeds.size < 4) return
        outSpeeds[0] = vx - vy - omega * k
        outSpeeds[1] = vx + vy + omega * k
        outSpeeds[2] = vx + vy - omega * k
        outSpeeds[3] = vx - vy + omega * k
    }

    companion object {
        /**
         * Normalizes wheel speeds in-place if any wheel speed magnitude exceeds the maximum allowed speed limit.
         *
         * @param speeds 4-element array of wheel speeds $[v_{FL}, v_{FR}, v_{BL}, v_{BR}]$ (m/s).
         * @param maxSpeedMetersPerSecond Maximum allowed wheel surface speed in m/s.
         */
        fun normalize(speeds: DoubleArray, maxSpeedMetersPerSecond: Double) {
            if (speeds.size < 4) return
            if (maxSpeedMetersPerSecond <= 0.0 || maxSpeedMetersPerSecond.isNaN()) {
                speeds[0] = 0.0
                speeds[1] = 0.0
                speeds[2] = 0.0
                speeds[3] = 0.0
                return
            }

            var maxMagnitude = 0.0
            for (i in 0..3) {
                val absSpeed = kotlin.math.abs(speeds[i])
                if (absSpeed > maxMagnitude) {
                    maxMagnitude = absSpeed
                }
            }

            if (maxMagnitude > maxSpeedMetersPerSecond) {
                val scale = maxSpeedMetersPerSecond / maxMagnitude
                speeds[0] *= scale
                speeds[1] *= scale
                speeds[2] *= scale
                speeds[3] *= scale
            }
        }
    }
}
