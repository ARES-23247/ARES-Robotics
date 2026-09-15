package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import kotlin.math.abs
import kotlin.math.sign

/** Recorded source units and polarity are retained; callers must select compatible physical channels. */
internal object RecordedSysIdInputs {
    const val MAX_ALIGNMENT_US = 50_000L
    const val MAX_DERIVATIVE_GAP_US = 50_000L
    private const val REVERSAL_WINDOW_US = 50_000L

    fun align(
        voltages: List<TelemetryFrame>, velocities: List<TelemetryFrame>,
        accelerations: List<TelemetryFrame>? = null,
    ): List<AlignedDataRow> {
        val voltage = latestOrdered(voltages)
        val velocity = latestOrdered(velocities)
        val acceleration = accelerations?.let(::latestOrdered)
        if (voltage.isEmpty() || velocity.isEmpty() || acceleration?.isEmpty() == true) return emptyList()
        val identities = listOfNotNull(voltage.firstOrNull(), velocity.firstOrNull(), acceleration?.firstOrNull())
        require(identities.map { it.sessionId }.distinct().size == 1) { "SysId channels must belong to one session" }
        require(identities.map { it.key.trimStart('/') }.distinct().size == identities.size) { "SysId channels must be distinct" }

        val reversals = ArrayList<Long>()
        var lastSign = 0.0
        var previous: TelemetryFrame? = null
        for (frame in velocity) {
            if (!valid(frame) || previous?.let { frame.timestampUs - it.timestampUs > MAX_DERIVATIVE_GAP_US } == true) lastSign = 0.0
            if (valid(frame)) {
                val currentSign = sign(frame.value)
                if (currentSign != 0.0 && lastSign != 0.0 && currentSign != lastSign) reversals.add(frame.timestampUs)
                if (currentSign != 0.0) lastSign = currentSign
            }
            previous = frame
        }
        val voltageCursor = Nearest(voltage)
        val accelerationCursor = acceleration?.let(::Nearest)
        val rows = ArrayList<AlignedDataRow>(velocity.size)
        var reversalIndex = 0
        for ((index, frame) in velocity.withIndex()) {
            val t = frame.timestampUs
            while (reversalIndex < reversals.size && reversals[reversalIndex] < t - REVERSAL_WINDOW_US) reversalIndex++
            if (!valid(frame) || (reversalIndex < reversals.size && reversals[reversalIndex] <= t + REVERSAL_WINDOW_US)) continue
            val volts = voltageCursor.at(t)?.value ?: continue
            val accel = if (accelerationCursor != null) {
                accelerationCursor.at(t)?.value ?: continue
            } else {
                val before = velocity.getOrNull(index - 1) ?: continue
                val gap = t - before.timestampUs
                if (!valid(before) || gap !in 1..MAX_DERIVATIVE_GAP_US) continue
                val derivative = (frame.value - before.value) / (gap / 1_000_000.0)
                if (!derivative.isFinite()) continue
                derivative
            }
            // Only the legacy display anchor is milliseconds; matching and derivatives use microseconds.
            rows.add(AlignedDataRow(frame.timestampMs, volts, frame.value, accel))
        }
        return rows
    }

    private fun latestOrdered(frames: List<TelemetryFrame>): List<TelemetryFrame> {
        val latest = HashMap<Long, TelemetryFrame>()
        val source = frames.firstOrNull()
        for (frame in frames) {
            require(frame.sessionId == source?.sessionId && frame.key.trimStart('/') == source.key.trimStart('/')) { "Select one SysId source per channel" }
            val prior = latest[frame.timestampUs]
            if (prior == null || frame.sampleOrder >= prior.sampleOrder) latest[frame.timestampUs] = frame
        }
        return latest.values.sortedBy { it.timestampUs }
    }

    private fun valid(frame: TelemetryFrame) = frame.stringValue == null && frame.value.isFinite()

    /** Monotonic cursors make matching linear after ordering. Invalid nearest updates remain barriers. */
    private class Nearest(private val frames: List<TelemetryFrame>) {
        private var index = 0
        fun at(t: Long): TelemetryFrame? {
            while (index < frames.lastIndex && abs(frames[index + 1].timestampUs - t) <= abs(frames[index].timestampUs - t)) index++
            return frames[index].takeIf { abs(it.timestampUs - t) <= MAX_ALIGNMENT_US && valid(it) }
        }
    }
}
