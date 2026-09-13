package com.areslib.telemetry

import com.areslib.telemetry.schema.HARDWARE_TOPOLOGY_TOPIC

import com.areslib.control.safety.BrownoutGuard
import com.areslib.state.RobotState
import com.areslib.util.RobotClock

private const val MAX_SAFE_DOUBLE_INTEGER: Long = 9_007_199_254_740_991L
private const val VISION_TARGET_FRESHNESS_MS = 500L

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
    private val visionPoseArray = DoubleArray(3)
    private val gamepad1Topics = GamepadTelemetry.topics("Gamepad1")
    private val gamepad2Topics = GamepadTelemetry.topics("Gamepad2")
    private var indicatorNames = emptyArray<String>()
    private var indicatorTopics = emptyArray<String>()
    
    private var lastPublishedPath: com.areslib.pathing.Path? = null
    private var cachedPathPoints: DoubleArray = emptyDoubleArray
    private var commandCatalogRevision = Long.MIN_VALUE
    private var commandCatalogJson = "[]"
    private var frameSequence = 0L

    /**
     * Publishes one immutable state snapshot and optionally flushes the telemetry backend.
     * Reusable pose and covariance arrays avoid the largest avoidable per-frame buffers; topic
     * formatting and the backend may still allocate. Platform telemetry managers that append
     * topics after this shared publisher must pass [flush] as `false` and explicitly flush once
     * after their complete frame has been assembled.
     */
    fun publish(
        state: RobotState,
        gamepad1: GamepadState? = null,
        gamepad2: GamepadState? = null,
        dtSeconds: Double? = null,
        batteryVoltage: Double? = null,
        brownoutGuard: BrownoutGuard? = null,
        flush: Boolean = true
    ) {
        // Changes on every publication even when every physical measurement is stationary. This
        // lets remote tools distinguish an active telemetry loop from a merely open/stale socket.
        telemetry.putNumber(TelemetryTopicConstants.TELEMETRY_FRAME_SEQUENCE, frameSequence.toDouble())
        frameSequence = if (frameSequence == MAX_SAFE_DOUBLE_INTEGER) 0L else frameSequence + 1L

        // ── Drive ──
        // Raw Pinpoint Odometry
        telemetry.putNumber(TelemetryTopicConstants.DRIVE_ODOM_X, state.drive.odometryX)
        telemetry.putNumber(TelemetryTopicConstants.DRIVE_ODOM_Y, state.drive.odometryY)
        telemetry.putNumber(TelemetryTopicConstants.DRIVE_ODOM_HEADING, state.drive.odometryHeading)

        // Fused EKF Estimated Pose
        val estimator = state.drive.poseEstimator
        val estimatedX = estimator.estimatedPoseX
        val estimatedY = estimator.estimatedPoseY
        val estimatedHeading = estimator.estimatedPoseHeading
        telemetry.putNumber(TelemetryTopicConstants.DRIVE_POSE_X, estimatedX)
        telemetry.putNumber(TelemetryTopicConstants.DRIVE_POSE_Y, estimatedY)
        telemetry.putNumber(TelemetryTopicConstants.DRIVE_POSE_HEADING, estimatedHeading)
        telemetry.putString(
            "Drive/Pose_Source",
            if (state.drive.poseEstimateIsExternal) "EXTERNAL" else "ARES_EKF"
        )
        estimatedPoseArray[0] = estimatedX
        estimatedPoseArray[1] = estimatedY
        estimatedPoseArray[2] = estimatedHeading
        telemetry.putDoubleArray("ARES/EstimatedPose", estimatedPoseArray)
        telemetry.putNumber(TelemetryTopicConstants.ESTIMATED_POSE_X, estimatedX)
        telemetry.putNumber(TelemetryTopicConstants.ESTIMATED_POSE_Y, estimatedY)
        telemetry.putNumber(TelemetryTopicConstants.ESTIMATED_POSE_HEADING, estimatedHeading)

        telemetry.putNumber("Drive/Velocity_X", state.drive.xVelocityMetersPerSecond)
        telemetry.putNumber("Drive/Velocity_Y", state.drive.yVelocityMetersPerSecond)
        telemetry.putNumber("Drive/Velocity_Omega", state.drive.angularVelocityRadiansPerSecond)

        publishCommandCatalog()

        // ── EKF Covariance Diagonals ──
        covarianceArray[0] = estimator.covariance00
        covarianceArray[1] = estimator.covariance11
        covarianceArray[2] = estimator.covariance22
        telemetry.putDoubleArray("Robot/Odometry/Covariance", covarianceArray)

        // ── AdvantageScope 3D Pose ──
        telemetry.logPose3d("Robot/Pose3d", estimatedX, estimatedY, estimatedHeading)

        // ── Loop Time & Diagnostics ──
        if (dtSeconds != null) {
            val loopMs = dtSeconds * 1000.0
            val frequency = 1.0 / dtSeconds
            val valid = dtSeconds > 0.0 && loopMs.isFinite() && frequency.isFinite()
            telemetry.putNumber("Robot/LoopTimeMs", if (valid) loopMs else Double.NaN)
            telemetry.putNumber("Profiling/LoopTime_ms", if (valid) loopMs else Double.NaN)
            telemetry.putNumber("Profiling/Hz", if (valid) frequency else Double.NaN)
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
        // VisionState retains a bounded oldest-to-newest diagnostic history. Publishing the first
        // entry made the field overlay trail by almost the entire 50-sample buffer. Only the newest
        // still-fresh observation represents the current camera frame.
        val newestVisionMeasurement = state.vision.measurements.lastOrNull()
        val visionMeasurementAgeMs = if (newestVisionMeasurement == null) {
            Long.MAX_VALUE
        } else {
            val nowMs = RobotClock.currentTimeMillis()
            if (nowMs < newestVisionMeasurement.timestampMs) Long.MAX_VALUE else nowMs - newestVisionMeasurement.timestampMs
        }
        val primaryMeasurement = if (
            state.vision.hasTarget && visionMeasurementAgeMs in 0L..VISION_TARGET_FRESHNESS_MS
        ) {
            newestVisionMeasurement
        } else {
            null
        }
        val hasVisionTarget = primaryMeasurement != null
        telemetry.putBoolean("Vision/HasTarget", hasVisionTarget)
        telemetry.putNumber("Vision/Target_X", state.vision.targetX)
        telemetry.putNumber("Vision/Target_Y", state.vision.targetY)
        telemetry.putNumber("Vision/MeasurementCount", state.vision.measurements.size.toDouble())
        telemetry.putBoolean("Vision/EKF_Accepted", state.vision.lastMeasurementAccepted)
        telemetry.putString("Vision/EKF_RejectionReason", state.vision.lastRejectionReason ?: "")
        telemetry.putNumber("Vision/EKF_NIS", state.drive.poseEstimator.lastNormalizedInnovationSquared)
        telemetry.putNumber("Vision/EKF_AcceptedCount", state.vision.measurementCount.toDouble())
        telemetry.putNumber("Vision/EKF_RejectedCount", state.vision.rejectionCount.toDouble())

        if (primaryMeasurement != null) {
            val pose = primaryMeasurement.targetPose
            visionPoseArray[0] = pose.x
            visionPoseArray[1] = pose.y
            visionPoseArray[2] = pose.rotationZ
            telemetry.putDoubleArray("Vision/PoseArray", visionPoseArray)
            telemetry.putNumber("Vision/Pose_X", pose.x)
            telemetry.putNumber("Vision/Pose_Y", pose.y)
            telemetry.putNumber("Vision/Pose_Heading", pose.rotationZ)
            telemetry.putNumber("Vision/Primary_TagId", primaryMeasurement.tagId.toDouble())
            telemetry.putNumber("Vision/Primary_Ambiguity", primaryMeasurement.ambiguity)
            telemetry.putString("Vision/Primary_Source", primaryMeasurement.sourceId)
            telemetry.putString("Vision/Primary_Solver", primaryMeasurement.solverType.name)
            telemetry.putNumber("Vision/Primary_FrameId", primaryMeasurement.frameId.toDouble())
            telemetry.putNumber("Vision/Primary_LatencyMs", primaryMeasurement.latencyMs)
            telemetry.putNumber("Vision/Primary_StdDevX", primaryMeasurement.stdDevXMeters)
            telemetry.putNumber("Vision/Primary_StdDevY", primaryMeasurement.stdDevYMeters)
            telemetry.putNumber("Vision/Primary_StdDevHeading", primaryMeasurement.stdDevHeadingRadians)
        } else {
            telemetry.putDoubleArray("Vision/PoseArray", emptyDoubleArray)
            telemetry.putNumber("Vision/Pose_X", 0.0)
            telemetry.putNumber("Vision/Pose_Y", 0.0)
            telemetry.putNumber("Vision/Pose_Heading", 0.0)
            telemetry.putNumber("Vision/Primary_TagId", -1.0)
            telemetry.putNumber("Vision/Primary_Ambiguity", 1.0)
            telemetry.putString("Vision/Primary_Source", "")
            telemetry.putString("Vision/Primary_Solver", "UNKNOWN")
            telemetry.putNumber("Vision/Primary_FrameId", 0.0)
            telemetry.putNumber("Vision/Primary_LatencyMs", 0.0)
            telemetry.putNumber("Vision/Primary_StdDevX", 0.0)
            telemetry.putNumber("Vision/Primary_StdDevY", 0.0)
            telemetry.putNumber("Vision/Primary_StdDevHeading", 0.0)
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
        GamepadTelemetry.publish(telemetry, gamepad1Topics, gamepad1 ?: emptyGamepadState)

        // ── Gamepad 2 ──
        GamepadTelemetry.publish(telemetry, gamepad2Topics, gamepad2 ?: emptyGamepadState)

        // ── Indicator Lights ──
        publishIndicatorLights(state.superstructure.indicatorLights)
        
        if (flush) telemetry.update()
    }

    /** Publishes hardware topology JSON, optionally joining it to the caller-owned frame. */
    fun publishTopology(topologyJson: String, flush: Boolean = true) {
        telemetry.putString(HARDWARE_TOPOLOGY_TOPIC, topologyJson)
        if (flush) telemetry.update()
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
                    "category" to descriptor.category,
                    "requiredResources" to "0x${descriptor.requiredResources.toString(16)}"
                )
            }
            commandCatalogJson = com.google.gson.Gson().toJson(catalog)
        }
        telemetry.putString("ARES/Auto/CommandCatalog", commandCatalogJson)
    }

    /** Caches dynamic indicator keys and uses indexed lookups to avoid a Map iterator per frame. */
    private fun publishIndicatorLights(lights: Map<String, Double>) {
        var rebuildTopics = lights.size != indicatorNames.size
        if (!rebuildTopics) {
            for (i in indicatorNames.indices) {
                if (!lights.containsKey(indicatorNames[i])) {
                    rebuildTopics = true
                    break
                }
            }
        }
        if (rebuildTopics) {
            for (i in indicatorNames.indices) {
                if (!lights.containsKey(indicatorNames[i])) telemetry.putNumber(indicatorTopics[i], 0.0)
            }
            indicatorNames = lights.keys.toTypedArray()
            indicatorTopics = Array(indicatorNames.size) { index ->
                "Superstructure/IndicatorLight/${indicatorNames[index]}"
            }
        }
        for (i in indicatorNames.indices) {
            val value = lights[indicatorNames[i]] ?: continue
            telemetry.putNumber(indicatorTopics[i], value)
        }
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
