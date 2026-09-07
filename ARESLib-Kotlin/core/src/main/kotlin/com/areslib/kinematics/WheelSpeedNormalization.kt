package com.areslib.kinematics

/** Common scale for coupled wheel commands. Positive infinity means no speed limit. */
internal fun wheelSpeedScale(maxMagnitude: Double, limit: Double): Double = when {
    !maxMagnitude.isFinite() || limit.isNaN() || limit <= 0.0 -> 0.0
    maxMagnitude > limit -> limit / maxMagnitude
    else -> 1.0
}
