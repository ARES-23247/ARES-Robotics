@file:Suppress("UNUSED_PARAMETER")
package org.json

/**
 * Class implementation for [JSONObject].
 *
 * Robotics framework desktop mock providing lightweight JSON structure manipulation
 * without requiring the full external Android or `org.json` runtime library on desktop.
 *
 * Provides safe no-op and in-memory default behavior for telemetry serialization,
 * hardware configuration schemas, and simulator communication payloads.
 */
open class JSONObject {
    /** Constructs an empty JSON object. */
    constructor()

    /**
     * Constructs a JSON object parsed from the supplied [json] string representation.
     */
    constructor(json: String)

    /** Associates the given [value] with [key], returning this object for method chaining. */
    fun put(key: String, value: Any?): JSONObject = this

    /** Retrieves the value mapped by [key], returning an empty string default in mock mode. */
    fun get(key: String): Any = ""

    /** Retrieves the string value mapped by [key] or returns [defaultValue] when absent. */
    fun optString(key: String, defaultValue: String): String = ""

    /** Returns the serialized JSON string representation of this object. */
    override fun toString(): String = "{}"
}
