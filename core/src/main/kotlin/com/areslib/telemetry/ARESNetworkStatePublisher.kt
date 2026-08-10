package com.areslib.telemetry

import com.areslib.state.RobotState
import com.areslib.control.safety.BrownoutGuard

/**
 * Serializes and publishes the complete RobotState to an ITelemetry interface.
 * Covers drive, superstructure, vision, and optional gamepad inputs so that
 * every robot built with ARESLib gets full logging and replay for free.
 */
class ARESNetworkStatePublisher(private val telemetry: ITelemetry) {

    private val EMPTY_DOUBLE_ARRAY = DoubleArray(0)
    private val covarianceArray = DoubleArray(3)
    
    private var lastPublishedPath: com.areslib.pathing.Path? = null
    private var cachedPathPoints: DoubleArray = EMPTY_DOUBLE_ARRAY

    /**
     * publish declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun publish(
        state: RobotState,
        gamepad1: GamepadState? = null,
        gamepad2: GamepadState? = null,
        dtSeconds: Double? = null,
        batteryVoltage: Double? = null,
        brownoutGuard: com.areslib.control.safety.BrownoutGuard? = null
    ) {
        // ── Drive ──
        // Raw Pinpoint Odometry
        telemetry.putNumber("Drive/Odom_X", state.drive.odometryX)
        telemetry.putNumber("Drive/Odom_Y", state.drive.odometryY)
        telemetry.putNumber("Drive/Odom_Heading", state.drive.odometryHeading)

        // Fused EKF Estimated Pose
        telemetry.putNumber("Drive/Pose_X", state.drive.poseEstimator.estimatedPose.x)
        telemetry.putNumber("Drive/Pose_Y", state.drive.poseEstimator.estimatedPose.y)
        telemetry.putNumber("Drive/Drive_Heading", state.drive.poseEstimator.estimatedPose.heading.radians)
        telemetry.putDoubleArray("ARES/EstimatedPose", doubleArrayOf(
            state.drive.poseEstimator.estimatedPose.x,
            state.drive.poseEstimator.estimatedPose.y,
            state.drive.poseEstimator.estimatedPose.heading.radians
        ))
        telemetry.putNumber("ARES/EstimatedPose/0", state.drive.poseEstimator.estimatedPose.x)
        telemetry.putNumber("ARES/EstimatedPose/1", state.drive.poseEstimator.estimatedPose.y)
        telemetry.putNumber("ARES/EstimatedPose/2", state.drive.poseEstimator.estimatedPose.heading.radians)

        telemetry.putNumber("Drive/Velocity_X", state.drive.xVelocityMetersPerSecond)
        telemetry.putNumber("Drive/Velocity_Y", state.drive.yVelocityMetersPerSecond)
        telemetry.putNumber("Drive/Velocity_Omega", state.drive.angularVelocityRadiansPerSecond)

        // ── EKF Covariance Diagonals ──
        val cov = state.drive.poseEstimator.covariance
        covarianceArray[0] = cov.m00
        covarianceArray[1] = cov.m11
        covarianceArray[2] = cov.m22
        telemetry.putDoubleArray("Robot/Odometry/Covariance", covarianceArray)

        // ── AdvantageScope 3D Pose ──
        telemetry.logPose3d("Robot/Pose3d", state.drive.poseEstimator.estimatedPose)

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
            telemetry.putDoubleArray("Vision/PoseArray", EMPTY_DOUBLE_ARRAY)
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
            cachedPathPoints = EMPTY_DOUBLE_ARRAY
            telemetry.putDoubleArray("Path/Points", EMPTY_DOUBLE_ARRAY)
        }

        // ── Gamepad 1 ──
        telemetry.logGamepad("Gamepad1", gamepad1 ?: GamepadState())

        // ── Gamepad 2 ──
        telemetry.logGamepad("Gamepad2", gamepad2 ?: GamepadState())

        // ── Indicator Lights ──
        for (i in state.superstructure.indicatorLights.entries) {
            telemetry.putNumber("Superstructure/IndicatorLight/${i.key}", i.value)
        }
        
        // Trigger batch flush of the telemetry values published in this frame
        telemetry.update()
    }

    /**
     * publishTopology declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun publishTopology(topologyJson: String) {
        telemetry.putString("Topology/HardwareMap", topologyJson)
        telemetry.update()
    }

    /**
     * publishCalibration declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun publishCalibration(
        isActive: Boolean,
        gyroHeading: Double,
        tagIndex: Int,
        cameraIndex: Int,
        cameraToTag: DoubleArray,
        tagFieldPosition: DoubleArray = doubleArrayOf(Double.NaN, Double.NaN, Double.NaN)
    ) {
        telemetry.putBoolean("Calibration/IsActive", isActive)
        telemetry.putNumber("Calibration/GyroHeading", gyroHeading)
        telemetry.putNumber("Calibration/TagIndex", tagIndex.toDouble())
        telemetry.putNumber("Calibration/CameraIndex", cameraIndex.toDouble())
        telemetry.putDoubleArray("Calibration/CameraToTag", cameraToTag)
        telemetry.putDoubleArray("Calibration/TagField", tagFieldPosition)
        telemetry.update()
    }
}
