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
) : VisionIO, AutoCloseable {

    private val table = NetworkTableInstance.getDefault().getTable(tableName)
    private val botposeSub = table.getDoubleArrayTopic("botpose_wpiblue").subscribe(DoubleArray(0))
    private val botposeMt2Sub = table.getDoubleArrayTopic("botpose_orb_wpiblue").subscribe(DoubleArray(0))
    private val tvSub = table.getDoubleTopic("tv").subscribe(0.0)
    private val botposeTargetSpaceSub = table.getDoubleArrayTopic("botpose_targetspace").subscribe(DoubleArray(0))
    private val tidSub = table.getDoubleTopic("tid").subscribe(-1.0)
    private val heartbeatSub = table.getDoubleTopic("hb").subscribe(Double.NaN)
    private val stdDevsSub = table.getDoubleArrayTopic("stddevs").subscribe(DoubleArray(0))
    
    private val orientationPub = table.getDoubleArrayTopic("robot_orientation_set").publish()
    private val imuModePub = table.getDoubleTopic("imumode_set").publish()
    private val pipelinePub = table.getDoubleTopic("pipeline").publish()
    private val ledModePub = table.getDoubleTopic("ledMode").publish()
    private val streamPub = table.getDoubleTopic("stream").publish()
    private val cameraPosePub = table.getDoubleArrayTopic("camerapose_robotspace_set").publish()

    private var lastHeartbeat = Long.MIN_VALUE
    private var lastHeartbeatChangeMs = Long.MIN_VALUE
    private var lastEmittedFrameId = Long.MIN_VALUE
    private var lastPublishedImuMode = Int.MIN_VALUE
    
    // Pre-allocated buffers to prevent GC
    private val scratchOrientation = DoubleArray(6)
    private val scratchCameraPose = DoubleArray(6)
    
    // Single pre-allocated instance for Zero-GC
    private val cachedMeasurement = VisionMeasurement()
    private val cachedMeasurementList = java.util.Collections.singletonList(cachedMeasurement)

    init {
        // Enforce match-ready settings to NetworkTables on startup
        try {
            pipelinePub.set(defaultPipeline.toDouble())
            ledModePub.set(1.0) // 1 = Force Off
            streamPub.set(0.0)  // 0 = Standard Stream
            publishCameraPose()
            setImuMode(imuMode)
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

    override fun setImuMode(mode: Int) {
        if (mode == lastPublishedImuMode) return
        imuModePub.set(mode.toDouble())
        lastPublishedImuMode = mode
    }

    private fun publishCameraPose() {
        val pose = cameraPoses.firstOrNull() ?: return
        scratchCameraPose[0] = pose.x
        scratchCameraPose[1] = pose.y
        scratchCameraPose[2] = pose.z
        scratchCameraPose[3] = Math.toDegrees(pose.rotation.x)
        scratchCameraPose[4] = Math.toDegrees(pose.rotation.y)
        scratchCameraPose[5] = Math.toDegrees(pose.rotation.z)
        cameraPosePub.set(scratchCameraPose)
    }

    /**
     * Polled update cycle extracting latest AprilTag vision measurements into [inputs].
     *
     * @param inputs Pre-allocated [VisionIOInputs] target container.
     */
    override fun updateInputs(inputs: VisionIOInputs) {

        inputs.cameraPoses = cameraPoses
        
        val nowMs = com.areslib.util.RobotClock.currentTimeMillis()
        val tvSample = tvSub.getAtomic()
        val heartbeatRaw = heartbeatSub.get()
        val heartbeat = if (heartbeatRaw.isFinite()) heartbeatRaw.toLong() else Long.MIN_VALUE
        if (heartbeat != Long.MIN_VALUE && heartbeat != lastHeartbeat) {
            lastHeartbeat = heartbeat
            lastHeartbeatChangeMs = nowMs
        }
        // Each pose is read with its NT publish timestamp. This prevents a new MT2 frame
        // from being paired with an unrelated cached MT1 recovery solve.
        val megaTag1Sample = botposeSub.getAtomic()
        val megaTag2Sample = botposeMt2Sub.getAtomic()
        val megaTag1Botpose = megaTag1Sample.value
        val megaTag2Botpose = megaTag2Sample.value
        val mt1TagCount = tagCountFromBotpose(megaTag1Botpose)
        val mt2TagCount = tagCountFromBotpose(megaTag2Botpose)
        val usingMegaTag2 = megaTag2Botpose.size >= 11 && mt2TagCount > 0
        val botposeSample = if (usingMegaTag2) megaTag2Sample else megaTag1Sample
        val botpose = botposeSample.value
        val observationTimestampMicros = botposeSample.timestamp

        val heartbeatConnected = lastHeartbeatChangeMs != Long.MIN_VALUE && nowMs - lastHeartbeatChangeMs <= 1_000L
        inputs.isConnected = if (lastHeartbeatChangeMs != Long.MIN_VALUE) {
            heartbeatConnected
        } else {
            // Compatibility fallback for older firmware that does not publish hb.
            botpose.isNotEmpty()
        }
        
        val tagCount = if (usingMegaTag2) mt2TagCount else mt1TagCount
        val targetValid = tvSample.value == 1.0 &&
            timestampsAreCoherent(observationTimestampMicros, tvSample.timestamp)
        if (inputs.isConnected && targetValid && botpose.size >= 11 && tagCount > 0) {
            val frameId = if (observationTimestampMicros > 0L) observationTimestampMicros else heartbeat
            if (frameId != Long.MIN_VALUE && frameId == lastEmittedFrameId) {
                inputs.measurements = emptyList()
                return
            }
            // Limelight latency (ms) is typically index 6.
            val latencyMs = if (botpose.size > 6) botpose[6] else 0.0
            val timestampMs = nowMs - latencyMs.toLong()
            
            // Limelight's field-pose arrays do not contain solve ambiguity. Keep the
            // numeric field JSON-safe while marking it semantically unavailable below.
            val ambiguity = 0.0
            
            val targetPose = poseFromBotpose(botpose)
            val recoveryIsCoherent = mt1TagCount > 0 && (!usingMegaTag2 ||
                timestampsAreCoherent(observationTimestampMicros, megaTag1Sample.timestamp))
            val recoveryPose = if (recoveryIsCoherent) poseFromBotpose(megaTag1Botpose) else Pose3d()
            
            // Populate target-space pose for alignment controllers
            val targetSpaceSample = botposeTargetSpaceSub.getAtomic()
            val targetSpace = targetSpaceSample.value
            val robotPoseTargetSpace = if (targetSpace.size >= 6 &&
                timestampsAreCoherent(observationTimestampMicros, targetSpaceSample.timestamp)) {
                Pose3d(
                    Translation3d(targetSpace[0], targetSpace[1], targetSpace[2]),
                    Rotation3d(Math.toRadians(targetSpace[3]), Math.toRadians(targetSpace[4]), Math.toRadians(targetSpace[5]))
                )
            } else {
                Pose3d()
            }
            val tagIdSample = tidSub.getAtomic()
            val tagId = if (timestampsAreCoherent(observationTimestampMicros, tagIdSample.timestamp)) {
                tagIdSample.value.toInt()
            } else {
                -1
            }
            
            cachedMeasurement.timestampMs = timestampMs
            cachedMeasurement.targetPose = targetPose
            cachedMeasurement.recoveryPose = recoveryPose
            cachedMeasurement.hasRecoveryPose = recoveryIsCoherent
            cachedMeasurement.tagId = tagId
            cachedMeasurement.tagCount = tagCount
            cachedMeasurement.ambiguity = ambiguity
            cachedMeasurement.ambiguityAvailable = false
            cachedMeasurement.tagSpanMeters = finiteMetric(botpose, 8)
            cachedMeasurement.averageTagDistanceMeters = finiteMetric(botpose, 9)
            cachedMeasurement.averageTagAreaPercent = finiteMetric(botpose, 10)
            cachedMeasurement.robotPoseTargetSpace = robotPoseTargetSpace
            cachedMeasurement.sourceId = tableName
            cachedMeasurement.frameId = if (frameId != Long.MIN_VALUE) frameId else timestampMs
            cachedMeasurement.solverType = if (usingMegaTag2) VisionSolverType.MEGATAG2 else VisionSolverType.MEGATAG1
            cachedMeasurement.latencyMs = latencyMs
            applyObservationStdDevs(cachedMeasurement, usingMegaTag2, botpose, tagCount, observationTimestampMicros)
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
        tagCount: Int,
        observationTimestampMicros: Long
    ) {
        val reportedSample = stdDevsSub.getAtomic()
        val reported = if (timestampsAreCoherent(observationTimestampMicros, reportedSample.timestamp)) {
            reportedSample.value
        } else {
            EMPTY_DOUBLE_ARRAY
        }
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

    private fun tagCountFromBotpose(botpose: DoubleArray): Int =
        if (botpose.size > 7 && botpose[7].isFinite()) botpose[7].toInt().coerceAtLeast(0) else 0

    private fun finiteMetric(botpose: DoubleArray, index: Int): Double =
        botpose.getOrNull(index)?.takeIf { it.isFinite() && it >= 0.0 } ?: -1.0

    private fun timestampsAreCoherent(firstMicros: Long, secondMicros: Long): Boolean =
        firstMicros > 0L && secondMicros > 0L &&
            kotlin.math.abs(firstMicros - secondMicros) <= MAX_COMPANION_TIMESTAMP_DELTA_MICROS

    override fun close() {
        botposeSub.close()
        botposeMt2Sub.close()
        tvSub.close()
        botposeTargetSpaceSub.close()
        tidSub.close()
        heartbeatSub.close()
        stdDevsSub.close()
        orientationPub.close()
        imuModePub.close()
        pipelinePub.close()
        ledModePub.close()
        streamPub.close()
        cameraPosePub.close()
    }

    private companion object {
        const val MAX_COMPANION_TIMESTAMP_DELTA_MICROS = 50_000L
        val EMPTY_DOUBLE_ARRAY = DoubleArray(0)
    }
}
