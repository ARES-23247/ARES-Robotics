package com.areslib.ftc.vision

import com.areslib.hardware.vision.AprilTagCluster
import com.areslib.hardware.vision.ClusterTargetMeasurement
import com.areslib.hardware.vision.VisionIO
import com.areslib.hardware.vision.VisionIOInputs
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.deepCopy
import com.areslib.state.RobotFieldManager
import com.areslib.state.FieldType
import com.areslib.state.VisionMeasurement
import com.areslib.state.VisionSolverType
import com.areslib.util.RobotClock
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit
import org.firstinspires.ftc.vision.apriltag.AprilTagClusterDetection
import org.firstinspires.ftc.vision.apriltag.AprilTagProcessor
import org.firstinspires.ftc.vision.apriltag.AprilTagSingleDetection
import kotlin.math.sqrt

/**
 * SDK 12 VisionPortal boundary, polled once per robot loop. Native clusters are camera-relative
 * aiming observations only, including BIOBUZZ's moving goals. Seeing a cluster latches this camera
 * into targeting-only operation until it is recreated; mixed frames cannot localize the robot.
 *
 * Absolute poses require an explicit whitelist of reviewed stationary singleton IDs and exactly
 * one measured robot-to-camera transform (+X forward, +Y left, +Z up, meters/radians). The default
 * publishes no absolute pose. Never whitelist moving tags, including BIOBUZZ cluster members.
 * [tagPoseConvention] defaults to the SDK optical tag basis used by maintained FTC layouts.
 * Select WPILIB_NWU explicitly for tag rotations imported without that basis conversion.
 *
 * Distance units come from each SDK detection. [outputAngleUnit] must match the processor builder
 * (SDK default: degrees). Capture nanoseconds are converted once into the RobotClock millisecond
 * domain. Invalid, future, or older-than-500ms captures are discarded. A successful poll with no new
 * frame remains connected and clears observations. Outputs are borrowed until the next poll;
 * snapshot them before retention. Workspaces cap each frame at 32 observations without aliasing.
 */
