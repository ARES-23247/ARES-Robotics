package com.ares.analytics.service.analysis

import kotlin.math.PI
import kotlin.math.abs

enum class OverlayAlignmentMode(val label: String) {
    FIRST_MOTION_OR_ENABLE("Motion Trigger"),
    SESSION_START("Session Start (t0)"),
    MANUAL_OFFSET_MS("Manual Offset"),
}

data class TimedPoseSample(
    val timestampMs: Long,
    val x: Double,
    val y: Double,
    val headingRadians: Double,
)

data class RunOverlayAlignment(
    val mode: OverlayAlignmentMode,
    val primaryAnchorMs: Long,
    val compareAnchorMs: Long,
    val manualOffsetMs: Long = 0L,
) {
    /** Maps a timestamp on the Primary run timeline onto the aligned Compare run timestamp. */
    fun mapPrimaryToCompareTimestamp(primaryTimestampMs: Long): Long {
        val elapsedFromPrimaryAnchor = primaryTimestampMs - primaryAnchorMs
        return compareAnchorMs + elapsedFromPrimaryAnchor + manualOffsetMs
    }

    /** Maps a Compare run timestamp onto the Primary run timeline so both curves share one X axis. */
    fun mapCompareToPrimaryTimestamp(compareTimestampMs: Long): Long {
        val elapsedFromCompareAnchor = compareTimestampMs - compareAnchorMs - manualOffsetMs
        return primaryAnchorMs + elapsedFromCompareAnchor
    }
}

/**
 * Aligns Primary (`Run A`) and Compare (`Run B`) sessions by first motion/enable trigger,
 * raw session `t0`, or manual millisecond offset, and interpolates 2D field ghost poses across `±π`.
 */
object RunOverlayAlignmentEngine {

    private const val MOTION_SPEED_THRESHOLD_MPS = 0.08

    fun computeAlignment(
        mode: OverlayAlignmentMode,
        primarySessionStartMs: Long,
        compareSessionStartMs: Long,
        primaryMotionSamples: List<TimedScalarSample> = emptyList(),
        compareMotionSamples: List<TimedScalarSample> = emptyList(),
        manualOffsetMs: Long = 0L,
    ): RunOverlayAlignment {
        val primaryAnchor = when (mode) {
            OverlayAlignmentMode.FIRST_MOTION_OR_ENABLE ->
                detectFirstMotionTimestamp(primaryMotionSamples) ?: primarySessionStartMs
            OverlayAlignmentMode.SESSION_START,
            OverlayAlignmentMode.MANUAL_OFFSET_MS -> primarySessionStartMs
        }
        val compareAnchor = when (mode) {
            OverlayAlignmentMode.FIRST_MOTION_OR_ENABLE ->
                detectFirstMotionTimestamp(compareMotionSamples) ?: compareSessionStartMs
            OverlayAlignmentMode.SESSION_START,
            OverlayAlignmentMode.MANUAL_OFFSET_MS -> compareSessionStartMs
        }
        val appliedOffset = if (mode == OverlayAlignmentMode.MANUAL_OFFSET_MS) manualOffsetMs else 0L
        return RunOverlayAlignment(
            mode = mode,
            primaryAnchorMs = primaryAnchor,
            compareAnchorMs = compareAnchor,
            manualOffsetMs = appliedOffset,
        )
    }

    fun detectFirstMotionTimestamp(
        speedOrVoltageSamples: List<TimedScalarSample>,
        threshold: Double = MOTION_SPEED_THRESHOLD_MPS,
    ): Long? = speedOrVoltageSamples
        .asSequence()
        .filter { it.value.isFinite() }
        .sortedBy { it.timestampMs }
        .firstOrNull { abs(it.value) >= threshold }
        ?.timestampMs

    /** Projects Compare run scalar samples onto the Primary run's timestamp axis. */
    fun projectCompareSeriesToPrimaryTimeline(
        compareSamples: List<TimedScalarSample>,
        alignment: RunOverlayAlignment,
    ): List<TimedScalarSample> =
        SignalMathEngine.deduplicateTimestamps(compareSamples).map { sample ->
            TimedScalarSample(
                timestampMs = alignment.mapCompareToPrimaryTimestamp(sample.timestampMs),
                value = sample.value,
            )
        }

    /**
     * Interpolates `(x, y, headingRadians)` at `[targetTimestampMs]` using shortest-arc wrap across `±π`
     * so CCW-positive robot headings never spin backwards through `0 rad` when crossing `π`.
     */
    fun interpolatePoseAt(
        trace: List<TimedPoseSample>,
        targetTimestampMs: Long,
    ): TimedPoseSample? {
        if (trace.isEmpty()) return null
        if (targetTimestampMs <= trace.first().timestampMs) return trace.first()
        if (targetTimestampMs >= trace.last().timestampMs) return trace.last()

        var lo = 0
        var hi = trace.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val midTs = trace[mid].timestampMs
            when {
                midTs < targetTimestampMs -> lo = mid + 1
                midTs > targetTimestampMs -> hi = mid - 1
                else -> return trace[mid]
            }
        }
        val left = trace[hi.coerceAtLeast(0)]
        val right = trace[lo.coerceAtMost(trace.lastIndex)]
        val span = (right.timestampMs - left.timestampMs).toDouble()
        val alpha = if (span > 0.0) ((targetTimestampMs - left.timestampMs) / span).coerceIn(0.0, 1.0) else 0.0
        val x = left.x + alpha * (right.x - left.x)
        val y = left.y + alpha * (right.y - left.y)
        val dTheta = wrapRadians(right.headingRadians - left.headingRadians)
        val heading = wrapRadians(left.headingRadians + alpha * dTheta)
        return TimedPoseSample(targetTimestampMs, x, y, heading)
    }

    internal fun wrapRadians(angle: Double): Double {
        var wrapped = (angle + PI) % (2.0 * PI)
        if (wrapped < 0.0) wrapped += 2.0 * PI
        return wrapped - PI
    }
}
