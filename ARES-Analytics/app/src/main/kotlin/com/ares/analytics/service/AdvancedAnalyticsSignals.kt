package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Owns a sorted numeric snapshot; the last ordered update wins for a duplicate source time. */
internal fun numericAnalyticsSeries(frames: List<TelemetryFrame>, sessionId: String, key: String): List<TelemetryFrame> {
    val ordered = frames.filter {
        it.sessionId == sessionId && it.key.trimStart('/') == key.trimStart('/')
    }.sortedWith(compareBy<TelemetryFrame> { it.timestampUs }.thenBy { it.sampleOrder })
    val result = ArrayList<TelemetryFrame>(ordered.size)
    for (frame in ordered) {
        if (result.lastOrNull()?.timestampUs == frame.timestampUs) result[result.lastIndex] = frame
        else result.add(frame)
    }
    // An invalid latest update supersedes older numeric data at the same source time.
    result.removeAll { it.stringValue != null || !it.value.isFinite() }
    return result
}

/** Greedy nearest unused pairs, with earlier ties. Vectors require exact time; correlations may allow skew. */
internal fun alignAnalyticsSeries(
    left: List<TelemetryFrame>,
    right: List<TelemetryFrame>,
    maximumSkewUs: Long = 0L,
): List<Pair<TelemetryFrame, TelemetryFrame>> {
    require(maximumSkewUs >= 0L)
    val result = ArrayList<Pair<TelemetryFrame, TelemetryFrame>>(minOf(left.size, right.size))
    var index = 0
    for (frame in left) {
        if (index == right.size) break
        while (index + 1 < right.size && right[index + 1].timestampUs <= frame.timestampUs) index++
        var selected = index
        if (index + 1 < right.size && abs(right[index + 1].timestampUs - frame.timestampUs) <
            abs(right[index].timestampUs - frame.timestampUs)) selected++
        if (abs(right[selected].timestampUs - frame.timestampUs) <= maximumSkewUs) {
            result.add(frame to right[selected])
            index = selected + 1
        }
    }
    return result
}

/** Pearson r is invariant to independent offsets/scales; normalize before accumulating second moments. */
internal fun analyticsCorrelation(samples: List<Pair<TelemetryFrame, TelemetryFrame>>): Double? {
    if (samples.size < 2) return null
    var minX = Double.POSITIVE_INFINITY; var maxX = Double.NEGATIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
    for ((x, y) in samples) {
        minX = minOf(minX, x.value); maxX = maxOf(maxX, x.value)
        minY = minOf(minY, y.value); maxY = maxOf(maxY, y.value)
    }
    // Half sums avoid overflow for opposite extremes and retain small deviations from a large offset.
    val centerX = minX / 2.0 + maxX / 2.0
    val centerY = minY / 2.0 + maxY / 2.0
    val scaleX = max(abs(minX - centerX), abs(maxX - centerX))
    val scaleY = max(abs(minY - centerY), abs(maxY - centerY))
    if (scaleX == 0.0 || scaleY == 0.0) return null
    var meanX = 0.0; var meanY = 0.0
    var xx = 0.0; var yy = 0.0; var xy = 0.0
    var count = 0
    for ((left, right) in samples) {
        val x = (left.value - centerX) / scaleX
        val y = (right.value - centerY) / scaleY
        count++
        val dx = x - meanX; val dy = y - meanY
        meanX += dx / count; meanY += dy / count
        xx += dx * (x - meanX); yy += dy * (y - meanY); xy += dx * (y - meanY)
    }
    val denominator = sqrt(xx) * sqrt(yy)
    if (!denominator.isFinite() || denominator <= 0.0) return null
    return (xy / denominator).takeIf { it.isFinite() }?.coerceIn(-1.0, 1.0)
}
