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
    /** True only after a finite, self-consistent solution has been written. */
    var isValid: Boolean = false
    var virtualTargetX: Double = 0.0
    var virtualTargetY: Double = 0.0
    var aimAngleRad: Double = 0.0
    var robotTargetHeadingRad: Double = 0.0
    var aimDistanceMeters: Double = 0.0
    var targetFlywheelRpm: Double = 0.0
    var targetCowlAngleRotations: Double = 0.0
    var angularVelocityFeedforwardRadPerSec: Double = 0.0

    /** Clears prior targets so failed calculations cannot retain an earlier valid shot. */
    fun clear() {
        isValid = false
        virtualTargetX = 0.0
        virtualTargetY = 0.0
        aimAngleRad = 0.0
        robotTargetHeadingRad = 0.0
        aimDistanceMeters = 0.0
        targetFlywheelRpm = 0.0
        targetCowlAngleRotations = 0.0
        angularVelocityFeedforwardRadPerSec = 0.0
    }
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
 * @property shotCowlRotations Cowl mechanism rotations corresponding to each distance breakpoint.
 * @property delayCompensationSeconds Phase delay compensation for system latency ($s$).
 * @property shooterFacesRearward `true` if shooter points out the rear of the robot (180° offset from front).
 */
class ShotConfig(
    val shooterOffsetX: Double,
    val shooterOffsetY: Double,
    tofKeys: DoubleArray,
    tofValues: DoubleArray,
    shotKeys: DoubleArray,
    shotRpm: DoubleArray,
    shotCowlRotations: DoubleArray,
    val delayCompensationSeconds: Double = 0.05,
    val shooterFacesRearward: Boolean = true
) {
    internal val tofKeys = tofKeys.copyOf()
    internal val tofValues = tofValues.copyOf()
    internal val shotKeys = shotKeys.copyOf()
    internal val shotRpm = shotRpm.copyOf()
    internal val shotCowlRotations = shotCowlRotations.copyOf()

    init {
        require(this.tofKeys.isNotEmpty()) { "tofKeys and tofValues must not be empty" }
        require(this.shotKeys.isNotEmpty()) { "shot lookup tables must not be empty" }
        require(this.tofKeys.size == this.tofValues.size) { "tofKeys and tofValues must have the same length" }
        require(this.shotKeys.size == this.shotRpm.size) { "shotKeys and shotRpm must have the same length" }
        require(this.shotKeys.size == this.shotCowlRotations.size) {
            "shotKeys and shotCowlRotations must have the same length"
        }
        require(shooterOffsetX.isFinite() && shooterOffsetY.isFinite()) { "Shooter offsets must be finite" }
        require(delayCompensationSeconds.isFinite() && delayCompensationSeconds >= 0.0) {
            "Delay compensation must be finite and non-negative"
        }
        requireStrictlyIncreasingFinite(this.tofKeys, "tofKeys")
        requireStrictlyIncreasingFinite(this.shotKeys, "shotKeys")
        requireFinite(this.tofValues, "tofValues")
        requireFinite(this.shotRpm, "shotRpm")
        requireFinite(this.shotCowlRotations, "shotCowlRotations")
        require(this.tofKeys.all { it >= 0.0 } && this.shotKeys.all { it >= 0.0 }) {
            "Distance breakpoints must be non-negative"
        }
        require(this.tofValues.all { it >= 0.0 }) { "Flight times must be non-negative" }
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
 * Latency-compensated shoot-on-the-move solver for a piecewise-linear flight-time model.
 *
 * Field velocity and chassis angular rate are held constant through the configured delay. Shooter
 * position and tangential velocity include the rotated chassis offset. Each flight-time segment
 * and both clamped tails are solved for an intercept; the earliest nonnegative flight time wins.
 * Results are checked against the original table within a relative 1e-9 time tolerance.
 *
 * Heading feedforward differentiates the implicit intercept, including distance-dependent flight
 * time and centripetal acceleration of the offset shooter. At table knots the selected segment's
 * one-sided slope is used; endpoint-clamped slopes are zero. The rate is suppressed inside 5 cm
 * where aiming direction is poorly conditioned. A singular derivative or invalid input invalidates
 * the entire result. Physical drag, chassis acceleration and target motion are outside this model.
 *
 * Calculation uses primitive local variables and caller-owned output storage without loop allocations.
 */
class ShotSetup(private val config: ShotConfig) {

    /**
     * Linearly interpolates projectile time-of-flight in seconds ($s$) for a given aim distance in meters ($m$).
     *
     * @param distance Straight-line aim distance to virtual target in meters ($m$).
     * @return Interpolated time-of-flight in seconds ($s$).
     */
    fun interpolateTof(distance: Double): Double {
        return interpolateValidated(config.tofKeys, config.tofValues, distance)
    }

    /**
     * Linearly interpolates target flywheel velocity in Revolutions Per Minute ($RPM$) for a given aim distance in meters ($m$).
     *
     * @param distance Straight-line aim distance to virtual target in meters ($m$).
     * @return Interpolated flywheel target speed ($RPM$).
     */
    fun interpolateRpm(distance: Double): Double {
        return interpolateValidated(config.shotKeys, config.shotRpm, distance)
    }

    /**
     * Linearly interpolates target cowl/hood position in mechanism rotations for a given aim distance in meters ($m$).
     *
     * @param distance Straight-line aim distance to virtual target in meters ($m$).
     * @return Interpolated cowl mechanism position in rotations.
     */
    fun interpolateCowlRotations(distance: Double): Double {
        return interpolateValidated(config.shotKeys, config.shotCowlRotations, distance)
    }

    /**
     * Solves a latency-compensated intercept for Shoot-on-the-Move (SOTM).
     *
     * Computes virtual target coordinates, aim distance, heading orientation, flywheel RPM, cowl angle,
     * and rotational feedforward rate. Populates [result] in-place with zero heap allocations.
     * Invalid or nonrepresentable solutions clear [result]; callers must check [ShotResult.isValid].
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
        result.clear()
        if (!robotPose.x.isFinite() || !robotPose.y.isFinite() || !robotPose.heading.radians.isFinite() ||
            !fieldCentricSpeeds.vxMetersPerSecond.isFinite() || !fieldCentricSpeeds.vyMetersPerSecond.isFinite() ||
            !fieldCentricSpeeds.omegaRadiansPerSecond.isFinite() || !target.x.isFinite() || !target.y.isFinite()) return
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

        // 4. Solve the piecewise-linear flight table, including endpoint-clamped intervals.
        val tof = ShotInterceptSolver.flightTime(target.x - shooterX, target.y - shooterY,
            shooterVx, shooterVy, config.tofKeys, config.tofValues)
        if (!tof.isFinite()) return
        val virtualTargetX = target.x - shooterVx * tof
        val virtualTargetY = target.y - shooterVy * tof

        // 5. Final coordinates and aiming target heading calculations
        val dxFinal = virtualTargetX - shooterX
        val dyFinal = virtualTargetY - shooterY
        val aimDistance = hypot(dxFinal, dyFinal)
        if (!aimDistance.isFinite() || aimDistance == 0.0 || !virtualTargetX.isFinite() || !virtualTargetY.isFinite()) return
        if (abs(interpolateTof(aimDistance) - tof) > 1e-9 * max(1.0, tof)) return

        val aimAngle = atan2(dyFinal, dxFinal)

        // Rearward-facing shooter: robot's front is 180° from the aim direction
        val robotTargetHeading = if (config.shooterFacesRearward) aimAngle + PI else aimAngle

        val wrappedRobotHeading = wrapAngle(robotTargetHeading)

        // 6. Differentiate q = target - shooter - velocity*TOF(|q|).
        // Constant chassis field velocity/omega still gives centripetal acceleration at an offset shooter.
        val angularVelFF = if (aimDistance > 0.05) {
            val ux = dxFinal / aimDistance
            val uy = dyFinal / aimDistance
            val slope = tofSlope(aimDistance)
            val omega = fieldCentricSpeeds.omegaRadiansPerSecond
            val wx = -shooterVx + omega * omega * rotOffsetX * tof
            val wy = -shooterVy + omega * omega * rotOffsetY * tof
            val denominator = 1.0 + slope * (ux * shooterVx + uy * shooterVy)
            if (!denominator.isFinite() || abs(denominator) < 1e-12) return
            val distanceRate = (ux * wx + uy * wy) / denominator
            (ux * (wy - shooterVy * slope * distanceRate) -
                uy * (wx - shooterVx * slope * distanceRate)) / aimDistance
        } else {
            0.0
        }

        // 7. Map lookahead aimDistance to flywheel and cowl parameters
        val shotIndex = segmentIndex(config.shotKeys, aimDistance)
        val nextShotIndex = min(shotIndex + 1, config.shotKeys.lastIndex)
        val shotFraction = fraction(config.shotKeys, shotIndex, nextShotIndex, aimDistance)
        val targetRpm = (1.0 - shotFraction) * config.shotRpm[shotIndex] + shotFraction * config.shotRpm[nextShotIndex]
        val targetCowlRotations = (1.0 - shotFraction) * config.shotCowlRotations[shotIndex] +
            shotFraction * config.shotCowlRotations[nextShotIndex]
        if (!targetRpm.isFinite() || !targetCowlRotations.isFinite() || !angularVelFF.isFinite() ||
            !aimAngle.isFinite() || !wrappedRobotHeading.isFinite()) return

        // Write outputs
        result.virtualTargetX = virtualTargetX
        result.virtualTargetY = virtualTargetY
        result.aimAngleRad = aimAngle
        result.robotTargetHeadingRad = wrappedRobotHeading
        result.aimDistanceMeters = aimDistance
        result.targetFlywheelRpm = targetRpm
        result.targetCowlAngleRotations = targetCowlRotations
        result.angularVelocityFeedforwardRadPerSec = angularVelFF
        result.isValid = true
    }

    private fun tofSlope(distance: Double): Double {
        val keys = config.tofKeys
        if (distance <= keys[0] || distance >= keys[keys.lastIndex]) return 0.0
        val index = segmentIndex(keys, distance)
        return (config.tofValues[index + 1] - config.tofValues[index]) / (keys[index + 1] - keys[index])
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
            require(keys.isNotEmpty() && keys.size == values.size) { "Interpolation arrays must be nonempty and equal sized" }
            for (i in keys.indices) {
                require(keys[i].isFinite() && values[i].isFinite()) { "Interpolation data must be finite" }
                require(i == 0 || keys[i] > keys[i - 1]) { "Interpolation keys must increase strictly" }
            }
            return interpolateValidated(keys, values, x)
        }

        private fun interpolateValidated(keys: DoubleArray, values: DoubleArray, x: Double): Double {
            if (x.isNaN()) return Double.NaN
            // Clamp at the LUT endpoints instead of extrapolating beyond them.
            if (x <= keys[0]) return values[0]
            if (x >= keys[keys.size - 1]) return values[values.size - 1]
            val i = segmentIndex(keys, x)
            val t = fraction(keys, i, i + 1, x)
            return (1.0 - t) * values[i] + t * values[i + 1]
        }

        private fun fraction(keys: DoubleArray, lo: Int, hi: Int, x: Double): Double {
            if (lo == hi || x <= keys[lo]) return 0.0
            if (x >= keys[hi]) return 1.0
            val diff = keys[hi] - keys[lo]
            return if (diff.isFinite()) (x - keys[lo]) / diff else
                (x * 0.5 - keys[lo] * 0.5) / (keys[hi] * 0.5 - keys[lo] * 0.5)
        }

        private fun segmentIndex(keys: DoubleArray, x: Double): Int {
            var lo = 0
            var hi = keys.lastIndex
            while (hi - lo > 1) {
                val mid = lo + (hi - lo) / 2
                if (x < keys[mid]) hi = mid else lo = mid
            }
            return lo
        }
    }
}
