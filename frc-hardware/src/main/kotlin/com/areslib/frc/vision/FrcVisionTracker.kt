package com.areslib.frc.vision

import com.areslib.action.RobotAction
import com.areslib.hardware.vision.VisionIO
import com.areslib.hardware.vision.VisionIOInputs
import com.areslib.hardware.vision.VisionOutlierFilter
import com.areslib.Store
import com.areslib.subsystem.VisionTracker
import com.areslib.telemetry.RobotStatusTracker
import com.areslib.state.VisionMeasurement
import com.areslib.state.VisionSolverType
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.wrapAngle

/**
 * AprilTag vision tracking and field pose estimation manager for FRC platforms.
 *
 * Feeds robot orientation (yaw, yaw rate, pitch, roll, linear speed) to Limelight vision hardware for MegaTag2 gyro-assisted localization.
 * Filters AprilTag vision measurements using the configured FRC physical-validity
 * gates plus a target-space distance cutoff ($<6.0\text{m}$) before passing pose
 * observations to CTRE swerve EKF via `swerveIO.addVisionMeasurement`.
 *
 * ### Filtering Thresholds & Physical Units:
 * - Maximum Tag Distance: $6.0$ meters ($m$).
 * - Maximum Tag Ambiguity: [com.areslib.hardware.vision.VisionFilterConfig.maxAmbiguity].
 * - Orientation Parameters: Radians ($rad$), Degrees ($^\circ$), and Meters per Second ($m/s$).
 *
 * @param store Redux store instance holding [RobotState].
 * @param visionIO Limelight or PhotonVision camera IO instance ([VisionIO]).
 * @param swerveIO Physical CTRE swerve hardware IO ([SwerveHardwareIO]).
 * @param isSimulation `true` when running in WPILib simulation mode.
 *
 * @see VisionTracker
 * @see FrcLimelightIO
 * @see SwerveHardwareIO
 */
