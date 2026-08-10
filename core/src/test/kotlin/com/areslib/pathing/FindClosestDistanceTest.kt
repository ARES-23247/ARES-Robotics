package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class FindClosestDistanceTest {

    /** Helper to create a simple straight-line path from (x1,y1) to (x2,y2). */
    private fun straightPath(
        x1: Double, y1: Double,
        x2: Double, y2: Double,
        numPoints: Int = 50
    ): Path {
        val totalDist = kotlin.math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
        val points = (0 until numPoints).map { i ->
            val t = i.toDouble() / (numPoints - 1)
            PathPoint(
                pose = Pose2d(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, Rotation2d(0.0)),
                velocityMps = 1.0,
                distanceMeters = totalDist * t,
                curvature = 0.0,
                tangentRadians = kotlin.math.atan2(y2 - y1, x2 - x1)
            )
        }
        return Path(points)
    }

    @Test
    fun `robot at path start returns distance near zero`() {
        val path = straightPath(0.0, 0.0, 2.0, 0.0)
        val dist = path.findClosestDistance(0.0, 0.0)
        assertEquals(0.0, dist, 0.01, "Robot at start should project to distance ~0")
    }

    @Test
    fun `robot at path end returns total distance`() {
        val path = straightPath(0.0, 0.0, 2.0, 0.0)
        val dist = path.findClosestDistance(2.0, 0.0)
        assertEquals(2.0, dist, 0.01, "Robot at end should project to total path length")
    }

    @Test
    fun `robot at midpoint returns half distance`() {
        val path = straightPath(0.0, 0.0, 2.0, 0.0)
        val dist = path.findClosestDistance(1.0, 0.0)
        assertEquals(1.0, dist, 0.05, "Robot at midpoint should project to ~1.0m")
    }

    @Test
    fun `robot offset perpendicular to path projects to nearest point`() {
        val path = straightPath(0.0, 0.0, 2.0, 0.0)
        // Robot is at (1.0, 0.5) — 0.5m above the midpoint of a horizontal path
        val dist = path.findClosestDistance(1.0, 0.5)
        assertEquals(1.0, dist, 0.05, "Perpendicular offset should project to same along-path distance")
    }

    @Test
    fun `robot before path start clamps to start`() {
        val path = straightPath(1.0, 0.0, 3.0, 0.0)
        val dist = path.findClosestDistance(0.0, 0.0)
        assertEquals(0.0, dist, 0.01, "Robot before start should project to path start distance")
    }

    @Test
    fun `robot beyond path end clamps to end`() {
        val path = straightPath(0.0, 0.0, 2.0, 0.0)
        val dist = path.findClosestDistance(3.0, 0.0)
        assertEquals(2.0, dist, 0.01, "Robot beyond end should project to path end distance")
    }

    @Test
    fun `diagonal path projection is accurate`() {
        // Path from (0,0) to (1,1), total distance = sqrt(2) ≈ 1.414
        val path = straightPath(0.0, 0.0, 1.0, 1.0)
        // Robot at (0.5, 0.5) should be at the midpoint
        val dist = path.findClosestDistance(0.5, 0.5)
        val expectedMid = kotlin.math.sqrt(2.0) / 2.0
        assertEquals(expectedMid, dist, 0.05, "Diagonal midpoint should project correctly")
    }

    @Test
    fun `empty path returns zero`() {
        val emptyPath = Path(emptyList())
        assertEquals(0.0, emptyPath.findClosestDistance(1.0, 1.0), 0.001)
    }

    @Test
    fun `single point path returns that point distance`() {
        val singlePoint = Path(listOf(
            PathPoint(Pose2d(1.0, 1.0, Rotation2d(0.0)), 0.0, distanceMeters = 0.5)
        ))
        assertEquals(0.5, singlePoint.findClosestDistance(5.0, 5.0), 0.001)
    }

    @Test
    fun `L-shaped path finds closest on correct segment`() {
        // Path goes right then up: (0,0) -> (1,0) -> (1,1)
        val seg1Points = (0..20).map { i ->
            val t = i / 20.0
            PathPoint(
                pose = Pose2d(t, 0.0, Rotation2d(0.0)),
                velocityMps = 1.0,
                distanceMeters = t,
                curvature = 0.0,
                tangentRadians = 0.0
            )
        }
        val seg2Points = (1..20).map { i ->
            val t = i / 20.0
            PathPoint(
                pose = Pose2d(1.0, t, Rotation2d(kotlin.math.PI / 2)),
                velocityMps = 1.0,
                distanceMeters = 1.0 + t,
                curvature = 0.0,
                tangentRadians = kotlin.math.PI / 2
            )
        }
        val path = Path(seg1Points + seg2Points)

        // Robot at (1.0, 0.5) should project onto the second segment at distance 1.5
        val dist = path.findClosestDistance(1.0, 0.5)
        assertEquals(1.5, dist, 0.1, "Should project onto vertical segment")
    }
}
