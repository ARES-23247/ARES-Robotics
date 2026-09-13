package com.areslib.networktables

/**
 * Streaming JSON parser and builder tailored specifically
 * for WPILib NetworkTables 4.1 JSON-RPC protocol frames (`announce`, `publish`, `unpublish`, `subscribe`).
 * Replaces heavy Jackson `ObjectMapper` AST nodes on the robot-side control path. Parsing is
 * bounded before allocating collections, and all emitted strings use JSON escaping.
 */
object NT4Json {

    data class ParsedMessage(
        val method: String,
        val topicName: String? = null,
        val pubUid: Int? = null,
        val subUid: Int? = null,
        val type: String? = null,
        val topics: List<String> = emptyList(),
        val prefix: Boolean = false
    )

    /**
     * Parses an incoming NT4 JSON text payload (which can be a single JSON object `{...}`
     * or a JSON array of objects `[{...}, {...}]`).
     */
    fun parseMessages(jsonText: String): List<ParsedMessage> = NT4ControlJsonReader.parse(jsonText)

    /** Reads a direct string member of the object within the inclusive source range. */
    fun extractStringField(json: String, fieldName: String, searchStart: Int, searchEnd: Int): String? =
        NT4ControlJsonReader.field(json, fieldName, searchStart, searchEnd, com.google.gson.stream.JsonToken.STRING) as? String

    /** Reads an exact 32-bit integer member; fractional, mistyped and out-of-range values return null. */
    fun extractIntField(json: String, fieldName: String, searchStart: Int, searchEnd: Int): Int? =
        NT4ControlJsonReader.field(json, fieldName, searchStart, searchEnd, com.google.gson.stream.JsonToken.NUMBER) as? Int

    /** Reads a direct boolean member; unrelated nested members are never used. */
    fun extractBooleanField(json: String, fieldName: String, searchStart: Int, searchEnd: Int): Boolean? =
        NT4ControlJsonReader.field(json, fieldName, searchStart, searchEnd, com.google.gson.stream.JsonToken.BOOLEAN) as? Boolean

    /** Reads a direct string-array member without treating brackets inside strings as delimiters. */
    @Suppress("UNCHECKED_CAST")
    fun extractStringArrayField(json: String, fieldName: String, searchStart: Int, searchEnd: Int): List<String> =
        NT4ControlJsonReader.field(json, fieldName, searchStart, searchEnd, com.google.gson.stream.JsonToken.BEGIN_ARRAY) as? List<String> ?: emptyList()

    /**
     * Constructs an NT4 `announce` JSON array payload for the provided entries.
     */
    fun buildAnnounceArray(entries: Collection<NT4Entry>): String {
        if (entries.isEmpty()) return "[]"
        val sb = java.lang.StringBuilder(entries.size * 128)
        sb.append("[")
        var first = true
        for (entry in entries) {
            if (!first) sb.append(",")
            first = false
            buildAnnounceObject(sb, entry, null)
        }
        sb.append("]")
        return sb.toString()
    }

    /**
     * Constructs a single NT4 `announce` JSON array payload for one entry.
     */
    fun buildAnnounceSingle(entry: NT4Entry, pubUid: Int? = null): String {
        val sb = java.lang.StringBuilder(160)
        sb.append("[")
        buildAnnounceObject(sb, entry, pubUid)
        sb.append("]")
        return sb.toString()
    }

    private fun buildAnnounceObject(sb: java.lang.StringBuilder, entry: NT4Entry, pubUid: Int?) {
        val cleanTopic = com.areslib.telemetry.TelemetryTopicNormalizer.toWireTopic(entry.topic)
        sb.append("{\"method\":\"announce\",\"params\":{")
        sb.append("\"name\":")
        appendJsonString(sb, cleanTopic)
        sb.append(',')
        sb.append("\"id\":").append(entry.id).append(",")
        sb.append("\"type\":")
        appendJsonString(sb, entry.value.typeString)
        sb.append(',')
        if (pubUid != null) sb.append("\"pubuid\":").append(pubUid).append(',')
        sb.append("\"properties\":{}")
        sb.append("}}")
    }

    /** Constructs one `unannounce` control message for a deleted topic. */
    fun buildUnannounceSingle(entry: NT4Entry): String {
        val cleanTopic = com.areslib.telemetry.TelemetryTopicNormalizer.toWireTopic(entry.topic)
        val sb = java.lang.StringBuilder(cleanTopic.length + 96)
        sb.append("[{\"method\":\"unannounce\",\"params\":{\"name\":")
        appendJsonString(sb, cleanTopic)
        sb.append(",\"id\":").append(entry.id).append("}}]")
        return sb.toString()
    }

    private fun appendJsonString(sb: java.lang.StringBuilder, value: String) {
        sb.append('"')
        for (character in value) {
            when (character) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (character < ' ') {
                    sb.append("\\u")
                    sb.append(character.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(character)
                }
            }
        }
        sb.append('"')
    }

    internal const val MAX_JSON_CHARS = 1_048_576
    internal const val MAX_MESSAGES = 1_024
    internal const val MAX_TOPICS = 256
}
