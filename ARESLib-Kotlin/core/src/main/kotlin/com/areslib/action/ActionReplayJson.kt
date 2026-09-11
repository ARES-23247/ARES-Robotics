package com.areslib.action

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import java.util.ArrayDeque

/** FTC-compatible token parsing without JsonParser's implicit lenient mode or duplicate loss. */
internal object ActionReplayJson {
    @Suppress("DEPRECATION")
    fun parse(text: String): JsonElement = JsonReader(StringReader(text)).use { reader ->
        reader.isLenient = false
        val root = readValue(reader)
        val containers = ArrayDeque<JsonElement>()
        if (root.isJsonObject || root.isJsonArray) containers.push(root)
        // An explicit stack avoids adding application recursion on top of the reader's nesting.
        while (containers.isNotEmpty()) {
            val parent = containers.peek()
            if (!reader.hasNext()) {
                if (parent.isJsonObject) reader.endObject() else reader.endArray()
                containers.pop()
                continue
            }
            val name = if (parent.isJsonObject) reader.nextName() else null
            if (name != null && parent.asJsonObject.has(name)) {
                throw ActionReplayException("Duplicate JSON member '$name'")
            }
            val child = readValue(reader)
            if (name != null) parent.asJsonObject.add(name, child) else parent.asJsonArray.add(child)
            if (child.isJsonObject || child.isJsonArray) containers.push(child)
        }
        if (reader.peek() != JsonToken.END_DOCUMENT) throw ActionReplayException("Trailing data after action record")
        root
    }

    private fun readValue(reader: JsonReader): JsonElement = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> { reader.beginObject(); JsonObject() }
        JsonToken.BEGIN_ARRAY -> { reader.beginArray(); JsonArray() }
        JsonToken.STRING -> JsonPrimitive(reader.nextString())
        JsonToken.NUMBER -> JsonPrimitive(ExactJsonNumber(reader.nextString()))
        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
        JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
        else -> throw ActionReplayException("Expected a JSON value at ${reader.path}")
    }

    // Preserve the original spelling (including negative zero) while rejecting integer
    // truncation/overflow in both top-level fields and nested Gson numeric adapters.
    private class ExactJsonNumber(private val text: String) : Number() {
        override fun toByte(): Byte = BigDecimal(text).byteValueExact()
        override fun toShort(): Short = BigDecimal(text).shortValueExact()
        override fun toInt(): Int = BigDecimal(text).intValueExact()
        override fun toLong(): Long = BigDecimal(text).longValueExact()
        override fun toFloat(): Float = text.toFloat().also { require(it.isFinite()) { "Nonfinite JSON float" } }
        override fun toDouble(): Double = text.toDouble().also { require(it.isFinite()) { "Nonfinite JSON double" } }
        override fun toString(): String = text
    }
}
