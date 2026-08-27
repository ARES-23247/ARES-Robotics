package com.areslib.pathing

import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PathingChampionshipTest {

    @Test
    fun testCostmapInflation() {
        val costmap = Costmap(widthMeters = 6.0, heightMeters = 6.0, resolutionMeters = 0.1)
        costmap.clear()

        // Set obstacle exactly at the center (0.0, 0.0)
        costmap.setObstacle(0.0, 0.0, true)

        // Inflate by 0.3 meters (robot bumper radius)
        costmap.inflate(robotRadiusMeters = 0.3)

        // The exact center should be non-traversable
        assertFalse(costmap.isTraversable(0.0, 0.0))

        // A cell 0.15m away should be non-traversable (within the 0.3m inflation radius)
        assertFalse(costmap.isTraversable(0.15, 0.0))
        assertFalse(costmap.isTraversable(-0.15, 0.15))

        // A cell 1.0m away should be perfectly traversable
        assertTrue(costmap.isTraversable(1.0, 1.0))
        assertTrue(costmap.isTraversable(-1.0, 0.0))
    }

    @Test
    fun testThetaStarPlanner() {
        val costmap = Costmap(widthMeters = 10.0, heightMeters = 10.0, resolutionMeters = 0.1)
        costmap.clear()

        // Place a thick wall in the middle, blocking the straight line from (-2.0, 0) to (2.0, 0)
        // Obstacle wall from y = -2.0 to y = 2.0 at x = 0.0
        var y = -2.0
        while (y <= 2.0) {
            costmap.setObstacle(0.0, y, true)
            y += 0.1
        }

        // Inflate costmap
        costmap.inflate(robotRadiusMeters = 0.2)

        val start = Translation2d(-3.0, 0.0)
        val end = Translation2d(3.0, 0.0)

        // Plan path
        val path = ThetaStarPlanner.plan(costmap, start, end)

        // Verify path was found
        assertTrue(path.isNotEmpty(), "Theta* should find a valid detour path around the wall")

        // Verify start and end points
        assertEquals(start.x, path.first().x, 1e-6)
        assertEquals(start.y, path.first().y, 1e-6)
        assertEquals(end.x, path.last().x, 1e-6)
        assertEquals(end.y, path.last().y, 1e-6)

        // Verify no path points lie inside obstacles
        for (point in path) {
            assertTrue(costmap.isTraversable(point.x, point.y), "Path point $point should not be inside obstacle inflation")
        }
    }

    @Test
    fun testSCurveTrajectoryParameterizer() {
        val waypoints = listOf(
            Translation2d(0.0, 0.0),
            Translation2d(1.0, 1.0),
            Translation2d(2.0, 0.0)
        )

        val constraints = SCurveTrajectoryParameterizer.Constraints(
            maxVelocityMps = 3.0,
            maxAccelerationMps2 = 2.0,
            maxJerkMps3 = 10.0,
            maxCentripetalAccelMps2 = 2.5
        )

        val path = SCurveTrajectoryParameterizer.generateTrajectory(
            waypoints = waypoints,
            constraints = constraints,
            startHeading = Rotation2d(0.0),
            endHeading = Rotation2d(Math.PI / 2.0)
        )

        assertTrue(path.points.isNotEmpty())

        // Verify starting point is at (0, 0) and has 0.0 velocity
        val startPoint = path.points.first()
        assertEquals(0.0, startPoint.pose.x, 1e-6)
        assertEquals(0.0, startPoint.pose.y, 1e-6)
        assertEquals(0.0, startPoint.velocityMps, 1e-6)
        assertEquals(0.0, startPoint.pose.heading.radians, 1e-6)

        // Verify ending point is at (2, 0) and has 0.0 velocity and ending heading
        val endPoint = path.points.last()
        assertEquals(2.0, endPoint.pose.x, 1e-6)
        assertEquals(0.0, endPoint.pose.y, 1e-6)
        assertEquals(0.0, endPoint.velocityMps, 1e-6)
        assertEquals(Math.PI / 2.0, endPoint.pose.heading.radians, 1e-6)

        // Verify that velocity limits are respected everywhere
        for (point in path.points) {
            assertTrue(point.velocityMps <= constraints.maxVelocityMps + 1e-6, "Velocity ${point.velocityMps} should not exceed limit ${constraints.maxVelocityMps}")
        }
    }
}
