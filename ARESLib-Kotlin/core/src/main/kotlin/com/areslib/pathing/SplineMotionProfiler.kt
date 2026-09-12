package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import com.areslib.math.kinematics.KinematicsMath
import com.areslib.math.wrapAngle
import kotlin.math.hypot

/**
 * Spline Trajectory Motion Profiling and Kinematic Velocity Sweep Engine.
 *
 * Evaluates Cubic Bezier and Hermite splines, analytical curvature at sampled parameters,
 * decoupled heading orientation profiles (including Point-Towards target zones),
 * and forward/backward velocity sweeps to enforce acceleration ($a_{\text{max}}$) and centripetal cornering limits.
 *
 * ### Mathematical Formulations:
 * 1. **Analytical Curvature**:
 *    `k = (x' y'' - y' x'') / (x'^2 + y'^2)^(3/2)` at regular cubic samples.
 *    Singular samples use a zero-curvature placeholder and a forced zero speed; constant
 *    geometry also stops. Collinear endpoint limits and regular cubic stalls retain their tangent.
 * 2. **Centripetal Velocity Cap**:
 *    $$v_{\text{corner}} = \min\left(v_{\text{max}}, \sqrt{\frac{a_{\text{centripetal}}}{|\kappa|}}\right)$$
 *    The current centripetal ceiling is 2 m/s², independent of the longitudinal acceleration limit.
 * 3. **Forward Velocity Integration Pass**:
 *    $$v_k^{(f)} = \min\left(v_{\text{corner}}, \sqrt{\left(v_{k-1}^{(f)}\right)^2 + 2 a_{\text{max}} \Delta s}\right)$$
 * 4. **Backward Velocity Integration Pass**:
 *    $$v_k^{(b)} = \min\left(v_k^{(f)}, \sqrt{\left(v_{k+1}^{(b)}\right)^2 + 2 a_{\text{max}} \Delta s}\right)$$
 *
 * ### Physical Units & Coordinate Conventions:
 * - Position $(x, y)$: Field-centric meters ($m$)
 * - Robot Heading $(\theta)$: Radians ($rad$), **CCW-positive** ($0 = +X$, $\frac{\pi}{2} = +Y$)
 * - Velocity ($v$): Meters per second ($m/s$)
 * - Acceleration ($a$): Meters per second squared ($m/s^2$)
 * - Curvature ($\kappa$): Inverse radius of curvature ($m^{-1}$)
 *
 * @see BezierSpline
 * @see SCurveTrajectoryParameterizer
 */
object SplineMotionProfiler {

