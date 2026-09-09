package com.ares.analytics.service

import kotlin.math.max

/** Exact retained sample mean; overflow of the bounded window is explicitly unknown until aged out. */
internal class MotorCurrentWindow(private val capacity: Int = 512) {
    init { require(capacity in 1..4096 && (capacity and (capacity - 1)) == 0) }
    private val times = LongArray(capacity)
    private val counts = IntArray(capacity * 2)
    private val means = DoubleArray(capacity * 2)
    private var head = 0
    private var size = 0
    private var lastSampleUs = -1L
    private var nowUs = -1L
    private var lastDiscardedUs = -1L
    val retainedSampleCount: Int get() = size

    fun clear() {
        counts.fill(0); means.fill(0.0)
        head = 0; size = 0; lastSampleUs = -1L; nowUs = -1L; lastDiscardedUs = -1L
    }

    fun accept(timestampUs: Long, amps: Double): Boolean {
        if (timestampUs < 0 || timestampUs < lastSampleUs || !amps.isFinite() || amps < 0.0) return false
        lastSampleUs = timestampUs
        advance(max(nowUs, timestampUs))
        if (nowUs - timestampUs > WINDOW_US) return true
        if (size == capacity) {
            lastDiscardedUs = times[head]
            head = (head + 1) % capacity
            size-- // the following write replaces this same leaf
        }
        val index = (head + size) % capacity
        times[index] = timestampUs
        writeLeaf(index, amps, 1)
        size++
        return true
    }

    fun meanAt(timestampUs: Long): Double {
        if (timestampUs < 0L || timestampUs < nowUs) return Double.NaN
        advance(timestampUs)
        if (size == 0 || (lastDiscardedUs >= 0L && nowUs - lastDiscardedUs <= WINDOW_US)) return Double.NaN
        return means[1]
    }

    private fun advance(timestampUs: Long) {
        nowUs = timestampUs
        while (size > 0 && nowUs - times[head] > WINDOW_US) {
            writeLeaf(head, 0.0, 0)
            head = (head + 1) % capacity
            size--
        }
    }

    private fun writeLeaf(index: Int, value: Double, count: Int) {
        var node = capacity + index
        counts[node] = count; means[node] = value
        node /= 2
        while (node > 0) {
            val left = node * 2; val right = left + 1
            val total = counts[left] + counts[right]
            counts[node] = total
            means[node] = when {
                counts[left] == 0 -> means[right]
                counts[right] == 0 -> means[left]
                // Nonnegative means make this difference finite; no overflowing sum or
                // subtractive rolling-total drift when a large sample leaves the window.
                else -> means[left] + (means[right] - means[left]) * (counts[right].toDouble() / total)
            }
            node /= 2
        }
    }

    companion object { const val WINDOW_US = 1_000_000L }
}
