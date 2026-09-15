package com.areslib.hardware.vision

import com.areslib.state.VisionMeasurement
import com.areslib.math.geometry.Pose3d

import com.areslib.hardware.LoggableDevice

/**
 * Mutable, caller-owned snapshot filled by [VisionIO.updateInputs].
 *
 * Poses use meters and radians. Field-pose headings are CCW-positive. Camera mount poses are
 * robot-relative; each translation is from robot center to camera in the robot frame. Collections and
 * pooled measurements may be borrowed until the next poll or close. Consumers must finish reading
 * within that lifetime, or take owned copies when retaining observations (as Redux does).
 */
data class VisionIOInputs(
    var isConnected: Boolean = false,
    var measurements: List<VisionMeasurement> = emptyList(),
    var cameraPoses: List<Pose3d> = emptyList(),
    var clusterTargets: List<ClusterTargetMeasurement> = emptyList()
)

/**
 * Hardware boundary for one vision source.
 *
 * [updateInputs] owns all device reads for a loop. Consumers read only the supplied snapshot. A
 * disconnected or invalid source reports `isConnected = false` and an empty measurement list; it
 * must not replay stale detections. Implementations may allocate outside strict robot hot paths,
 * but should cache SDK handles and avoid hidden device reads from properties.
 * Observation `timestampMs` is capture time in the shared RobotClock millisecond domain.
 * `captureTimestampMicros` may use a source-specific epoch and requires the platform's clock
 * conversion before comparison with another clock domain.
 */
interface VisionIO : LoggableDevice {
    /**
     * Camera-to-robot mounting transforms, in meters and radians, expressed in robot coordinates.
     * An empty list means the transform is owned by the camera configuration. This is the safe
     * default: publishing a guessed transform can silently corrupt every field-pose observation.
     */
    val cameraPoses: List<Pose3d>
        get() = emptyList()

    /**
     * Replaces [inputs] with the latest cached/device snapshot for this loop.
     */
    fun updateInputs(inputs: VisionIOInputs)

    /** Configures moving-target clusters outside the loop. Cluster cameras disable field-pose
     * publication entirely, including mixed static/moving frames. Use a separate camera for localization. */
    fun configureTargetClusters(clusters: List<AprilTagCluster>) {
        require(clusters.isEmpty()) { "This vision source does not support target clusters" }
    }

    /**
     * Supplies robot orientation and motion hints used by gyro-assisted estimators such as MegaTag2.
     * Angles are degrees, angular rates are degrees per second, and linear speed is meters per second.
     * The default implementation is a no-op for cameras that do not require orientation hints.
     */
    fun setOrientation(
        yawDegrees: Double, yawRateDegPerSec: Double,
        pitchDegrees: Double, pitchRateDegPerSec: Double,
        rollDegrees: Double, rollRateDegPerSec: Double,
        linearVelocityMps: Double = 0.0
    ) {}

    /**
     * Selects a camera IMU mode when supported. Limelight 4 should normally use
     * external seeding while disabled and assisted internal/external fusion while enabled.
     */
    fun setImuMode(mode: Int) {}
}
