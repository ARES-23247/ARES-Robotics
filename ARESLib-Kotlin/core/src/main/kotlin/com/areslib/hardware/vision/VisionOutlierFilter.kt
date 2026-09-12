package com.areslib.hardware.vision

import com.areslib.math.coordinate.CoordinateTransformers
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Pose3d
import com.areslib.math.wrapAngle
import com.areslib.state.VisionMeasurement
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Filter configuration thresholds for AprilTag measurements.
 */
data class VisionFilterConfig(
    val maxDistanceMeters: Double = 6.0,
    val maxAmbiguity: Double = 0.2,
    val maxRotationDeviationRad: Double = Math.toRadians(30.0),
    val minFieldX: Double = -CoordinateTransformers.FTC_FIELD_SIZE / 2.0,
    val maxFieldX: Double = CoordinateTransformers.FTC_FIELD_SIZE / 2.0,
    val minFieldY: Double = -CoordinateTransformers.FTC_FIELD_SIZE / 2.0,
    val maxFieldY: Double = CoordinateTransformers.FTC_FIELD_SIZE / 2.0,
    val minFieldZ: Double = -0.2,
    val maxFieldZ: Double = 1.0,
    val maxAbsoluteRollRad: Double = Math.toRadians(30.0),
    val maxAbsolutePitchRad: Double = Math.toRadians(30.0),
    /** Empty accepts every camera-field-map tag; otherwise the representative tag must be listed. */
    val allowedTagIds: Set<Int> = emptySet(),
    val maxAngularVelocityRadPerSec: Double = 2.0,
    val maxAccelerationG: Double = 2.5,
    val mahalanobisThreshold: Double = 18.0,
    /** Chi-square NIS threshold for translation-only observations such as MegaTag2. */
    val mahalanobisThreshold2D: Double = 9.210340371976184,
    /** Full robot length along its local X axis. Zero preserves center-point-only filtering. */
    val robotLengthMeters: Double = 0.45,
    /** Full robot width along its local Y axis. Zero preserves center-point-only filtering. */
    val robotWidthMeters: Double = 0.45,
    /** Maximum distance a footprint corner may cross a field boundary to absorb vision noise. */
    val fieldBoundsToleranceMeters: Double = 0.0254
) {
    // All scalar thresholds are immutable; validate once per constructed/copy configuration.
    internal val isValidConfiguration: Boolean =
        maxDistanceMeters.isFinite() && maxDistanceMeters >= 0.0 &&
            maxAmbiguity.isFinite() && maxAmbiguity >= 0.0 &&
            maxRotationDeviationRad.isFinite() && maxRotationDeviationRad >= 0.0 &&
            minFieldX.isFinite() && maxFieldX.isFinite() && minFieldX <= maxFieldX &&
            minFieldY.isFinite() && maxFieldY.isFinite() && minFieldY <= maxFieldY &&
            minFieldZ.isFinite() && maxFieldZ.isFinite() && minFieldZ <= maxFieldZ &&
            maxAbsoluteRollRad.isFinite() && maxAbsoluteRollRad >= 0.0 &&
            maxAbsolutePitchRad.isFinite() && maxAbsolutePitchRad >= 0.0 &&
            robotLengthMeters.isFinite() && robotLengthMeters >= 0.0 &&
            robotWidthMeters.isFinite() && robotWidthMeters >= 0.0 &&
            fieldBoundsToleranceMeters.isFinite() && fieldBoundsToleranceMeters >= 0.0 &&
            maxAngularVelocityRadPerSec.isFinite() && maxAngularVelocityRadPerSec >= 0.0 &&
            maxAccelerationG.isFinite() && maxAccelerationG >= 0.0 &&
            mahalanobisThreshold.isFinite() && mahalanobisThreshold > 0.0 &&
            mahalanobisThreshold2D.isFinite() && mahalanobisThreshold2D > 0.0

    companion object {
        @JvmStatic
        fun ftcDefaults() = VisionFilterConfig()

        @JvmStatic
        fun frcDefaults() = VisionFilterConfig(
            maxDistanceMeters = 10.0,
            maxAmbiguity = 0.15,
            maxRotationDeviationRad = Math.toRadians(30.0),
            minFieldX = -1.0,
            maxFieldX = 18.0,
            minFieldY = -1.0,
            maxFieldY = 9.0,
            minFieldZ = -0.2,
            maxFieldZ = 3.0,
            // The camera field map owns season tag identities; callers may supply an explicit allowlist.
            robotLengthMeters = 0.0,
            robotWidthMeters = 0.0,
            fieldBoundsToleranceMeters = 0.0,
            maxAngularVelocityRadPerSec = 6.0,
            maxAccelerationG = 5.0
        )
    }
}