    /**
     * Constructs a fully profiled [Path] from parsed PathPlanner JSON trajectory data.
     * Requested endpoint speeds are ceilings: local speed and reachable acceleration
     * limits may reduce them. This spatial profile does not promise jerk bounds.
     * Nonempty requests validate finite bounded geometry and metadata and preflight the shared
     * 100,000-sample budget before allocating path samples. Do not mutate input lists during construction.
     * Constraint boundaries supplement the regular grid, including zero-width zones. Edge speed
     * caps apply to both endpoints; acceleration bounds cover endpoints and the edge interior.
     * Regular intervals use conservative derivative bounds to cap both endpoint speeds and refine
     * unresolved bounds within the same sample budget. Intervals touching singular stops retain
     * pointwise handling; their interior curvature is not certified. Geometry and arc length between
     * samples remain approximations. Exact interval arithmetic adds construction cost on curved paths.
     * Interior stationary roots supplement the grid and retain their exact root classification
     * after conversion to floating-point parameters. Unrepresentable interior parameters reject.
     *
     * @param data Parsed trajectory structure [PathPlannerJsonParser.ParsedPathData].
     * @return Fully parameterized and velocity-profiled [Path].
     */
    fun buildProfiledPath(data: PathPlannerJsonParser.ParsedPathData): Path {

        val parsedWaypoints = data.waypoints
        if (parsedWaypoints.isEmpty()) return Path(emptyList())
        SplineProfileValidation.parsed(data)
        val sampling = splineSamplePlan(parsedWaypoints.size) { parsedWaypoints[it].anchor }
        val stationary = splineStationarySamples(parsedWaypoints)
        val stationaryCursor = SplineStationaryCursor(stationary)
        val boundedGrid = boundSplineIntervals(parsedWaypoints, splineRelativePositions(data, sampling, stationary), stationary)
        val relativePositions = boundedGrid.positions

        val pathPoints = ArrayList<PathPoint>(relativePositions.size)
        val forcedStops = BooleanArray(relativePositions.size)
        var accumulatedDistance = 0.0

        var initialTangent = Rotation2d(0.0)
        var firstDifferential: SplineDifferential? = null
        if (parsedWaypoints.size > 1) {
            val wp1 = parsedWaypoints[0]
            val wp2 = parsedWaypoints[1]
            firstDifferential = SplineDifferential(wp1.anchor, wp1.nextControl, wp2.prevControl, wp2.anchor)
            firstDifferential.evaluate(0.0)
            initialTangent = Rotation2d(firstDifferential.headingRadians)
        }
        forcedStops[0] = firstDifferential?.requiresStop ?: true

        var finalTangent = Rotation2d(0.0)
        if (parsedWaypoints.size > 1) {
            val wp1 = parsedWaypoints[parsedWaypoints.size - 2]
            val wp2 = parsedWaypoints[parsedWaypoints.size - 1]
            finalTangent = BezierSpline.evaluateHeading(wp1.anchor, wp1.nextControl, wp2.prevControl, wp2.anchor, 1.0)
        }

        val startRotDeg = data.startRotDeg ?: Math.toDegrees(initialTangent.radians)
        val endRotDeg = data.endRotDeg ?: Math.toDegrees(finalTangent.radians)

        pathPoints.add(
            PathPoint(
                pose = Pose2d(parsedWaypoints[0].anchor.x, parsedWaypoints[0].anchor.y, initialTangent),
                velocityMps = data.defaultMaxVel,
                distanceMeters = 0.0,
                curvature = firstDifferential?.curvature ?: 0.0,
                tangentRadians = initialTangent.radians
            )
        )

        var sampleIndex = 1
        for (i in 0 until parsedWaypoints.size - 1) {
            val wp1 = parsedWaypoints[i]
            val wp2 = parsedWaypoints[i + 1]
            val differential = if (i == 0) firstDifferential!! else
                SplineDifferential(wp1.anchor, wp1.nextControl, wp2.prevControl, wp2.anchor)
            if (i > 0) {
                differential.evaluate(0.0)
                constrainJunction(pathPoints.last(), forcedStops, pathPoints.lastIndex, differential)
            }

            while (sampleIndex < relativePositions.size && relativePositions[sampleIndex] <= i + 1.0) {
                val position = relativePositions[sampleIndex++]
                val t = position - i
                val point = BezierSpline.evaluate(wp1.anchor, wp1.nextControl, wp2.prevControl, wp2.anchor, t)
                differential.evaluate(t, stationaryCursor.at(position))
                val heading = Rotation2d(differential.headingRadians)
                forcedStops[pathPoints.size] = differential.requiresStop

                val prevPathPoint = pathPoints.last()
                val dx = point.x - prevPathPoint.pose.x
                val dy = point.y - prevPathPoint.pose.y
                accumulatedDistance += hypot(dx, dy)

                pathPoints.add(
                    PathPoint(
                        pose = Pose2d(point.x, point.y, heading),
                        velocityMps = data.defaultMaxVel,
                        distanceMeters = accumulatedDistance,
                        curvature = differential.curvature,
                        tangentRadians = heading.radians
                    )
                )
            }
        }

        // Decoupled Rotation & Point-Towards Zone heading interpolation
        applyRotations(pathPoints, relativePositions, data, startRotDeg, endRotDeg)

        // Forward and backward motion profiling sweeps
        applyMotionProfile(pathPoints, relativePositions, data.startVel, data.endVel, data.defaultMaxVel, data.defaultMaxAccel, data.constraintZones, forcedStops, boundedGrid.edgeSpeedCeilings)

        // Parse path events
        val pathEvents = mutableListOf<PathEvent>()
        for (marker in data.eventMarkers) {
            var targetIndex = relativePositions.binarySearch(marker.waypointRelativePos)
            if (targetIndex < 0) targetIndex = -targetIndex - 1
            if (targetIndex >= pathPoints.size) targetIndex = pathPoints.size - 1
            if (targetIndex < 0) targetIndex = 0
            val triggerDist = pathPoints[targetIndex].distanceMeters
            pathEvents.add(PathEvent(marker.commandName, triggerDist))
        }

        return Path(pathPoints, pathEvents)
    }

