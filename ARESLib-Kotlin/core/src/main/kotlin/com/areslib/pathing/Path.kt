package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import kotlin.math.hypot
import java.util.RandomAccess

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

    /** Allocates an independent mutable [PathPoint] and immutable pose value. */
    fun toPathPoint(): PathPoint = PathPoint(
        Pose2d(x, y, Rotation2d(headingRad)),
        velocityMps,
        distanceMeters,
        curvature,
        tangentRadians
    )

    /**
     * Copies values into [out], allocating an immutable Pose2d only when its coordinates or raw heading change.
     * Moving samples still allocate a pose; use primitive fields directly for allocation-free loops.
     *
     * @param out Destination pre-allocated [PathPoint] instance.
     */
    fun copyInto(out: PathPoint) {
        if (out.pose.x != x || out.pose.y != y || out.pose.heading.rawRadians != headingRad) {
            out.pose = Pose2d(x, y, Rotation2d(headingRad))
        }
        out.velocityMps = velocityMps
        out.distanceMeters = distanceMeters
        out.curvature = curvature
        out.tangentRadians = tangentRadians
    }
}

/**
 * Distance-parameterized path with caller-owned point and event lists and mutable point payloads.
 * Distances must be finite, nonnegative and nondecreasing; sampled geometry/velocity/curvature/
 * raw angles must be finite. Callers must preserve these invariants and not mutate during queries.
 * Queries check the points they visit, not the entire list, so an unvisited malformed point is not
 * necessarily detected. Sampling interpolates scalar fields independently and angles on shortest arcs.
 *
 * Random-access lists support O(log N) sampling and O(log N + K) windowed projection, where K is
 * the number of intersecting segments. Other lists use linear traversal instead of indexed searches;
 * their iterators may allocate. Use random-access storage for allocation-free in-place queries.
 * Constructor/copy/destructuring/equality and mutable payload ownership retain data-class semantics.
 */
