package com.areslib.pathing

/**
 * Trajectory Marker Event Trigger Definition.
 *
 * Represents one action occurrence triggered when supplied path progress reaches
 * [triggerDistanceMeters]. Repeated [eventName] values represent separate occurrences.
 * Progress may be a virtual target distance, not measured robot travel. The follower requires
 * a finite, nonnegative threshold when installing its marker schedule.
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
