package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class TrajectoryPlanningTest {
    private val limits = TrajectoryLimits(
        maxVelocityMps = 3.0,
        maxAccelerationMps2 = 2.0,
        maxJerkMps3 = 8.0,
        maxCentripetalAccelerationMps2 = 2.5,
        maxAngularVelocityRps = 2.0,
        maxAngularAccelerationRps2 = 3.0
    )

    @Test
    fun `jerk limited provider emits a finite monotonic time trajectory`() {
        val result = JerkLimitedTrajectoryProvider.generate(
            request(
                Pose2d(0.0, 0.0, Rotation2d(0.0)),
                Pose2d(2.0, 1.0, Rotation2d(Math.PI / 2.0))
            )
        )

        assertTrue(result.isSuccess, result.diagnostics.joinToString { it.message })
        val trajectory = requireNotNull(result.trajectory)
        assertTrue(trajectory.durationSeconds > 0.0)
        assertEquals(TrajectoryEngine.JERK_LIMITED, trajectory.engine)
        assertTrue(trajectory.states.zipWithNext().all { (a, b) -> b.timeSeconds > a.timeSeconds })
        assertTrue(trajectory.states.all { abs(it.angularVelocityRps) <= limits.maxAngularVelocityRps + 1e-9 })
        assertTrue(trajectory.states.all {
            abs(it.angularAccelerationRps2) <= limits.maxAngularAccelerationRps2 + 1e-6
        })
    }

    @Test
    fun `fast swerve falls back cleanly when no dynamics optimizer is installed`() {
        val planner = TrajectoryPlanner(listOf(JerkLimitedTrajectoryProvider))
        val result = planner.generate(
            request(
                Pose2d(0.0, 0.0, Rotation2d()),
                Pose2d(1.0, 0.0, Rotation2d()),
                preset = TrajectoryPreset.FAST
            )
        )

        assertTrue(result.isSuccess)
        assertEquals(TrajectoryEngine.JERK_LIMITED, result.trajectory?.engine)
        assertTrue(result.diagnostics.any { it.code == "engine_fallback" })
    }

    @Test
    fun `explicit unavailable engine does not silently change the request`() {
        val planner = TrajectoryPlanner(listOf(JerkLimitedTrajectoryProvider))
        val result = planner.generate(
            request(
                Pose2d(0.0, 0.0, Rotation2d()),
                Pose2d(1.0, 0.0, Rotation2d()),
                preferredEngine = TrajectoryEngine.DYNAMICS_OPTIMIZED
            )
        )

        assertFalse(result.isSuccess)
        assertEquals(null, result.trajectory)
        assertTrue(result.diagnostics.any { it.code == "engine_unavailable" })
    }

    @Test
    fun `non finite waypoint returns actionable validation`() {
        val result = JerkLimitedTrajectoryProvider.generate(
            request(
                Pose2d(Double.NaN, 0.0, Rotation2d()),
                Pose2d(1.0, 0.0, Rotation2d())
            )
        )

        assertFalse(result.isSuccess)
        assertTrue(result.diagnostics.any { it.code == "invalid_waypoint" })
    }

    @Test
    fun `distance adapter preserves tangent at a stationary endpoint`() {
        val trajectory = JerkLimitedTrajectoryProvider.generate(
            request(
                Pose2d(0.0, 0.0, Rotation2d(Math.PI / 2.0)),
                Pose2d(0.0, 2.0, Rotation2d(Math.PI / 2.0))
            )
        ).trajectory!!

        val path = trajectory.toPath()
        assertEquals(Math.PI / 2.0, path.points.first().tangentRadians, 1e-6)
        assertEquals(Math.PI / 2.0, path.points.last().tangentRadians, 1e-6)
    }

    private fun request(
        vararg poses: Pose2d,
        preset: TrajectoryPreset = TrajectoryPreset.BALANCED,
        preferredEngine: TrajectoryEngine? = null
    ): TrajectoryRequest = TrajectoryRequest(
        waypoints = poses.toList(),
        driveModel = DriveModel.SWERVE,
        preset = preset,
        limits = limits,
        preferredEngine = preferredEngine
    )
}
