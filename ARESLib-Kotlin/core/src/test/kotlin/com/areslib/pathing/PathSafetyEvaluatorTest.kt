package com.areslib.pathing

import com.areslib.math.geometry.Translation2d
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PathSafetyEvaluatorTest {

    @Test
    fun testEmptyPath() {
        val costmap = Costmap()
        val report = PathSafetyEvaluator.evaluate(emptyList(), costmap)
        assertFalse(report.isSafe)
        assertEquals(0.0, report.minimumDistanceToObstacleMeters, 1e-6)
        assertEquals(0.0, report.recommendedSpeedMultiplier, 1e-6)
    }

    @Test
    fun testEmptyCostmapIsSafe() {
        val costmap = Costmap()
        val path = listOf(
            Translation2d(-2.0, -2.0),
            Translation2d(2.0, 2.0)
        )

        val report = PathSafetyEvaluator.evaluate(path, costmap)
        assertTrue(report.isSafe)
        assertEquals(10.0, report.minimumDistanceToObstacleMeters, 1e-6) // Default safe distance when clear
        assertEquals(1.0, report.recommendedSpeedMultiplier, 1e-6)
        assertEquals(0.0, report.averageObstacleDensity, 1e-6)
        assertEquals(0.0, report.maxObstacleDensity, 1e-6)
    }

    @Test
    fun testSafePathWithObstacle() {
        val costmap = Costmap()
        // Put obstacle at (0.0, 0.0)
        costmap.setObstacle(0.0, 0.0, true)

        // Path far away from obstacle
        val path = listOf(
            Translation2d(-4.0, -2.0),
            Translation2d(-4.0, 2.0)
        )

        val report = PathSafetyEvaluator.evaluate(path, costmap, robotRadiusMeters = 0.25)
        assertTrue(report.isSafe)
        assertTrue(report.minimumDistanceToObstacleMeters >= 4.0)
        assertEquals(1.0, report.recommendedSpeedMultiplier, 1e-6)
    }

    @Test
    fun testCautionPathNearObstacle() {
        val costmap = Costmap()
        // Put obstacle at (0.0, 0.0)
        costmap.setObstacle(0.0, 0.0, true)

        // Path passing exactly 0.5m away from the obstacle
        val path = listOf(
            Translation2d(-2.0, 0.5),
            Translation2d(2.0, 0.5)
        )

        val report = PathSafetyEvaluator.evaluate(path, costmap, searchRadiusMeters = 0.6, robotRadiusMeters = 0.25)
        // With robotRadius = 0.25:
        // Safe clearance limit = robotRadius + 0.05 = 0.30m
        // Caution zone limit = robotRadius + 0.35 = 0.60m
        // Closest obstacle is at (0.0, 0.0), distance to path point (0.0, 0.5) is 0.5m
        // 0.5m is >= 0.30m -> report.isSafe = true
        // 0.5m is < 0.60m -> caution zone -> report.recommendedSpeedMultiplier = 0.6
        assertTrue(report.isSafe)
        assertEquals(0.5, report.minimumDistanceToObstacleMeters, 0.05)
        assertEquals(0.6, report.recommendedSpeedMultiplier, 1e-6)
        assertTrue(report.maxObstacleDensity > 0.0)
    }

    @Test
    fun testUnsafePathHittingObstacle() {
        val costmap = Costmap()
        // Put obstacle at (0.0, 0.0)
        costmap.setObstacle(0.0, 0.0, true)

        // Path passing directly through the obstacle
        val path = listOf(
            Translation2d(-1.0, 0.0),
            Translation2d(1.0, 0.0)
        )

        val report = PathSafetyEvaluator.evaluate(path, costmap, searchRadiusMeters = 0.5, robotRadiusMeters = 0.25)
        assertFalse(report.isSafe)
        assertEquals(0.0, report.minimumDistanceToObstacleMeters, 0.05)
        assertEquals(0.3, report.recommendedSpeedMultiplier, 1e-6)
        assertTrue(report.maxObstacleDensity > 0.0)
    }
}
