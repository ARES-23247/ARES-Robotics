package com.areslib.hardware.vision

import com.areslib.state.VisionMeasurement
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Translation3d
import com.areslib.math.geometry.Rotation3d

import com.areslib.hardware.LoggableDevice

/**
 * Mutable, caller-owned snapshot filled by [VisionIO.updateInputs].
 *
 * Poses use meters and radians. Field-pose headings are CCW-positive. Camera mount poses are
 * robot-relative; each translation is from robot center to camera in the robot frame. Implementors
 * should replace snapshot collections rather than mutate lists retained from a previous cycle.
 */
data class VisionIOInputs(
    var isConnected: Boolean = false,
    var measurements: List<VisionMeasurement> = emptyList(),
    var cameraPoses: List<Pose3d> = emptyList()
)

/**
 * Hardware boundary for one vision source.
 *
 * [updateInputs] owns all device reads for a loop. Consumers read only the supplied snapshot. A
 * disconnected or invalid source reports `isConnected = false` and an empty measurement list; it
 * must not replay stale detections. Implementations may allocate outside strict robot hot paths,
 * but should cache SDK handles and avoid hidden device reads from properties.
 */
interface VisionIO : LoggableDevice {
    /**
     * Camera-to-robot mounting transforms, in meters and radians, expressed in robot coordinates.
     * The default is a single forward-facing camera 0.18 m ahead of robot center.
     */
    val cameraPoses: List<Pose3d>
        get() = listOf(Pose3d(Translation3d(0.18, 0.0, 0.0), Rotation3d(0.0, 0.0, 0.0)))

    /**
     * Replaces [inputs] with the latest cached/device snapshot for this loop.
     */
    fun updateInputs(inputs: VisionIOInputs)

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
