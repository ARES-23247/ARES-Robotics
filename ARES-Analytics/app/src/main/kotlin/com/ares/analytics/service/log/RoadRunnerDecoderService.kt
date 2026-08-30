package com.ares.analytics.service.log

import com.ares.analytics.service.FrameBatcher
import com.ares.analytics.shared.models.TelemetryFrame
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Service for decoding FTC RoadRunner binary telemetry files (`.log`) emitted during trajectory execution.
 *
 * Unpacks nested binary schema structures describing robot odometry pose ($x, y, \theta$), profile target state,
 * motor power commands, and heading errors ($rad$).
 *
 * ### Physical Units & Kinematics Specs:
 * - Position ($x, y$): Meters ($m$) or Inches ($in$)
 * - Heading ($\theta$): Radians ($rad$), **CCW-positive** (0 = +X)
 * - Velocity ($v_x, v_y, \omega$): $m/s$ and $rad/s$
 * - Motor Commands: Duty cycle percent ($-1.0 \dots +1.0$)
 *
 * ### Thread Safety & Performance Guarantees:
 * Runs asynchronously on `Dispatchers.IO`. Decodes binary streams into [FrameBatcher] channel buffer.
 *
 * @see BaseLogDecoder
 * @see CsvLogDecoder
 */
class RoadRunnerDecoderService : BaseLogDecoder() {

    /**
     * Sealed interface representing schema definitions for binary RoadRunner record fields.
     */
    sealed interface RRSchema

    /** Schema descriptor for 32-bit integer fields. */
    object IntSchema : RRSchema

    /** Schema descriptor for 64-bit long integer fields. */
    object LongSchema : RRSchema

    /** Schema descriptor for 64-bit IEEE 754 double floating-point fields. */
    object DoubleSchema : RRSchema

    /** Schema descriptor for UTF-8 string fields. */
    object StringSchema : RRSchema

    /** Schema descriptor for boolean flags (`true` / `false`). */
    object BooleanSchema : RRSchema

    /**
     * Schema descriptor for enumerated type fields.
     *
     * @property constants List of string enum variant names.
     */
    class EnumSchema(val constants: List<String>) : RRSchema

    /**
     * Schema descriptor for array collection fields.
     *
     * @property elementSchema Type schema of array elements.
     */
    class ArraySchema(val elementSchema: RRSchema) : RRSchema

    /**
     * Schema descriptor for nested struct objects.
     *
     * @property fields List of field name and type schema pairs.
     */
    class StructSchema(val fields: List<Pair<String, RRSchema>>) : RRSchema

