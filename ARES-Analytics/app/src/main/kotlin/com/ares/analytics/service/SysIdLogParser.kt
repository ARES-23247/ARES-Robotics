package com.ares.analytics.service

import com.ares.analytics.shared.models.MAX_SUPPORTED_TIMESTAMP_MS
import kotlinx.serialization.json.*
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.round

/**
 * Imports chronological, finite SysId rows without inventing timestamps or acceleration.
 * JSON timestamps and unqualified CSV time columns are milliseconds. CSV may explicitly
 * name seconds or microseconds. Missing acceleration needs a preceding timed velocity;
 * explicit zero acceleration is a measurement and is preserved. Conflicting duplicate times are omitted.
 */
internal object SysIdLogParser {
    private data class Sample(val time: Long, val voltage: Double, val velocity: Double, val acceleration: Double?)

    fun parse(content: String): List<AlignedDataRow> {
        val lines = content.lineSequence().map { it.trim().removePrefix("\uFEFF").trimStart() }.filter { it.isNotEmpty() }.iterator()
        if (!lines.hasNext()) return emptyList()
        val first = lines.next()
        // A null value marks an ambiguous timestamp, including any later duplicate.
        val samples = TreeMap<Long, Sample?>()
        fun add(sample: Sample?) {
            if (sample != null) {
                if (!samples.containsKey(sample.time)) samples[sample.time] = sample
                else if (samples[sample.time] != sample) samples[sample.time] = null
            }
        }
        if (first.startsWith("{")) {
            fun parseLine(line: String) {
                try { add(jsonSample(Json.parseToJsonElement(line).jsonObject)) }
                catch (_: IllegalArgumentException) { /* Reject this malformed row, preserving neighbors. */ }
            }
            parseLine(first)
            while (lines.hasNext()) parseLine(lines.next())
        } else {
            val header = csvFields(first)?.map { it.lowercase() } ?: return emptyList()
            val timeColumn = header.indexOfFirst { timeMultiplier(it) != null }
            val voltageColumn = header.indexOfFirst { it.contains("volt") || it == "v" || it == "u" }
            val velocityColumn = header.indexOfFirst { it.contains("vel") || it.contains("speed") || it == "omega" }
            val accelerationColumn = header.indexOfFirst { it.contains("accel") || it == "a" }
            if (timeColumn < 0 || voltageColumn < 0 || velocityColumn < 0) return emptyList()
            val multiplier = timeMultiplier(header[timeColumn])!!
            while (lines.hasNext()) {
                val fields = csvFields(lines.next()) ?: continue
                val time = timestamp(fields.getOrNull(timeColumn)?.toDoubleOrNull(), multiplier) ?: continue
                val voltage = finite(fields.getOrNull(voltageColumn)?.toDoubleOrNull()) ?: continue
                val velocity = finite(fields.getOrNull(velocityColumn)?.toDoubleOrNull()) ?: continue
                val accelerationText = if (accelerationColumn < 0) null else fields.getOrNull(accelerationColumn)
                // A missing column in a row is malformed; a present blank field explicitly omits acceleration.
                if (accelerationColumn >= 0 && accelerationText == null) continue
                val acceleration = if (accelerationText.isNullOrBlank()) null
                    else finite(accelerationText.toDoubleOrNull()) ?: continue
                add(Sample(time, voltage, velocity, acceleration))
            }
        }
        val result = ArrayList<AlignedDataRow>(samples.size)
        var previous: Sample? = null
        for (current in samples.values) {
            if (current == null) continue
            val prior = previous
            previous = current
            val acceleration = current.acceleration ?: if (prior == null) continue else {
                val seconds = (current.time - prior.time) / 1000.0
                val difference = current.velocity - prior.velocity
                val derivative = if (difference.isFinite()) difference / seconds
                    else current.velocity / seconds - prior.velocity / seconds
                finite(derivative) ?: continue
            }
            result.add(AlignedDataRow(current.time, current.voltage, current.velocity, acceleration))
        }
        return result
    }

