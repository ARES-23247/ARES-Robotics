package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import kotlin.math.hypot
import com.areslib.math.wrapAngle

/**
 * Represents a single point along a trajectory path.
 *
 * @param pose The desired robot pose (position + robot orientation heading).
 * @param velocityMps The target linear velocity at this point in meters per second.
 * @param distanceMeters Accumulated arc-length distance from the path start.
 * @param curvature Signed curvature of the path at this point (1/radius, radians/meter).
 * @param tangentRadians The direction the path is traveling at this point (spline tangent angle).
 *        This is distinct from [pose.heading], which represents the robot's desired orientation
 *        (which can differ for holonomic drivetrains that strafe while facing a different direction).
 */
/**
 * Class implementation for Path Point.
 *
 * Autonomous path planning, trajectory generation, and obstacle avoidance module.
 *
 * ### Coordinate System:
 * Field-centric coordinates in meters ($m$) relative to field origin.
 */
data class PathPoint(
    var pose: Pose2d,
    var velocityMps: Double,
    var distanceMeters: Double = 0.0,
    var curvature: Double = 0.0,
    var tangentRadians: Double = 0.0
)

/**
 * Class implementation for Mutable Path Point.
 *
 * Autonomous path planning, trajectory generation, and obstacle avoidance module.
 *
 * ### Coordinate System:
 * Field-centric coordinates in meters ($m$) relative to field origin.
 */
class MutablePathPoint {
    var x: Double = 0.0
    var y: Double = 0.0
    var headingRad: Double = 0.0
    var velocityMps: Double = 0.0
    var distanceMeters: Double = 0.0
    var curvature: Double = 0.0
    var tangentRadians: Double = 0.0

