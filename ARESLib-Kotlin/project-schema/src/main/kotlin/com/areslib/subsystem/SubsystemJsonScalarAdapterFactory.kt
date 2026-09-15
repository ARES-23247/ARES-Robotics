package com.areslib.subsystem

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/** Descriptor scalars must retain their JSON types; Gson coercions can change safety intent. */
internal object SubsystemJsonScalarAdapterFactory : TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        val raw = type.rawType
        val token = when (raw) {
            String::class.java -> JsonToken.STRING
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> JsonToken.BOOLEAN
            Int::class.javaPrimitiveType, Int::class.javaObjectType,
            Long::class.javaPrimitiveType, Long::class.javaObjectType,
            Double::class.javaPrimitiveType, Double::class.javaObjectType,
            Float::class.javaPrimitiveType, Float::class.javaObjectType -> JsonToken.NUMBER
            else -> if (raw.isEnum) JsonToken.STRING else return null
        }
        val delegate = gson.getDelegateAdapter(this, type)
        return object : TypeAdapter<T>() {
            override fun write(writer: JsonWriter, value: T?) = delegate.write(writer, value)

            override fun read(reader: JsonReader): T? {
                val path = reader.path
                if (reader.peek() == JsonToken.NULL) {
                    // These two enum types are nullable hardware mounting/connection metadata.
                    val nullableEnum = raw == SubsystemPneumaticsModuleType::class.java ||
                        raw == SubsystemHubFacingDirection::class.java
                    require(!raw.isPrimitive && (!raw.isEnum || nullableEnum)) { "$path cannot be null" }
                    return delegate.read(reader)
                }
                require(reader.peek() == token) { "$path must be a JSON ${token.name.lowercase()}" }
                // JsonTreeReader's integer accessors truncate fractional/out-of-range numbers.
                // Decode exact integer values here so parsing the tree keeps streaming semantics.
                @Suppress("UNCHECKED_CAST")
                val value = when (raw) {
                    Int::class.javaPrimitiveType, Int::class.javaObjectType -> reader.nextString().toBigDecimal().intValueExact() as T
                    Long::class.javaPrimitiveType, Long::class.javaObjectType -> reader.nextString().toBigDecimal().longValueExact() as T
                    else -> delegate.read(reader)
                }
                require(!raw.isEnum || value != null) { "$path contains an unknown ${raw.simpleName}" }
                require(value !is Double || value.isFinite()) { "$path must be finite" }
                require(value !is Float || value.isFinite()) { "$path must be finite" }
                return value
            }
        }
    }
}
