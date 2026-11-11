package com.ares.analytics.service.analysis

import com.ares.analytics.shared.models.League
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class ScatterPresetKind(val label: String) {
    BATTERY_INTERNAL_RESISTANCE("Battery V vs I (R_int mΩ)"),
    MOTOR_TORQUE_SPEED("Motor Velocity vs Current"),
    PHASE_PORTRAIT("Phase Portrait (Pos vs Vel)"),
    ERROR_VS_SPEED("Tracking Error vs Speed"),
    CUSTOM("Custom X vs Y"),
}

enum class BatteryResistanceGrade(val label: String) {
    GOOD("GOOD"),
    FAIR("FAIR"),
    WEAK("REPLACE SOON"),
}

data class ScatterPoint(
    val timestampMs: Long,
    val x: Double,
    val y: Double,
    /** Normalized `[0..1]` progression within the session window for time-gradient coloring. */
    val normalizedTime: Float,
)

data class ScatterRegressionResult(
    val points: List<ScatterPoint>,
    val slope: Double,
    val intercept: Double,
    val rSquared: Double,
    val pearsonR: Double,
    val xMin: Double,
    val xMax: Double,
    val yMin: Double,
    val yMax: Double,
)

data class BatteryInternalResistanceReport(
    val internalResistanceMilliOhms: Double,
    val openCircuitVoltage: Double,
    val peakCurrentAmps: Double,
    val minObservedVoltage: Double,
    val grade: BatteryResistanceGrade,
    val regression: ScatterRegressionResult,
)

/**
 * Synchronizes arbitrary X and Y telemetry channels onto matched timestamps and computes OLS
 * linear regression, correlation, phase-portrait bounds, and empirical battery + harness
 * internal resistance ($R_{\text{int}}$ in $\text{m}\Omega$).
 */
object ScatterRegressionAnalyzer {

    fun analyzePair(
        xSamples: List<TimedScalarSample>,
        ySamples: List<TimedScalarSample>,
        maxToleranceMs: Long = 50L,
        maxPoints: Int = 1_500,
    ): ScatterRegressionResult {
        val xs = SignalMathEngine.deduplicateTimestamps(xSamples)
        val ys = SignalMathEngine.deduplicateTimestamps(ySamples)
        if (xs.size < 3 || ys.size < 3) {
            return emptyScatter()
        }

        val minTs = max(xs.first().timestampMs, ys.first().timestampMs)
        val maxTs = min(xs.last().timestampMs, ys.last().timestampMs)
        if (minTs >= maxTs) return emptyScatter()
        val spanMs = (maxTs - minTs).coerceAtLeast(1L).toDouble()

        val rawPoints = ArrayList<ScatterPoint>(min(xs.size, maxPoints))
        val stride = max(1, xs.size / maxPoints)
        var yIdx = 0

        var i = 0
        while (i < xs.size) {
            val sx = xs[i]
            val ts = sx.timestampMs
            if (ts in minTs..maxTs) {
                while (yIdx + 1 < ys.size && ys[yIdx + 1].timestampMs <= ts) {
                    yIdx++
                }
                val nearestGap = min(
                    abs(ts - ys[yIdx].timestampMs),
                    if (yIdx + 1 < ys.size) abs(ys[yIdx + 1].timestampMs - ts) else Long.MAX_VALUE,
                )
                if (nearestGap <= maxToleranceMs) {
                    val interpY = if (ys[yIdx].timestampMs == ts || yIdx + 1 >= ys.size) {
                        ys[yIdx].value
                    } else {
                        val left = ys[yIdx]
                        val right = ys[yIdx + 1]
                        val dt = (right.timestampMs - left.timestampMs).toDouble()
                        val alpha = if (dt > 0.0) ((ts - left.timestampMs) / dt).coerceIn(0.0, 1.0) else 0.0
                        left.value + alpha * (right.value - left.value)
                    }
                    if (sx.value.isFinite() && interpY.isFinite()) {
                        val normT = ((ts - minTs) / spanMs).coerceIn(0.0, 1.0).toFloat()
                        rawPoints += ScatterPoint(ts, sx.value, interpY, normT)
                    }
                }
            }
            i += stride
        }

        if (rawPoints.size < 3) return emptyScatter()

        val n = rawPoints.size.toDouble()
        val meanX = rawPoints.sumOf { it.x } / n
        val meanY = rawPoints.sumOf { it.y } / n
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        var xMin = Double.POSITIVE_INFINITY
        var xMax = Double.NEGATIVE_INFINITY
        var yMin = Double.POSITIVE_INFINITY
        var yMax = Double.NEGATIVE_INFINITY

        for (pt in rawPoints) {
            val dx = pt.x - meanX
            val dy = pt.y - meanY
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
            xMin = min(xMin, pt.x)
            xMax = max(xMax, pt.x)
            yMin = min(yMin, pt.y)
            yMax = max(yMax, pt.y)
        }

        val slope = if (sxx > 1e-12) sxy / sxx else 0.0
        val intercept = meanY - slope * meanX
        val denom = sqrt(sxx * syy)
        val r = if (denom > 1e-12) (sxy / denom).coerceIn(-1.0, 1.0) else 0.0
        val r2 = (r * r).coerceIn(0.0, 1.0)

        return ScatterRegressionResult(
            points = rawPoints,
            slope = slope,
            intercept = intercept,
            rSquared = r2,
            pearsonR = r,
            xMin = xMin,
            xMax = xMax,
            yMin = yMin,
            yMax = yMax,
        )
    }

