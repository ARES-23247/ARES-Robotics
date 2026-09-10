package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import kotlin.math.*
import java.util.Random
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TrajectoryNumericalProfileTest {
    @Test fun `tiny nonzero lateral motion retains its travel direction`() {
        val result = JerkLimitedTrajectoryProvider.generate(auditRequest(listOf(Pose2d(), Pose2d(0.0, 1e-12))))
        assertTrue(result.isSuccess, result.diagnostics.toString())
        val states = result.trajectory!!.states
        for (state in states) assertEquals(Math.PI / 2.0, state.pathTangentRadians, 1e-12)
        for ((a, b) in states.zipWithNext()) {
            assertEquals(b.pose.y - a.pose.y, (a.velocityYMps * 0.5 + b.velocityYMps * 0.5) *
                (b.timeSeconds - a.timeSeconds), 1e-20)
        }
    }

    @Test fun `small nonzero curvature still obeys its centripetal speed limit`() {
        val path = SCurveTrajectoryParameterizer.generateTrajectory(listOf(
            com.areslib.math.geometry.Translation2d(), com.areslib.math.geometry.Translation2d(1.0, 0.0),
            com.areslib.math.geometry.Translation2d(2.0, 1e-6)),
            SCurveTrajectoryParameterizer.Constraints(1000.0, 1000.0, 1e8, 1e-5))
        assertTrue(path.points.any { abs(it.curvature) > 0.0 })
        for (point in path.points) {
            assertTrue(point.velocityMps * point.velocityMps * abs(point.curvature) <= 1.0000001e-5,
                "v=${point.velocityMps} curvature=${point.curvature}")
        }
    }

    @Test fun `irregular heading segments respect emitted angular acceleration bounds`() {
        val random = Random(5401)
        repeat(80) { iteration ->
            val firstLength = if (iteration % 2 == 0) 1e-4 else 1.0
            val secondLength = if (iteration % 2 == 0) 1.0 else 1e-4
            val request = auditRequest(listOf(Pose2d(),
                Pose2d(firstLength, 0.0, Rotation2d(random.nextDouble() * 4.0 - 2.0)),
                Pose2d(firstLength + secondLength, 0.0, Rotation2d(random.nextDouble() * 4.0 - 2.0))))
                .copy(limits = auditRequest().limits.copy(maxAngularAccelerationRps2 = 0.01, maxAngularVelocityRps = 5.0))
            val result = JerkLimitedTrajectoryProvider.generate(request)
            assertTrue(result.isSuccess, "case=$iteration ${result.diagnostics}")
            for (state in result.trajectory!!.states) {
                assertTrue(abs(state.angularAccelerationRps2) <= 0.010000001,
                    "case=$iteration alpha=${state.angularAccelerationRps2}")
            }
        }
    }

    @Test fun `tiny positive representable speeds do not trigger an arbitrary zero velocity cutoff`() {
        val request = auditRequest().copy(limits = auditRequest().limits.copy(maxVelocityMps = 1e-10))
        val result = JerkLimitedTrajectoryProvider.generate(request)
        assertTrue(result.isSuccess, result.diagnostics.toString())
        assertTrue(result.trajectory!!.durationSeconds.isFinite())
    }

    @Test fun `submicrosecond segments preserve trapezoidal displacement instead of imposing a clock floor`() {
        val request = auditRequest(listOf(Pose2d(), Pose2d(0.01, 0.0))).copy(
            limits = TrajectoryLimits(1e10, 1e20, 1e30, 1e20, 1e10, 1e20))
        val result = JerkLimitedTrajectoryProvider.generate(request)
        assertTrue(result.isSuccess, result.diagnostics.toString())
        for ((a, b) in result.trajectory!!.states.zipWithNext()) {
            val displacement = (a.velocityXMps * 0.5 + b.velocityXMps * 0.5) * (b.timeSeconds - a.timeSeconds)
            assertEquals(b.pose.x - a.pose.x, displacement, 1e-10)
        }
    }

    @Test fun `unrepresentable scalar speed magnitude cannot leak into distance adapter`() {
        val trajectory = auditTrajectory(auditState(0.0, value = Double.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) { trajectory.toPath() }
    }
}
