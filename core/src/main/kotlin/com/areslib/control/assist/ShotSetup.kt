package com.areslib.control.assist

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.math.geometry.Translation2d
import kotlin.math.*
import com.areslib.math.wrapAngle

/**
 * Pre-allocated result container for zero-allocation Shoot-on-the-Move (SOTM) trajectory calculations.
 *
 * Populated in-place by [ShotSetup.calculate] to maintain 100% Zero-GC compliance during 50Hz update loops.
 *
 * @property virtualTargetX X coordinate of the virtual lookahead target in field coordinates ($m$).
 * @property virtualTargetY Y coordinate of the virtual lookahead target in field coordinates ($m$).
 * @property aimAngleRad Field-relative aiming angle towards virtual target in radians ($rad$, CCW positive).
 * @property robotTargetHeadingRad Required robot chassis heading angle in radians ($rad$, CCW positive).
 * @property aimDistanceMeters Distance from shooter offset to virtual target in meters ($m$).
 * @property targetFlywheelRpm Target interpolated flywheel velocity in Revolutions Per Minute ($RPM$).
 * @property targetCowlAngleRotations Target interpolated cowl/hood position in mechanism rotations.
 * @property angularVelocityFeedforwardRadPerSec Direct derivative feedforward rate for heading rotation in radians per second ($rad/s$).
 */
class ShotResult {
    var virtualTargetX: Double = 0.0
    var virtualTargetY: Double = 0.0
    var aimAngleRad: Double = 0.0
    var robotTargetHeadingRad: Double = 0.0
    var aimDistanceMeters: Double = 0.0
    var targetFlywheelRpm: Double = 0.0
    var targetCowlAngleRotations: Double = 0.0

    /**
     * Compatibility alias retained for callers compiled against the old, incorrectly
     * labeled property. Values have always been mechanism rotations, never degrees.
     */
    @Deprecated("Cowl lookup values are rotations, not degrees", ReplaceWith("targetCowlAngleRotations"))
    var targetCowlAngleDegrees: Double
        get() = targetCowlAngleRotations
        set(value) {
            targetCowlAngleRotations = value
        }
    var angularVelocityFeedforwardRadPerSec: Double = 0.0
}

/**
 * Robot-specific shooting mechanism geometry footprint and ballistic tuning configuration.
 *
 * Encapsulates physical offset distance of the shooter relative to chassis center of mass,
 * system latency compensation, and 1D distance lookup tables for time-of-flight, flywheel RPM, and cowl angle.
 *
 * @property shooterOffsetX Forward offset of shooter center relative to chassis center in meters ($m$).
 * @property shooterOffsetY Lateral left offset of shooter center relative to chassis center in meters ($m$).
 * @property tofKeys Sorted distance breakpoints for time-of-flight interpolation ($m$).
 * @property tofValues Projectile time-of-flight corresponding to each distance breakpoint ($s$).
 * @property shotKeys Sorted distance breakpoints for RPM and cowl angle interpolation ($m$).
 * @property shotRpm Target flywheel rotational speeds corresponding to distance breakpoints ($RPM$).
 * @property shotCowl Cowl mechanism rotations corresponding to each distance breakpoint.
 * This compatibility name is retained for source compatibility; use [shotCowlRotations]
 * when reading the configured table.
 * @property delayCompensationSeconds Phase delay compensation for system latency ($s$).
 * @property shooterFacesRearward `true` if shooter points out the rear of the robot (180° offset from front).
 */
data class ShotConfig(
    val shooterOffsetX: Double,
    val shooterOffsetY: Double,
    val tofKeys: DoubleArray,
    val tofValues: DoubleArray,
    val shotKeys: DoubleArray,
    val shotRpm: DoubleArray,
    val shotCowl: DoubleArray,
    val delayCompensationSeconds: Double = 0.05,
    val shooterFacesRearward: Boolean = true
) {
    /** Explicitly unit-labeled view of [shotCowl]. */
    val shotCowlRotations: DoubleArray get() = shotCowl

    init {
        require(tofKeys.isNotEmpty()) { "tofKeys and tofValues must not be empty" }
        require(shotKeys.isNotEmpty()) { "shot lookup tables must not be empty" }
        require(tofKeys.size == tofValues.size) { "tofKeys and tofValues must have the same length" }
        require(shotKeys.size == shotRpm.size) { "shotKeys and shotRpm must have the same length" }
        require(shotKeys.size == shotCowl.size) { "shotKeys and shotCowl must have the same length" }
        require(shooterOffsetX.isFinite() && shooterOffsetY.isFinite()) { "Shooter offsets must be finite" }
        require(delayCompensationSeconds.isFinite() && delayCompensationSeconds >= 0.0) {
            "Delay compensation must be finite and non-negative"
        }
        requireStrictlyIncreasingFinite(tofKeys, "tofKeys")
        requireStrictlyIncreasingFinite(shotKeys, "shotKeys")
        requireFinite(tofValues, "tofValues")
        requireFinite(shotRpm, "shotRpm")
        requireFinite(shotCowl, "shotCowl")
    }

    private fun requireStrictlyIncreasingFinite(values: DoubleArray, name: String) {
        for (index in values.indices) {
            require(values[index].isFinite()) { "$name must contain only finite values" }
            if (index > 0) {
                require(values[index] > values[index - 1]) { "$name must be strictly increasing" }
            }
        }
    }

    private fun requireFinite(values: DoubleArray, name: String) {
        for (index in values.indices) {
            require(values[index].isFinite()) { "$name must contain only finite values" }
        }
    }
}

