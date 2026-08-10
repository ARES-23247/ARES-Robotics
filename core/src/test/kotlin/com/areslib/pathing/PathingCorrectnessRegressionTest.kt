package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import com.areslib.state.RobotFieldObstacle
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `path chainer preserves tangent and rejects spatial discontinuity`() {
        val first = Path(listOf(
            PathPoint(Pose2d(0.0, 0.0, Rotation2d(0.0)), 0.0, 0.0, tangentRadians = 0.0),
            PathPoint(Pose2d(1.0, 0.0, Rotation2d(0.0)), 1.0, 1.0, tangentRadians = 0.0)
        ))
        val continuous = Path(listOf(
            PathPoint(Pose2d(1.0, 0.0, Rotation2d(0.0)), 1.0, 0.0, tangentRadians = PI / 2.0),
            PathPoint(Pose2d(1.0, 1.0, Rotation2d(0.0)), 0.0, 1.0, tangentRadians = PI / 2.0)
        ))
        assertEquals(PI / 2.0, PathChainer.chainPaths(listOf(first, continuous)).points.last().tangentRadians, 1e-9)

        val discontinuous = Path(listOf(
            PathPoint(Pose2d(5.0, 5.0, Rotation2d(0.0)), 0.0, 0.0)
        ))
        assertFailsWith<IllegalArgumentException> { PathChainer.chainPaths(listOf(first, discontinuous)) }
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

    @Test
    fun `path safety rejects out of bounds samples and measures cell edges`() {
        val costmap = Costmap(2.0, 2.0, 0.1, Translation2d(-1.0, -1.0))
        assertFalse(PathSafetyEvaluator.evaluate(listOf(Translation2d(100.0, 0.0)), costmap).isSafe)

        costmap.setObstacle(0.0, 0.0)
        val report = PathSafetyEvaluator.evaluate(
            listOf(Translation2d(0.35, 0.35)),
            costmap,
            searchRadiusMeters = 1.0,
            robotRadiusMeters = 0.0
        )
        assertEquals(kotlin.math.hypot(0.30, 0.30), report.minimumDistanceToObstacleMeters, 0.02)
    }

    @Test
    fun `generated curved trajectory reports curvature`() {
        val path = TrajectoryGenerator.generateTrajectory(
            Pose2d(0.0, 0.0, Rotation2d(0.0)),
            Pose2d(2.0, 2.0, Rotation2d(PI / 2.0)),
            TrajectoryGenerator.PathConstraints(2.0, 2.0)
        )
        assertTrue(path.points.any { abs(it.curvature) > 1e-3 })
    }
}
