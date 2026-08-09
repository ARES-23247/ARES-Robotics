package com.areslib.kinematics

import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.math.geometry.Translation2d
import com.areslib.math.geometry.Rotation2d
import kotlin.math.hypot
import kotlin.math.atan2
import com.areslib.math.wrapAngle

/**
 * Swerve Drivetrain Forward and Inverse Kinematics Calculator with Second-Order Dynamics Constraints.
 *
 * Converts robot-frame chassis velocities $[v_x, v_y, \omega]^T$ into individual module drive velocities ($m/s$) and steering angles ($\theta$).
 * Applies second-order kinematics bounds to limit steering angular velocity ($\omega_{steer}$), steering angular acceleration ($\alpha_{steer}$),
 * and drive linear acceleration ($a_{drive}$).
 *
 * ### Inverse Kinematics Formulation:
 * For each module $i$ positioned at physical offset vector $[x_i, y_i]^T$ relative to the robot center of rotation:
 * $$v_{x,i} = v_x - \omega \cdot y_i$$
 * $$v_{y,i} = v_y + \omega \cdot x_i$$
 * $$v_{module,i} = \sqrt{v_{x,i}^2 + v_{y,i}^2}, \quad \theta_{module,i} = \text{atan2}(v_{y,i}, v_{x,i})$$
 *
 * ### Steer Angle Optimization ($\le 90^\circ$ Rule):
 * If the steering angular delta $\Delta \theta = \text{wrap}(\theta_{target} - \theta_{current})$ exceeds $90^\circ$ ($\pi/2$ rad):
 * $$\theta_{optimized} = \text{wrap}(\theta_{target} + \pi), \quad v_{optimized} = -v_{target}$$
 *
 * ### Physical Units & Coordinate System:
 * - Module Positions: Meters ($m$) relative to robot center (+X forward, +Y left)
 * - Drive Velocity: Meters per second ($m/s$)
 * - Module Heading: Radians ($rad$), counter-clockwise positive (0° = +X forward)
 * - Steering Velocity Limit ($\omega_{steer}$): Radians per second ($rad/s$)
 * - Steering Acceleration Limit ($\alpha_{steer}$): Radians per second squared ($rad/s^2$)
 * - Drive Acceleration Limit ($a_{drive}$): Meters per second squared ($m/s^2$)
 * - Timestep ($\Delta t$): Seconds ($s$)
 *
 * ### Zero-GC Guarantees:
 * High-frequency update loops (50Hz–1000Hz) should call [toSwerveModuleStates] with a pre-allocated array of
 * [SwerveModuleState] objects to avoid heap allocation overhead.
 *
 * @property moduleTranslations List of 2D translation vectors defining physical module positions relative to robot center of mass ($m$).
 * @property maxSteerVelRadPerSec Maximum allowable steering rotation speed limit in rad/s (default: $4\pi$ rad/s).
 * @property maxSteerAccelRadPerSec2 Maximum allowable steering angular acceleration limit in rad/s² (default: $8\pi$ rad/s²).
 * @property maxDriveAccelMps2 Maximum allowable linear drive acceleration limit in m/s² (default: $8.0$ m/s²).
 * @see SwerveModuleState
 */