/**
 * Pure functional lookahead coordinate solver for Shoot-on-the-Move (SOTM).
 *
 * Computes exact virtual target aiming vector, target flywheel RPM, cowl angle, and rotational feedforward ($\omega_{FF}$)
 * using an iterative latency-compensated lookahead convergence algorithm.
 *
 * ### Mathematical Algorithm:
 * 1. **Phase Delay Compensation**:
 *    $$\mathbf{x}_{comp} = \mathbf{x}_{robot} + \mathbf{v}_{chassis} \cdot \Delta t_{delay}, \quad \theta_{comp} = \theta_{robot} + \omega_{chassis} \cdot \Delta t_{delay}$$
 * 2. **Shooter Offset Transformation**:
 *    $$\begin{bmatrix} o_{x,rot} \\ o_{y,rot} \end{bmatrix} = \begin{bmatrix} \cos\theta_{comp} & -\sin\theta_{comp} \\ \sin\theta_{comp} & \cos\theta_{comp} \end{bmatrix} \begin{bmatrix} o_x \\ o_y \end{bmatrix}$$
 *    $$\mathbf{x}_{shooter} = \mathbf{x}_{comp} + \mathbf{o}_{rot}$$
 * 3. **Field Velocity of Shooter**:
 *    $$v_{shooter,x} = v_{x,field} - \omega \cdot o_{y,rot}, \quad v_{shooter,y} = v_{y,field} + \omega \cdot o_{x,rot}$$
 * 4. **Iterative Lookahead Convergence (5 steps)**:
 *    $$\mathbf{x}_{virtual}^{(k+1)} = \mathbf{x}_{target} - \mathbf{v}_{shooter} \cdot \text{TOF}\left(\|\mathbf{x}_{virtual}^{(k)} - \mathbf{x}_{shooter}\|\right)$$
 * 5. **Heading Feedforward Calculation**:
 *    $$\omega_{FF} = \frac{-\Delta x_{final} \cdot v_{shooter,y} + \Delta y_{final} \cdot v_{shooter,x}}{\|\Delta \mathbf{x}_{final}\|^2}$$
 *
 * ### Zero-GC Compliance:
 * Operates strictly using primitive calculations. Populates a pre-allocated [ShotResult] output container in-place.
 *
 * @param config Robot-specific physical geometry and ballistic calibration table [ShotConfig].
 * @see ShotConfig
 * @see ShotResult
 */
class ShotSetup(private val config: ShotConfig) {

    /**
     * Linearly interpolates projectile time-of-flight in seconds ($s$) for a given aim distance in meters ($m$).
     *
     * @param distance Straight-line aim distance to virtual target in meters ($m$).
     * @return Interpolated time-of-flight in seconds ($s$).
     */
    fun interpolateTof(distance: Double): Double {
        return interpolate(config.tofKeys, config.tofValues, distance)
    }

    /**
     * Linearly interpolates target flywheel velocity in Revolutions Per Minute ($RPM$) for a given aim distance in meters ($m$).
     *
     * @param distance Straight-line aim distance to virtual target in meters ($m$).
     * @return Interpolated flywheel target speed ($RPM$).
     */
    fun interpolateRpm(distance: Double): Double {
        return interpolate(config.shotKeys, config.shotRpm, distance)
    }

    /**
     * Linearly interpolates target cowl/hood position in mechanism rotations for a given aim distance in meters ($m$).
     *
     * @param distance Straight-line aim distance to virtual target in meters ($m$).
     * @return Interpolated cowl mechanism position in rotations.
     */
    fun interpolateCowlRotations(distance: Double): Double {
        return interpolate(config.shotKeys, config.shotCowlRotations, distance)
    }

    /** Compatibility alias for the formerly unit-ambiguous method name. */
    @Deprecated("Use the rotation-labeled API", ReplaceWith("interpolateCowlRotations(distance)"))
    fun interpolateCowl(distance: Double): Double {
        return interpolateCowlRotations(distance)
    }

