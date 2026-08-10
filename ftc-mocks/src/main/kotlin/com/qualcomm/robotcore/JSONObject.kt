@file:Suppress("UNUSED_PARAMETER")
package org.json

/**
 * Class implementation for J S O N Object.
 *
 * Robotics framework control component.
 */
open class JSONObject {
    constructor()
    constructor(json: String)
    fun put(key: String, value: Any?): JSONObject = this
    fun get(key: String): Any = ""
    fun optString(key: String, defaultValue: String): String = ""
    override fun toString(): String = "{}"
}
