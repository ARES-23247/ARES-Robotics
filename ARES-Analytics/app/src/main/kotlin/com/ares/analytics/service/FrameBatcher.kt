package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame

/**
 * Frame-count-bounded buffer for sequential bulk log imports.
 *
 * Flushes to DuckDB every [batchSize] frames (default 50,000), and computes timestamp bounds
 * incrementally. The limit counts frames, not payload bytes; decoders must separately bound
 * individual records. This class is not thread-safe: callers must await each add/flush in order.
 *
 * ### Performance Guarantees & Memory Footprint:
 * Auto-flushes when `buffer.size >= batchSize`. Successful sequential ingestion keeps at most
 * [batchSize] pending frame references; failed flushes retain pending frames for retry.
 *
 * @param databaseService Target database service for executing batch insertions.
 * @param batchSize Maximum frame buffer capacity before executing an automatic batch flush.
 * @param keyTransform Optional lambda transformation applied to frame topic keys before insertion (e.g. key normalization).
 *
 * @see DatabaseService
 * @see com.ares.analytics.service.log.BaseLogDecoder
 */
class FrameBatcher(
    private val databaseService: DatabaseService,
    private val batchSize: Int = 50_000,
    private val keyTransform: ((String) -> String)? = null
) {
    init {
        require(batchSize > 0) { "batchSize must be positive" }
    }

    private val buffer = mutableListOf<TelemetryFrame>()

    /** Earliest timestamp observed across all frames added to this batcher. */
    var minTimestamp: Long = Long.MAX_VALUE
        private set

    /** Latest timestamp observed across all frames added to this batcher. */
    var maxTimestamp: Long = Long.MIN_VALUE
        private set

    /** Total number of frames that have been flushed + those still in the buffer. */
    val frameCount: Int get() = totalFlushed + buffer.size

    private var totalFlushed = 0

    /**
     * Adds a single frame to the internal buffer. If the buffer reaches
     * [batchSize], the batch is automatically flushed to the database.
     */
    suspend fun add(frame: TelemetryFrame) {
        val key = keyTransform?.invoke(frame.key) ?: frame.key
        val finalFrame = if (key == frame.key) frame else frame.copy(key = key)
        buffer.add(finalFrame)
        if (frame.timestampMs < minTimestamp) minTimestamp = frame.timestampMs
        if (frame.timestampMs > maxTimestamp) maxTimestamp = frame.timestampMs

        if (buffer.size >= batchSize) {
            flush()
        }
    }

    /**
     * Flushes any remaining frames in the buffer to the database.
     * Must be called after parsing completes to ensure no frames are lost.
     */
    suspend fun flush() {
        if (buffer.isNotEmpty()) {
            // The database consumes the list before returning; sequential callers cannot mutate
            // it during this suspension. Preserve it unchanged if insertion fails for retry.
            databaseService.insertTelemetryFrames(buffer)
            totalFlushed += buffer.size
            buffer.clear()
        }
    }
}
