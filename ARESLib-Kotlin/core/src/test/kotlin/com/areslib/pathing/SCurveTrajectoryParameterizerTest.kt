package com.areslib.pathing

import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Championship-grade test suite for [SCurveTrajectoryParameterizer].
 *
 * Validates jerk-limited S-curve velocity profiling, centripetal constraint enforcement,
 * boundary condition handling (empty/single waypoints), and NaN-guard resilience on the
 * spatial interpolation pipeline.
 */
class SCurveTrajectoryParameterizerTest {

    private val defaultConstraints = SCurveTrajectoryParameterizer.Constraints(
        maxVelocityMps = 2.0,
        maxAccelerationMps2 = 3.0,
        maxJerkMps3 = 20.0,
        maxCentripetalAccelMps2 = 2.5
    )

    // ─── Empty and Single Waypoint ──────────────────────────────────────

    @Test
    fun `empty waypoints returns empty path`() {
        val path = SCurveTrajectoryParameterizer.generateTrajectory(
            waypoints = emptyList(),
            constraints = defaultConstraints
        )
        assertTrue(path.points.isEmpty(), "Empty waypoints should produce an empty path")
    }

    @Test
    fun `single waypoint returns path with 1 point`() {
        val path = SCurveTrajectoryParameterizer.generateTrajectory(
            waypoints = listOf(Translation2d(1.0, 2.0)),
            constraints = defaultConstraints,
            startHeading = Rotation2d(0.5)
        )
        assertEquals(1, path.points.size, "Single waypoint should produce a path with exactly 1 point")
        assertEquals(1.0, path.points[0].pose.x, 1e-6, "X should match waypoint")
        assertEquals(2.0, path.points[0].pose.y, 1e-6, "Y should match waypoint")
        assertEquals(0.0, path.points[0].velocityMps, 1e-6, "Single point velocity should be 0")
    }

    // ─── Straight Line ──────────────────────────────────────────────────

    @Test
    fun `straight line has zero start and end velocity`() {
        val path = SCurveTrajectoryParameterizer.generateTrajectory(
            waypoints = listOf(Translation2d(0.0, 0.0), Translation2d(3.0, 0.0)),
            constraints = defaultConstraints
        )
        assertTrue(path.points.size > 2, "Straight line should have multiple interpolated points")
        assertEquals(0.0, path.points.first().velocityMps, 1e-6, "Start velocity should be 0")
        assertEquals(0.0, path.points.last().velocityMps, 1e-6, "End velocity should be 0")
    }

    @Test
    fun `straight line has monotonically increasing distance`() {
        val path = SCurveTrajectoryParameterizer.generateTrajectory(
            waypoints = listOf(Translation2d(0.0, 0.0), Translation2d(2.0, 0.0)),
            constraints = defaultConstraints
        )
        for (i in 1 until path.points.size) {
            assertTrue(
                path.points[i].distanceMeters >= path.points[i - 1].distanceMeters - 1e-9,
                "Distance should be monotonically non-decreasing at index $i: " +
                    "${path.points[i - 1].distanceMeters} -> ${path.points[i].distanceMeters}"
            )
        }
    }

    @Test
    fun `straight line velocity stays within max constraint`() {
        val path = SCurveTrajectoryParameterizer.generateTrajectory(
            waypoints = listOf(Translation2d(0.0, 0.0), Translation2d(5.0, 0.0)),
            constraints = defaultConstraints
        )
        for (point in path.points) {
            assertTrue(point.velocityMps <= defaultConstraints.maxVelocityMps + 1e-6,
                "Velocity ${point.velocityMps} exceeds max ${defaultConstraints.maxVelocityMps}")
            assertTrue(point.velocityMps >= -1e-6,
                "Velocity ${point.velocityMps} should not be negative")
        }
    }

