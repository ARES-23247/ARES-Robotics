package com.areslib.codegen

/**
 * Strict, allocation-light argument decoder used by generated capability dispatchers.
 *
 * Routine files store arguments as strings so they remain portable across Android, desktop, and
 * RoboRIO. Generated code converts those strings at the boundary and never passes unvalidated
 * values into student-written robot code. Unknown keys, missing required values, malformed values,
 * and out-of-range numbers all fail closed with an actionable message.
 */
class CapabilityArgumentReader(
    private val capabilityKey: String,
    private val arguments: Map<String, String>,
    allowedKeys: Set<String>
) {
    init {
        val unexpected = arguments.keys - allowedKeys
        require(unexpected.isEmpty()) {
            "$capabilityKey received unknown arguments: ${unexpected.sorted().joinToString()}"
        }
    }

    fun requiredNumber(
        key: String,
        defaultValue: Double? = null,
        minimum: Double? = null,
        maximum: Double? = null
    ): Double = optionalNumber(key, defaultValue, minimum, maximum)
        ?: throw IllegalArgumentException("$capabilityKey requires numeric argument '$key'")

    fun optionalNumber(
        key: String,
        defaultValue: Double? = null,
        minimum: Double? = null,
        maximum: Double? = null
    ): Double? {
        val raw = arguments[key] ?: return defaultValue
        val value = raw.toDoubleOrNull()
            ?: throw IllegalArgumentException("$capabilityKey argument '$key' must be a number")
        require(value.isFinite()) { "$capabilityKey argument '$key' must be finite" }
        require(minimum == null || value >= minimum) {
            "$capabilityKey argument '$key' must be at least $minimum"
        }
        require(maximum == null || value <= maximum) {
            "$capabilityKey argument '$key' must be at most $maximum"
        }
        return value
    }

    fun requiredBoolean(key: String, defaultValue: Boolean? = null): Boolean =
        optionalBoolean(key, defaultValue)
            ?: throw IllegalArgumentException("$capabilityKey requires boolean argument '$key'")

    fun optionalBoolean(key: String, defaultValue: Boolean? = null): Boolean? {
        val raw = arguments[key] ?: return defaultValue
        return when {
            raw.equals("true", ignoreCase = true) -> true
            raw.equals("false", ignoreCase = true) -> false
            else -> throw IllegalArgumentException("$capabilityKey argument '$key' must be true or false")
        }
    }

    fun requiredText(key: String, defaultValue: String? = null): String =
        optionalText(key, defaultValue)
            ?: throw IllegalArgumentException("$capabilityKey requires text argument '$key'")

    fun optionalText(key: String, defaultValue: String? = null): String? = arguments[key] ?: defaultValue

    fun requiredEnum(key: String, options: Set<String>, defaultValue: String? = null): String =
        optionalEnum(key, options, defaultValue)
            ?: throw IllegalArgumentException("$capabilityKey requires enum argument '$key'")

    fun optionalEnum(
        key: String,
        options: Set<String>,
        defaultValue: String? = null
    ): String? {
        val value = arguments[key] ?: defaultValue ?: return null
        require(value in options) {
            "$capabilityKey argument '$key' must be one of: ${options.sorted().joinToString()}"
        }
        return value
    }
}
