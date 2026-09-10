package com.areslib.kinematics

import kotlin.math.abs

/** Preserve subnormal sums on the ordinary path and avoid overflow in finite means. */
internal fun wheelMean(a: Double, b: Double): Double {
    val sum = a + b
    return if (sum.isFinite()) sum * 0.5 else a * 0.5 + b * 0.5
}

internal fun wheelDifferenceRatio(right: Double, left: Double, width: Double): Double {
    val difference = right - left
    return if (difference.isFinite()) difference / width
        else (right * 0.5 - left * 0.5) / (width * 0.5)
}

/** Compensate cancellation before dividing; scale only when a finite sum overflows. */
internal fun wheelMean(a: Double, b: Double, c: Double, d: Double): Double {
    val sum = compensatedWheelSum(a, b, c, d)
    return if (sum.isFinite()) sum * 0.25
        else compensatedWheelSum(a * 0.25, b * 0.25, c * 0.25, d * 0.25)
}

/** Choose division order so neither a tiny mean nor a large four-times-width loses the result. */
internal fun wheelMeanRatio(a: Double, b: Double, c: Double, d: Double, width: Double): Double {
    val sum = compensatedWheelSum(a, b, c, d)
    if (!sum.isFinite()) return compensatedWheelSum(a * 0.25, b * 0.25, c * 0.25, d * 0.25) / width
    val ratio = sum / width
    return if (ratio.isFinite()) ratio * 0.25 else (sum * 0.25) / width
}

private fun compensatedWheelSum(a: Double, b: Double, c: Double, d: Double): Double {
    val ab = a + b
    val e1 = if (abs(a) >= abs(b)) (a - ab) + b else (b - ab) + a
    val abc = ab + c
    val e2 = if (abs(ab) >= abs(c)) (ab - abc) + c else (c - abc) + ab
    val sum = abc + d
    val e3 = if (abs(abc) >= abs(d)) (abc - sum) + d else (d - sum) + abc
    return sum + (e1 + e2 + e3)
}
