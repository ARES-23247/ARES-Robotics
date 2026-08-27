package com.areslib.state

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Rotation2d
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.withSign

/** Deeply immutable 3D pose representation for Redux and replay ownership. */
data class Pose3dSnapshot(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    val quaternionW: Double = 1.0,
    val quaternionX: Double = 0.0,
    val quaternionY: Double = 0.0,
    val quaternionZ: Double = 0.0
) {
    val rotationX: Double
        get() {
            val sinRollCosPitch = 2.0 * (quaternionW * quaternionX + quaternionY * quaternionZ)
            val cosRollCosPitch = 1.0 - 2.0 * (quaternionX * quaternionX + quaternionY * quaternionY)
            return atan2(sinRollCosPitch, cosRollCosPitch)
        }

    val rotationY: Double
        get() {
            val sinPitch = 2.0 * (quaternionW * quaternionY - quaternionZ * quaternionX)
            return if (abs(sinPitch) >= 1.0) (PI / 2.0).withSign(sinPitch) else asin(sinPitch)
        }

    val rotationZ: Double
        get() {
            val sinYawCosPitch = 2.0 * (quaternionW * quaternionZ + quaternionX * quaternionY)
            val cosYawCosPitch = 1.0 - 2.0 * (quaternionY * quaternionY + quaternionZ * quaternionZ)
            return atan2(sinYawCosPitch, cosYawCosPitch)
        }

    fun toPose2d(): Pose2d = Pose2d(x, y, Rotation2d(rotationZ))

    companion object {
        fun from(pose: Pose3d): Pose3dSnapshot = Pose3dSnapshot(
            x = pose.translation.x,
            y = pose.translation.y,
            z = pose.translation.z,
            quaternionW = pose.rotation.q.w,
            quaternionX = pose.rotation.q.x,
            quaternionY = pose.rotation.q.y,
            quaternionZ = pose.rotation.q.z
        )
    }
}

/** Immutable retained form of a mutable, pool-friendly hardware vision measurement. */
data class VisionMeasurementSnapshot(
    val timestampMs: Long = 0L,
    val captureTimestampMicros: Long = 0L,
    val targetPose: Pose3dSnapshot = Pose3dSnapshot(),
    val tagId: Int = -1,
    val ambiguity: Double = 0.0,
    val ambiguityAvailable: Boolean = true,
    val robotPoseTargetSpace: Pose3dSnapshot = Pose3dSnapshot(),
    val tagCount: Int = 1,
    val tagSpanMeters: Double = -1.0,
    val averageTagDistanceMeters: Double = -1.0,
    val averageTagAreaPercent: Double = -1.0,
    val sourceId: String = "",
    val frameId: Long = 0L,
    val solverType: VisionSolverType = VisionSolverType.UNKNOWN,
    val latencyMs: Double = 0.0,
    val stdDevXMeters: Double = 0.0,
    val stdDevYMeters: Double = 0.0,
    val stdDevHeadingRadians: Double = 0.0,
    val recoveryPose: Pose3dSnapshot = Pose3dSnapshot(),
    val hasRecoveryPose: Boolean = false,
    val recoveryAmbiguity: Double = 0.0,
    val recoveryAmbiguityAvailable: Boolean = false
)

/** Captures one mutable hardware measurement for immutable Redux/replay retention. */
fun VisionMeasurement.snapshot(): VisionMeasurementSnapshot = VisionMeasurementSnapshot(
    timestampMs = timestampMs,
    captureTimestampMicros = captureTimestampMicros,
    targetPose = Pose3dSnapshot.from(targetPose),
    tagId = tagId,
    ambiguity = ambiguity,
    ambiguityAvailable = ambiguityAvailable,
    robotPoseTargetSpace = Pose3dSnapshot.from(robotPoseTargetSpace),
    tagCount = tagCount,
    tagSpanMeters = tagSpanMeters,
    averageTagDistanceMeters = averageTagDistanceMeters,
    averageTagAreaPercent = averageTagAreaPercent,
    sourceId = sourceId,
    frameId = frameId,
    solverType = solverType,
    latencyMs = latencyMs,
    stdDevXMeters = stdDevXMeters,
    stdDevYMeters = stdDevYMeters,
    stdDevHeadingRadians = stdDevHeadingRadians,
    recoveryPose = Pose3dSnapshot.from(recoveryPose),
    hasRecoveryPose = hasRecoveryPose,
    recoveryAmbiguity = recoveryAmbiguity,
    recoveryAmbiguityAvailable = recoveryAmbiguityAvailable
)

/** Immutable 3x3 diagnostic matrix retained by Redux. */
data class Matrix3x3Snapshot(
    val m00: Double,
    val m01: Double,
    val m02: Double,
    val m10: Double,
    val m11: Double,
    val m12: Double,
    val m20: Double,
    val m21: Double,
    val m22: Double
) {
    fun copyToDoubleArray(): DoubleArray = doubleArrayOf(
        m00, m01, m02,
        m10, m11, m12,
        m20, m21, m22
    )

    companion object {
        fun from(values: DoubleArray): Matrix3x3Snapshot {
            require(values.size >= 9) { "A 3x3 matrix requires at least 9 values" }
            return Matrix3x3Snapshot(
                values[0], values[1], values[2],
                values[3], values[4], values[5],
                values[6], values[7], values[8]
            )
        }
    }
}
