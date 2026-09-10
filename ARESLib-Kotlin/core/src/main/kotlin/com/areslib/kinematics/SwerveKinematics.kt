package com.areslib.kinematics

import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.math.geometry.Translation2d
import com.areslib.math.geometry.Rotation2d
import kotlin.math.hypot
import kotlin.math.atan2
import com.areslib.math.wrapAngle

/**
 * Stateful swerve inverse kinematics with steering velocity/acceleration and drive acceleration limits.
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
 * Construction, owning overloads and invalid-buffer exceptions may allocate. Geometry is an
 * immutable owned snapshot; solver mutation belongs to one control-loop thread. Per-tick
 * duplicate-output validation uses N(N-1)/2 identity comparisons (six for four modules).
 *
 * The first nonzero call after construction/reset seeds ideal module targets without rate limits.
 * Send an initial zero command to seed rest before a rate-limited start. Exactly zero commands,
 * invalid commands/time, and unrepresentable coupled module vectors neutralize all drive speeds
 * immediately and clear steering velocity, retaining the last valid angles. This emergency
 * neutral path intentionally bypasses acceleration limits. It does not establish hardware enable,
 * feedback freshness or steering tracking. Rate-limited targets may overshoot an angle while
 * decelerating; this is a discrete velocity limiter, not a time-optimal steering trajectory.
 *
 * @property moduleTranslations List of 2D translation vectors defining physical module positions relative to robot center of mass ($m$).
 * @property maxSteerVelRadPerSec Maximum allowable steering rotation speed limit in rad/s (default: $4\pi$ rad/s).
 * @property maxSteerAccelRadPerSec2 Maximum allowable steering angular acceleration limit in rad/s² (default: $8\pi$ rad/s²).
 * @property maxDriveAccelMps2 Maximum allowable linear drive acceleration limit in m/s² (default: $8.0$ m/s²).
 * @see SwerveModuleState
 */
