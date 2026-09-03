package com.areslib.math

internal const val TWO_PI: Double = 2.0 * Math.PI

/**
 * Safely wraps an unbounded angle in radians to the principal interval $[-\pi, \pi)$.
 *
 * Prevents phase discontinuities in feedback controllers and Kalman filter innovation residuals
 * when crossing the $\pm\pi$ boundary.
 *
 * ### Mathematical Formulation:
 * $$\theta_{\text{wrapped}} = ((\theta + \pi) \pmod{2\pi}) - \pi$$
 *
 * ### Physical Units & Coordinate Conventions:
 * - Angle: Radians ($rad$), **CCW-positive** ($0 = +X$, $\frac{\pi}{2} = +Y$).
 *
 * ### Zero-GC & Robustness Guarantees:
 * - $O(1)$ deterministic execution time with zero dynamic memory allocation.
 * - Instantly returns $0.0$ if the input is `Double.NaN` or `Double.POSITIVE_INFINITY` / `Double.NEGATIVE_INFINITY`,
 *   preventing infinite loop hangs inside mod-arithmetic loops.
 *
 * @param angleRad The unbounded angle in radians ($rad$).
 * @return Angle wrapped into $[-\pi, \pi)$ radians ($rad$). Returns $0.0$ on non-finite input.
 */
fun wrapAngle(angleRad: Double): Double {
    if (!angleRad.isFinite()) {
        return 0.0 // Return safe default instead of looping infinitely
    }
    val wrapped = (angleRad + Math.PI) % TWO_PI
    return if (wrapped < 0.0) wrapped + Math.PI else wrapped - Math.PI
}

