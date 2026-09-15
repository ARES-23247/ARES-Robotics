package com.ares.analytics.service

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor

/** Allocation-free at ordinary tick sizes; exact integer arithmetic handles exceptional clock jumps. */
internal class ReplayPlaybackTime {
    private var lastClockMs = 0L
    private var fractionMs = 0.0
    var advanced = false
        private set
    var ended = false
        private set

    fun reset(nowMs: Long) {
        lastClockMs = nowMs
        fractionMs = 0.0
        advanced = false
        ended = false
    }

    fun advance(nowMs: Long, current: Long, start: Long, end: Long, speed: Double, looping: Boolean): Long {
        require(start >= 0 && end >= start && current in start..end)
        require(speed.isFinite() && speed in 0.25..8.0)
        advanced = false
        ended = end == start || (!looping && current == end)
        if (ended || nowMs <= lastClockMs) return current
        val previous = lastClockMs
        val elapsed = nowMs - previous
        lastClockMs = nowMs
        val scaled = if (elapsed >= 0) elapsed.toDouble() * speed + fractionMs else Double.POSITIVE_INFINITY
        val duration = end - start
        val remaining = end - current
        val whole: Long
        if (scaled <= 4_503_599_627_370_496.0) {
            whole = floor(scaled).toLong()
            fractionMs = scaled - whole
        } else {
            // Constructing from Double preserves the actual binary floating-point rate/fraction.
            val exact = BigDecimal.valueOf(nowMs).subtract(BigDecimal.valueOf(previous))
                .multiply(BigDecimal(speed)).add(BigDecimal(fractionMs))
            val integral = exact.setScale(0, RoundingMode.FLOOR)
            fractionMs = exact.subtract(integral).toDouble().coerceAtMost(Math.nextDown(1.0))
            advanced = integral.signum() > 0
            if (integral >= BigDecimal.valueOf(remaining)) {
                if (!looping) { ended = true; fractionMs = 0.0; return end }
                return start + integral.subtract(BigDecimal.valueOf(remaining))
                    .remainder(BigDecimal.valueOf(duration)).longValueExact()
            }
            whole = integral.longValueExact()
        }
        advanced = whole > 0
        if (!advanced) return current
        if (whole >= remaining) {
            if (!looping) { ended = true; fractionMs = 0.0; return end }
            return start + (whole - remaining) % duration
        }
        return current + whole
    }
}
