package com.areslib.drivetrain

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.util.Locale

/** Immutable, validated CANcoder offsets in rotations. */
data class SwerveOffsetData(
    val frontLeft: Double = 0.0,
    val frontRight: Double = 0.0,
    val backLeft: Double = 0.0,
    val backRight: Double = 0.0
) {
    init {
        require(frontLeft.isFinite() && frontRight.isFinite() && backLeft.isFinite() && backRight.isFinite()) {
            "All four swerve offsets must be finite"
        }
    }

    fun toJsonString(): String = String.format(
        Locale.US,
        "{\n  \"frontLeft\": %.7f,\n  \"frontRight\": %.7f,\n  \"backLeft\": %.7f,\n  \"backRight\": %.7f\n}",
        frontLeft,
        frontRight,
        backLeft,
        backRight
    )

    companion object {
        private val REQUIRED_KEYS = setOf("frontLeft", "frontRight", "backLeft", "backRight")

        /** Parses exactly four finite numeric keys. Missing, duplicate-shim, or unknown data fails. */
        @Suppress("DEPRECATION")
        fun fromJsonString(json: String): SwerveOffsetData {
            require(json.isNotBlank()) { "Swerve offset JSON must not be blank" }
            require(json.length <= MAX_JSON_CHARS) { "Swerve offset JSON exceeds $MAX_JSON_CHARS characters" }
            val values = HashMap<String, Double>(REQUIRED_KEYS.size)
            try {
                JsonReader(StringReader(json)).use { reader ->
                    reader.isLenient = false
                    require(reader.peek() == JsonToken.BEGIN_OBJECT) { "Swerve offsets must be a JSON object" }
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        require(key in REQUIRED_KEYS) { "Unknown swerve offset key '$key'" }
                        require(!values.containsKey(key)) { "Duplicate swerve offset key '$key'" }
                        require(reader.peek() == JsonToken.NUMBER) { "Swerve offset '$key' must be numeric" }
                        val value = reader.nextDouble()
                        require(value.isFinite()) { "Swerve offset '$key' must be finite" }
                        values[key] = value
                    }
                    reader.endObject()
                    require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing data after swerve offsets" }
                }
            } catch (failure: IllegalArgumentException) {
                throw failure
            } catch (failure: Exception) {
                throw IllegalArgumentException("Malformed swerve offset JSON", failure)
            }
            require(values.keys == REQUIRED_KEYS) { "Swerve offsets require exactly ${REQUIRED_KEYS.sorted()}" }

            return SwerveOffsetData(
                frontLeft = values.getValue("frontLeft"),
                frontRight = values.getValue("frontRight"),
                backLeft = values.getValue("backLeft"),
                backRight = values.getValue("backRight")
            )
        }

        private const val MAX_JSON_CHARS = 16_384
    }
}
