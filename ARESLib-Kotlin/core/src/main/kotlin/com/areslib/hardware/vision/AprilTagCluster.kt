package com.areslib.hardware.vision

import kotlin.math.*

/** Offset from a tag center to the shared aiming point, in that tag's raw optical frame, meters.
 * This is the object frame of Limelight's getTargetPoseCameraSpace(), not FTC field coordinates.
 * All cluster members must remain rigidly attached to the same target.
 */
data class AprilTagClusterMember(val tagId: Int, val offsetX: Double, val offsetY: Double, val offsetZ: Double)

/** Immutable targeting configuration; these tags must never be used as fixed field landmarks.
 * Member IDs are unique within/across clusters configured on one camera. Configure outside polling.
 * maxDisagreementMeters bounds agreement between independently reconstructed aiming points.
 */
class AprilTagCluster(
    val id: String,
    members: List<AprilTagClusterMember>,
    val maxDisagreementMeters: Double = 0.15,
    val maxRangeMeters: Double = 8.0
) {
    val members: List<AprilTagClusterMember> = java.util.Collections.unmodifiableList(ArrayList(members))
    init {
        require(id.isNotBlank()) { "Cluster ID is required" }
        require(members.size in 2..32) { "A cluster needs 2..32 members" }
        require(members.map { it.tagId }.distinct().size == members.size) { "Duplicate cluster tag ID" }
        require(members.all { it.tagId >= 0 && it.offsetX.isFinite() && it.offsetY.isFinite() && it.offsetZ.isFinite() })
        require(maxDisagreementMeters.isFinite() && maxDisagreementMeters > 0.0)
        require(maxRangeMeters.isFinite() && maxRangeMeters > 0.05)
    }
}

/** Borrowed per-frame aiming measurement. Camera optical coordinates: +X right, +Y down, +Z forward.
 * Independent of botpose/field localization. Capture time is RobotClock milliseconds. Never retain
 * this pooled value across polls; use snapshot() for Redux/replay. A point is not a full 6DOF solve.
 */
class ClusterTargetMeasurement {
    var clusterId: String = ""
    var sourceId: String = ""
    var timestampMs: Long = 0
    var frameId: Long = 0
    var xMeters: Double = 0.0
    var yMeters: Double = 0.0
    var zMeters: Double = 0.0
    var contributingTags: Int = 0
    var visibleTags: Int = 0
    var spreadMeters: Double = 0.0

    fun snapshot(): ClusterTargetSnapshot = ClusterTargetSnapshot(clusterId, sourceId, timestampMs,
        frameId, xMeters, yMeters, zMeters, contributingTags, visibleTags, spreadMeters)
}

/** Immutable camera-relative target for controllers. Positive bearing turns left; elevation is up.
 * A controller must check isFresh(), explicit enable, and its own camera-to-actuator calibration.
 */
data class ClusterTargetSnapshot(
    val clusterId: String, val sourceId: String, val timestampMs: Long, val frameId: Long,
    val xMeters: Double, val yMeters: Double, val zMeters: Double,
    val contributingTags: Int, val visibleTags: Int, val spreadMeters: Double
) {
    val bearingRadians: Double get() = atan2(-xMeters, zMeters)
    val elevationRadians: Double get() = atan2(-yMeters, hypot(xMeters, zMeters))
    val rangeMeters: Double get() = hypot(hypot(xMeters, yMeters), zMeters)
    fun isFresh(nowMs: Long, maxAgeMs: Long = 250L): Boolean =
        maxAgeMs >= 0 && timestampMs >= 0 && nowMs >= timestampMs && nowMs - timestampMs <= maxAgeMs &&
            xMeters.isFinite() && yMeters.isFinite() && zMeters.isFinite() && zMeters > 0.05 &&
            contributingTags > 0 && visibleTags >= contributingTags && spreadMeters.isFinite() && spreadMeters >= 0.0
}

