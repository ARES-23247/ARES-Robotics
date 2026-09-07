package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import kotlin.math.*
import kotlin.test.*

class TrajectoryMathAuditTest {
    private val limits = TrajectoryLimits(3.0, 2.0, 8.0, 2.5, 0.05, 0.1)

    @Test
    fun `rotation limited timestamps remain consistent with translational velocity`() {
        val request = TrajectoryRequest(
            listOf(Pose2d(), Pose2d(1.0, 0.0, Rotation2d(PI / 2.0))),
            DriveModel.MECANUM, TrajectoryPreset.SAFE, limits)
        val trajectory = JerkLimitedTrajectoryProvider.generate(request).trajectory!!
        for ((before, after) in trajectory.states.zipWithNext()) {
            val displacement = (before.velocityXMps + after.velocityXMps) *
                (after.timeSeconds - before.timeSeconds) / 2.0
            assertEquals(after.pose.x - before.pose.x, displacement, 1e-10)
            assertTrue(abs(after.angularVelocityRps) <= limits.maxAngularVelocityRps + 1e-9)
        }
    }

    @Test
    fun `reported vector acceleration and finite difference jerk respect limits`() {
        val motionLimits = limits.copy(maxAngularVelocityRps = 5.0, maxAngularAccelerationRps2 = 10.0)
        val request = TrajectoryRequest(
            listOf(Pose2d(), Pose2d(2.0, 0.0), Pose2d(2.0, 2.0)),
            DriveModel.MECANUM, TrajectoryPreset.SAFE, motionLimits)
        val trajectory = JerkLimitedTrajectoryProvider.generate(request).trajectory!!
        for (state in trajectory.states) {
            assertTrue(hypot(state.accelerationXMps2, state.accelerationYMps2) <= motionLimits.maxAccelerationMps2 + 1e-9)
        }
        for ((before, after) in trajectory.states.zipWithNext()) {
            val jerk = hypot(after.accelerationXMps2 - before.accelerationXMps2,
                after.accelerationYMps2 - before.accelerationYMps2) / (after.timeSeconds - before.timeSeconds)
            assertTrue(jerk <= motionLimits.maxJerkMps3 + 1e-9, "Jerk was $jerk")
        }
    }
}
