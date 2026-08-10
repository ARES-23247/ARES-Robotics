package com.areslib.telemetry

import com.areslib.control.safety.BrownoutGuard
import com.areslib.state.RobotState

/**
 * Serializes and publishes the complete RobotState to an ITelemetry interface.
 * Covers drive, superstructure, vision, and optional gamepad inputs so that
 * every robot built with ARESLib gets full logging and replay for free.
 */
class ARESNetworkStatePublisher(private val telemetry: ITelemetry) {

    private val emptyDoubleArray = DoubleArray(0)
    private val emptyGamepadState = GamepadState()
    private val covarianceArray = DoubleArray(3)
    private val estimatedPoseArray = DoubleArray(3)
    
    private var lastPublishedPath: com.areslib.pathing.Path? = null
    private var cachedPathPoints: DoubleArray = emptyDoubleArray
    private var commandCatalogRevision = Long.MIN_VALUE
    private var commandCatalogJson = "[]"

    /**
     * Publishes one immutable state snapshot and flushes the telemetry backend.
     * Reusable pose and covariance arrays avoid the largest avoidable per-frame buffers; topic
     * formatting and the backend may still allocate.
     */
    fun publish(
        state: RobotState,
        gamepad1: GamepadState? = null,
        gamepad2: GamepadState? = null,
        dtSeconds: Double? = null,
        batteryVoltage: Double? = null,
        brownoutGuard: BrownoutGuard? = null
    ) {
        // ── Drive ──
        // Raw Pinpoint Odometry
        telemetry.putNumber(TelemetryTopicConstants.DRIVE_ODOM_X, state.drive.odometryX)
        telemetry.putNumber(TelemetryTopicConstants.DRIVE_ODOM_Y, state.drive.odometryY)
        telemetry.putNumber(TelemetryTopicConstants.DRIVE_ODOM_HEADING, state.drive.odometryHeading)

        // Fused EKF Estimated Pose
        val estimatedPose = state.drive.poseEstimator.estimatedPose
        telemetry.putNumber(TelemetryTopicConstants.DRIVE_POSE_X, estimatedPose.x)
        telemetry.putNumber(TelemetryTopicConstants.DRIVE_POSE_Y, estimatedPose.y)
        telemetry.putNumber(TelemetryTopicConstants.DRIVE_POSE_HEADING, estimatedPose.heading.radians)
        telemetry.putString(
            "Drive/Pose_Source",
            if (state.drive.poseEstimateIsExternal) "EXTERNAL" else "ARES_EKF"
        )
        estimatedPoseArray[0] = estimatedPose.x
        estimatedPoseArray[1] = estimatedPose.y
        estimatedPoseArray[2] = estimatedPose.heading.radians
        telemetry.putDoubleArray("ARES/EstimatedPose", estimatedPoseArray)
        telemetry.putNumber(TelemetryTopicConstants.ESTIMATED_POSE_X, estimatedPose.x)
        telemetry.putNumber(TelemetryTopicConstants.ESTIMATED_POSE_Y, estimatedPose.y)
        telemetry.putNumber(TelemetryTopicConstants.ESTIMATED_POSE_HEADING, estimatedPose.heading.radians)

        telemetry.putNumber("Drive/Velocity_X", state.drive.xVelocityMetersPerSecond)
        telemetry.putNumber("Drive/Velocity_Y", state.drive.yVelocityMetersPerSecond)
        telemetry.putNumber("Drive/Velocity_Omega", state.drive.angularVelocityRadiansPerSecond)

        publishCommandCatalog()

        // ── EKF Covariance Diagonals ──
        val cov = state.drive.poseEstimator.covariance
        covarianceArray[0] = cov.m00
        covarianceArray[1] = cov.m11
        covarianceArray[2] = cov.m22
        telemetry.putDoubleArray("Robot/Odometry/Covariance", covarianceArray)

        // ── AdvantageScope 3D Pose ──
        telemetry.logPose3d("Robot/Pose3d", estimatedPose)

        // ── Loop Time & Diagnostics ──
        if (dtSeconds != null) {
            val loopMs = dtSeconds * 1000.0
            telemetry.putNumber("Robot/LoopTimeMs", loopMs)
            telemetry.putNumber("Profiling/LoopTime_ms", loopMs)
            if (dtSeconds > 0) {
                telemetry.putNumber("Profiling/Hz", 1.0 / dtSeconds)
            }
        }

        // ── Power / Battery ──
        if (batteryVoltage != null) {
            telemetry.putNumber("Robot/BatteryVoltage", batteryVoltage)
        }
        if (brownoutGuard != null) {
            telemetry.putNumber("Robot/BrownoutPowerScale", brownoutGuard.powerScale)
            telemetry.putString("Robot/BrownoutState", brownoutGuard.state.name)
            telemetry.putNumber("Robot/StateOfCharge", brownoutGuard.batteryPercent)
            telemetry.putNumber("Diagnostics/Power/BrownoutCount", brownoutGuard.tripCount.toDouble())
        }


        // ── Vision ──
        val primaryMeasurement = state.vision.measurements.firstOrNull()
        val hasVisionTarget = primaryMeasurement != null || state.vision.hasTarget
        telemetry.putBoolean("Vision/HasTarget", hasVisionTarget)
        telemetry.putNumber("Vision/Target_X", state.vision.targetX)
        telemetry.putNumber("Vision/Target_Y", state.vision.targetY)
        telemetry.putNumber("Vision/MeasurementCount", state.vision.measurements.size.toDouble())

        if (primaryMeasurement != null) {
            val pose = primaryMeasurement.targetPose.toPose2d()
            telemetry.logPoseArray2d("Vision/PoseArray", pose)
            telemetry.logPose2d("Vision/Pose", pose, useUnderscores = true)
            telemetry.putNumber("Vision/Primary_TagId", primaryMeasurement.tagId.toDouble())
            telemetry.putNumber("Vision/Primary_Ambiguity", primaryMeasurement.ambiguity)
        } else {
            telemetry.putDoubleArray("Vision/PoseArray", emptyDoubleArray)
            telemetry.putNumber("Vision/Pose_X", 0.0)
            telemetry.putNumber("Vision/Pose_Y", 0.0)
            telemetry.putNumber("Vision/Pose_Heading", 0.0)
            telemetry.putNumber("Vision/Primary_TagId", -1.0)
            telemetry.putNumber("Vision/Primary_Ambiguity", 1.0)
        }

        // ── Path State ──
        val path = state.pathState
        telemetry.putBoolean("Path/Active", path.activePath != null)
        telemetry.putNumber("Path/DistanceMeters", path.currentDistanceMeters)
        telemetry.putBoolean("Path/IsChained", path.isChained)
        telemetry.putBoolean("Path/DetourActive", path.detourActive)
        
        // Tuning errors
        telemetry.putNumber("Path/Error_CrossTrack", path.crossTrackErrorMeters)
        telemetry.putNumber("Path/Error_AlongTrack", path.alongTrackErrorMeters)
        telemetry.putNumber("Path/Error_Heading", path.headingErrorRadians)
        
        // EKF Drift/Diagnostics
        telemetry.putNumber("Drive/EKF_Drift_X", state.drive.ekfDriftX)
        telemetry.putNumber("Drive/EKF_Drift_Y", state.drive.ekfDriftY)
        telemetry.putNumber("Drive/Innovation_Theta", state.drive.lastInnovationTheta)
        
        val activePath = path.activePath
        if (activePath != null) {
            if (activePath !== lastPublishedPath) {
                lastPublishedPath = activePath
                val pointsList = activePath.points
                val flatPoints = DoubleArray(pointsList.size * 3)
                for (i in pointsList.indices) {
                    val pt = pointsList[i]
                    flatPoints[i * 3] = pt.pose.x
                    flatPoints[i * 3 + 1] = pt.pose.y
                    flatPoints[i * 3 + 2] = pt.pose.heading.radians
                }
                cachedPathPoints = flatPoints
            }
            telemetry.putDoubleArray("Path/Points", cachedPathPoints)
        } else {
            lastPublishedPath = null
            cachedPathPoints = emptyDoubleArray
            telemetry.putDoubleArray("Path/Points", emptyDoubleArray)
        }

        // ── Gamepad 1 ──
        telemetry.logGamepad("Gamepad1", gamepad1 ?: emptyGamepadState)

        // ── Gamepad 2 ──
        telemetry.logGamepad("Gamepad2", gamepad2 ?: emptyGamepadState)

        // ── Indicator Lights ──
        for (i in state.superstructure.indicatorLights.entries) {
            telemetry.putNumber("Superstructure/IndicatorLight/${i.key}", i.value)
        }
        
        // Trigger batch flush of the telemetry values published in this frame
        telemetry.update()
    }

