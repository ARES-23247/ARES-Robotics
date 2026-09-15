package com.areslib.state

import com.areslib.pathing.Path

/**
 * Holds state for active path tracking, chaining, and detour events.
 * Path references retain shared ownership of their lists and mutable point payloads. Callers
 * must keep those values stable while any state snapshot or follower retains the path.
 */
data class PathState(
    val activePath: Path? = null,
    val currentDistanceMeters: Double = 0.0,
    val isChained: Boolean = false,
    val detourActive: Boolean = false,
    val originalPathBeforeDetour: Path? = null,
    val crossTrackErrorMeters: Double = 0.0,
    val alongTrackErrorMeters: Double = 0.0,
    val headingErrorRadians: Double = 0.0
)
