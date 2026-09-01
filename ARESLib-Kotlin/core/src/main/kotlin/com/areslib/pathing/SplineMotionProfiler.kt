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
 * Evaluates Cubic Bezier and Hermite splines, numerical path curvature $\kappa$,
 * decoupled heading orientation profiles (including Point-Towards target zones),
 * and forward/backward velocity sweeps to enforce acceleration ($a_{\text{max}}$) and centripetal cornering limits.
 *
 * ### Mathematical Formulations:
 * 1. **Numerical Curvature $\kappa$**:
 *    $$\kappa = \frac{d\theta}{ds} \approx \frac{\text{wrapAngle}(\theta_{k+1} - \theta_{k-1})}{s_{k+1} - s_{k-1}}$$
 * 2. **Centripetal Velocity Cap**:
 *    $$v_{\text{corner}} = \min\left(v_{\text{max}}, \sqrt{\frac{a_{\text{max}}}{|\kappa|}}\right)$$
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
     *
     * @param data Parsed trajectory structure [PathPlannerJsonParser.ParsedPathData].
     * @return Fully parameterized and velocity-profiled [Path].
     */
    fun buildProfiledPath(data: PathPlannerJsonParser.ParsedPathData): Path {

        val parsedWaypoints = data.waypoints
        if (parsedWaypoints.isEmpty()) return Path(emptyList())

        val pathPoints = mutableListOf<PathPoint>()
        var accumulatedDistance = 0.0

        var initialTangent = Rotation2d(0.0)
        if (parsedWaypoints.size > 1) {
            val wp1 = parsedWaypoints[0]
            val wp2 = parsedWaypoints[1]
            initialTangent = BezierSpline.evaluateHeading(wp1.anchor, wp1.nextControl, wp2.prevControl, wp2.anchor, 0.0)
        }

        var finalTangent = Rotation2d(0.0)
        if (parsedWaypoints.size > 1) {
            val wp1 = parsedWaypoints[parsedWaypoints.size - 2]
            val wp2 = parsedWaypoints[parsedWaypoints.size - 1]
            finalTangent = BezierSpline.evaluateHeading(wp1.anchor, wp1.nextControl, wp2.prevControl, wp2.anchor, 1.0)
        }

        val startRotDeg = data.startRotDeg ?: Math.toDegrees(initialTangent.radians)
        val endRotDeg = data.endRotDeg ?: Math.toDegrees(finalTangent.radians)

        val relativePositions = mutableListOf<Double>()
        relativePositions.add(0.0)

        pathPoints.add(
            PathPoint(
                pose = Pose2d(parsedWaypoints[0].anchor.x, parsedWaypoints[0].anchor.y, initialTangent),
                velocityMps = data.defaultMaxVel,
                distanceMeters = 0.0,
                tangentRadians = initialTangent.radians
            )
        )

        for (i in 0 until parsedWaypoints.size - 1) {
            val wp1 = parsedWaypoints[i]
            val wp2 = parsedWaypoints[i + 1]
            val dist = hypot(wp2.anchor.x - wp1.anchor.x, wp2.anchor.y - wp1.anchor.y)
            val numSamples = maxOf(10, (dist / 0.05).toInt())

            for (step in 1..numSamples) {
                val t = step.toDouble() / numSamples
                val point = BezierSpline.evaluate(wp1.anchor, wp1.nextControl, wp2.prevControl, wp2.anchor, t)
                val heading = BezierSpline.evaluateHeading(wp1.anchor, wp1.nextControl, wp2.prevControl, wp2.anchor, t)

                val prevPathPoint = pathPoints.last()
                val dx = point.x - prevPathPoint.pose.x
                val dy = point.y - prevPathPoint.pose.y
                accumulatedDistance += hypot(dx, dy)

                pathPoints.add(
                    PathPoint(
                        pose = Pose2d(point.x, point.y, heading),
                        velocityMps = data.defaultMaxVel,
                        distanceMeters = accumulatedDistance,
                        tangentRadians = heading.radians
                    )
                )
                relativePositions.add(i.toDouble() + t)
            }
        }

        // Calculate numerical curvature for each path point
        computeCurvatures(pathPoints)

        // Decoupled Rotation & Point-Towards Zone heading interpolation
        applyRotations(pathPoints, relativePositions, data, startRotDeg, endRotDeg)

        // Forward and backward motion profiling sweeps
        applyMotionProfile(pathPoints, relativePositions, data.startVel, data.endVel, data.defaultMaxVel, data.defaultMaxAccel, data.constraintZones)

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

    /** Generates a natural cubic path with C2-continuous geometry and interpolated robot heading. */
    fun generateHermitePath(
        points: List<Translation2d>,
        startHeading: Rotation2d,
        endHeading: Rotation2d,
        maxVelocityMps: Double,
        maxAccelerationMps2: Double
    ): Path {
        if (points.size < 2) return Path(emptyList())

        val parsedWaypoints = naturalCubicWaypointControls(points)

        val pathPoints = mutableListOf<PathPoint>()
        var accumulatedDistance = 0.0

        val initialTangent = if (points.size >= 2) {
            val dx = points[1].x - points[0].x
            val dy = points[1].y - points[0].y
            Rotation2d(Math.atan2(dy, dx))
        } else Rotation2d(0.0)

        pathPoints.add(
            PathPoint(
                pose = Pose2d(parsedWaypoints[0].anchor.x, parsedWaypoints[0].anchor.y, initialTangent),
                velocityMps = maxVelocityMps,
                distanceMeters = 0.0,
                tangentRadians = initialTangent.radians
            )
        )

        for (i in 0 until parsedWaypoints.size - 1) {
            val wp1 = parsedWaypoints[i]
            val wp2 = parsedWaypoints[i + 1]
            val dist = hypot(wp2.anchor.x - wp1.anchor.x, wp2.anchor.y - wp1.anchor.y)
            val numSamples = maxOf(10, (dist / 0.05).toInt())
            for (step in 1..numSamples) {
                val t = step.toDouble() / numSamples
                val point = BezierSpline.evaluate(wp1.anchor, wp1.nextControl, wp2.prevControl, wp2.anchor, t)
                val heading = BezierSpline.evaluateHeading(wp1.anchor, wp1.nextControl, wp2.prevControl, wp2.anchor, t)
                val prevPathPoint = pathPoints.last()
                val dx = point.x - prevPathPoint.pose.x
                val dy = point.y - prevPathPoint.pose.y
                accumulatedDistance += hypot(dx, dy)
                pathPoints.add(
                    PathPoint(
                        pose = Pose2d(point.x, point.y, heading),
                        velocityMps = maxVelocityMps,
                        distanceMeters = accumulatedDistance,
                        tangentRadians = heading.radians
                    )
                )
            }
        }

        computeCurvatures(pathPoints)

        // Heading cosine interpolation from startHeading to endHeading
        val startAngle = startHeading.radians
        val endAngle = endHeading.radians
        val totalDist = pathPoints.last().distanceMeters
        for (idx in pathPoints.indices) {
            val dCurr = pathPoints[idx].distanceMeters
            val t = if (totalDist < 1e-6) 0.0 else dCurr / totalDist
            val t2 = (1.0 - Math.cos(t * Math.PI)) / 2.0
            val delta = wrapAngle(endAngle - startAngle)
            val interpAngle = startAngle + delta * t2
            val p = pathPoints[idx]
            pathPoints[idx] = p.copy(pose = Pose2d(p.pose.x, p.pose.y, Rotation2d(interpAngle)))
        }

        applyMotionProfile(pathPoints, List(pathPoints.size) { 0.0 }, 0.0, 0.0, maxVelocityMps, maxAccelerationMps2, emptyList())

        return Path(pathPoints, emptyList())
    }

    private fun computeCurvatures(pathPoints: MutableList<PathPoint>) {
        for (idx in 0 until pathPoints.size) {
            val nextIdx = if (idx < pathPoints.size - 1) idx + 1 else idx
            val prevIdx = if (idx > 0) idx - 1 else idx

            val pPrev = pathPoints[prevIdx]
            val pNext = pathPoints[nextIdx]

            val ds = pNext.distanceMeters - pPrev.distanceMeters
            val dTheta = pNext.pose.heading.radians - pPrev.pose.heading.radians
            val normDTheta = wrapAngle(dTheta)

            val kappa = if (ds > 1e-4) normDTheta / ds else 0.0
            val clampedKappa = kappa.coerceIn(-100.0, 100.0)
            pathPoints[idx] = pathPoints[idx].copy(curvature = clampedKappa)
        }
    }

    private fun applyRotations(
        pathPoints: MutableList<PathPoint>,
        relativePositions: List<Double>,
        data: PathPlannerJsonParser.ParsedPathData,
        startRotDeg: Double,
        endRotDeg: Double
    ) {
        val explicitRotations = arrayOfNulls<Double>(pathPoints.size)
        explicitRotations[0] = Math.toRadians(startRotDeg)
        explicitRotations[pathPoints.size - 1] = Math.toRadians(endRotDeg)

        for (idx in pathPoints.indices) {
            val pos = relativePositions[idx]
            for (zone in data.pointTowardsZones) {
                if (pos >= zone.minWaypointRelativePos && pos <= zone.maxWaypointRelativePos) {
                    val dx = zone.x - pathPoints[idx].pose.x
                    val dy = zone.y - pathPoints[idx].pose.y
                    explicitRotations[idx] = Math.atan2(dy, dx) + Math.toRadians(zone.rotationOffset)
                    break
                }
            }
        }

        data.rotationTargets.forEach { target ->
            var minDiff = Double.MAX_VALUE
            var bestIdx = -1
            for (k in pathPoints.indices) {
                val diff = Math.abs(relativePositions[k] - target.waypointRelativePos)
                if (diff < minDiff) {
                    minDiff = diff
                    bestIdx = k
                }
            }
            if (bestIdx != -1 && explicitRotations[bestIdx] == null) {
                explicitRotations[bestIdx] = Math.toRadians(target.rotationDegrees)
            }
        }

        for (idx in pathPoints.indices) {
            if (explicitRotations[idx] != null) {
                val p = pathPoints[idx]
                pathPoints[idx] = p.copy(pose = Pose2d(p.pose.x, p.pose.y, Rotation2d(explicitRotations[idx]!!)))
            } else {
                var prevIdx = 0
                for (k in idx - 1 downTo 0) {
                    if (explicitRotations[k] != null) {
                        prevIdx = k
                        break
                    }
                }
                var nextIdx = pathPoints.size - 1
                for (k in idx + 1 until pathPoints.size) {
                    if (explicitRotations[k] != null) {
                        nextIdx = k
                        break
                    }
                }
                val dCurr = pathPoints[idx].distanceMeters
                val dPrev = pathPoints[prevIdx].distanceMeters
                val dNext = pathPoints[nextIdx].distanceMeters
                val denom = dNext - dPrev
                val t = if (Math.abs(denom) < 1e-6) 0.0 else (dCurr - dPrev) / denom
                val t2 = (1.0 - Math.cos(t * Math.PI)) / 2.0
                val startAngle = explicitRotations[prevIdx]!!
                val endAngle = explicitRotations[nextIdx]!!
                val delta = wrapAngle(endAngle - startAngle)
                val interpAngle = startAngle + delta * t2
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
        maxCentripetalAccel: Double = 2.0
    ) {
        // Pass 1: Forward Sweep
        pathPoints[0] = pathPoints[0].copy(velocityMps = startVel)
        for (i in 1 until pathPoints.size) {
            val prev = pathPoints[i - 1]
            val curr = pathPoints[i]
            val dx = curr.distanceMeters - prev.distanceMeters

            val pos = if (i < relativePositions.size) relativePositions[i] else 0.0
            var activeMaxVel = defaultMaxVel
            var activeMaxAccel = defaultMaxAccel
            for (zone in constraintZones) {
                if (pos >= zone.minWaypointRelativePos && pos <= zone.maxWaypointRelativePos) {
                    activeMaxVel = zone.maxVelocity
                    activeMaxAccel = zone.maxAcceleration
                    break
                }
            }

            val kappa = curr.curvature
            val pointMaxVel = if (Math.abs(kappa) > 1e-4) {
                val radius = 1.0 / Math.abs(kappa)
                minOf(activeMaxVel, Math.sqrt(maxCentripetalAccel * radius))
            } else {
                activeMaxVel
            }

            val maxReachable = KinematicsMath.finalVelocity(prev.velocityMps, activeMaxAccel, dx)
            val newVel = minOf(pointMaxVel, maxReachable)
            pathPoints[i] = curr.copy(velocityMps = newVel)
        }

        // Pass 2: Backward Sweep
        pathPoints[pathPoints.size - 1] = pathPoints[pathPoints.size - 1].copy(velocityMps = endVel)
        for (i in pathPoints.size - 2 downTo 0) {
            val next = pathPoints[i + 1]
            val curr = pathPoints[i]
            val dx = next.distanceMeters - curr.distanceMeters

            val pos = if (i < relativePositions.size) relativePositions[i] else 0.0
            var activeMaxVel = defaultMaxVel
            var activeMaxAccel = defaultMaxAccel
            for (zone in constraintZones) {
                if (pos >= zone.minWaypointRelativePos && pos <= zone.maxWaypointRelativePos) {
                    activeMaxVel = zone.maxVelocity
                    activeMaxAccel = zone.maxAcceleration
                    break
                }
            }

            val kappa = curr.curvature
            val pointMaxVel = if (Math.abs(kappa) > 1e-4) {
                val radius = 1.0 / Math.abs(kappa)
                minOf(activeMaxVel, Math.sqrt(maxCentripetalAccel * radius))
            } else {
                activeMaxVel
            }

            val maxReachable = KinematicsMath.finalVelocity(next.velocityMps, activeMaxAccel, dx)
            val newVel = minOf(curr.velocityMps, minOf(pointMaxVel, maxReachable))
            pathPoints[i] = curr.copy(velocityMps = newVel)
        }
    }
}
