package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import kotlin.math.atan2
import com.areslib.math.wrapAngle
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal const val MAX_TRAJECTORY_SAMPLES = 100_000

/** Negative means the segment cannot fit the finite, bounded spatial sampling budget. */
internal fun boundedTrajectorySegmentSteps(distance: Double, spacing: Double): Int {
    if (!distance.isFinite() || distance < 0.0) return -1
    val steps = maxOf(2.0, kotlin.math.ceil(distance / spacing))
    return if (steps.isFinite() && steps < MAX_TRAJECTORY_SAMPLES) steps.toInt() else -1
}

/**
 * Spatial velocity-profile seed for [JerkLimitedTrajectoryProvider].
 *
 * Linearly samples waypoint segments and applies forward/backward velocity sweeps.
 * Jerk limits influence the acceleration ramp, but the sweeps alone do not bound jerk
 * at cruise or corner transitions. Use [JerkLimitedTrajectoryProvider] for a timed
 * trajectory with checked finite-difference acceleration and jerk limits.
 *
 * ### Mathematical Formulations:
 * 1. **Acceleration-ramp seed**: $a_{k+1} \le a_k + j_{max}\Delta s / v_{avg}$.
 * 2. **Centripetal Cornering Velocity Constraint**:
 *    $$v_{\text{corner}} = \sqrt{\frac{a_{\text{centripetal}}}{\max(\epsilon, |\kappa|)}}$$
 * 3. **Forward/Backward Constraint Integration**:
 *    $$v_k = \min\left(v_{\text{corner}}, \sqrt{v_{k-1}^2 + 2 a_{\text{max}} \Delta s}\right)$$
 *
 * ### Physical Units & Coordinate Conventions:
 * - Position $(x, y)$: Field-centric meters ($m$)
 * - Robot Heading $(\theta)$: Radians ($rad$), **CCW-positive** ($0 = +X$, $\frac{\pi}{2} = +Y$)
 * - Velocity ($v$): Meters per second ($m/s$)
 * - Acceleration ($a$): Meters per second squared ($m/s^2$)
 * - Jerk ($j$): Meters per second cubed ($m/s^3$)
 * - Curvature ($\kappa$): Inverse radius of curvature ($m^{-1}$)
 *
 * Sampling is capped at 100,000 points before allocation. This is piecewise-linear geometry,
 * not a continuously smooth or dynamically optimized motion plan.
 */
object SCurveTrajectoryParameterizer {

    /**
     * Kinematic velocity, acceleration, jerk, and centripetal motion boundaries.
     *
     * @property maxVelocityMps Maximum linear speed $v_{\text{max}}$ in meters per second ($m/s$).
     * @property maxAccelerationMps2 Maximum linear acceleration $a_{\text{max}}$ in meters per second squared ($m/s^2$).
     * @property maxJerkMps3 Maximum linear jerk $j_{\text{max}}$ in meters per second cubed ($m/s^3$).
     * @property maxCentripetalAccelMps2 Maximum centripetal cornering acceleration $a_{\text{centripetal}}$ in $m/s^2$.
     */
    data class Constraints(
        val maxVelocityMps: Double,
        val maxAccelerationMps2: Double,
        val maxJerkMps3: Double,
        val maxCentripetalAccelMps2: Double = 2.5
    )


