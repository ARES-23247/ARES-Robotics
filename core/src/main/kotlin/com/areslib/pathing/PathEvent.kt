package com.areslib.pathing

/**
 * Trajectory Marker Event Trigger Definition.
 *
 * Represents an action marker embedded along a trajectory path triggered when the robot's accumulated
 * distance exceeds [triggerDistanceMeters].
 *
 * ### Physical Units:
 * - Trigger Distance ([triggerDistanceMeters]): Accumulated arc-length distance from path origin in meters ($m$).
 *
 * @property eventName Registered string key matching a command in [NamedCommands].
 * @property triggerDistanceMeters Arc-length distance threshold along path in meters ($m$).
 */
data class PathEvent(
    val eventName: String,
    val triggerDistanceMeters: Double
)
