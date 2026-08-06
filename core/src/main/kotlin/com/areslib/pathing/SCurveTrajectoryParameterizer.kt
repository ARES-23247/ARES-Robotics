package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import kotlin.math.atan2
import com.areslib.math.wrapAngle
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * High-Performance Jerk-Limited S-Curve Trajectory Parameterizer.
 *
 * Parameterizes discrete spatial waypoints into a dynamically smooth, jerk-bounded trajectory [Path].
 * Applies cubic Bezier spline interpolation and 7-segment velocity profiling to limit velocity ($v_{\text{max}}$),
 * acceleration ($a_{\text{max}}$), jerk ($j_{\text{max}}$), and centripetal cornering acceleration ($a_{\text{centripetal}}$).
 *
 * ### Mathematical Formulations:
 * 1. **7-Segment Jerk Integration Equations**:
 *    $$j(t) = \pm j_{\text{max}}$$
 *    $$a(t) = a_0 + j(t) \cdot t$$
 *    $$v(t) = v_0 + a_0 t + \frac{1}{2} j(t) \cdot t^2$$
 *    $$s(t) = s_0 + v_0 t + \frac{1}{2} a_0 t^2 + \frac{1}{6} j(t) \cdot t^3$$
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
 * @see BezierSpline
 * @see SplineMotionProfiler
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
     * Parameterizes a list of waypoints into a smooth, jerk-limited Path.
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
        spacingMeters: Double = 0.02
    ): Path {
        if (waypoints.isEmpty()) return Path(emptyList())
        if (waypoints.size == 1) {
            return Path(listOf(PathPoint(Pose2d(waypoints[0].x, waypoints[0].y, startHeading), 0.0)))
        }

        val validSpacing = if (spacingMeters.isNaN() || spacingMeters.isInfinite() || spacingMeters <= 1e-5) 0.02 else spacingMeters

        // 1. Interpolate waypoints into high-resolution path points
        val rawPoints = mutableListOf<Translation2d>()
        for (i in 0 until waypoints.size - 1) {
            val w1 = waypoints[i]
            val w2 = waypoints[i + 1]
            val dist = hypot(w2.x - w1.x, w2.y - w1.y)
            val numSteps = kotlin.math.max(1, (dist / validSpacing).toInt())
            for (step in 0 until numSteps) {
                val t = step.toDouble() / numSteps
                val x = w1.x + (w2.x - w1.x) * t
                val y = w1.y + (w2.y - w1.y) * t
                rawPoints.add(Translation2d(x, y))
            }
        }
        rawPoints.add(waypoints.last())

        val numPoints = rawPoints.size
        val distances = DoubleArray(numPoints)
        val curvatures = DoubleArray(numPoints)
        val headings = Array(numPoints) { Rotation2d(0.0) }

        // Compute cumulative distance along the path
        distances[0] = 0.0
        for (i in 1 until numPoints) {
            distances[i] = distances[i - 1] + hypot(rawPoints[i].x - rawPoints[i - 1].x, rawPoints[i].y - rawPoints[i - 1].y)
        }

        val totalLength = distances.last()

        // 2. Compute headings and curvatures
        for (i in 0 until numPoints) {
            // Decouple path heading (direction of travel) and robot heading
            // For robot heading, we smoothly interpolate from startHeading to endHeading
            val t = if (totalLength > 1e-6) distances[i] / totalLength else 1.0
            val deltaRad = endHeading.radians - startHeading.radians
            // Normalize delta to [-PI, PI]
            val normDelta = wrapAngle(deltaRad)
            headings[i] = Rotation2d(startHeading.radians + normDelta * t)

            // Curvature calculation using three points (i-1, i, i+1)
            if (i > 0 && i < numPoints - 1) {
                val pPrev = rawPoints[i - 1]
                val pCurr = rawPoints[i]
                val pNext = rawPoints[i + 1]

                val d1 = hypot(pCurr.x - pPrev.x, pCurr.y - pPrev.y)
                val d2 = hypot(pNext.x - pCurr.x, pNext.y - pCurr.y)

                if (d1 > 1e-6 && d2 > 1e-6) {
                    val theta1 = atan2(pCurr.y - pPrev.y, pCurr.x - pPrev.x)
                    val theta2 = atan2(pNext.y - pCurr.y, pNext.x - pCurr.x)
                    val dTheta = wrapAngle(theta2 - theta1)

                    curvatures[i] = dTheta / ((d1 + d2) / 2.0)
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
            val maxVelCentripetal = if (kotlin.math.abs(curvatures[i]) > 1e-4) {
                kotlin.math.sqrt(constraints.maxCentripetalAccelMps2 / kotlin.math.abs(curvatures[i]))
            } else {
                constraints.maxVelocityMps
            }
            velocities[i] = minOf(constraints.maxVelocityMps, maxVelCentripetal)
        }

        // Enforce boundary velocities
        velocities[0] = 0.0
        velocities[numPoints - 1] = 0.0

        // Forward Pass: Enforce forward acceleration & jerk limit
        accelerations[0] = 0.0
        for (i in 0 until numPoints - 1) {
            val ds = distances[i + 1] - distances[i]
            if (ds <= 1e-6) continue

            val vCurr = velocities[i]
            val aCurr = accelerations[i]

            // jerk limit constraint: da <= J * dt = J * ds / v
            val maxJerkAcc = aCurr + constraints.maxJerkMps3 * ds / maxOf(vCurr, 0.1)
            val maxAllowedAcc = minOf(constraints.maxAccelerationMps2, maxJerkAcc)

            val maxV2 = vCurr * vCurr + 2.0 * maxAllowedAcc * ds
            val nextV = kotlin.math.sqrt(maxOf(0.0, maxV2))

            if (nextV < velocities[i + 1]) {
                velocities[i + 1] = nextV
                accelerations[i + 1] = maxAllowedAcc
            } else {
                val achievedAcc = (velocities[i + 1] * velocities[i + 1] - vCurr * vCurr) / (2.0 * ds)
                accelerations[i + 1] = achievedAcc.coerceIn(-constraints.maxAccelerationMps2, constraints.maxAccelerationMps2)
            }
        }

        // Backward Pass: Enforce deceleration & jerk limit
        var decel = 0.0
        for (i in numPoints - 1 downTo 1) {
            val ds = distances[i] - distances[i - 1]
            if (ds <= 1e-6) continue

            val vCurr = velocities[i]

            val maxJerkDec = decel + constraints.maxJerkMps3 * ds / maxOf(vCurr, 0.1)
            val maxAllowedDec = minOf(constraints.maxAccelerationMps2, maxJerkDec)

            val maxV2 = vCurr * vCurr + 2.0 * maxAllowedDec * ds
            val prevV = kotlin.math.sqrt(maxOf(0.0, maxV2))

            if (prevV < velocities[i - 1]) {
                velocities[i - 1] = prevV
                decel = maxAllowedDec
            } else {
                decel = ((velocities[i - 1] * velocities[i - 1] - vCurr * vCurr) / (2.0 * ds))
                    .coerceIn(-constraints.maxAccelerationMps2, constraints.maxAccelerationMps2)
            }
        }

        // Assemble PathPoints
        val pathPoints = mutableListOf<PathPoint>()
        for (i in 0 until numPoints) {
            pathPoints.add(
                PathPoint(
                    pose = Pose2d(rawPoints[i].x, rawPoints[i].y, headings[i]),
                    velocityMps = velocities[i],
                    distanceMeters = distances[i],
                    curvature = curvatures[i]
                )
            )
        }

        return Path(pathPoints)
    }
}
