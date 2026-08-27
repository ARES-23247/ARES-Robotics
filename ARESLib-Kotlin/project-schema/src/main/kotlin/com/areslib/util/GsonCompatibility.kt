package com.areslib.util

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/**
 * Parses JSON through the API shared by the FTC SDK's bundled Gson and ARES's desktop Gson.
 *
 * Robot Controller supplies Gson at runtime, so core code must not link against newer-only
 * convenience methods such as `JsonParser.parseString`.
 */
@Suppress("DEPRECATION")
fun parseJsonElement(json: String): JsonElement = JsonParser().parse(json)
