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

/** Wraps this angle in radians to $[-\pi, \pi)$. */
fun Double.wrapToPi(): Double = wrapAngle(this)

/** Wraps this angle in radians to $[0, 2\pi)$. Returns 0.0 on non-finite input. */
fun Double.wrapTo2Pi(): Double {
    if (!isFinite()) return 0.0
    val wrapped = this % TWO_PI
    val res = if (wrapped < 0.0) wrapped + TWO_PI else wrapped
    return if (res == 0.0) 0.0 else res
}

/** Converts this value from radians to degrees. */
fun Double.toDegrees(): Double = Math.toDegrees(this)

/** Converts this value from degrees to radians. */
fun Double.toRadians(): Double = Math.toRadians(this)

/** Converts angular velocity in radians per second to rotations per minute. */
fun Double.radPerSecToRpm(): Double = if (!isFinite()) 0.0 else (this / TWO_PI) * 60.0

/** Converts rotational speed in rotations per minute to radians per second. */
fun Double.rpmToRadPerSec(): Double = if (!isFinite()) 0.0 else (this / 60.0) * TWO_PI

/**
 * Low part of a rounded product using Dekker splitting. Callers scale operands first so
 * multiplying by 2^27+1 and forming partial products cannot overflow. This is not a general
 * replacement for fused multiply-add on arbitrary inputs. No arrays or boxed pairs are created.
 */
internal fun productRoundoff(a: Double, b: Double, product: Double): Double {
    val splitA = 134_217_729.0 * a
    val highA = splitA - (splitA - a)
    val lowA = a - highA
    val splitB = 134_217_729.0 * b
    val highB = splitB - (splitB - b)
    val lowB = b - highB
    return ((highA * highB - product) + highA * lowB + lowA * highB) + lowA * lowB
}

