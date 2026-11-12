package com.ares.analytics.service.analysis

import com.ares.analytics.service.SysIdService
import com.ares.analytics.shared.models.TelemetryFrame
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Immutable scalar telemetry sample ordered by millisecond timestamp. */
data class TimedScalarSample(
    val timestampMs: Long,
    val value: Double,
)

enum class DerivedChannelOperation(val label: String, val unitSuffix: String) {
    DERIVATIVE("d/dt Derivative", "/s"),
    INTEGRAL("∫ dt Integral", "·s"),
    INTEGRAL_MAH("∫ I dt Charge", "mAh"),
    CHANNEL_DELTA("A − B Delta", ""),
    MOVING_AVERAGE("Moving Average (5-pt)", ""),
    SAVITZKY_GOLAY("Savitzky-Golay (5-pt)", ""),
}

data class SpectralPeak(
    val frequencyHz: Double,
    val amplitude: Double,
    val powerDensity: Double,
)

data class SignalSpectrumAnalysis(
    val sampleRateHz: Double,
    val frequenciesHz: DoubleArray,
    val magnitudes: DoubleArray,
    val dominantFrequencyHz: Double,
    val peaks: List<SpectralPeak>,
)

/**
 * Pure, allocation-disciplined math & spectral transformations for recorded and live telemetry channels.
 *
 * Wraps [SysIdService.performFftAnalysis] for Hann-windowed spectral decomposition while providing
 * derivative, trapezoidal integral (`A·s` / `mAh`), asynchronous channel delta, and Savitzky-Golay smoothing.
 */
object SignalMathEngine {

    fun fromFrames(frames: List<TelemetryFrame>): List<TimedScalarSample> =
        frames
            .asSequence()
            .filter { it.value.isFinite() }
            .sortedBy { it.timestampMs }
            .map { TimedScalarSample(it.timestampMs, it.value) }
            .toList()

    /**
     * Central-difference numerical derivative (`dy/dt` per second) with one-sided causal slopes at endpoints.
     * Collapses duplicate timestamps safely so zero `dt` never produces `NaN` or `Infinity`.
     */
    fun derivative(samples: List<TimedScalarSample>): List<TimedScalarSample> {
        val clean = deduplicateTimestamps(samples)
        if (clean.size < 2) return emptyList()
        val out = ArrayList<TimedScalarSample>(clean.size)
        for (i in clean.indices) {
            val left = clean[max(0, i - 1)]
            val right = clean[min(clean.lastIndex, i + 1)]
            val dtSec = (right.timestampMs - left.timestampMs) / 1000.0
            val slope = if (dtSec > 1e-9) (right.value - left.value) / dtSec else 0.0
            out += TimedScalarSample(clean[i].timestampMs, if (slope.isFinite()) slope else 0.0)
        }
        return out
    }

    /** Cumulative trapezoidal integral $\int_{t_0}^{t} y(\tau)\,d\tau$ in `[unit * seconds]`. */
    fun cumulativeIntegral(samples: List<TimedScalarSample>): List<TimedScalarSample> {
        val clean = deduplicateTimestamps(samples)
        if (clean.isEmpty()) return emptyList()
        val out = ArrayList<TimedScalarSample>(clean.size)
        var acc = 0.0
        out += TimedScalarSample(clean[0].timestampMs, 0.0)
        for (i in 1 until clean.size) {
            val dtSec = ((clean[i].timestampMs - clean[i - 1].timestampMs).coerceAtLeast(0L)) / 1000.0
            val area = 0.5 * (clean[i - 1].value + clean[i].value) * dtSec
            if (area.isFinite()) acc += area
            out += TimedScalarSample(clean[i].timestampMs, acc)
        }
        return out
    }

    /** Converts Coulombs (`Ampere * seconds`) to milliamp-hours (`1 mAh = 3.6 A·s`). */
    fun ampSecondsToMilliAmpHours(ampSeconds: Double): Double = ampSeconds / 3.6

    /**
     * Computes `A(t) - B(t)` over the overlapping timestamp domain `[max(startA, startB), min(endA, endB)]`
     * by linearly interpolating channel `B` onto channel `A`'s timestamps.
     */
    fun channelDelta(
        channelA: List<TimedScalarSample>,
        channelB: List<TimedScalarSample>,
    ): List<TimedScalarSample> {
        val a = deduplicateTimestamps(channelA)
        val b = deduplicateTimestamps(channelB)
        if (a.isEmpty() || b.isEmpty()) return emptyList()
        val minTs = max(a.first().timestampMs, b.first().timestampMs)
        val maxTs = min(a.last().timestampMs, b.last().timestampMs)
        if (minTs > maxTs) return emptyList()

        val out = ArrayList<TimedScalarSample>(a.size)
        var bIdx = 0
        for (sampleA in a) {
            val ts = sampleA.timestampMs
            if (ts < minTs || ts > maxTs) continue
            while (bIdx + 1 < b.size && b[bIdx + 1].timestampMs <= ts) {
                bIdx++
            }
            val bVal = if (b[bIdx].timestampMs == ts || bIdx + 1 >= b.size) {
                b[bIdx].value
            } else {
                val left = b[bIdx]
                val right = b[bIdx + 1]
                val span = (right.timestampMs - left.timestampMs).toDouble()
                val alpha = if (span > 0.0) (ts - left.timestampMs) / span else 0.0
                left.value + alpha * (right.value - left.value)
            }
            val diff = sampleA.value - bVal
            if (diff.isFinite()) {
                out += TimedScalarSample(ts, diff)
            }
        }
        return out
    }

