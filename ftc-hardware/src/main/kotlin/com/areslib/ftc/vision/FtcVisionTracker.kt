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
    stdDevs: com.areslib.math.geometry.Vector3 = com.areslib.math.geometry.Vector3(0.05, 0.05, 0.1),
    private val onOdometryReseed: ((Pose2d) -> Unit)? = null
) : VisionTracker {
    private var megaTag2StdDevs = Vector3(stdDevs.x, stdDevs.y, 1.0e6)
    var stdDevs: Vector3 = stdDevs
        set(value) {
            if (field == value) return
            field = value
            megaTag2StdDevs = Vector3(value.x, value.y, 1.0e6)
        }

    /** Updates live covariance only when tuning values actually change. */
    fun setStdDevs(xMeters: Double, yMeters: Double, headingRadians: Double) {
        if (stdDevs.x == xMeters && stdDevs.y == yMeters && stdDevs.z == headingRadians) return
        stdDevs = Vector3(xMeters, yMeters, headingRadians)
    }
    /** Vision inputs container polled each loop frame. */
    val visionInputs = VisionIOInputs()
    /** Most recent valid AprilTag estimated robot field pose ([Pose2d]). */
    var lastLimelightPose: Pose2d? = null
        private set
    /** Timestamp ($ms$) of last valid AprilTag pose measurement. */
    var lastLimelightTimeMs = 0L
        private set
    /** Status message string describing the active vision filter, freshness, or pose-reseed state. */
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
    private val recentSourceIds = arrayOfNulls<String>(8)
    private val recentFrameIds = LongArray(8) { Long.MIN_VALUE }
    private val recentTimestampsMs = LongArray(8) { Long.MIN_VALUE }
    private val freshMeasurements = ArrayList<com.areslib.state.VisionMeasurement>(8)
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

        val driveBeforeVision = store.state.drive
        io.setOrientation(
            yawDegrees = Math.toDegrees(driveBeforeVision.poseEstimator.estimatedPoseHeading),
            yawRateDegPerSec = Math.toDegrees(driveBeforeVision.measuredAngularVelocityRadiansPerSecond),
            pitchDegrees = driveBeforeVision.pitchDegrees,
            pitchRateDegPerSec = 0.0,
            rollDegrees = driveBeforeVision.rollDegrees,
            rollRateDegPerSec = 0.0,
            linearVelocityMps = kotlin.math.hypot(
                driveBeforeVision.measuredFieldXVelocityMetersPerSecond,
                driveBeforeVision.measuredFieldYVelocityMetersPerSecond
            )
        )
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

        freshMeasurements.clear()
        for (candidate in visionInputs.measurements) {
            if (isFreshFrame(candidate, timestampMs)) freshMeasurements.add(candidate)
        }
        if (freshMeasurements.isEmpty()) {
            lastVisionStatus = "STALE_FRAME"
            com.areslib.telemetry.RobotStatusTracker.visionConnected = visionInputs.isConnected
            com.areslib.telemetry.RobotStatusTracker.visionStatus = lastVisionStatus
            return
        }

        val robotPoseForSelection = store.state.drive.poseEstimator.estimatedPose
        var bestMeasurement = freshMeasurements[0]
        var bestAmbiguity = bestMeasurement.ambiguity
        var bestDistance = kotlin.math.sqrt((bestMeasurement.targetPose.x - robotPoseForSelection.x) * (bestMeasurement.targetPose.x - robotPoseForSelection.x) + (bestMeasurement.targetPose.y - robotPoseForSelection.y) * (bestMeasurement.targetPose.y - robotPoseForSelection.y))
        
        for (i in 1 until freshMeasurements.size) {
            val m = freshMeasurements[i]
            val mDist = kotlin.math.sqrt((m.targetPose.x - robotPoseForSelection.x) * (m.targetPose.x - robotPoseForSelection.x) + (m.targetPose.y - robotPoseForSelection.y) * (m.targetPose.y - robotPoseForSelection.y))
            if (m.ambiguity < bestAmbiguity || (m.ambiguity == bestAmbiguity && mDist < bestDistance)) {
                bestMeasurement = m
                bestAmbiguity = m.ambiguity
                bestDistance = mDist
            }
        }
        val measurement = bestMeasurement
        lastLimelightTimeMs = measurement.timestampMs

        val robotPose = store.state.drive.poseEstimator.estimatedPose
        val robotHeading = robotPose.heading.radians
        val fieldPose3d = measurement.targetPose
        val fieldPose2d = fieldPose3d.toPose2d()
        val recoveryPose3d = if (measurement.hasRecoveryPose) measurement.recoveryPose else fieldPose3d
        val recoveryPose2d = recoveryPose3d.toPose2d()

        // Limelight field poses are canonical and alliance-independent. Alliance mirroring belongs
        // only at the season driver-input boundary.
        lastLimelightPose = fieldPose2d

        val dx = fieldPose2d.x - robotPose.x
        val dy = fieldPose2d.y - robotPose.y
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        val fieldYaw = fieldPose3d.rotation.z
        val headingDiff = wrapAngle(fieldYaw - robotHeading)
        val recoveryHeadingDiff = wrapAngle(recoveryPose2d.heading.radians - robotHeading)
        val recoveryPoseIsPlausible = measurement.hasRecoveryPose &&
            recoveryPose2d.x.isFinite() && recoveryPose2d.y.isFinite() &&
            recoveryPose2d.heading.radians.isFinite() &&
            VisionOutlierFilter.isPoseWithinFieldBounds(store.state.vision.filterConfig, recoveryPose3d)

        lastVisionStatus = checkVisionOutlierRejection(
            measurement,
            fieldPose3d,
            distance,
            headingDiff
        )
        val passesPhysicalFilters = lastVisionStatus == "ACCEPTED"

        // Fuse once through the authoritative EKF path before recovery logic consumes
        // the decision. This prevents a tracker-side approximation from disagreeing
        // with the estimator's full covariance/Mahalanobis calculation.
        val fusionStdDevs = if (measurement.solverType == com.areslib.state.VisionSolverType.MEGATAG2) {
            megaTag2StdDevs
        } else {
            stdDevs
        }
        store.dispatch(RobotAction.VisionMeasurementsReceived(
            freshMeasurements,
            timestampMs,
            fusionStdDevs
        ))
        if (passesPhysicalFilters && hasInitializedPoseWithVision) {
            lastVisionStatus = if (store.state.vision.lastMeasurementAccepted) {
                "ACCEPTED"
            } else {
                when (store.state.vision.lastRejectionReason) {
                    "mahalanobis_rejected" -> "REJ_MAHALANOBIS"
                    null -> "REJ_EKF"
                    else -> "REJ_EKF_${store.state.vision.lastRejectionReason}"
                }
            }
        }
        val isAccepted = lastVisionStatus == "ACCEPTED"

        val tuning = store.state.tuning
        val velThreshold = tuning.stolenRobotVelocityThreshold
        val angularThreshold = tuning.stolenRobotAngularVelocityThreshold
        val isStationary = kotlin.math.abs(store.state.drive.xVelocityMetersPerSecond) < velThreshold &&
                           kotlin.math.abs(store.state.drive.yVelocityMetersPerSecond) < velThreshold &&
                           kotlin.math.abs(store.state.drive.measuredAngularVelocityRadiansPerSecond) < angularThreshold

        if (!hasInitializedPoseWithVision && isAccepted && isStationary) {
            // A stationary MT1 pose gives initialization an independent yaw reference;
            // MT2 yaw is intentionally ignored during normal fusion because it echoes IMU yaw.
            val snapPose = if (recoveryPoseIsPlausible) recoveryPose2d else fieldPose2d
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
            val isRecoverableRejection = when (lastVisionStatus) {
                "REJ_DIST", "REJ_YAW", "REJ_MAHALANOBIS" -> true
                else -> false
            }
            val independentYawDivergence = recoveryPoseIsPlausible &&
                kotlin.math.abs(recoveryHeadingDiff) > store.state.vision.filterConfig.maxRotationDeviationRad
            val isRejectedOrDivergent = isRecoverableRejection ||
                (isAccepted && distance > 0.4) || independentYawDivergence

            if (isRejectedOrDivergent && isStationary) {
                // MT1 is deliberately kept out of normal high-rate fusion, but its yaw
                // is independent of the gyro supplied to MT2. Consistent stationary MT1
                // frames can therefore recover a robot that was lifted and rotated or
                // whose gyro heading was reset/corrupted.
                val p2d = if (recoveryPoseIsPlausible) {
                    recoveryPose2d
                } else if (measurement.solverType != com.areslib.state.VisionSolverType.MEGATAG2) {
                    fieldPose2d
                } else {
                    Pose2d(fieldPose2d.x, fieldPose2d.y, Rotation2d(robotHeading))
                }
                if (consecutiveVisionRejections > 0) {
                    val meanX = accumX / consecutiveVisionRejections
                    val meanY = accumY / consecutiveVisionRejections
                    val meanHeading = kotlin.math.atan2(accumSin, accumCos)
                    val sampleTranslationError = kotlin.math.hypot(p2d.x - meanX, p2d.y - meanY)
                    val sampleHeadingError = kotlin.math.abs(wrapAngle(p2d.heading.radians - meanHeading))
                    if (sampleTranslationError > 0.35 || sampleHeadingError > Math.toRadians(20.0)) {
                        resetRecoveryAccumulator()
                    }
                }
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

                    resetRecoveryAccumulator()

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
                resetRecoveryAccumulator()
            }
        }

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
        distance: Double,
        headingDiff: Double
    ): String {
        val filterConfig = store.state.vision.filterConfig
        val drive = store.state.drive

        return when {
            !measurement.ambiguity.isFinite() || !fieldPose3d.x.isFinite() ||
                !fieldPose3d.y.isFinite() || !fieldPose3d.z.isFinite() ||
                !fieldPose3d.rotation.x.isFinite() || !fieldPose3d.rotation.y.isFinite() ||
                !fieldPose3d.rotation.z.isFinite() || !distance.isFinite() ||
                !headingDiff.isFinite() || !drive.measuredAngularVelocityRadiansPerSecond.isFinite() ||
                !drive.xAccelerationG.isFinite() || !drive.yAccelerationG.isFinite() ||
                !drive.zAccelerationG.isFinite() -> {
                "REJ_INVALID"
            }
            measurement.ambiguity > filterConfig.maxAmbiguity -> {
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
            kotlin.math.abs(drive.measuredAngularVelocityRadiansPerSecond) > filterConfig.maxAngularVelocityRadPerSec -> {
                "REJ_RATE"
            }
            shockMagnitude(
                drive.xAccelerationG,
                drive.yAccelerationG,
                drive.zAccelerationG
            ) > filterConfig.maxAccelerationG -> {
                "REJ_SHOCK"
            }
            else -> "ACCEPTED"
        }
    }

    private fun resetRecoveryAccumulator() {
        consecutiveVisionRejections = 0
        accumX = 0.0
        accumY = 0.0
        accumSin = 0.0
        accumCos = 0.0
    }

    private fun shockMagnitude(xG: Double, yG: Double, zG: Double): Double {
        val dynamicZ = if (zG == 0.0) 0.0 else zG - 1.0
        return kotlin.math.sqrt(xG * xG + yG * yG + dynamicZ * dynamicZ)
    }

    private fun isFreshFrame(measurement: com.areslib.state.VisionMeasurement, nowMs: Long): Boolean {
        if (measurement.timestampMs <= 0L || measurement.timestampMs > nowMs + 50L ||
            nowMs - measurement.timestampMs > 500L) {
            return false
        }

        val sourceId = measurement.sourceId.ifEmpty { "default" }
        var emptySlot = -1
        for (i in recentSourceIds.indices) {
            val existing = recentSourceIds[i]
            if (existing == sourceId) {
                val duplicate = if (measurement.frameId != 0L) {
                    measurement.frameId == recentFrameIds[i] ||
                        measurement.timestampMs <= recentTimestampsMs[i]
                } else {
                    measurement.timestampMs <= recentTimestampsMs[i]
                }
                if (duplicate) return false
                recentFrameIds[i] = measurement.frameId
                recentTimestampsMs[i] = measurement.timestampMs
                return true
            }
            if (existing == null && emptySlot == -1) emptySlot = i
        }

        val slot = if (emptySlot >= 0) emptySlot else sourceId.hashCode().and(Int.MAX_VALUE) % recentSourceIds.size
        recentSourceIds[slot] = sourceId
        recentFrameIds[slot] = measurement.frameId
        recentTimestampsMs[slot] = measurement.timestampMs
        return true
    }
}