/**
 * An outlier rejection filter that discards noisy, distant, or heading-deviating AprilTag vision measurements.
 */
class VisionOutlierFilter(val config: VisionFilterConfig = VisionFilterConfig()) {

    /**
     * Returns true if the vision measurement is physically valid and falls within all threshold constraints.
     */
    fun isValid(
        measurement: VisionMeasurement,
        robotHeadingRad: Double,
        robotPose: Pose2d,
        angularVelocityRadPerSec: Double = 0.0,
        linearAccelXG: Double = 0.0,
        linearAccelYG: Double = 0.0,
        linearAccelZG: Double = 1.0
    ): Boolean {
        return isValid(
            config = config,
            measurement = measurement,
            robotHeadingRad = robotHeadingRad,
            robotPose = robotPose,
            angularVelocityRadPerSec = angularVelocityRadPerSec,
            linearAccelXG = linearAccelXG,
            linearAccelYG = linearAccelYG,
            linearAccelZG = linearAccelZG
        )
    }

    companion object {
        fun isValid(
            config: VisionFilterConfig,
            measurement: VisionMeasurement,
            robotHeadingRad: Double,
            robotPose: Pose2d,
            angularVelocityRadPerSec: Double = 0.0,
            linearAccelXG: Double = 0.0,
            linearAccelYG: Double = 0.0,
            linearAccelZG: Double = 1.0
        ): Boolean = isValid(
            config = config,
            measurement = measurement,
            robotHeadingRad = robotHeadingRad,
            robotPoseX = robotPose.x,
            robotPoseY = robotPose.y,
            angularVelocityRadPerSec = angularVelocityRadPerSec,
            linearAccelXG = linearAccelXG,
            linearAccelYG = linearAccelYG,
            linearAccelZG = linearAccelZG
        )

        /** Primitive overload for zero-allocation historical-pose gating. */
        fun isValid(
            config: VisionFilterConfig,
            measurement: VisionMeasurement,
            robotHeadingRad: Double,
            robotPoseX: Double,
            robotPoseY: Double,
            angularVelocityRadPerSec: Double = 0.0,
            linearAccelXG: Double = 0.0,
            linearAccelYG: Double = 0.0,
            linearAccelZG: Double = 1.0
        ): Boolean {
            if (!config.isValidConfiguration) return false
            val pose = measurement.targetPose
            if (!hasValidVisionObservation(measurement) ||
                !robotPoseX.isFinite() || !robotPoseY.isFinite() || !robotHeadingRad.isFinite() ||
                !angularVelocityRadPerSec.isFinite() || !linearAccelXG.isFinite() ||
                !linearAccelYG.isFinite() || !linearAccelZG.isFinite()) return false
            val roll = pose.rotation.x
            val pitch = pose.rotation.y
            val yaw = pose.rotation.z

            // 1. Check available solve ambiguity.
            if (measurement.ambiguityAvailable && measurement.ambiguity > config.maxAmbiguity) {
                return false
            }
            if (config.allowedTagIds.isNotEmpty() && measurement.tagId !in config.allowedTagIds) return false

            // Reject camera-reported impossible geometry without inventing a universal
            // target-area threshold (which is lens/exposure/pipeline dependent).
            if (measurement.averageTagDistanceMeters >= 0.0 &&
                measurement.averageTagDistanceMeters > config.maxDistanceMeters) {
                return false
            }

            // 2. Check 3D spatial boundaries using the rotated robot footprint.
            val tagPose3d = pose
            if (!poseWithinFieldBounds(config, tagPose3d, roll, pitch, yaw)) {
                return false
            }

            // 3. Check Distance
            val dx = tagPose3d.x - robotPoseX
            val dy = tagPose3d.y - robotPoseY
            val distance = stableNorm(dx, dy)

            if (distance > config.maxDistanceMeters) {
                return false
            }

            // 4. Check Yaw rotation alignment relative to robot gyro heading
            val headingDiff = wrapAngle(yaw - wrapAngle(robotHeadingRad))

            if (kotlin.math.abs(headingDiff) > config.maxRotationDeviationRad) {
                return false
            }

            // 5. Check Angular Velocity Lockout (Motion Blur guard)
            if (kotlin.math.abs(angularVelocityRadPerSec) > config.maxAngularVelocityRadPerSec) {
                return false
            }

            // 6. Check High-G Shock Lockout (Collision guard)
            val dynamicZ = if (linearAccelZG == 0.0) 0.0 else linearAccelZG - 1.0
            val shockMagnitude = stableNorm(linearAccelXG, linearAccelYG, dynamicZ)
            if (shockMagnitude > config.maxAccelerationG) {
                return false
            }

            return true
        }

        /**
         * Checks an axis-aligned field against all four corners of a rotated rectangular robot.
         * The trigonometric extents are algebraically equivalent to transforming each corner,
         * while avoiding geometry allocations in the robot loop.
         */
        fun isPoseWithinFieldBounds(config: VisionFilterConfig, pose: Pose3d): Boolean {
            if (!config.isValidConfiguration || !hasFiniteUnitVisionPose(pose)) return false
            return poseWithinFieldBounds(config, pose, pose.rotation.x, pose.rotation.y, pose.rotation.z)
        }

        private fun poseWithinFieldBounds(
            config: VisionFilterConfig, pose: Pose3d, roll: Double, pitch: Double, heading: Double
        ): Boolean {
            if (pose.z < config.minFieldZ || pose.z > config.maxFieldZ) return false
            if (abs(roll) > config.maxAbsoluteRollRad ||
                abs(pitch) > config.maxAbsolutePitchRad) return false

            val absCos = kotlin.math.abs(kotlin.math.cos(heading))
            val absSin = kotlin.math.abs(kotlin.math.sin(heading))
            val halfLength = config.robotLengthMeters / 2.0
            val halfWidth = config.robotWidthMeters / 2.0
            val rawXExtent = absCos * halfLength + absSin * halfWidth
            val rawYExtent = absSin * halfLength + absCos * halfWidth
            // A positive footprint must not vanish when halving the smallest represented dimension.
            val xExtent = if (rawXExtent == 0.0 &&
                ((absCos > 0.0 && config.robotLengthMeters > 0.0) || (absSin > 0.0 && config.robotWidthMeters > 0.0))) Double.MIN_VALUE else rawXExtent
            val yExtent = if (rawYExtent == 0.0 &&
                ((absSin > 0.0 && config.robotLengthMeters > 0.0) || (absCos > 0.0 && config.robotWidthMeters > 0.0))) Double.MIN_VALUE else rawYExtent
            val tolerance = config.fieldBoundsToleranceMeters

            return lowerEdgeInside(pose.x, xExtent, config.minFieldX, tolerance) &&
                upperEdgeInside(pose.x, xExtent, config.maxFieldX, tolerance) &&
                lowerEdgeInside(pose.y, yExtent, config.minFieldY, tolerance) &&
                upperEdgeInside(pose.y, yExtent, config.maxFieldY, tolerance)
        }

        private fun lowerEdgeInside(center: Double, extent: Double, bound: Double, tolerance: Double): Boolean {
            val corner = center - extent
            val boundary = bound - tolerance
            return if (corner.isFinite() && boundary.isFinite()) corner >= boundary
            else center * 0.5 - extent * 0.5 >= bound * 0.5 - tolerance * 0.5
        }

        private fun upperEdgeInside(center: Double, extent: Double, bound: Double, tolerance: Double): Boolean {
            val corner = center + extent
            val boundary = bound + tolerance
            return if (corner.isFinite() && boundary.isFinite()) corner <= boundary
            else center * 0.5 + extent * 0.5 <= bound * 0.5 + tolerance * 0.5
        }

        private fun stableNorm(x: Double, y: Double): Double {
            val squared = x * x + y * y
            return if (squared >= java.lang.Double.MIN_NORMAL && squared.isFinite()) sqrt(squared)
            else hypot(x, y)
        }

        private fun stableNorm(x: Double, y: Double, z: Double): Double {
            val squared = x * x + y * y + z * z
            return if (squared >= java.lang.Double.MIN_NORMAL && squared.isFinite()) sqrt(squared)
            else hypot(hypot(x, y), z)
        }
    }
}

/** Cheap configuration-independent validation before correlation can hide a usable observation. */
internal fun hasValidVisionObservation(measurement: VisionMeasurement): Boolean =
    hasFiniteUnitVisionPose(measurement.targetPose) && measurement.tagCount > 0 &&
        (!measurement.ambiguityAvailable || (measurement.ambiguity.isFinite() && measurement.ambiguity >= 0.0)) &&
        measurement.tagSpanMeters.isFinite() && measurement.averageTagDistanceMeters.isFinite() &&
        measurement.averageTagAreaPercent.isFinite() && measurement.latencyMs.isFinite() && measurement.latencyMs >= 0.0

private fun hasFiniteUnitVisionPose(pose: Pose3d): Boolean {
    if (!pose.x.isFinite() || !pose.y.isFinite() || !pose.z.isFinite()) return false
    val q = pose.rotation.q
    if (!q.w.isFinite() || !q.x.isFinite() || !q.y.isFinite() || !q.z.isFinite()) return false
    val normSquared = q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z
    // Permit harmless accumulated floating-point roundoff, not a zero/scaled quaternion fallback.
    return abs(normSquared - 1.0) <= 1e-6
}
