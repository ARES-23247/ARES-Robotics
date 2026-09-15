package com.areslib.pathing

import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import kotlin.math.abs
import kotlin.math.hypot

/** Shared finite input policy for parsed and directly constructed spline requests. */
internal object SplineProfileValidation {
    const val MAX_WAYPOINTS = 512
    const val MAX_METADATA_ENTRIES = 2048
    const val MAX_ABS_COORDINATE_METERS = 1000.0

    fun generated(points: List<Translation2d>, start: Rotation2d, end: Rotation2d, velocity: Double, acceleration: Double) {
        limits(velocity, acceleration)
        require(points.size in 2..MAX_WAYPOINTS) { "A generated path requires 2..$MAX_WAYPOINTS points" }
        points.forEach(::coordinate)
        // Rotation2d.radians normalizes non-finite values to zero; validate the supplied value.
        require(start.rawRadians.isFinite() && end.rawRadians.isFinite()) { "Generated path headings must be finite" }
    }

    fun parsed(data: PathPlannerJsonParser.ParsedPathData) {
        require(data.waypoints.size in 1..MAX_WAYPOINTS) { "Spline waypoint count is outside 1..$MAX_WAYPOINTS" }
        limits(data.defaultMaxVel, data.defaultMaxAccel)
        require(data.startVel.isFinite() && data.startVel >= 0.0 && data.endVel.isFinite() && data.endVel >= 0.0) {
            "Endpoint speed ceilings must be finite and non-negative"
        }
        require(data.startRotDeg?.isFinite() != false && data.endRotDeg?.isFinite() != false) { "Endpoint headings must be finite" }
        data.waypoints.forEach { coordinate(it.anchor); coordinate(it.prevControl); coordinate(it.nextControl) }
        val last = data.waypoints.lastIndex.toDouble()
        require(data.rotationTargets.size <= MAX_METADATA_ENTRIES && data.constraintZones.size <= MAX_METADATA_ENTRIES &&
            data.pointTowardsZones.size <= MAX_METADATA_ENTRIES && data.eventMarkers.size <= MAX_METADATA_ENTRIES) { "Too many spline metadata entries" }
        data.rotationTargets.forEach {
            relative(it.waypointRelativePos, last)
            require(it.rotationDegrees.isFinite()) { "Rotation target must be finite" }
        }
        data.constraintZones.forEach {
            interval(it.minWaypointRelativePos, it.maxWaypointRelativePos, last)
            limits(it.maxVelocity, it.maxAcceleration)
        }
        data.pointTowardsZones.forEach {
            interval(it.minWaypointRelativePos, it.maxWaypointRelativePos, last)
            require(it.rotationOffset.isFinite()) { "Point-towards offset must be finite" }
            coordinate(it.x, it.y)
        }
        data.eventMarkers.forEach {
            relative(it.waypointRelativePos, last)
            require(it.commandName.isNotBlank() && it.commandName.length <= 256) { "Invalid spline event command name" }
        }
    }

    private fun limits(velocity: Double, acceleration: Double) {
        require(velocity.isFinite() && velocity > 0.0) { "Maximum velocity must be finite and positive" }
        require(acceleration.isFinite() && acceleration > 0.0) { "Maximum acceleration must be finite and positive" }
    }
    private fun coordinate(point: Translation2d) = coordinate(point.x, point.y)
    private fun coordinate(x: Double, y: Double) {
        require(x.isFinite() && y.isFinite() && abs(x) <= MAX_ABS_COORDINATE_METERS && abs(y) <= MAX_ABS_COORDINATE_METERS) {
            "Spline coordinates must be finite and within +/-$MAX_ABS_COORDINATE_METERS m"
        }
    }
    private fun relative(value: Double, last: Double) {
        require(value.isFinite() && value in 0.0..last) { "Spline relative position must be within 0..$last" }
    }
    private fun interval(start: Double, end: Double, last: Double) {
        relative(start, last); relative(end, last)
        require(start <= end) { "Spline interval start exceeds its end" }
    }
}

internal class SplineSamplePlan(val steps: IntArray, val totalSamples: Int)

/** Preserve the existing chord-based resolution, but budget the entire path before allocation. */
internal inline fun splineSamplePlan(size: Int, anchorAt: (Int) -> Translation2d): SplineSamplePlan {
    val steps = IntArray(size - 1)
    var total = 1L
    var previous = anchorAt(0)
    for (index in 1 until size) {
        val current = anchorAt(index)
        val requested = hypot(current.x - previous.x, current.y - previous.y) / 0.05
        require(requested.isFinite() && requested < MAX_TRAJECTORY_SAMPLES) { "Spline segment exceeds finite sample budget" }
        val count = maxOf(10, requested.toInt())
        total += count
        require(total <= MAX_TRAJECTORY_SAMPLES) { "Spline exceeds the $MAX_TRAJECTORY_SAMPLES-sample budget" }
        steps[index - 1] = count
        previous = current
    }
    return SplineSamplePlan(steps, total.toInt())
}
