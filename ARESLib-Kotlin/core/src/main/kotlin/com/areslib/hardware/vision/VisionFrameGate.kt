package com.areslib.hardware.vision

import com.areslib.state.VisionMeasurement

/**
 * Per-tracker freshness and duplicate history in the shared RobotClock millisecond domain.
 *
 * Call [beginUpdate] once per poll before [accept]. Capture timestamps must be positive;
 * source-native microsecond clocks are deliberately ignored. Both age limits are inclusive.
 * Sources are stable configured camera identities, including the unnamed empty identity.
 * Storage grows only when a new source is first encountered and is reused thereafter. There
 * is no eight-camera eviction limit. This mutable workspace belongs to one robot-loop owner.
 */
class VisionFrameGate(private val maxAgeMs: Long, private val maxFutureSkewMs: Long = 50L) {
    init {
        require(maxAgeMs >= 0L) { "Maximum vision age must be nonnegative" }
        require(maxFutureSkewMs >= 0L) { "Maximum future skew must be nonnegative" }
    }

    private var sourceIds = arrayOfNulls<String>(8)
    private var frameIds = LongArray(8)
    private var timestampsMs = LongArray(8)
    private var sourceCount = 0
    private var hasUpdate = false
    private var updateTimestampMs = 0L

    /**
     * Returns true only for a forward/equal poll within [maxAgeMs] of the previous poll.
     * A rewind clears frame history for a new replay epoch; a forward gap preserves duplicate
     * history but returns false so the caller can discard recovery/dwell evidence.
     */
    fun beginUpdate(timestampMs: Long): Boolean {
        val elapsed = timestampMs - updateTimestampMs
        val continuous = hasUpdate && timestampMs >= updateTimestampMs &&
            elapsed >= 0L && elapsed <= maxAgeMs
        if (hasUpdate && timestampMs < updateTimestampMs) clear()
        updateTimestampMs = timestampMs
        hasUpdate = true
        return continuous
    }

    /** Checks age without consuming an identity; subtraction overflow rejects the timestamp. */
    fun isRecent(timestampMs: Long): Boolean {
        if (!hasUpdate || timestampMs <= 0L) return false
        return if (timestampMs > updateTimestampMs) {
            val lead = timestampMs - updateTimestampMs
            lead >= 0L && lead <= maxFutureSkewMs
        } else {
            val age = updateTimestampMs - timestampMs
            age >= 0L && age <= maxAgeMs
        }
    }

    /**
     * Consumes a fresh capture once. A repeated nonzero frame ID or a non-increasing capture
     * timestamp is rejected. Frame IDs may restart after a camera reboot if capture time advances.
     * Rejected observations do not alter history. Physical/quality filtering belongs to the caller.
     */
    fun accept(measurement: VisionMeasurement): Boolean {
        if (!isRecent(measurement.timestampMs)) return false
        val sourceId = measurement.sourceId
        var index = 0
        while (index < sourceCount) {
            if (sourceIds[index] == sourceId) {
                if (measurement.timestampMs <= timestampsMs[index] ||
                    (measurement.frameId != 0L && measurement.frameId == frameIds[index])) return false
                frameIds[index] = measurement.frameId
                timestampsMs[index] = measurement.timestampMs
                return true
            }
            index++
        }
        if (sourceCount == sourceIds.size) {
            val size = sourceIds.size * 2
            sourceIds = sourceIds.copyOf(size)
            frameIds = frameIds.copyOf(size)
            timestampsMs = timestampsMs.copyOf(size)
        }
        sourceIds[sourceCount] = sourceId
        frameIds[sourceCount] = measurement.frameId
        timestampsMs[sourceCount] = measurement.timestampMs
        sourceCount++
        return true
    }

    /** Explicitly starts a new observation epoch while retaining allocated storage. */
    fun clear() {
        sourceIds.fill(null, 0, sourceCount)
        sourceCount = 0
        hasUpdate = false
    }
}
