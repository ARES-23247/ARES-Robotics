package com.areslib.math

internal const val TWO_PI: Double = 2.0 * Math.PI

/**
 * Safely wraps an unbounded angle in radians to the principal interval $[-\pi, \pi)$.
 *
 * Prevents phase discontinuities in feedback controllers and Kalman filter innovation residuals
 * when crossing the $\pm\pi$ boundary.
 *
 * ### Mathematical Formulation:
 * Reduce by the represented Double period $2\pi$, then add/subtract one period if needed.
 * Already-normalized values are returned unchanged, preserving small angles. Very large
 * inputs are reduced modulo the represented period, not an arbitrary-precision value of pi.
 *
 * ### Physical Units & Coordinate Conventions:
 * - Angle: Radians ($rad$), **CCW-positive** ($0 = +X$, $\frac{\pi}{2} = +Y$).
 *
 * ### Zero-GC & Robustness Guarantees:
 * - Bounded fixed-width arithmetic with zero dynamic memory allocation; no real-time deadline guarantee.
 * - Instantly returns $0.0$ if the input is `Double.NaN` or `Double.POSITIVE_INFINITY` / `Double.NEGATIVE_INFINITY`,
 *   preserving the legacy fallback. Callers must validate raw measurements/commands before
 *   wrapping; a finite wrapped value does not prove the original input was valid.
 *
 * @param angleRad The unbounded angle in radians ($rad$).
 * @return Angle wrapped into $[-\pi, \pi)$ radians ($rad$). Returns $0.0$ on non-finite input.
 */
fun wrapAngle(angleRad: Double): Double {
    if (!angleRad.isFinite()) {
        return 0.0 // Return safe default instead of looping infinitely
    }
    if (angleRad >= -Math.PI && angleRad < Math.PI) return angleRad
    val wrapped = angleRad % TWO_PI
    return when {
        wrapped >= Math.PI -> wrapped - TWO_PI
        wrapped < -Math.PI -> wrapped + TWO_PI
        else -> wrapped
    }
}