    /**
     * Decodes a binary RoadRunner trajectory log file into [batcher].
     *
     * @param file Target binary log file.
     * @param sessionId Session identifier string.
     * @param batcher Destination telemetry frame batch buffer.
     */
    override suspend fun decode(
        file: File,
        sessionId: String,
        batcher: FrameBatcher
    ) {
        require(file.isFile) { "RoadRunner log does not exist" }
        require(file.length() in 4..MAX_LOG_BYTES) { "RoadRunner log size is invalid" }
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        var offset = 0
        var schemaNodeCount = 0
        var decodedValueCount = 0

        fun requireRemaining(count: Int) {
            require(count >= 0 && offset <= bytes.size - count) {
                "Truncated RoadRunner log at byte $offset"
            }
        }

        fun readInt(): Int {
            requireRemaining(Int.SIZE_BYTES)
            return buffer.getInt(offset).also { offset += Int.SIZE_BYTES }
        }

        fun readShort(): Short {
            requireRemaining(Short.SIZE_BYTES)
            return buffer.getShort(offset).also { offset += Short.SIZE_BYTES }
        }

        fun readLong(): Long {
            requireRemaining(Long.SIZE_BYTES)
            return buffer.getLong(offset).also { offset += Long.SIZE_BYTES }
        }

        fun readDouble(): Double {
            requireRemaining(Double.SIZE_BYTES)
            return buffer.getDouble(offset).also { offset += Double.SIZE_BYTES }
        }

        fun readString(): String {
            val len = readInt()
            require(len in 0..MAX_STRING_BYTES) { "Invalid RoadRunner string length: $len" }
            requireRemaining(len)
            val str = String(bytes, offset, len, Charsets.UTF_8)
            offset += len
            return str
        }

        fun readSchema(depth: Int = 0): RRSchema {
            require(depth <= MAX_NESTING_DEPTH) { "RoadRunner schema nesting is too deep" }
            require(++schemaNodeCount <= MAX_SCHEMA_NODES) { "RoadRunner schema is too large" }
            val schemaType = readInt()
            return when (schemaType) {
                0 -> {
                    val numFields = readInt()
                    require(numFields in 0..MAX_STRUCT_FIELDS) { "Invalid struct field count: $numFields" }
                    val fields = mutableListOf<Pair<String, RRSchema>>()
                    for (i in 0 until numFields) {
                        fields.add(Pair(readString(), readSchema(depth + 1)))
                    }
                    StructSchema(fields)
                }
                1 -> IntSchema
                2 -> LongSchema
                3 -> DoubleSchema
                4 -> StringSchema
                5 -> BooleanSchema
                6 -> {
                    val numConstants = readInt()
                    require(numConstants in 0..MAX_ENUM_CONSTANTS) { "Invalid enum constant count: $numConstants" }
                    val constants = mutableListOf<String>()
                    for (i in 0 until numConstants) {
                        constants.add(readString())
                    }
                    EnumSchema(constants)
                }
                7 -> ArraySchema(readSchema(depth + 1))
                else -> throw IllegalArgumentException("Unknown schema type: $schemaType")
            }
        }

        fun arraySchemaCount(schema: RRSchema): Int {
            return when (schema) {
                is StructSchema -> {
                    var count = 0
                    for (field in schema.fields) {
                        count += arraySchemaCount(field.second)
                    }
                    count
                }
                is ArraySchema -> 1 + arraySchemaCount(schema.elementSchema)
                else -> 0
            }
        }

        fun readMsg(schema: RRSchema, depth: Int = 0): Any {
            require(depth <= MAX_NESTING_DEPTH) { "RoadRunner value nesting is too deep" }
            require(++decodedValueCount <= MAX_DECODED_VALUES) { "RoadRunner record budget exceeded" }
            return when (schema) {
                is StructSchema -> {
                    val map = mutableMapOf<String, Any>()
                    for (field in schema.fields) {
                        map[field.first] = readMsg(field.second, depth + 1)
                    }
                    map
                }
                is IntSchema -> {
                    readInt()
                }
                is LongSchema -> {
                    readLong()
                }
                is DoubleSchema -> {
                    readDouble()
                }
                is StringSchema -> readString()
                is BooleanSchema -> {
                    requireRemaining(1)
                    val raw = bytes[offset].toInt() and 0xff
                    offset += 1
                    require(raw == 0 || raw == 1) { "Invalid RoadRunner boolean value: $raw" }
                    raw == 1
                }
                is EnumSchema -> {
                    val ordinal = readInt()
                    require(ordinal in schema.constants.indices) { "Invalid enum ordinal: $ordinal" }
                    schema.constants[ordinal]
                }
                is ArraySchema -> {
                    val size = readInt()
                    require(size in 0..MAX_ARRAY_ELEMENTS) { "Invalid RoadRunner array length: $size" }
                    val list = mutableListOf<Any>()
                    for (i in 0 until size) {
                        list.add(readMsg(schema.elementSchema, depth + 1))
                    }
                    list
                }
            }
        }

        // Check Magic
        requireRemaining(4)
        val magic = String(bytes, offset, 2, Charsets.UTF_8)
        offset += 2
        require(magic == "RR") { "Invalid RoadRunner log magic" }
        var logRevision = readShort().toInt()
        require(logRevision == 0 || logRevision == 1) { "Unsupported RoadRunner revision: $logRevision" }
        val keyIDs = mutableMapOf<Int, String>()
        val keySchemas = mutableMapOf<Int, RRSchema>()
        var firstRRTimestamp: Long? = null
        var lastTimestampMs = 0L
        var segmentOffsetMs = 0L
        var segmentHasTimestamp = false

        fun applyTimestamp(timestamp: Long) {
            require(timestamp >= 0L) { "RoadRunner timestamp is negative" }
            val origin = firstRRTimestamp ?: timestamp.also { firstRRTimestamp = it }
            require(timestamp >= origin) { "RoadRunner timestamps moved backwards" }
            val relativeMs = (timestamp - origin) / 1_000_000L
            require(relativeMs <= Long.MAX_VALUE - segmentOffsetMs) { "RoadRunner timestamp overflow" }
            lastTimestampMs = segmentOffsetMs + relativeMs
            segmentHasTimestamp = true
        }

        fun flatten(
            prefix: String,
            value: Any,
            timestampMs: Long,
            frames: MutableList<TelemetryFrame>,
            depth: Int = 0
        ) {
            require(depth <= MAX_NESTING_DEPTH) { "RoadRunner telemetry nesting is too deep" }
            when (value) {
                is String -> {
                    // StringSchema and EnumSchema both decode to their exact textual value.
                    frames += TelemetryFrame(timestampMs, sessionId, prefix, 0.0, value)
                }
                is Boolean -> {
                    frames += TelemetryFrame(timestampMs, sessionId, prefix, if (value) 1.0 else 0.0)
                }
                is Number -> {
                    val numeric = value.toDouble()
                    require(numeric.isFinite()) { "Non-finite RoadRunner value for $prefix" }
                    frames += TelemetryFrame(timestampMs, sessionId, prefix, numeric)
                }
                is Map<*, *> -> {
                    val x = value["x"] as? Number
                    val y = value["y"] as? Number
                    val heading = value["heading"] as? Number
                    if (x != null && y != null && heading != null) {
                        require(x.toDouble().isFinite() && y.toDouble().isFinite() && heading.toDouble().isFinite()) {
                            "Non-finite RoadRunner pose for $prefix"
                        }
                        // Pose2d: convert inches to meters
                        frames += TelemetryFrame(timestampMs, sessionId, "$prefix/x", x.toDouble() * 0.0254)
                        frames += TelemetryFrame(timestampMs, sessionId, "$prefix/y", y.toDouble() * 0.0254)
                        frames += TelemetryFrame(timestampMs, sessionId, "$prefix/heading", heading.toDouble())
                    } else {
                        for ((k, v) in value) {
                            if (k is String && v != null) {
                                flatten("$prefix/$k", v, timestampMs, frames, depth + 1)
                            }
                        }
                    }
                }
                is List<*> -> {
                    for (i in value.indices) {
                        val v = value[i]
                        if (v != null) {
                            flatten("$prefix[$i]", v, timestampMs, frames, depth + 1)
                        }
                    }
                }
            }
        }

        while (offset < bytes.size) {
            // Check for concatenated logs
            if (offset + 2 <= bytes.size && String(bytes, offset, 2, Charsets.UTF_8) == "RR") {
                offset += 2
                logRevision = readShort().toInt()
                require(logRevision == 0 || logRevision == 1) {
                    "Unsupported concatenated RoadRunner revision: $logRevision"
                }
                keyIDs.clear()
                keySchemas.clear()
                if (segmentHasTimestamp) {
                    require(lastTimestampMs < Long.MAX_VALUE) { "RoadRunner segment timeline overflow" }
                    segmentOffsetMs = lastTimestampMs + 1L
                }
                firstRRTimestamp = null
                lastTimestampMs = segmentOffsetMs
                segmentHasTimestamp = false
                continue
            }

            val type = readInt()
            when (type) {
                0 -> {
                    require(keyIDs.size < MAX_KEYS) { "RoadRunner log declares too many keys" }
                    val keyID = keyIDs.size
                    val keyName = readString()
                    require(keyName.isNotBlank()) { "RoadRunner key name is blank" }
                    val schema = readSchema()
                    keyIDs[keyID] = keyName
                    keySchemas[keyID] = schema
                    if (logRevision == 0) {
                        val metadataBytes = 4 * arraySchemaCount(schema)
                        requireRemaining(metadataBytes)
                        offset += metadataBytes
                    }
                }
                1 -> {
                    val keyID = readInt()
                    val key = requireNotNull(keyIDs[keyID]) { "RoadRunner update references unknown key $keyID" }
                    val schema = requireNotNull(keySchemas[keyID]) { "RoadRunner key $keyID has no schema" }
                    val msg = readMsg(schema)

                    when {
                        (key == "OPMODE_PRE_INIT" || key == "OPMODE_PRE_START" || key == "OPMODE_POST_STOP" || key == "TIMESTAMP") && msg is Long -> {
                            applyTimestamp(msg)
                        }
                        msg is Map<*, *> -> {
                            (msg["timestamp"] as? Long)?.let { timestamp ->
                                applyTimestamp(timestamp)
                            }
                        }
                    }

                    // Validate and materialize the complete value before committing any of its
                    // fields. A bad nested value cannot leave a valid-looking partial record.
                    val recordFrames = mutableListOf<TelemetryFrame>()
                    flatten(key, msg, lastTimestampMs, recordFrames)
                    for (frame in recordFrames) batcher.add(frame)
                }
                else -> throw IllegalArgumentException("Unknown RoadRunner record type: $type")
            }
        }
    }

    private companion object {
        const val MAX_LOG_BYTES = 256L * 1024L * 1024L
        const val MAX_STRING_BYTES = 1024 * 1024
        const val MAX_SCHEMA_NODES = 10_000
        const val MAX_STRUCT_FIELDS = 4_096
        const val MAX_ENUM_CONSTANTS = 4_096
        const val MAX_ARRAY_ELEMENTS = 1_000_000
        const val MAX_DECODED_VALUES = 2_000_000
        const val MAX_NESTING_DEPTH = 64
        const val MAX_KEYS = 65_536
    }
}
