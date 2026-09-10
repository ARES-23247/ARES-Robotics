package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.wrapAngle
import java.math.BigDecimal
import java.util.LinkedList
import java.util.Random
import java.util.RandomAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TimedTrajectoryContractTest {
    @Test fun `every scalar and force component must be finite`() {
        for (bad in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val base = auditState(0.0)
            val invalid = listOf(base.copy(timeSeconds = bad), base.copy(pose = Pose2d(bad, 0.0)),
                base.copy(pose = Pose2d(0.0, bad)), base.copy(pose = Pose2d(0.0, 0.0, Rotation2d(bad))),
                base.copy(velocityXMps = bad), base.copy(velocityYMps = bad), base.copy(angularVelocityRps = bad),
                base.copy(accelerationXMps2 = bad), base.copy(accelerationYMps2 = bad), base.copy(angularAccelerationRps2 = bad),
                base.copy(distanceMeters = bad), base.copy(curvature = bad), base.copy(pathTangentRadians = bad),
                base.copy(moduleFeedforwards = listOf(ModuleForceFeedforward(bad, 0.0))),
                base.copy(moduleFeedforwards = listOf(ModuleForceFeedforward(0.0, bad))))
            for (state in invalid) assertThrows(IllegalArgumentException::class.java) { auditTrajectory(state) }
        }
    }

    @Test fun `timeline and marker bounds fail before sampling`() {
        assertThrows(IllegalArgumentException::class.java) { auditTrajectory() }
        assertThrows(IllegalArgumentException::class.java) { auditTrajectory(auditState(1.0)) }
        assertThrows(IllegalArgumentException::class.java) { auditTrajectory(auditState(0.0), auditState(0.0)) }
        assertThrows(IllegalArgumentException::class.java) { auditTrajectory(auditState(0.0), auditState(2.0), auditState(1.0)) }
        assertThrows(IllegalArgumentException::class.java) { auditTrajectory(auditState(0.0).copy(distanceMeters = 2.0), auditState(1.0)) }
        for (time in doubleArrayOf(-1.0, 3.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                TimedTrajectory(listOf(auditState(0.0), auditState(2.0)), listOf(TimedTrajectoryEvent(CommandKey("shoot"), time)), TrajectoryEngine.JERK_LIMITED)
            }
        }
        val trajectory = auditTrajectory(auditState(0.0), auditState(2.0))
        for (time in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY))
            assertThrows(IllegalArgumentException::class.java) { trajectory.sample(time) }
        assertSame(trajectory.states.first(), trajectory.sample(-Double.MAX_VALUE))
        assertSame(trajectory.states.last(), trajectory.sample(Double.MAX_VALUE))
    }

    @Test fun `owned values retain copy equality destructuring and random access semantics`() {
        val linked = object : LinkedList<TimedTrajectoryState>() {
            var indexedReads = 0
            override fun get(index: Int): TimedTrajectoryState { indexedReads++; return super.get(index) }
        }
        repeat(1024) { linked.add(auditState(it.toDouble())) }
        val trajectory = TimedTrajectory(linked, engine = TrajectoryEngine.JERK_LIMITED)
        assertEquals(0, linked.indexedReads)
        assertTrue(trajectory.states is RandomAccess)
        assertSame(linked.first, trajectory.states.first(), "Already immutable empty-force states need no copy")
        val copy = trajectory.copy()
        val (states, events, engine) = copy
        assertEquals(trajectory, copy); assertEquals(trajectory.hashCode(), copy.hashCode())
        assertEquals(trajectory.toString(), copy.toString()); assertEquals(trajectory.states, states)
        assertEquals(emptyList<TimedTrajectoryEvent>(), events); assertEquals(TrajectoryEngine.JERK_LIMITED, engine)
        assertNotEquals(trajectory, copy.copy(engine = TrajectoryEngine.ONLINE_REPLAN))
        assertNotEquals(trajectory, null); assertNotEquals(trajectory, "trajectory")
        assertThrows(IllegalArgumentException::class.java) { copy.copy(states = emptyList()) }
    }

    @Test fun `events and nearest force snapshots preserve adapter meaning without exposing aliases`() {
        val a = auditState(0.0).copy(moduleFeedforwards = listOf(ModuleForceFeedforward(1.0, 2.0)))
        val b = auditState(2.0).copy(moduleFeedforwards = listOf(ModuleForceFeedforward(3.0, 4.0)))
        val trajectory = TimedTrajectory(listOf(a, b), listOf(TimedTrajectoryEvent(CommandKey("shoot"), 1.0)), TrajectoryEngine.JERK_LIMITED)
        assertEquals(a.moduleFeedforwards, trajectory.sample(0.5).moduleFeedforwards)
        assertEquals(b.moduleFeedforwards, trajectory.sample(1.0).moduleFeedforwards)
        assertThrows(UnsupportedOperationException::class.java) { (trajectory.events as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (trajectory.states[0].moduleFeedforwards as MutableList).clear() }
        val path = trajectory.toPath()
        assertEquals(1.0, path.events.single().triggerDistanceMeters)
        path.points.first().pose = Pose2d(999.0, 0.0)
        assertEquals(0.0, trajectory.states.first().pose.x)
    }

    @Test fun `finite exponent grid interpolation stays convex and agrees with decimal oracle`() {
        val random = Random(5402)
        fun number(): Double {
            var result: Double
            do { result = Double.fromBits(random.nextLong()) } while (!result.isFinite())
            return result
        }
        repeat(2000) { iteration ->
            val a = number(); val b = number(); val fraction = (iteration % 7 + 1) / 8.0
            val actual = auditTrajectory(auditState(0.0, value = a), auditState(1.0, value = b)).sample(fraction).velocityXMps
            val expected = BigDecimal(a).multiply(BigDecimal(1.0 - fraction)).add(BigDecimal(b).multiply(BigDecimal(fraction))).toDouble()
            assertTrue(actual.isFinite() && actual >= minOf(a, b) && actual <= maxOf(a, b))
            assertEquals(expected, actual, Math.ulp(maxOf(kotlin.math.abs(a), kotlin.math.abs(b))) * 4.0,
                "case=$iteration")
        }
    }

    @Test fun `wrapped interpolation crosses the angle boundary along the short arc`() {
        val a = auditState(0.0, Pose2d(0.0, 0.0, Rotation2d(Math.toRadians(170.0))))
        val b = auditState(2.0, Pose2d(2.0, 0.0, Rotation2d(Math.toRadians(-170.0))))
        assertEquals(-Math.PI, wrapAngle(auditTrajectory(a, b).sample(1.0).pose.heading.radians), 1e-12)
    }
}