class FtcVisionPortalIO(
    private val aprilTagProcessor: AprilTagProcessor,
    cameraPoses: List<Pose3d> = emptyList(),
    localizationTagIds: Set<Int> = emptySet(),
    private val outputAngleUnit: AngleUnit = AngleUnit.DEGREES,
    private val sourceId: String = "visionportal",
    private val tagPoseConvention: FtcTagPoseConvention = FtcTagPoseConvention.FTC_OPTICAL,
) : VisionIO {
    override val cameraPoses: List<Pose3d> = cameraPoses.map { it.deepCopy() }
    private val stationaryIds = localizationTagIds.toIntArray().apply { sort() }
    private val measurements = ArrayList<VisionMeasurement>(CAPACITY)
    private val targets = ArrayList<ClusterTargetMeasurement>(CAPACITY)
    private val measurementPool = Array(CAPACITY) { VisionMeasurement() }
    private val targetPool = Array(CAPACITY) { ClusterTargetMeasurement() }
    private val poseMath = FtcVisionPoseMath()
    private var targetingOnly = false
    private var lastWarningTime = Long.MIN_VALUE

    init {
        require(sourceId.isNotBlank()) { "A stable camera source ID is required" }
        require(stationaryIds.all { it >= 0 }) { "Stationary tag IDs must be nonnegative" }
        require(stationaryIds.isEmpty() || cameraPoses.size == 1) {
            "Localization requires exactly one measured robot-to-camera transform"
        }
        for (pose in this.cameraPoses) {
            val t = pose.translation
            val q = pose.rotation.q
            val norm = sqrt(q.w*q.w + q.x*q.x + q.y*q.y + q.z*q.z)
            require(t.x.isFinite() && t.y.isFinite() && t.z.isFinite() && norm.isFinite() && norm > 1e-12) {
                "Camera transform must be finite with a nonzero rotation"
            }
            q.w /= norm; q.x /= norm; q.y /= norm; q.z /= norm
        }
    }

    /** Cluster processors own their SDK tag library. Configuring targeting disables field poses. */
    override fun configureTargetClusters(clusters: List<AprilTagCluster>) {
        if (clusters.isNotEmpty()) targetingOnly = true
    }

    override fun updateInputs(inputs: VisionIOInputs) {
        measurements.clear()
        targets.clear()
        inputs.measurements = emptyList()
        inputs.clusterTargets = emptyList()
        inputs.cameraPoses = cameraPoses
        try {
            val detections = aprilTagProcessor.freshDetections
            inputs.isConnected = true
            if (detections == null) return
            for (i in detections.indices) if (detections[i] is AprilTagClusterDetection) targetingOnly = true
            val nowNs = RobotClock.nanoTime()
            val nowMs = RobotClock.currentTimeMillis()
            for (i in detections.indices) {
                val detection = detections[i]
                val pose = detection.ftcPose ?: continue
                val capturedNs = detection.frameAcquisitionNanoTime
                if (capturedNs == 0L) continue
                val ageNs = nowNs - capturedNs
                if (ageNs < 0L || ageNs > 500_000_000L) continue
                val timestampMs = nowMs - ageNs / 1_000_000L
                if (timestampMs < 0L) continue
                val scale = DistanceUnit.METER.fromUnit(detection.distanceUnit, 1.0)
                if (!pose.x.isFinite() || !pose.y.isFinite() || !pose.z.isFinite() ||
                    !pose.roll.isFinite() || !pose.pitch.isFinite() || !pose.yaw.isFinite()) continue
                val forward = pose.y * scale
                val range = sqrt(pose.x*pose.x + pose.y*pose.y + pose.z*pose.z) * scale
                if (!range.isFinite() || range > 8.0 || forward <= 0.05) continue
                when (detection) {
                    is AprilTagClusterDetection -> {
                        if (targets.size == CAPACITY || detection.percentClusterFound !in 1..100) continue
                        val id = detection.metadata.shortName.ifBlank { detection.metadata.name }
                        var duplicate = false
                        for (j in targets.indices) if (targets[j].clusterId == id) duplicate = true
                        if (id.isBlank() || duplicate) continue
                        val target = targetPool[targets.size]
                        target.clusterId = id; target.sourceId = sourceId
                        target.timestampMs = timestampMs; target.frameId = capturedNs
                        target.xMeters = pose.x * scale
                        target.yMeters = -pose.z * scale
                        target.zMeters = forward
                        // SDK exposes percentage, not member count: report a lower bound of one.
                        target.contributingTags = 1; target.visibleTags = 1; target.spreadMeters = 0.0
                        targets.add(target)
                    }
                    is AprilTagSingleDetection -> {
                        if (targetingOnly || java.util.Arrays.binarySearch(stationaryIds,detection.id) < 0 || measurements.size == CAPACITY) continue
                        val field = RobotFieldManager.activeConfig
                        if (field.fieldType != FieldType.FTC) continue
                        var selected: com.areslib.state.RobotFieldAprilTag? = null
                        val tags = field.apriltags
                        for (j in tags.indices) if (tags[j].id == detection.id) { selected = tags[j]; break }
                        val tag = selected ?: continue
                        if (!tag.x.isFinite() || !tag.y.isFinite() || !tag.z.isFinite() ||
                            !tag.roll.isFinite() || !tag.pitch.isFinite() || !tag.yaw.isFinite()) continue
                        val measurement = measurementPool[measurements.size]
                        poseMath.solve(tag, cameraPoses[0], pose, scale, outputAngleUnit, tagPoseConvention, measurement)
                        measurement.timestampMs = timestampMs
                        measurement.captureTimestampMicros = capturedNs / 1000L
                        measurement.frameId = capturedNs; measurement.sourceId = sourceId
                        measurement.tagId = detection.id; measurement.tagCount = 1
                        measurement.solverType = VisionSolverType.VISION_PORTAL
                        measurement.latencyMs = ageNs / 1_000_000.0
                        measurement.averageTagDistanceMeters = range
                        measurement.ambiguity = 0.0; measurement.ambiguityAvailable = false
                        measurements.add(measurement)
                    }
                }
            }
            inputs.measurements = measurements
            inputs.clusterTargets = targets
        } catch (failure: Exception) {
            measurements.clear(); targets.clear()
            inputs.isConnected = false
            val now = RobotClock.currentTimeMillis()
            if (lastWarningTime == Long.MIN_VALUE || now - lastWarningTime > 2000L) {
                System.err.println("FtcVisionPortalIO: Could not read AprilTag detections: ${failure.message}")
                lastWarningTime = now
            }
        }
    }

    private companion object { const val CAPACITY = 32 }
}
