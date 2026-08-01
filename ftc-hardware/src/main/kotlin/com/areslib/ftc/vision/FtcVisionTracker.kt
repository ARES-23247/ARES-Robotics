package com.areslib.ftc.vision

import com.areslib.action.RobotAction
import com.areslib.ftc.drivetrain.PinpointIO
import com.areslib.hardware.vision.VisionIO
import com.areslib.hardware.vision.VisionIOInputs
import com.areslib.Store
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Vector3
import com.areslib.subsystem.VisionTracker
import com.areslib.math.wrapAngle

/**
 * Manages the robot's AprilTag vision tracking system.
 * Handles outlier rejection (using yaw, field boundary, distance, and EKF Mahalanobis checks),
 * and triggers EKF snap updates during initialization or recovery states.
 */
class FtcVisionTracker @kotlin.jvm.JvmOverloads constructor(
    private val store: Store,
    val limelightIO: VisionIO?,
    private val pinpointIO: PinpointIO?,
    var stdDevs: com.areslib.math.geometry.Vector3 = com.areslib.math.geometry.Vector3(0.05, 0.05, 0.1)
) : VisionTracker {
    val visionInputs = VisionIOInputs()
    var lastLimelightPose: Pose2d? = null
        private set
    var lastLimelightTimeMs = 0L
        private set
    override var lastVisionStatus = "OFFLINE"
        private set

    /** True if the vision sensor hardware is connected and responding. */
    override val isConnected: Boolean
        get() = limelightIO != null && visionInputs.isConnected
    private var consecutiveVisionRejections = 0
    private var accumX = 0.0
    private var accumY = 0.0
    private var accumSin = 0.0
    private var accumCos = 0.0
    var hasInitializedPoseWithVision = false

    // Pre-allocated structure to guarantee Zero-GC compliance inside EKF verification loops


    /**
     * Polls the vision sensors, performs outlier rejection, and updates the EKF store.
     * Triggers mathematical snaps if initial pose or kidnapped robot conditions are met.
     */
    override fun update(timestampMs: Long) {
        val io = limelightIO ?: run {
            com.areslib.telemetry.RobotStatusTracker.visionConnected = false
            com.areslib.telemetry.RobotStatusTracker.visionStatus = "OFFLINE"
            return
        }

        io.updateInputs(visionInputs)
        if (visionInputs.measurements.isEmpty()) {
            lastVisionStatus = "NO TARGET"
            com.areslib.telemetry.RobotStatusTracker.visionConnected = visionInputs.isConnected
            com.areslib.telemetry.RobotStatusTracker.visionStatus = lastVisionStatus
            return
        }

        val measurement = visionInputs.measurements[0]
        lastLimelightPose = measurement.targetPose.toPose2d()
        lastLimelightTimeMs = timestampMs

        val robotPose = store.state.drive.poseEstimator.estimatedPose
        val robotHeading = robotPose.heading.radians
        val fieldPose3d = measurement.targetPose
        val fieldPose2d = fieldPose3d.toPose2d()
        val dx = fieldPose2d.x - robotPose.x
        val dy = fieldPose2d.y - robotPose.y
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        val fieldYaw = fieldPose3d.rotation.z
        val headingDiff = wrapAngle(fieldYaw - robotHeading)

        lastVisionStatus = checkVisionOutlierRejection(measurement, distance, headingDiff)
        val filterConfig = store.state.vision.filterConfig

        val tuning = store.state.tuning
        val velThreshold = tuning.stolenRobotVelocityThreshold
        val angularThreshold = tuning.stolenRobotAngularVelocityThreshold
        val isStationary = kotlin.math.abs(store.state.drive.xVelocityMetersPerSecond) < velThreshold &&
                           kotlin.math.abs(store.state.drive.yVelocityMetersPerSecond) < velThreshold &&
                           kotlin.math.abs(store.state.drive.measuredAngularVelocityRadiansPerSecond) < angularThreshold

        if (!hasInitializedPoseWithVision && measurement.ambiguity < filterConfig.maxAmbiguity && isStationary) {
            val snapPose = measurement.targetPose.toPose2d()
            pinpointIO?.initialize(snapPose, resetHardware = false)
            hasInitializedPoseWithVision = true
            lastVisionStatus = "INIT_ALIGN_SNAP"
            store.dispatch(RobotAction.PoseUpdate(
                xMeters = snapPose.x,
                yMeters = snapPose.y,
                headingRadians = snapPose.heading.radians,
                timestampMs = timestampMs,
                isReset = true
            ))
        } else {
            // Kidnapped Robot Recovery (Active Play)
            // Triggered if vision observation is rejected by EKF OR pose error relative to EKF > 0.4m
            val isRejectedOrDivergent = lastVisionStatus.startsWith("REJ_") || distance > 0.4
            val isHighConfidence = measurement.ambiguity < filterConfig.maxAmbiguity

            if (isRejectedOrDivergent && isHighConfidence && isStationary) {
                val p2d = measurement.targetPose.toPose2d()
                accumX += p2d.x
                accumY += p2d.y
                accumSin += kotlin.math.sin(p2d.heading.radians)
                accumCos += kotlin.math.cos(p2d.heading.radians)
                consecutiveVisionRejections++

                val reqThreshold = tuning.stolenRobotRejectionThreshold.toInt().coerceAtLeast(1)
                if (consecutiveVisionRejections >= reqThreshold) {
                    val avgX = accumX / consecutiveVisionRejections
                    val avgY = accumY / consecutiveVisionRejections
                    val avgHeading = kotlin.math.atan2(accumSin, accumCos)
                    val snapPose = Pose2d(avgX, avgY, Rotation2d(avgHeading))

                    pinpointIO?.initialize(snapPose, resetHardware = false)

                    consecutiveVisionRejections = 0
                    accumX = 0.0
                    accumY = 0.0
                    accumSin = 0.0
                    accumCos = 0.0

                    lastVisionStatus = "RESEED_SNAP"
                    store.dispatch(RobotAction.PoseUpdate(
                        xMeters = snapPose.x,
                        yMeters = snapPose.y,
                        headingRadians = snapPose.heading.radians,
                        timestampMs = timestampMs,
                        isReset = true
                    ))
                }
            } else {
                consecutiveVisionRejections = 0
                accumX = 0.0
                accumY = 0.0
                accumSin = 0.0
                accumCos = 0.0
            }
        }

        store.dispatch(RobotAction.VisionMeasurementsReceived(
            visionInputs.measurements,
            timestampMs,
            null
        ))

        com.areslib.telemetry.RobotStatusTracker.visionConnected = visionInputs.isConnected
        com.areslib.telemetry.RobotStatusTracker.visionStatus = lastVisionStatus
    }

    private fun checkVisionOutlierRejection(
        measurement: com.areslib.state.VisionMeasurement,
        distance: Double,
        headingDiff: Double
    ): String {
        val fieldPose3d = measurement.targetPose
        val fieldPose2d = fieldPose3d.toPose2d()
        val filterConfig = store.state.vision.filterConfig

        return when {
            measurement.ambiguity > filterConfig.maxAmbiguity -> {
                "REJ_AMBIG"
            }
            fieldPose3d.x < filterConfig.minFieldX || fieldPose3d.x > filterConfig.maxFieldX ||
            fieldPose3d.y < filterConfig.minFieldY || fieldPose3d.y > filterConfig.maxFieldY ||
            fieldPose3d.z < filterConfig.minFieldZ || fieldPose3d.z > filterConfig.maxFieldZ -> {
                "REJ_BOUNDS"
            }
            distance > filterConfig.maxDistanceMeters -> {
                "REJ_DIST"
            }
            kotlin.math.abs(headingDiff) > filterConfig.maxRotationDeviationRad -> {
                "REJ_YAW"
            }
            else -> {
                // Dry run of EKF Mahalanobis distance checks using pre-allocated stdDev vector
                val currentEstimator = store.state.drive.poseEstimator
                if (currentEstimator.history.isNotEmpty()) {
                    var closestIndex = -1
                    val history = currentEstimator.history
                    for (i in history.size - 1 downTo 0) {
                        if (history[i].timestampMs <= measurement.timestampMs) {
                            closestIndex = i
                            break
                        }
                    }
                    if (closestIndex != -1) {
                        val baseEntry = currentEstimator.history[closestIndex]
                        val numTags = visionInputs.measurements.size
                        val tagFactor = if (numTags <= 1) 2.5 else (1.0 / kotlin.math.sqrt(numTags.toDouble()))
                        val distFactor = kotlin.math.sqrt(1.0 + distance * distance)
                        
                        val scaledStdDevsX = stdDevs.x * (tagFactor * distFactor)
                        val scaledStdDevsY = stdDevs.y * (tagFactor * distFactor)
                        val scaledStdDevsZ = stdDevs.z * (tagFactor * distFactor)
                        
                        val rXX = scaledStdDevsX * scaledStdDevsX
                        val rYY = scaledStdDevsY * scaledStdDevsY
                        val rZZ = scaledStdDevsZ * scaledStdDevsZ
                        
                        val sXX = baseEntry.covariance.m00 + rXX
                        val sYY = baseEntry.covariance.m11 + rYY
                        val sZZ = baseEntry.covariance.m22 + rZZ
                        
                        val yX = fieldPose2d.x - baseEntry.pose.x
                        val yY = fieldPose2d.y - baseEntry.pose.y
                        val yZ = wrapAngle(fieldPose2d.heading.radians - baseEntry.pose.heading.radians)
                        
                        val dMSquared = (yX * yX / sXX) + (yY * yY / sYY) + (yZ * yZ / sZZ)
                        if (dMSquared > filterConfig.mahalanobisThreshold) {
                            "REJ_MAHALANOBIS"
                        } else {
                            "ACCEPTED"
                        }
                    } else {
                        "ACCEPTED (NO_HIST)"
                    }
                } else {
                    "ACCEPTED"
                }
            }
        }
    }
}

