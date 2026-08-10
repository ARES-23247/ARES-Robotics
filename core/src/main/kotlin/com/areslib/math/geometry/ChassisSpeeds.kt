package com.areslib.math.geometry

/**
 * Holonomic Chassis Velocity State Representation.
 *
 * Stores translational linear velocities $(v_x, v_y)$ and rotational angular velocity $\omega$
 * in either robot-centric or field-centric frames of reference.
 *
 * ### Mathematical Formulation:
 * Field-relative to Robot-centric inverse rotation transformation:
 * $$\begin{bmatrix} v_{x, \text{robot}} \\ v_{y, \text{robot}} \end{bmatrix} = \begin{bmatrix} \cos\theta & \sin\theta \\ -\sin\theta & \cos\theta \end{bmatrix} \begin{bmatrix} v_{x, \text{field}} \\ v_{y, \text{field}} \end{bmatrix}$$
 *
 * ### Physical Units & Coordinate Conventions:
 * - $v_x$: Forward linear velocity in meters per second ($m/s$). Positive is forward.
 * - $v_y$: Leftward (strafe) linear velocity in meters per second ($m/s$). Positive is left.
 * - $\omega$: Angular velocity in radians per second ($rad/s$). **CCW-positive**.
 * - Heading $(\theta)$: Robot heading in radians ($rad$), **CCW-positive** ($0 = +X$, $\frac{\pi}{2} = +Y$).
 *
 * ### Zero-GC Guarantee:
 * Value-oriented primitive fields with minimal allocation overhead during high-frequency control loops.
 *
 * @property vxMetersPerSecond Linear velocity along X-axis in meters per second ($m/s$).
 * @property vyMetersPerSecond Linear velocity along Y-axis in meters per second ($m/s$).
 * @property omegaRadiansPerSecond Angular velocity about Z-axis in radians per second ($rad/s$).
 */
data class ChassisSpeeds(
    var vxMetersPerSecond: Double = 0.0,
    var vyMetersPerSecond: Double = 0.0,
    var omegaRadiansPerSecond: Double = 0.0
) {
    companion object {
        /**
         * Converts field-relative velocities $(v_{x, \text{field}}, v_{y, \text{field}}, \omega)$ into robot-centric [ChassisSpeeds].
         * Applies the inverse rotation matrix $\mathbf{R}(-\theta)$.
         *
         * @param vxMetersPerSecond Field-centric X velocity in meters per second ($m/s$).
         * @param vyMetersPerSecond Field-centric Y velocity in meters per second ($m/s$).
         * @param omegaRadiansPerSecond Rotational velocity in radians per second ($rad/s$).
         * @param robotHeading Current robot heading orientation [Rotation2d].
         * @return Equivalent robot-centric [ChassisSpeeds].
         */
        fun fromFieldRelativeSpeeds(
            vxMetersPerSecond: Double,
            vyMetersPerSecond: Double,
            omegaRadiansPerSecond: Double,
            robotHeading: Rotation2d
        ): ChassisSpeeds {
            val cos = robotHeading.cos
            val sin = robotHeading.sin
            val robotX = vxMetersPerSecond * cos + vyMetersPerSecond * sin
            val robotY = -vxMetersPerSecond * sin + vyMetersPerSecond * cos
            return ChassisSpeeds(robotX, robotY, omegaRadiansPerSecond)
        }

        /**
         * Discretizes continuous-time chassis speeds over timestep [dtSeconds] using the exact inverse-SE(2)
         * translation Jacobian. The returned constant robot-frame twist exponentiates to the requested
         * pose increment $(v_x\Delta t, v_y\Delta t, \omega\Delta t)$.
         *
         * @param vxMetersPerSecond Continuous X velocity in meters per second ($m/s$).
         * @param vyMetersPerSecond Continuous Y velocity in meters per second ($m/s$).
         * @param omegaRadiansPerSecond Rotational velocity in radians per second ($rad/s$).
         * @param dtSeconds Loop period duration in seconds ($s$).
         * @return Discretized [ChassisSpeeds].
         */
        fun discretize(
            vxMetersPerSecond: Double,
            vyMetersPerSecond: Double,
            omegaRadiansPerSecond: Double,
            dtSeconds: Double
        ): ChassisSpeeds {
            if (!vxMetersPerSecond.isFinite() || !vyMetersPerSecond.isFinite() ||
                !omegaRadiansPerSecond.isFinite() || !dtSeconds.isFinite() || dtSeconds <= 0.0
            ) {
                return ChassisSpeeds()
            }

            val dTheta = omegaRadiansPerSecond * dtSeconds
            val halfTheta = dTheta * 0.5
            val halfThetaByTanHalfTheta = if (kotlin.math.abs(dTheta) < 1e-6) {
                1.0 - dTheta * dTheta / 12.0
            } else {
                halfTheta / kotlin.math.tan(halfTheta)
            }

            // Inverse of the SE(2) exponential's translation Jacobian. This produces
            // the constant robot-frame twist whose exponential exactly reaches the
            // desired (vx * dt, vy * dt, omega * dt) pose increment.
            val discVx = halfThetaByTanHalfTheta * vxMetersPerSecond + halfTheta * vyMetersPerSecond
            val discVy = -halfTheta * vxMetersPerSecond + halfThetaByTanHalfTheta * vyMetersPerSecond
            return ChassisSpeeds(discVx, discVy, omegaRadiansPerSecond)
        }
    }
}

