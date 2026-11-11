package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.wrapAngle
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/**
 * Configuration for the Pedro v3-inspired `Foresight` predictive stopping-pose lookahead.
 *
 * When [enabled], the path follower projects the robot's instantaneous velocity and angular rate
 * forward along its $SE(2)$ braking trajectory to estimate the physical stopping pose
 * $(x_{\text{stop}}, y_{\text{stop}}, \theta_{\text{stop}})$ under configured deceleration limits.
 *
 * @property enabled Whether predictive stopping-pose lookahead is active.
 * @property decelRateMps2 Translational braking deceleration rate in $m/s^2$ ($> 0$).
 * @property angularDecelRateRadPerSec2 Rotational braking deceleration rate in $rad/s^2$ ($> 0$).
 * @property scalar Dimensionless lookahead horizon multiplier ($> 0$, default `1.0`).
 */
data class ForesightConfig(
    val enabled: Boolean = false,
    val decelRateMps2: Double = 2.5,
    val angularDecelRateRadPerSec2: Double = Math.PI * 2.0,
    val scalar: Double = 1.0
) {
    val isValid: Boolean
        get() = decelRateMps2.isFinite() && decelRateMps2 > 0.0 &&
            angularDecelRateRadPerSec2.isFinite() && angularDecelRateRadPerSec2 > 0.0 &&
            scalar.isFinite() && scalar > 0.0

    companion object {
        /** Disabled configuration preserving legacy SAFE / BALANCED behavior. */
        val DISABLED = ForesightConfig(enabled = false)

        /**
         * Resolves the default [ForesightConfig] for a [TrajectoryPreset].
         * Enabled automatically for [TrajectoryPreset.FAST] and [TrajectoryPreset.ADAPTIVE];
         * disabled for [TrajectoryPreset.SAFE] and [TrajectoryPreset.BALANCED].
         */
        fun forPreset(
            preset: TrajectoryPreset,
            decelRateMps2: Double = 2.5,
            angularDecelRateRadPerSec2: Double = Math.PI * 2.0,
            scalar: Double = 1.0
        ): ForesightConfig = ForesightConfig(
            enabled = preset == TrajectoryPreset.FAST || preset == TrajectoryPreset.ADAPTIVE,
            decelRateMps2 = decelRateMps2,
            angularDecelRateRadPerSec2 = angularDecelRateRadPerSec2,
            scalar = scalar
        )
    }
}

/**
 * Zero-GC $SE(2)$ predictive stopping-pose estimator ("Foresight").
 *
 * Computes where the robot would come to a rest if maximum configured braking deceleration
 * were applied starting at the current control tick, accounting for coupled translational
 * and rotational deceleration along an $SE(2)$ arc.
 */
