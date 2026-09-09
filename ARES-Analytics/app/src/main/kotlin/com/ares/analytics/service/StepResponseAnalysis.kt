package com.ares.analytics.service

import com.ares.analytics.shared.models.MAX_SUPPORTED_TIMESTAMP_MS
import kotlin.math.*

/** Identifies one settled constant-input response; all scans and sample storage are O(n). */
internal object StepResponseAnalysis {
    private val ZERO_GAINS = AutoTunerPIDFGains(0.0, 0.0, 0.0)
    private val UNKNOWN = StepResponseMetrics()
    /** SIMC PI for a first-order-plus-delay model; no second pole has been identified for D. */
    fun gains(metrics: StepResponseMetrics): AutoTunerPIDFGains {
        val zero = ZERO_GAINS
        if (!metrics.isUsable) return zero
        val tau = metrics.timeConstantMs / 1000.0
        val delay = metrics.deadTimeMs / 1000.0
        if (tau <= 0.0) return zero
        val lambda = max(tau * 0.65, delay * 3.0)
        val proportional = (tau / (lambda + delay)) / metrics.processGain
        val integralTime = min(tau, 4.0 * (lambda + delay))
        if (!proportional.isFinite() || integralTime <= 0.0) return zero
        val kP = proportional.coerceIn(0.0, 50.0)
        val integral = kP / integralTime
        if (!integral.isFinite()) return zero
        return AutoTunerPIDFGains(kP, integral.coerceIn(0.0, 100.0), 0.0)
    }

    fun identify(samples: List<AlignedDataRow>, identifiedDcGain: Double? = null): StepResponseMetrics {
        // Indexed segment scans must not turn a linked list into quadratic work.
        val data = if (samples is java.util.RandomAccess) samples else samples.toList()
        if (identifiedDcGain != null && (!identifiedDcGain.isFinite() || identifiedDcGain <= 1e-6)) return UNKNOWN
        if (data.size < 20) return UNKNOWN
        var previousTime = -1L
        for (row in data) {
            if (row.timestampMs !in 0L..MAX_SUPPORTED_TIMESTAMP_MS || row.timestampMs <= previousTime ||
                !row.voltage.isFinite() || !row.velocity.isFinite() || !row.accel.isFinite()) return UNKNOWN
            previousTime = row.timestampMs
        }
        var best = UNKNOWN
        var largestStep = 0.0
        var start = 1
        while (start < data.size) {
            val voltage = data[start].voltage
            val step = abs(voltage - data[start - 1].voltage)
            if (!step.isFinite() || step < 1.0) { start++; continue }
            val tolerance = max(0.1, step * 0.05)
            var end = start + 1
            while (end < data.size && abs(data[end].voltage - voltage) <= tolerance) end++
            if (start >= 4 && end - start >= 10) {
                val candidate = fitSegment(data, start, end, tolerance, identifiedDcGain)
                if (candidate.timeConstantMs.isFinite() &&
                    ((candidate.isUsable && !best.isUsable) ||
                        (candidate.isUsable == best.isUsable && step > largestStep))) {
                    best = candidate
                    largestStep = step
                }
            }
            // An examined plateau is never scanned again as another candidate's tail.
            start = end
        }
        return best
    }

