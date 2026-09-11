// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ares.analytics.viewmodel.field

import com.ares.analytics.shared.AprilTagPlacement
import com.ares.analytics.shared.GamePiece
import com.ares.analytics.shared.Obstacle
import com.ares.analytics.shared.PathPoint
import com.ares.analytics.shared.models.League
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FieldValidationAuditTest {
    @Test
    fun `polygon bounds scan each obstacle a bounded number of times`() {
        var reads = 0
        val points = listOf(PathPoint(0.0, 0.0), PathPoint(0.2, 0.0), PathPoint(0.0, 0.2))
        val vertices = object : AbstractList<PathPoint>() {
            override val size: Int get() = points.size
            override fun get(index: Int): PathPoint { reads++; return points[index] }
        }
        val count = 40
        val obstacles = List(count) { Obstacle.Polygon("p-$it", "Polygon $it", vertices) }
        val issues = FieldEditorValidator.validate(League.FTC, 2.0, 2.0, obstacles,
            emptyList(), emptyList(), emptyList())
        assertEquals(count * (count - 1) / 2, issues.size)
        assertTrue(issues.all { it.severity == FieldValidationSeverity.WARNING && it.elementIds.size == 2 })
        assertTrue(reads <= count * 20, "Validation repeatedly scanned vertices: $reads reads")
    }

    @Test
    fun `bounds errors rotated extents and tag margins retain their severity across leagues`() {
        for (league in League.entries) {
            val minX = if (league == League.FRC) 0.0 else -1.0
            val maxX = if (league == League.FRC) 2.0 else 1.0
            val issues = FieldEditorValidator.validate(league, 2.0, 2.0,
                listOf(Obstacle.Circle("bad", "Bad", -100.0, -100.0, 0.0),
                    Obstacle.Rectangle("rotated", "Rotated", maxX - 0.1, 0.5, 0.5, 0.5, 45.0)),
                listOf(GamePiece("piece", "Piece", minX - 0.01, 0.5)),
                listOf(AprilTagPlacement("edge", 7, minX - 0.25, 0.5),
                    AprilTagPlacement("outside", 7, minX - 0.251, 0.5)), emptyList())
            assertEquals(setOf(setOf("bad"), setOf("edge", "outside")),
                issues.filter { it.severity == FieldValidationSeverity.ERROR }.map { it.elementIds }.toSet())
            assertEquals(setOf(setOf("rotated"), setOf("piece"), setOf("outside")),
                issues.filter { it.severity == FieldValidationSeverity.WARNING }.map { it.elementIds }.toSet())
        }
    }
}
