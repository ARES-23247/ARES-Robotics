package com.areslib.routine

import kotlin.math.round

/**
 * Builds and validates an [AutonomousCatalogDocument] using a concise, text-first Kotlin DSL.
 *
 * ### Example
 * ```kotlin
 * val autos = autonomousCatalog("season-2026", defaultEntryId = "red-basket") {
 *     entry(
 *         id = "red-basket",
 *         routineId = "score-preload",
 *         xMeters = -0.9,
 *         yMeters = 1.2,
 *         headingDegrees = 90.0,
 *         alliance = RoutineAlliance.RED,
 *     )
 * }
 * ```
 */
fun autonomousCatalog(
    projectId: String,
    revision: Int = 1,
    defaultEntryId: String? = null,
    block: AutonomousCatalogBuilder.() -> Unit,
): AutonomousCatalogDocument = AutonomousCatalogBuilder(
    projectId = projectId,
    revision = revision,
    defaultEntryId = defaultEntryId,
).apply(block).build()

@DslMarker
annotation class AutonomousCatalogDsl

@AutonomousCatalogDsl
class AutonomousCatalogBuilder internal constructor(
    private val projectId: String,
    var revision: Int,
    var defaultEntryId: String?,
) {
    private val entries = mutableListOf<AutonomousCatalogEntry>()

    /**
     * Declares an autonomous selector choice binding a trigger-neutral [routineId] to a starting
     * field pose and alliance mirroring policy.
     */
    fun entry(
        id: String,
        routineId: String,
        xMeters: Double = 0.0,
        yMeters: Double = 0.0,
        headingDegrees: Double = 0.0,
        displayName: String = defaultAutoEntryDisplayName(id),
        description: String? = null,
        alliance: RoutineAlliance = RoutineAlliance.RED,
        mirrorForOppositeAlliance: Boolean = true,
        sortOrder: Int = entries.size,
        enabled: Boolean = true,
        makeDefault: Boolean = false,
    ): AutonomousCatalogEntry {
        val built = AutonomousCatalogEntry(
            entryId = id,
            displayName = displayName,
            description = description,
            routineId = routineId,
            startingPose = RoutinePose(xMeters, yMeters, Math.toRadians(headingDegrees)),
            authoredAlliance = alliance,
            mirrorForOppositeAlliance = mirrorForOppositeAlliance,
            sortOrder = sortOrder,
            enabled = enabled,
        )
        entries += built
        if (makeDefault || (defaultEntryId == null && enabled && entries.count { it.enabled } == 1)) {
            defaultEntryId = id
        }
        return built
    }

    internal fun build(): AutonomousCatalogDocument {
        val doc = AutonomousCatalogDocument(
            projectId = projectId,
            revision = revision,
            defaultEntryId = defaultEntryId,
            entries = entries.toList(),
        )
        val errors = validateAutonomousCatalog(doc).filter { it.severity == RoutineValidationSeverity.ERROR }
        require(errors.isEmpty()) {
            errors.joinToString("; ") { "${it.path}: ${it.message}" }
        }
        return doc
    }
}

/**
 * Renders this canonical [AutonomousCatalogDocument] into idiomatic, copy-pasteable Kotlin Text DSL
 * code ([autonomousCatalog]).
 */
fun AutonomousCatalogDocument.toKotlinDsl(): String = buildString {
    val headerArgs = buildList {
        add(projectId.quote())
        if (revision != 1) add("revision = $revision")
        if (defaultEntryId != null) add("defaultEntryId = ${defaultEntryId.quote()}")
    }
    appendLine("autonomousCatalog(${headerArgs.joinToString(", ")}) {")
    entries.forEachIndexed { idx, e ->
        val deg = round(Math.toDegrees(e.startingPose.headingRadians) * 100.0) / 100.0
        val defaultName = defaultAutoEntryDisplayName(e.entryId)
        val args = buildList {
            add("id = ${e.entryId.quote()}")
            add("routineId = ${e.routineId.quote()}")
            add("xMeters = ${e.startingPose.xMeters}")
            add("yMeters = ${e.startingPose.yMeters}")
            add("headingDegrees = $deg")
            if (e.displayName != defaultName && e.displayName.isNotBlank()) {
                add("displayName = ${e.displayName.quote()}")
            }
            if (!e.description.isNullOrBlank()) add("description = ${e.description.quote()}")
            if (e.authoredAlliance != RoutineAlliance.RED) {
                add("alliance = RoutineAlliance.${e.authoredAlliance.name}")
            }
            if (!e.mirrorForOppositeAlliance) add("mirrorForOppositeAlliance = false")
            if (e.sortOrder != idx) add("sortOrder = ${e.sortOrder}")
            if (!e.enabled) add("enabled = false")
        }
        appendLine("    entry(${args.joinToString(", ")})")
    }
    append("}")
}

private fun defaultAutoEntryDisplayName(id: String): String =
    id.trim()
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .split(Regex("[^A-Za-z0-9]+"))
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token.lowercase().replaceFirstChar { it.titlecase() }
        }
        .ifBlank { id }

private fun String.quote(): String = buildString {
    append('"')
    for (ch in this@quote) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '$' -> append("\\$")
            else -> append(ch)
        }
    }
    append('"')
}
