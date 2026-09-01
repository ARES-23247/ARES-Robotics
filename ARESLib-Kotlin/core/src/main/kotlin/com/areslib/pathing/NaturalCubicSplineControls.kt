package com.areslib.pathing

import com.areslib.math.geometry.Translation2d

/**
 * Converts interpolation anchors into cubic Bezier controls with continuous first and second
 * derivatives at every interior anchor. Endpoint second derivatives are zero (natural boundary
 * conditions), so callers do not need to invent tangent magnitudes.
 */
internal fun naturalCubicWaypointControls(
    anchors: List<Translation2d>,
): List<PathPlannerJsonParser.WaypointData> {
    require(anchors.size >= 2) { "A spline requires at least two anchors" }
    val derivativeX = solveNaturalCubicDerivatives(anchors.map(Translation2d::x))
    val derivativeY = solveNaturalCubicDerivatives(anchors.map(Translation2d::y))

    return anchors.indices.map { index ->
        val anchor = anchors[index]
        val derivative = Translation2d(derivativeX[index], derivativeY[index])
        PathPlannerJsonParser.WaypointData(
            anchor = anchor,
            prevControl = if (index == 0) {
                anchor
            } else {
                Translation2d(anchor.x - derivative.x / 3.0, anchor.y - derivative.y / 3.0)
            },
            nextControl = if (index == anchors.lastIndex) {
                anchor
            } else {
                Translation2d(anchor.x + derivative.x / 3.0, anchor.y + derivative.y / 3.0)
            },
        )
    }
}

/** Solves the natural cubic first-derivative system for one Cartesian component. */
private fun solveNaturalCubicDerivatives(values: List<Double>): DoubleArray {
    val last = values.lastIndex
    if (last == 1) {
        val delta = values[1] - values[0]
        return doubleArrayOf(delta, delta)
    }

    val lower = DoubleArray(values.size)
    val diagonal = DoubleArray(values.size)
    val upper = DoubleArray(values.size)
    val right = DoubleArray(values.size)

    diagonal[0] = 2.0
    upper[0] = 1.0
    right[0] = 3.0 * (values[1] - values[0])
    for (index in 1 until last) {
        lower[index] = 1.0
        diagonal[index] = 4.0
        upper[index] = 1.0
        right[index] = 3.0 * (values[index + 1] - values[index - 1])
    }
    lower[last] = 1.0
    diagonal[last] = 2.0
    right[last] = 3.0 * (values[last] - values[last - 1])

    for (index in 1..last) {
        val factor = lower[index] / diagonal[index - 1]
        diagonal[index] -= factor * upper[index - 1]
        right[index] -= factor * right[index - 1]
    }

    val result = DoubleArray(values.size)
    result[last] = right[last] / diagonal[last]
    for (index in last - 1 downTo 0) {
        result[index] = (right[index] - upper[index] * result[index + 1]) / diagonal[index]
    }
    return result
}
