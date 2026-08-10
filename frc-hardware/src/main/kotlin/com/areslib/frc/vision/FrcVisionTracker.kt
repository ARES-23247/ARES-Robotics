package com.areslib.frc.vision

import com.areslib.action.RobotAction
import com.areslib.hardware.vision.VisionIO
import com.areslib.hardware.vision.VisionIOInputs
import com.areslib.hardware.vision.VisionOutlierFilter
import com.areslib.Store
import com.areslib.subsystem.VisionTracker
import com.areslib.telemetry.RobotStatusTracker

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
    private val isSimulation: Boolean
) : VisionTracker {

    /** Vision inputs container polled each loop frame. */
    val visionInputs = VisionIOInputs()

    private var _lastVisionStatus: String = "INIT"

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
            val yawRate = swerveIO?.yawRateDegreesPerSecond ?: Math.toDegrees(drive.angularVelocityRadiansPerSecond)
            val yaw = swerveIO?.rawGyroYawDegrees ?: Math.toDegrees(drive.odometryHeading)
            
            io.setOrientation(
                yawDegrees = yaw,
                yawRateDegPerSec = yawRate,
                pitchDegrees = drive.pitchDegrees,
                pitchRateDegPerSec = 0.0,
                rollDegrees = drive.rollDegrees,
                rollRateDegPerSec = 0.0,
                linearVelocityMps = Math.hypot(drive.xVelocityMetersPerSecond, drive.yVelocityMetersPerSecond)
            )
            io.updateInputs(visionInputs)
            if (visionInputs.measurements.isNotEmpty()) {
                val measurement = visionInputs.measurements[0]
                // Distance-based outlier rejection: skip fusion for far/ambiguous tags.
                // Use full euclidean target-space distance; tag-normal depth (z) alone would
                // let an off-axis robot at (x=5, z=1) pass the 6 m filter.
                val ts = measurement.robotPoseTargetSpace
                val distance = kotlin.math.hypot(kotlin.math.hypot(ts.x, ts.y), ts.z)
                val ambiguity = measurement.ambiguity
                val filterConfig = store.state.vision.filterConfig
                val passesCommonFilter = VisionOutlierFilter.isValid(
                    config = filterConfig,
                    measurement = measurement,
                    robotHeadingRad = drive.poseEstimator.estimatedPoseHeading,
                    robotPose = drive.poseEstimator.estimatedPose,
                    angularVelocityRadPerSec = drive.measuredAngularVelocityRadiansPerSecond,
                    linearAccelXG = drive.xAccelerationG,
                    linearAccelYG = drive.yAccelerationG,
                    linearAccelZG = drive.zAccelerationG
                )
                if (!isSimulation && swerveIO != null && distance < 6.0 && passesCommonFilter) {
                    try {
                        val pose = com.areslib.math.geometry.Pose2d(
                            measurement.targetPose.translation.x,
                            measurement.targetPose.translation.y,
                            com.areslib.math.geometry.Rotation2d(measurement.targetPose.rotation.z)
                        )
                        val latencyMs = timestampMs - measurement.timestampMs
                        val timestampSec = edu.wpi.first.wpilibj.Timer.getFPGATimestamp() - (latencyMs / 1000.0)
                        swerveIO.addVisionMeasurement(pose, timestampSec)
                    } catch (e: Throwable) {
                        System.err.println("FrcSwerveRobot: Failed to feed vision to SwerveDrivetrain: ${e.message}")
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
                    distance >= 6.0 -> "REJECTED_FAR"
                    ambiguity > filterConfig.maxAmbiguity -> "REJECTED_AMBIGUOUS"
                    !passesCommonFilter -> "REJECTED_FILTERED"
                    else -> "ACCEPTED"
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
}