/** Allocation-free reconstruction of one rigid cluster's shared point from Limelight single-tag
 * poses. Uses Rz(yaw) Ry(pitch) Rx(roll), radians. This is agreement-gated point fusion, not joint
 * corner PnP. Each unique tag contributes at most once. Conflicting two-tag observations fail closed;
 * with more tags, only a strict majority agreeing with a seed may contribute. No covariance claim
 * is made from tag count, since per-frame camera/calibration errors are correlated.
 */
class AprilTagClusterTracker(private val cluster: AprilTagCluster) {
    private val members = cluster.members.toTypedArray()
    private val seen = BooleanArray(members.size)
    private val valid = BooleanArray(members.size)
    private val x = DoubleArray(members.size)
    private val y = DoubleArray(members.size)
    private val z = DoubleArray(members.size)

    fun beginFrame() { seen.fill(false); valid.fill(false) }

    fun addTag(tagId: Int, tx: Double, ty: Double, tz: Double, roll: Double, pitch: Double, yaw: Double) {
        var index = -1
        for (i in members.indices) if (members[i].tagId == tagId) { index = i; break }
        if (index < 0) return
        if (seen[index]) { valid[index] = false; return }
        seen[index] = true
        if (!tx.isFinite() || !ty.isFinite() || !tz.isFinite() || tz <= 0.05 ||
            !roll.isFinite() || !pitch.isFinite() || !yaw.isFinite()) return
        val member = members[index]
        val cr = cos(roll); val sr = sin(roll)
        val cp = cos(pitch); val sp = sin(pitch)
        val cy = cos(yaw); val sy = sin(yaw)
        val rx = member.offsetX
        val ry = cr * member.offsetY - sr * member.offsetZ
        val rz = sr * member.offsetY + cr * member.offsetZ
        val px = cp * rx + sp * rz
        val pz = -sp * rx + cp * rz
        x[index] = tx + cy * px - sy * ry
        y[index] = ty + sy * px + cy * ry
        z[index] = tz + pz
        val range = hypot(hypot(x[index], y[index]), z[index])
        valid[index] = range.isFinite() && range <= cluster.maxRangeMeters && z[index] > 0.05
    }

    fun finishFrame(output: ClusterTargetMeasurement, sourceId: String, timestampMs: Long, frameId: Long): Boolean {
        if (timestampMs < 0) return false
        var count = 0
        var observed = 0
        for (i in valid.indices) { if (valid[i]) count++; if (seen[i]) observed++ }
        if (count == 0) return false
        var best = -1
        var bestCount = 0
        for (i in valid.indices) if (valid[i]) {
            var neighbors = 0
            for (j in valid.indices) if (valid[j] && distance(i, j) <= cluster.maxDisagreementMeters) neighbors++
            if (neighbors > bestCount) { best = i; bestCount = neighbors }
        }
        if (bestCount <= count / 2) return false
        // A bridge tag must not join two mutually inconsistent observations into one majority.
        for (i in valid.indices) if (valid[i] && distance(best, i) <= cluster.maxDisagreementMeters) {
            for (j in 0 until i) if (valid[j] && distance(best, j) <= cluster.maxDisagreementMeters &&
                distance(i, j) > cluster.maxDisagreementMeters) return false
        }
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (i in valid.indices) if (valid[i] && distance(best, i) <= cluster.maxDisagreementMeters) {
            sx += x[i]; sy += y[i]; sz += z[i]
        }
        sx /= bestCount; sy /= bestCount; sz /= bestCount
        var spread = 0.0
        for (i in valid.indices) if (valid[i] && distance(best, i) <= cluster.maxDisagreementMeters) {
            spread = max(spread, hypot(hypot(x[i] - sx, y[i] - sy), z[i] - sz))
        }
        output.clusterId = cluster.id; output.sourceId = sourceId
        output.timestampMs = timestampMs; output.frameId = frameId
        output.xMeters = sx; output.yMeters = sy; output.zMeters = sz
        output.contributingTags = bestCount; output.visibleTags = observed; output.spreadMeters = spread
        return true
    }

    private fun distance(i: Int, j: Int): Double = hypot(hypot(x[i] - x[j], y[i] - y[j]), z[i] - z[j])
}
