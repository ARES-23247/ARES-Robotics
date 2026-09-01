package com.areslib.pathing

import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI

class NaturalCubicSplineControlsTest {
    @Test
    fun `adjacent cubic segments have matching first and second derivatives`() {
        val waypoints = naturalCubicWaypointControls(
            listOf(
                Translation2d(0.0, 0.0),
                Translation2d(0.8, 1.2),
                Translation2d(2.1, 0.4),
                Translation2d(3.0, 1.8),
            ),
        )

        for (joint in 1 until waypoints.lastIndex) {
            val left = waypoints[joint - 1]
            val center = waypoints[joint]
            val right = waypoints[joint + 1]

            val leftFirst = scale(subtract(center.anchor, center.prevControl), 3.0)
            val rightFirst = scale(subtract(center.nextControl, center.anchor), 3.0)
            assertVectorEquals(leftFirst, rightFirst)

            val leftSecond = secondDerivativeAtEnd(
                left.nextControl,
                center.prevControl,
                center.anchor,
            )
            val rightSecond = secondDerivativeAtStart(
                center.anchor,
                center.nextControl,
                right.prevControl,
            )
            assertVectorEquals(leftSecond, rightSecond)
        }
    }

    @Test
    fun `natural endpoint curvature and requested robot headings remain independent`() {
        val anchors = listOf(
            Translation2d(0.0, 0.0),
            Translation2d(1.0, 1.0),
            Translation2d(2.0, -0.5),
        )
        val controls = naturalCubicWaypointControls(anchors)
        assertVectorEquals(
            Translation2d(0.0, 0.0),
            secondDerivativeAtStart(
                controls[0].anchor,
                controls[0].nextControl,
                controls[1].prevControl,
            ),
        )
        assertVectorEquals(
            Translation2d(0.0, 0.0),
            secondDerivativeAtEnd(
                controls[1].nextControl,
                controls[2].prevControl,
                controls[2].anchor,
            ),
        )

        val path = SplineMotionProfiler.generateHermitePath(
            points = anchors,
            startHeading = Rotation2d(PI / 2.0),
            endHeading = Rotation2d(-PI / 2.0),
            maxVelocityMps = 2.0,
            maxAccelerationMps2 = 1.0,
        )
        assertTrue(path.points.size > anchors.size)
        assertEquals(PI / 2.0, path.points.first().pose.heading.radians, 1e-9)
        assertEquals(-PI / 2.0, path.points.last().pose.heading.radians, 1e-9)
    }

    private fun secondDerivativeAtStart(
        p0: Translation2d,
        p1: Translation2d,
        p2: Translation2d,
    ): Translation2d = scale(add(subtract(p0, scale(p1, 2.0)), p2), 6.0)

    private fun secondDerivativeAtEnd(
        p1: Translation2d,
        p2: Translation2d,
        p3: Translation2d,
    ): Translation2d = scale(add(subtract(p3, scale(p2, 2.0)), p1), 6.0)

    private fun add(left: Translation2d, right: Translation2d) =
        Translation2d(left.x + right.x, left.y + right.y)

    private fun subtract(left: Translation2d, right: Translation2d) =
        Translation2d(left.x - right.x, left.y - right.y)

    private fun scale(value: Translation2d, factor: Double) =
        Translation2d(value.x * factor, value.y * factor)

    private fun assertVectorEquals(expected: Translation2d, actual: Translation2d) {
        assertEquals(expected.x, actual.x, 1e-9)
        assertEquals(expected.y, actual.y, 1e-9)
    }
}
