package com.ares.analytics.domain.pit

import com.ares.analytics.shared.models.League
import java.util.Locale

/** Canonical tag prefix used to associate recorded sessions with a tracked physical battery pack. */
const val BATTERY_TAG_PREFIX = "bat:"

/**
 * Normalizes user-entered battery identifiers (e.g. `"bat:bat 01"`, `"#2"`) into a canonical uppercase token
 * (`"BAT-01"`, `"2"`), or returns `""` when blank/invalid.
 */
fun normalizeBatteryId(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val withoutPrefix = when {
        trimmed.startsWith("battery:", ignoreCase = true) -> trimmed.substringAfter(':')
        trimmed.startsWith("bat:", ignoreCase = true) -> trimmed.substringAfter(':')
        else -> trimmed
    }.trim()
    if (withoutPrefix.isEmpty()) return ""
    return withoutPrefix
        .uppercase(Locale.US)
        .replace(Regex("[^A-Z0-9_-]+"), "-")
        .trim('-')
        .take(24)
}

/** Formats a normalized `bat:<ID>` session tag, or returns `null` if [batteryId] is blank. */
fun formatBatterySessionTag(batteryId: String): String? {
    val normalized = normalizeBatteryId(batteryId)
    return if (normalized.isEmpty()) null else "$BATTERY_TAG_PREFIX$normalized"
}

/** Returns true if a session tag encodes a tracked battery identifier. */
fun isBatterySessionTag(tag: String): Boolean {
    val trimmed = tag.trim()
    return trimmed.startsWith("bat:", ignoreCase = true) ||
        trimmed.startsWith("battery:", ignoreCase = true)
}

/** Extracts the first canonical battery ID from [tags], or `null` when absent. */
fun extractBatteryIdFromTags(tags: Iterable<String>): String? =
    tags.firstOrNull(::isBatterySessionTag)?.let(::normalizeBatteryId)?.takeIf(String::isNotEmpty)

/**
 * Replaces any existing `bat:*` / `battery:*` tag in [tags] with the canonical tag for [batteryId]
 * (or removes battery tags altogether when [batteryId] is null/blank).
 */
fun replaceBatteryTag(tags: List<String>, batteryId: String?): List<String> {
    val cleaned = tags.filterNot(::isBatterySessionTag)
    val formatted = batteryId?.let(::formatBatterySessionTag)
    return if (formatted != null) (cleaned + formatted).distinct() else cleaned.distinct()
}

/** Current pit pool operational state for a physical battery pack. */
enum class BatteryPoolStatus(val label: String) {
    READY("Ready"),
    IN_ROBOT("In Robot"),
    CHARGING("Charging"),
    COOLING("Cooling"),
    PRACTICE_ONLY("Practice Only"),
    RETIRED("Retired");

    companion object {
        fun fromStored(raw: String?): BatteryPoolStatus =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: READY
    }
}

/** Automated competition readiness classification derived from internal resistance and voltage sag. */
enum class BatteryHealthGrade(val label: String) {
    COMPETITION_PRIME("Competition Prime"),
    HEALTHY("Healthy"),
    PRACTICE_ONLY("Practice / Degrading"),
    RETIRE_OR_INSPECT("Retire / High R_int"),
    UNTESTED("No Load Data")
}

/** Physical battery specification persisted in DuckDB (`battery_fleet`). */
data class BatterySpecRecord(
    val batteryId: String,
    val teamId: String,
    val seasonId: String,
    val robotId: String,
    val label: String = batteryId,
    val nominalVoltage: Double = 12.0,
    val capacityAh: Double = 3.0,
    val status: BatteryPoolStatus = BatteryPoolStatus.READY,
    val notes: String = "",
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
) {
    companion object {
        fun defaultForLeague(
            batteryId: String,
            teamId: String,
            seasonId: String,
            robotId: String,
            league: League,
            nowMs: Long = 0L,
        ): BatterySpecRecord {
            val (nominalV, capAh) = when (league) {
                League.FRC -> 12.0 to 18.0
                League.FTC -> 12.0 to 3.0
                League.XRP -> 6.0 to 2.0
            }
            val cleanId = normalizeBatteryId(batteryId).ifBlank { "BAT-01" }
            return BatterySpecRecord(
                batteryId = cleanId,
                teamId = teamId,
                seasonId = seasonId,
                robotId = robotId,
                label = cleanId,
                nominalVoltage = nominalV,
                capacityAh = capAh,
                status = BatteryPoolStatus.READY,
                createdAtMs = nowMs,
                updatedAtMs = nowMs,
            )
        }
    }
}

/** Point-in-time bench / Battery Beak measurement persisted in DuckDB (`battery_checkins`). */
data class BatteryCheckinRecord(
    val checkinId: String,
    val batteryId: String,
    val teamId: String,
    val seasonId: String,
    val robotId: String,
    val restingVoltage: Double? = null,
    val beakResistanceMilliohms: Double? = null,
    val stateOfChargePercent: Double? = null,
    val inspectorInitials: String = "",
    val notes: String = "",
    val recordedAtMs: Long,
)

/** Per-session electrical performance sample attributed to one battery pack. */
data class BatteryRunSample(
    val sessionId: String,
    val matchNumber: Int?,
    val createdAtMs: Long,
    val durationMs: Long,
    val minVoltage: Double?,
    val internalResistanceMilliohms: Double?,
    val isSimulation: Boolean,
)

/** Full synthesized health dossier for one tracked battery pack. */
data class BatteryHealthSummary(
    val batteryId: String,
    val label: String,
    val nominalVoltage: Double,
    val capacityAh: Double,
    val status: BatteryPoolStatus,
    val grade: BatteryHealthGrade,
    val hardwareCycleCount: Int,
    val simulationRunCount: Int,
    val latestRestingVoltage: Double?,
    val latestBeakMilliohms: Double?,
    val medianTelemetryMilliohms: Double?,
    val latestTelemetryMilliohms: Double?,
    val resistanceSlopeMilliohmsPerRun: Double?,
    val worstMinVoltage: Double?,
    val latestMinVoltage: Double?,
    val lastUsedAtMs: Long?,
    val recommendation: String,
    val recentRuns: List<BatteryRunSample>,
    val isDiscoveredFromTagsOnly: Boolean = false,
)