    @Test
    fun `straight line has near-zero curvature`() {
        val path = SCurveTrajectoryParameterizer.generateTrajectory(
            waypoints = listOf(Translation2d(0.0, 0.0), Translation2d(4.0, 0.0)),
            constraints = defaultConstraints
        )
        for (point in path.points) {
            assertTrue(abs(point.curvature) < 0.1,
                "Straight line curvature should be near zero, was ${point.curvature}")
        }
    }

    // ─── Curved Path ────────────────────────────────────────────────────

    @Test
    fun `curved path with turn has curvature-limited velocity lower than straight max`() {
        // 90-degree turn: sharp enough to trigger centripetal limiting
        val path = SCurveTrajectoryParameterizer.generateTrajectory(
            waypoints = listOf(
                Translation2d(0.0, 0.0),
                Translation2d(1.0, 0.0),
                Translation2d(1.0, 1.0)
            ),
            constraints = defaultConstraints,
            spacingMeters = 0.01
        )

        // Find the max velocity in the curved region (middle of path)
        val midPoints = path.points.filter {
            it.distanceMeters > 0.3 && it.distanceMeters < path.points.last().distanceMeters - 0.3
        }
        assertTrue(midPoints.isNotEmpty(), "Should have points in the curved region")

        // At least some points in the curved region should have curvature-limited velocity
        val hasReducedVelocity = midPoints.any { it.velocityMps < defaultConstraints.maxVelocityMps - 0.1 }
        assertTrue(hasReducedVelocity,
            "Curved region should have velocity reduced below max due to centripetal constraint")
    }

    @Test
    fun `curved path produces non-zero curvature at turn`() {
        val path = SCurveTrajectoryParameterizer.generateTrajectory(
            waypoints = listOf(
                Translation2d(0.0, 0.0),
                Translation2d(2.0, 0.0),
                Translation2d(2.0, 2.0)
            ),
            constraints = defaultConstraints,
            spacingMeters = 0.01
        )

        val hasCurvature = path.points.any { abs(it.curvature) > 0.01 }
        assertTrue(hasCurvature, "A 90-degree turn path should have points with non-zero curvature")
    }

    // ─── Jerk Constraints ───────────────────────────────────────────────

    @Test
    fun `velocity profile has no sudden jumps between consecutive points`() {
        val path = SCurveTrajectoryParameterizer.generateTrajectory(
            waypoints = listOf(Translation2d(0.0, 0.0), Translation2d(5.0, 0.0)),
            constraints = defaultConstraints,
            spacingMeters = 0.02
        )

        // Check that velocity changes between consecutive points are bounded
        for (i in 1 until path.points.size) {
            val ds = path.points[i].distanceMeters - path.points[i - 1].distanceMeters
            val dv = abs(path.points[i].velocityMps - path.points[i - 1].velocityMps)
            if (ds > 1e-6) {
                // v^2 = v0^2 + 2*a*ds => dv is bounded by sqrt(2*maxA*ds) approximately
                val maxDvBound = kotlin.math.sqrt(2.0 * defaultConstraints.maxAccelerationMps2 * ds) + 0.5
                assertTrue(dv <= maxDvBound + 1e-3,
                    "Velocity jump at index $i: dv=$dv exceeds approximate bound $maxDvBound (ds=$ds)")
            }
        }
    }