class SwerveKinematics(
    moduleTranslations: List<Translation2d>,
    val maxSteerVelRadPerSec: Double = Math.PI * 4.0,
    val maxSteerAccelRadPerSec2: Double = Math.PI * 8.0,
    val maxDriveAccelMps2: Double = 8.0
) {
    val moduleTranslations: List<Translation2d> = java.util.Collections.unmodifiableList(ArrayList(moduleTranslations))

    init {
        require(this.moduleTranslations.all { it.x.isFinite() && it.y.isFinite() }) { "Module coordinates must be finite" }
        require(maxSteerVelRadPerSec.isFinite() && maxSteerVelRadPerSec >= 0.0) { "Steering velocity limit must be finite and nonnegative" }
        require(maxSteerAccelRadPerSec2.isFinite() && maxSteerAccelRadPerSec2 >= 0.0) { "Steering acceleration limit must be finite and nonnegative" }
        require(maxDriveAccelMps2.isFinite() && maxDriveAccelMps2 >= 0.0) { "Drive acceleration limit must be finite and nonnegative" }
    }

    private val numModules = this.moduleTranslations.size
    private val previousSteerVels = DoubleArray(numModules) { 0.0 }
    private val previousStates = Array(numModules) { SwerveModuleState() }
    private var hasPreviousState = false
    private val targetStatesBuffer = Array(numModules) { SwerveModuleState() }
    private val nextSteerVels = DoubleArray(numModules)

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
     * @param outStates At least one distinct, nonnull state per configured module. Invalid buffers
     * reject before changing outputs or history; trailing elements remain untouched.
     */
    fun toSwerveModuleStates(
        chassisSpeeds: ChassisSpeeds,
        dtSeconds: Double,
        outStates: Array<SwerveModuleState>
    ) {
        require(outStates.size >= numModules) { "Swerve output requires $numModules distinct module states" }
        validateStates(outStates, numModules)
        if (!dtSeconds.isFinite() || dtSeconds <= 0.0 ||
            !chassisSpeeds.vxMetersPerSecond.isFinite() ||
            !chassisSpeeds.vyMetersPerSecond.isFinite() ||
            !chassisSpeeds.omegaRadiansPerSecond.isFinite() ||
            (chassisSpeeds.vxMetersPerSecond == 0.0 && 
             chassisSpeeds.vyMetersPerSecond == 0.0 && 
             chassisSpeeds.omegaRadiansPerSecond == 0.0)) {
            
            neutralize(outStates)
            return
        }

        for (i in 0 until numModules) {
            val module = this.moduleTranslations[i]
            val vx = component(chassisSpeeds.vxMetersPerSecond, -chassisSpeeds.omegaRadiansPerSecond, module.y)
            val vy = component(chassisSpeeds.vyMetersPerSecond, chassisSpeeds.omegaRadiansPerSecond, module.x)
            
            val speed = hypot(vx, vy)
            if (!speed.isFinite()) {
                neutralize(outStates)
                return
            }
            if (speed > 0.0) {
                targetStatesBuffer[i].angle = Rotation2d(atan2(vy, vx))
            } else {
                targetStatesBuffer[i].angle = previousStates[i].angle
            }
            targetStatesBuffer[i].speedMetersPerSecond = speed
        }

        val maxDeltaSteerVel = maxSteerAccelRadPerSec2 * dtSeconds
        val maxDeltaDriveVel = maxDriveAccelMps2 * dtSeconds
        for (i in 0 until numModules) {
            val target = targetStatesBuffer[i]
            val prev = previousStates[i]

            optimizeModuleState(target, prev.angle, target) // modify target directly
            val optimized = target

            if (hasPreviousState) {
                val steerErr = wrapAngle(optimized.angle.radians - prev.angle.radians)
                val targetSteerVel = (steerErr / dtSeconds).coerceIn(-maxSteerVelRadPerSec, maxSteerVelRadPerSec)

                val limitedSteerVel = moveTowards(previousSteerVels[i], targetSteerVel, maxDeltaSteerVel)

                nextSteerVels[i] = limitedSteerVel

                val angle = prev.angle.radians + limitedSteerVel * dtSeconds
                if (!angle.isFinite()) {
                    neutralize(outStates)
                    return
                }
                val limitedAngleRad = wrapAngle(angle)
                optimized.angle = Rotation2d(limitedAngleRad)

                optimized.speedMetersPerSecond = moveTowards(prev.speedMetersPerSecond,
                    optimized.speedMetersPerSecond, maxDeltaDriveVel)
            } else {
                nextSteerVels[i] = 0.0
            }
        }

        // Commit only after every coupled module has a finite target.
        for (i in 0 until numModules) {
            val optimized = targetStatesBuffer[i]
            outStates[i].speedMetersPerSecond = optimized.speedMetersPerSecond
            outStates[i].angle = optimized.angle

            previousStates[i].speedMetersPerSecond = optimized.speedMetersPerSecond
            previousStates[i].angle = optimized.angle
            previousSteerVels[i] = nextSteerVels[i]
        }

        hasPreviousState = true
    }

    private fun neutralize(outStates: Array<SwerveModuleState>) {
        for (i in 0 until numModules) {
            outStates[i].speedMetersPerSecond = 0.0
            outStates[i].angle = previousStates[i].angle
            previousStates[i].speedMetersPerSecond = 0.0
            previousSteerVels[i] = 0.0
        }
        hasPreviousState = true
    }

    private fun validateStates(states: Array<SwerveModuleState>, count: Int) {
        for (i in 0 until count) {
            requireNotNull(states[i]) { "Swerve module $i is null" }
            for (j in 0 until i) require(states[i] !== states[j]) { "Swerve module states must be distinct" }
        }
    }

    // Only an overflowing product needs scaling. Finite ordinary products keep their usual
    // rounding; halving the sum avoids rejecting a representable cancellation with translation.
    private fun component(translation: Double, omega: Double, offset: Double): Double {
        val product = omega * offset
        return if (product.isFinite()) translation + product else
            ((omega * 0.5) * offset + translation * 0.5) * 2.0
    }

    private fun moveTowards(current: Double, target: Double, maximumDelta: Double): Double =
        if (current < target) {
            if (target - current <= maximumDelta) target else minOf(target, current + maximumDelta)
        } else {
            if (current - target <= maximumDelta) target else maxOf(target, current - maximumDelta)
        }

    /**
     * Resets internal steer-state memory so a shared [SwerveKinematics] instance does not
     * bleed teleop steering state into a subsequent autonomous run.
     *
     * Clears angles, drive speeds and steering velocities, then clears [hasPreviousState]. The
     * next nonzero call seeds ideal targets; an initial zero call instead seeds rate-limited rest.
     */
    fun reset() {
        hasPreviousState = false
        for (i in 0 until numModules) {
            previousSteerVels[i] = 0.0
            previousStates[i].speedMetersPerSecond = 0.0
            previousStates[i].angle = Rotation2d()
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
     * It may alias [desired]. Invalid raw speed/angle inputs yield zero drive speed and the valid
     * current heading, or zero heading if current feedback is invalid. No validity is inferred
     * from the legacy angle wrapper's nonfinite-to-zero fallback.
     */
    fun optimizeModuleState(desired: SwerveModuleState, currentAngle: Rotation2d, out: SwerveModuleState) {
        val current = currentAngle.radians
        val target = desired.angle.radians
        if (!currentAngle.rawRadians.isFinite() || !desired.angle.rawRadians.isFinite() || !desired.speedMetersPerSecond.isFinite()) {
            out.speedMetersPerSecond = 0.0
            out.angle = Rotation2d(if (currentAngle.rawRadians.isFinite()) current else 0.0)
            return
        }
        val delta = wrapAngle(target - current)
        var targetSpeed = desired.speedMetersPerSecond
        var targetAngle = target

        if (kotlin.math.abs(delta) > Math.PI / 2.0) {
            targetAngle = wrapAngle(target + Math.PI)
            targetSpeed = -targetSpeed
        }

        out.speedMetersPerSecond = targetSpeed
        out.angle = Rotation2d(targetAngle)
    }

    /**
     * Normalizes all module drive speeds in-place if any single module velocity magnitude exceeds $v_{max}$.
     *
     * Scales all drive velocities uniformly by $\beta = \frac{v_{max}}{\max(|v_i|)}$ to maintain trajectory path curvature.
     *
     * @param moduleStates Distinct, nonnull targets modified in-place. Invalid arrays reject
     * before mutation; an aliased wheel must not be scaled twice.
     * @param maxSpeedMps Maximum allowable physical drive speed in meters per second ($m/s$).
     */
    fun desaturateWheelSpeeds(moduleStates: Array<SwerveModuleState>, maxSpeedMps: Double) {
        validateStates(moduleStates, moduleStates.size)
        var realMaxSpeed = 0.0
        for (state in moduleStates) {
            val absSpeed = kotlin.math.abs(state.speedMetersPerSecond)
            realMaxSpeed = kotlin.math.max(realMaxSpeed, absSpeed)
        }
        val scale = wheelSpeedScale(realMaxSpeed, maxSpeedMps)
        if (scale.isNaN() || scale < 1.0) {
            for (state in moduleStates) {
                state.speedMetersPerSecond = scaledWheelSpeed(state.speedMetersPerSecond, realMaxSpeed, maxSpeedMps, scale)
            }
        }
    }
}