class FrcVisionTracker(
    private val store: Store,
    val visionIO: VisionIO?,
    private val swerveIO: com.areslib.hardware.drive.SwerveHardwareIO?,
    private val isSimulation: Boolean,
    // Phoenix 6 uses its own monotonic timebase for vision rewind. Supplying an FPGA
    // timestamp directly can place the observation in the wrong estimator epoch.
    private val estimatorTimeSecondsProvider: () -> Double = { com.ctre.phoenix6.Utils.getCurrentTimeSeconds() },
    private val fpgaToEstimatorTimeSeconds: (Double) -> Double = { com.ctre.phoenix6.Utils.fpgaToCurrentTime(it) },
    private val isDisabledProvider: () -> Boolean = { edu.wpi.first.wpilibj.DriverStation.isDisabled() }
) : VisionTracker {

    /** Vision inputs container polled each loop frame. */
    val visionInputs = VisionIOInputs()

    private var _lastVisionStatus: String = "INIT"
    private val recentSourceIds = arrayOfNulls<String>(8)
    private val recentFrameIds = LongArray(8) { Long.MIN_VALUE }
    private val recentTimestampsMs = LongArray(8) { Long.MIN_VALUE }
    private var recoveryCount = 0
    private var recoveryX = 0.0
    private var recoveryY = 0.0
    private var recoverySin = 0.0
    private var recoveryCos = 0.0
    private var stationarySinceMs = 0L
    private var recoveryStartedMs = 0L
    private val historicalPose = DoubleArray(3)

    /** Allows calibration to observe camera frames without contaminating odometry-only routes. */
    var fusionEnabled: Boolean = true

    /** Human-readable status string describing active vision filter state (`"ACCEPTED"`, `"REJECTED_FAR"`, `"REJECTED_AMBIGUOUS"`, `"NO TARGET"`, `"OFFLINE"`). */
    override val lastVisionStatus: String
        get() = _lastVisionStatus

    /** True if vision hardware is connected and producing valid telemetry. */
    override val isConnected: Boolean
        get() = visionIO != null && visionInputs.isConnected

    /**
     * Executes 50Hz vision update: passes chassis gyro orientation to camera, reads AprilTag measurements, filters outliers, and feeds observations to CTRE swerve pose estimator.
     *
     * @param timestampMs System timestamp in milliseconds ($ms$).
     */
    override fun update(timestampMs: Long) {

        visionIO?.let { io ->
            val drive = store.state.drive
            val disabled = isDisabledProvider()
            // MegaTag2 needs the field-relative estimator heading. Raw Pigeon yaw can
            // differ after CTRE resetPose() applies an odometry heading offset.
            val yaw = Math.toDegrees(drive.poseEstimator.estimatedPoseHeading)
            val driveSignalsValid = drive.measuredMotionValid && drive.imuMeasurementsValid &&
                yaw.isFinite()
            io.setImuMode(if (disabled) DISABLED_IMU_MODE else ENABLED_IMU_MODE)
            if (driveSignalsValid) {
                io.setOrientation(
                    yawDegrees = yaw,
                    yawRateDegPerSec = Math.toDegrees(drive.measuredAngularVelocityRadiansPerSecond),
                    pitchDegrees = drive.pitchDegrees,
                    pitchRateDegPerSec = 0.0,
                    rollDegrees = drive.rollDegrees,
                    rollRateDegPerSec = 0.0,
                    linearVelocityMps = Math.hypot(
                        drive.measuredFieldXVelocityMetersPerSecond,
                        drive.measuredFieldYVelocityMetersPerSecond
                    )
                )
            }
            io.updateInputs(visionInputs)
            if (!driveSignalsValid) {
                stationarySinceMs = 0L
                resetRecovery()
                _lastVisionStatus = "REJECTED_DRIVE_SIGNALS"
                RobotStatusTracker.visionConnected = visionInputs.isConnected
                return@let
            }
            if (visionInputs.measurements.isNotEmpty()) {
                var acceptedCount = 0
                var rejectedCount = 0
                var staleCount = 0
                var recoverySnapped = false
                var residualRejected = false
                val measuredLinearSpeed = kotlin.math.hypot(
                    drive.measuredFieldXVelocityMetersPerSecond,
                    drive.measuredFieldYVelocityMetersPerSecond
                )
                val stationary = measuredLinearSpeed < store.state.tuning.recovery.stolenRobotVelocityThreshold &&
                    kotlin.math.abs(drive.measuredAngularVelocityRadiansPerSecond) <
                    store.state.tuning.recovery.stolenRobotAngularVelocityThreshold
                stationarySinceMs = when {
                    !stationary -> 0L
                    stationarySinceMs == 0L -> timestampMs
                    else -> stationarySinceMs
                }
                val recoveryAllowed = fusionEnabled && (disabled ||
                    (stationary && timestampMs - stationarySinceMs >= 500L))
                if (!recoveryAllowed) resetRecovery()
                for (measurement in visionInputs.measurements) {
                    if (!isFreshFrame(measurement, timestampMs)) {
                        staleCount++
                        continue
                    }
                if (recoveryAllowed && considerRecovery(measurement, timestampMs, drive)) {
                    recoverySnapped = true
                    acceptedCount++
                    continue
                }
                // Distance-based outlier rejection: skip fusion for far/ambiguous tags.
                // Use full euclidean target-space distance; tag-normal depth (z) alone would
                // let an off-axis robot at (x=5, z=1) pass the 6 m filter.
                val ts = measurement.robotPoseTargetSpace
                val targetSpaceDistance = kotlin.math.hypot(kotlin.math.hypot(ts.x, ts.y), ts.z)
                val distance = when {
                    measurement.averageTagDistanceMeters >= 0.0 -> measurement.averageTagDistanceMeters
                    targetSpaceDistance > MIN_VALID_TARGET_RANGE_METERS -> targetSpaceDistance
                    else -> Double.NaN
                }
                val filterConfig = store.state.vision.filterConfig
                val timestampSec = measurementTimestampSeconds(measurement, timestampMs)
                val hasHistoricalPose = try {
                    swerveIO?.samplePoseAt(timestampSec, historicalPose) == true
                } catch (_: Throwable) {
                    false
                }
                val referenceX = if (hasHistoricalPose) historicalPose[0] else drive.poseEstimator.estimatedPoseX
                val referenceY = if (hasHistoricalPose) historicalPose[1] else drive.poseEstimator.estimatedPoseY
                val referenceHeading = if (hasHistoricalPose) historicalPose[2] else drive.poseEstimator.estimatedPoseHeading
                val translationResidual = kotlin.math.hypot(
                    measurement.targetPose.x - referenceX,
                    measurement.targetPose.y - referenceY
                )
                val passesNormalResidualGate = translationResidual <= MAX_NORMAL_FUSION_RESIDUAL_METERS
                val passesCommonFilter = VisionOutlierFilter.isValid(
                    config = filterConfig,
                    measurement = measurement,
                    robotHeadingRad = referenceHeading,
                    robotPoseX = referenceX,
                    robotPoseY = referenceY,
                    angularVelocityRadPerSec = drive.measuredAngularVelocityRadiansPerSecond,
                    linearAccelXG = drive.xAccelerationG,
                    linearAccelYG = drive.yAccelerationG,
                    linearAccelZG = drive.zAccelerationG
                )
                if (fusionEnabled && !isSimulation && swerveIO != null && distance < MAX_TARGET_RANGE_METERS &&
                    passesCommonFilter && passesNormalResidualGate) {
                    try {
                        val pose = com.areslib.math.geometry.Pose2d(
                            measurement.targetPose.translation.x,
                            measurement.targetPose.translation.y,
                            com.areslib.math.geometry.Rotation2d(measurement.targetPose.rotation.z)
                        )
                        val stdDevX = validStdDevOrFallback(measurement.stdDevXMeters, 0.7)
                        val stdDevY = validStdDevOrFallback(measurement.stdDevYMeters, 0.7)
                        val headingFallback = if (measurement.solverType == VisionSolverType.MEGATAG2) 1.0e6 else 0.35
                        val stdDevHeading = validStdDevOrFallback(measurement.stdDevHeadingRadians, headingFallback)
                        swerveIO.addVisionMeasurement(
                            pose,
                            timestampSec,
                            stdDevX,
                            stdDevY,
                            stdDevHeading
                        )
                        acceptedCount++
                    } catch (e: Throwable) {
                        System.err.println("FrcSwerveRobot: Failed to feed vision to SwerveDrivetrain: ${e.message}")
                        rejectedCount++
                    }
                } else if (!distance.isFinite() || distance >= MAX_TARGET_RANGE_METERS ||
                    !passesCommonFilter || !passesNormalResidualGate) {
                    rejectedCount++
                    residualRejected = residualRejected || !passesNormalResidualGate
                }
                }
                store.dispatch(RobotAction.VisionMeasurementsReceived(
                    visionInputs.measurements,
                    timestampMs,
                    null,
                    // The accepted measurement above is already consumed by CTRE's
                    // estimator. Preserve it in Redux for diagnostics without fusing the
                    // same correlated observation into the ARES EKF again.
                    fuseIntoPoseEstimator = false
                ))
                _lastVisionStatus = when {
                    !fusionEnabled -> "FUSION_DISABLED"
                    recoverySnapped -> "RESEED_SNAP"
                    acceptedCount > 0 || (isSimulation && rejectedCount == 0 && staleCount == 0) -> "ACCEPTED"
                    staleCount == visionInputs.measurements.size -> "STALE_FRAME"
                    rejectedCount > 0 -> if (residualRejected) "REJECTED_RESIDUAL" else "REJECTED_FILTERED"
                    else -> "NO TARGET"
                }
            } else {
                _lastVisionStatus = "NO TARGET"
            }
            RobotStatusTracker.visionConnected = visionInputs.isConnected
        } ?: run {
            RobotStatusTracker.visionConnected = false
            _lastVisionStatus = "OFFLINE"
        }
    }

    private fun validStdDevOrFallback(value: Double, fallback: Double): Double =
        if (value.isFinite() && value > 0.0) value else fallback

    private fun measurementTimestampSeconds(measurement: VisionMeasurement, nowMs: Long): Double {
        if (measurement.captureTimestampMicros > 0L) {
            val converted = try {
                fpgaToEstimatorTimeSeconds(measurement.captureTimestampMicros / 1_000_000.0)
            } catch (_: Throwable) {
                Double.NaN
            }
            if (converted.isFinite()) return converted
        }
        val latencyMs = (nowMs - measurement.timestampMs).coerceIn(0L, 1_000L)
        return estimatorTimeSecondsProvider() - latencyMs / 1_000.0
    }

    private fun considerRecovery(
        measurement: VisionMeasurement,
        timestampMs: Long,
        drive: com.areslib.state.DriveState
    ): Boolean {
        if (!measurement.hasRecoveryPose || swerveIO == null) {
            resetRecovery()
            return false
        }
        val candidate3d = measurement.recoveryPose
        val filterConfig = store.state.vision.filterConfig
        val targetSpace = measurement.robotPoseTargetSpace
        val targetSpaceRange = kotlin.math.sqrt(
            targetSpace.x * targetSpace.x + targetSpace.y * targetSpace.y + targetSpace.z * targetSpace.z
        )
        val targetRange = when {
            measurement.averageTagDistanceMeters >= 0.0 -> measurement.averageTagDistanceMeters
            targetSpaceRange > MIN_VALID_TARGET_RANGE_METERS -> targetSpaceRange
            else -> Double.NaN
        }
        val dynamicZ = if (drive.zAccelerationG == 0.0) 0.0 else drive.zAccelerationG - 1.0
        val shockMagnitude = kotlin.math.sqrt(
            drive.xAccelerationG * drive.xAccelerationG +
                drive.yAccelerationG * drive.yAccelerationG + dynamicZ * dynamicZ
        )
        val plausible = (!measurement.recoveryAmbiguityAvailable ||
            (measurement.recoveryAmbiguity.isFinite() &&
                measurement.recoveryAmbiguity <= filterConfig.maxAmbiguity)) &&
            (filterConfig.allowedTagIds.isEmpty() || measurement.tagId in filterConfig.allowedTagIds) &&
            candidate3d.x.isFinite() && candidate3d.y.isFinite() && candidate3d.rotation.z.isFinite() &&
            targetRange.isFinite() && targetRange <= MAX_TARGET_RANGE_METERS &&
            kotlin.math.abs(drive.measuredAngularVelocityRadiansPerSecond) <= filterConfig.maxAngularVelocityRadPerSec &&
            shockMagnitude.isFinite() && shockMagnitude <= filterConfig.maxAccelerationG &&
            VisionOutlierFilter.isPoseWithinFieldBounds(filterConfig, candidate3d)
        if (!plausible) {
            resetRecovery()
            return false
        }

        val estimate = store.state.drive.poseEstimator.estimatedPose
        val translationError = kotlin.math.hypot(candidate3d.x - estimate.x, candidate3d.y - estimate.y)
        val headingError = kotlin.math.abs(wrapAngle(candidate3d.rotation.z - estimate.heading.radians))
        if (translationError <= 0.5 && headingError <= filterConfig.maxRotationDeviationRad) {
            resetRecovery()
            return false
        }

        if (recoveryCount > 0) {
            val meanX = recoveryX / recoveryCount
            val meanY = recoveryY / recoveryCount
            val meanHeading = kotlin.math.atan2(recoverySin, recoveryCos)
            if (kotlin.math.hypot(candidate3d.x - meanX, candidate3d.y - meanY) > 0.35 ||
                kotlin.math.abs(wrapAngle(candidate3d.rotation.z - meanHeading)) > Math.toRadians(20.0)) {
                resetRecovery()
            }
        }

        recoveryX += candidate3d.x
        recoveryY += candidate3d.y
        recoverySin += kotlin.math.sin(candidate3d.rotation.z)
        recoveryCos += kotlin.math.cos(candidate3d.rotation.z)
        if (recoveryCount == 0) recoveryStartedMs = timestampMs
        recoveryCount++
        val baseRequired = store.state.tuning.recovery.stolenRobotRejectionThreshold.toInt().coerceAtLeast(1)
        val required = if (measurement.tagCount >= 2) baseRequired else baseRequired * 2
        if (recoveryCount < required || timestampMs - recoveryStartedMs < MIN_RECOVERY_CONSENSUS_MS) return false

        val snapPose = Pose2d(
            recoveryX / recoveryCount,
            recoveryY / recoveryCount,
            Rotation2d(kotlin.math.atan2(recoverySin, recoveryCos))
        )
        swerveIO.seedPose(snapPose)
        store.dispatch(
            RobotAction.PoseUpdate(
                xMeters = snapPose.x,
                yMeters = snapPose.y,
                headingRadians = snapPose.heading.radians,
                timestampMs = timestampMs,
                pitchDegrees = drive.pitchDegrees,
                rollDegrees = drive.rollDegrees,
                xAccelerationG = drive.xAccelerationG,
                yAccelerationG = drive.yAccelerationG,
                zAccelerationG = drive.zAccelerationG,
                angularVelocityRadiansPerSecond = drive.measuredAngularVelocityRadiansPerSecond,
                xVelocityMetersPerSecond = drive.measuredFieldXVelocityMetersPerSecond,
                yVelocityMetersPerSecond = drive.measuredFieldYVelocityMetersPerSecond,
                motionMeasurementsValid = drive.measuredMotionValid,
                imuMeasurementsValid = drive.imuMeasurementsValid,
                isExternalEstimate = true
            )
        )
        resetRecovery()
        return true
    }

    private fun resetRecovery() {
        recoveryCount = 0
        recoveryX = 0.0
        recoveryY = 0.0
        recoverySin = 0.0
        recoveryCos = 0.0
        recoveryStartedMs = 0L
    }

    private fun isFreshFrame(measurement: VisionMeasurement, nowMs: Long): Boolean {
        if (measurement.timestampMs <= 0L || measurement.timestampMs > nowMs + 50L ||
            nowMs - measurement.timestampMs > 1_000L) {
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

    private companion object {
        const val MAX_NORMAL_FUSION_RESIDUAL_METERS = 1.0
        const val MAX_TARGET_RANGE_METERS = 6.0
        const val MIN_RECOVERY_CONSENSUS_MS = 500L
        const val MIN_VALID_TARGET_RANGE_METERS = 0.05
        const val DISABLED_IMU_MODE = 1
        const val ENABLED_IMU_MODE = 4
    }
}