    /**
     * Publishes the hardware topology JSON and flushes it immediately.
     */
    fun publishTopology(topologyJson: String) {
        telemetry.putString("Topology/HardwareMap", topologyJson)
        telemetry.update()
    }

    /** Publishes the robot's actual auto capabilities for the guided Analytics editor. */
    private fun publishCommandCatalog() {
        val revision = com.areslib.pathing.NamedCommands.catalogRevision
        if (revision != commandCatalogRevision) {
            commandCatalogRevision = revision
            val catalog = com.areslib.pathing.NamedCommands.catalog().map { descriptor ->
                mapOf(
                    "key" to descriptor.key.value,
                    "displayName" to descriptor.displayName,
                    "description" to descriptor.description,
                    "category" to descriptor.category
                )
            }
            commandCatalogJson = com.google.gson.Gson().toJson(catalog)
        }
        telemetry.putString("ARES/Auto/CommandCatalog", commandCatalogJson)
    }

    /**
     * Publishes one camera-calibration observation and flushes it immediately.
     */
    fun publishCalibration(
        isActive: Boolean,
        gyroHeading: Double,
        tagIndex: Int,
        cameraIndex: Int,
        cameraToTag: DoubleArray,
        tagFieldPosition: DoubleArray = UNKNOWN_TAG_FIELD_POSITION
    ) {
        telemetry.putBoolean("Calibration/IsActive", isActive)
        telemetry.putNumber("Calibration/GyroHeading", gyroHeading)
        telemetry.putNumber("Calibration/TagIndex", tagIndex.toDouble())
        telemetry.putNumber("Calibration/CameraIndex", cameraIndex.toDouble())
        telemetry.putDoubleArray("Calibration/CameraToTag", cameraToTag)
        telemetry.putDoubleArray("Calibration/TagField", tagFieldPosition)
        telemetry.update()
    }

    private companion object {
        val UNKNOWN_TAG_FIELD_POSITION = doubleArrayOf(Double.NaN, Double.NaN, Double.NaN)
    }
}