    /**
     * Samples waypoint segments and creates a spatial velocity seed. Use
     * [JerkLimitedTrajectoryProvider] for checked time-parameterized acceleration/jerk limits.
     * @param waypoints List of spatial translation coordinates.
     * @param constraints Motion constraints (velocity, acceleration, jerk, centripetal limits).
     * @param startHeading Optional starting robot heading.
     * @param endHeading Optional ending robot heading.
     * @param spacingMeters Interpolation resolution along the path.
     */
    fun generateTrajectory(
        waypoints: List<Translation2d>,
        constraints: Constraints,
        startHeading: Rotation2d = Rotation2d(0.0),
        endHeading: Rotation2d = Rotation2d(0.0),
        startVelocityMps: Double = 0.0,
        endVelocityMps: Double = 0.0,
        spacingMeters: Double = 0.02
    ): Path {
        require(waypoints.size <= MAX_TRAJECTORY_SAMPLES) { "Trajectory input exceeds the sample budget" }
        require(startHeading.rawRadians.isFinite() && endHeading.rawRadians.isFinite()) { "Headings must be finite" }
        require(waypoints.all { it.x.isFinite() && it.y.isFinite() }) { "Waypoints must be finite" }
        require(constraints.maxVelocityMps.isFinite() && constraints.maxVelocityMps > 0.0) {
            "Maximum velocity must be finite and positive"
        }
        require(constraints.maxAccelerationMps2.isFinite() && constraints.maxAccelerationMps2 > 0.0) {
            "Maximum acceleration must be finite and positive"
        }
        require(constraints.maxJerkMps3.isFinite() && constraints.maxJerkMps3 > 0.0) {
            "Maximum jerk must be finite and positive"
        }
        require(constraints.maxCentripetalAccelMps2.isFinite() && constraints.maxCentripetalAccelMps2 > 0.0) {
            "Maximum centripetal acceleration must be finite and positive"
        }
        require(startVelocityMps.isFinite() && startVelocityMps in 0.0..constraints.maxVelocityMps) {
            "Start velocity must be finite and within the configured velocity limit"
        }
        require(endVelocityMps.isFinite() && endVelocityMps in 0.0..constraints.maxVelocityMps) {
            "End velocity must be finite and within the configured velocity limit"
        }
        val input = waypoints.toList()
        if (input.isEmpty()) return Path(emptyList())
        if (input.size == 1) {
            return Path(listOf(PathPoint(Pose2d(input[0].x, input[0].y, startHeading), endVelocityMps)))
        }

        val validSpacing = if (spacingMeters.isNaN() || spacingMeters.isInfinite() || spacingMeters <= 1e-5) 0.02 else spacingMeters

        val stepsBySegment = IntArray(input.size - 1)
        var sampleCount = 1L
        for (index in stepsBySegment.indices) {
            val before = input[index]
            val after = input[index + 1]
            val steps = boundedTrajectorySegmentSteps(hypot(after.x - before.x, after.y - before.y), validSpacing)
            require(steps >= 0) { "Trajectory segment exceeds finite distance or sample budget" }
            sampleCount += steps
            require(sampleCount <= MAX_TRAJECTORY_SAMPLES) { "Trajectory exceeds the 100000-sample budget" }
            stepsBySegment[index] = steps
        }

        // 1. Interpolate input into high-resolution path points
        val rawPoints = ArrayList<Translation2d>(sampleCount.toInt())
        for (i in 0 until input.size - 1) {
            val w1 = input[i]
            val w2 = input[i + 1]
            val numSteps = stepsBySegment[i]
            for (step in 0 until numSteps) {
                val t = step.toDouble() / numSteps
                val x = w1.x + (w2.x - w1.x) * t
                val y = w1.y + (w2.y - w1.y) * t
                rawPoints.add(Translation2d(x, y))
            }
        }
        rawPoints.add(input.last())

        val numPoints = rawPoints.size
        val distances = DoubleArray(numPoints)
        val curvatures = DoubleArray(numPoints)
        val tangents = DoubleArray(numPoints)
        val headings = Array(numPoints) { Rotation2d(0.0) }

        // Compute cumulative distance along the path
        distances[0] = 0.0
        for (i in 1 until numPoints) {
            distances[i] = distances[i - 1] + hypot(rawPoints[i].x - rawPoints[i - 1].x, rawPoints[i].y - rawPoints[i - 1].y)
            require(distances[i].isFinite()) { "Cumulative trajectory distance cannot be represented" }
        }

        val totalLength = distances.last()

        // Path tangent is independent of robot heading. The follower uses this direction
        // for translational feedforward, so leaving it at PathPoint's zero default sends
        // every trajectory along +X regardless of its actual geometry.
        var lastValidTangent = 0.0
        val initialHeading = startHeading.radians
        val headingDelta = wrapAngle(endHeading.radians - initialHeading)
        for (i in 0 until numPoints) {
            val before = if (i == 0) rawPoints[0] else rawPoints[i - 1]
            val after = if (i == numPoints - 1) rawPoints[numPoints - 1] else rawPoints[i + 1]
            val dx = after.x - before.x
            val dy = after.y - before.y
            if (hypot(dx, dy) > 0.0) {
                lastValidTangent = atan2(dy, dx)
            }
            tangents[i] = lastValidTangent
        }

        // 2. Compute headings and curvatures
        for (i in 0 until numPoints) {
            // Decouple path heading (direction of travel) and robot heading
            // For robot heading, we smoothly interpolate from startHeading to endHeading
            val t = if (totalLength > 0.0) distances[i] / totalLength else 1.0
            headings[i] = Rotation2d(initialHeading + headingDelta * t)

            // Curvature calculation using three points (i-1, i, i+1)
            if (i > 0 && i < numPoints - 1) {
                val pPrev = rawPoints[i - 1]
                val pCurr = rawPoints[i]
                val pNext = rawPoints[i + 1]

                val d1 = hypot(pCurr.x - pPrev.x, pCurr.y - pPrev.y)
                val d2 = hypot(pNext.x - pCurr.x, pNext.y - pCurr.y)

                if (d1 > 0.0 && d2 > 0.0) {
                    val theta1 = atan2(pCurr.y - pPrev.y, pCurr.x - pPrev.x)
                    val theta2 = atan2(pNext.y - pCurr.y, pNext.x - pCurr.x)
                    val dTheta = wrapAngle(theta2 - theta1)

                    val shorter = minOf(d1, d2)
                    val averageDistance = shorter + (maxOf(d1, d2) - shorter) * 0.5
                    curvatures[i] = dTheta / averageDistance
                } else {
                    curvatures[i] = 0.0
                }
            } else {
                curvatures[i] = 0.0
            }
        }

        // 3. S-curve velocity profiling: Forward and Backward passes with acceleration and jerk constraints
        val velocities = DoubleArray(numPoints)
        val accelerations = DoubleArray(numPoints)

        // Initialize with maximum velocity limits (centripetal constraints)
        for (i in 0 until numPoints) {
            val maxVelCentripetal = if (kotlin.math.abs(curvatures[i]) > 0.0) {
                kotlin.math.sqrt(constraints.maxCentripetalAccelMps2) / kotlin.math.sqrt(kotlin.math.abs(curvatures[i]))
            } else {
                constraints.maxVelocityMps
            }
            velocities[i] = minOf(constraints.maxVelocityMps, maxVelCentripetal)
        }

        // Enforce boundary velocities
        velocities[0] = startVelocityMps
        velocities[numPoints - 1] = endVelocityMps

        // Forward Pass: Enforce forward acceleration & jerk limit
        accelerations[0] = 0.0
        for (i in 0 until numPoints - 1) {
            val ds = distances[i + 1] - distances[i]
            if (ds <= 0.0) {
                velocities[i + 1] = velocities[i]
                accelerations[i + 1] = accelerations[i]
                continue
            }

            val vCurr = velocities[i]
            val aCurr = accelerations[i]

            // jerk limit constraint: da <= J * dt = J * ds / v
            val estimatedNextV = velocities[i + 1]
            val avgV = (vCurr + estimatedNextV) / 2.0
            val maxJerkAcc = aCurr + constraints.maxJerkMps3 * ds / maxOf(avgV, 0.01)
            val maxAllowedAcc = minOf(constraints.maxAccelerationMps2, maxJerkAcc)

            val maxV2 = vCurr * vCurr + 2.0 * maxAllowedAcc * ds
            val nextV = kotlin.math.sqrt(maxOf(0.0, maxV2))

            if (nextV < velocities[i + 1]) {
                velocities[i + 1] = nextV
                accelerations[i + 1] = maxAllowedAcc
            } else {
                val achievedAcc = (velocities[i + 1] * velocities[i + 1] - vCurr * vCurr) / (2.0 * ds)
                // A local speed ceiling can force deceleration. It is not an upper
                // bound on forward acceleration after the corner; carrying its negative
                // value onward creates artificial zero-speed plateaus.
                accelerations[i + 1] = achievedAcc.coerceIn(0.0, constraints.maxAccelerationMps2)
            }
        }

        // Backward Pass: Enforce deceleration & jerk limit
        velocities[numPoints - 1] = endVelocityMps
        var decel = 0.0
        for (i in numPoints - 1 downTo 1) {
            val ds = distances[i] - distances[i - 1]
            if (ds <= 0.0) {
                velocities[i - 1] = velocities[i]
                continue
            }

            val vCurr = velocities[i]

            val estimatedNextV = velocities[i - 1]
            val avgV = (vCurr + estimatedNextV) / 2.0
            val maxJerkDec = decel + constraints.maxJerkMps3 * ds / maxOf(avgV, 0.01)
            val maxAllowedDec = minOf(constraints.maxAccelerationMps2, maxJerkDec)

            val maxV2 = vCurr * vCurr + 2.0 * maxAllowedDec * ds
            val prevV = kotlin.math.sqrt(maxOf(0.0, maxV2))

            if (prevV < velocities[i - 1]) {
                velocities[i - 1] = prevV
                decel = maxAllowedDec
            } else {
                decel = ((velocities[i - 1] * velocities[i - 1] - vCurr * vCurr) / (2.0 * ds))
                    .coerceIn(0.0, constraints.maxAccelerationMps2)
            }
        }

        // Assemble PathPoints
        val pathPoints = mutableListOf<PathPoint>()
        for (i in 0 until numPoints) {
            require(velocities[i].isFinite() && velocities[i] >= 0.0 && curvatures[i].isFinite() &&
                tangents[i].isFinite() && headings[i].rawRadians.isFinite()) { "Spatial profile contains unrepresentable values" }
            pathPoints.add(
                PathPoint(
                    pose = Pose2d(rawPoints[i].x, rawPoints[i].y, headings[i]),
                    velocityMps = velocities[i],
                    distanceMeters = distances[i],
                    curvature = curvatures[i],
                    tangentRadians = tangents[i]
                )
            )
        }

        return Path(pathPoints)
    }
}