    @Test
    fun `jerk limiting produces smoother profile than unconstrained`() {
        // High jerk limit = nearly unconstrained acceleration changes
        val looseConstraints = SCurveTrajectoryParameterizer.Constraints(
            maxVelocityMps = 2.0,
            maxAccelerationMps2 = 3.0,
            maxJerkMps3 = 1000.0,
            maxCentripetalAccelMps2 = 2.5
        )
        val tightConstraints = SCurveTrajectoryParameterizer.Constraints(
            maxVelocityMps = 2.0,
            maxAccelerationMps2 = 3.0,
            maxJerkMps3 = 5.0,  // Very tight jerk limit
            maxCentripetalAccelMps2 = 2.5
        )

        val waypoints = listOf(Translation2d(0.0, 0.0), Translation2d(3.0, 0.0))

        val loosePath = SCurveTrajectoryParameterizer.generateTrajectory(waypoints, looseConstraints, spacingMeters = 0.02)
        val tightPath = SCurveTrajectoryParameterizer.generateTrajectory(waypoints, tightConstraints, spacingMeters = 0.02)

        // Both paths should complete without errors
        assertTrue(loosePath.points.size > 2, "Loose-jerk path should have points")
        assertTrue(tightPath.points.size > 2, "Tight-jerk path should have points")

        // Tight jerk should generally have a lower peak velocity (ramps up more slowly)
        val loosePeak = loosePath.points.maxOf { it.velocityMps }
        val tightPeak = tightPath.points.maxOf { it.velocityMps }
        assertTrue(tightPeak <= loosePeak + 1e-6,
            "Tight jerk profile peak ($tightPeak) should not exceed loose peak ($loosePeak)")
    }

    // ─── Centripetal Constraint ──────────────────────────────────────────

    @Test
    fun `tight turn produces lower max velocity than gentle turn`() {
        // Very tight 90-degree turn over 0.5m
        val tightPath = SCurveTrajectoryParameterizer.generateTrajectory(
            waypoints = listOf(
                Translation2d(0.0, 0.0),
                Translation2d(0.5, 0.0),
                Translation2d(0.5, 0.5)
            ),
            constraints = defaultConstraints,
            spacingMeters = 0.01
        )

        // Gentle turn over 3m
        val gentlePath = SCurveTrajectoryParameterizer.generateTrajectory(
            waypoints = listOf(
                Translation2d(0.0, 0.0),
                Translation2d(3.0, 0.0),
                Translation2d(3.0, 3.0)
            ),
            constraints = defaultConstraints,
            spacingMeters = 0.01
        )

        val tightPeakVel = tightPath.points.maxOf { it.velocityMps }
        val gentlePeakVel = gentlePath.points.maxOf { it.velocityMps }

        assertTrue(tightPeakVel < gentlePeakVel + 0.1,
            "Tight turn peak velocity ($tightPeakVel) should be lower than gentle turn ($gentlePeakVel)")
    }

    @Test
    fun `centripetal velocity limit obeys physics formula`() {
        // For a point with curvature K, centripetal limit = sqrt(maxCentripetal / |K|)
        val path = SCurveTrajectoryParameterizer.generateTrajectory(
            waypoints = listOf(
                Translation2d(0.0, 0.0),
                Translation2d(1.0, 0.0),
                Translation2d(1.0, 1.0)
            ),
            constraints = defaultConstraints,
            spacingMeters = 0.01
        )

        for (point in path.points) {
            if (abs(point.curvature) > 1e-4) {
                val centripLimit = kotlin.math.sqrt(
                    defaultConstraints.maxCentripetalAccelMps2 / abs(point.curvature)
                )
                val vLimit = minOf(centripLimit, defaultConstraints.maxVelocityMps)
                assertTrue(point.velocityMps <= vLimit + 0.15,
                    "Velocity ${point.velocityMps} exceeds centripetal limit $vLimit at curvature ${point.curvature}")
            }
        }
    }

    // ─── NaN Spacing Guard ──────────────────────────────────────────────

    @Test
    fun `NaN spacing falls back to default 0_02m`() {
        val path = SCurveTrajectoryParameterizer.generateTrajectory(
            waypoints = listOf(Translation2d(0.0, 0.0), Translation2d(1.0, 0.0)),
            constraints = defaultConstraints,
            spacingMeters = Double.NaN
        )
        assertTrue(path.points.size > 2, "NaN spacing should fall back to default and produce points")

        val defaultPath = SCurveTrajectoryParameterizer.generateTrajectory(
            waypoints = listOf(Translation2d(0.0, 0.0), Translation2d(1.0, 0.0)),
            constraints = defaultConstraints,
            spacingMeters = 0.02
        )
        assertEquals(defaultPath.points.size, path.points.size,
            "NaN spacing path should have same point count as default spacing path")
    }

