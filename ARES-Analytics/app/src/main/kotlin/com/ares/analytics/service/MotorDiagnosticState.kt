package com.ares.analytics.service

import kotlin.math.abs
import kotlin.math.max

internal enum class MotorFeedbackSignal { POWER, VELOCITY, CURRENT }

/** Legacy MotorIO contract: duty [-1,1], encoder ticks/s, and nonnegative amperes. */
internal class MotorDiagnosticState {
    private val values = DoubleArray(3) { Double.NaN }
    private val times = LongArray(3) { -1L }
    private var current: MotorCurrentWindow? = null
    var timestampUs = -1L
        private set
    var hasEvidence = false
        private set
    var isStalled = false
        private set
    var isDisconnected = false
        private set

    fun accept(signal: MotorFeedbackSignal, timeUs: Long, value: Double): Boolean {
        val index = signal.ordinal
        if (timeUs < 0L || timeUs < times[index]) return false
        times[index] = timeUs
        timestampUs = max(timestampUs, timeUs)
        val valid = value.isFinite() && when (signal) {
            MotorFeedbackSignal.POWER -> value in -1.0..1.0
            MotorFeedbackSignal.CURRENT -> value >= 0.0
            MotorFeedbackSignal.VELOCITY -> true
        }
        values[index] = if (valid) value else Double.NaN
        if (signal == MotorFeedbackSignal.CURRENT) {
            if (!valid) current?.clear() else {
                val window = current ?: MotorCurrentWindow().also { current = it }
                window.accept(timeUs, value)
            }
        }
        val window = current
        val mean = if (window == null) Double.NaN else window.meanAt(timestampUs)
        hasEvidence = mean.isFinite()
        for (i in values.indices) {
            if (!values[i].isFinite() || times[i] < 0L || timestampUs - times[i] > MotorCurrentWindow.WINDOW_US) hasEvidence = false
        }
        isStalled = hasEvidence && abs(values[0]) > 0.35 && abs(values[1]) < 5.0 && mean > 5.0
        isDisconnected = hasEvidence && abs(values[0]) > 0.35 && abs(values[1]) < 5.0 && mean < 0.1
        return true
    }
}
