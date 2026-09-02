package com.ares.analytics.ui.screens.fieldeditor

import com.ares.analytics.shared.Obstacle
import com.ares.analytics.shared.PathPoint
import com.ares.analytics.shared.models.League
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldEditorTransformsTest {
    @Test
    fun `FTC mirrors around the center-origin axes`() {
        val rectangle = Obstacle.Rectangle("r", "Rectangle", 0.75, -0.5, 0.4, 0.2, rotation = 30.0)

        assertEquals(rectangle.copy(centerX = -0.75, rotation = -30.0), FieldEditorTransforms.mirrorObstacleX(rectangle, 3.66, League.FTC))
        assertEquals(rectangle.copy(centerY = 0.5, rotation = -30.0), FieldEditorTransforms.mirrorObstacleY(rectangle, 3.66, League.FTC))
    }

    @Test
    fun `FRC mirrors around the field dimensions`() {
        val circle = Obstacle.Circle("c", "Circle", 2.0, 1.5, 0.25)

        val mirroredX = FieldEditorTransforms.mirrorObstacleX(circle, 16.54, League.FRC) as Obstacle.Circle
        val mirroredY = FieldEditorTransforms.mirrorObstacleY(circle, 8.21, League.FRC) as Obstacle.Circle
        assertEquals(14.54, mirroredX.centerX, 1e-9)
        assertEquals(6.71, mirroredY.centerY, 1e-9)
        assertEquals(circle.copy(centerX = mirroredX.centerX), mirroredX)
        assertEquals(circle.copy(centerY = mirroredY.centerY), mirroredY)
    }

    @Test
    fun `polygon mirrors every vertex without changing metadata`() {
        val polygon = Obstacle.Polygon(
            id = "p",
            name = "Protected zone",
            vertices = listOf(PathPoint(1.0, 2.0), PathPoint(-0.5, 0.25)),
            locked = true,
            colorHex = "#123456",
        )

        assertEquals(
            polygon.copy(vertices = listOf(PathPoint(-1.0, 2.0), PathPoint(0.5, 0.25))),
            FieldEditorTransforms.mirrorObstacleX(polygon, 3.66, League.FTC),
        )
    }
}
