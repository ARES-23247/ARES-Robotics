package com.ares.analytics.viewmodel.sysid

import com.ares.analytics.shared.models.MAX_SUPPORTED_TIMESTAMP_MS
import com.ares.analytics.shared.models.TelemetryFrame

internal fun isGeometricCalibration(kind: String) = when (kind) {
    "PINPOINT_SPIN", "TRACK_WIDTH_SPIN", "VISION_CALIBRATION", "LINEAR_DRIVE" -> true
    else -> false
}

/** Owned by the collector lock. Missing channels and duplicate packets never become measurements. */
internal class SysIdSampleAssembler(private val capacity: Int = 512) {
    init { require(capacity > 0) }
    private data class Key(val session: String, val microseconds: Long)
    private class Pending(val values: DoubleArray, var mask: Int = 0)
    private val pending = LinkedHashMap<Key, Pending>()
    private val completed = LinkedHashSet<Key>()
    internal val pendingCount get() = pending.size
    internal val completedCount get() = completed.size
    fun clear() { pending.clear(); completed.clear() }

    fun accept(frame: TelemetryFrame, kind: String): DoubleArray? {
        val columns = when (kind) {
            "PINPOINT_SPIN", "VISION_CALIBRATION" -> 4
            "LINEAR_DRIVE" -> 3
            "TRACK_WIDTH_SPIN" -> 7
            else -> 5
        }
        val index = if (frame.key == "SysId/Data") null else frame.key.removePrefix("SysId/Data/").toIntOrNull()
        if (frame.key != "SysId/Data" && (index == null || index !in 0 until columns)) return null
        val key = Key(frame.sessionId, frame.timestampUs)
        if (key in completed) return null
        if (frame.key == "SysId/Data") {
            val text = frame.stringValue ?: return null
            finish(key)
            if (text.length > 1024) return null
            val parts = text.split('|', limit = columns + 1)
            if (parts.size < columns) return null
            val values = DoubleArray(columns)
            for (i in values.indices) values[i] = parts[i].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            return validated(values)
        }
        if (!frame.value.isFinite() || frame.stringValue != null) { finish(key); return null }
        val i = requireNotNull(index)
        var row = pending[key]
        if (row == null) {
            if (pending.size == capacity) {
                val oldest = pending.keys.iterator(); oldest.next(); oldest.remove()
            }
            row = Pending(DoubleArray(columns))
            pending[key] = row
        }
        if (row.values.size != columns || (row.mask and (1 shl i) != 0 && row.values[i] != frame.value)) {
            finish(key)
            return null
        }
        row.values[i] = frame.value
        row.mask = row.mask or (1 shl i)
        if (row.mask != (1 shl columns) - 1) return null
        finish(key)
        return validated(row.values)
    }

    private fun validated(values: DoubleArray): DoubleArray? {
        val time = values[0]
        if (time < 0 || time > MAX_SUPPORTED_TIMESTAMP_MS || time != time.toLong().toDouble()) return null
        return values
    }
    private fun finish(key: Key) {
        pending.remove(key)
        if (completed.size == capacity) {
            val oldest = completed.iterator(); oldest.next(); oldest.remove()
        }
        completed.add(key)
    }
}
