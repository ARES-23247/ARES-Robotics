package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TrajectoryProviderBoundaryTest {
    @Test fun `provider engine registration cannot silently replace another provider`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrajectoryPlanner(listOf(JerkLimitedTrajectoryProvider, JerkLimitedTrajectoryProvider))
        }
    }

    @Test fun `explicit supported provider is checked once per request`() {
        var checks = 0
        val provider = object : TrajectoryProvider {
            override val engine = TrajectoryEngine.JERK_LIMITED
            override fun supports(request: TrajectoryRequest): Boolean { checks++; return true }
            override fun generate(request: TrajectoryRequest) = TrajectoryGenerationResult(auditTrajectory(auditState(0.0)))
        }
        assertTrue(TrajectoryPlanner(listOf(provider)).generate(auditRequest().copy(preferredEngine = provider.engine)).isSuccess)
        assertEquals(1, checks)
    }

    @Test fun `finite enormous geometry is rejected before allocating billions of samples`() {
        val diagnostics = validateTrajectoryRequest(auditRequest(listOf(Pose2d(), Pose2d(1e100, 0.0))))
        assertTrue(diagnostics.any { it.severity == TrajectoryDiagnosticSeverity.ERROR })
    }

    @Test fun `subspacing translation from rest has a reachable intermediate sample`() {
        val result = JerkLimitedTrajectoryProvider.generate(auditRequest(listOf(Pose2d(), Pose2d(0.01, 0.0))))
        assertTrue(result.isSuccess, result.diagnostics.toString())
        val trajectory = result.trajectory!!
        assertTrue(trajectory.states.size >= 3)
        assertEquals(0.01, trajectory.states.last().pose.x)
    }

    @Test fun `intermediate waypoint orientation is preserved in generated profile`() {
        val result = JerkLimitedTrajectoryProvider.generate(auditRequest(listOf(
            Pose2d(), Pose2d(1.0, 0.0, Rotation2d(1.0)), Pose2d(2.0, 0.0))))
        assertTrue(result.isSuccess)
        val atWaypoint = result.trajectory!!.states.minBy { kotlin.math.abs(it.pose.x - 1.0) }
        assertEquals(1.0, atWaypoint.pose.heading.radians, 1e-10)
    }
}
