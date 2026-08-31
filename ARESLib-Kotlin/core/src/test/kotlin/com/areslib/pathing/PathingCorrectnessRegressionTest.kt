package com.areslib.pathing

import com.areslib.math.geometry.Translation2d
import com.areslib.state.RobotFieldObstacle
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PathingCorrectnessRegressionTest {

    @Test
    fun `s curve preserves vertical path tangent`() {
        val path = SCurveTrajectoryParameterizer.generateTrajectory(
            listOf(Translation2d(0.0, 0.0), Translation2d(0.0, 2.0)),
            SCurveTrajectoryParameterizer.Constraints(2.0, 2.0, 10.0)
        )
        assertTrue(path.points.all { abs(it.tangentRadians - PI / 2.0) < 1e-9 })
    }

    @Test
    fun `costmap uses obstacle width on x axis`() {
        val costmap = Costmap(10.0, 10.0, 0.1, Translation2d(-5.0, -5.0))
        costmap.setStaticObstacles(listOf(RobotFieldObstacle(x = 0.0, y = 0.0, width = 2.0, height = 0.4)))
        assertTrue(costmap.isOccupied(0.9, 0.0))
        assertFalse(costmap.isOccupied(0.0, 0.9))
    }

    @Test
    fun `expiring overlapping dynamic obstacle retains other layers`() {
        val costmap = Costmap(4.0, 4.0, 0.1, Translation2d(-2.0, -2.0))
        costmap.setObstacle(0.0, 0.0)
        costmap.inflate(0.2)
        costmap.insertDynamicObstacle(0.0, 0.0, 0.3, timestampMs = 0L)
        costmap.insertDynamicObstacle(0.0, 0.0, 0.3, timestampMs = 100L)

        costmap.expireDynamicObstacles(currentTimeMs = 60L, maxAgeMs = 50L)
        assertFalse(costmap.isTraversable(0.0, 0.0), "live overlapping/static occupancy must remain")

        costmap.clear()
        costmap.insertDynamicObstacle(0.0, 0.0, 0.3, timestampMs = 200L)
        assertFalse(costmap.isTraversable(0.0, 0.0), "clear must reset dynamic bookkeeping for reuse")
    }

}
