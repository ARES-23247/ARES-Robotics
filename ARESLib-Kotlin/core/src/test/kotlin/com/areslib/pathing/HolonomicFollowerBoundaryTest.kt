package com.areslib.pathing

import com.areslib.control.feedback.PIDController
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HolonomicFollowerBoundaryTest {
    @Test
    fun `unrepresentable position difference neutralizes and preserves events`() {
        val drive = FollowerDriveProbe()
        drive.pose = Pose2d(-Double.MAX_VALUE, 0.0)
        val follower = HolonomicPathFollower(drive)
        var events = 0
        follower.startPath(Path(emptyList(), listOf(PathEvent("event", 0.0))))
        follower.onEventTriggered = { events++ }
        follower.update(PathPoint(Pose2d(Double.MAX_VALUE, 0.0), 1.0), 0.02)
        assertEquals(0.0, drive.vx)
        assertEquals(0, events)
    }

    @Test
    fun `recursive callback update is rejected without recursion and outer update stops`() {
        val drive = FollowerDriveProbe()
        val follower = HolonomicPathFollower(drive)
        follower.startPath(Path(emptyList(), listOf(PathEvent("recurse", 0.0))))
        follower.onEventTriggered = { follower.update(followerTarget(), 0.02) }
        assertThrows(IllegalStateException::class.java) { follower.update(followerTarget(), 0.02) }
        assertEquals(1, drive.poseReads)
        assertEquals(0.0, drive.vx)
        follower.update(followerTarget(), 0.02)
        assertTrue(drive.vx > 0.0)
    }

    @Test
    fun `invalid input stop failure is propagated without retrying failed stop`() {
        val drive = FollowerDriveProbe()
        val follower = HolonomicPathFollower(drive)
        val failure = IllegalStateException("neutral failed")
        drive.writeFailure = failure
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            follower.update(followerTarget(), 0.0)
        })
        assertEquals(1, drive.writes)
    }

    @Test
    fun `new path and stop reset integral and derivative histories`() {
        val drive = FollowerDriveProbe()
        val follower = HolonomicPathFollower(drive, PIDController(0.0, 1.0, 1.0))
        val target = PathPoint(Pose2d(1.0, 0.0), 0.0)
        follower.update(target, 0.1)
        assertEquals(0.1, drive.vx, 1e-12)
        follower.update(target, 0.1)
        drive.pose = target.pose
        follower.startPath(Path(emptyList()))
        follower.update(target, 0.1)
        assertEquals(0.0, drive.vx, 1e-12)
        drive.pose = Pose2d()
        follower.update(target, 0.1)
        follower.stop()
        drive.pose = target.pose
        follower.update(target, 0.1)
        assertEquals(0.0, drive.vx, 1e-12)
    }

    @Test
    fun `nonfinite raw headings neutralize rather than wrapping to zero`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (invalidEstimate in listOf(false, true)) {
                val drive = FollowerDriveProbe()
                val follower = HolonomicPathFollower(drive)
                val target = followerTarget()
                if (invalidEstimate) drive.pose = Pose2d(0.0, 0.0, Rotation2d(bad))
                else target.pose = Pose2d(0.0, 0.0, Rotation2d(bad))
                follower.update(target, 0.02)
                assertEquals(0.0, drive.vx, "bad=$bad estimate=$invalidEstimate")
                assertEquals(0.0, drive.vy)
                assertEquals(0.0, drive.omega)
            }
        }
    }

    @Test
    fun `invalid input never consumes a marker and valid retry still fires it`() {
        val invalidators: List<(PathPoint) -> Unit> = listOf(
            { it.pose = Pose2d(Double.NaN, 0.0) },
            { it.pose = Pose2d(0.0, Double.POSITIVE_INFINITY) },
            { it.velocityMps = Double.NaN }, { it.distanceMeters = Double.NaN },
            { it.distanceMeters = Double.POSITIVE_INFINITY }, { it.distanceMeters = -1.0 },
            { it.curvature = Double.NaN }, { it.tangentRadians = Double.POSITIVE_INFINITY })
        for (invalidate in invalidators) {
            val drive = FollowerDriveProbe()
            val follower = HolonomicPathFollower(drive)
            var events = 0
            follower.onEventTriggered = { events++ }
            follower.startPath(Path(emptyList(), listOf(PathEvent("event", 0.0))))
            follower.update(followerTarget().also(invalidate), 0.02)
            assertEquals(0, events)
            assertEquals(0.0, drive.vx)
            follower.update(followerTarget(), 0.02)
            assertEquals(1, events)
        }
    }

    @Test
    fun `invalid timestep stops without consuming events or retaining PID history`() {
        for (dt in listOf(0.0, -0.02, Double.NaN, Double.POSITIVE_INFINITY)) {
            val drive = FollowerDriveProbe()
            val follower = HolonomicPathFollower(drive, PIDController(0.0, 1.0, 0.0))
            val target = PathPoint(Pose2d(1.0, 0.0), 0.0)
            follower.update(target, 0.1)
            var events = 0
            follower.startPath(Path(emptyList(), listOf(PathEvent("event", 1.0))))
            follower.onEventTriggered = { events++ }
            target.distanceMeters = 1.0
            follower.update(target, dt)
            assertEquals(0, events)
            assertEquals(0.0, drive.vx)
            drive.pose = target.pose
            follower.update(target, 0.1)
            assertEquals(0.0, drive.vx)
            assertEquals(1, events)
        }
    }

    @Test
    fun `invalid event distances reject setup and neutralize previous command`() {
        for (distance in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val drive = FollowerDriveProbe()
            val follower = HolonomicPathFollower(drive)
            follower.update(followerTarget(), 0.02)
            assertTrue(drive.vx > 0.0)
            assertThrows(IllegalArgumentException::class.java) {
                follower.startPath(Path(emptyList(), listOf(PathEvent("bad", distance))))
            }
            assertEquals(0.0, drive.vx)
        }
    }

    @Test
    fun `callback mutations cannot change the already validated target command`() {
        val drive = FollowerDriveProbe()
        val follower = HolonomicPathFollower(drive)
        val target = followerTarget()
        follower.startPath(Path(emptyList(), listOf(PathEvent("mutate", 0.0))))
        follower.onEventTriggered = { target.velocityMps = -3.0 }
        follower.update(target, 0.02)
        assertEquals(1.0, drive.vx, 1e-12)
        assertEquals(1, drive.poseReads)
    }

    @Test
    fun `robot frame output and NaN tangent fallback preserve direct following`() {
        val drive = FollowerDriveProbe()
        drive.pose = Pose2d(0.0, 0.0, Rotation2d(Math.PI / 2.0))
        val follower = HolonomicPathFollower(drive)
        val target = PathPoint(drive.pose, 1.0, tangentRadians = 0.0)
        follower.update(target, 0.02)
        assertEquals(0.0, drive.vx, 1e-12)
        assertEquals(-1.0, drive.vy, 1e-12)
        target.tangentRadians = Double.NaN
        follower.update(target, 0.02)
        assertEquals(1.0, drive.vx, 1e-12)
        assertEquals(0.0, drive.vy, 1e-12)
    }
}
