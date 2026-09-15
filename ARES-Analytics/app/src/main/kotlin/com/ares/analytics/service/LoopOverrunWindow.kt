package com.ares.analytics.service

import kotlin.math.max

/**
 * Fixed diagnostic policy: three samples >25 ms within an inclusive source-time second,
 * or a current sample >=100 ms. Owns exactly three primitive sample slots.
 *
 * Three newest overruns suffice to decide the count threshold. At the start of an occurrence
 * they also contain all qualifying evidence; any fourth overrun means it was already active.
 * Retaining the occurrence peak separately preserves older maxima while that fault stays active.
 * [peakMs] is occurrence evidence, not a separately queryable rolling-window maximum.
 * The caller supplies distinct samples in source order; equal microseconds may have distinct orders.
 */
internal class LoopOverrunWindow {
    private val times = LongArray(REQUIRED_OVERRUNS)
    private val periods = DoubleArray(REQUIRED_OVERRUNS)
    private var head = 0
    private var count = 0
    private var lastTimeUs = -1L
    val retainedSampleCount: Int get() = count
    var isSlow: Boolean = false
        private set
    var peakMs: Double = Double.NaN
        private set

    /** Invalid/misordered input leaves the last observed state unchanged and returns false. */
    fun accept(timestampUs: Long, periodMs: Double): Boolean {
        if (timestampUs < 0L || timestampUs < lastTimeUs || !periodMs.isFinite() ||
            periodMs <= 0.0 || !(1_000.0 / periodMs).isFinite()) return false
        lastTimeUs = timestampUs
        while (count > 0 && timestampUs - times[head] > WINDOW_US) {
            head = (head + 1) % REQUIRED_OVERRUNS
            count--
        }
        if (periodMs > MODERATE_THRESHOLD_MS) {
            if (count == REQUIRED_OVERRUNS) { head = (head + 1) % REQUIRED_OVERRUNS; count-- }
            val index = (head + count) % REQUIRED_OVERRUNS
            times[index] = timestampUs
            periods[index] = periodMs
            count++
        }
        val nextSlow = periodMs >= SEVERE_THRESHOLD_MS || count == REQUIRED_OVERRUNS
        var nextPeak = periodMs
        if (nextSlow) {
            for (offset in 0 until count) nextPeak = max(nextPeak, periods[(head + offset) % REQUIRED_OVERRUNS])
            if (isSlow) nextPeak = max(nextPeak, peakMs)
        }
        isSlow = nextSlow
        peakMs = nextPeak
        return true
    }
    companion object {
        const val MODERATE_THRESHOLD_MS = 25.0
        private const val SEVERE_THRESHOLD_MS = 100.0
        private const val REQUIRED_OVERRUNS = 3
        private const val WINDOW_US = 1_000_000L
    }
}
