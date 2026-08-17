package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Translation2d
import kotlin.math.hypot
import kotlin.math.pow

/**
 * On-the-Fly Continuous Trajectory Generation Utility.
 *
 * Generates smooth single-segment Cubic Bezier trajectory paths on the fly with trapezoidal velocity profiling.
 *
 * ### Mathematical Formulation:
 * 1. **Cubic Bezier Control Point Scaling**:
 *    $$\mathbf{P}_1 = \mathbf{P}_0 + \frac{d_{\text{chord}}}{3} \begin{bmatrix} \cos\theta_0 \\ \sin\theta_0 \end{bmatrix}, \quad \mathbf{P}_2 = \mathbf{P}_3 - \frac{d_{\text{chord}}}{3} \begin{bmatrix} \cos\theta_3 \\ \sin\theta_3 \end{bmatrix}$$
 * 2. **Trapezoidal Backward/Forward Deceleration/Acceleration Sweeps**:
 *    $$v_{\text{max, reachable}} = \sqrt{v_{\text{neighbor}}^2 + 2 a_{\text{max}} \Delta s}$$
 *
 * ### Physical Units & Coordinate Conventions:
 * - Position $(x, y)$: Field-centric meters ($m$)
 * - Robot Heading ($\theta$): Radians ($rad$), **CCW-positive** ($0 = +X$, $\frac{\pi}{2} = +Y$)
 * - Linear Velocity Bounds ($v_{\text{max}}$): Meters per second ($m/s$)
 * - Linear Acceleration Bounds ($a_{\text{max}}$): Meters per second squared ($m/s^2$)
 *
 * @see BezierSpline
 * @see Path
 */
object TrajectoryGenerator {
    
    /**
     * Kinematic velocity and acceleration constraints for trajectory generation.
     *
     * @property maxVelocityMps Maximum linear speed in meters per second ($m/s$).
     * @property maxAccelerationMps2 Maximum linear acceleration in meters per second squared ($m/s^2$).
     */
    data class PathConstraints(
        val maxVelocityMps: Double,
        val maxAccelerationMps2: Double
    )

    /**
     * Generates an on-the-fly single-segment trajectory [Path] connecting [startPose] to [endPose].
     *
     * @param startPose Current robot pose in meters ($m$) and **CCW-positive** radians ($rad$).
     * @param endPose Destination target pose in meters ($m$) and **CCW-positive** radians ($rad$).
     * @param constraints Motion velocity and acceleration bounds [PathConstraints].
     * @return Fully motion-profiled [Path].
     */
    fun generateTrajectory(
        startPose: Pose2d,
        endPose: Pose2d,
        constraints: PathConstraints,
        initialVelocityMps: Double = 0.0
    ): Path {
        require(initialVelocityMps.isFinite() && initialVelocityMps >= 0.0) {
            "Initial velocity must be finite and non-negative"
        }
        require(constraints.maxVelocityMps.isFinite() && constraints.maxVelocityMps > 0.0)
        require(constraints.maxAccelerationMps2.isFinite() && constraints.maxAccelerationMps2 > 0.0)

        val p0 = Translation2d(startPose.x, startPose.y)
        val p3 = Translation2d(endPose.x, endPose.y)
        
        val chordDist = hypot(p3.x - p0.x, p3.y - p0.y)
        if (chordDist < 0.01) {
            // Already at destination
            return Path(listOf(PathPoint(endPose, 0.0, 0.0)))
        }

        // Control point scaling factor for a smooth curve (usually 1/3 to 1/2 of chord length)
        val scaling = (chordDist / 3.0).coerceAtLeast(0.05)

        val p1 = Translation2d(
            p0.x + scaling * startPose.heading.cos,
            p0.y + scaling * startPose.heading.sin
        )
        val p2 = Translation2d(
            p3.x - scaling * endPose.heading.cos,
            p3.y - scaling * endPose.heading.sin
        )

        // Fixed sample count for zero-allocation budgets
        val numSamples = 50
        val points = mutableListOf<PathPoint>()
        var accumulatedDistance = 0.0
        var prevPoint = p0

        // Forward pass: Generate geometry and naive velocity
        for (step in 0..numSamples) {
            val t = step.toDouble() / numSamples
            val point = BezierSpline.evaluate(p0, p1, p2, p3, t)
            val heading = BezierSpline.evaluateHeading(p0, p1, p2, p3, t)
            val derivative = BezierSpline.evaluateDerivative(p0, p1, p2, p3, t)
            val u = 1.0 - t
            val secondDerivativeX = 6.0 * u * (p2.x - 2.0 * p1.x + p0.x) +
                6.0 * t * (p3.x - 2.0 * p2.x + p1.x)
            val secondDerivativeY = 6.0 * u * (p2.y - 2.0 * p1.y + p0.y) +
                6.0 * t * (p3.y - 2.0 * p2.y + p1.y)
            val speedSquared = derivative.x * derivative.x + derivative.y * derivative.y
            val curvature = if (speedSquared > 1e-12) {
                (derivative.x * secondDerivativeY - derivative.y * secondDerivativeX) /
                    speedSquared.pow(1.5)
            } else {
                0.0
            }
            
            val dist = hypot(point.x - prevPoint.x, point.y - prevPoint.y)
            accumulatedDistance += dist
            
            points.add(
                PathPoint(
                    pose = Pose2d(point.x, point.y, heading),
                    velocityMps = constraints.maxVelocityMps,
                    distanceMeters = accumulatedDistance,
                    curvature = curvature,
                    tangentRadians = heading.radians
                )
            )
            prevPoint = point
        }

        // Backward pass: Apply trapezoidal deceleration (v_f^2 = v_i^2 + 2ad)
        points.last().velocityMps = 0.0
        for (i in points.size - 2 downTo 0) {
            val curr = points[i]
            val next = points[i + 1]
            val dist = next.distanceMeters - curr.distanceMeters
            val maxReachableVel = kotlin.math.sqrt(next.velocityMps * next.velocityMps + 2.0 * constraints.maxAccelerationMps2 * dist)
            curr.velocityMps = kotlin.math.min(curr.velocityMps, maxReachableVel)
        }

        // Forward pass: Apply trapezoidal acceleration. A finite initialVelocityMps starts the
        // profile from the robot's current speed instead of forcing a rest discontinuity;
        // callers regenerating a trajectory mid-motion should pass the measured speed.
        points.first().velocityMps = initialVelocityMps
        for (i in 1 until points.size) {
            val curr = points[i]
            val prev = points[i - 1]
            val dist = curr.distanceMeters - prev.distanceMeters
            val maxReachableVel = kotlin.math.sqrt(prev.velocityMps * prev.velocityMps + 2.0 * constraints.maxAccelerationMps2 * dist)
            curr.velocityMps = kotlin.math.min(curr.velocityMps, maxReachableVel)
        }

        return Path(points, emptyList())
    }
}