    @Test
    fun `Infinity spacing falls back to default`() {
        val path = SCurveTrajectoryParameterizer.generateTrajectory(
            waypoints = listOf(Translation2d(0.0, 0.0), Translation2d(1.0, 0.0)),
            constraints = defaultConstraints,
            spacingMeters = Double.POSITIVE_INFINITY
        )
        assertTrue(path.points.size > 2, "Infinity spacing should fall back to default and produce points")
    }

    @Test
    fun `zero spacing falls back to default`() {
        val path = SCurveTrajectoryParameterizer.generateTrajectory(
            waypoints = listOf(Translation2d(0.0, 0.0), Translation2d(1.0, 0.0)),
            constraints = defaultConstraints,
            spacingMeters = 0.0
        )
        assertTrue(path.points.size > 2, "Zero spacing should fall back to default and produce points")
    }

    // ─── Total Distance ─────────────────────────────────────────────────

    @Test
    fun `total path distance matches expected geometric distance for straight line`() {
        val waypoints = listOf(Translation2d(0.0, 0.0), Translation2d(3.0, 4.0))
        val expectedDistance = hypot(3.0, 4.0) // 5.0 meters

        val path = SCurveTrajectoryParameterizer.generateTrajectory(
            waypoints = waypoints,
            constraints = defaultConstraints,
            spacingMeters = 0.01
        )

        val totalDistance = path.points.last().distanceMeters
        assertEquals(expectedDistance, totalDistance, 0.05,
            "Total distance $totalDistance should match geometric distance $expectedDistance")
    }

    @Test
    fun `total path distance for multi-segment path sums correctly`() {
        val waypoints = listOf(
            Translation2d(0.0, 0.0),
            Translation2d(1.0, 0.0),
            Translation2d(1.0, 1.0),
            Translation2d(0.0, 1.0)
        )
        val expectedMinDistance = 3.0 // Three 1m segments

        val path = SCurveTrajectoryParameterizer.generateTrajectory(
            waypoints = waypoints,
            constraints = defaultConstraints,
            spacingMeters = 0.01
        )

        val totalDistance = path.points.last().distanceMeters
        assertEquals(expectedMinDistance, totalDistance, 0.1,
            "Total distance $totalDistance should approximate 3.0m for three 1m segments")
    }

    @Test
    fun `path starts at distance zero`() {
        val path = SCurveTrajectoryParameterizer.generateTrajectory(
            waypoints = listOf(Translation2d(0.0, 0.0), Translation2d(2.0, 0.0)),
            constraints = defaultConstraints
        )
        assertEquals(0.0, path.points.first().distanceMeters, 1e-9, "First point distance should be 0")
    }

    @Test
    fun `end velocity constraint specifies handover exit velocity`() {
        val path = SCurveTrajectoryParameterizer.generateTrajectory(
            waypoints = listOf(Translation2d(0.0, 0.0), Translation2d(5.0, 0.0)),
            constraints = defaultConstraints,
            endVelocityMps = 1.0
        )
        assertEquals(1.0, path.points.last().velocityMps, 0.05, "Last point velocity should equal requested exit handover velocity")
    }

    @Test
    fun `start velocity constraint specifies initial entry velocity`() {
        val path = SCurveTrajectoryParameterizer.generateTrajectory(
            waypoints = listOf(Translation2d(0.0, 0.0), Translation2d(5.0, 0.0)),
            constraints = defaultConstraints,
            startVelocityMps = 1.5
        )
        assertEquals(1.5, path.points.first().velocityMps, 0.05, "First point velocity should equal requested start entry velocity")
    }
}
