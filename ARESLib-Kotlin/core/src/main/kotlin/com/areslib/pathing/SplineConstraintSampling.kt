package com.areslib.pathing

import java.util.PriorityQueue
import kotlin.math.roundToInt

/** Merge constraint boundaries into the regular grid before allocating geometry. */
internal fun splineRelativePositions(
    data: PathPlannerJsonParser.ParsedPathData,
    plan: SplineSamplePlan,
    stationary: List<SplineStationarySample> = emptyList()
): List<Double> {
    val boundaries = DoubleArray(data.constraintZones.size * 2 + stationary.size)
    data.constraintZones.forEachIndexed { index, zone ->
        boundaries[index * 2] = zone.minWaypointRelativePos
        boundaries[index * 2 + 1] = zone.maxWaypointRelativePos
    }
    stationary.forEachIndexed { index, sample -> boundaries[data.constraintZones.size * 2 + index] = sample.relativePosition }
    return splineRelativePositions(data.waypoints.size, plan, boundaries)
}

internal fun splineRelativePositions(waypointCount: Int, plan: SplineSamplePlan, boundaries: DoubleArray): List<Double> {
    val last = waypointCount - 1.0
    boundaries.sort()
    var extraCount = 0
    for (position in boundaries) {
        if (position == 0.0 || position == last) continue
        if (extraCount > 0 && boundaries[extraCount - 1] == position) continue
        val segment = position.toInt()
        val steps = plan.steps[segment]
        val nearestStep = ((position - segment) * steps).roundToInt()
        if (segment + nearestStep.toDouble() / steps == position) continue
        boundaries[extraCount++] = position
    }
    val total = plan.totalSamples.toLong() + extraCount
    require(total <= MAX_TRAJECTORY_SAMPLES) { "Spline boundaries and critical points exceed the $MAX_TRAJECTORY_SAMPLES-sample budget" }
    val positions = ArrayList<Double>(total.toInt())
    positions.add(0.0)
    var nextBoundary = 0
    for (segment in plan.steps.indices) {
        for (step in 1..plan.steps[segment]) {
            val regular = segment + step.toDouble() / plan.steps[segment]
            while (nextBoundary < extraCount && boundaries[nextBoundary] < regular) {
                positions.add(boundaries[nextBoundary++])
            }
            positions.add(regular)
        }
    }
    return positions
}

/** Monotone-position sweep retaining the first input zone at inclusive boundaries. */
internal class SplineConstraintCursor(source: List<PathPlannerJsonParser.ParsedConstraintsZone>) {
    private val zones = source.toList()
    private val starts = zones.indices.sortedBy { zones[it].minWaypointRelativePos }
    private val active = PriorityQueue<Int>()
    private var nextStart = 0
    private var previous = Double.NEGATIVE_INFINITY
    private var previousAfter = false

    // after=true queries the open interval immediately after a boundary, without rounding
    // a midpoint. At one position, inclusive queries must precede open-interval queries.
    fun at(position: Double, after: Boolean = false): PathPlannerJsonParser.ParsedConstraintsZone? {
        require(position > previous || position == previous && (!previousAfter || after)) {
            "Constraint cursor queries must advance in boundary order"
        }
        previous = position
        previousAfter = after
        while (nextStart < starts.size && zones[starts[nextStart]].minWaypointRelativePos <= position) {
            active.add(starts[nextStart++])
        }
        while (active.isNotEmpty()) {
            val end = zones[active.peek()].maxWaypointRelativePos
            if (end > position || end == position && !after) break
            active.remove()
        }
        return if (active.isEmpty()) null else zones[active.peek()]
    }
}
