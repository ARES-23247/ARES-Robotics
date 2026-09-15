// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ares.analytics.ui.components.pathplanner

import com.ares.analytics.shared.PathPoint
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolygonHitTestTest {
    @Test
    fun `closed boundary includes all edges vertices and repeated closing vertex`() {
        val square = listOf(PathPoint(0.0, 0.0), PathPoint(2.0, 0.0),
            PathPoint(2.0, 2.0), PathPoint(0.0, 2.0))
        for (vertices in listOf(square, square.reversed(), square + square.first())) {
            for (point in square + listOf(PathPoint(1.0, 0.0), PathPoint(2.0, 1.0),
                PathPoint(1.0, 2.0), PathPoint(0.0, 1.0), PathPoint(1.0, 1.0))) {
                assertTrue(pointInPolygon(point.x, point.y, vertices), "Point $point in $vertices")
            }
            assertFalse(pointInPolygon(2.000001, 1.0, vertices))
            assertFalse(pointInPolygon(-0.000001, 1.0, vertices))
        }
        val triangle = listOf(PathPoint(0.0, 0.0), PathPoint(2.0, 0.0), PathPoint(0.0, 2.0))
        assertTrue(pointInPolygon(1.0, 1.0, triangle))
        assertFalse(pointInPolygon(1.001, 1.001, triangle))
    }

    @Test
    fun `incomplete or nonfinite geometry is not a filled polygon`() {
        val triangle = listOf(PathPoint(0.0, 0.0), PathPoint(2.0, 0.0), PathPoint(0.0, 2.0))
        for (count in 0..2) assertFalse(pointInPolygon(0.0, 0.0, triangle.take(count)))
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFalse(pointInPolygon(invalid, 0.0, triangle))
            assertFalse(pointInPolygon(0.0, invalid, triangle))
            assertFalse(pointInPolygon(0.0, 0.0, triangle + PathPoint(invalid, 0.0)))
            assertFalse(pointInPolygon(0.0, 0.0, triangle + PathPoint(0.0, invalid)))
        }
    }
}
