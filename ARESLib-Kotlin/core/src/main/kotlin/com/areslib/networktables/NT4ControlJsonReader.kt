package com.areslib.networktables

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.StringReader
import java.math.BigDecimal

/** Reads control fields once at their actual object level without constructing a JSON object tree. */
internal object NT4ControlJsonReader {
    private val methods = setOf("publish", "unpublish", "subscribe", "unsubscribe", "announce", "unannounce", "properties", "setproperties")

    fun parse(text: String): List<NT4Json.ParsedMessage> {
        require(text.length <= NT4Json.MAX_JSON_CHARS) { "NT4 JSON frame is too large" }
        if (text.isBlank()) return emptyList()
        return read(text) { cursor ->
            val result = ArrayList<NT4Json.ParsedMessage>()
            if (cursor.reader.peek() == JsonToken.BEGIN_ARRAY) {
                cursor.visit(0); cursor.reader.beginArray()
                var count = 0
                while (cursor.reader.hasNext()) {
                    require(++count <= NT4Json.MAX_MESSAGES) { "NT4 JSON frame contains too many messages" }
                    cursor.message(1)?.let(result::add)
                }
                cursor.reader.endArray()
            } else cursor.message(0)?.let(result::add) // Preserve the existing single-object extension.
            result
        }
    }

    /** Public field helpers inspect direct members of one object in an inclusive character range. */
    fun field(text: String, name: String, start: Int, end: Int, kind: JsonToken): Any? {
        if (start < 0 || end >= text.length || start > end) return null
        require(text.length <= NT4Json.MAX_JSON_CHARS) { "NT4 JSON frame is too large" }
        val selected = if (start == 0 && end == text.lastIndex) text else text.substring(start, end + 1)
        return read(selected) { cursor ->
            var result: Any? = null
            if (cursor.reader.peek() == JsonToken.BEGIN_OBJECT) {
                cursor.members(0) { key ->
                    if (key != name) cursor.skip(1)
                    else result = when (kind) {
                        JsonToken.STRING -> cursor.string(1)
                        JsonToken.NUMBER -> cursor.integer(1)
                        JsonToken.BOOLEAN -> cursor.boolean(1)
                        JsonToken.BEGIN_ARRAY -> cursor.strings(1)
                        else -> null
                    }
                }
            } else cursor.skip(0)
            result
        }
    }

    @Suppress("DEPRECATION")
    private fun <T> read(text: String, block: (Cursor) -> T): T = try {
        JsonReader(StringReader(text)).use { reader ->
            // Legacy strict mode is available in the FTC SDK's older Gson runtime as well.
            reader.isLenient = false
            block(Cursor(reader)).also {
                require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing data after NT4 JSON frame" }
            }
        }
    } catch (failure: IOException) {
        throw IllegalArgumentException("Invalid NT4 JSON frame", failure)
    }

    private class Params {
        var name: String? = null
        var type: String? = null
        var pubUid: Int? = null
        var subUid: Int? = null
        var topics: List<String>? = null
        var prefix = false
        var validOptions = true
    }

    private class Cursor(val reader: JsonReader) {
        private var values = 0
        fun visit(depth: Int) {
            require(depth <= 32 && ++values <= 65_536) { "NT4 JSON nesting or value budget exceeded" }
        }

        fun members(depth: Int, accept: (String) -> Unit) {
            visit(depth); reader.beginObject()
            val names = HashSet<String>()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(names.add(name)) { "Duplicate NT4 JSON member '$name'" }
                accept(name)
            }
            reader.endObject()
        }

        fun message(depth: Int): NT4Json.ParsedMessage? {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) { skip(depth); return null }
            var method: String? = null
            var params: Params? = null
            members(depth) { name ->
                when (name) {
                    "method" -> method = string(depth + 1)
                    "params" -> params = params(depth + 1)
                    else -> skip(depth + 1)
                }
            }
            val command = method?.takeIf { it in methods } ?: return null
            val data = params ?: return null
            val valid = when (command) {
                "publish" -> data.name != null && data.pubUid != null && data.type != null
                "unpublish" -> data.pubUid != null
                "subscribe" -> data.subUid != null && data.topics != null && data.validOptions
                "unsubscribe" -> data.subUid != null
                else -> true
            }
            if (!valid) return null
            return NT4Json.ParsedMessage(command, data.name, data.pubUid, data.subUid, data.type,
                data.topics.orEmpty(), data.prefix)
        }

        private fun params(depth: Int): Params? {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) { skip(depth); return null }
            val result = Params()
            members(depth) { name ->
                when (name) {
                    "name" -> result.name = string(depth + 1)
                    "type" -> result.type = string(depth + 1)
                    "pubuid" -> result.pubUid = integer(depth + 1)
                    "subuid" -> result.subUid = integer(depth + 1)
                    "topics" -> result.topics = strings(depth + 1)
                    "options" -> {
                        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                            result.validOptions = false; skip(depth + 1)
                        } else members(depth + 1) { option ->
                            if (option == "prefix") {
                                val prefix = boolean(depth + 2)
                                result.validOptions = prefix != null
                                result.prefix = prefix ?: false
                            } else skip(depth + 2)
                        }
                    }
                    else -> skip(depth + 1)
                }
            }
            return result
        }

        fun string(depth: Int): String? {
            if (reader.peek() != JsonToken.STRING) { skip(depth); return null }
            visit(depth); return reader.nextString()
        }

        fun integer(depth: Int): Int? {
            if (reader.peek() != JsonToken.NUMBER) { skip(depth); return null }
            visit(depth)
            val value = reader.nextString()
            value.toIntOrNull()?.let { return it }
            // The fast path covers ordinary IDs; exact decimal/exponent forms must not truncate.
            if ('.' !in value && 'e' !in value && 'E' !in value) return null
            return try { BigDecimal(value).intValueExact() }
            catch (_: ArithmeticException) { null }
            catch (_: NumberFormatException) { null }
        }

        fun boolean(depth: Int): Boolean? {
            if (reader.peek() != JsonToken.BOOLEAN) { skip(depth); return null }
            visit(depth); return reader.nextBoolean()
        }

        fun strings(depth: Int): List<String>? {
            if (reader.peek() != JsonToken.BEGIN_ARRAY) { skip(depth); return null }
            visit(depth); reader.beginArray()
            val result = ArrayList<String>()
            var count = 0
            var valid = true
            while (reader.hasNext()) {
                require(++count <= NT4Json.MAX_TOPICS) { "NT4 subscription has too many topics" }
                val value = string(depth + 1)
                if (value == null) valid = false else result.add(value)
            }
            reader.endArray()
            return if (valid) result else null
        }

        fun skip(depth: Int) {
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> members(depth) { skip(depth + 1) }
                JsonToken.BEGIN_ARRAY -> {
                    visit(depth); reader.beginArray()
                    while (reader.hasNext()) skip(depth + 1)
                    reader.endArray()
                }
                else -> { visit(depth); reader.skipValue() }
            }
        }
    }
}
