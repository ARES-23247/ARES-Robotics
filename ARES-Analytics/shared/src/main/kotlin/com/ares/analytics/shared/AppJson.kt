package com.ares.analytics.shared

import kotlinx.serialization.json.Json

/** Shared JSON policy for robot, dashboard, and persisted workspace data. */
val AppJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

/** [AppJson] with stable human-readable output for files maintained by users. */
val AppJsonPretty = Json(AppJson) { prettyPrint = true }
