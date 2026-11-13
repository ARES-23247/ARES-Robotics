package com.ares.analytics.domain.pit

import com.ares.analytics.shared.AppJson
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.isSimulationSessionTags
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

/** Pure domain match schedule item used for offline caching, CSV/JSON import, and queue projection. */
@Serializable
data class ScheduledMatchItem(
    val matchNumber: Int,
    val compLevel: String = "quals",
    val redAlliance: List<String> = emptyList(),
    val blueAlliance: List<String> = emptyList(),
    val scheduledTimeMs: Long? = null,
    val source: String = "manual",
)

/** Projected row in the competition Pit Match Queue with team-centric alliance & session status. */
data class TeamMatchQueueRow(
    val item: ScheduledMatchItem,
    val ourAlliance: String?,
    val partnerTeams: List<String>,
    val opponentTeams: List<String>,
    val linkedSessionIds: List<String>,
    val isNextUnplayedForTeam: Boolean,
)

/**
 * Pure CSV / JSON tournament schedule parser & team queue builder.
 *
 * Works offline without network access and supports CSV tables as well as TBA, TOA, FTC Events v2,
 * and native ARES schedule JSON payloads.
 */
object EventScheduleCodec {

    fun encodeJson(items: List<ScheduledMatchItem>): String = AppJson.encodeToString(sortMatches(items))

    fun parseJson(jsonText: String): List<ScheduledMatchItem> {
        val trimmed = jsonText.trim()
        if (trimmed.isEmpty()) return emptyList()
        val parsedNative = runCatching { AppJson.decodeFromString<List<ScheduledMatchItem>>(trimmed) }.getOrNull()
        if (parsedNative != null && parsedNative.any { it.matchNumber > 0 }) {
            return sortMatches(parsedNative.filter { it.matchNumber > 0 })
        }
        val element = runCatching { AppJson.parseToJsonElement(trimmed) }.getOrNull() ?: return emptyList()
        val array = when {
            element is JsonArray -> element
            element is JsonObject && element["schedule"] is JsonArray -> element["schedule"]!!.jsonArray
            element is JsonObject && element["matches"] is JsonArray -> element["matches"]!!.jsonArray
            else -> return emptyList()
        }
        return sortMatches(array.mapNotNull(::parseMatchObject))
    }

    fun parseCsv(csvText: String): List<ScheduledMatchItem> {
        val rawLines = csvText.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        if (rawLines.isEmpty()) return emptyList()

        val firstCells = splitCsvRow(rawLines.first())
        val hasHeader = firstCells.any { cell ->
            val lower = cell.lowercase(Locale.US)
            lower.contains("match") || lower.contains("red") || lower.contains("blue") || lower.contains("level")
        }

        val items = if (hasHeader && rawLines.size > 1) {
            parseHeaderedCsv(firstCells, rawLines.drop(1))
        } else {
            val dataLines = if (hasHeader) rawLines.drop(1) else rawLines
            dataLines.mapNotNull(::parseSimpleCsvRow)
        }
        return sortMatches(items)
    }

    fun buildQueueRows(
        items: List<ScheduledMatchItem>,
        teamId: String,
        sessions: List<Session>,
    ): List<TeamMatchQueueRow> {
        val normalizedTeam = normalizeTeamToken(teamId)
        val hardwareSessions = sessions.filterNot { it.tags.isSimulationSessionTags() }
        val sorted = sortMatches(items)

        val preliminary = sorted.map { item ->
            val redClean = item.redAlliance.map(::normalizeTeamToken).filter(String::isNotBlank)
            val blueClean = item.blueAlliance.map(::normalizeTeamToken).filter(String::isNotBlank)
            val ourAlliance = when {
                normalizedTeam.isBlank() -> null
                normalizedTeam in redClean -> "red"
                normalizedTeam in blueClean -> "blue"
                else -> null
            }
            val partners = when (ourAlliance) {
                "red" -> item.redAlliance.filter { normalizeTeamToken(it) != normalizedTeam }
                "blue" -> item.blueAlliance.filter { normalizeTeamToken(it) != normalizedTeam }
                else -> emptyList()
            }
            val opponents = when (ourAlliance) {
                "red" -> item.blueAlliance
                "blue" -> item.redAlliance
                else -> emptyList()
            }
            val linkedIds = hardwareSessions
                .filter { it.matchNumber == item.matchNumber }
                .map { it.sessionId }
            TeamMatchQueueRow(
                item = item,
                ourAlliance = ourAlliance,
                partnerTeams = partners,
                opponentTeams = opponents,
                linkedSessionIds = linkedIds,
                isNextUnplayedForTeam = false,
            )
        }

        val teamRows = preliminary.filter { it.ourAlliance != null }
        val nextMatch = if (teamRows.isNotEmpty()) {
            teamRows.firstOrNull { it.linkedSessionIds.isEmpty() }?.item
        } else {
            preliminary.firstOrNull { it.linkedSessionIds.isEmpty() }?.item
        }
        return preliminary.map { row ->
            if (nextMatch != null && row.item.compLevel == nextMatch.compLevel && row.item.matchNumber == nextMatch.matchNumber) {
                row.copy(isNextUnplayedForTeam = true)
            } else {
                row
            }
        }
    }

