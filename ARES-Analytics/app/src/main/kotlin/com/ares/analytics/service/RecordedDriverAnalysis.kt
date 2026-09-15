package com.ares.analytics.service

import com.ares.analytics.service.db.AnalysisTelemetryInput
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.models.AnalysisDiagnostic
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/** Consumes complete, latest-update, source-time-ordered inputs from the analysis repository. */
internal object RecordedDriverAnalysis {
    val motionKeys = listOf("Drive/ChassisSpeeds/vx", "Drive/ChassisSpeeds/vy", "Drive/ChassisSpeeds/omega")

    fun jitter(input: AnalysisTelemetryInput, keys: List<String>, fft: (DoubleArray, Double) -> FftResult): DriverProfileAnalysisResult {
        val topics = input.frames.groupBy { it.key }
        val axes = keys.distinct().map { key ->
            if (input.complete) spectrum(key, topics[key].orEmpty(), fft)
            else DriverAxisObservation(key, input.status, 0)
        }
        val usable = axes.filter { it.status == "complete" }
        val detected = usable.filter { it.thresholdCrossed }
        val strongest = (detected.ifEmpty { usable }).maxByOrNull { it.bandAmplitude ?: 0.0 }
        val status = when {
            !input.complete -> input.status
            usable.isEmpty() -> "unavailable"
            usable.size < axes.size -> "partial"
            else -> "complete"
        }
        val message = if (usable.isEmpty()) {
            "Insufficient usable gamepad telemetry: " + axes.joinToString { "${it.sourceKey}: ${it.status}" }
        } else {
            val observed = if (detected.isNotEmpty()) {
                "An 8-12 Hz component crossed the configured amplitude and noise-floor thresholds near " +
                    "${format(strongest!!.peakFrequencyHz!!)} Hz in ${strongest.sourceKey}."
            } else "No configured 8-12 Hz threshold was crossed in the analyzed axes."
            "$observed Analyzed ${usable.size} of ${axes.size} requested axes after source-time linear resampling. " +
                "This does not identify a cause or response-curve tuning values."
        }
        return DriverProfileAnalysisResult(detected.isNotEmpty(), strongest?.peakFrequencyHz ?: 0.0,
            null, null, message, axes, status)
    }

    private fun spectrum(key: String, frames: List<TelemetryFrame>, fft: (DoubleArray, Double) -> FftResult): DriverAxisObservation {
        fun unavailable(reason: String) = DriverAxisObservation(key, reason, frames.size)
        if (frames.size < 64) return unavailable("insufficient_samples")
        // An invalid update is a barrier, never a point to drop before interpolation.
        if (frames.any { it.stringValue != null || !it.value.isFinite() || abs(it.value) > 1.0 }) return unavailable("invalid_normalized_input")
        val spanUs = frames.last().timestampUs - frames.first().timestampUs
        if (spanUs < 1_000_000L) return unavailable("insufficient_duration")
        val periodUs = spanUs.toDouble() / (frames.size - 1)
        val sampleRate = 1_000_000.0 / periodUs
        if (sampleRate <= 24.0) return unavailable("undersampled")
        for (i in 1 until frames.size) {
            val delta = frames[i].timestampUs - frames[i - 1].timestampUs
            if (delta <= 0 || abs(delta - periodUs) > periodUs * 0.5 || delta >= 1_000_000.0 / 24.0) {
                return unavailable("irregular_or_gapped")
            }
        }
        // Use elapsed source times, never absolute epoch doubles or rounded milliseconds.
        // FFT describes this explicitly interpolated signal; it is not an anti-alias guarantee.
        val firstUs = frames.first().timestampUs
        var cursor = 0
        val uniform = DoubleArray(frames.size) { index ->
            val target = periodUs * index
            while (cursor + 1 < frames.lastIndex && frames[cursor + 1].timestampUs - firstUs < target) cursor++
            val left = frames[cursor]; val right = frames[cursor + 1]
            val weight = ((target - (left.timestampUs - firstUs)) / (right.timestampUs - left.timestampUs)).coerceIn(0.0, 1.0)
            left.value * (1.0 - weight) + right.value * weight
        }
        val result = fft(uniform, sampleRate)
        if (result.frequencies.size < 2 || result.magnitudes.size != result.frequencies.size ||
            result.frequencies.any { !it.isFinite() || it < 0.0 } || result.magnitudes.any { !it.isFinite() || it < 0.0 }) {
            return unavailable("spectrum_unavailable")
        }
        var amplitude = 0.0
        var frequency = 0.0
        for (i in 1 until result.frequencies.size) {
            if (result.frequencies[i] in 8.0..12.0 && result.magnitudes[i] > amplitude) {
                amplitude = result.magnitudes[i]; frequency = result.frequencies[i]
            }
        }
        val magnitudes = result.magnitudes.copyOfRange(1, result.magnitudes.size).apply { sort() }
        val floor = magnitudes[magnitudes.size / 2]
        return DriverAxisObservation(key, "complete", frames.size, spanUs / 1e6,
            frequency, amplitude, amplitude >= 0.02 && amplitude / 3.0 >= floor)
    }

