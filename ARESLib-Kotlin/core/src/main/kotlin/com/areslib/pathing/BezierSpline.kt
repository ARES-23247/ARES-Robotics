package com.areslib.pathing

import com.areslib.math.geometry.*

import kotlin.math.atan2

/**
 * Cubic Bezier Parametric Curve Evaluation Engine.
 *
 * Provides analytical position, velocity derivative vector, and tangent heading evaluation
 * for Cubic Bezier splines used in trajectory planning.
 *
 * ### Mathematical Formulations:
 * 1. **Cubic Bezier Position Vector $\mathbf{B}(t)$**:
 *    Given start anchor $\mathbf{P}_0$, control handle $\mathbf{P}_1$, control handle $\mathbf{P}_2$, and end anchor $\mathbf{P}_3$ for $t \in [0.0, 1.0]$:
 *    $$\mathbf{B}(t) = (1-t)^3 \mathbf{P}_0 + 3(1-t)^2 t \mathbf{P}_1 + 3(1-t) t^2 \mathbf{P}_2 + t^3 \mathbf{P}_3$$
 * 2. **First Derivative Tangent Vector $\mathbf{B}'(t)$**:
 *    $$\mathbf{B}'(t) = 3(1-t)^2(\mathbf{P}_1 - \mathbf{P}_0) + 6(1-t)t(\mathbf{P}_2 - \mathbf{P}_1) + 3t^2(\mathbf{P}_3 - \mathbf{P}_2)$$
 * 3. **Spline Path Tangent Heading $\theta(t)$**:
 *    $$\theta(t) = \text{atan2}(B_y'(t), B_x'(t))$$
 *
 * ### Physical Units & Coordinate Conventions:
 * - Control Points $(\mathbf{P}_0, \mathbf{P}_1, \mathbf{P}_2, \mathbf{P}_3)$: Field-centric meters ($m$)
 * - Spline Parameter $t$: Normalized unit interval $[0.0, 1.0]$
 * - Derivative $\mathbf{B}'(t)$: Meters per unit parameter ($m/t$)
 * - Tangent Heading $\theta(t)$: Radians ($rad$), **CCW-positive** ($0 = +X$, $\frac{\pi}{2} = +Y$)
 *
 * Position and derivative evaluation allocate their returned vectors. Heading evaluation uses
 * scalar arithmetic and an unboxed Rotation2d return on the JVM; callers may still box value
 * classes in generic contexts. No intermediate derivative vector is needed for heading.
 */
object BezierSpline {
    
    /**
     * Evaluates the 2D Cartesian position along the Cubic Bezier curve at parameter $t \in [0.0, 1.0]$.
     * 
     * @param p0 Start anchor point $\mathbf{P}_0$ in meters ($m$).
     * @param p1 First handle control point $\mathbf{P}_1$ in meters ($m$).
     * @param p2 Second handle control point $\mathbf{P}_2$ in meters ($m$).
     * @param p3 End anchor point $\mathbf{P}_3$ in meters ($m$).
     * @param t Curve interpolation parameter $t \in [0.0, 1.0]$.
     * @return Evaluated 2D position [Translation2d] in meters ($m$).
     */
    fun evaluate(p0: Translation2d, p1: Translation2d, p2: Translation2d, p3: Translation2d, t: Double): Translation2d {
        val u = 1.0 - t
        val uu = u * u
        val uuu = uu * u
        val tt = t * t
        val ttt = tt * t

        val x = uuu * p0.x + 3 * uu * t * p1.x + 3 * u * tt * p2.x + ttt * p3.x
        val y = uuu * p0.y + 3 * uu * t * p1.y + 3 * u * tt * p2.y + ttt * p3.y

        return Translation2d(x, y)
    }

    /**
     * Evaluates the first derivative vector $\mathbf{B}'(t)$ along the Cubic Bezier curve at parameter $t \in [0.0, 1.0]$.
     *
     * @param p0 Start anchor point $\mathbf{P}_0$ in meters ($m$).
     * @param p1 First handle control point $\mathbf{P}_1$ in meters ($m$).
     * @param p2 Second handle control point $\mathbf{P}_2$ in meters ($m$).
     * @param p3 End anchor point $\mathbf{P}_3$ in meters ($m$).
     * @param t Curve interpolation parameter $t \in [0.0, 1.0]$.
     * @return Tangent derivative vector [Translation2d].
     */
    fun evaluateDerivative(p0: Translation2d, p1: Translation2d, p2: Translation2d, p3: Translation2d, t: Double): Translation2d =
        withDerivative(p0, p1, p2, p3, t) { x, y -> Translation2d(x, y) }

    private inline fun <T> withDerivative(
        p0: Translation2d, p1: Translation2d, p2: Translation2d, p3: Translation2d,
        t: Double, result: (Double, Double) -> T
    ): T {
        val u = 1.0 - t
        val firstWeight = 3 * (u * u)
        val middleWeight = 6 * u * t
        val lastWeight = 3 * (t * t)

        // B'(t) = 3(1-t)^2(P1 - P0) + 6(1-t)t(P2 - P1) + 3t^2(P3 - P2)
        val term1X = firstWeight * (p1.x - p0.x)
        val term1Y = firstWeight * (p1.y - p0.y)

        val term2X = middleWeight * (p2.x - p1.x)
        val term2Y = middleWeight * (p2.y - p1.y)

        val term3X = lastWeight * (p3.x - p2.x)
        val term3Y = lastWeight * (p3.y - p2.y)

        return result(term1X + term2X + term3X, term1Y + term2Y + term3Y)
    }

    /**
     * Calculates the path tangent direction angle along the curve at parameter $t \in [0.0, 1.0]$.
     * Collapsed endpoint handles use the first nonzero control-polygon direction, giving the
     * one-sided limiting tangent. At an interior stationary point, or a fully constant curve,
     * no unique direction is supplied and the result retains atan2's zero-vector convention.
     *
     * @param p0 Start anchor point $\mathbf{P}_0$ in meters ($m$).
     * @param p1 First handle control point $\mathbf{P}_1$ in meters ($m$).
     * @param p2 Second handle control point $\mathbf{P}_2$ in meters ($m$).
     * @param p3 End anchor point $\mathbf{P}_3$ in meters ($m$).
     * @param t Curve interpolation parameter $t \in [0.0, 1.0]$.
     * @return Path tangent direction [Rotation2d] in radians ($rad$), CCW-positive.
     */
    fun evaluateHeading(p0: Translation2d, p1: Translation2d, p2: Translation2d, p3: Translation2d, t: Double): Rotation2d =
        withDerivative(p0, p1, p2, p3, t) { x, y ->
            var dx = x
            var dy = y
            if (dx == 0.0 && dy == 0.0) {
                if (t == 0.0) {
                    dx = p2.x - p0.x
                    dy = p2.y - p0.y
                    if (dx == 0.0 && dy == 0.0) {
                        dx = p3.x - p0.x
                        dy = p3.y - p0.y
                    }
                } else if (t == 1.0) {
                    dx = p3.x - p1.x
                    dy = p3.y - p1.y
                    if (dx == 0.0 && dy == 0.0) {
                        dx = p3.x - p0.x
                        dy = p3.y - p0.y
                    }
                }
            }
            Rotation2d(atan2(dy, dx))
        }
}

