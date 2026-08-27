package com.areslib.pathing

import com.areslib.math.geometry.Translation2d
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PathSafetyBoundaryContractTest {

    @Test
    fun `invalid safety radii fail closed without scanning the path`() {
        val costmap = Costmap(2.0, 2.0, 0.1, Translation2d(-1.0, -1.0))
        val path = listOf(Translation2d(0.0, 0.0))
        val invalidCases = listOf(
            Double.NaN to 0.25,
            Double.POSITIVE_INFINITY to 0.25,
            0.0 to 0.25,
            -0.1 to 0.25,
            0.5 to Double.NaN,
            0.5 to Double.POSITIVE_INFINITY,
            0.5 to -0.01
        )

        for ((searchRadius, robotRadius) in invalidCases) {
            val report = PathSafetyEvaluator.evaluate(path, costmap, searchRadius, robotRadius)
            assertFalse(report.isSafe)
            assertEquals(0.0, report.minimumDistanceToObstacleMeters)
            assertEquals(0.0, report.averageObstacleDensity)
            assertEquals(0.0, report.maxObstacleDensity)
            assertEquals(0.0, report.recommendedSpeedMultiplier)
        }
    }

    @Test
    fun `non-finite path coordinates fail closed without throwing`() {
        val costmap = Costmap(2.0, 2.0, 0.1, Translation2d(-1.0, -1.0))
        val invalidPoints = listOf(
            Translation2d(Double.NaN, 0.0),
            Translation2d(Double.POSITIVE_INFINITY, 0.0),
            Translation2d(Double.NEGATIVE_INFINITY, 0.0),
            Translation2d(0.0, Double.NaN),
            Translation2d(0.0, Double.POSITIVE_INFINITY),
            Translation2d(0.0, Double.NEGATIVE_INFINITY)
        )

        for (point in invalidPoints) {
            val report = PathSafetyEvaluator.evaluate(listOf(point), costmap)
            assertFalse(report.isSafe, "non-finite point $point must fail closed")
            assertEquals(0.0, report.minimumDistanceToObstacleMeters)
            assertEquals(0.0, report.recommendedSpeedMultiplier)
        }
    }

    @Test
    fun `dynamic obstacle remains through exact max age and expires one millisecond later`() {
        val costmap = Costmap(4.0, 4.0, 0.1, Translation2d(-2.0, -2.0))
        costmap.insertDynamicObstacle(0.0, 0.0, 0.2, timestampMs = 1_000L)

        costmap.expireDynamicObstacles(currentTimeMs = 1_050L, maxAgeMs = 50L)
        assertFalse(costmap.isTraversable(0.0, 0.0), "age == maxAge must still be live")

        costmap.expireDynamicObstacles(currentTimeMs = 1_051L, maxAgeMs = 50L)
        assertTrue(costmap.isTraversable(0.0, 0.0), "age > maxAge must be removed")
    }

    @Test
    fun `capacity rejection never rasterizes an obstacle that cannot later expire`() {
        val costmap = Costmap(6.0, 6.0, 0.1, Translation2d(-3.0, -3.0))
        repeat(100) { index ->
            costmap.insertDynamicObstacle(-1.0, -1.0, 0.1, timestampMs = index.toLong())
        }

        costmap.insertDynamicObstacle(2.0, 2.0, 0.2, timestampMs = 100L)

        assertFalse(costmap.isTraversable(-1.0, -1.0))
        assertTrue(costmap.isTraversable(2.0, 2.0), "the untracked 101st obstacle must not leave ghost occupancy")

        costmap.expireDynamicObstacles(currentTimeMs = 1_000L, maxAgeMs = 0L)
        assertTrue(costmap.isTraversable(-1.0, -1.0), "all tracked occupancy counts must drain to zero")
    }
}
