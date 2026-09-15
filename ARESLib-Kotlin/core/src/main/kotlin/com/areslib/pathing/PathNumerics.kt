package com.areslib.pathing

import com.areslib.math.wrapAngle
import kotlin.math.abs
import kotlin.math.max

/** Convex interpolation of finite endpoints with t in [0, 1], avoiding overflowing differences. */
internal fun pathLerp(start: Double, end: Double, t: Double): Double = when {
    t == 0.0 -> start
    t == 1.0 -> end
    (start < 0.0) != (end < 0.0) -> start * (1.0 - t) + end * t
    else -> start + (end - start) * t
}

/** Shortest-arc interpolation; reduce finite raw angles before subtracting them. */
internal fun pathAngleLerp(start: Double, end: Double, t: Double): Double {
    val from = wrapAngle(start)
    return wrapAngle(from + wrapAngle(wrapAngle(end) - from) * t)
}

/**
 * Projection fraction on a finite segment, clamped to [0, 1]. Normalize direction and query
 * independently so squared lengths cannot overflow or turn tiny nonzero segments into zero.
 * Quarter-scaled coordinates handle finite endpoint differences that overflow subtraction.
 * No allocations; rounding still limits nearly cancelling or extremely unequal components.
 */
internal fun pathProjectionFraction(
    x: Double, y: Double, ax: Double, ay: Double, bx: Double, by: Double
): Double {
    // Axis-aligned segments need only one coordinate. Besides saving work, this keeps an
    // arbitrarily large perpendicular offset from erasing subnormal along-segment geometry.
    if (ax == bx) return if (ay == by) 0.0 else axisFraction(y, ay, by)
    if (ay == by) return axisFraction(x, ax, bx)
    var dx = bx - ax
    var dy = by - ay
    var qx = x - ax
    var qy = y - ay
    if (!dx.isFinite() || !dy.isFinite() || !qx.isFinite() || !qy.isFinite()) {
        dx = bx * 0.25 - ax * 0.25
        dy = by * 0.25 - ay * 0.25
        qx = x * 0.25 - ax * 0.25
        qy = y * 0.25 - ay * 0.25
    }
    val directionScale = max(abs(dx), abs(dy))
    val queryScale = max(abs(qx), abs(qy))
    if (directionScale == 0.0 || queryScale == 0.0) return 0.0
    val ux = dx / directionScale
    val uy = dy / directionScale
    val dot = (qx / queryScale) * ux + (qy / queryScale) * uy
    if (dot <= 0.0) return 0.0
    val denominator = ux * ux + uy * uy
    val ratio = queryScale / directionScale
    val t = if (ratio.isFinite()) (dot / denominator) * ratio
        else (dot * queryScale / directionScale) / denominator
    return t.coerceIn(0.0, 1.0)
}

private fun axisFraction(query: Double, start: Double, end: Double): Double {
    val span = end - start
    val t = if (span.isFinite()) (query - start) / span
        else (query * 0.5 - start * 0.5) / (end * 0.5 - start * 0.5)
    return t.coerceIn(0.0, 1.0)
}
