package com.ares.analytics.ui.components.pathplanner

data class Waypoint(
    val x: Double,
    val y: Double,
    /** Spline tangent heading in radians. null = auto-calculate (point toward next waypoint). */
    val headingRad: Double? = null,
    val prevControlLength: Double = 0.5,
    val nextControlLength: Double = 0.5,
    /** Robot rotation in degrees. null means no explicit robot heading is attached. */
    val rotationDeg: Double? = null
)

/**
 * Resolves the effective heading for a waypoint. If [Waypoint.headingRad] is non-null, returns it.
 * Otherwise auto-calculates: points from previous toward next waypoint (Catmull-Rom style for
 * intermediate waypoints), giving the most direct path.
 */
fun resolveHeading(waypoints: List<Waypoint>, index: Int): Double {
    val wp = waypoints[index]
    if (wp.headingRad != null) return wp.headingRad

    return when {
        waypoints.size <= 1 -> 0.0
        index == 0 -> {
            val next = waypoints[1]
            kotlin.math.atan2(next.y - wp.y, next.x - wp.x)
        }
        index == waypoints.size - 1 -> {
            val prev = waypoints[index - 1]
            kotlin.math.atan2(wp.y - prev.y, wp.x - prev.x)
        }
        else -> {
            // Average direction from prev→this and this→next (Catmull-Rom tangent)
            val prev = waypoints[index - 1]
            val next = waypoints[index + 1]
            val a1 = kotlin.math.atan2(wp.y - prev.y, wp.x - prev.x)
            val a2 = kotlin.math.atan2(next.y - wp.y, next.x - wp.x)
            kotlin.math.atan2(kotlin.math.sin(a1) + kotlin.math.sin(a2), kotlin.math.cos(a1) + kotlin.math.cos(a2))
        }
    }
}
