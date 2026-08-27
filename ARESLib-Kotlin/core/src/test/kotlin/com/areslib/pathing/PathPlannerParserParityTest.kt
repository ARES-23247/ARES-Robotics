package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class PathPlannerParserParityTest {

    @Test
    fun testKinematicStatesAndGlobalLimits() {
        val json = """
            {
              "waypoints": [
                {"anchor": {"x": 0.0, "y": 0.0}, "prevControl": null, "nextControl": {"x": 1.0, "y": 0.0}},
                {"anchor": {"x": 4.0, "y": 0.0}, "prevControl": {"x": 3.0, "y": 0.0}, "nextControl": null}
              ],
              "globalConstraints": {
                "maxVelocity": 3.0,
                "maxAcceleration": 2.0
              },
              "idealStartingState": {
                "velocity": 1.2,
                "rotation": 45.0
              },
              "goalEndState": {
                "velocity": 0.8,
                "rotation": -90.0
              }
            }
        """.trimIndent()

        val path = PathPlannerParser.parsePath(json)
        assertNotNull(path)
        assertTrue(path.points.size > 2)

        val firstPoint = path.points.first()
        val lastPoint = path.points.last()

        // Verify starting/ending velocities
        assertEquals(1.2, firstPoint.velocityMps, 1e-4)
        assertEquals(0.8, lastPoint.velocityMps, 1e-4)

        // Verify starting/ending rotations (converted to radians)
        assertEquals(Math.toRadians(45.0), firstPoint.pose.heading.radians, 1e-4)
        assertEquals(Math.toRadians(-90.0), lastPoint.pose.heading.radians, 1e-4)
    }

    @Test
    fun testRotationTargets() {
        val json = """
            {
              "waypoints": [
                {"anchor": {"x": 0.0, "y": 0.0}},
                {"anchor": {"x": 4.0, "y": 0.0}}
              ],
              "idealStartingState": {
                "velocity": 0.0,
                "rotation": 0.0
              },
              "goalEndState": {
                "velocity": 0.0,
                "rotation": 180.0
              },
              "rotationTargets": [
                {
                  "waypointRelativePos": 0.5,
                  "rotationDegrees": 90.0
                }
              ]
            }
        """.trimIndent()

        val path = PathPlannerParser.parsePath(json)
        assertNotNull(path)

        // Verify rotation target is mapped and interpolated correctly
        // At distance midpoint, heading should be close to 90 degrees
        val midpoint = path.sampleAtDistance(path.points.last().distanceMeters / 2.0)
        assertEquals(Math.toRadians(90.0), midpoint.pose.heading.radians, 0.1)
    }

    @Test
    fun testConstraintZones() {
        val json = """
            {
              "waypoints": [
                {"anchor": {"x": 0.0, "y": 0.0}},
                {"anchor": {"x": 10.0, "y": 0.0}}
              ],
              "globalConstraints": {
                "maxVelocity": 3.0,
                "maxAcceleration": 2.0
              },
              "constraintZones": [
                {
                  "name": "Slow Zone",
                  "minWaypointRelativePos": 0.3,
                  "maxWaypointRelativePos": 0.7,
                  "constraints": {
                    "maxVelocity": 0.8,
                    "maxAcceleration": 0.5
                  }
                }
              ]
            }
        """.trimIndent()

        val path = PathPlannerParser.parsePath(json)
        assertNotNull(path)

        // The path segment is 10 meters long (0.0 to 1.0 relative progress).
        // The midpoint (5.0 meters) is at relative progress 0.5 (inside the slow zone).
        // Verify that velocity in the midpoint area does not exceed the slow zone's limit of 0.8 M/S.
        val midpoint = path.sampleAtDistance(5.0)
        assertTrue(midpoint.velocityMps <= 0.8 + 1e-4, "Velocity at midpoint should be limited to 0.8, but was ${midpoint.velocityMps}")
    }

    @Test
    fun testPointTowardsZones() {
        val json = """
            {
              "waypoints": [
                {"anchor": {"x": 0.0, "y": 0.0}},
                {"anchor": {"x": 10.0, "y": 0.0}}
              ],
              "pointTowardsZones": [
                {
                  "name": "Aim Target",
                  "minWaypointRelativePos": 0.4,
                  "maxWaypointRelativePos": 0.6,
                  "rotationOffset": 10.0,
                  "fieldPosition": {
                    "x": 5.0,
                    "y": 5.0
                  }
                }
              ]
            }
        """.trimIndent()

        val path = PathPlannerParser.parsePath(json)
        assertNotNull(path)

        // At midpoint x=5.0, target is at (5.0, 5.0).
        // The vector from (5.0, 0.0) to (5.0, 5.0) has angle of 90 degrees.
        // Adding rotationOffset of 10 degrees yields 100 degrees target heading.
        val midpoint = path.sampleAtDistance(5.0)
        val expectedRad = Math.toRadians(90.0 + 10.0)
        assertEquals(expectedRad, midpoint.pose.heading.radians, 1e-4)
    }
}
