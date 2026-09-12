package com.areslib.ftc.vision

import com.areslib.action.RobotAction
import com.areslib.ftc.drivetrain.PinpointIO
import com.areslib.hardware.vision.VisionIO
import com.areslib.hardware.vision.VisionRecoveryConsensus
import com.areslib.hardware.vision.VisionFrameGate
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
 * - **Kidnapped Robot Recovery**: Accumulates vision target poses over consistent divergent observations until the configured whole-sample requirement is met.
 *   Re-seeds EKF pose when robot velocity $< \text{stolenRobotVelocityThreshold}$ ($0.1\text{m/s}$) and angular velocity $< \text{stolenRobotAngularVelocityThreshold}$ ($0.25\text{rad/s}$).
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
    var stdDevs: Vector3 = stdDevs
        set(value) { field = value }

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
    private val recoveryConsensus = VisionRecoveryConsensus()
    private val frameGate = VisionFrameGate(500L)
    private var updating = false
    private var lastRecoveryUpdateMs = 0L
    private val freshMeasurements = ArrayList<com.areslib.state.VisionMeasurement>(8)
    /** True after a successful initial or recovery alignment; callers may request reinitialization. */
    var hasInitializedPoseWithVision = false

    /**
     * Executes 50Hz vision update loop: polls hardware, filters outliers, triggers pose snaps, and dispatches [RobotAction.VisionMeasurementsReceived].
     *
     * @param timestampMs Current RobotClock time in milliseconds ($ms$).
     */
    override fun update(timestampMs: Long) {
        check(!updating) { "Vision tracker update is not reentrant" }
        updating = true
        try {
            updateFrame(timestampMs)
        } catch (failure: Throwable) {
            clearInputs()
            resetRecoveryAccumulator()
            lastLimelightPose = null
            publishStatus("IO_ERROR")
            throw failure
        } finally {
            updating = false
        }
    }

    private fun clearInputs() {
        visionInputs.isConnected = false
        visionInputs.measurements = emptyList()
        visionInputs.cameraPoses = emptyList()
        freshMeasurements.clear()
    }

    private fun publishStatus(status: String) {
        lastVisionStatus = status
        com.areslib.telemetry.RobotStatusTracker.visionConnected = isConnected
        com.areslib.telemetry.RobotStatusTracker.visionStatus = status
    }

    private fun updateFrame(timestampMs: Long) {
        if (!frameGate.beginUpdate(timestampMs)) {
            resetRecoveryAccumulator()
            lastLimelightPose = null
        }
        val recoveryAge = timestampMs - lastRecoveryUpdateMs
        if (recoveryConsensus.sampleCount > 0L && (recoveryAge < 0L || recoveryAge > 500L)) resetRecoveryAccumulator()
        clearInputs()

        val io = limelightIO ?: run {
            resetRecoveryAccumulator()
            lastLimelightPose = null
            publishStatus("OFFLINE")
            return
        }

        val driveBeforeVision = store.state.drive
        val yawDegrees = Math.toDegrees(wrapAngle(driveBeforeVision.poseEstimator.estimatedPoseHeading))
        val yawRate = Math.toDegrees(driveBeforeVision.measuredAngularVelocityRadiansPerSecond)
        val measuredLinearSpeed = kotlin.math.hypot(
            driveBeforeVision.measuredFieldXVelocityMetersPerSecond,
            driveBeforeVision.measuredFieldYVelocityMetersPerSecond)
        val driveSignalsValid = VisionOutlierFilter.isDriveObservationValid(driveBeforeVision) &&
            yawDegrees.isFinite() && yawRate.isFinite() && measuredLinearSpeed.isFinite()
        if (driveSignalsValid) {
            io.setOrientation(yawDegrees, yawRate, driveBeforeVision.pitchDegrees, 0.0,
                driveBeforeVision.rollDegrees, 0.0, measuredLinearSpeed)
        }
        io.updateInputs(visionInputs)
        if (!visionInputs.isConnected) {
            clearInputs()
            resetRecoveryAccumulator()
            lastLimelightPose = null
            publishStatus("OFFLINE")
            return
        }
        if (!driveSignalsValid) {
            resetRecoveryAccumulator()
            lastLimelightPose = null
            publishStatus("REJ_DRIVE_SIGNALS")
            return
        }
        if (lastLimelightPose != null && !frameGate.isRecent(lastLimelightTimeMs)) lastLimelightPose = null
        if (visionInputs.measurements.isEmpty()) {
            resetRecoveryAccumulator()
            lastVisionStatus = "NO TARGET"
            com.areslib.telemetry.RobotStatusTracker.visionConnected = visionInputs.isConnected
            com.areslib.telemetry.RobotStatusTracker.visionStatus = lastVisionStatus
            return
        }

        for (i in visionInputs.measurements.indices) {
            val candidate = visionInputs.measurements[i]
            if (frameGate.accept(candidate)) freshMeasurements.add(candidate)
        }
        if (freshMeasurements.isEmpty()) {
            lastVisionStatus = "STALE_FRAME"
            com.areslib.telemetry.RobotStatusTracker.visionConnected = visionInputs.isConnected
            com.areslib.telemetry.RobotStatusTracker.visionStatus = lastVisionStatus
            return
        }

        val robotPose = driveBeforeVision.poseEstimator.estimatedPose
        val filterConfig = store.state.vision.filterConfig
        var bestIndex = 0
        var bestQuality = -1
        var bestAmbiguity = Double.POSITIVE_INFINITY
        var bestDistance = Double.POSITIVE_INFINITY
        var physicalPoseIsPlausible = false
        var recoveryPoseIsPlausible = false
        for (i in freshMeasurements.indices) {
            val candidate = freshMeasurements[i]
            val normalValid = VisionOutlierFilter.isValidForRecovery(filterConfig, candidate, false,
                driveBeforeVision.measuredAngularVelocityRadiansPerSecond, driveBeforeVision.xAccelerationG,
                driveBeforeVision.yAccelerationG, driveBeforeVision.zAccelerationG)
            val recoveryValid = VisionOutlierFilter.isValidForRecovery(filterConfig, candidate, true,
                driveBeforeVision.measuredAngularVelocityRadiansPerSecond, driveBeforeVision.xAccelerationG,
                driveBeforeVision.yAccelerationG, driveBeforeVision.zAccelerationG)
            // Prefer a usable normal solve; retain independent-only solves for recovery. When all
            // candidates are invalid, keep the first solely for its rejection status/diagnostics.
            val quality = if (normalValid) 2 else if (recoveryValid) 1 else 0
            val pose = if (normalValid) candidate.targetPose else candidate.recoveryPose
            val ambiguityAvailable = if (normalValid) candidate.ambiguityAvailable else candidate.recoveryAmbiguityAvailable
            val ambiguity = if (!ambiguityAvailable) Double.POSITIVE_INFINITY else
                if (normalValid) candidate.ambiguity else candidate.recoveryAmbiguity
            val distance = if (quality == 0) Double.POSITIVE_INFINITY else distance(pose, robotPose)
            var better = quality > bestQuality
            if (quality == bestQuality && quality > 0) {
                better = ambiguity < bestAmbiguity
                if (ambiguity == bestAmbiguity) {
                    better = distance < bestDistance
                    if (distance.isInfinite() && bestDistance.isInfinite()) {
                        val best = freshMeasurements[bestIndex]
                        val bestPose = if (physicalPoseIsPlausible) best.targetPose else best.recoveryPose
                        better = quarterDistance(pose, robotPose) < quarterDistance(bestPose, robotPose)
                    }
                }
            }
            if (better) {
                bestIndex = i
                bestQuality = quality
                bestAmbiguity = ambiguity
                bestDistance = distance
                physicalPoseIsPlausible = normalValid
                recoveryPoseIsPlausible = recoveryValid
            }
        }
        val measurement = freshMeasurements[bestIndex]
        val robotHeading = robotPose.heading.radians
        val fieldPose3d = measurement.targetPose
        val fieldPose2d = fieldPose3d.toPose2d()
        val recoveryPose2d = if (measurement.hasRecoveryPose) measurement.recoveryPose.toPose2d() else fieldPose2d

        // This public pose feeds calibration and telemetry. Rejected or independent-only normal
        // solves must not appear as a valid normal camera observation.
        lastLimelightPose = if (physicalPoseIsPlausible) fieldPose2d else null
        if (physicalPoseIsPlausible) lastLimelightTimeMs = measurement.timestampMs

        val distance = if (physicalPoseIsPlausible) bestDistance else distance(fieldPose3d, robotPose)
        val fieldYaw = fieldPose3d.rotation.z
        val headingDiff = wrapAngle(fieldYaw - robotHeading)
        val recoveryHeadingDiff = wrapAngle(recoveryPose2d.heading.radians - robotHeading)

        lastVisionStatus = checkVisionOutlierRejection(
            measurement,
            fieldPose3d,
            distance,
            headingDiff,
            physicalPoseIsPlausible
        )
        val passesPhysicalFilters = lastVisionStatus == "ACCEPTED"

        // Fuse once through the authoritative EKF path before recovery logic consumes
        // the decision. This prevents a tracker-side approximation from disagreeing
        // with the estimator's full covariance/Mahalanobis calculation.
        val visionResult = store.dispatchAndGetState(RobotAction.VisionMeasurementsReceived(
            freshMeasurements,
            timestampMs,
            stdDevs,
            diagnosticMeasurementIndex = bestIndex
        )).vision
        if (passesPhysicalFilters && hasInitializedPoseWithVision) {
            lastVisionStatus = if (visionResult.diagnosticMeasurementIndex != bestIndex) {
                "REJ_EKF"
            } else if (visionResult.diagnosticMeasurementAccepted) {
                "ACCEPTED"
            } else {
                when (visionResult.diagnosticMeasurementRejectionReason) {
                    "mahalanobis_rejected" -> "REJ_MAHALANOBIS"
                    null -> "REJ_EKF"
                    else -> "REJ_EKF_${visionResult.diagnosticMeasurementRejectionReason}"
                }
            }
        }
        val isAccepted = lastVisionStatus == "ACCEPTED"

        val tuning = store.state.tuning
        val velThreshold = tuning.recovery.stolenRobotVelocityThreshold
        val angularThreshold = tuning.recovery.stolenRobotAngularVelocityThreshold
        val isStationary = velThreshold.isFinite() && velThreshold > 0.0 &&
            angularThreshold.isFinite() && angularThreshold > 0.0 && measuredLinearSpeed < velThreshold &&
            kotlin.math.abs(driveBeforeVision.measuredAngularVelocityRadiansPerSecond) < angularThreshold

        if (!hasInitializedPoseWithVision && isAccepted && isStationary) {
            // A stationary MT1 pose gives initialization an independent yaw reference;
            // MT2 yaw is intentionally ignored during normal fusion because it echoes IMU yaw.
            val snapPose = if (recoveryPoseIsPlausible) recoveryPose2d else fieldPose2d
            reseedOdometry(snapPose)
            hasInitializedPoseWithVision = true
            resetRecoveryAccumulator()
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

            val requiredSamples = VisionRecoveryConsensus.requiredSamples(tuning.recovery.stolenRobotRejectionThreshold)
            if (requiredSamples > 0L && isRejectedOrDivergent && isStationary &&
                (physicalPoseIsPlausible || recoveryPoseIsPlausible)) {
                // MT1 is deliberately kept out of normal high-rate fusion, but its yaw
                // is independent of the gyro supplied to MT2. Consistent stationary MT1
                // frames can therefore recover a robot that was lifted and rotated or
                // whose gyro heading was reset/corrupted.
                val recoveryX = if (recoveryPoseIsPlausible) recoveryPose2d.x else fieldPose2d.x
                val recoveryY = if (recoveryPoseIsPlausible) recoveryPose2d.y else fieldPose2d.y
                val recoveryHeading = when {
                    recoveryPoseIsPlausible -> recoveryPose2d.heading.radians
                    measurement.solverType != com.areslib.state.VisionSolverType.MEGATAG2 -> fieldPose2d.heading.radians
                    else -> robotHeading
                }
                if (!recoveryConsensus.add(recoveryX, recoveryY, recoveryHeading)) {
                    resetRecoveryAccumulator()
                    publishStatus("REJ_INVALID")
                    return
                }
                lastRecoveryUpdateMs = timestampMs

                if (recoveryConsensus.sampleCount >= requiredSamples) {
                    val snapPose = Pose2d(recoveryConsensus.meanX, recoveryConsensus.meanY,
                        Rotation2d(recoveryConsensus.meanHeadingRad))
                    reseedOdometry(snapPose)
                    hasInitializedPoseWithVision = true
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
        headingDiff: Double,
        physicalPoseIsPlausible: Boolean
    ): String {
        val filterConfig = store.state.vision.filterConfig
        val drive = store.state.drive
        if (!distance.isFinite() || !headingDiff.isFinite()) return "REJ_INVALID"
        if (physicalPoseIsPlausible) return when {
            distance > filterConfig.maxDistanceMeters -> "REJ_DIST"
            kotlin.math.abs(headingDiff) > filterConfig.maxRotationDeviationRad -> "REJ_YAW"
            else -> "ACCEPTED"
        }

        // Physical validation already ran once. Detailed reasons are only needed on rejection.
        return when {
            (measurement.ambiguityAvailable && !measurement.ambiguity.isFinite()) || !fieldPose3d.x.isFinite() ||
                !fieldPose3d.y.isFinite() || !fieldPose3d.z.isFinite() ||
                !fieldPose3d.rotation.x.isFinite() || !fieldPose3d.rotation.y.isFinite() ||
                !fieldPose3d.rotation.z.isFinite() || !distance.isFinite() ||
                !headingDiff.isFinite() || !drive.measuredAngularVelocityRadiansPerSecond.isFinite() ||
                !drive.xAccelerationG.isFinite() || !drive.yAccelerationG.isFinite() ||
                !drive.zAccelerationG.isFinite() -> {
                "REJ_INVALID"
            }
            measurement.ambiguityAvailable && measurement.ambiguity > filterConfig.maxAmbiguity -> {
                "REJ_AMBIG"
            }
            !VisionOutlierFilter.isPoseWithinFieldBounds(filterConfig, fieldPose3d) -> {
                "REJ_BOUNDS"
            }
            kotlin.math.abs(drive.measuredAngularVelocityRadiansPerSecond) > filterConfig.maxAngularVelocityRadPerSec -> "REJ_RATE"
            shockMagnitude(drive.xAccelerationG, drive.yAccelerationG, drive.zAccelerationG) > filterConfig.maxAccelerationG -> "REJ_SHOCK"
            else -> "REJ_FILTERED"
        }
    }

    private fun resetRecoveryAccumulator() {
        recoveryConsensus.clear()
        lastRecoveryUpdateMs = 0L
    }

    private fun shockMagnitude(xG: Double, yG: Double, zG: Double): Double {
        val dynamicZ = if (zG == 0.0) 0.0 else zG - 1.0
        return kotlin.math.hypot(kotlin.math.hypot(xG, yG), dynamicZ)
    }

    private fun distance(pose3d: com.areslib.math.geometry.Pose3d, pose2d: Pose2d): Double =
        kotlin.math.hypot(pose3d.x - pose2d.x, pose3d.y - pose2d.y)

    // Only needed to order distances larger than Double.MAX_VALUE. Scaling both finite poses
    // before subtraction also handles opposite-sign endpoints without overflow; ordinary tiny
    // distances use the unscaled hypot path above.
    private fun quarterDistance(pose3d: com.areslib.math.geometry.Pose3d, pose2d: Pose2d): Double =
        kotlin.math.hypot(pose3d.x * 0.25 - pose2d.x * 0.25, pose3d.y * 0.25 - pose2d.y * 0.25)
}