    /** Centered symmetric moving-average smoother with clamped boundary windows. */
    fun movingAverage(samples: List<TimedScalarSample>, windowSize: Int = 5): List<TimedScalarSample> {
        val clean = deduplicateTimestamps(samples)
        if (clean.isEmpty()) return emptyList()
        val radius = (windowSize.coerceIn(3, 31)) / 2
        return clean.indices.map { i ->
            val lo = max(0, i - radius)
            val hi = min(clean.lastIndex, i + radius)
            var sum = 0.0
            for (k in lo..hi) sum += clean[k].value
            TimedScalarSample(clean[i].timestampMs, sum / (hi - lo + 1).toDouble())
        }
    }

    /**
     * 5-point quadratic Savitzky-Golay filter (`[-3, 12, 17, 12, -3] / 35`) for interior samples,
     * falling back to 3-point local average at the two endpoints. Preserves quadratic peaks cleanly.
     */
    fun savitzkyGolay5(samples: List<TimedScalarSample>): List<TimedScalarSample> {
        val clean = deduplicateTimestamps(samples)
        if (clean.size < 5) return movingAverage(clean, windowSize = 3)
        val out = ArrayList<TimedScalarSample>(clean.size)
        for (i in clean.indices) {
            val smoothed = if (i < 2 || i > clean.lastIndex - 2) {
                val lo = max(0, i - 1)
                val hi = min(clean.lastIndex, i + 1)
                var sum = 0.0
                for (k in lo..hi) sum += clean[k].value
                sum / (hi - lo + 1).toDouble()
            } else {
                (
                    -3.0 * clean[i - 2].value +
                        12.0 * clean[i - 1].value +
                        17.0 * clean[i].value +
                        12.0 * clean[i + 1].value +
                        -3.0 * clean[i + 2].value
                    ) / 35.0
            }
            out += TimedScalarSample(clean[i].timestampMs, smoothed)
        }
        return out
    }

    fun applyOperation(
        operation: DerivedChannelOperation,
        primary: List<TimedScalarSample>,
        secondary: List<TimedScalarSample> = emptyList(),
    ): List<TimedScalarSample> = when (operation) {
        DerivedChannelOperation.DERIVATIVE -> derivative(primary)
        DerivedChannelOperation.INTEGRAL -> cumulativeIntegral(primary)
        DerivedChannelOperation.INTEGRAL_MAH -> cumulativeIntegral(primary).map {
            it.copy(value = ampSecondsToMilliAmpHours(it.value))
        }
        DerivedChannelOperation.CHANNEL_DELTA -> channelDelta(primary, secondary)
        DerivedChannelOperation.MOVING_AVERAGE -> movingAverage(primary, windowSize = 5)
        DerivedChannelOperation.SAVITZKY_GOLAY -> savitzkyGolay5(primary)
    }