    /**
     * Generates a natural cubic path with C2-continuous geometry and interpolated robot heading.
     * Fewer than two points retain the empty-path result. Other requests share the facade's
     * finite geometry/positive-limit policy and the 100,000-sample trajectory budget.
     * Positive travel interpolates heading independently of scale. Zero travel retains the
     * final orientation as a stationary goal; it does not define a timed angular profile.
     */
    fun generateHermitePath(
        points: List<Translation2d>,
        startHeading: Rotation2d,
        endHeading: Rotation2d,
        maxVelocityMps: Double,
        maxAccelerationMps2: Double
    ): Path {
        if (points.size < 2) return Path(emptyList())
        SplineProfileValidation.generated(points, startHeading, endHeading, maxVelocityMps, maxAccelerationMps2)
        val sampling = splineSamplePlan(points.size) { points[it] }

        val parsedWaypoints = naturalCubicWaypointControls(points)
        val stationary = splineStationarySamples(parsedWaypoints)
        val stationaryCursor = SplineStationaryCursor(stationary)
        val boundedGrid = boundSplineIntervals(parsedWaypoints, splineRelativePositions(parsedWaypoints.size, sampling,
            DoubleArray(stationary.size) { stationary[it].relativePosition }), stationary)
        val relativePositions = boundedGrid.positions

        val pathPoints = ArrayList<PathPoint>(relativePositions.size)
        val forcedStops = BooleanArray(relativePositions.size)
        var accumulatedDistance = 0.0

        val firstDifferential = SplineDifferential(
            parsedWaypoints[0].anchor, parsedWaypoints[0].nextControl,
            parsedWaypoints[1].prevControl, parsedWaypoints[1].anchor)
        firstDifferential.evaluate(0.0)
        val initialTangent = Rotation2d(firstDifferential.headingRadians)
        forcedStops[0] = firstDifferential.requiresStop

        pathPoints.add(
            PathPoint(
                pose = Pose2d(parsedWaypoints[0].anchor.x, parsedWaypoints[0].anchor.y, initialTangent),
                velocityMps = maxVelocityMps,
                distanceMeters = 0.0,
                curvature = firstDifferential.curvature,
                tangentRadians = initialTangent.radians
            )
        )

        var sampleIndex = 1
        for (i in 0 until parsedWaypoints.size - 1) {
            val wp1 = parsedWaypoints[i]
            val wp2 = parsedWaypoints[i + 1]
            val differential = if (i == 0) firstDifferential else
                SplineDifferential(wp1.anchor, wp1.nextControl, wp2.prevControl, wp2.anchor)
            if (i > 0) {
                differential.evaluate(0.0)
                constrainJunction(pathPoints.last(), forcedStops, pathPoints.lastIndex, differential)
            }
            while (sampleIndex < relativePositions.size && relativePositions[sampleIndex] <= i + 1.0) {
                val position = relativePositions[sampleIndex++]
                val t = position - i
                val point = BezierSpline.evaluate(wp1.anchor, wp1.nextControl, wp2.prevControl, wp2.anchor, t)
                differential.evaluate(t, stationaryCursor.at(position))
                val heading = Rotation2d(differential.headingRadians)
                forcedStops[pathPoints.size] = differential.requiresStop
                val prevPathPoint = pathPoints.last()
                val dx = point.x - prevPathPoint.pose.x
                val dy = point.y - prevPathPoint.pose.y
                accumulatedDistance += hypot(dx, dy)
                pathPoints.add(
                    PathPoint(
                        pose = Pose2d(point.x, point.y, heading),
                        velocityMps = maxVelocityMps,
                        distanceMeters = accumulatedDistance,
                        curvature = differential.curvature,
                        tangentRadians = heading.radians
                    )
                )
            }
        }

        // Heading cosine interpolation from startHeading to endHeading
        val startAngle = startHeading.radians
        val endAngle = endHeading.radians
        val delta = wrapAngle(endAngle - startAngle)
        val totalDist = pathPoints.last().distanceMeters
        for (idx in pathPoints.indices) {
            val dCurr = pathPoints[idx].distanceMeters
            val t = if (totalDist == 0.0) 1.0 else dCurr / totalDist
            val t2 = cosineEase(t)
            val interpAngle = startAngle + delta * t2
            val p = pathPoints[idx]
            pathPoints[idx] = p.copy(pose = Pose2d(p.pose.x, p.pose.y, Rotation2d(interpAngle)))
        }

        applyMotionProfile(pathPoints, emptyList(), 0.0, 0.0, maxVelocityMps, maxAccelerationMps2, emptyList(), forcedStops, boundedGrid.edgeSpeedCeilings)

        return Path(pathPoints, emptyList())
    }

