package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.wrapAngle
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TimedTrajectoryBoundaryTest {
    @Test fun `nonfinite raw waypoint and state headings are rejected before wrapping`() {
        for (bad in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val pose = Pose2d(1.0, 0.0, Rotation2d(bad))
            assertTrue(validateTrajectoryRequest(auditRequest(listOf(Pose2d(), pose))).any { it.code == "invalid_waypoint" })
            assertThrows(IllegalArgumentException::class.java) { auditTrajectory(auditState(0.0, pose = pose)) }
        }
    }

    @Test fun `finite opposite extremes interpolate without overflowing scalar components`() {
        val a = auditState(0.0, pose = Pose2d(-Double.MAX_VALUE, Double.MAX_VALUE), value = -Double.MAX_VALUE)
        val b = auditState(2.0, pose = Pose2d(Double.MAX_VALUE, -Double.MAX_VALUE), value = Double.MAX_VALUE)
        val middle = auditTrajectory(a, b).sample(1.0)
        assertEquals(0.0, middle.pose.x); assertEquals(0.0, middle.pose.y)
        assertEquals(0.0, middle.velocityXMps); assertEquals(0.0, middle.velocityYMps)
        assertEquals(0.0, middle.angularVelocityRps); assertEquals(0.0, middle.accelerationXMps2)
        assertEquals(0.0, middle.accelerationYMps2); assertEquals(0.0, middle.angularAccelerationRps2)
        assertEquals(0.0, middle.curvature)
    }

    @Test fun `trajectory owns state event and force lists after validation`() {
        val forces = mutableListOf(ModuleForceFeedforward(1.0, 2.0))
        val states = mutableListOf(auditState(0.0).copy(moduleFeedforwards = forces), auditState(2.0))
        val events = mutableListOf(TimedTrajectoryEvent(CommandKey("shoot"), 1.0))
        val trajectory = TimedTrajectory(states, events, TrajectoryEngine.JERK_LIMITED)
        states.clear(); events.clear(); forces.clear()
        assertEquals(2.0, trajectory.durationSeconds)
        assertEquals(1, trajectory.events.size)
        assertEquals(listOf(ModuleForceFeedforward(1.0, 2.0)), trajectory.states.first().moduleFeedforwards)
        assertThrows(UnsupportedOperationException::class.java) { (trajectory.states as MutableList).clear() }
    }

    @Test fun `exact interior sample reuses the stored immutable state`() {
        val trajectory = auditTrajectory(auditState(0.0), auditState(1.0), auditState(2.0))
        assertSame(trajectory.states[1], trajectory.sample(1.0))
    }

    @Test fun `large finite tangents use shortest wrapped arc without subtraction overflow`() {
        val before = auditState(0.0).copy(pathTangentRadians = -Double.MAX_VALUE)
        val after = auditState(2.0).copy(pathTangentRadians = Double.MAX_VALUE)
        val expected = wrapAngle(wrapAngle(before.pathTangentRadians) +
            wrapAngle(wrapAngle(after.pathTangentRadians) - wrapAngle(before.pathTangentRadians)) * 0.5)
        assertEquals(expected, wrapAngle(auditTrajectory(before, after).sample(1.0).pathTangentRadians), 1e-12)
    }

    @Test fun `trajectory distances cannot start below zero`() {
        assertThrows(IllegalArgumentException::class.java) { auditTrajectory(auditState(0.0).copy(distanceMeters = -1.0)) }
    }
}

internal fun auditState(time: Double, pose: Pose2d = Pose2d(time, 0.0), value: Double = 0.0) = TimedTrajectoryState(
    time, pose, value, value, value, value, value, value, time, value, 0.0)

internal fun auditTrajectory(vararg states: TimedTrajectoryState) =
    TimedTrajectory(states.toList(), engine = TrajectoryEngine.JERK_LIMITED)

internal fun auditRequest(poses: List<Pose2d> = listOf(Pose2d(), Pose2d(1.0, 0.0))) = TrajectoryRequest(
    poses, DriveModel.SWERVE, TrajectoryPreset.BALANCED, TrajectoryLimits(3.0, 2.0, 8.0, 2.5, 2.0, 3.0))
