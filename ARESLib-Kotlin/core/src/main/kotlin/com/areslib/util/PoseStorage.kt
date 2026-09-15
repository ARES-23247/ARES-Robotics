package com.areslib.util

import com.areslib.math.geometry.Pose2d
import com.areslib.state.Alliance

/**
 * Process-local immutable pose/alliance handoff between Autonomous and TeleOp.
 * Read [snapshot] once before dispatching actions or calling user code. A null snapshot is invalid.
 */
object PoseStorage {
    /** A validated pose and its authored alliance, published together. */
    class Snapshot internal constructor(val pose: Pose2d, val alliance: Alliance)

    @JvmStatic
    @Volatile
    var snapshot: Snapshot? = null
        private set

    /** Publishes one finite handoff; invalid input discards any older handoff and returns false. */
    @JvmStatic
    fun save(pose: Pose2d, alliance: Alliance): Boolean {
        val valid = pose.x.isFinite() && pose.y.isFinite() && pose.heading.rawRadians.isFinite()
        snapshot = if (valid) Snapshot(pose, alliance) else null
        return valid
    }

    @JvmStatic
    fun clear() {
        snapshot = null
    }
}
