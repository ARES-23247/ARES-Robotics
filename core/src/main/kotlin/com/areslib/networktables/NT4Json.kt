package com.areslib.networktables

/**
 * Lightweight, zero-allocation micro JSON parser and builder tailored specifically
 * for WPILib NetworkTables 4.1 JSON-RPC protocol frames (`announce`, `publish`, `unpublish`, `subscribe`).
 * Replaces heavy Jackson `ObjectMapper` AST nodes to maintain Zero-GC compliance.
 */
object NT4Json {

    data class ParsedMessage(
        val method: String,
        val topicName: String? = null,
        val pubUid: Int? = null,
        val type: String? = null,
        val topics: List<String> = emptyList()
    )

    /**
     * Parses an incoming NT4 JSON text payload (which can be a single JSON object `{...}`
     * or a JSON array of objects `[{...}, {...}]`).
     */
    fun parseMessages(jsonText: String): List<ParsedMessage> {
        val trimmed = jsonText.trim()
        if (trimmed.isEmpty()) return emptyList()

        return if (trimmed.startsWith("[")) {
            val objects = extractJsonObjectRanges(trimmed)
            objects.mapNotNull { parseSingleObject(trimmed, it.first, it.last) }
        } else if (trimmed.startsWith("{")) {
            val msg = parseSingleObject(trimmed, 0, trimmed.length - 1)
            if (msg != null) listOf(msg) else emptyList()
        } else {
            emptyList()
        }
    }

    private fun extractJsonObjectRanges(arrayJson: String): List<IntRange> {
        val list = ArrayList<IntRange>(4)
        var depth = 0
        var start = -1
        var inString = false
        var isEscaped = false

        for (i in arrayJson.indices) {
            val c = arrayJson[i]
            if (isEscaped) {
                isEscaped = false
                continue
            }
            if (c == '\\') {
                isEscaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (inString) continue

            if (c == '{') {
                if (depth == 0) start = i
                depth++
            } else if (c == '}') {
                depth--
                if (depth == 0 && start != -1) {
                    list.add(start..i)
                    start = -1
                }
            }
        }
        return list
    }

    private fun parseSingleObject(json: String, startIdx: Int, endIdx: Int): ParsedMessage? {
        val method = extractStringField(json, "method", startIdx, endIdx) ?: return null

        val paramsStart = json.indexOf("\"params\"", startIdx)
        val searchStart = if (paramsStart != -1 && paramsStart <= endIdx) paramsStart else startIdx

        val name = extractStringField(json, "name", searchStart, endIdx)
        val pubUid = extractIntField(json, "pubuid", searchStart, endIdx)
        val type = extractStringField(json, "type", searchStart, endIdx)
        val topics = extractStringArrayField(json, "topics", searchStart, endIdx)

        return ParsedMessage(
            method = method,
            topicName = name,
            pubUid = pubUid,
            type = type,
            topics = topics
        )
    }

    fun extractStringField(json: String, fieldName: String, searchStart: Int, searchEnd: Int): String? {
        val key = "\"$fieldName\""
        val keyIdx = json.indexOf(key, searchStart)
        if (keyIdx == -1 || keyIdx > searchEnd) return null

        val colonIdx = json.indexOf(':', keyIdx + key.length)
        if (colonIdx == -1 || colonIdx > searchEnd) return null

        val quoteStart = json.indexOf('"', colonIdx + 1)
        if (quoteStart == -1 || quoteStart > searchEnd) return null

        val quoteEnd = findClosingQuote(json, quoteStart + 1, searchEnd)
        if (quoteEnd == -1) return null

        return json.substring(quoteStart + 1, quoteEnd)
    }

    fun extractIntField(json: String, fieldName: String, searchStart: Int, searchEnd: Int): Int? {
        val key = "\"$fieldName\""
        val keyIdx = json.indexOf(key, searchStart)
        if (keyIdx == -1 || keyIdx > searchEnd) return null

        val colonIdx = json.indexOf(':', keyIdx + key.length)
        if (colonIdx == -1 || colonIdx > searchEnd) return null

        var idx = colonIdx + 1
        while (idx <= searchEnd && json[idx].isWhitespace()) idx++

        val sb = java.lang.StringBuilder()
        if (idx <= searchEnd && (json[idx] == '-' || json[idx] == '+')) {
            sb.append(json[idx])
            idx++
        }
        while (idx <= searchEnd && json[idx].isDigit()) {
            sb.append(json[idx])
            idx++
        }
        return sb.toString().toIntOrNull()
    }

    fun extractStringArrayField(json: String, fieldName: String, searchStart: Int, searchEnd: Int): List<String> {
        val key = "\"$fieldName\""
        val keyIdx = json.indexOf(key, searchStart)
        if (keyIdx == -1 || keyIdx > searchEnd) return emptyList()

        val bracketStart = json.indexOf('[', keyIdx + key.length)
        if (bracketStart == -1 || bracketStart > searchEnd) return emptyList()

        val bracketEnd = json.indexOf(']', bracketStart + 1)
        if (bracketEnd == -1 || bracketEnd > searchEnd) return emptyList()

        val result = ArrayList<String>()

        var idx = bracketStart + 1
        while (idx < bracketEnd) {
            val qStart = json.indexOf('"', idx)
            if (qStart == -1 || qStart >= bracketEnd) break
            val qEnd = findClosingQuote(json, qStart + 1, bracketEnd)
            if (qEnd == -1 || qEnd > bracketEnd) break
            result.add(json.substring(qStart + 1, qEnd))
            idx = qEnd + 1
        }
        return result
    }

    private fun findClosingQuote(s: String, startIdx: Int, endIdx: Int = s.length - 1): Int {
        var isEscaped = false
        for (i in startIdx..endIdx) {
            val c = s[i]
            if (isEscaped) {
                isEscaped = false
                continue
            }
            if (c == '\\') {
                isEscaped = true
                continue
            }
            if (c == '"') return i
        }
        return -1
    }

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
            buildAnnounceObject(sb, entry)
        }
        sb.append("]")
        return sb.toString()
    }

    /**
     * Constructs a single NT4 `announce` JSON array payload for one entry.
     */
    fun buildAnnounceSingle(entry: NT4Entry): String {
        val sb = java.lang.StringBuilder(160)
        sb.append("[")
        buildAnnounceObject(sb, entry)
        sb.append("]")
        return sb.toString()
    }

    private fun buildAnnounceObject(sb: java.lang.StringBuilder, entry: NT4Entry) {
        val cleanTopic = if (entry.topic.startsWith("/")) entry.topic else "/" + entry.topic
        sb.append("{\"method\":\"announce\",\"params\":{")
        sb.append("\"name\":\"").append(cleanTopic).append("\",")
        sb.append("\"id\":").append(entry.id).append(",")
        sb.append("\"type\":\"").append(entry.value.typeString).append("\",")
        sb.append("\"pubuid\":").append(entry.id).append(",")
        sb.append("\"properties\":{}")
        sb.append("}}")
    }
}
