// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ares.analytics.ui.components.pathplanner

import com.ares.analytics.shared.PathPoint

/** Even-odd containment for field polygons, including their boundary, in either winding. */
internal fun pointInPolygon(x: Double, y: Double, vertices: List<PathPoint>): Boolean {
    if (vertices.size < 3 || !x.isFinite() || !y.isFinite() ||
        vertices.any { !it.x.isFinite() || !it.y.isFinite() }) return false
    var inside = false
    var previous = vertices.last()
    for (current in vertices) {
        val cross = (x - current.x) * (previous.y - current.y) -
            (y - current.y) * (previous.x - current.x)
        if (cross == 0.0 && x >= minOf(current.x, previous.x) && x <= maxOf(current.x, previous.x) &&
            y >= minOf(current.y, previous.y) && y <= maxOf(current.y, previous.y)) return true
        if ((current.y > y) != (previous.y > y)) {
            val intersectionX = (previous.x - current.x) * (y - current.y) / (previous.y - current.y) + current.x
            if (x < intersectionX) inside = !inside
        }
        previous = current
    }
    return inside
}
