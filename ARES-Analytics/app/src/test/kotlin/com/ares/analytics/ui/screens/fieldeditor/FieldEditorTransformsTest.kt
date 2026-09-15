package com.ares.analytics.ui.screens.fieldeditor

import com.ares.analytics.shared.Obstacle
import com.ares.analytics.shared.PathPoint
import com.ares.analytics.shared.models.League
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class FieldEditorTransformsTest {
    @Test
    fun `rotated rectangle corners match reflection in every league`() {
        fun corners(rect: Obstacle.Rectangle): List<PathPoint> {
            val angle = Math.toRadians(rect.rotation)
            return listOf(-1, 1).flatMap { sx -> listOf(-1, 1).map { sy ->
                val x = sx * rect.width / 2
                val y = sy * rect.height / 2
                PathPoint(rect.centerX + x * cos(angle) - y * sin(angle),
                    rect.centerY + x * sin(angle) + y * cos(angle))
            } }
        }
        val rectangle = Obstacle.Rectangle("r", "Rectangle", 0.75, 0.5, 0.4, 0.2, rotation = 37.0)
        for (league in League.entries) {
            for (mirrorX in listOf(true, false)) {
                val mirrored = (if (mirrorX) FieldEditorTransforms.mirrorObstacleX(rectangle, 2.54, league)
                    else FieldEditorTransforms.mirrorObstacleY(rectangle, 1.4224, league)) as Obstacle.Rectangle
                val expected = corners(rectangle).map { point ->
                    if (mirrorX) point.copy(x = (if (league == League.FTC) 0.0 else 2.54) - point.x)
                    else point.copy(y = (if (league == League.FTC) 0.0 else 1.4224) - point.y)
                }
                val actual = corners(mirrored)
                expected.forEach { point -> assertTrue(actual.any { abs(it.x - point.x) < 1e-12 && abs(it.y - point.y) < 1e-12 }) }
            }
        }
    }

    @Test
    fun `XRP circles and polygons reflect through field dimensions`() {
        val circle = Obstacle.Circle("c", "Circle", 0.54, 0.4224, 0.1)
        assertEquals(2.0, (FieldEditorTransforms.mirrorObstacleX(circle, 2.54, League.XRP) as Obstacle.Circle).centerX, 1e-12)
        assertEquals(1.0, (FieldEditorTransforms.mirrorObstacleY(circle, 1.4224, League.XRP) as Obstacle.Circle).centerY, 1e-12)
        val polygon = Obstacle.Polygon("p", "Triangle", listOf(PathPoint(0.0, 0.0), PathPoint(1.0, 0.0), PathPoint(0.0, 1.0)))
        assertEquals(polygon.copy(vertices = listOf(PathPoint(2.0, 0.0), PathPoint(1.0, 0.0), PathPoint(2.0, 1.0))),
            FieldEditorTransforms.mirrorObstacleX(polygon, 2.0, League.XRP))
        assertEquals(polygon.copy(vertices = listOf(PathPoint(0.0, 2.0), PathPoint(1.0, 2.0), PathPoint(0.0, 1.0))),
            FieldEditorTransforms.mirrorObstacleY(polygon, 2.0, League.XRP))
    }
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