    private fun jsonSample(element: JsonObject): Sample? {
        val packed = element["SysId/Data"] ?: element["SysId_Data"] ?: element["sysid_data"]
        if (packed != null) {
            val values = packed.jsonArray
            if (values.size < 4) return null
            val time = timestamp(values[0].jsonPrimitive.doubleOrNull) ?: return null
            val voltage = finite(values[1].jsonPrimitive.doubleOrNull) ?: return null
            finite(values[2].jsonPrimitive.doubleOrNull) ?: return null // Position retains its slot even though fitting uses velocity.
            val velocity = finite(values[3].jsonPrimitive.doubleOrNull) ?: return null
            val accelerationValue = values.getOrNull(4)
            val acceleration = if (accelerationValue == null || accelerationValue is JsonNull) null
                else finite(accelerationValue.jsonPrimitive.doubleOrNull) ?: return null
            return Sample(time, voltage, velocity, acceleration)
        }
        val time = timestamp((element["timestampMs"] ?: element["TimestampMs"] ?: element["timestamp"] ?: element["time"])?.jsonPrimitive?.doubleOrNull) ?: return null
        val voltage = finite((element["voltage"] ?: element["Voltage"] ?: element["Drive/Voltage"])?.jsonPrimitive?.doubleOrNull) ?: return null
        val velocity = finite((element["velocity"] ?: element["Velocity"] ?: element["speed"] ?: element["Drive/Velocity"])?.jsonPrimitive?.doubleOrNull) ?: return null
        val accelerationValue = element["accel"] ?: element["acceleration"] ?: element["Acceleration"] ?: element["Drive/Acceleration"]
        val acceleration = if (accelerationValue == null || accelerationValue is JsonNull) null
            else finite(accelerationValue.jsonPrimitive.doubleOrNull) ?: return null
        return Sample(time, voltage, velocity, acceleration)
    }

    private fun finite(value: Double?): Double? = value?.takeIf { it.isFinite() }

    private fun timestamp(value: Double?, multiplier: Double = 1.0): Long? {
        val milliseconds = (value ?: return null) * multiplier
        if (!milliseconds.isFinite() || milliseconds < 0 || milliseconds > MAX_SUPPORTED_TIMESTAMP_MS) return null
        val rounded = round(milliseconds)
        // Allow conversion roundoff, but never silently truncate a fractional millisecond.
        if (abs(milliseconds - rounded) > 1e-6) return null
        return rounded.toLong()
    }

    private fun timeMultiplier(header: String): Double? = when (header.replace(" ", "")) {
        "t", "ts", "time", "timestamp", "timems", "timestampms", "time_ms", "timestamp_ms", "time(ms)", "timestamp(ms)" -> 1.0
        "time(s)", "timestamp(s)", "time_s", "timestamp_s", "time_sec", "time_seconds", "seconds" -> 1000.0
        "time(us)", "timestamp(us)", "time_us", "timestamp_us", "microseconds" -> 0.001
        else -> null
    }

    /** Single-record CSV, including quoted commas and doubled quotes. Multiline fields are rejected. */
    private fun csvFields(line: String): List<String>? {
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var closedQuote = false
        var index = 0
        while (index < line.length) {
            val char = line[index++]
            when {
                quoted && char == '"' -> {
                    if (index < line.length && line[index] == '"') { field.append('"'); index++ }
                    else { quoted = false; closedQuote = true }
                }
                quoted -> field.append(char)
                char == ',' -> { fields.add(field.toString().trim()); field.setLength(0); closedQuote = false }
                closedQuote && !char.isWhitespace() -> return null
                closedQuote -> Unit
                char == '"' -> {
                    if (field.isNotBlank()) return null
                    field.setLength(0)
                    quoted = true
                }
                else -> field.append(char)
            }
        }
        if (quoted) return null
        fields.add(field.toString().trim())
        return fields
    }
}
