package com.areslib.ftc.vision

import com.areslib.action.RobotAction
import com.areslib.ftc.drivetrain.PinpointIO
import com.areslib.hardware.vision.VisionIO
import com.areslib.hardware.vision.VisionIOInputs
import com.areslib.hardware.vision.VisionOutlierFilter
import com.areslib.Store
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Vector3
import com.areslib.subsystem.VisionTracker
import com.areslib.math.wrapAngle

/**
 * AprilTag vision tracking and field localization manager for FTC platforms.
 *
 * Implements a 4-tier outlier rejection cascade (ambiguity filter, rotated robot-footprint field boundary check, distance cutoff, and EKF Mahalanobis distance validation).
 * Coordinates vision-based pose initialization and active-play kidnapped robot recovery (`RESEED_SNAP`).
 *
 * ### Recovery States & Thresholds:
 * - **Initialization Snap**: Re-seeds EKF and Pinpoint odometry pose when stationary if `hasInitializedPoseWithVision` is `false`.
 * - **Kidnapped Robot Recovery**: Accumulates vision target poses over consecutive EKF rejections (`consecutiveVisionRejections >= stolenRobotRejectionThreshold`).
 *   Re-seeds EKF pose when robot velocity $< \text{stolenRobotVelocityThreshold}$ ($0.1\text{m/s}$) and angular velocity $< \text{stolenRobotAngularVelocityThreshold}$ ($0.2\text{rad/s}$).
 *
 * @param store Redux store instance holding [RobotState].
 * @param limelightIO Underlying vision hardware IO instance ([VisionIO]).
 * @param pinpointIO Hardware odometry IO instance ([PinpointIO]) for pose re-seeding.
 * @param stdDevs Vision measurement standard deviation matrix ($m, m, rad$).
 *
 * @see VisionTracker
 * @see FtcLimelightIO
 * @see PinpointIO
 */
