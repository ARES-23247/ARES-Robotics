package com.areslib.state

import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Checks an implicitly closed outline during field loading/editing, never in a periodic loop.
 * Concave polygons and forward collinear vertices are valid; repeated vertices, self-touching,
 * crossings, collapsed outlines and adjacent backtracking are not. Either winding is accepted.
 *
 * Edges are sorted by minimum X; disjoint X/Y bounds skip determinant work. The worst case is
 * quadratic for mutually overlapping bounds, without constructing a quadratic pair collection.
 */
internal fun isSimpleFieldPolygon(points: List<RobotFieldPoint>): Boolean {
    if (points.size < 3) return false
    // Canonical callers use array lists, but the public field model also accepts sequential lists.
    val vertices = points.toTypedArray()
    if (vertices.any { !it.x.isFinite() || !it.y.isFinite() }) return false
    var hasCorner = false
    for (i in vertices.indices) {
        val previous = vertices[if (i == 0) vertices.lastIndex else i - 1]
        val current = vertices[i]
        val next = vertices[(i + 1) % vertices.size]
        if (current.x == next.x && current.y == next.y) return false
        if (orientation(previous, current, next) == 0) {
            // Adjacent edges may meet only at their shared vertex, not retrace a segment.
            if (!onSegment(previous, next, current)) return false
        } else hasCorner = true
    }
    if (!hasCorner) return false

    val edges = Array(vertices.size) { i -> PolygonEdge(i, vertices[i], vertices[(i + 1) % vertices.size]) }
    edges.sortWith(Comparator { first, second -> first.minX.compareTo(second.minX) })
    for (i in edges.indices) {
        val first = edges[i]
        for (j in i + 1 until edges.size) {
            val second = edges[j]
            if (second.minX > first.maxX) break
            val separation = abs(first.index - second.index)
            if (separation == 1 || separation == edges.lastIndex) continue
            if (first.maxY < second.minY || second.maxY < first.minY) continue
            if (orientation(first.a, first.b, second.a) * orientation(first.a, first.b, second.b) <= 0 &&
                orientation(second.a, second.b, first.a) * orientation(second.a, second.b, first.b) <= 0
            ) return false
        }
    }
    return true
}

private class PolygonEdge(val index: Int, val a: RobotFieldPoint, val b: RobotFieldPoint) {
    val minX = min(a.x, b.x)
    val maxX = max(a.x, b.x)
    val minY = min(a.y, b.y)
    val maxY = max(a.y, b.y)
}

private fun onSegment(a: RobotFieldPoint, b: RobotFieldPoint, point: RobotFieldPoint): Boolean =
    point.x >= min(a.x, b.x) && point.x <= max(a.x, b.x) &&
        point.y >= min(a.y, b.y) && point.y <= max(a.y, b.y)

/** Sign of the orientation determinant, with exact binary-Double arithmetic on uncertain cases. */
private fun orientation(a: RobotFieldPoint, b: RobotFieldPoint, c: RobotFieldPoint): Int {
    val left = (a.x - c.x) * (b.y - c.y)
    val right = (a.y - c.y) * (b.x - c.x)
    val determinant = left - right
    // A deliberately conservative filter compared with the usual ~3.33e-16 orient2d bound.
    // Very small, overflowing or near-collinear determinants take the exact path; this is an
    // arithmetic switch, not a geometric epsilon that rejects small valid polygons.
    val uncertainty = max(1e-300, (abs(left) + abs(right)) * 1e-14)
    if (determinant.isFinite() && abs(determinant) > uncertainty) return if (determinant > 0.0) 1 else -1

    // BigDecimal(Double) preserves the exact binary input; valueOf would round through text.
    val acx = BigDecimal(a.x).subtract(BigDecimal(c.x))
    val acy = BigDecimal(a.y).subtract(BigDecimal(c.y))
    val bcx = BigDecimal(b.x).subtract(BigDecimal(c.x))
    val bcy = BigDecimal(b.y).subtract(BigDecimal(c.y))
    return acx.multiply(bcy).subtract(acy.multiply(bcx)).signum()
}
