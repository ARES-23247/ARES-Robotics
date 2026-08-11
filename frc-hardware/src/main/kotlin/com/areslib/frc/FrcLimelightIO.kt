package com.areslib.frc

import com.areslib.hardware.vision.VisionIO
import com.areslib.hardware.vision.VisionIOInputs
import com.areslib.state.VisionMeasurement
import com.areslib.state.VisionSolverType
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Translation3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Quaternion
import edu.wpi.first.networktables.NetworkTableInstance

/**
 * NetworkTables 4 (NT4) vision IO wrapper for Limelight 3/3G/4 camera sensors on FRC platforms.
 *
 * MegaTag2 is the normal CTRE fusion observation. MegaTag1 is retained as a
 * separate, independent-yaw pose exclusively for conservative stationary recovery.
 *
 * ### Physical Units & Coordinates:
 * - Target Space Depth ($Z$): Meters ($m$).
 * - Rotation Yaw/Pitch/Roll: Radians ($rad$) internally, converted from NetworkTables Degrees ($^\circ$).
 * - Timestamp Latency Correction: Milliseconds ($ms$) from `botpose[6]`.
 *
 * @param tableName Limelight NetworkTables table name (default `"limelight"`).
 * @param cameraPoses List of 3D camera mounting offsets ([Pose3d]) relative to robot center.
 * @param defaultPipeline Active pipeline index (default 0).
 * @param imuMode Limelight IMU mode (default 4 = `INTERNAL_EXTERNAL_ASSIST`).
 *
 * @see VisionIO
 * @see VisionIOInputs
 * @see VisionMeasurement
 */