    fun normalizeTeamToken(raw: String): String =
        raw.trim()
            .removePrefix("frc")
            .removePrefix("FRC")
            .removePrefix("ftc")
            .removePrefix("FTC")
            .trim()
            .uppercase(Locale.US)

    private fun parseMatchObject(element: JsonElement): ScheduledMatchItem? {
        val obj = element as? JsonObject ?: return null
        val matchNumber = obj["matchNumber"]?.jsonPrimitive?.intOrNull
            ?: obj["match_number"]?.jsonPrimitive?.intOrNull
            ?: obj["match_name"]?.jsonPrimitive?.contentOrNull?.let(::parseMatchNumber)
            ?: return null
        if (matchNumber <= 0) return null

        val rawLevel = obj["compLevel"]?.jsonPrimitive?.contentOrNull
            ?: obj["comp_level"]?.jsonPrimitive?.contentOrNull
            ?: obj["tournamentLevel"]?.jsonPrimitive?.contentOrNull
            ?: obj["match_name"]?.jsonPrimitive?.contentOrNull
            ?: "quals"
        val compLevel = normalizeCompLevel(rawLevel)

        // Support native, TBA, TOA, and FIRST FTC Events team encodings.
        val red = mutableListOf<String>()
        val blue = mutableListOf<String>()
        obj["alliances"]?.let { alliancesEl ->
            val alliances = alliancesEl as? JsonObject ?: return@let
            (alliances["red"] as? JsonObject)?.get("team_keys")?.jsonArray?.forEach {
                red += normalizeTeamToken(it.jsonPrimitive.content)
            }
            (alliances["blue"] as? JsonObject)?.get("team_keys")?.jsonArray?.forEach {
                blue += normalizeTeamToken(it.jsonPrimitive.content)
            }
        }
        if (red.isEmpty() && blue.isEmpty()) {
            obj["participants"]?.let { partEl ->
                (partEl as? JsonArray)?.forEach { item ->
                    val p = item as? JsonObject ?: return@forEach
                    val teamKey = p["team_key"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val station = p["station"]?.jsonPrimitive?.intOrNull ?: 0
                    if (teamKey.isNotBlank()) {
                        if (station in 11..19) red += normalizeTeamToken(teamKey) else blue += normalizeTeamToken(teamKey)
                    }
                }
            }
        }
        if (red.isEmpty() && blue.isEmpty()) {
            obj["teams"]?.let { teamsEl ->
                (teamsEl as? JsonArray)?.forEach { item ->
                    val t = item as? JsonObject ?: return@forEach
                    val teamNum = t["teamNumber"]?.jsonPrimitive?.intOrNull?.toString()
                        ?: t["teamNumber"]?.jsonPrimitive?.contentOrNull
                        ?: return@forEach
                    val station = t["station"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (station.startsWith("Red", ignoreCase = true)) red += teamNum else blue += teamNum
                }
            }
        }

        val timeMs = obj["scheduledTimeMs"]?.jsonPrimitive?.longOrNull
            ?: obj["time"]?.jsonPrimitive?.longOrNull?.let { if (it < 10_000_000_000L) it * 1000L else it }
            ?: parseIsoTimeMs(obj["scheduled_time"]?.jsonPrimitive?.contentOrNull ?: obj["startTime"]?.jsonPrimitive?.contentOrNull)

        return ScheduledMatchItem(
            matchNumber = matchNumber,
            compLevel = compLevel,
            redAlliance = red.distinct(),
            blueAlliance = blue.distinct(),
            scheduledTimeMs = timeMs,
            source = "json",
        )
    }

    private fun parseHeaderedCsv(headers: List<String>, rows: List<String>): List<ScheduledMatchItem> {
        val indexByName = headers.mapIndexed { i, h -> h.trim().lowercase(Locale.US) to i }.toMap()
        val matchIdx = findHeader(indexByName, "match_number", "match", "match#", "match number")
        val levelIdx = findHeader(indexByName, "comp_level", "level", "type")
        val redGroupIdx = findHeader(indexByName, "red_alliance", "red")
        val blueGroupIdx = findHeader(indexByName, "blue_alliance", "blue")
        val redCols = listOf("red1", "red2", "red3", "r1", "r2", "r3").mapNotNull { indexByName[it] }
        val blueCols = listOf("blue1", "blue2", "blue3", "b1", "b2", "b3").mapNotNull { indexByName[it] }
        val timeIdx = findHeader(indexByName, "scheduled_time", "time", "start_time")

        return rows.mapNotNull { line ->
            val cells = splitCsvRow(line)
            val matchCell = matchIdx?.let { cells.getOrNull(it) }.orEmpty()
            val matchNum = parseMatchNumber(matchCell) ?: return@mapNotNull null
            val level = normalizeCompLevel(levelIdx?.let { cells.getOrNull(it) } ?: matchCell)
            val red = if (redCols.isNotEmpty()) {
                redCols.mapNotNull { cells.getOrNull(it)?.let(::normalizeTeamToken)?.takeIf(String::isNotEmpty) }
            } else {
                splitTeamList(redGroupIdx?.let { cells.getOrNull(it) })
            }
            val blue = if (blueCols.isNotEmpty()) {
                blueCols.mapNotNull { cells.getOrNull(it)?.let(::normalizeTeamToken)?.takeIf(String::isNotEmpty) }
            } else {
                splitTeamList(blueGroupIdx?.let { cells.getOrNull(it) })
            }
            ScheduledMatchItem(
                matchNumber = matchNum,
                compLevel = level,
                redAlliance = red,
                blueAlliance = blue,
                scheduledTimeMs = parseIsoTimeMs(timeIdx?.let { cells.getOrNull(it) }),
                source = "csv",
            )
        }
    }

    private fun parseSimpleCsvRow(line: String): ScheduledMatchItem? {
        val cells = splitCsvRow(line)
        if (cells.isEmpty()) return null
        val matchNum = parseMatchNumber(cells[0]) ?: return null
        val level = normalizeCompLevel(cells[0])
        val teamCells = cells.drop(1).map(::normalizeTeamToken).filter(String::isNotEmpty)
        val half = teamCells.size / 2
        return ScheduledMatchItem(
            matchNumber = matchNum,
            compLevel = level,
            redAlliance = teamCells.take(half),
            blueAlliance = teamCells.drop(half),
            source = "csv",
        )
    }

    private fun splitCsvRow(line: String): List<String> = line.split(',').map(String::trim)

    private fun splitTeamList(raw: String?): List<String> = raw
        .orEmpty()
        .split(' ', ';', '/', '|', '+')
        .map(::normalizeTeamToken)
        .filter(String::isNotEmpty)

    private fun findHeader(map: Map<String, Int>, vararg candidates: String): Int? =
        candidates.firstNotNullOfOrNull { map[it] }

    private fun parseMatchNumber(raw: String): Int? =
        Regex("\\d+").find(raw)?.value?.toIntOrNull()?.takeIf { it > 0 }

    private fun normalizeCompLevel(raw: String): String {
        val lower = raw.lowercase(Locale.US)
        return when {
            lower.contains("elim") || lower.contains("playoff") || lower.contains("semi") ||
                lower.contains("quarter") || lower.startsWith("qf") ||
                lower.startsWith("sf") || lower == "f" || lower.contains("final") -> "elims"
            lower.contains("prac") -> "practice"
            else -> "quals"
        }
    }

    private fun parseIsoTimeMs(raw: String?): Long? {
        val clean = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        clean.toLongOrNull()?.let { num -> return if (num < 10_000_000_000L) num * 1000L else num }
        return runCatching { Instant.parse(clean).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(clean).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun sortMatches(items: List<ScheduledMatchItem>): List<ScheduledMatchItem> {
        fun levelOrder(level: String): Int = when (level.lowercase(Locale.US)) {
            "practice" -> 0
            "quals" -> 1
            else -> 2
        }
        return items.sortedWith(compareBy({ levelOrder(it.compLevel) }, { it.matchNumber }))
    }
}
