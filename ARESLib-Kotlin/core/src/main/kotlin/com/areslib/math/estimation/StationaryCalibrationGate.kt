package com.areslib.math.estimation

/**
 * Allocation-free collection gate using cached motion observations in RobotClock milliseconds.
 * Unknown, stale, future or moving feedback and interrupted observation loops restart the dwell.
 */
class StationaryCalibrationGate(
    private val translationThresholdMps: Double = 0.03,
    private val angularThresholdRps: Double = 0.05,
    private val dwellMs: Long = 500L,
    private val maximumFeedbackAgeMs: Long = 100L,
) {
    private var active = false
    private var stationarySinceMs = 0L
    private var lastUpdateMs = 0L

    init {
        require(translationThresholdMps.isFinite() && translationThresholdMps >= 0.0)
        require(angularThresholdRps.isFinite() && angularThresholdRps >= 0.0)
        require(dwellMs >= 0L && maximumFeedbackAgeMs >= 0L)
    }

    fun reset() { active = false }

    fun update(
        nowMs: Long,
        driverNeutral: Boolean,
        translationMetersPerSecond: Double,
        angularRadiansPerSecond: Double,
        motionMeasurementsValid: Boolean,
        observationTimestampMs: Long,
    ): Boolean {
        val age = nowMs - observationTimestampMs
        val stationary = driverNeutral && motionMeasurementsValid && observationTimestampMs <= nowMs &&
            age >= 0L && age <= maximumFeedbackAgeMs &&
            translationMetersPerSecond.isFinite() && angularRadiansPerSecond.isFinite() &&
            kotlin.math.abs(translationMetersPerSecond) <= translationThresholdMps &&
            kotlin.math.abs(angularRadiansPerSecond) <= angularThresholdRps
        if (!stationary) {
            reset()
            return false
        }
        val gap = nowMs - lastUpdateMs
        if (!active || nowMs < lastUpdateMs || gap < 0L || gap > maximumFeedbackAgeMs) {
            active = true
            stationarySinceMs = nowMs
        }
        lastUpdateMs = nowMs
        val elapsed = nowMs - stationarySinceMs
        return elapsed >= 0L && elapsed >= dwellMs
    }
}
