package com.areslib.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RobotFieldPolygonValidationTest {
    private fun polygon(vararg xy: Double) = RobotFieldObstacle(
        id = "boundary", shape = "polygon",
        points = xy.asList().chunked(2).map { RobotFieldPoint(it[0], it[1]) },
    )

    private fun reject(obstacle: RobotFieldObstacle) {
        val issues = RobotFieldValidator.validate(RobotFieldConfig(obstacles = listOf(obstacle)))
        assertEquals(listOf(RobotFieldValidationCode.OBSTACLE_INVALID), issues.map { it.code })
        assertEquals(setOf("boundary"), issues.single().elementIds)
    }

    private fun accept(obstacle: RobotFieldObstacle) {
        assertTrue(RobotFieldValidator.validate(RobotFieldConfig(obstacles = listOf(obstacle))).isEmpty())
    }

    @Test
    fun `crossed bow tie is rejected in either winding`() {
        val crossing = polygon(0.0, 0.0, 2.0, 2.0, 0.0, 2.0, 2.0, 0.0)
        reject(crossing)
        reject(crossing.copy(points = crossing.points.reversed()))
    }

    @Test
    fun `nonadjacent edge touching a vertex is rejected`() {
        reject(polygon(0.0, 0.0, 2.0, 0.0, 2.0, 2.0, 1.0, 0.0, 0.0, 2.0))
    }

    @Test
    fun `adjacent backtracking is rejected`() {
        reject(polygon(0.0, 0.0, 2.0, 0.0, 1.0, 0.0, 2.0, 2.0, 0.0, 2.0))
    }

    @Test
    fun `duplicate adjacent points and duplicate closing point are rejected`() {
        reject(polygon(0.0, 0.0, 2.0, 0.0, 2.0, 0.0, 0.0, 2.0))
        reject(polygon(0.0, 0.0, 2.0, 0.0, 0.0, 2.0, -0.0, 0.0))
    }

    @Test
    fun `a collinear outline is rejected`() {
        reject(polygon(0.0, 0.0, 1.0, 1.0, 2.0, 2.0))
    }

    @Test
    fun `finite coordinates with overflowing cross products still reject crossings`() {
        reject(polygon(-1e200, -1e200, 1e200, 1e200, -1e200, 1e200, 1e200, -1e200))
    }

    @Test
    fun `subnormal cross products still reject crossings`() {
        reject(polygon(0.0, 0.0, 1e-200, 1e-200, 0.0, 1e-200, 1e-200, 0.0))
    }

    @Test
    fun `validated field loading rejects crossed authored polygons`() {
        val json = """{"schemaVersion":2,"fieldType":"ftc","obstacles":[{"id":"boundary","shape":"polygon","points":[{"x":0,"y":0},{"x":2,"y":2},{"x":0,"y":2},{"x":2,"y":0}]}]}"""
        val error = assertThrows(IllegalArgumentException::class.java) {
            ValidatedRobotFieldLoader.load(json.toByteArray(), FieldType.FTC, false)
        }
        assertEquals("Field contains an invalid obstacle", error.message)
    }

    @Test
    fun `convex and concave polygons accept either winding and straight intermediate vertices`() {
        for (valid in listOf(
            polygon(0.0, 0.0, 2.0, 0.0, 2.0, 2.0, 0.0, 2.0),
            polygon(0.0, 0.0, 2.0, 0.0, 2.0, 2.0, 1.0, 1.0, 0.0, 2.0),
            polygon(0.0, 0.0, 1.0, 0.0, 2.0, 0.0, 2.0, 2.0, 0.0, 2.0),
        )) {
            accept(valid)
            accept(valid.copy(points = valid.points.reversed()))
        }
    }

    @Test
    fun `small valid geometry is not rejected by an absolute epsilon`() {
        accept(polygon(0.0, 0.0, 1e-200, 0.0, 1e-200, 1e-200, 0.0, 1e-200))
    }

    @Test
    fun `large valid geometry and large coordinate offsets remain accepted`() {
        accept(polygon(-1e200, -1e200, 1e200, -1e200, 1e200, 1e200, -1e200, 1e200))
        val start = 1e150
        val next = Math.nextUp(start)
        accept(polygon(start, start, next, start, next, next, start, next))
    }

    @Test
    fun `nonfinite or incomplete outlines remain invalid`() {
        reject(polygon(0.0, 0.0, 1.0, 0.0))
        reject(polygon(0.0, 0.0, 1.0, 0.0, Double.NaN, 1.0))
        reject(polygon(0.0, 0.0, 1.0, 0.0, 0.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun `polygon validity survives exact rotations reflections scales and cyclic starting vertices`() {
        val valid = polygon(0.0, 0.0, 4.0, 0.0, 4.0, 4.0, 2.0, 2.0, 0.0, 4.0)
        val invalid = polygon(0.0, 0.0, 4.0, 4.0, 0.0, 4.0, 4.0, 0.0)
        for (source in listOf(valid, invalid)) {
            for (scale in listOf(Math.scalb(1.0, -600), 1.0, Math.scalb(1.0, 600))) {
                for (reflected in listOf(false, true)) {
                    for (quarterTurns in 0..3) {
                        val transformed = source.points.map { point ->
                            var x = if (reflected) -point.x else point.x
                            var y = point.y
                            repeat(quarterTurns) { val oldX = x; x = -y; y = oldX }
                            RobotFieldPoint(x * scale, y * scale)
                        }
                        for (start in transformed.indices) {
                            val shifted = transformed.drop(start) + transformed.take(start)
                            for (points in listOf(shifted, shifted.reversed())) {
                                val obstacle = source.copy(points = points)
                                if (source === valid) accept(obstacle) else reject(obstacle)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `nonblocking polygons still require an unambiguous outline`() {
        reject(polygon(0.0, 0.0, 2.0, 2.0, 0.0, 2.0, 2.0, 0.0).copy(isBlocking = false))
    }

    @Test
    fun `sequential vertex lists are supported without changing the source list`() {
        val source = polygon(0.0, 0.0, 2.0, 0.0, 2.0, 2.0, 0.0, 2.0)
        val linked = java.util.LinkedList(source.points)
        accept(source.copy(points = linked))
        assertEquals(source.points, linked)
    }
}
