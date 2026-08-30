package com.areslib.routine

import com.google.gson.GsonBuilder
import com.areslib.util.sha256Hex

const val ARES_AUTONOMOUS_CATALOG_SCHEMA_VERSION: Int = 1

enum class RoutineAlliance { RED, BLUE }

/**
 * One student-facing autonomous choice.
 *
 * The routine stays reusable and trigger-neutral; this entry supplies match-only start metadata.
 * When [mirrorForOppositeAlliance] is true, the platform's field model mirrors [startingPose] from
 * [authoredAlliance]. This avoids baking FTC/FRC field geometry into the routine format.
 */
data class AutonomousCatalogEntry(
    val entryId: String,
    val displayName: String,
    val description: String? = null,
    val routineId: String,
    val startingPose: RoutinePose,
    val authoredAlliance: RoutineAlliance = RoutineAlliance.RED,
    val mirrorForOppositeAlliance: Boolean = true,
    val sortOrder: Int = 0,
    val enabled: Boolean = true
)

/** Versioned list rendered by both the Analytics selector and the robot-side INIT selector. */
data class AutonomousCatalogDocument(
    val schemaVersion: Int = ARES_AUTONOMOUS_CATALOG_SCHEMA_VERSION,
    val projectId: String,
    val revision: Int = 1,
    val defaultEntryId: String? = null,
    val entries: List<AutonomousCatalogEntry>
)

fun validateAutonomousCatalog(
    document: AutonomousCatalogDocument,
    routineIds: Set<String> = emptySet()
): List<RoutineValidationIssue> {
    val issues = mutableListOf<RoutineValidationIssue>()
    val keyPattern = Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")
    if (document.schemaVersion != ARES_AUTONOMOUS_CATALOG_SCHEMA_VERSION) {
        issues += autoIssue("catalog", "unsupported_schema", "Unsupported autonomous catalog schema ${document.schemaVersion}")
    }
    if (!document.projectId.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) {
        issues += autoIssue("projectId", "invalid_project_id", "Project ID is not filesystem safe")
    }
    if (document.revision < 1) issues += autoIssue("revision", "invalid_revision", "Revision must be at least 1")
    val entryIds = linkedSetOf<String>()
    document.entries.forEachIndexed { index, entry ->
        val path = "entries[$index]"
        if (!entry.entryId.matches(keyPattern)) {
            issues += autoIssue(path, "invalid_entry_id", "Entry ID '${entry.entryId}' is not stable")
        } else if (!entryIds.add(entry.entryId)) {
            issues += autoIssue(path, "duplicate_entry", "Entry '${entry.entryId}' is duplicated")
        }
        if (entry.displayName.isBlank()) issues += autoIssue(path, "missing_name", "Entry name is required")
        if (!entry.routineId.matches(keyPattern)) {
            issues += autoIssue(path, "invalid_routine_id", "Routine ID '${entry.routineId}' is not stable")
        } else if (routineIds.isNotEmpty() && entry.routineId !in routineIds) {
            issues += autoIssue(path, "unknown_routine", "Unknown routine '${entry.routineId}'")
        }
        if (!entry.startingPose.xMeters.isFinite() || !entry.startingPose.yMeters.isFinite() ||
            !entry.startingPose.headingRadians.isFinite()
        ) {
            issues += autoIssue(path, "non_finite_pose", "Starting pose must contain finite values")
        }
    }
    document.defaultEntryId?.let { defaultId ->
        if (defaultId !in entryIds) {
            issues += autoIssue("defaultEntryId", "unknown_default", "Default entry '$defaultId' does not exist")
        } else if (document.entries.first { it.entryId == defaultId }.enabled.not()) {
            issues += autoIssue("defaultEntryId", "disabled_default", "Default autonomous entry is disabled")
        }
    }
    if (document.entries.none { it.enabled }) {
        issues += RoutineValidationIssue(
            RoutineValidationSeverity.WARNING,
            document.projectId,
            "entries",
            "no_enabled_autos",
            "No autonomous choices are enabled; the robot will safely do nothing"
        )
    }
    return issues
}

private fun autoIssue(path: String, code: String, message: String) = RoutineValidationIssue(
    RoutineValidationSeverity.ERROR,
    "autonomous-catalog",
    path,
    code,
    message
)

object AutonomousCatalogCodec {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun encode(document: AutonomousCatalogDocument): String {
        val canonical = document.canonicalized()
        requireValid(canonical)
        return gson.toJson(canonical)
    }

    fun decode(json: String): AutonomousCatalogDocument {
        val document = try {
            gson.fromJson(json, AutonomousCatalogDocument::class.java)
        } catch (error: Exception) {
            throw IllegalArgumentException("Autonomous catalog is not valid JSON: ${error.message}", error)
        } ?: throw IllegalArgumentException("Autonomous catalog is empty")
        try {
            requireValid(document)
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("Autonomous catalog is missing or contains invalid fields", error)
        }
        return document.canonicalized()
    }

    fun contentHash(document: AutonomousCatalogDocument): String = sha256Hex(encode(document))

    private fun requireValid(document: AutonomousCatalogDocument) {
        val errors = validateAutonomousCatalog(document).filter { it.severity == RoutineValidationSeverity.ERROR }
        require(errors.isEmpty()) { errors.joinToString(separator = "; ") { it.message } }
    }
}

private fun AutonomousCatalogDocument.canonicalized(): AutonomousCatalogDocument = copy(
    entries = entries.sortedWith(compareBy<AutonomousCatalogEntry> { it.sortOrder }.thenBy { it.entryId })
)
