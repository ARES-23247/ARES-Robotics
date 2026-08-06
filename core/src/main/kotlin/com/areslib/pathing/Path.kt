package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import kotlin.math.hypot
import com.areslib.math.wrapAngle

/**
 * Trajectory Discrete Point State Representation.
 *
 * Stores the target pose, linear velocity, accumulated distance, curvature, and path tangent direction at a single point along a path.
 *
 * ### Physical Units & Coordinate Conventions:
 * - Pose $[x, y]$: Field-centric position in meters ($m$)
 * - Robot Heading ($\theta$): Radians ($rad$), **CCW-positive** ($0 = +X$, $\frac{\pi}{2} = +Y$)
 * - Tangent Direction ($\theta_{\text{tangent}}$): Tangent heading of the path curve in radians ($rad$), CCW-positive
 * - Linear Velocity ($v$): Target linear speed in meters per second ($m/s$)
 * - Accumulated Distance ($s$): Arc-length distance along trajectory from start in meters ($m$)
 * - Curvature ($\kappa$): Inverse radius of curvature in radians per meter ($rad/m$ or $m^{-1}$)
 *
 * @property pose Desired robot 2D pose [Pose2d].
 * @property velocityMps Target linear velocity in meters per second ($m/s$).
 * @property distanceMeters Accumulated arc-length distance from path origin in meters ($m$).
 * @property curvature Path curvature $\kappa = \frac{d\theta}{ds}$ in $m^{-1}$.
 * @property tangentRadians Tangent direction angle of the spline path curve in radians ($rad$).
 */
data class PathPoint(
    var pose: Pose2d,
    var velocityMps: Double,
    var distanceMeters: Double = 0.0,
    var curvature: Double = 0.0,
    var tangentRadians: Double = 0.0
)

/**
 * Mutable Primitive-Field Path Point Container for Zero-GC Operations.
 */
class MutablePathPoint {
    var x: Double = 0.0
    var y: Double = 0.0
    var headingRad: Double = 0.0
    var velocityMps: Double = 0.0
    var distanceMeters: Double = 0.0
    var curvature: Double = 0.0
    var tangentRadians: Double = 0.0

    /** Converts this mutable container into an immutable [PathPoint]. */
    fun toPathPoint(): PathPoint = PathPoint(
        Pose2d(x, y, Rotation2d(headingRad)),
        velocityMps,
        distanceMeters,
        curvature,
        tangentRadians
    )

    /**
     * Copies values from this container into pre-allocated [out] [PathPoint] without heap allocation.
     *
     * @param out Destination pre-allocated [PathPoint] instance.
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
 * Parameterized Trajectory Path Representation.
 *
 * Stores ordered sequences of trajectory points [PathPoint] and markers [PathEvent].
 * Supports distance-parameterized sampling via linear interpolation.
 *
 * ### Mathematical Formulations:
 * Interpolation parameter $t \in [0.0, 1.0]$ between segment points $\mathbf{P}_k$ and $\mathbf{P}_{k+1}$:
 * $$t = \frac{s_{\text{query}} - s_k}{s_{k+1} - s_k}$$
 * Interp Position:
 * $$\mathbf{x}(t) = (1-t) \mathbf{x}_k + t \mathbf{x}_{k+1}$$
 * Interp Heading:
 * $$\theta(t) = \theta_k + t \cdot \text{wrapAngle}(\theta_{k+1} - \theta_k)$$
 *
 * @property points Ordered list of discrete trajectory points [PathPoint].
 * @property events Ordered list of distance-triggered marker events [PathEvent].
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
     * Creates a mirrored version of this path across the X-axis for the Red Alliance.
     *
     * PathPlanner paths are authored from the BLUE alliance perspective.
     * For RED alliance, the path is mirrored across the X-axis (field mirror symmetry):
     * - X is preserved
     * - Y is negated
     * - Heading and tangent angles are negated
     * - Curvature sign is flipped (reflection inverts curvature)
     * - Distance along the path is preserved
     */
    fun mirrorForRedAlliance(): Path {
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
