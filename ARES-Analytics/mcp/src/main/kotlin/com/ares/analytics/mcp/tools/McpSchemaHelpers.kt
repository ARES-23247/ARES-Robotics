package com.ares.analytics.mcp.tools

import com.ares.analytics.shared.AppJsonPretty
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@Serializable
internal data class McpErrorEnvelope(
    val ok: Boolean = false,
    val error: String,
)

internal fun toolSchema(
    required: List<String> = emptyList(),
    propertiesBlock: JsonObjectBuilder.() -> Unit,
): ToolSchema = ToolSchema(
    properties = buildJsonObject(propertiesBlock),
    required = required,
)

internal fun JsonObjectBuilder.stringProp(description: String) {
    put("type", "string")
    put("description", description)
}

internal fun JsonObjectBuilder.enumProp(description: String, values: List<String>) {
    put("type", "string")
    put("description", description)
    putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
}

internal fun JsonObjectBuilder.boolProp(description: String) {
    put("type", "boolean")
    put("description", description)
}

internal fun JsonObjectBuilder.numberProp(description: String) {
    put("type", "number")
    put("description", description)
}

internal fun JsonObjectBuilder.stringArrayProp(description: String) {
    put("type", "array")
    put("description", description)
    putJsonObject("items") { put("type", "string") }
}

internal fun JsonObjectBuilder.anyArrayProp(description: String) {
    put("type", "array")
    put("description", description)
}

internal fun JsonObject?.reqStr(key: String): String =
    this?.get(key)?.jsonPrimitive?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Missing required string parameter '$key'.")

internal fun JsonObject?.optStr(key: String): String? =
    this?.get(key)?.jsonPrimitive?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

internal fun JsonObject?.optBool(key: String, default: Boolean): Boolean =
    this?.get(key)?.jsonPrimitive?.booleanOrNull ?: default

internal fun JsonObject?.optDouble(key: String): Double? =
    this?.get(key)?.jsonPrimitive?.doubleOrNull

internal fun JsonObject?.optInt(key: String): Int? =
    this?.get(key)?.jsonPrimitive?.intOrNull

internal fun JsonObject?.optStrList(key: String): List<String> =
    (this?.get(key) as? JsonArray)?.mapNotNull { el ->
        el.jsonPrimitive.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }.orEmpty()

internal fun JsonObject?.optObjList(key: String): List<JsonObject> =
    (this?.get(key) as? JsonArray)?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }.orEmpty()

internal inline fun <reified T> okJsonResult(payload: T): CallToolResult = CallToolResult(
    content = listOf(TextContent(text = AppJsonPretty.encodeToString(payload))),
    isError = false,
)

internal inline fun guardedTool(block: () -> CallToolResult): CallToolResult = try {
    block()
} catch (error: Throwable) {
    CallToolResult(
        content = listOf(
            TextContent(
                text = AppJsonPretty.encodeToString(
                    McpErrorEnvelope(error = error.message ?: error::class.simpleName.orEmpty()),
                ),
            ),
        ),
        isError = true,
    )
}
