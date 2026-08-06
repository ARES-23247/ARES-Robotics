package com.areslib.control.assist

import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.control.feedback.PIDController

/**
 * Intelligent Vision-Based Drivetrain Steering & Centering Assist.
 *
 * Blends manual driver joystick commands with real-time vision target tracking PID feedback
 * to automate piece intake pickup or scoring alignment while allowing driver throttle control ($v_x$).
 *
 * ### Control Mathematics:
 * When target is visible, calculated feedback adjustments are clamped and blended:
 * $$u_{yaw} = \text{coerce}\left(\text{PID}_{yaw}(e_{yaw}, 0), -2.0, 2.0\right)$$
 * $$u_{lat} = \text{coerce}\left(\text{PID}_{lat}(e_{lat}, 0), -1.5, 1.5\right)$$
 * $$v_{x,blended} = v_{x,driver}$$
 * $$v_{y,blended} = v_{y,driver} + u_{lat}$$
 * $$\omega_{blended} = \omega_{driver} + u_{yaw}$$
 *
 * ### Physical Units & Coordinate System:
 * - Linear Velocity ($v_x, v_y$): Meters per second ($m/s$), +X forward, +Y left
 * - Angular Velocity ($\omega$): Radians per second ($rad/s$), CCW positive
 * - Yaw Error ($e_{yaw}$): Degrees ($^\circ$)
 * - Lateral Error ($e_{lat}$): Meters ($m$)
 * - Timestep ($\Delta t$): Seconds ($s$)
 *
 * @param yawPID PID controller for heading orientation correction ($rad/s$ per degree).
 * @param lateralPID PID controller for lateral centering correction ($m/s$ per meter).
 * @see PIDController
 */
class IntakeTargetAssist(
    private val yawPID: PIDController = PIDController(0.04, 0.0, 0.002),
    private val lateralPID: PIDController = PIDController(0.8, 0.0, 0.05)
) {

    /**
     * Calculates blended [ChassisSpeeds] incorporating manual driver commands and closed-loop vision adjustments.
     *
     * @param driverManualSpeeds Raw driver input velocities $[v_x, v_y, \omega]^T$ (m/s, rad/s).
     * @param targetVisible `true` if camera vision currently tracks a target; `false` otherwise.
     * @param yawErrorDegrees Target angular offset from camera centerline in degrees ($^\circ$).
     * @param lateralErrorMeters Target lateral translation offset from camera center in meters ($m$).
     * @param dtSeconds Elapsed cycle loop timestep in seconds ($s$).
     * @return Commanded blended [ChassisSpeeds] (m/s, rad/s).
     */
    fun calculateAssistedSpeeds(
        driverManualSpeeds: ChassisSpeeds,
        targetVisible: Boolean,
        yawErrorDegrees: Double,
        lateralErrorMeters: Double,
        dtSeconds: Double
    ): ChassisSpeeds {
        if (!targetVisible) {
            // No target visible; return raw driver speeds unmodified
            return driverManualSpeeds
        }

        // Calculate vision-based steering and centering feedback corrections
        val rawRotCorrection = yawPID.calculate(yawErrorDegrees, 0.0, dtSeconds)
        val rawLateralCorrection = lateralPID.calculate(lateralErrorMeters, 0.0, dtSeconds)

        // Enforce tight bounds on correction limits manually
        val rotCorrection = rawRotCorrection.coerceIn(-2.0, 2.0)
        val lateralCorrection = rawLateralCorrection.coerceIn(-1.5, 1.5)

        // Blend driver's manual velocity with the closed-loop vision adjustments
        // Let the driver retain control over forward/backward velocity (vx),
        // but assist them with rotational (omega) and lateral (vy) alignment.
        val blendedVy = driverManualSpeeds.vyMetersPerSecond + lateralCorrection
        val blendedOmega = driverManualSpeeds.omegaRadiansPerSecond + rotCorrection

        return ChassisSpeeds(
            vxMetersPerSecond = driverManualSpeeds.vxMetersPerSecond,
            vyMetersPerSecond = blendedVy,
            omegaRadiansPerSecond = blendedOmega
        )
    }
}