    fun coaching(input: AnalysisTelemetryInput): DriverCoachingReport {
        // The source union includes missing/invalid slots. They break adjacent-motion comparisons
        // and remain in the coverage denominator rather than making dropped records disappear.
        val slots = ArrayList<MotionSlot>()
        for (frame in input.frames) {
            if (slots.lastOrNull()?.timeUs != frame.timestampUs) slots.add(MotionSlot(frame.timestampUs))
            val slot = slots.last()
            val value = frame.value.takeIf { frame.stringValue == null && it.isFinite() } ?: Double.NaN
            when (frame.key) {
                motionKeys[0] -> slot.vx = value
                motionKeys[1] -> slot.vy = value
                motionKeys[2] -> slot.omega = value
            }
        }
        var validCount = 0
        var combinedCount = 0
        var reversals = 0
        var observedUs = 0L
        var previous: MotionSlot? = null
        for (slot in slots) {
            val speed = hypot(slot.vx, slot.vy)
            slot.speed = speed
            if (speed.isFinite() && slot.omega.isFinite()) {
                validCount++
                if (speed >= 0.8 && abs(slot.omega) >= 1.5) combinedCount++
                val before = previous
                if (before != null && before.speed.isFinite() && before.omega.isFinite()) {
                    val delta = slot.timeUs - before.timeUs
                    if (delta in 1..200_000L) {
                        observedUs += delta
                        if (speed >= 0.2 && before.speed >= 0.2) {
                            val cosine = (before.vx / before.speed) * (slot.vx / speed) +
                                (before.vy / before.speed) * (slot.vy / speed)
                            if (cosine <= -0.5) reversals++
                        }
                    }
                }
            }
            previous = slot
        }
        val spanUs = if (slots.isEmpty()) 0L else slots.last().timeUs - slots.first().timeUs
        val observedSeconds = observedUs / 1e6
        val coverage = if (slots.isEmpty()) 0.0 else validCount.toDouble() / slots.size
        val timeCoverage = if (spanUs <= 0L) 0.0 else observedUs.toDouble() / spanUs
        val enough = input.complete && validCount >= 30 && observedUs >= 500_000L
        val combined = if (validCount >= 30) combinedCount.toDouble() / validCount else null
        val rate = if (enough) reversals * 60.0 / observedSeconds else null
        val confidence = when {
            !enough || coverage < 0.6 || timeCoverage < 0.6 -> DriverReviewConfidence.INSUFFICIENT
            validCount >= 200 && observedSeconds >= 10.0 && coverage >= 0.9 && timeCoverage >= 0.9 -> DriverReviewConfidence.STRONG
            else -> DriverReviewConfidence.LIMITED
        }
        val observations = buildList {
            if (!enough) add(DriverMotionObservation("More continuous motion evidence is needed",
                "$validCount complete samples cover ${format(observedSeconds)} observed seconds; input status ${input.status}.",
                "Record synchronized vx, vy and omega with valid updates no more than 200 ms apart."))
            if (combined != null && combined >= 0.15) add(DriverMotionObservation("Frequent combined translation and rotation",
                "${format(combined * 100)}% of complete samples exceeded 0.8 m/s and 1.5 rad/s; this is a sample fraction, not elapsed-time occupancy.",
                "Compare the timestamps with driver video and wheel/current/voltage evidence; chassis motion alone does not prove slip or wasted energy."))
            if (rate != null && rate >= 40.0) add(DriverMotionObservation("Frequent large direction changes",
                "${format(rate)} changes per observed minute turned adjacent moving vectors by at least 120 degrees; stops, invalid updates and gaps break comparisons.",
                "Review these intervals with the driver before choosing an intentional response-curve experiment."))
            if (isEmpty()) add(DriverMotionObservation("No configured motion-pattern threshold was crossed",
                "Complete samples and observed intervals stayed below this review's thresholds.",
                "Use driver video for context. This is not a score or proof of safe or efficient driving."))
        }
        return DriverCoachingReport(validCount, slots.size, spanUs / 1e6, coverage, combined, rate,
            confidence, observations, observedSeconds, timeCoverage, input.status)
    }

    private class MotionSlot(val timeUs: Long, var vx: Double = Double.NaN, var vy: Double = Double.NaN,
        var omega: Double = Double.NaN, var speed: Double = Double.NaN)
    private fun format(value: Double) = String.format(Locale.ROOT, "%.2f", value)
}

/** Missing axes cannot establish a negative result for all requested controls. */
internal fun recordedDriverDiagnostics(sessionId: String, result: DriverProfileAnalysisResult): List<AnalysisDiagnostic> = buildList {
    fun number(key: String, value: Double) { add(AnalysisDiagnostic(sessionId, "Diagnostics/Driver/$key", value)) }
    fun text(key: String, value: String) { add(AnalysisDiagnostic(sessionId, "Diagnostics/Driver/$key", 0.0, value)) }
    val usable = result.axes.filter { it.status == "complete" }
    text("InputStatus", result.inputStatus)
    number("RequestedAxes", result.axes.size.toDouble())
    number("AnalyzedAxes", usable.size.toDouble())
    number("ObservedSamples", usable.sumOf { it.sampleCount }.toDouble())
    for ((index, axis) in result.axes.withIndex()) {
        text("Axes/$index/Source", axis.sourceKey); text("Axes/$index/Status", axis.status)
        number("Axes/$index/SourceSamples", axis.sampleCount.toDouble())
    }
    if (usable.isNotEmpty()) {
        if (result.hasJitter || usable.size == result.axes.size) number("JitterPresent", if (result.hasJitter) 1.0 else 0.0)
        if (result.peakFrequencyHz.isFinite() && result.peakFrequencyHz in 8.0..12.0) number("PeakJitterFrequency", result.peakFrequencyHz)
        text("Interpretation", "Recorded normalized joystick components after source-time linear resampling; no cause, safety or tuning identification.")
    }
}
