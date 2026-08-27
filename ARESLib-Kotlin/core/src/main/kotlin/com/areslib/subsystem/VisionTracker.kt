package com.areslib.subsystem

/**
 * Platform-independent interface for AprilTag vision tracking,
 * outlier rejection, and EKF pose correction.
 *
 * Both FTC and FRC platforms implement this interface with their
 * respective camera SDK polling and coordinate transforms.
 */
interface VisionTracker {

    /**
     * Human-readable status string describing the last vision processing result.
     * Examples: "ACCEPTED", "REJ_AMBIG (0.35 > 0.20)", "NO TARGET", "OFFLINE".
     */
    val lastVisionStatus: String

    /**
     * True if the vision sensor hardware is connected and responding.
     */
    val isConnected: Boolean

    /**
     * Polls the vision sensors, performs outlier rejection
     * (ambiguity, field boundary, distance, heading deviation, Mahalanobis),
     * and dispatches [com.areslib.action.RobotAction.VisionMeasurementsReceived]
     * to the store for EKF fusion.
     *
     * Also handles special modes:
     * - Init-snap: One-time absolute pose alignment during initialization
     * - Kidnapped recovery: Automatic re-seed after sustained rejections while stationary
     *
     * @param timestampMs Current timestamp from [com.areslib.util.RobotClock].
     */
    fun update(timestampMs: Long)
}