class FtcVisionTracker @kotlin.jvm.JvmOverloads constructor(
    private val store: Store,
    val limelightIO: VisionIO?,
    private val pinpointIO: PinpointIO?,
    var stdDevs: com.areslib.math.geometry.Vector3 = com.areslib.math.geometry.Vector3(0.05, 0.05, 0.1),
    private val onOdometryReseed: ((Pose2d) -> Unit)? = null
) : VisionTracker {
    /** Vision inputs container polled each loop frame. */
    val visionInputs = VisionIOInputs()
    /** Most recent valid AprilTag estimated robot field pose ([Pose2d]). */
    var lastLimelightPose: Pose2d? = null
        private set
    /** Timestamp ($ms$) of last valid AprilTag pose measurement. */
    var lastLimelightTimeMs = 0L
        private set
    /** Status message string describing active vision filter state (`"ACCEPTED"`, `"REJ_AMBIG"`, `"REJ_BOUNDS"`, `"REJ_DIST"`, `"REJ_YAW"`, `"REJ_MAHALANOBIS"`, `"INIT_ALIGN_SNAP"`, `"RESEED_SNAP"`). */
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
    /** Flag tracking whether initial vision pose alignment has executed. */
    var hasInitializedPoseWithVision = false

    /**
     * Executes 50Hz vision update loop: polls hardware, filters outliers, triggers pose snaps, and dispatches [RobotAction.VisionMeasurementsReceived].
     *
     * @param timestampMs Current system time in milliseconds ($ms$).
     */
    override fun update(timestampMs: Long) {

        val io = limelightIO ?: run {
            com.areslib.telemetry.RobotStatusTracker.visionConnected = false
            com.areslib.telemetry.RobotStatusTracker.visionStatus = "OFFLINE"
            return
        }

        io.updateInputs(visionInputs)
        if (visionInputs.measurements.isEmpty()) {
            if (lastLimelightPose != null && timestampMs - lastLimelightTimeMs > 500L) {
                lastLimelightPose = null
            }
            lastVisionStatus = "NO TARGET"
            com.areslib.telemetry.RobotStatusTracker.visionConnected = visionInputs.isConnected
            com.areslib.telemetry.RobotStatusTracker.visionStatus = lastVisionStatus
            return
        }

        val robotPoseForSelection = store.state.drive.poseEstimator.estimatedPose
        var bestMeasurement = visionInputs.measurements[0]
        var bestAmbiguity = bestMeasurement.ambiguity
        var bestDistance = kotlin.math.sqrt((bestMeasurement.targetPose.x - robotPoseForSelection.x) * (bestMeasurement.targetPose.x - robotPoseForSelection.x) + (bestMeasurement.targetPose.y - robotPoseForSelection.y) * (bestMeasurement.targetPose.y - robotPoseForSelection.y))
        
        for (i in 1 until visionInputs.measurements.size) {
            val m = visionInputs.measurements[i]
            val mDist = kotlin.math.sqrt((m.targetPose.x - robotPoseForSelection.x) * (m.targetPose.x - robotPoseForSelection.x) + (m.targetPose.y - robotPoseForSelection.y) * (m.targetPose.y - robotPoseForSelection.y))
            if (m.ambiguity < bestAmbiguity || (m.ambiguity == bestAmbiguity && mDist < bestDistance)) {
                bestMeasurement = m
                bestAmbiguity = m.ambiguity
                bestDistance = mDist
            }
        }
        val measurement = bestMeasurement
        lastLimelightTimeMs = timestampMs

        val robotPose = store.state.drive.poseEstimator.estimatedPose
        val robotHeading = robotPose.heading.radians
        var fieldPose3d = measurement.targetPose
        var fieldPose2d = fieldPose3d.toPose2d()

        if (store.state.drive.alliance == com.areslib.state.Alliance.RED) {
            fieldPose2d = Pose2d(-fieldPose2d.x, -fieldPose2d.y, Rotation2d(fieldPose2d.heading.radians + Math.PI))
            fieldPose3d = com.areslib.math.geometry.Pose3d(
                com.areslib.math.geometry.Translation3d(fieldPose2d.x, fieldPose2d.y, fieldPose3d.z),
                com.areslib.math.geometry.Rotation3d(fieldPose3d.rotation.x, fieldPose3d.rotation.y, fieldPose2d.heading.radians)
            )
        }
        // Expose the same alliance-adjusted field pose that filtering and estimator fusion consume.
        lastLimelightPose = fieldPose2d

        val dx = fieldPose2d.x - robotPose.x
        val dy = fieldPose2d.y - robotPose.y
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        val fieldYaw = fieldPose3d.rotation.z
        val headingDiff = wrapAngle(fieldYaw - robotHeading)

        lastVisionStatus = checkVisionOutlierRejection(
            measurement,
            fieldPose3d,
            fieldPose2d,
            distance,
            headingDiff
        )
        val filterConfig = store.state.vision.filterConfig
        val passesSnapPlausibility = measurement.ambiguity.isFinite() &&
            measurement.ambiguity < filterConfig.maxAmbiguity &&
            VisionOutlierFilter.isPoseWithinFieldBounds(filterConfig, fieldPose3d)

        val tuning = store.state.tuning
        val velThreshold = tuning.stolenRobotVelocityThreshold
        val angularThreshold = tuning.stolenRobotAngularVelocityThreshold
        val isStationary = kotlin.math.abs(store.state.drive.xVelocityMetersPerSecond) < velThreshold &&
                           kotlin.math.abs(store.state.drive.yVelocityMetersPerSecond) < velThreshold &&
                           kotlin.math.abs(store.state.drive.measuredAngularVelocityRadiansPerSecond) < angularThreshold

        if (!hasInitializedPoseWithVision && passesSnapPlausibility && isStationary) {
            val snapPose = fieldPose2d
            reseedOdometry(snapPose)
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
            val isHighConfidence = measurement.ambiguity.isFinite() &&
                measurement.ambiguity < filterConfig.maxAmbiguity

            if (isRejectedOrDivergent && isHighConfidence && passesSnapPlausibility && isStationary) {
                val p2d = fieldPose2d
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

                    reseedOdometry(snapPose)

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

    private fun reseedOdometry(pose: Pose2d) {
        val reseed = onOdometryReseed
        if (reseed != null) {
            reseed(pose)
        } else {
            pinpointIO?.initialize(pose, resetHardware = false)
        }
    }

    private fun checkVisionOutlierRejection(
        measurement: com.areslib.state.VisionMeasurement,
        fieldPose3d: com.areslib.math.geometry.Pose3d,
        fieldPose2d: Pose2d,
        distance: Double,
        headingDiff: Double
    ): String {
        val filterConfig = store.state.vision.filterConfig

        return when {
            !measurement.ambiguity.isFinite() || measurement.ambiguity > filterConfig.maxAmbiguity -> {
                "REJ_AMBIG"
            }
            !VisionOutlierFilter.isPoseWithinFieldBounds(filterConfig, fieldPose3d) -> {
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
                        val numTags = measurement.tagCount.coerceAtLeast(1)
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

