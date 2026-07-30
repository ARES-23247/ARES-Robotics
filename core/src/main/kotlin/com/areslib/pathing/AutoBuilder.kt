package com.areslib.pathing

import com.areslib.sequencer.Task
import com.areslib.state.Alliance

/**
 * A declarative builder facade for constructing Autonomous routines from PathPlanner
 * .auto files and associated configurations.
 */
class AutoBuilder {
    private var follower: HolonomicPathFollower? = null

    /**
     * Configures the path follower that will be used to execute paths
     * spawned from this AutoBuilder.
     */
    fun configureFollower(follower: HolonomicPathFollower): AutoBuilder {
        this.follower = follower
        return this
    }

    /**
     * Builds a comprehensive [Task] representing the entire autonomous routine
     * defined in the PathPlanner .auto file.
     * 
     * @param autoName The name of the .auto file (without extension)
     * @param timestampMs The base timestamp used for generating the sequence
     */
    fun buildAuto(autoName: String, timestampMs: Long, alliance: Alliance = Alliance.BLUE): Task {
        val activeFollower = follower ?: error("AutoBuilder requires a configured follower. Call configureFollower() first.")
        return DynamicPathLoader.loadAuto(autoName, activeFollower, timestampMs, alliance)
    }

    /**
     * Builds a [Task] that directly follows a single PathPlanner .path file,
     * without needing a surrounding .auto routine file.
     * 
     * @param pathName The name of the .path file (without extension)
     */
    fun buildPath(pathName: String, alliance: Alliance = Alliance.BLUE): Task {
        val activeFollower = follower ?: error("AutoBuilder requires a configured follower. Call configureFollower() first.")
        val path = DynamicPathLoader.loadPath(pathName)
        val mirroredPath = com.areslib.math.coordinate.AllianceMirroring.mirror(path, alliance, com.areslib.math.coordinate.FieldSymmetry.MIRRORED)
        return com.areslib.sequencer.FollowPathTask(activeFollower, mirroredPath, mirrorForAlliance = false)
    }
}
