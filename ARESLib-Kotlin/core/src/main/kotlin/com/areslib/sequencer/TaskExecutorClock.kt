package com.areslib.sequencer

/** Checked millisecond arithmetic for one executor; reset admits a fresh idle clock epoch. */
internal class TaskExecutorClock {
    var activeTaskStartTimeMs = 0L
    var suspendedAtMs = 0L
    var hasTimestamp = false
        private set
    private var lastTimestampMs = 0L

    fun observeTimestamp(timestampMs: Long) {
        require(!hasTimestamp || timestampMs >= lastTimestampMs) { "Task executor clock moved backward" }
        lastTimestampMs = timestampMs
        hasTimestamp = true
    }

    fun elapsedSince(startMs: Long, timestampMs: Long): Long {
        require(timestampMs >= startMs) { "Task executor clock moved backward" }
        val elapsed = timestampMs - startMs
        require(elapsed >= 0L) { "Task executor elapsed time exceeds Long.MAX_VALUE" }
        return elapsed
    }

    fun reset() { hasTimestamp = false }
}
