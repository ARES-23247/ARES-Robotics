package com.ares.analytics.service.log

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.FrameBatcher
import com.ares.analytics.shared.models.MAX_SUPPORTED_TIMESTAMP_MS
import com.ares.analytics.shared.models.RobotActionRecord
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.Closeable
import java.io.File
import java.io.Reader

/**
 * Metadata extracted from a Redux robot action log envelope header.
 *
 * @property durationMs Total session execution duration in milliseconds ($ms$).
 * @property matchNumber Tournament match sequence number.
 * @property alliance Alliance station color string (`"RED"`, `"BLUE"`, or `"UNKNOWN"`).
 */
data class ActionLogMetadata(
    val durationMs: Long,
    val matchNumber: Int,
    val alliance: String
)

/**
 * Service for parsing line-delimited JSON (`.jsonl`) telemetry log files and Redux robot action event streams.
 */
class JsonlLogDecoder(
    private val databaseService: DatabaseService,
    private val maxActionRecords: Int = MAX_ACTION_RECORDS,
) {
    init {
        require(maxActionRecords > 0) { "maxActionRecords must be positive" }
    }

    private companion object {
        const val MAX_JSONL_BYTES = 512L * 1024L * 1024L
        const val MAX_JSONL_LINE_CHARS = 1_048_576
        const val MAX_ACTION_PAYLOAD_CHARS = 524_288
        const val MAX_ACTION_RECORDS = 250_000
        const val ACTION_BATCH_SIZE = 5_000
        const val SINGLE_PASS_ACTION_LIMIT = 25_000
        const val ACTION_SCHEMA_VERSION = 1
    }

    suspend fun parseJsonlLog(file: File, sessionId: String, batcher: FrameBatcher): Int {
        return parseJsonlLog(file, sessionId) { frame -> batcher.add(frame) }
    }

    internal suspend fun parseJsonlLog(
        file: File,
        sessionId: String,
        emit: suspend (TelemetryFrame) -> Unit
    ): Int {
        require(file.isFile && file.length() in 1L..MAX_JSONL_BYTES) {
            "JSONL log size is outside the supported range"
        }
        var acceptedFrames = 0
        var rejectedLines = 0
        val normalizedKeys = HashMap<String, String>(128)
        val fastEntries = FlatJsonEntries()
        BoundedLineReader(file.reader(Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    try {
                        val fastCount = tryParseFlatTelemetryLine(trimmed, sessionId, normalizedKeys, fastEntries, emit)
                        if (fastCount >= 0) {
                            if (fastCount == 0) {
                                // Valid flat JSON object with timestamp, or check if missing timestamp
                                if (!fastEntries.hasTimestamp) rejectedLines++
                            } else {
                                acceptedFrames += fastCount
                            }
                            continue
                        }

                        val obj = Json.parseToJsonElement(trimmed) as? JsonObject
                        if (obj == null) {
                            rejectedLines++
                            continue
                        }
                        val timestampMs = obj["timestampMs"]?.jsonPrimitive?.longOrNull
                            ?: obj["time"]?.jsonPrimitive?.longOrNull
                            ?: obj["timestamp"]?.jsonPrimitive?.longOrNull

                        if (timestampMs == null) {
                            rejectedLines++
                            continue
                        }
                        for ((key, value) in obj) {
                            if (key == "timestampMs" || key == "time" || key == "timestamp") continue
                            val primitive = value as? JsonPrimitive ?: continue
                            val normKey = normalizedKeys.getOrPut(key) { key.trimStart('/') }
                            val doubleVal = primitive.doubleOrNull
                            val booleanVal = primitive.booleanOrNull
                            when {
                                doubleVal != null -> {
                                    emit(TelemetryFrame(timestampMs, sessionId, normKey, doubleVal))
                                    acceptedFrames++
                                }
                                booleanVal != null -> {
                                    emit(TelemetryFrame(timestampMs, sessionId, normKey, if (booleanVal) 1.0 else 0.0))
                                    acceptedFrames++
                                }
                                primitive.isString -> {
                                    emit(TelemetryFrame(timestampMs, sessionId, normKey, 0.0, primitive.content))
                                    acceptedFrames++
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        rejectedLines++
                    }
                }
            }
        }
        require(acceptedFrames > 0) {
            "JSONL log ${file.name} contained no usable telemetry frames ($rejectedLines rejected lines)"
        }
        return acceptedFrames
    }

    private class FlatJsonEntries {
        var keys = arrayOfNulls<String>(32)
        var kinds = IntArray(32) // 1 = double, 2 = string
        var doubles = DoubleArray(32)
        var strings = arrayOfNulls<String>(32)
        var size = 0
        var hasTimestamp = false

        fun reset() {
            size = 0
            hasTimestamp = false
        }

        fun add(key: String, kind: Int, dVal: Double, sVal: String?) {
            if (size == keys.size) {
                val next = keys.size * 2
                keys = keys.copyOf(next)
                kinds = kinds.copyOf(next)
                doubles = doubles.copyOf(next)
                strings = strings.copyOf(next)
            }
            keys[size] = key
            kinds[size] = kind
            doubles[size] = dVal
            strings[size] = sVal
            size++
        }
    }

    /**
     * Zero-JsonObject fast path for standard flat unescaped telemetry lines (`{"timestampMs":1000,"/A":1.5}`).
     * Returns `-1` whenever the line contains escapes, nested structures, or anything ambiguous so the caller
     * falls back to [Json.parseToJsonElement] with identical semantics.
     */
    private suspend fun tryParseFlatTelemetryLine(
        s: String,
        sessionId: String,
        normalizedKeys: HashMap<String, String>,
        entries: FlatJsonEntries,
        emit: suspend (TelemetryFrame) -> Unit,
    ): Int {
        val len = s.length
        if (len < 2 || s[0] != '{' || s[len - 1] != '}') return -1
        if (s.indexOf('\\') >= 0) return -1
        entries.reset()
        var i = 1
        val endLimit = len - 1
        var timestampMs = 0L
        var sawTimestamp = false

        while (true) {
            while (i < endLimit && s[i] <= ' ') i++
            if (i >= endLimit) break
            if (s[i] != '"') return -1
            val keyStart = i + 1
            val keyEnd = s.indexOf('"', keyStart)
            if (keyEnd < 0 || keyEnd >= endLimit) return -1
            val rawKey = s.substring(keyStart, keyEnd)
            i = keyEnd + 1
            while (i < endLimit && s[i] <= ' ') i++
            if (i >= endLimit || s[i] != ':') return -1
            i++
            while (i < endLimit && s[i] <= ' ') i++
            if (i >= endLimit) return -1

            val isTsKey = rawKey == "timestampMs" || rawKey == "time" || rawKey == "timestamp"
            val ch = s[i]
            if (ch == '"') {
                val valStart = i + 1
                val valEnd = s.indexOf('"', valStart)
                if (valEnd < 0 || valEnd > endLimit) return -1
                val strVal = s.substring(valStart, valEnd)
                i = valEnd + 1
                if (isTsKey) {
                    val parsedTs = strVal.toLongOrNull() ?: return -1
                    if (!sawTimestamp || rawKey == "timestampMs" || (rawKey == "time" && !sawTimestamp)) {
                        if (rawKey == "timestampMs" || !sawTimestamp) {
                            timestampMs = parsedTs
                            sawTimestamp = true
                        }
                    }
                } else {
                    val normKey = normalizedKeys.getOrPut(rawKey) { rawKey.trimStart('/') }
                    val asDouble = strVal.toDoubleOrNull()
                    val asBool = if (asDouble == null) strVal.toBooleanStrictOrNull() else null
                    when {
                        asDouble != null -> entries.add(normKey, 1, asDouble, null)
                        asBool != null -> entries.add(normKey, 1, if (asBool) 1.0 else 0.0, null)
                        else -> entries.add(normKey, 2, 0.0, strVal)
                    }
                }
            } else if (ch == '{' || ch == '[') {
                return -1
            } else {
                val tokenStart = i
                while (i < endLimit && s[i] != ',' && s[i] > ' ') i++
                if (i == tokenStart) return -1
                val token = s.substring(tokenStart, i)
                if (isTsKey) {
                    val parsedTs = token.toLongOrNull() ?: return -1
                    if (rawKey == "timestampMs" || !sawTimestamp) {
                        timestampMs = parsedTs
                        sawTimestamp = true
                    }
                } else if (token == "true") {
                    val normKey = normalizedKeys.getOrPut(rawKey) { rawKey.trimStart('/') }
                    entries.add(normKey, 1, 1.0, null)
                } else if (token == "false") {
                    val normKey = normalizedKeys.getOrPut(rawKey) { rawKey.trimStart('/') }
                    entries.add(normKey, 1, 0.0, null)
                } else if (token == "null") {
                    // JsonNull is ignored unless string; fall back if unsure
                    return -1
                } else {
                    val dVal = token.toDoubleOrNull() ?: return -1
                    val normKey = normalizedKeys.getOrPut(rawKey) { rawKey.trimStart('/') }
                    entries.add(normKey, 1, dVal, null)
                }
            }

            while (i < endLimit && s[i] <= ' ') i++
            if (i < endLimit) {
                if (s[i] != ',') return -1
                i++
                while (i < endLimit && s[i] <= ' ') i++
                if (i >= endLimit) return -1
            }
        }

        entries.hasTimestamp = sawTimestamp
        if (!sawTimestamp) return 0
        for (idx in 0 until entries.size) {
            val k = entries.keys[idx]!!
            if (entries.kinds[idx] == 1) {
                emit(TelemetryFrame(timestampMs, sessionId, k, entries.doubles[idx]))
            } else {
                emit(TelemetryFrame(timestampMs, sessionId, k, 0.0, entries.strings[idx]))
            }
        }
        return entries.size
    }

    suspend fun parseActionLogJsonl(file: File, sessionId: String): ActionLogMetadata? {
        require(file.isFile && file.length() in 1L..MAX_JSONL_BYTES) {
            "Action log size is outside the supported range"
        }
        var minTimestamp = Long.MAX_VALUE
        var maxTimestamp = Long.MIN_VALUE
        var firstMatchNumber = 0
        var firstAlliance = "UNKNOWN"
        var isFirstLine = true
        var actionCount = 0
        var singlePassBuffer: ArrayList<RobotActionRecord>? = ArrayList(minOf(maxActionRecords, 5_000))

        BoundedLineReader(file.reader(Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val action = parseAction(line, sessionId) ?: continue
                actionCount++
                require(actionCount <= maxActionRecords) { "Action log contains too many records" }
                if (isFirstLine) {
                    firstMatchNumber = action.matchNumber
                    firstAlliance = action.alliance
                    isFirstLine = false
                }
                minTimestamp = minOf(minTimestamp, action.timestampMs)
                maxTimestamp = maxOf(maxTimestamp, action.timestampMs)
                val buf = singlePassBuffer
                if (buf != null) {
                    if (buf.size < SINGLE_PASS_ACTION_LIMIT) {
                        buf.add(action)
                    } else {
                        buf.clear()
                        singlePassBuffer = null
                    }
                }
            }
        }

        require(actionCount > 0) { "Action log ${file.name} contained no usable actions" }
        val metadata = ActionLogMetadata(
            durationMs = if (maxTimestamp > minTimestamp) Math.subtractExact(maxTimestamp, minTimestamp) else 0L,
            matchNumber = firstMatchNumber,
            alliance = firstAlliance,
        )

        val buffered = singlePassBuffer
        if (buffered != null) {
            var offset = 0
            while (offset < buffered.size) {
                val end = minOf(offset + ACTION_BATCH_SIZE, buffered.size)
                databaseService.insertRobotActionsBulk(buffered.subList(offset, end))
                offset = end
            }
            return metadata
        }

        val actions = ArrayList<RobotActionRecord>(ACTION_BATCH_SIZE)
        BoundedLineReader(file.reader(Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                parseAction(line, sessionId)?.let { action ->
                    actions += action
                    if (actions.size == ACTION_BATCH_SIZE) {
                        databaseService.insertRobotActionsBulk(actions)
                        actions.clear()
                    }
                }
            }
        }
        if (actions.isNotEmpty()) databaseService.insertRobotActionsBulk(actions)
        return metadata
    }

    private fun parseAction(line: String, sessionId: String): RobotActionRecord? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val obj = Json.parseToJsonElement(trimmed) as? JsonObject ?: return null
            requireActionSchemaVersion(obj)
            val payload = obj["payload"] as? JsonObject ?: return null
            val timestampMs = payload["timestampMs"]?.jsonPrimitive?.longOrNull ?: return null
            if (timestampMs !in 0L..MAX_SUPPORTED_TIMESTAMP_MS) return null
            val payloadJson = payload.toString()
            require(payloadJson.length <= MAX_ACTION_PAYLOAD_CHARS) { "Action payload exceeds the size limit" }
            RobotActionRecord(
                timestampMs = timestampMs,
                sessionId = sessionId,
                runId = obj["run_id"]?.jsonPrimitive?.contentOrNull ?: "",
                robotId = obj["robot_id"]?.jsonPrimitive?.contentOrNull ?: "",
                matchNumber = obj["match_number"]?.jsonPrimitive?.intOrNull ?: 0,
                alliance = obj["alliance"]?.jsonPrimitive?.contentOrNull ?: "UNKNOWN",
                actionType = obj["type"]?.jsonPrimitive?.contentOrNull ?: "Unknown",
                payloadJson = payloadJson
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ActionLogFormatException) {
            throw error
        } catch (error: IllegalArgumentException) {
            if (error.message?.contains("size limit") == true) throw error
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun requireActionSchemaVersion(envelope: JsonObject) {
        val schemaVersion = envelope["schema_version"] as? JsonPrimitive
            ?: throw ActionLogFormatException("Action record is missing schema_version")
        if (schemaVersion.isString || schemaVersion.content != ACTION_SCHEMA_VERSION.toString()) {
            throw ActionLogFormatException(
                "Unsupported action-log schema_version $schemaVersion; supported version is $ACTION_SCHEMA_VERSION"
            )
        }
    }

    private class ActionLogFormatException(message: String) : IllegalArgumentException(message)

    private class BoundedLineReader(private val reader: Reader) : Closeable {
        private val buffer = CharArray(16_384)
        private var pos = 0
        private var limit = 0
        private var skipNextLf = false
        private val lineBuilder = StringBuilder(256)

        fun readLine(): String? {
            lineBuilder.setLength(0)
            var sawInput = false
            while (true) {
                if (pos >= limit) {
                    val read = reader.read(buffer, 0, buffer.size)
                    if (read < 0) {
                        skipNextLf = false
                        return if (sawInput) lineBuilder.toString() else null
                    }
                    pos = 0
                    limit = read
                }
                if (skipNextLf) {
                    skipNextLf = false
                    if (buffer[pos] == '\n') {
                        pos++
                        continue
                    }
                }
                sawInput = true
                var scan = pos
                while (scan < limit) {
                    val c = buffer[scan]
                    if (c == '\n' || c == '\r') break
                    scan++
                }
                val chunkLen = scan - pos
                if (chunkLen > 0) {
                    require(lineBuilder.length + chunkLen <= MAX_JSONL_LINE_CHARS) {
                        "JSONL line exceeds the size limit"
                    }
                    lineBuilder.append(buffer, pos, chunkLen)
                    pos = scan
                }
                if (pos < limit) {
                    val term = buffer[pos++]
                    if (term == '\r') {
                        if (pos < limit) {
                            if (buffer[pos] == '\n') pos++
                        } else {
                            skipNextLf = true
                        }
                    }
                    return lineBuilder.toString()
                }
            }
        }

        override fun close() = reader.close()
    }
}
