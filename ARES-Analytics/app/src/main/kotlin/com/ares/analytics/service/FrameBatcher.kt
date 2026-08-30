package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame

/**
 * High-performance bounded channel buffer for accumulating [TelemetryFrame] objects during bulk log imports.
 *
 * Prevents JVM heap exhaustion by auto-flushing frame buffers to DuckDB in constant-sized memory chunks
 * (default 50,000 frames). Computes session bounding timestamps ($t_{\text{min}}, t_{\text{max}}$) incrementally,
 * eliminating the need to store full session frame arrays in memory.
 *
 * ### Performance Guarantees & Memory Footprint:
 * Maintains $O(1)$ amortized memory allocation bounds per imported frame. Auto-flushes when `buffer.size >= batchSize`.
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
        if (frame.timestampMs < minTimestamp) minTimestamp = frame.timestampMs
        if (frame.timestampMs > maxTimestamp) maxTimestamp = frame.timestampMs
        val finalFrame = if (keyTransform != null) {
            frame.copy(key = keyTransform.invoke(frame.key))
        } else {
            frame
        }
        buffer.add(finalFrame)

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
            databaseService.insertTelemetryFrames(buffer.toList())
            totalFlushed += buffer.size
            buffer.clear()
        }
    }
}
