package com.areslib.frc

import com.areslib.hardware.drive.SwerveHardwareIO

/**
 * Retains the most recent loop-sampled four-module absolute-encoder observation.
 *
 * Calibration is deliberately denied unless all four readings are finite, inside the physical
 * one-rotation absolute-sensor envelope, and recent. ARESLib's cached encoder-validity signal
 * proves that the latest coordinated CTRE refresh succeeded before the values are accepted.
 */
internal class SwerveOffsetCalibrationSampleCache(
    private val maxAgeMs: Long = 100L
) {
    private val scratch = DoubleArray(MODULE_COUNT)
    private val cached = DoubleArray(MODULE_COUNT)
    private var sampleTimestampMs = Long.MIN_VALUE

    fun record(io: SwerveHardwareIO, timestampMs: Long) {
        scratch.fill(Double.NaN)
        try {
            if (!io.getEncoderPositionsIfValid(scratch)) {
                invalidate()
                return
            }
            val latencyMs = io.signalLatencyMs
            if (!isPlausible(scratch) || !latencyMs.isFinite() || latencyMs < 0.0 || latencyMs > maxAgeMs) {
                invalidate()
                return
            }
            scratch.copyInto(cached)
            sampleTimestampMs = timestampMs
        } catch (_: Exception) {
            invalidate()
        }
    }

    fun copyFresh(nowMs: Long, out: DoubleArray): Boolean {
        require(out.size >= MODULE_COUNT) { "Swerve offset output must contain four modules" }
        val ageMs = nowMs - sampleTimestampMs
        if (sampleTimestampMs == Long.MIN_VALUE || ageMs < 0L || ageMs > maxAgeMs || !isPlausible(cached)) {
            return false
        }
        cached.copyInto(out)
        return true
    }

    private fun invalidate() {
        cached.fill(Double.NaN)
        sampleTimestampMs = Long.MIN_VALUE
    }

    internal companion object {
        const val MODULE_COUNT = 4
        const val MAX_ABSOLUTE_ENCODER_ROTATIONS = 1.0

        fun isPlausible(values: DoubleArray): Boolean {
            if (values.size < MODULE_COUNT) return false
            for (index in 0 until MODULE_COUNT) {
                val value = values[index]
                if (!value.isFinite() || kotlin.math.abs(value) > MAX_ABSOLUTE_ENCODER_ROTATIONS) return false
            }
            return true
        }
    }
}
