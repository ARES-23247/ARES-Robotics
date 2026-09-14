package com.areslib.superstructure

/** STEP holds each point's value until the next point, reproducing every knot exactly. */
enum class LutInterpolationMethod { LINEAR, STEP, SMOOTH_COSINE }

data class LutControlPoint(val inputX: Double, val outputY: Double)

data class SuperstructureDynamicLut(
    val lutId: String,
    val displayName: String = "",
    val inputUnit: String = "",
    val outputUnit: String = "",
    val interpolation: LutInterpolationMethod = LutInterpolationMethod.LINEAR,
    val controlPoints: List<LutControlPoint> = emptyList(),
) {
    /** Samples a validated, sorted LUT without allocating. */
    fun sample(x: Double): Double {
        if (!x.isFinite() || controlPoints.isEmpty()) return Double.NaN
        if (controlPoints.size == 1 || x <= controlPoints.first().inputX) return controlPoints.first().outputY
        if (x >= controlPoints.last().inputX) return controlPoints.last().outputY
        for (index in 0 until controlPoints.size - 1) {
            val lower = controlPoints[index]
            val upper = controlPoints[index + 1]
            if (x <= upper.inputX) {
                if (x == upper.inputX) return upper.outputY
                if (interpolation == LutInterpolationMethod.STEP) return lower.outputY
                val span = upper.inputX - lower.inputX
                val ratio = if (span.isFinite()) (x - lower.inputX) / span
                    else (x * 0.5 - lower.inputX * 0.5) / (upper.inputX * 0.5 - lower.inputX * 0.5)
                return when (interpolation) {
                    LutInterpolationMethod.STEP -> lower.outputY
                    LutInterpolationMethod.LINEAR -> interpolateFinite(lower.outputY, upper.outputY, ratio)
                    LutInterpolationMethod.SMOOTH_COSINE -> {
                        val factor = (1.0 - kotlin.math.cos(ratio * kotlin.math.PI)) / 2.0
                        interpolateFinite(lower.outputY, upper.outputY, factor)
                    }
                }
            }
        }
        return controlPoints.last().outputY
    }

    private fun interpolateFinite(lower: Double, upper: Double, fraction: Double): Double =
        if ((lower <= 0.0 && upper >= 0.0) || (lower >= 0.0 && upper <= 0.0))
            lower * (1.0 - fraction) + upper * fraction
        else lower + fraction * (upper - lower)
}