    /**
     * toPathPoint declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun toPathPoint(): PathPoint = PathPoint(
        Pose2d(x, y, Rotation2d(headingRad)),
        velocityMps,
        distanceMeters,
        curvature,
        tangentRadians
    )

    /**
     * copyInto declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun copyInto(out: PathPoint) {
        out.pose = Pose2d(x, y, Rotation2d(headingRad))
        out.velocityMps = velocityMps
        out.distanceMeters = distanceMeters
        out.curvature = curvature
        out.tangentRadians = tangentRadians
    }
}

/**
 * An immutable data structure representing a parsed trajectory.
 */
data class Path(
    val points: List<PathPoint>,
    val events: List<PathEvent> = emptyList()
) {
    /**
     * Interpolates to find the target PathPoint at a given distance along the path.
     */
    fun sampleAtDistance(distanceMeters: Double): PathPoint {
        if (points.isEmpty()) return PathPoint(Pose2d(0.0, 0.0, Rotation2d.fromDegrees(0.0)), 0.0)
        if (distanceMeters <= points.first().distanceMeters) return points.first()
        if (distanceMeters >= points.last().distanceMeters) return points.last()

        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]

            if (distanceMeters >= p1.distanceMeters && distanceMeters <= p2.distanceMeters) {
                val denom = p2.distanceMeters - p1.distanceMeters
                val t = if (kotlin.math.abs(denom) < 1e-6) 0.0 else (distanceMeters - p1.distanceMeters) / denom
                
                val interpX = p1.pose.x + (p2.pose.x - p1.pose.x) * t
                val interpY = p1.pose.y + (p2.pose.y - p1.pose.y) * t
                
                // For simplicity, linearly interpolate heading. In a production system, use AngleMath.lerp.
                val deltaHeading = p2.pose.heading.radians - p1.pose.heading.radians
                // Normalize delta
                val normDelta = wrapAngle(deltaHeading)
                
                val interpHeading = Rotation2d(p1.pose.heading.radians + normDelta * t)
                val interpVel = p1.velocityMps + (p2.velocityMps - p1.velocityMps) * t
                val interpCurvature = p1.curvature + (p2.curvature - p1.curvature) * t
                
                // Interpolate tangent with angle wrapping
                val deltaTangent = p2.tangentRadians - p1.tangentRadians
                val normTangentDelta = wrapAngle(deltaTangent)
                val interpTangent = p1.tangentRadians + normTangentDelta * t
                
                return PathPoint(
                    Pose2d(interpX, interpY, interpHeading),
                    interpVel,
                    distanceMeters,
                    interpCurvature,
                    interpTangent
                )
            }
        }
        return points.last()
    }

    /**
     * Interpolates in-place to find the target state without heap allocations.
     */
    fun sampleAtDistance(distanceMeters: Double, out: MutablePathPoint) {
        if (points.isEmpty()) {
            out.x = 0.0; out.y = 0.0; out.headingRad = 0.0
            out.velocityMps = 0.0; out.distanceMeters = distanceMeters; out.curvature = 0.0
            out.tangentRadians = 0.0
            return
        }
        if (distanceMeters <= points.first().distanceMeters) {
            val first = points.first()
            out.x = first.pose.x; out.y = first.pose.y; out.headingRad = first.pose.heading.radians
            out.velocityMps = first.velocityMps; out.distanceMeters = first.distanceMeters; out.curvature = first.curvature
            out.tangentRadians = first.tangentRadians
            return
        }
        if (distanceMeters >= points.last().distanceMeters) {
            val last = points.last()
            out.x = last.pose.x; out.y = last.pose.y; out.headingRad = last.pose.heading.radians
            out.velocityMps = last.velocityMps; out.distanceMeters = last.distanceMeters; out.curvature = last.curvature
            out.tangentRadians = last.tangentRadians
            return
        }

        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]

            if (distanceMeters >= p1.distanceMeters && distanceMeters <= p2.distanceMeters) {
                val denom = p2.distanceMeters - p1.distanceMeters
                val t = if (kotlin.math.abs(denom) < 1e-6) 0.0 else (distanceMeters - p1.distanceMeters) / denom
                
                out.x = p1.pose.x + (p2.pose.x - p1.pose.x) * t
                out.y = p1.pose.y + (p2.pose.y - p1.pose.y) * t
                
                val deltaHeading = p2.pose.heading.radians - p1.pose.heading.radians
                val normDelta = wrapAngle(deltaHeading)
                out.headingRad = p1.pose.heading.radians + normDelta * t
                
                out.velocityMps = p1.velocityMps + (p2.velocityMps - p1.velocityMps) * t
                out.distanceMeters = distanceMeters
                out.curvature = p1.curvature + (p2.curvature - p1.curvature) * t
                
                val deltaTangent = p2.tangentRadians - p1.tangentRadians
                val normTangentDelta = wrapAngle(deltaTangent)
                out.tangentRadians = p1.tangentRadians + normTangentDelta * t
                return
            }
        }
        val last = points.last()
        out.x = last.pose.x; out.y = last.pose.y; out.headingRad = last.pose.heading.radians
        out.velocityMps = last.velocityMps; out.distanceMeters = last.distanceMeters; out.curvature = last.curvature
        out.tangentRadians = last.tangentRadians
    }

    /**
     * Creates a mirrored version of this path across the X-axis for the Blue Alliance.
     * FTC Field coordinates: Red is -Y, Blue is +Y.
     * Mirroring across the X-axis negates Y coordinates, headings, tangents, and curvatures.
     */
    fun mirrorForBlueAlliance(): Path {
        val mirroredPoints = points.map { p ->
            PathPoint(
                pose = Pose2d(p.pose.x, -p.pose.y, Rotation2d(-p.pose.heading.radians)),
                velocityMps = p.velocityMps,
                distanceMeters = p.distanceMeters,
                curvature = -p.curvature,
                tangentRadians = -p.tangentRadians
            )
        }
        return Path(mirroredPoints, events)
    }

    /**
     * Creates a 180°-rotated version of this path for the Red Alliance.
     *
     * PathPlanner paths are authored from the BLUE alliance perspective.
     * For RED alliance, the entire path is rotated 180° through the field origin:
     * - X and Y are negated
     * - Heading and tangent angles are offset by π radians
     * - Curvature sign is preserved (rotation doesn't flip curvature)
     * - Distance along the path is preserved
     */
    fun mirrorForRedAlliance(): Path {
        val mirroredPoints = points.map { p ->
            PathPoint(
                pose = Pose2d(-p.pose.x, -p.pose.y, Rotation2d(p.pose.heading.radians + Math.PI)),
                velocityMps = p.velocityMps,
                distanceMeters = p.distanceMeters,
                curvature = p.curvature,
                tangentRadians = p.tangentRadians + Math.PI
            )
        }
        return Path(mirroredPoints, events)
    }

    /**
     * Finds the arc-length distance along this path that is closest to the given field position.
     * Uses point-to-segment projection with sub-sample interpolation for accuracy.
     * 
     * This is used for **closest-point projection**: given the robot's actual position,
     * find where on the planned path it currently is, enabling path resumption
     * or start-offset correction.
     *
     * @param x field X coordinate in meters
     * @param y field Y coordinate in meters
     * @return arc-length distance (meters) of the closest point on the path
     */
    fun findClosestDistance(
        x: Double, 
        y: Double, 
        minDistance: Double = 0.0, 
        maxDistance: Double = Double.MAX_VALUE
    ): Double {
        if (points.isEmpty()) return 0.0
        if (points.size == 1) return points[0].distanceMeters

        var bestDist2 = Double.MAX_VALUE
        var bestArcLength = if (minDistance == 0.0) 0.0 else minDistance

        // Project onto each segment and find the nearest point
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]

            // Skip segments completely outside the search window
            if (p2.distanceMeters < minDistance || p1.distanceMeters > maxDistance) {
                continue
            }

            // Segment vector
            val segX = p2.pose.x - p1.pose.x
            val segY = p2.pose.y - p1.pose.y
            val segLen2 = segX * segX + segY * segY

            val denom = p2.distanceMeters - p1.distanceMeters
            val minT = if (p1.distanceMeters < minDistance && denom > 1e-6) (minDistance - p1.distanceMeters) / denom else 0.0
            val maxT = if (p2.distanceMeters > maxDistance && denom > 1e-6) (maxDistance - p1.distanceMeters) / denom else 1.0

            // Project query point onto segment, clamped to valid window [minT, maxT]
            val t = when {
                segLen2 < 1e-12 -> minT  // degenerate segment
                else -> {
                    val dotProduct = (x - p1.pose.x) * segX + (y - p1.pose.y) * segY
                    (dotProduct / segLen2).coerceIn(minT, maxT)
                }
            }

            // Closest point on segment
            val closestX = p1.pose.x + segX * t
            val closestY = p1.pose.y + segY * t
            val dx = x - closestX
            val dy = y - closestY
            val d2 = dx * dx + dy * dy

            if (d2 < bestDist2) {
                bestDist2 = d2
                // Interpolate arc-length between the two path points
                bestArcLength = p1.distanceMeters + denom * t
            }
        }

        return bestArcLength
    }
}