    /**
     * Performs a latency-compensated iterative convergence calculation for Shoot-on-the-Move (SOTM).
     *
     * Computes virtual target coordinates, aim distance, heading orientation, flywheel RPM, cowl angle,
     * and rotational feedforward rate. Populates [result] in-place with zero heap allocations.
     *
     * @param robotPose Current estimated robot position and heading orientation on the field ($m, rad$).
     * @param fieldCentricSpeeds Current velocity vector of the chassis in field coordinates ($m/s, rad/s$).
     * @param target Field coordinates of the scoring target opening ($m$).
     * @param result Pre-allocated [ShotResult] container populated in-place.
     */
    fun calculate(
        robotPose: Pose2d,
        fieldCentricSpeeds: ChassisSpeeds,
        target: Translation2d,
        result: ShotResult
    ) {
        val dtDelay = config.delayCompensationSeconds

        // 1. Compute phase delay compensated chassis position and heading
        val compHeading = robotPose.heading.radians + fieldCentricSpeeds.omegaRadiansPerSecond * dtDelay
        val compX = robotPose.x + fieldCentricSpeeds.vxMetersPerSecond * dtDelay
        val compY = robotPose.y + fieldCentricSpeeds.vyMetersPerSecond * dtDelay

        // 2. Translate center to shooter offset based on heading rotation
        val cosH = cos(compHeading)
        val sinH = sin(compHeading)
        val rotOffsetX = config.shooterOffsetX * cosH - config.shooterOffsetY * sinH
        val rotOffsetY = config.shooterOffsetX * sinH + config.shooterOffsetY * cosH

        val shooterX = compX + rotOffsetX
        val shooterY = compY + rotOffsetY

        // 3. Field-relative shooter velocity vector (translation + rotational cross product)
        val shooterVx = fieldCentricSpeeds.vxMetersPerSecond - fieldCentricSpeeds.omegaRadiansPerSecond * rotOffsetY
        val shooterVy = fieldCentricSpeeds.vyMetersPerSecond + fieldCentricSpeeds.omegaRadiansPerSecond * rotOffsetX

        // 4. Iterative solver for lookahead distance (5 loops)
        var virtualTargetX = target.x
        var virtualTargetY = target.y
        var aimDistance: Double

        for (i in 0 until 5) {
            val dx = virtualTargetX - shooterX
            val dy = virtualTargetY - shooterY
            aimDistance = hypot(dx, dy)
            val tof = interpolateTof(aimDistance)
            val newVirtualX = target.x - shooterVx * tof
            val newVirtualY = target.y - shooterVy * tof
            virtualTargetX = 0.5 * virtualTargetX + 0.5 * newVirtualX
            virtualTargetY = 0.5 * virtualTargetY + 0.5 * newVirtualY
        }

        // 5. Final coordinates and aiming target heading calculations
        val dxFinal = virtualTargetX - shooterX
        val dyFinal = virtualTargetY - shooterY
        aimDistance = hypot(dxFinal, dyFinal)

        val aimAngle = atan2(dyFinal, dxFinal)

        // Rearward-facing shooter: robot's front is 180° from the aim direction
        val robotTargetHeading = if (config.shooterFacesRearward) aimAngle + PI else aimAngle

        val wrappedRobotHeading = wrapAngle(robotTargetHeading)

        // 6. Direct derivative for exact heading angular velocity feedforward
        val angularVelFF = if (aimDistance > 0.05) {
            (-dxFinal * shooterVy + dyFinal * shooterVx) / (aimDistance * aimDistance)
        } else {
            0.0
        }

        // 7. Map lookahead aimDistance to flywheel and cowl parameters
        val targetRpm = interpolateRpm(aimDistance)
        val targetCowlRotations = interpolateCowlRotations(aimDistance)

        // Write outputs
        result.virtualTargetX = virtualTargetX
        result.virtualTargetY = virtualTargetY
        result.aimAngleRad = aimAngle
        result.robotTargetHeadingRad = wrappedRobotHeading
        result.aimDistanceMeters = aimDistance
        result.targetFlywheelRpm = targetRpm
        result.targetCowlAngleRotations = targetCowlRotations
        result.angularVelocityFeedforwardRadPerSec = angularVelFF
    }

    companion object {
        /**
         * Generic piecewise-linear interpolation for sorted key/value arrays.
         * Zero-allocation: operates on primitive arrays with indexed access.
         *
         * @param keys Sorted ascending breakpoint array.
         * @param values Corresponding output values.
         * @param x The input value to interpolate.
         * @return The interpolated output value.
         */
        fun interpolate(keys: DoubleArray, values: DoubleArray, x: Double): Double {
            // NaN falls through every comparison below (all evaluate false), so handle it
            // explicitly with a safe default rather than silently returning values[last].
            if (x.isNaN()) return values[0]
            // Clamp at the LUT endpoints instead of extrapolating beyond them.
            if (x <= keys[0]) return values[0]
            if (x >= keys[keys.size - 1]) return values[values.size - 1]
            for (i in 0 until keys.size - 1) {
                if (x >= keys[i] && x <= keys[i + 1]) {
                    val diff = keys[i + 1] - keys[i]
                    if (diff <= 1e-9) return values[i]
                    val t = (x - keys[i]) / diff
                    return values[i] + t * (values[i + 1] - values[i])
                }
            }
            return values[values.size - 1]
        }
    }
}