data class Path(
    val points: List<PathPoint>,
    val events: List<PathEvent> = emptyList()
) {
    /**
     * Finite-distance sampling clamped to endpoints. Empty paths return a zero state. Endpoint and
     * exact interior knots return the original mutable point; only interior interpolation allocates.
     * At interior duplicate distances the first matching knot wins; the final endpoint clamp returns
     * the final point. Returned aliases must not be mutated while another caller samples this path.
     */
    fun sampleAtDistance(distanceMeters: Double): PathPoint = sample(
        distanceMeters,
        exact = { it ?: PathPoint(Pose2d(), 0.0) },
        between = { before, after, t ->
            PathPoint(
                Pose2d(pathLerp(before.pose.x, after.pose.x, t), pathLerp(before.pose.y, after.pose.y, t),
                    Rotation2d(pathAngleLerp(before.pose.heading.rawRadians, after.pose.heading.rawRadians, t))),
                pathLerp(before.velocityMps, after.velocityMps, t), distanceMeters,
                pathLerp(before.curvature, after.curvature, t),
                pathAngleLerp(before.tangentRadians, after.tangentRadians, t)
            )
        }
    )

    /** Same sampling contract, writing primitives after validation; errors leave [out] unchanged. */
    fun sampleAtDistance(distanceMeters: Double, out: MutablePathPoint) {
        sample(distanceMeters, exact = { point ->
            out.x = point?.pose?.x ?: 0.0
            out.y = point?.pose?.y ?: 0.0
            out.headingRad = point?.pose?.heading?.radians ?: 0.0
            out.velocityMps = point?.velocityMps ?: 0.0
            out.distanceMeters = point?.distanceMeters ?: 0.0
            out.curvature = point?.curvature ?: 0.0
            out.tangentRadians = point?.tangentRadians ?: 0.0
        }, between = { before, after, t ->
            out.x = pathLerp(before.pose.x, after.pose.x, t)
            out.y = pathLerp(before.pose.y, after.pose.y, t)
            out.headingRad = pathAngleLerp(before.pose.heading.rawRadians, after.pose.heading.rawRadians, t)
            out.velocityMps = pathLerp(before.velocityMps, after.velocityMps, t)
            out.distanceMeters = distanceMeters
            out.curvature = pathLerp(before.curvature, after.curvature, t)
            out.tangentRadians = pathAngleLerp(before.tangentRadians, after.tangentRadians, t)
        })
    }

    private inline fun <T> sample(
        distance: Double, exact: (PathPoint?) -> T, between: (PathPoint, PathPoint, Double) -> T
    ): T {
        require(distance.isFinite()) { "Path sample distance must be finite" }
        if (points.isEmpty()) return exact(null)
        val first = points.first()
        val last = points.last()
        requirePoint(first)
        requirePoint(last)
        require(last.distanceMeters >= first.distanceMeters) { "Path distances must be nondecreasing" }
        if (distance <= first.distanceMeters) return exact(first)
        if (distance >= last.distanceMeters) return exact(last)
        val end = firstEndAtOrAfter(distance)
        val before = points[end - 1]
        val after = points[end]
        requirePoint(before)
        requirePoint(after)
        require(before.distanceMeters <= distance && distance <= after.distanceMeters &&
            after.distanceMeters >= before.distanceMeters) { "Path points do not bracket the sample" }
        if (distance == after.distanceMeters) return exact(after)
        val t = (distance - before.distanceMeters) / (after.distanceMeters - before.distanceMeters)
        return between(before, after, t)
    }

    /** Lower bound among segment ends, preserving duplicate-knot order. */
    private fun firstEndAtOrAfter(distance: Double): Int {
        if (points !is RandomAccess) {
            var index = 0
            for (point in points) {
                requireDistance(point)
                if (index > 0 && point.distanceMeters >= distance) return index
                index++
            }
            return points.lastIndex
        }
        var low = 1
        var high = points.lastIndex
        while (low < high) {
            val middle = low + (high - low) / 2
            val point = points[middle]
            requireDistance(point)
            if (point.distanceMeters < distance) low = middle + 1 else high = middle
        }
        return low
    }

    /** Legacy center-origin X-axis reflection: preserves X, negates Y, heading, tangent and curvature. */
    fun mirrorForBlueAlliance(): Path = mirrorAcrossXAxis()

    /**
     * Same legacy center-origin X-axis reflection as [mirrorForBlueAlliance]. Neither method knows
     * field dimensions or the current alliance. For corner-origin FRC fields use
     * [com.areslib.math.coordinate.AllianceMirroring] with an explicit field origin and symmetry.
     */
    fun mirrorForRedAlliance(): Path = mirrorAcrossXAxis()

    private fun mirrorAcrossXAxis(): Path = Path(points.map { point ->
        requirePoint(point)
        PathPoint(Pose2d(point.pose.x, -point.pose.y, Rotation2d(-point.pose.heading.radians)),
            point.velocityMps, point.distanceMeters, -point.curvature, -point.tangentRadians)
    }, events)

    /**
     * Projects finite field coordinates onto the nearest path segment within a finite ordered arc
     * window. The window is clamped to the actual path extent; an empty path returns zero. Equal
     * computed separations retain the earliest visited segment. Geometry and distance of visited
     * points are validated. Other point fields are irrelevant to this geometric query.
     *
     * Normalized projection and hypot avoid arbitrary small-segment cutoffs and squared-distance
     * overflow. Extremely unequal/cancelling coordinates remain subject to floating-point rounding.
     */
    fun findClosestDistance(
        x: Double, y: Double, minDistance: Double = 0.0, maxDistance: Double = Double.MAX_VALUE
    ): Double {
        require(x.isFinite() && y.isFinite() && minDistance.isFinite() && maxDistance.isFinite() &&
            minDistance <= maxDistance) { "Projection requires finite coordinates and an ordered finite window" }
        if (points.isEmpty()) return 0.0
        val first = points.first()
        val last = points.last()
        requireGeometry(first)
        requireGeometry(last)
        require(last.distanceMeters >= first.distanceMeters) { "Path distances must be nondecreasing" }
        val lower = minDistance.coerceIn(first.distanceMeters, last.distanceMeters)
        val upper = maxDistance.coerceIn(first.distanceMeters, last.distanceMeters)
        if (lower == upper) return lower
        var bestDistance = Double.POSITIVE_INFINITY
        var bestOverflow = false
        var bestArcLength = lower
        visitSegments(lower, upper) { before, after ->
            val span = after.distanceMeters - before.distanceMeters
            val minT = if (span > 0.0 && before.distanceMeters < lower) (lower - before.distanceMeters) / span else 0.0
            val maxT = if (span > 0.0 && after.distanceMeters > upper) (upper - before.distanceMeters) / span else 1.0
            val t = pathProjectionFraction(x, y, before.pose.x, before.pose.y, after.pose.x, after.pose.y)
                .coerceIn(minT, maxT)
            val closestX = pathLerp(before.pose.x, after.pose.x, t)
            val closestY = pathLerp(before.pose.y, after.pose.y, t)
            var separation = hypot(x - closestX, y - closestY)
            val overflow = !separation.isFinite()
            if (overflow) {
                // One common power-of-two scale keeps distances beyond MAX_VALUE comparable.
                separation = hypot(x * 0.25 - closestX * 0.25, y * 0.25 - closestY * 0.25)
            }
            if (bestDistance == Double.POSITIVE_INFINITY ||
                (bestOverflow && !overflow) || (bestOverflow == overflow && separation < bestDistance)) {
                bestDistance = separation
                bestOverflow = overflow
                bestArcLength = pathLerp(before.distanceMeters, after.distanceMeters, t).coerceIn(lower, upper)
            }
        }
        return bestArcLength
    }

    private inline fun visitSegments(lower: Double, upper: Double, visit: (PathPoint, PathPoint) -> Unit) {
        val randomAccess = points is RandomAccess
        var index = if (randomAccess) firstEndAtOrAfter(lower) else 1
        val iterator = if (randomAccess) null else points.iterator()
        var before = iterator?.next() ?: points[index - 1]
        while (index < points.size) {
            val after = iterator?.next() ?: points[index]
            requireGeometry(before)
            requireGeometry(after)
            require(after.distanceMeters >= before.distanceMeters) { "Path distances must be nondecreasing" }
            if (before.distanceMeters > upper) break
            if (after.distanceMeters >= lower) visit(before, after)
            before = after
            index++
        }
    }

    private fun requireDistance(point: PathPoint) {
        require(point.distanceMeters.isFinite() && point.distanceMeters >= 0.0) { "Path distance must be finite and nonnegative" }
    }

    private fun requireGeometry(point: PathPoint) {
        requireDistance(point)
        require(point.pose.x.isFinite() && point.pose.y.isFinite()) { "Path coordinates must be finite" }
    }

    private fun requirePoint(point: PathPoint) {
        requireGeometry(point)
        require(point.pose.heading.rawRadians.isFinite() && point.velocityMps.isFinite() &&
            point.curvature.isFinite() && point.tangentRadians.isFinite()) { "Path sample fields must be finite" }
    }
}
