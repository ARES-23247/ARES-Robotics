package com.areslib.pathing

import com.areslib.math.geometry.Translation2d

/**
 * Converts interpolation anchors into cubic Bezier controls with continuous first and second
 * derivatives with respect to uniform segment parameters at every interior anchor.
 * Endpoint second derivatives are zero (natural boundary
 * conditions), so callers do not need to invent tangent magnitudes.
 */
internal fun naturalCubicWaypointControls(
    anchors: List<Translation2d>,
): List<PathPlannerJsonParser.WaypointData> {
    require(anchors.size >= 2) { "A spline requires at least two anchors" }
    val count = anchors.size
    val last = count - 1
    val derivativeX = DoubleArray(count)
    val derivativeY = DoubleArray(count)
    if (count == 2) {
        val dx = anchors[1].x - anchors[0].x
        val dy = anchors[1].y - anchors[0].y
        derivativeX[0] = dx; derivativeX[1] = dx
        derivativeY[0] = dy; derivativeY[1] = dy
    } else {
        // Both coordinates share the same tridiagonal matrix. Factor once and solve both
        // right-hand sides in place; all off-diagonal entries are one.
        val diagonal = DoubleArray(count) { 4.0 }
        diagonal[0] = 2.0
        diagonal[last] = 2.0
        for (index in 0..last) {
            val before = anchors[if (index == 0) 0 else index - 1]
            val after = anchors[if (index == last) last else index + 1]
            derivativeX[index] = 3.0 * (after.x - before.x)
            derivativeY[index] = 3.0 * (after.y - before.y)
        }
        for (index in 1..last) {
            val factor = 1.0 / diagonal[index - 1]
            diagonal[index] -= factor
            derivativeX[index] -= factor * derivativeX[index - 1]
            derivativeY[index] -= factor * derivativeY[index - 1]
        }
        derivativeX[last] /= diagonal[last]
        derivativeY[last] /= diagonal[last]
        for (index in last - 1 downTo 0) {
            derivativeX[index] = (derivativeX[index] - derivativeX[index + 1]) / diagonal[index]
            derivativeY[index] = (derivativeY[index] - derivativeY[index + 1]) / diagonal[index]
        }
    }

    return anchors.indices.map { index ->
        val anchor = anchors[index]
        val dx = derivativeX[index] / 3.0
        val dy = derivativeY[index] / 3.0
        PathPlannerJsonParser.WaypointData(
            anchor = anchor,
            prevControl = if (index == 0) {
                anchor
            } else {
                Translation2d(anchor.x - dx, anchor.y - dy)
            },
            nextControl = if (index == anchors.lastIndex) {
                anchor
            } else {
                Translation2d(anchor.x + dx, anchor.y + dy)
            },
        )
    }
}