class SwerveKinematics(
    val moduleTranslations: List<Translation2d>,
    val maxSteerVelRadPerSec: Double = Math.PI * 4.0,
    val maxSteerAccelRadPerSec2: Double = Math.PI * 8.0,
    val maxDriveAccelMps2: Double = 8.0
) {
    private val numModules = moduleTranslations.size
    private val previousSteerVels = DoubleArray(numModules) { 0.0 }
    private val previousStates = Array(numModules) { SwerveModuleState() }
    private var hasPreviousState = false
    private val targetStatesBuffer = Array(numModules) { SwerveModuleState() }

    constructor(vararg moduleTranslations: Translation2d) : this(moduleTranslations.toList())

    /**
     * Converts robot-frame [ChassisSpeeds] to an array of [SwerveModuleState]s with second-order kinematics limits applied.
     *
     * @param chassisSpeeds Desired robot-frame velocities $[v_x, v_y, \omega]$ (m/s, rad/s).
     * @param dtSeconds Loop cycle elapsed time in seconds (default: 0.02s / 50Hz).
     * @return Array of calculated target [SwerveModuleState]s.
     */
    fun toSwerveModuleStates(chassisSpeeds: ChassisSpeeds, dtSeconds: Double = 0.02): Array<SwerveModuleState> {
        val out = Array(numModules) { SwerveModuleState() }
        toSwerveModuleStates(chassisSpeeds, dtSeconds, out)
        return out
    }

    /**
     * Zero-GC allocation variant of inverse kinematics into a pre-allocated output array.
     *
     * @param chassisSpeeds Desired robot-frame velocities $[v_x, v_y, \omega]$ (m/s, rad/s).
     * @param dtSeconds Loop cycle elapsed time in seconds.
     * @param outStates Pre-allocated output array of [SwerveModuleState] instances to overwrite.
     */
    fun toSwerveModuleStates(
        chassisSpeeds: ChassisSpeeds,
        dtSeconds: Double,
        outStates: Array<SwerveModuleState>
    ) {
        if (!dtSeconds.isFinite() || dtSeconds <= 0.0 ||
            !chassisSpeeds.vxMetersPerSecond.isFinite() ||
            !chassisSpeeds.vyMetersPerSecond.isFinite() ||
            !chassisSpeeds.omegaRadiansPerSecond.isFinite() ||
            (chassisSpeeds.vxMetersPerSecond == 0.0 && 
             chassisSpeeds.vyMetersPerSecond == 0.0 && 
             chassisSpeeds.omegaRadiansPerSecond == 0.0)) {
            
            for (i in 0 until numModules) {
                outStates[i].speedMetersPerSecond = 0.0
                outStates[i].angle = previousStates[i].angle
                previousStates[i].speedMetersPerSecond = 0.0
            }
            hasPreviousState = true
            return
        }

        for (i in 0 until numModules) {
            val module = moduleTranslations[i]
            val vx = chassisSpeeds.vxMetersPerSecond - chassisSpeeds.omegaRadiansPerSecond * module.y
            val vy = chassisSpeeds.vyMetersPerSecond + chassisSpeeds.omegaRadiansPerSecond * module.x
            
            val speed = hypot(vx, vy)
            if (speed > 1e-4) {
                targetStatesBuffer[i].angle = Rotation2d(atan2(vy, vx))
            } else {
                targetStatesBuffer[i].angle = previousStates[i].angle
            }
            targetStatesBuffer[i].speedMetersPerSecond = speed
        }

        for (i in 0 until numModules) {
            val target = targetStatesBuffer[i]
            val prev = previousStates[i]

            optimizeModuleState(target, prev.angle, target) // modify target directly
            val optimized = target

            if (hasPreviousState && dtSeconds > 0.0) {
                val steerErr = wrapAngle(optimized.angle.radians - prev.angle.radians)
                val targetSteerVel = (steerErr / dtSeconds).coerceIn(-maxSteerVelRadPerSec, maxSteerVelRadPerSec)

                val prevSteerVel = previousSteerVels[i]
                val steerVelErr = targetSteerVel - prevSteerVel
                val maxDeltaVel = maxSteerAccelRadPerSec2 * dtSeconds
                val limitedSteerVel = prevSteerVel + steerVelErr.coerceIn(-maxDeltaVel, maxDeltaVel)

                previousSteerVels[i] = limitedSteerVel

                val limitedAngleRad = wrapAngle(prev.angle.radians + limitedSteerVel * dtSeconds)
                optimized.angle = Rotation2d(limitedAngleRad)

                val driveVelErr = optimized.speedMetersPerSecond - prev.speedMetersPerSecond
                val maxDriveDeltaVel = maxDriveAccelMps2 * dtSeconds
                optimized.speedMetersPerSecond = prev.speedMetersPerSecond + driveVelErr.coerceIn(-maxDriveDeltaVel, maxDriveDeltaVel)
            } else {
                previousSteerVels[i] = 0.0
            }

            outStates[i].speedMetersPerSecond = optimized.speedMetersPerSecond
            outStates[i].angle = optimized.angle

            previousStates[i].speedMetersPerSecond = optimized.speedMetersPerSecond
            previousStates[i].angle = optimized.angle
        }

        hasPreviousState = true
    }

    /**
     * Resets internal steer-state memory so a shared [SwerveKinematics] instance does not
     * bleed teleop steering state into a subsequent autonomous run.
     *
     * Clears [hasPreviousState] (so the next update seeds angles from chassis velocity rather
     * than stale previous angles) and zeros [previousSteerVels] (so the steering-velocity
     * second-order limits restart from rest).
     */
    fun reset() {
        hasPreviousState = false
        for (i in 0 until numModules) {
            previousSteerVels[i] = 0.0
        }
    }

    /**
     * Minimizes module steering angular travel by flipping target orientation by $180^\circ$ ($\pi$ rad)
     * and negating drive velocity magnitude if the steering angular delta exceeds $90^\circ$ ($\pi/2$ rad).
     *
     * @param desired Raw desired target [SwerveModuleState].
     * @param currentAngle Current measured module steering orientation [Rotation2d] ($rad$).
     * @return Optimized target [SwerveModuleState].
     */
    fun optimizeModuleState(desired: SwerveModuleState, currentAngle: Rotation2d): SwerveModuleState {
        val out = SwerveModuleState()
        optimizeModuleState(desired, currentAngle, out)
        return out
    }

    /**
     * Zero-GC variant of [optimizeModuleState] populating a pre-allocated output [out] instance in-place.
     *
     * @param desired Raw desired target [SwerveModuleState].
     * @param currentAngle Current measured module steering orientation [Rotation2d] ($rad$).
     * @param out Pre-allocated [SwerveModuleState] output container receiving optimized parameters.
     */
    fun optimizeModuleState(desired: SwerveModuleState, currentAngle: Rotation2d, out: SwerveModuleState) {
        var delta = wrapAngle(desired.angle.radians - currentAngle.radians)
        var targetSpeed = desired.speedMetersPerSecond

        if (kotlin.math.abs(delta) > Math.PI / 2.0) {
            delta = wrapAngle(delta + Math.PI)
            targetSpeed = -targetSpeed
        }

        out.speedMetersPerSecond = targetSpeed
        out.angle = Rotation2d(currentAngle.radians + delta)
    }

    /**
     * Normalizes all module drive speeds in-place if any single module velocity magnitude exceeds $v_{max}$.
     *
     * Scales all drive velocities uniformly by $\beta = \frac{v_{max}}{\max(|v_i|)}$ to maintain trajectory path curvature.
     *
     * @param moduleStates Array of [SwerveModuleState] targets modified in-place.
     * @param maxSpeedMps Maximum allowable physical drive speed in meters per second ($m/s$).
     */
    fun desaturateWheelSpeeds(moduleStates: Array<SwerveModuleState>, maxSpeedMps: Double) {
        var realMaxSpeed = 0.0
        for (state in moduleStates) {
            val absSpeed = kotlin.math.abs(state.speedMetersPerSecond)
            if (absSpeed > realMaxSpeed) {
                realMaxSpeed = absSpeed
            }
        }
        if (realMaxSpeed > maxSpeedMps && realMaxSpeed > 1e-4) {
            val scale = maxSpeedMps / realMaxSpeed
            for (state in moduleStates) {
                state.speedMetersPerSecond *= scale
            }
        }
    }
}
