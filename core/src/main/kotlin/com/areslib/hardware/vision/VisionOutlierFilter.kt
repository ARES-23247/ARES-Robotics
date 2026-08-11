package com.areslib.hardware.vision

import com.areslib.math.coordinate.CoordinateTransformers
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Pose3d
import com.areslib.math.wrapAngle
import com.areslib.state.VisionMeasurement

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
            if (!config.isValid()) return false
            val pose = measurement.targetPose
            if ((measurement.ambiguityAvailable && !measurement.ambiguity.isFinite()) ||
                !pose.x.isFinite() || !pose.y.isFinite() || !pose.z.isFinite() ||
                !pose.rotation.x.isFinite() || !pose.rotation.y.isFinite() || !pose.rotation.z.isFinite() ||
                !robotPoseX.isFinite() || !robotPoseY.isFinite() || !robotHeadingRad.isFinite() ||
                !angularVelocityRadPerSec.isFinite() || !linearAccelXG.isFinite() ||
                !linearAccelYG.isFinite() || !linearAccelZG.isFinite()) {
                return false
            }

            // 1. Check Ambiguity (if >= 0.0)
            if (measurement.ambiguityAvailable && measurement.ambiguity > config.maxAmbiguity) {
                return false
            }

            // Reject camera-reported impossible geometry without inventing a universal
            // target-area threshold (which is lens/exposure/pipeline dependent).
            if (measurement.averageTagDistanceMeters >= 0.0 &&
                measurement.averageTagDistanceMeters > config.maxDistanceMeters) {
                return false
            }

            // 2. Check 3D spatial boundaries using the rotated robot footprint.
            val tagPose3d = pose
            if (!isPoseWithinFieldBounds(config, tagPose3d)) {
                return false
            }

            // 3. Check Distance
            val tagPose2d = tagPose3d.toPose2d()
            val dx = tagPose2d.x - robotPoseX
            val dy = tagPose2d.y - robotPoseY
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)

            if (distance > config.maxDistanceMeters) {
                return false
            }

            // 4. Check Yaw rotation alignment relative to robot gyro heading
            val tagYaw = tagPose3d.rotation.z
            val headingDiff = wrapAngle(tagYaw - robotHeadingRad)

            if (kotlin.math.abs(headingDiff) > config.maxRotationDeviationRad) {
                return false
            }

            // 5. Check Angular Velocity Lockout (Motion Blur guard)
            if (kotlin.math.abs(angularVelocityRadPerSec) > config.maxAngularVelocityRadPerSec) {
                return false
            }

            // 6. Check High-G Shock Lockout (Collision guard)
            val dynamicZ = if (linearAccelZG == 0.0) 0.0 else linearAccelZG - 1.0
            val shockMagnitude = kotlin.math.sqrt(
                linearAccelXG * linearAccelXG +
                linearAccelYG * linearAccelYG +
                dynamicZ * dynamicZ
            )
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
            if (!config.isValid() ||
                !pose.x.isFinite() || !pose.y.isFinite() || !pose.z.isFinite() ||
                !pose.rotation.x.isFinite() || !pose.rotation.y.isFinite() ||
                !pose.rotation.z.isFinite()) {
                return false
            }

            if (pose.z < config.minFieldZ || pose.z > config.maxFieldZ) return false

            val heading = pose.rotation.z
            val absCos = kotlin.math.abs(kotlin.math.cos(heading))
            val absSin = kotlin.math.abs(kotlin.math.sin(heading))
            val halfLength = config.robotLengthMeters / 2.0
            val halfWidth = config.robotWidthMeters / 2.0
            val xExtent = absCos * halfLength + absSin * halfWidth
            val yExtent = absSin * halfLength + absCos * halfWidth
            val tolerance = config.fieldBoundsToleranceMeters

            return pose.x - xExtent >= config.minFieldX - tolerance &&
                pose.x + xExtent <= config.maxFieldX + tolerance &&
                pose.y - yExtent >= config.minFieldY - tolerance &&
                pose.y + yExtent <= config.maxFieldY + tolerance
        }

        private fun VisionFilterConfig.isValid(): Boolean =
            maxDistanceMeters.isFinite() && maxDistanceMeters >= 0.0 &&
                maxAmbiguity.isFinite() && maxAmbiguity >= 0.0 &&
                maxRotationDeviationRad.isFinite() && maxRotationDeviationRad >= 0.0 &&
                minFieldX.isFinite() && maxFieldX.isFinite() && minFieldX <= maxFieldX &&
                minFieldY.isFinite() && maxFieldY.isFinite() && minFieldY <= maxFieldY &&
                minFieldZ.isFinite() && maxFieldZ.isFinite() && minFieldZ <= maxFieldZ &&
                robotLengthMeters.isFinite() && robotLengthMeters >= 0.0 &&
                robotWidthMeters.isFinite() && robotWidthMeters >= 0.0 &&
                fieldBoundsToleranceMeters.isFinite() && fieldBoundsToleranceMeters >= 0.0 &&
                maxAngularVelocityRadPerSec.isFinite() && maxAngularVelocityRadPerSec >= 0.0 &&
                maxAccelerationG.isFinite() && maxAccelerationG >= 0.0 &&
                mahalanobisThreshold.isFinite() && mahalanobisThreshold > 0.0 &&
                mahalanobisThreshold2D.isFinite() && mahalanobisThreshold2D > 0.0
    }
}
