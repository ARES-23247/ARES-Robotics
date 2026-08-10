package com.areslib.hardware.vision

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Translation2d
import com.areslib.state.VisionMeasurement
import kotlin.math.abs
import kotlin.math.sqrt
import com.areslib.math.wrapAngle

/**
 * Filter configuration thresholds for AprilTag measurements.
 */
data class VisionFilterConfig(
    val maxDistanceMeters: Double = 6.0,
    val maxAmbiguity: Double = 0.2,
    val maxRotationDeviationRad: Double = Math.toRadians(30.0),
    val minFieldX: Double = -2.5,
    val maxFieldX: Double = 2.5,
    val minFieldY: Double = -2.5,
    val maxFieldY: Double = 2.5,
    val minFieldZ: Double = -0.2,
    val maxFieldZ: Double = 1.0,
    val maxAngularVelocityRadPerSec: Double = 2.0,
    val maxAccelerationG: Double = 2.5,
    val mahalanobisThreshold: Double = 18.0
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
        ): Boolean {
            val pose = measurement.targetPose
            if (!measurement.ambiguity.isFinite() ||
                !pose.x.isFinite() || !pose.y.isFinite() || !pose.z.isFinite() ||
                !pose.rotation.x.isFinite() || !pose.rotation.y.isFinite() || !pose.rotation.z.isFinite() ||
                !robotPose.x.isFinite() || !robotPose.y.isFinite() || !robotHeadingRad.isFinite() ||
                !angularVelocityRadPerSec.isFinite() || !linearAccelXG.isFinite() ||
                !linearAccelYG.isFinite() || !linearAccelZG.isFinite()) {
                return false
            }

            // 1. Check Ambiguity (if >= 0.0)
            if (measurement.ambiguity > config.maxAmbiguity) {
                return false
            }

            // 2. Check 3D Spatial Boundaries
            val tagPose3d = pose
            if (tagPose3d.x < config.minFieldX || tagPose3d.x > config.maxFieldX ||
                tagPose3d.y < config.minFieldY || tagPose3d.y > config.maxFieldY ||
                tagPose3d.z < config.minFieldZ || tagPose3d.z > config.maxFieldZ) {
                return false
            }

            // 3. Check Distance
            val tagPose2d = tagPose3d.toPose2d()
            val dx = tagPose2d.x - robotPose.x
            val dy = tagPose2d.y - robotPose.y
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
    }
}
