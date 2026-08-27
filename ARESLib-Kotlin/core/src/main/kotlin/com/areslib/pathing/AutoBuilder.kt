package com.areslib.pathing

import com.areslib.sequencer.Task
import com.areslib.state.Alliance

/**
 * Declarative Autonomous Routine Construction Facade.
 *
 * Compiles PathPlanner `.auto` and `.path` JSON files into executable sequencer [Task] graphs
 * (such as [com.areslib.sequencer.SequentialTaskGroup], [com.areslib.sequencer.ParallelTaskGroup],
 * or [com.areslib.sequencer.FollowPathTask]).
 *
 * ### Physical Units & Coordinate Conventions:
 * - Position: Meters ($m$)
 * - Heading: Radians ($rad$), **CCW-positive** ($0 = +X$, $\frac{\pi}{2} = +Y$)
 * - Time: Milliseconds ($ms$) base timestamp
 *
 * @see DynamicPathLoader
 * @see HolonomicPathFollower
 */
class AutoBuilder {
    private var follower: HolonomicPathFollower? = null

    /**
     * Configures the active holonomic path follower that will be bound to path execution tasks.
     *
     * @param follower Configured [HolonomicPathFollower] instance.
     * @return This [AutoBuilder] instance for fluent method chaining.
     */
    fun configureFollower(follower: HolonomicPathFollower): AutoBuilder {
        this.follower = follower
        return this
    }

    /**
     * Builds a comprehensive sequencer [Task] tree representing an entire autonomous routine loaded from a PathPlanner `.auto` file.
     * 
     * @param autoName Name of the `.auto` file (without extension).
     * @param timestampMs Reference base timestamp for sequencer instantiation in milliseconds ($ms$).
     * @param alliance Active team alliance color ([Alliance.BLUE] or [Alliance.RED]).
     * @return Top-level executable [Task] tree.
     */
    fun buildAuto(autoName: String, timestampMs: Long, alliance: Alliance = Alliance.BLUE): Task {
        val activeFollower = follower ?: error("AutoBuilder requires a configured follower. Call configureFollower() first.")
        return DynamicPathLoader.loadAuto(autoName, activeFollower, timestampMs, alliance)
    }

    /**
     * Builds a [Task] that directly follows a single PathPlanner `.path` file without requiring a `.auto` file wrapper.
     * 
     * @param pathName Name of the `.path` file (without extension).
     * @param alliance Active team alliance color ([Alliance.BLUE] or [Alliance.RED]).
     * @return Single [com.areslib.sequencer.FollowPathTask] ready for execution.
     */
    fun buildPath(pathName: String, alliance: Alliance = Alliance.BLUE): Task {
        val activeFollower = follower ?: error("AutoBuilder requires a configured follower. Call configureFollower() first.")
        val path = DynamicPathLoader.loadPath(pathName)
        val mirroredPath = com.areslib.math.coordinate.AllianceMirroring.mirror(path, alliance, com.areslib.math.coordinate.FieldSymmetry.MIRRORED)
        return com.areslib.sequencer.FollowPathTask(activeFollower, mirroredPath, mirrorForAlliance = false)
    }
}