class FrcLimelightIO(
    val tableName: String = "limelight",
    override val cameraPoses: List<Pose3d> = listOf(Pose3d(Translation3d(0.18, 0.0, 0.0), Rotation3d(0.0, 0.0, 0.0))),
    val defaultPipeline: Int = 0,
    val imuMode: Int = 4 // 4 = INTERNAL_EXTERNAL_ASSIST (recommended for LL3G/LL4), 0 = EXTERNAL_ONLY
) : VisionIO {

    private val table = NetworkTableInstance.getDefault().getTable(tableName)
    private val botposeSub = table.getDoubleArrayTopic("botpose_wpiblue").subscribe(DoubleArray(0))
    private val botposeMt2Sub = table.getDoubleArrayTopic("botpose_wpiblue_mt2").subscribe(DoubleArray(0))
    private val tvSub = table.getIntegerTopic("tv").subscribe(0)
    private val botposeTargetSpaceSub = table.getDoubleArrayTopic("botpose_targetspace").subscribe(DoubleArray(0))
    private val tidSub = table.getIntegerTopic("tid").subscribe(-1)
    private val heartbeatSub = table.getDoubleTopic("hb").subscribe(Double.NaN)
    private val stdDevsSub = table.getDoubleArrayTopic("stddevs").subscribe(DoubleArray(0))
    
    private val orientationPub = table.getDoubleArrayTopic("orientation_megatag2").publish()

    private var lastHeartbeat = Long.MIN_VALUE
    private var lastHeartbeatChangeMs = Long.MIN_VALUE
    private var lastEmittedFrameId = Long.MIN_VALUE
    
    // Pre-allocated buffers to prevent GC
    private val scratchBotpose = DoubleArray(7)
    private val scratchOrientation = DoubleArray(6)
    
    // Single pre-allocated instance for Zero-GC
    private val cachedMeasurement = VisionMeasurement()
    private val cachedMeasurementList = java.util.Collections.singletonList(cachedMeasurement)

    init {
        // Enforce match-ready settings to NetworkTables on startup
        try {
            table.getIntegerTopic("pipeline").publish().set(defaultPipeline.toLong())
            table.getIntegerTopic("ledMode").publish().set(1L) // 1 = Force Off
            table.getIntegerTopic("stream").publish().set(0L)  // 0 = Standard Stream
            table.getIntegerTopic("imuMode").publish().set(imuMode.toLong())
        } catch (e: Exception) {
            System.err.println("FrcLimelightIO: Failed to write startup configuration: ${e.message}")
        }
    }

    /**
     * Publishes robot orientation payload to Limelight for MegaTag2 gyro-assisted pose estimation.
     *
     * @param yawDegrees Robot yaw heading in degrees ($^\circ$).
     * @param yawRateDegPerSec Robot angular velocity in degrees per second ($^\circ/s$).
     * @param pitchDegrees Robot pitch angle in degrees ($^\circ$).
     * @param pitchRateDegPerSec Robot pitch rate in degrees per second ($^\circ/s$).
     * @param rollDegrees Robot roll angle in degrees ($^\circ$).
     * @param rollRateDegPerSec Robot roll rate in degrees per second ($^\circ/s$).
     * @param linearVelocityMps Total linear chassis velocity in meters per second ($m/s$).
     */
    override fun setOrientation(
        yawDegrees: Double, yawRateDegPerSec: Double,
        pitchDegrees: Double, pitchRateDegPerSec: Double,
        rollDegrees: Double, rollRateDegPerSec: Double,
        linearVelocityMps: Double
    ) {
        scratchOrientation[0] = yawDegrees
        scratchOrientation[1] = yawRateDegPerSec
        scratchOrientation[2] = pitchDegrees
        scratchOrientation[3] = pitchRateDegPerSec
        scratchOrientation[4] = rollDegrees
        scratchOrientation[5] = rollRateDegPerSec
        
        orientationPub.set(scratchOrientation)
    }

    /**
     * Polled update cycle extracting latest AprilTag vision measurements into [inputs].
     *
     * @param inputs Pre-allocated [VisionIOInputs] target container.
     */
    override fun updateInputs(inputs: VisionIOInputs) {

        inputs.cameraPoses = cameraPoses
        
        val nowMs = com.areslib.util.RobotClock.currentTimeMillis()
        val tv = tvSub.get()
        val heartbeatRaw = heartbeatSub.get()
        val heartbeat = if (heartbeatRaw.isFinite()) heartbeatRaw.toLong() else Long.MIN_VALUE
        if (heartbeat != Long.MIN_VALUE && heartbeat != lastHeartbeat) {
            lastHeartbeat = heartbeat
            lastHeartbeatChangeMs = nowMs
        }
        val megaTag1Botpose = botposeSub.get()
        val megaTag2Botpose = botposeMt2Sub.get()
        val usingMegaTag2 = megaTag2Botpose.size >= 6
        val botpose = if (usingMegaTag2) megaTag2Botpose else megaTag1Botpose

        val heartbeatConnected = lastHeartbeatChangeMs != Long.MIN_VALUE && nowMs - lastHeartbeatChangeMs <= 1_000L
        inputs.isConnected = if (lastHeartbeatChangeMs != Long.MIN_VALUE) {
            heartbeatConnected
        } else {
            // Compatibility fallback for older firmware that does not publish hb.
            botpose.isNotEmpty()
        }
        
        if (inputs.isConnected && tv == 1L && botpose.size >= 6) {
            if (heartbeat != Long.MIN_VALUE && heartbeat == lastEmittedFrameId) {
                inputs.measurements = emptyList()
                return
            }
            // Limelight latency (ms) is typically index 6.
            val latencyMs = if (botpose.size > 6) botpose[6] else 0.0
            val tagCount = if (botpose.size > 7 && botpose[7].isFinite()) {
                botpose[7].toInt().coerceAtLeast(1)
            } else {
                1
            }
            val timestampMs = nowMs - latencyMs.toLong()
            
            // Limelight's botpose_wpiblue array does not contain single-tag ambiguity.
            // Index 10 represents Average Target Area (percent), which is typically > 0.15% 
            // for good close-up tag readings, causing false outlier rejects. We set ambiguity 
            // to a stable constant (0.02) as multitag pose estimations are extremely stable.
            val ambiguity = 0.02
            
            val targetPose = poseFromBotpose(botpose)
            val recoveryPose = if (megaTag1Botpose.size >= 6) poseFromBotpose(megaTag1Botpose) else Pose3d()
            
            // Populate target-space pose for alignment controllers
            val targetSpace = botposeTargetSpaceSub.get()
            val robotPoseTargetSpace = if (targetSpace.size >= 6) {
                Pose3d(
                    Translation3d(targetSpace[0], targetSpace[1], targetSpace[2]),
                    Rotation3d(Math.toRadians(targetSpace[3]), Math.toRadians(targetSpace[4]), Math.toRadians(targetSpace[5]))
                )
            } else {
                Pose3d()
            }
            val tagId = tidSub.get().toInt()
            
            cachedMeasurement.timestampMs = timestampMs
            cachedMeasurement.targetPose = targetPose
            cachedMeasurement.recoveryPose = recoveryPose
            cachedMeasurement.hasRecoveryPose = megaTag1Botpose.size >= 6
            cachedMeasurement.tagId = tagId
            cachedMeasurement.tagCount = tagCount
            cachedMeasurement.ambiguity = ambiguity
            cachedMeasurement.robotPoseTargetSpace = robotPoseTargetSpace
            cachedMeasurement.sourceId = tableName
            cachedMeasurement.frameId = if (heartbeat != Long.MIN_VALUE) heartbeat else timestampMs
            cachedMeasurement.solverType = if (usingMegaTag2) VisionSolverType.MEGATAG2 else VisionSolverType.MEGATAG1
            cachedMeasurement.latencyMs = latencyMs
            applyObservationStdDevs(cachedMeasurement, usingMegaTag2, botpose, tagCount)
            lastEmittedFrameId = cachedMeasurement.frameId
            
            inputs.measurements = cachedMeasurementList
        } else {
            inputs.measurements = emptyList()
        }
    }

    private fun poseFromBotpose(botpose: DoubleArray): Pose3d {
        val roll = Math.toRadians(botpose[3])
        val pitch = Math.toRadians(botpose[4])
        val yaw = Math.toRadians(botpose[5])
        val cr = Math.cos(roll * 0.5)
        val sr = Math.sin(roll * 0.5)
        val cp = Math.cos(pitch * 0.5)
        val sp = Math.sin(pitch * 0.5)
        val cy = Math.cos(yaw * 0.5)
        val sy = Math.sin(yaw * 0.5)
        return Pose3d(
            Translation3d(botpose[0], botpose[1], botpose[2]),
            Rotation3d(
                Quaternion(
                    cr * cp * cy + sr * sp * sy,
                    sr * cp * cy - cr * sp * sy,
                    cr * sp * cy + sr * cp * sy,
                    cr * cp * sy - sr * sp * cy
                )
            )
        )
    }

    private fun applyObservationStdDevs(
        measurement: VisionMeasurement,
        usingMegaTag2: Boolean,
        botpose: DoubleArray,
        tagCount: Int
    ) {
        val reported = stdDevsSub.get()
        val offset = if (usingMegaTag2) 6 else 0
        val reportedX = reported.getOrNull(offset)
        val reportedY = reported.getOrNull(offset + 1)
        val reportedHeadingDegrees = reported.getOrNull(offset + 5)

        if (reportedX != null && reportedY != null && reportedHeadingDegrees != null &&
            reportedX.isFinite() && reportedX > 0.0 && reportedY.isFinite() && reportedY > 0.0 &&
            reportedHeadingDegrees.isFinite() && reportedHeadingDegrees > 0.0) {
            measurement.stdDevXMeters = reportedX
            measurement.stdDevYMeters = reportedY
            measurement.stdDevHeadingRadians = if (usingMegaTag2) 1.0e6 else Math.toRadians(reportedHeadingDegrees)
            return
        }

        val averageTagDistance = botpose.getOrNull(9)?.takeIf { it.isFinite() && it >= 0.0 } ?: 2.0
        val tagScale = 1.0 / kotlin.math.sqrt(tagCount.coerceAtLeast(1).toDouble())
        val translationStdDev = (0.12 + 0.04 * averageTagDistance * averageTagDistance) * tagScale
        measurement.stdDevXMeters = translationStdDev
        measurement.stdDevYMeters = translationStdDev
        measurement.stdDevHeadingRadians = if (usingMegaTag2) {
            1.0e6
        } else {
            Math.toRadians(5.0 + 2.0 * averageTagDistance * averageTagDistance) * tagScale
        }
    }
}
