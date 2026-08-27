package com.areslib.input

import kotlin.math.abs
import kotlin.math.pow

/**
 * Calibrates and shapes one raw axis without retaining state or allocating.
 *
 * [inputCenter] is the zero point. Values on either side are independently normalized, which
 * supports asymmetric sticks and unsigned triggers (`inputMin == inputCenter == 0`). Deadband is
 * removed and the remainder is rescaled, so full input still produces full output. [exponent]
 * values above one provide fine control near center; values below one make the response faster.
 */
data class AxisTransform(
    val inputMin: Double = -1.0,
    val inputCenter: Double = 0.0,
    val inputMax: Double = 1.0,
    val deadband: Double = 0.0,
    val exponent: Double = 1.0,
    val inverted: Boolean = false,
    val outputMin: Double = -1.0,
    val outputMax: Double = 1.0,
) {
    init {
        require(inputMin.isFinite() && inputCenter.isFinite() && inputMax.isFinite()) {
            "axis calibration values must be finite"
        }
        require(inputMin <= inputCenter && inputCenter < inputMax) {
            "axis calibration must satisfy inputMin <= inputCenter < inputMax"
        }
        require(deadband.isFinite() && deadband >= 0.0 && deadband < 1.0) {
            "deadband must be in [0, 1)"
        }
        require(exponent.isFinite() && exponent > 0.0) { "exponent must be positive" }
        require(outputMin.isFinite() && outputMin <= 0.0) { "outputMin must be finite and <= 0" }
        require(outputMax.isFinite() && outputMax >= 0.0) { "outputMax must be finite and >= 0" }
    }

    fun apply(rawValue: Double): Double {
        if (!rawValue.isFinite()) return 0.0

        val clamped = rawValue.coerceIn(inputMin, inputMax)
        var normalized = when {
            clamped >= inputCenter -> (clamped - inputCenter) / (inputMax - inputCenter)
            inputCenter == inputMin -> 0.0
            else -> (clamped - inputCenter) / (inputCenter - inputMin)
        }

        val magnitude = abs(normalized)
        normalized = if (magnitude <= deadband) {
            0.0
        } else {
            val rescaled = ((magnitude - deadband) / (1.0 - deadband)).pow(exponent)
            if (normalized < 0.0) -rescaled else rescaled
        }

        if (inverted) normalized = -normalized
        return if (normalized < 0.0) {
            -(-normalized * -outputMin)
        } else {
            normalized * outputMax
        }
    }

    companion object {
        /** Standard `[0, 1]` trigger calibration. */
        fun trigger(
            deadband: Double = 0.0,
            exponent: Double = 1.0,
            inverted: Boolean = false,
        ): AxisTransform = AxisTransform(
            inputMin = 0.0,
            inputCenter = 0.0,
            inputMax = 1.0,
            deadband = deadband,
            exponent = exponent,
            inverted = inverted,
            outputMin = -1.0,
            outputMax = 1.0,
        )
    }
}