    /** Equivalent to (1 - cos(pi*t))/2 without cancellation near zero progress. */
    private fun cosineEase(t: Double): Double {
        val sine = Math.sin(t * (Math.PI / 2.0))
        return sine * sine
    }

    private fun constrainJunction(point: PathPoint, forcedStops: BooleanArray, index: Int, outgoing: SplineDifferential) {
        // Allow only angular floating-point roundoff, not a physical corner at nonzero speed.
        val tangentRoundoff = 32.0 * Math.ulp(Math.PI)
        forcedStops[index] = forcedStops[index] || outgoing.requiresStop ||
            Math.abs(wrapAngle(outgoing.headingRadians - point.tangentRadians)) > tangentRoundoff
        if (Math.abs(outgoing.curvature) > Math.abs(point.curvature)) point.curvature = outgoing.curvature
    }

    private fun applyRotations(
        pathPoints: MutableList<PathPoint>,
        relativePositions: List<Double>,
        data: PathPlannerJsonParser.ParsedPathData,
        startRotDeg: Double,
        endRotDeg: Double
    ) {
        val explicitRotations = arrayOfNulls<Double>(pathPoints.size)
        explicitRotations[0] = wrapAngle(Math.toRadians(startRotDeg))
        explicitRotations[pathPoints.size - 1] = wrapAngle(Math.toRadians(endRotDeg))

        val pointTowardsOffsets = DoubleArray(data.pointTowardsZones.size) {
            wrapAngle(Math.toRadians(data.pointTowardsZones[it].rotationOffset))
        }
        for (idx in pathPoints.indices) {
            val pos = relativePositions[idx]
            for (zoneIndex in data.pointTowardsZones.indices) {
                val zone = data.pointTowardsZones[zoneIndex]
                if (pos >= zone.minWaypointRelativePos && pos <= zone.maxWaypointRelativePos) {
                    val dx = zone.x - pathPoints[idx].pose.x
                    val dy = zone.y - pathPoints[idx].pose.y
                    explicitRotations[idx] = wrapAngle(Math.atan2(dy, dx) + pointTowardsOffsets[zoneIndex])
                    break
                }
            }
        }

        data.rotationTargets.forEach { target ->
            val found = relativePositions.binarySearch(target.waypointRelativePos)
            val bestIdx = if (found >= 0) found else {
                val after = (-found - 1).coerceAtMost(relativePositions.lastIndex)
                val before = (after - 1).coerceAtLeast(0)
                if (Math.abs(relativePositions[before] - target.waypointRelativePos) <=
                    Math.abs(relativePositions[after] - target.waypointRelativePos)) before else after
            }
            if (explicitRotations[bestIdx] == null) {
                explicitRotations[bestIdx] = wrapAngle(Math.toRadians(target.rotationDegrees))
            }
        }

        // Walk anchor intervals once instead of rescanning both sides of every sample.
        var prevIdx = 0
        var nextIdx = 0
        var intervalStartDistance = 0.0
        var intervalDistance = 0.0
        var intervalStartAngle = 0.0
        var intervalAngleDelta = 0.0
        for (idx in pathPoints.indices) {
            if (explicitRotations[idx] != null) {
                prevIdx = idx
                val p = pathPoints[idx]
                pathPoints[idx] = p.copy(pose = Pose2d(p.pose.x, p.pose.y, Rotation2d(explicitRotations[idx]!!)))
            } else {
                if (nextIdx <= idx) {
                    nextIdx = idx + 1
                    while (nextIdx < pathPoints.lastIndex && explicitRotations[nextIdx] == null) nextIdx++
                    intervalStartDistance = pathPoints[prevIdx].distanceMeters
                    intervalDistance = pathPoints[nextIdx].distanceMeters - intervalStartDistance
                    intervalStartAngle = explicitRotations[prevIdx]!!
                    intervalAngleDelta = wrapAngle(explicitRotations[nextIdx]!! - intervalStartAngle)
                }
                val dCurr = pathPoints[idx].distanceMeters
                val t = if (intervalDistance == 0.0) 0.0 else (dCurr - intervalStartDistance) / intervalDistance
                val interpAngle = intervalStartAngle + intervalAngleDelta * cosineEase(t)
                val p = pathPoints[idx]
                pathPoints[idx] = p.copy(pose = Pose2d(p.pose.x, p.pose.y, Rotation2d(interpAngle)))
            }
        }
    }

