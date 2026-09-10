package com.areslib.kinematics

/** Common scale for coupled commands. NaN means invalid; positive infinite limit is unlimited. */
internal fun wheelSpeedScale(maxMagnitude: Double, limit: Double): Double = when {
    !maxMagnitude.isFinite() || limit.isNaN() || limit <= 0.0 -> Double.NaN
    maxMagnitude > limit -> limit / maxMagnitude
    else -> 1.0
}

/** A tiny scale can underflow before multiplication even when the scaled speed is representable. */
internal fun scaledWheelSpeed(value: Double, maxMagnitude: Double, limit: Double, scale: Double): Double {
    if (scale.isNaN()) return 0.0
    if (scale >= 1.0) return value
    val result = if (scale >= java.lang.Double.MIN_NORMAL) value * scale else (value / maxMagnitude) * limit
    return result.coerceIn(-limit, limit)
}