    /**
     * Fits $V_{\text{batt}}(I) = V_{\text{oc}} - R_{\text{int}} \cdot I$ across active current draw spans
     * and reports battery + power-harness DC internal resistance in milliohms ($\text{m}\Omega$).
     */
    fun analyzeBatteryInternalResistance(
        currentSamples: List<TimedScalarSample>,
        voltageSamples: List<TimedScalarSample>,
        league: League = League.FRC,
    ): BatteryInternalResistanceReport? {
        val scatter = analyzePair(
            xSamples = currentSamples.filter { it.value >= 0.0 },
            ySamples = voltageSamples.filter { it.value > 3.0 },
            maxToleranceMs = 60L,
        )
        if (scatter.points.size < 8 || (scatter.xMax - scatter.xMin) < 1.5) return null
        // Physical battery sag has negative dV/dI slope; convert V/A -> mOhm (* -1000)
        val rIntMilliOhms = (-scatter.slope * 1000.0).coerceAtLeast(0.0)
        val voc = scatter.intercept
        if (!rIntMilliOhms.isFinite() || !voc.isFinite()) return null

        val (goodMaxMilliOhms, fairMaxMilliOhms) = when (league) {
            League.FRC -> 18.0 to 28.0
            League.FTC -> 45.0 to 75.0
            League.XRP -> 120.0 to 220.0
        }
        val grade = when {
            rIntMilliOhms <= goodMaxMilliOhms -> BatteryResistanceGrade.GOOD
            rIntMilliOhms <= fairMaxMilliOhms -> BatteryResistanceGrade.FAIR
            else -> BatteryResistanceGrade.WEAK
        }

        return BatteryInternalResistanceReport(
            internalResistanceMilliOhms = rIntMilliOhms,
            openCircuitVoltage = voc,
            peakCurrentAmps = scatter.xMax,
            minObservedVoltage = scatter.yMin,
            grade = grade,
            regression = scatter,
        )
    }

    private fun emptyScatter() = ScatterRegressionResult(
        points = emptyList(),
        slope = 0.0,
        intercept = 0.0,
        rSquared = 0.0,
        pearsonR = 0.0,
        xMin = 0.0,
        xMax = 1.0,
        yMin = 0.0,
        yMax = 1.0,
    )
}