    private fun applyMotionProfile(
        pathPoints: MutableList<PathPoint>,
        relativePositions: List<Double>,
        startVel: Double,
        endVel: Double,
        defaultMaxVel: Double,
        defaultMaxAccel: Double,
        constraintZones: List<PathPlannerJsonParser.ParsedConstraintsZone>,
        forcedStops: BooleanArray,
        edgeSpeedCeilings: DoubleArray,
        maxCentripetalAccel: Double = SPLINE_CENTRIPETAL_ACCELERATION
    ) {
        // Resolve each point's limits once. These points are private to construction,
        // so changing their velocity fields avoids allocating copies during both sweeps.
        // Entry i stores the bound on edge i-1 -> i, including its interior zone.
        val accelerations = DoubleArray(pathPoints.size)
        val zoneCursor = if (constraintZones.isEmpty()) null else SplineConstraintCursor(constraintZones)
        var previousMaxAccel = defaultMaxAccel
        for (i in pathPoints.indices) {
            val edgeZone = if (zoneCursor != null && i > 0) {
                zoneCursor.at(relativePositions[i - 1], after = true)
            } else null
            val zone = zoneCursor?.at(relativePositions[i])
            val maxVel = if (forcedStops[i]) 0.0 else zone?.maxVelocity ?: defaultMaxVel
            val maxAccel = zone?.maxAcceleration ?: defaultMaxAccel
            accelerations[i] = if (i == 0) maxAccel else
                minOf(previousMaxAccel, maxAccel, edgeZone?.maxAcceleration ?: defaultMaxAccel)
            previousMaxAccel = maxAccel
            val curvature = Math.abs(pathPoints[i].curvature)
            pathPoints[i].velocityMps = if (curvature > 0.0) {
                minOf(maxVel, Math.sqrt(maxCentripetalAccel) / Math.sqrt(curvature))
            } else maxVel
            if (i > 0) {
                // Every zone boundary is sampled, so the edge interior has one priority winner.
                // Both ends must respect that cap for interpolated speeds to remain valid.
                val edgeMaxVel = minOf(edgeSpeedCeilings[i], edgeZone?.maxVelocity ?: defaultMaxVel)
                pathPoints[i - 1].velocityMps = minOf(pathPoints[i - 1].velocityMps, edgeMaxVel)
                pathPoints[i].velocityMps = minOf(pathPoints[i].velocityMps, edgeMaxVel)
            }
        }

        pathPoints[0].velocityMps = minOf(pathPoints[0].velocityMps, startVel)
        for (i in 1 until pathPoints.size) {
            val prev = pathPoints[i - 1]
            val curr = pathPoints[i]
            val distance = curr.distanceMeters - prev.distanceMeters
            // The edge bound includes both endpoints and its constant-priority interior.
            val maxAccel = accelerations[i]
            curr.velocityMps = minOf(curr.velocityMps,
                KinematicsMath.finalVelocity(prev.velocityMps, maxAccel, distance))
        }

        // Do not raise the endpoint above the speed reached by the forward sweep.
        val end = pathPoints.last()
        end.velocityMps = minOf(end.velocityMps, endVel)
        for (i in pathPoints.size - 2 downTo 0) {
            val next = pathPoints[i + 1]
            val curr = pathPoints[i]
            val distance = next.distanceMeters - curr.distanceMeters
            val maxAccel = accelerations[i + 1]
            curr.velocityMps = minOf(curr.velocityMps,
                KinematicsMath.finalVelocity(next.velocityMps, maxAccel, distance))
        }
    }
}
