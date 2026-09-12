package com.areslib.hardware.vision

import com.areslib.math.wrapAngle
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Reusable recovery consensus for field X/Y in meters and CCW-positive heading in radians.
 * A sample more than 0.35 m or 20 degrees from the current mean starts a new consensus.
 * Incremental translation and circular means avoid overflowing sums of finite field poses.
 * Owns only primitive state and allocates no storage while adding or clearing samples.
 * Callers separately enforce camera quality/freshness, stationarity, sample count and dwell.
 */
class VisionRecoveryConsensus {
    var sampleCount: Long = 0L
        private set
    var meanX: Double = 0.0
        private set
    var meanY: Double = 0.0
        private set
    var meanHeadingRad: Double = 0.0
        private set
    private var meanSin = 0.0
    private var meanCos = 0.0

    /**
     * Adds a finite sample, returning false and clearing consensus for invalid inputs.
     * A count of one afterward identifies a new window, including an inconsistent sample or
     * exhausted Long counter. The counter restarts rather than wrapping or crediting old evidence.
     */
    fun add(xMeters: Double, yMeters: Double, headingRadians: Double): Boolean {
        if (!xMeters.isFinite() || !yMeters.isFinite() || !headingRadians.isFinite()) {
            clear()
            return false
        }
        val heading = wrapAngle(headingRadians)
        if (sampleCount == Long.MAX_VALUE || (sampleCount > 0L &&
                (hypot(xMeters - meanX, yMeters - meanY) > MAX_TRANSLATION_DELTA_METERS ||
                    abs(wrapAngle(heading - meanHeadingRad)) > MAX_HEADING_DELTA_RAD))) clear()
        val sampleSin = sin(heading)
        val sampleCos = cos(heading)
        sampleCount++
        if (sampleCount == 1L) {
            meanX = xMeters
            meanY = yMeters
            meanSin = sampleSin
            meanCos = sampleCos
        } else {
            val weight = 1.0 / sampleCount.toDouble()
            // Accepted samples are close to the finite mean, so subtraction cannot overflow.
            meanX += (xMeters - meanX) * weight
            meanY += (yMeters - meanY) * weight
            meanSin += (sampleSin - meanSin) * weight
            meanCos += (sampleCos - meanCos) * weight
        }
        meanHeadingRad = wrapAngle(atan2(meanSin, meanCos))
        return true
    }

    fun clear() {
        sampleCount = 0L
        meanX = 0.0
        meanY = 0.0
        meanHeadingRad = 0.0
        meanSin = 0.0
        meanCos = 0.0
    }

    companion object {
        private const val MAX_TRANSLATION_DELTA_METERS = 0.35
        private val MAX_HEADING_DELTA_RAD = Math.toRadians(20.0)

        /**
         * Minimum whole observation count. [singleTag] doubles the request before rounding up,
         * as required by FRC recovery; FTC uses the unscaled requirement. Invalid/nonpositive
         * values and requests at or above 2^63 return zero, disabling recovery for an
         * unrepresentable count rather than truncating, clamping or overflowing it.
         */
        fun requiredSamples(threshold: Double, singleTag: Boolean = false): Long {
            if (!threshold.isFinite() || threshold <= 0.0) return 0L
            val scaled = if (singleTag) threshold * 2.0 else threshold
            // Long.MAX_VALUE rounds to exactly 2^63 as a Double. Every smaller representable
            // positive Double has a ceiling that fits Long; multiplying by two is an exact
            // exponent shift unless it overflows (also rejected by this comparison).
            if (scaled >= Long.MAX_VALUE.toDouble()) return 0L
            return ceil(scaled).toLong()
        }
    }
}