class ForesightLookaheadEstimator(
    var config: ForesightConfig = ForesightConfig.DISABLED
) {
    /** Predicted field X coordinate at rest ($m$). */
    var predictedStopX: Double = 0.0
        private set

    /** Predicted field Y coordinate at rest ($m$). */
    var predictedStopY: Double = 0.0
        private set

    /** Predicted field heading at rest ($rad$, CCW-positive). */
    var predictedStopHeadingRad: Double = 0.0
        private set

    /** Predicted translational stopping distance ($m$). */
    var stoppingDistanceMeters: Double = 0.0
        private set

    /** Whether the last [updateFieldRelative] or [updateRobotRelative] call succeeded with valid inputs. */
    var lastEstimateValid: Boolean = false
        private set

    /**
     * Updates the predicted stopping pose from field-relative velocity $(v_x, v_y)$ and angular rate $\omega$.
     * Executes in $O(1)$ time with zero heap allocations.
     */
    fun updateFieldRelative(
        currentX: Double,
        currentY: Double,
        currentHeadingRad: Double,
        fieldVxMps: Double,
        fieldVyMps: Double,
        omegaRadPerSec: Double
    ): Boolean {
        lastEstimateValid = false
        if (!currentX.isFinite() || !currentY.isFinite() || !currentHeadingRad.isFinite() ||
            !fieldVxMps.isFinite() || !fieldVyMps.isFinite() || !omegaRadPerSec.isFinite() ||
            !config.isValid
        ) {
            predictedStopX = if (currentX.isFinite()) currentX else 0.0
            predictedStopY = if (currentY.isFinite()) currentY else 0.0
            predictedStopHeadingRad = if (currentHeadingRad.isFinite()) wrapAngle(currentHeadingRad) else 0.0
            stoppingDistanceMeters = 0.0
            return false
        }

        val wrappedHeading = wrapAngle(currentHeadingRad)
        if (!config.enabled) {
            predictedStopX = currentX
            predictedStopY = currentY
            predictedStopHeadingRad = wrappedHeading
            stoppingDistanceMeters = 0.0
            lastEstimateValid = true
            return true
        }

        val speed = hypot(fieldVxMps, fieldVyMps)
        val stopDist = config.scalar * (speed * speed) / (2.0 * config.decelRateMps2)
        val deltaThetaStop = config.scalar * (omegaRadPerSec * abs(omegaRadPerSec)) / (2.0 * config.angularDecelRateRadPerSec2)
        if (!stopDist.isFinite() || !deltaThetaStop.isFinite()) {
            predictedStopX = currentX
            predictedStopY = currentY
            predictedStopHeadingRad = wrappedHeading
            stoppingDistanceMeters = 0.0
            return false
        }

        val stopX: Double
        val stopY: Double
        if (speed > 1e-6) {
            val velocityAngle = atan2(fieldVyMps, fieldVxMps)
            val tTrans = speed / config.decelRateMps2
            val tRot = abs(omegaRadPerSec) / config.angularDecelRateRadPerSec2
            val tArc = min(tTrans, tRot)
            val arcDeltaTheta = config.scalar * (
                omegaRadPerSec * tArc - 0.5 * sign(omegaRadPerSec) * config.angularDecelRateRadPerSec2 * tArc * tArc
            )
            // Integrate heading sweep only over min(t_v, t_omega) so fast spin during
            // near-zero translation cannot over-bend the translational braking chord.
            val effectiveArcAngle = velocityAngle + arcDeltaTheta / 3.0
            val halfTurn = abs(arcDeltaTheta) * 0.5
            val chordScale = if (halfTurn > 1e-4) sin(halfTurn) / halfTurn else 1.0
            val chordLength = stopDist * chordScale
            stopX = currentX + chordLength * cos(effectiveArcAngle)
            stopY = currentY + chordLength * sin(effectiveArcAngle)
        } else {
            stopX = currentX
            stopY = currentY
        }

        val stopHeading = wrapAngle(wrappedHeading + deltaThetaStop)
        if (!stopX.isFinite() || !stopY.isFinite() || !stopHeading.isFinite()) {
            predictedStopX = currentX
            predictedStopY = currentY
            predictedStopHeadingRad = wrappedHeading
            stoppingDistanceMeters = 0.0
            return false
        }

        predictedStopX = stopX
        predictedStopY = stopY
        predictedStopHeadingRad = stopHeading
        stoppingDistanceMeters = stopDist
        lastEstimateValid = true
        return true
    }

    /**
     * Updates the predicted stopping pose from robot-relative velocity $(v_{rx}, v_{ry})$ and angular rate $\omega$.
     * Executes in $O(1)$ time with zero heap allocations.
     */
    fun updateRobotRelative(
        currentX: Double,
        currentY: Double,
        currentHeadingRad: Double,
        robotVxMps: Double,
        robotVyMps: Double,
        omegaRadPerSec: Double
    ): Boolean {
        if (!currentHeadingRad.isFinite() || !robotVxMps.isFinite() || !robotVyMps.isFinite()) {
            return updateFieldRelative(currentX, currentY, currentHeadingRad, Double.NaN, Double.NaN, omegaRadPerSec)
        }
        val cosH = cos(currentHeadingRad)
        val sinH = sin(currentHeadingRad)
        val fieldVx = robotVxMps * cosH - robotVyMps * sinH
        val fieldVy = robotVxMps * sinH + robotVyMps * cosH
        return updateFieldRelative(currentX, currentY, currentHeadingRad, fieldVx, fieldVy, omegaRadPerSec)
    }

    /**
     * Projects the predicted stopping pose onto [path] around [currentArcDistance] to obtain the
     * Foresight lookahead arc distance.
     */
    fun projectStoppingArcLength(path: Path, currentArcDistance: Double): Double {
        if (!lastEstimateValid || path.points.isEmpty()) return currentArcDistance
        val windowAhead = max(1.5, stoppingDistanceMeters + 0.5)
        return path.findClosestDistance(
            x = predictedStopX,
            y = predictedStopY,
            minDistance = max(0.0, currentArcDistance - 0.5),
            maxDistance = currentArcDistance + windowAhead
        )
    }

    /**
     * Evaluates whether the predicted stopping pose has entered the [handoffRadiusMeters] neighborhood
     * of [endPose] (or has reached/crossed the end of the path), enabling smooth momentum-preserving
     * `pathSkip` segment handoffs.
     */
    fun shouldTriggerPathSkip(
        currentX: Double,
        currentY: Double,
        endPose: Pose2d,
        handoffRadiusMeters: Double
    ): Boolean {
        if (!lastEstimateValid || !handoffRadiusMeters.isFinite() || handoffRadiusMeters <= 0.0) return false
        val stopDistToEnd = hypot(predictedStopX - endPose.x, predictedStopY - endPose.y)
        if (stopDistToEnd <= handoffRadiusMeters) return true
        val currentDistToEnd = hypot(currentX - endPose.x, currentY - endPose.y)
        return stoppingDistanceMeters > 1e-3 && currentDistToEnd <= stoppingDistanceMeters + handoffRadiusMeters * 0.5
    }
}