    private fun fitSegment(data: List<AlignedDataRow>, start: Int, end: Int, tolerance: Double, identifiedDcGain: Double?): StepResponseMetrics {
        val unknown = UNKNOWN
        val before = max(0, start - 8)
        val tail = end - 10
        var voltageScale = 0.0
        var velocityScale = 0.0
        for (i in before until end) {
            val row = data[i]
            voltageScale = max(voltageScale, abs(row.voltage))
            velocityScale = max(velocityScale, abs(row.velocity))
        }
        if (voltageScale == 0.0 || velocityScale == 0.0) return unknown
        var inputBefore = 0.0
        var baseline = 0.0
        var baselineMin = Double.POSITIVE_INFINITY
        var baselineMax = Double.NEGATIVE_INFINITY
        for (i in before until start) {
            inputBefore += data[i].voltage / voltageScale
            val v = data[i].velocity / velocityScale
            baseline += v
            baselineMin = min(baselineMin, v); baselineMax = max(baselineMax, v)
        }
        inputBefore = (inputBefore / (start - before)) * voltageScale
        baseline /= start - before
        for (i in before until start) if (abs(data[i].voltage - inputBefore) > tolerance) return unknown
        var inputAfter = 0.0
        for (i in start until start + 8) inputAfter += data[i].voltage / voltageScale
        inputAfter = (inputAfter / 8) * voltageScale
        val inputDelta = inputAfter - inputBefore
        if (!inputDelta.isFinite() || abs(inputDelta) < 1.0) return unknown
        var target = 0.0
        var tailMin = Double.POSITIVE_INFINITY
        var tailMax = Double.NEGATIVE_INFINITY
        for (i in tail until end) {
            val v = data[i].velocity / velocityScale
            target += v
            tailMin = min(tailMin, v); tailMax = max(tailMax, v)
        }
        target /= 10
        val baselineMoving = baselineMax - baselineMin > 0.10 * abs(target - baseline)
        // A coast-to-step experiment has no measured steady baseline. Its DC gain must
        // come from an independently identifiable voltage/velocity fit, never the transient offset.
        if (baselineMoving) {
            if (identifiedDcGain == null) return unknown
            baseline = data[start].velocity / velocityScale
        }
        val responseDelta = target - baseline
        if (abs(responseDelta) * velocityScale <= 1e-6 ||
            tailMax - tailMin > 0.05 * abs(responseDelta)) return unknown
        val processGain = identifiedDcGain ?: ((responseDelta / inputDelta) * velocityScale)
        if (!processGain.isFinite()) return unknown
        val count = end - start
        val progress = DoubleArray(count)
        var previousProgress = 0.0
        var previousElapsed = 0.0
        var ten = Double.NaN
        var ninety = Double.NaN
        var peak = 0.0
        var progressScale = 1.0
        var lastOutside = -1
        val startTime = data[start].timestampMs
        for (j in 0 until count) {
            val row = data[start + j]
            val p = (row.velocity / velocityScale - baseline) / responseDelta
            if (!p.isFinite()) return unknown
            progress[j] = p
            val elapsed = (row.timestampMs - startTime).toDouble()
            fun crossing(level: Double): Double = if (p > previousProgress) {
                previousElapsed + (elapsed - previousElapsed) * ((level - previousProgress) / (p - previousProgress)).coerceIn(0.0, 1.0)
            } else elapsed
            if (ten.isNaN() && p >= 0.10) ten = crossing(0.10)
            if (ninety.isNaN() && p >= 0.90) ninety = crossing(0.90)
            peak = max(peak, p)
            progressScale = max(progressScale, abs(p))
            if (abs(p - 1.0) > 0.02) lastOutside = j
            previousProgress = p; previousElapsed = elapsed
        }
        val riseTime = ninety - ten
        val tau = riseTime / ln(9.0)
        if (!tau.isFinite() || tau <= 0.0) return unknown
        val delay = max(0.0, ten + tau * ln(0.9))
        val settling = if (lastOutside < count - 1) {
            (data[start + lastOutside + 1].timestampMs - startTime).toDouble()
        } else Double.NaN
        val overshoot = max(0.0, (peak - 1.0) * 100.0)
        if (!overshoot.isFinite()) return unknown
        var mean = 0.0
        for (p in progress) mean += p / progressScale
        mean /= count
        var ssRes = 0.0
        var ssTot = 0.0
        for (j in 0 until count) {
            val elapsed = (data[start + j].timestampMs - startTime).toDouble()
            val predicted = if (elapsed <= delay) 0.0 else -expm1(-(elapsed - delay) / tau)
            val actual = progress[j] / progressScale
            val residual = actual - predicted / progressScale
            val deviation = actual - mean
            ssRes += residual * residual
            ssTot += deviation * deviation
        }
        val modelFit = if (ssTot > 0.0) (1.0 - ssRes / ssTot).coerceIn(0.0, 1.0) else 0.0
        if (!modelFit.isFinite()) return unknown
        return StepResponseMetrics(riseTime, overshoot, settling, delay, tau, processGain, modelFit)
    }
}