    /**
     * Resamples irregular telemetry onto a uniform time grid over `[tStart, tEnd]` and computes
     * Hann-windowed one-sided FFT amplitude & power spectral density peaks.
     */
    fun computeSpectrum(
        samples: List<TimedScalarSample>,
        topPeakCount: Int = 3,
        fftAnalyser: ((DoubleArray, Double) -> Pair<DoubleArray, DoubleArray>)? = null,
    ): SignalSpectrumAnalysis {
        val clean = deduplicateTimestamps(samples)
        if (clean.size < 8) {
            return SignalSpectrumAnalysis(0.0, DoubleArray(0), DoubleArray(0), 0.0, emptyList())
        }
        val durationSec = (clean.last().timestampMs - clean.first().timestampMs) / 1000.0
        if (durationSec <= 1e-4) {
            return SignalSpectrumAnalysis(0.0, DoubleArray(0), DoubleArray(0), 0.0, emptyList())
        }

        val uniformCount = clean.size.coerceIn(8, 4096)
        val uniformDtMs = (clean.last().timestampMs - clean.first().timestampMs).toDouble() / (uniformCount - 1)
        val sampleRateHz = 1000.0 / uniformDtMs
        val uniformValues = resampleUniform(clean, uniformCount)

        val (frequencies, magnitudes) = if (fftAnalyser != null) {
            fftAnalyser(uniformValues, sampleRateHz)
        } else {
            computeHannFft(uniformValues, sampleRateHz)
        }
        if (frequencies.isEmpty() || magnitudes.isEmpty()) {
            return SignalSpectrumAnalysis(sampleRateHz, DoubleArray(0), DoubleArray(0), 0.0, emptyList())
        }

        val candidatePeaks = mutableListOf<SpectralPeak>()
        val df = if (frequencies.size >= 2) (frequencies[1] - frequencies[0]).coerceAtLeast(1e-6) else 1.0
        for (i in 1 until magnitudes.lastIndex) {
            val mag = magnitudes[i]
            if (mag > magnitudes[i - 1] && mag >= magnitudes[i + 1] && mag > 1e-6) {
                candidatePeaks += SpectralPeak(
                    frequencyHz = frequencies[i],
                    amplitude = mag,
                    powerDensity = (mag * mag) / (2.0 * df),
                )
            }
        }
        if (candidatePeaks.isEmpty() && magnitudes.size > 1) {
            var bestIdx = 1
            for (i in 2 until magnitudes.size) {
                if (magnitudes[i] > magnitudes[bestIdx]) bestIdx = i
            }
            if (magnitudes[bestIdx] > 0.0) {
                candidatePeaks += SpectralPeak(
                    frequencyHz = frequencies[bestIdx],
                    amplitude = magnitudes[bestIdx],
                    powerDensity = (magnitudes[bestIdx] * magnitudes[bestIdx]) / (2.0 * df),
                )
            }
        }
        val topPeaks = candidatePeaks.sortedByDescending { it.amplitude }.take(topPeakCount.coerceAtLeast(1))
        val dominant = topPeaks.firstOrNull()?.frequencyHz ?: 0.0
        return SignalSpectrumAnalysis(
            sampleRateHz = sampleRateHz,
            frequenciesHz = frequencies,
            magnitudes = magnitudes,
            dominantFrequencyHz = dominant,
            peaks = topPeaks,
        )
    }

    private fun computeHannFft(values: DoubleArray, sampleRateHz: Double): Pair<DoubleArray, DoubleArray> {
        val n = values.size
        var pow2 = 1
        while (pow2 < n) pow2 = pow2 shl 1
        val padded = DoubleArray(pow2)
        val scale = values.maxOfOrNull { abs(it) } ?: 0.0
        if (scale <= 0.0) return DoubleArray(0) to DoubleArray(0)
        var mean = 0.0
        for (v in values) mean += v / scale
        mean /= n
        var windowSum = 0.0
        for (i in values.indices) {
            val w = 0.5 * (1.0 - kotlin.math.cos(2.0 * kotlin.math.PI * i / (n - 1)))
            padded[i] = (values[i] / scale - mean) * w
            windowSum += w
        }
        val transformer = org.apache.commons.math3.transform.FastFourierTransformer(
            org.apache.commons.math3.transform.DftNormalization.STANDARD,
        )
        val complex = transformer.transform(padded, org.apache.commons.math3.transform.TransformType.FORWARD)
        val half = pow2 / 2
        val frequencies = DoubleArray(half + 1)
        val magnitudes = DoubleArray(half + 1)
        for (i in 0..half) {
            frequencies[i] = (i.toDouble() / pow2) * sampleRateHz
            val factor = if (i == 0 || i == half) 1.0 else 2.0
            magnitudes[i] = (factor * complex[i].abs() / windowSum) * scale
        }
        return frequencies to magnitudes
    }

    private fun resampleUniform(clean: List<TimedScalarSample>, count: Int): DoubleArray {
        val startMs = clean.first().timestampMs.toDouble()
        val endMs = clean.last().timestampMs.toDouble()
        val step = (endMs - startMs) / (count - 1).toDouble()
        val out = DoubleArray(count)
        var srcIdx = 0
        for (i in 0 until count) {
            val targetMs = startMs + i * step
            while (srcIdx + 1 < clean.size && clean[srcIdx + 1].timestampMs <= targetMs) {
                srcIdx++
            }
            out[i] = if (srcIdx + 1 >= clean.size) {
                clean.last().value
            } else {
                val left = clean[srcIdx]
                val right = clean[srcIdx + 1]
                val span = (right.timestampMs - left.timestampMs).toDouble()
                val alpha = if (span > 0.0) ((targetMs - left.timestampMs) / span).coerceIn(0.0, 1.0) else 0.0
                left.value + alpha * (right.value - left.value)
            }
        }
        return out
    }

    internal fun deduplicateTimestamps(samples: List<TimedScalarSample>): List<TimedScalarSample> {
        if (samples.isEmpty()) return emptyList()
        val sorted = samples.filter { it.value.isFinite() }.sortedBy { it.timestampMs }
        if (sorted.isEmpty()) return emptyList()
        val out = ArrayList<TimedScalarSample>(sorted.size)
        for (sample in sorted) {
            if (out.isNotEmpty() && out.last().timestampMs == sample.timestampMs) {
                out[out.lastIndex] = sample
            } else {
                out += sample
            }
        }
        return out
    }
}
