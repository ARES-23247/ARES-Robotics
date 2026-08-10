package com.areslib.hardware.vision

import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation3d
import com.areslib.state.VisionMeasurement

/**
 * Always-connected, manually injectable simulator vision source.
 *
 * [updateInputs] publishes camera mounts but intentionally leaves the caller's measurement list
 * unchanged, allowing [injectMeasurement] to persist until the simulation replaces or clears it.
 * Coordinates are field-relative meters with a CCW-positive yaw in radians. This helper allocates
 * measurement objects and is not intended to model camera latency, occlusion, or noise.
 */
class SimVisionIO(
    override val cameraPoses: List<Pose3d> = listOf(Pose3d(Translation3d(0.18, 0.0, 0.0), Rotation3d(0.0, 0.0, 0.0)))
) : VisionIO {
    /** Marks the source connected and supplies configured camera mount poses. */
    override fun updateInputs(inputs: VisionIOInputs) {
        inputs.cameraPoses = cameraPoses
        inputs.isConnected = true
        
        // In a real simulation, we would calculate intersections with field tags.
        // For now, this just acts as an empty source unless manually populated by the sim.
    }

    /**
     * Replaces [inputs]' detections with one synthetic tag-1 observation timestamped by RobotClock.
     * [heading] is CCW-positive radians and [ambiguity] is passed through without validation.
     */
    fun injectMeasurement(inputs: VisionIOInputs, x: Double, y: Double, heading: Double, ambiguity: Double = 0.0) {
        val pose = Pose3d(
            Translation3d(x, y, 0.0),
            Rotation3d(0.0, 0.0, heading)
        )
        val measurement = VisionMeasurement(
            timestampMs = com.areslib.util.RobotClock.currentTimeMillis(),
            targetPose = pose,
            tagId = 1,
            ambiguity = ambiguity
        )
        inputs.measurements = listOf(measurement)
    }
}
