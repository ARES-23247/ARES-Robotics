package com.areslib.hardware.vision

import com.areslib.state.VisionMeasurement

/**
 * A thread-safe, chronological sliding window buffer that stores and sorts
 * asynchronous, out-of-order vision measurements by timestamps.
 *
 * Automatically evicts measurements older than [maxHistoryMs] relative to the latest measurement.
 */
class VisionMeasurementBuffer(val maxHistoryMs: Long = 1500L) {

    private val buffer = ArrayList<VisionMeasurement>()

    /**
     * Adds a new vision measurement to the buffer, maintaining sorted order and evicting expired entries.
     */
    fun addMeasurement(measurement: VisionMeasurement) {
        synchronized(buffer) {
            var insertIndex = buffer.size
            while (insertIndex > 0 && buffer[insertIndex - 1].timestampMs > measurement.timestampMs) {
                insertIndex--
            }
            buffer.add(insertIndex, measurement)
            evictOldEntries()
        }
    }

    /**
     * Evicts all entries older than [maxHistoryMs] relative to the newest measurement in the buffer.
     */
    private fun evictOldEntries() {
        synchronized(buffer) {
            if (buffer.isEmpty()) return
            val latest = buffer[buffer.size - 1]
            val cutoff = latest.timestampMs - maxHistoryMs
            
            var removeCount = 0
            while (removeCount < buffer.size && buffer[removeCount].timestampMs < cutoff) {
                removeCount++
            }
            
            if (removeCount > 0) {
                for (i in 0 until buffer.size - removeCount) {
                    buffer[i] = buffer[i + removeCount]
                }
                for (i in 0 until removeCount) {
                    buffer.removeAt(buffer.size - 1)
                }
            }
        }
    }

    /**
     * Retrieves all measurements recorded in the closed interval [startMs]..[endMs].
     */
    fun getMeasurementsBetween(startMs: Long, endMs: Long): List<VisionMeasurement> {
        return synchronized(buffer) {
            val result = ArrayList<VisionMeasurement>()
            for (i in 0 until buffer.size) {
                val item = buffer[i]
                if (item.timestampMs in startMs..endMs) {
                    result.add(item)
                }
            }
            result
        }
    }

    /**
     * Deletes all entries older than [timestampMs].
     */
    fun clearBefore(timestampMs: Long) {
        synchronized(buffer) {
            var removeCount = 0
            while (removeCount < buffer.size && buffer[removeCount].timestampMs < timestampMs) {
                removeCount++
            }
            
            if (removeCount > 0) {
                for (i in 0 until buffer.size - removeCount) {
                    buffer[i] = buffer[i + removeCount]
                }
                for (i in 0 until removeCount) {
                    buffer.removeAt(buffer.size - 1)
                }
            }
        }
    }

    /**
     * Returns a copy of the buffer's contents sorted in ascending chronological order.
     */
    fun getAll(): List<VisionMeasurement> {
        return synchronized(buffer) {
            ArrayList(buffer)
        }
    }

    /**
     * Clears all measurements from the buffer.
     */
    fun clear() {
        synchronized(buffer) {
            buffer.clear()
        }
    }

    /**
     * Number of active measurements currently tracked in the buffer.
     */
    val size: Int
        get() = synchronized(buffer) { buffer.size }
}
