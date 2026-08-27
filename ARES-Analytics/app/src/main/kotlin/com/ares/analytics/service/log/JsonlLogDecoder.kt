package com.ares.analytics.service.log

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.FrameBatcher
import com.ares.analytics.shared.RobotActionRecord
import com.ares.analytics.shared.TelemetryFrame
import com.ares.analytics.shared.models.MAX_SUPPORTED_TIMESTAMP_MS
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.io.BufferedReader
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
 *
 * Parses streaming JSON objects containing frame key-value pairs or structured [RobotActionRecord] objects emitted by
 * `ARESLib-Kotlin` Redux reducers.
 *
 * ### Schema & Data Formats:
 * - Telemetry JSON: `{"timestampMs": 12345, "Drive/Pose_X": 1.25, "Hardware/Motors/fl/Power": 0.85}`
 * - Action JSON: Redux dispatch action payloads `(actionType, payloadJson, alliance, matchNumber)`
 *
 * ### Thread Safety & Performance Guarantees:
 * Suspend functions process JSON line streams sequentially on `Dispatchers.IO`, buffering parsed items into [FrameBatcher] channel without loading complete files into memory.
 *
 * @param databaseService Primary DuckDB persistence interface.
 *
 * @see CsvLogDecoder
 * @see FrameBatcher
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
        // A 2.5-minute match can legitimately dispatch several Redux actions per 20 ms tick.
        // Keep a hard streaming-import bound, but do not reject complete competition runs.
        const val MAX_ACTION_RECORDS = 250_000
        const val ACTION_BATCH_SIZE = 500
        const val ACTION_SCHEMA_VERSION = 1
    }

    /**
     * Parses a line-delimited JSON telemetry file line by line into [batcher].
     *
     * @param file Source `.jsonl` file.
     * @param sessionId Target session ID string.
     * @param batcher Destination telemetry frame batch buffer.
     */
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
        BoundedLineReader(file.bufferedReader(Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    try {
                        val obj = Json.parseToJsonElement(trimmed) as? JsonObject
                        if (obj == null) {
                            rejectedLines++
                            continue
                        }
                        // Look for timestamp
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
                            val doubleVal = primitive.doubleOrNull
                            val booleanVal = primitive.booleanOrNull
                            when {
                                doubleVal != null -> {
                                    emit(TelemetryFrame(timestampMs, sessionId, key.trimStart('/'), doubleVal))
                                    acceptedFrames++
                                }
                                booleanVal != null -> {
                                    emit(TelemetryFrame(timestampMs, sessionId, key.trimStart('/'), if (booleanVal) 1.0 else 0.0))
                                    acceptedFrames++
                                }
                                primitive.isString -> {
                                    emit(TelemetryFrame(timestampMs, sessionId, key.trimStart('/'), 0.0, primitive.content))
                                    acceptedFrames++
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
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

    suspend fun parseActionLogJsonl(file: File, sessionId: String): ActionLogMetadata? {
        require(file.isFile && file.length() in 1L..MAX_JSONL_BYTES) {
            "Action log size is outside the supported range"
        }
        val preflight = inspectActionLog(file, sessionId)
        val actions = ArrayList<RobotActionRecord>(ACTION_BATCH_SIZE)
        BoundedLineReader(file.bufferedReader(Charsets.UTF_8)).use { reader ->
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
        return preflight
    }

    private fun inspectActionLog(file: File, sessionId: String): ActionLogMetadata {
        var minTimestamp = Long.MAX_VALUE
        var maxTimestamp = Long.MIN_VALUE
        var firstMatchNumber = 0
        var firstAlliance = "UNKNOWN"
        var isFirstLine = true
        var actionCount = 0

        BoundedLineReader(file.bufferedReader(Charsets.UTF_8)).use { reader ->
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
            }
        }

        require(actionCount > 0) { "Action log ${file.name} contained no usable actions" }

        return ActionLogMetadata(
            durationMs = if (maxTimestamp > minTimestamp) Math.subtractExact(maxTimestamp, minTimestamp) else 0L,
            matchNumber = firstMatchNumber,
            alliance = firstAlliance
        )
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

    private class BoundedLineReader(reader: Reader) : Closeable {
        private val reader = if (reader is BufferedReader) reader else reader.buffered()
        private var pushedBack = NO_PUSHBACK

        fun readLine(): String? {
            val line = StringBuilder()
            var sawInput = false
            while (true) {
                val value = readChar()
                if (value == -1) return if (sawInput) line.toString() else null
                sawInput = true
                when (value.toChar()) {
                    '\n' -> return line.toString()
                    '\r' -> {
                        val next = readChar()
                        if (next != '\n'.code) pushedBack = next
                        return line.toString()
                    }
                    else -> {
                        require(line.length < MAX_JSONL_LINE_CHARS) { "JSONL line exceeds the size limit" }
                        line.append(value.toChar())
                    }
                }
            }
        }

        private fun readChar(): Int {
            if (pushedBack != NO_PUSHBACK) {
                val value = pushedBack
                pushedBack = NO_PUSHBACK
                return value
            }
            return reader.read()
        }

        override fun close() = reader.close()

        private companion object {
            const val NO_PUSHBACK = -2
        }
    }
}
