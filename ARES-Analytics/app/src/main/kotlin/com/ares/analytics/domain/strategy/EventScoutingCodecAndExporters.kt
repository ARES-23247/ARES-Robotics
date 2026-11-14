package com.ares.analytics.domain.strategy

import com.ares.analytics.shared.AppJson
import com.ares.analytics.shared.models.League
import java.time.OffsetDateTime
import java.util.Locale
import kotlinx.serialization.json.*

/**
 * Serialization, multi-scout lossless merge, TBA/FIRST FTC/TOA JSON score ingestion,
 * CSV round-trip, and printable 1-page competition-floor exporters for Scouting & Picklists.
 */
object EventScoutingCodecAndExporters {

    private val prettyJson = Json(AppJson) {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeBundleJson(bundle: EventScoutingBundle): String =
        prettyJson.encodeToString(EventScoutingBundle.serializer(), bundle)

    fun decodeBundleOrExternalJson(
        rawText: String,
        fallbackLeague: League,
        fallbackEventCode: String = "",
    ): EventScoutingBundle {
        val trimmed = rawText.trimStart('\uFEFF').trim()
        if (trimmed.isEmpty()) return EventScoutingBundle(eventCode = fallbackEventCode, league = fallbackLeague)

        val element = runCatching { AppJson.parseToJsonElement(trimmed) }.getOrNull()
        if (element is JsonObject && (
                "matchResults" in element ||
                    "pitProfiles" in element ||
                    "standObservations" in element ||
                    "picklistOverrides" in element
                )
        ) {
            return runCatching { AppJson.decodeFromJsonElement(EventScoutingBundle.serializer(), element) }
                .getOrElse { EventScoutingBundle(eventCode = fallbackEventCode, league = fallbackLeague) }
        }

        val parsedMatches = parseExternalMatchResultsJson(trimmed, fallbackLeague)
        require(parsedMatches.isNotEmpty()) {
            "Unrecognized scouting JSON payload: expected an EventScoutingBundle or TBA/FTC/TOA matches array."
        }
        return EventScoutingBundle(
            eventCode = fallbackEventCode,
            league = fallbackLeague,
            matchResults = parsedMatches,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    /**
     * Merges [incoming] scout updates into [local] without clobbering sibling scouts' stand observations
     * and preserving played match results and newer timestamps (`updatedAtMs`).
     */
    fun mergeBundles(
        local: EventScoutingBundle,
        incoming: EventScoutingBundle,
    ): Pair<EventScoutingBundle, MergeScoutingSummary> {
        val mergedMatches = LinkedHashMap<String, EventMatchResult>()
        local.matchResults.forEach { mergedMatches[it.normalizedKey()] = it }
        var matchUpdates = 0
        incoming.matchResults.forEach { inc ->
            val key = inc.normalizedKey()
            val cur = mergedMatches[key]
            val winner = when {
                cur == null -> inc
                !cur.isPlayed && inc.isPlayed -> inc
                cur.isPlayed && !inc.isPlayed -> cur
                inc.updatedAtMs >= cur.updatedAtMs -> inc.copy(
                    redAutoPoints = inc.redAutoPoints ?: cur.redAutoPoints,
                    blueAutoPoints = inc.blueAutoPoints ?: cur.blueAutoPoints,
                    redTeleopPoints = inc.redTeleopPoints ?: cur.redTeleopPoints,
                    blueTeleopPoints = inc.blueTeleopPoints ?: cur.blueTeleopPoints,
                    redEndgamePoints = inc.redEndgamePoints ?: cur.redEndgamePoints,
                    blueEndgamePoints = inc.blueEndgamePoints ?: cur.blueEndgamePoints,
                    scheduledTimeMs = inc.scheduledTimeMs ?: cur.scheduledTimeMs,
                )
                else -> cur
            }
            if (cur == null || winner != cur) matchUpdates++
            mergedMatches[key] = winner
        }

        val mergedPit = LinkedHashMap<String, PitTeamProfile>()
        local.pitProfiles.forEach { p ->
            val clean = normalizeTeamNumber(p.teamNumber)
            if (clean.isNotBlank()) mergedPit[clean] = p.copy(teamNumber = clean)
        }
        var pitUpdates = 0
        incoming.pitProfiles.forEach { p ->
            val clean = normalizeTeamNumber(p.teamNumber)
            if (clean.isBlank()) return@forEach
            val norm = p.copy(teamNumber = clean)
            val cur = mergedPit[clean]
            if (cur == null || norm.updatedAtMs >= cur.updatedAtMs) {
                mergedPit[clean] = norm
                if (norm != cur) pitUpdates++
            }
        }

        val mergedStand = LinkedHashMap<String, StandMatchObservation>()
        local.standObservations.forEach { obs ->
            val clean = normalizeTeamNumber(obs.teamNumber)
            if (clean.isNotBlank()) {
                val n = obs.copy(teamNumber = clean)
                mergedStand[n.mergeIdentityKey()] = n
            }
        }
        var standUpdates = 0
        incoming.standObservations.forEach { obs ->
            val clean = normalizeTeamNumber(obs.teamNumber)
            if (clean.isBlank()) return@forEach
            val n = obs.copy(teamNumber = clean)
            val key = n.mergeIdentityKey()
            val cur = mergedStand[key]
            if (cur == null || n.updatedAtMs >= cur.updatedAtMs) {
                mergedStand[key] = n
                if (n != cur) standUpdates++
            }
        }

        val mergedOverrides = LinkedHashMap<String, TeamPicklistOverride>()
        local.picklistOverrides.forEach { o ->
            val clean = normalizeTeamNumber(o.teamNumber)
            if (clean.isNotBlank()) mergedOverrides[clean] = o.copy(teamNumber = clean)
        }
        var overrideUpdates = 0
        incoming.picklistOverrides.forEach { o ->
            val clean = normalizeTeamNumber(o.teamNumber)
            if (clean.isBlank()) return@forEach
            val norm = o.copy(teamNumber = clean)
            val cur = mergedOverrides[clean]
            if (cur == null || norm.updatedAtMs >= cur.updatedAtMs) {
                mergedOverrides[clean] = norm
                if (norm != cur) overrideUpdates++
            }
        }

        val finalBundle = EventScoutingBundle(
            schemaVersion = maxOf(local.schemaVersion, incoming.schemaVersion),
            eventCode = local.eventCode.ifBlank { incoming.eventCode },
            league = local.league,
            selectedPreset = local.selectedPreset,
            matchResults = mergedMatches.values.sortedWith(compareBy<EventMatchResult> { it.compLevel }.thenBy { it.matchNumber }),
            pitProfiles = mergedPit.values.sortedBy { it.teamNumber.toIntOrNull() ?: Int.MAX_VALUE },
            standObservations = mergedStand.values.sortedWith(compareBy<StandMatchObservation> { it.matchNumber }.thenBy { it.teamNumber }),
            picklistOverrides = mergedOverrides.values.sortedBy { it.teamNumber.toIntOrNull() ?: Int.MAX_VALUE },
            updatedAtMs = maxOf(local.updatedAtMs, incoming.updatedAtMs, System.currentTimeMillis()),
        )

        val authors = (
            incoming.pitProfiles.map { it.scoutAuthor } +
                incoming.standObservations.map { it.scoutAuthor }
            ).map(String::trim).filter(String::isNotBlank).toSet()

        return finalBundle to MergeScoutingSummary(
            mergedMatches = matchUpdates,
            mergedPitProfiles = pitUpdates,
            mergedStandObservations = standUpdates,
            mergedPicklistOverrides = overrideUpdates,
            distinctScoutAuthors = authors,
        )
    }

    /**
     * Parses TBA (`/matches` & `/matches/simple`), FIRST FTC Events v2 (`{"matches": [...]}`),
     * and The Orange Alliance (`/api/event/{key}/matches`) JSON score payloads.
     */
    fun parseExternalMatchResultsJson(
        rawJson: String,
        defaultLeague: League = League.FTC,
    ): List<EventMatchResult> {
        val trimmed = rawJson.trimStart('\uFEFF').trim()
        if (trimmed.isEmpty()) return emptyList()
        val root = runCatching { AppJson.parseToJsonElement(trimmed) }.getOrNull() ?: return emptyList()
        val array = when {
            root is JsonArray -> root
            root is JsonObject && root["matches"] is JsonArray -> root["matches"]!!.jsonArray
            root is JsonObject && root["schedule"] is JsonArray -> root["schedule"]!!.jsonArray
            else -> return emptyList()
        }

        val nowMs = System.currentTimeMillis()
        return array.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            when {
                "alliances" in obj -> parseTbaMatchObject(obj, nowMs)
                "scoreRedFinal" in obj || "tournamentLevel" in obj -> parseFtcEventsMatchObject(obj, nowMs)
                "participants" in obj || "red_score" in obj -> parseToaMatchObject(obj, nowMs)
                else -> runCatching { AppJson.decodeFromJsonElement(EventMatchResult.serializer(), obj) }.getOrNull()
            }
        }.sortedWith(compareBy<EventMatchResult> { it.compLevel }.thenBy { it.matchNumber })
    }

    private fun parseTbaMatchObject(obj: JsonObject, nowMs: Long): EventMatchResult? {
        val rawLevel = obj["comp_level"]?.jsonPrimitive?.contentOrNull ?: "qm"
        val compLevel = if (rawLevel == "qm") "quals" else "elims"
        val matchNumRaw = obj["match_number"]?.jsonPrimitive?.intOrNull ?: return null
        val setNum = obj["set_number"]?.jsonPrimitive?.intOrNull ?: 1
        val matchNum = if (rawLevel == "qm" || setNum <= 1) matchNumRaw else setNum * 100 + matchNumRaw
        val alliances = obj["alliances"]?.jsonObject ?: return null
        val redObj = alliances["red"]?.jsonObject
        val blueObj = alliances["blue"]?.jsonObject
        val redTeams = redObj?.get("team_keys")?.jsonArray?.map { normalizeTeamNumber(it.jsonPrimitive.content) }.orEmpty()
        val blueTeams = blueObj?.get("team_keys")?.jsonArray?.map { normalizeTeamNumber(it.jsonPrimitive.content) }.orEmpty()
        val redScore = redObj?.get("score")?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }
        val blueScore = blueObj?.get("score")?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }
        val bd = obj["score_breakdown"] as? JsonObject
        val redBd = bd?.get("red") as? JsonObject
        val blueBd = bd?.get("blue") as? JsonObject
        val redFoul = redBd?.get("foulPoints")?.jsonPrimitive?.intOrNull ?: 0
        val blueFoul = blueBd?.get("foulPoints")?.jsonPrimitive?.intOrNull ?: 0
        val timeMs = obj["actual_time"]?.jsonPrimitive?.longOrNull?.times(1000L)
            ?: obj["time"]?.jsonPrimitive?.longOrNull?.times(1000L)
        return EventMatchResult(
            matchNumber = matchNum,
            compLevel = compLevel,
            redTeams = redTeams,
            blueTeams = blueTeams,
            redTotalScore = redScore,
            blueTotalScore = blueScore,
            redFoulPointsAwarded = redFoul,
            blueFoulPointsAwarded = blueFoul,
            redAutoPoints = redBd?.get("autoPoints")?.jsonPrimitive?.intOrNull,
            blueAutoPoints = blueBd?.get("autoPoints")?.jsonPrimitive?.intOrNull,
            redTeleopPoints = redBd?.get("teleopPoints")?.jsonPrimitive?.intOrNull,
            blueTeleopPoints = blueBd?.get("teleopPoints")?.jsonPrimitive?.intOrNull,
            redEndgamePoints = redBd?.get("endGamePoints")?.jsonPrimitive?.intOrNull
                ?: redBd?.get("endgamePoints")?.jsonPrimitive?.intOrNull,
            blueEndgamePoints = blueBd?.get("endGamePoints")?.jsonPrimitive?.intOrNull
                ?: blueBd?.get("endgamePoints")?.jsonPrimitive?.intOrNull,
            scheduledTimeMs = timeMs,
            source = "tba",
            updatedAtMs = nowMs,
        )
    }

    private fun parseFtcEventsMatchObject(obj: JsonObject, nowMs: Long): EventMatchResult? {
        val matchNum = obj["matchNumber"]?.jsonPrimitive?.intOrNull ?: return null
        val levelRaw = obj["tournamentLevel"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val compLevel = if (levelRaw.contains("QUAL", ignoreCase = true) || levelRaw.isBlank()) "quals" else "elims"
        val redTeams = mutableListOf<String>()
        val blueTeams = mutableListOf<String>()
        obj["teams"]?.jsonArray?.forEach { t ->
            val tObj = t as? JsonObject ?: return@forEach
            val num = tObj["teamNumber"]?.jsonPrimitive?.contentOrNull?.let(::normalizeTeamNumber) ?: return@forEach
            val station = tObj["station"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (station.startsWith("Red", ignoreCase = true)) redTeams += num
            else if (station.startsWith("Blue", ignoreCase = true)) blueTeams += num
        }
        val redTotal = obj["scoreRedFinal"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }
        val blueTotal = obj["scoreBlueFinal"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }
        val redFoul = obj["scoreRedFoul"]?.jsonPrimitive?.intOrNull ?: 0
        val blueFoul = obj["scoreBlueFoul"]?.jsonPrimitive?.intOrNull ?: 0
        val timeMs = obj["actualStartTime"]?.jsonPrimitive?.contentOrNull?.let(::parseIsoTimestampMs)
            ?: obj["scheduledStartTime"]?.jsonPrimitive?.contentOrNull?.let(::parseIsoTimestampMs)
        return EventMatchResult(
            matchNumber = matchNum,
            compLevel = compLevel,
            redTeams = redTeams,
            blueTeams = blueTeams,
            redTotalScore = redTotal,
            blueTotalScore = blueTotal,
            redFoulPointsAwarded = redFoul,
            blueFoulPointsAwarded = blueFoul,
            redAutoPoints = obj["scoreRedAuto"]?.jsonPrimitive?.intOrNull,
            blueAutoPoints = obj["scoreBlueAuto"]?.jsonPrimitive?.intOrNull,
            scheduledTimeMs = timeMs,
            source = "ftc-events",
            updatedAtMs = nowMs,
        )
    }

    private fun parseToaMatchObject(obj: JsonObject, nowMs: Long): EventMatchResult? {
        val matchNum = obj["match_number"]?.jsonPrimitive?.intOrNull ?: return null
        val name = obj["match_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val compLevel = if (name.contains("Qual", ignoreCase = true) || name.isBlank()) "quals" else "elims"
        val redTeams = mutableListOf<String>()
        val blueTeams = mutableListOf<String>()
        obj["participants"]?.jsonArray?.forEach { p ->
            val pObj = p as? JsonObject ?: return@forEach
            val key = pObj["team_key"]?.jsonPrimitive?.contentOrNull?.let(::normalizeTeamNumber) ?: return@forEach
            val station = pObj["station"]?.jsonPrimitive?.intOrNull ?: 0
            if (station in 11..19) redTeams += key else if (station >= 20) blueTeams += key
        }
        return EventMatchResult(
            matchNumber = matchNum,
            compLevel = compLevel,
            redTeams = redTeams,
            blueTeams = blueTeams,
            redTotalScore = obj["red_score"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 },
            blueTotalScore = obj["blue_score"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 },
            redFoulPointsAwarded = obj["red_pen"]?.jsonPrimitive?.intOrNull ?: 0,
            blueFoulPointsAwarded = obj["blue_pen"]?.jsonPrimitive?.intOrNull ?: 0,
            redAutoPoints = obj["red_auto_pts"]?.jsonPrimitive?.intOrNull,
            blueAutoPoints = obj["blue_auto_pts"]?.jsonPrimitive?.intOrNull,
            redTeleopPoints = obj["red_tele_pts"]?.jsonPrimitive?.intOrNull,
            blueTeleopPoints = obj["blue_tele_pts"]?.jsonPrimitive?.intOrNull,
            redEndgamePoints = obj["red_end_pts"]?.jsonPrimitive?.intOrNull,
            blueEndgamePoints = obj["blue_end_pts"]?.jsonPrimitive?.intOrNull,
            scheduledTimeMs = obj["scheduled_time"]?.jsonPrimitive?.contentOrNull?.let(::parseIsoTimestampMs),
            source = "toa",
            updatedAtMs = nowMs,
        )
    }

    fun parseScoutingCsv(csvText: String, league: League = League.FTC): EventScoutingBundle {
        val lines = csvText.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        if (lines.size < 2) return EventScoutingBundle(league = league)
        val headers = splitCsvRow(lines.first()).map { it.trim().lowercase(Locale.US) }
        val nowMs = System.currentTimeMillis()
        val stand = mutableListOf<StandMatchObservation>()
        val matches = mutableListOf<EventMatchResult>()
        lines.drop(1).forEach { line ->
            val cols = splitCsvRow(line)
            val rowMap = headers.mapIndexed { i, h -> h to cols.getOrElse(i) { "" }.trim() }.toMap()
            if ("team" in rowMap && "match" in rowMap) {
                val team = normalizeTeamNumber(rowMap["team"].orEmpty())
                val matchNum = rowMap["match"]?.toIntOrNull()
                if (team.isNotBlank() && matchNum != null) {
                    val endgame = EndgameTier.entries.firstOrNull {
                        it.name.equals(rowMap["endgame"], ignoreCase = true)
                    } ?: EndgameTier.NONE
                    stand += StandMatchObservation(
                        teamNumber = team,
                        compLevel = rowMap["level"]?.takeIf(String::isNotBlank) ?: "quals",
                        matchNumber = matchNum,
                        autoAttempted = rowMap["auto_attempted"]?.toBooleanStrictOrNull() ?: true,
                        autoSucceeded = rowMap["auto_ok"]?.toBooleanStrictOrNull() ?: false,
                        estimatedCycles = rowMap["cycles"]?.toIntOrNull() ?: 0,
                        endgameAchieved = endgame,
                        defenseRating = rowMap["defense"]?.toIntOrNull()?.coerceIn(0, 5) ?: 0,
                        driverRating = rowMap["driver"]?.toIntOrNull()?.coerceIn(1, 5) ?: 3,
                        diedOrCommsLost = rowMap["died"]?.toBooleanStrictOrNull() ?: false,
                        notes = rowMap["notes"].orEmpty(),
                        scoutAuthor = rowMap["scout"]?.ifBlank { "CSV Scout" } ?: "CSV Scout",
                        updatedAtMs = nowMs,
                    )
                }
            } else if ("red_teams" in rowMap && "blue_teams" in rowMap && "match" in rowMap) {
                val matchNum = rowMap["match"]?.toIntOrNull() ?: return@forEach
                matches += EventMatchResult(
                    matchNumber = matchNum,
                    compLevel = rowMap["level"]?.ifBlank { "quals" } ?: "quals",
                    redTeams = rowMap["red_teams"].orEmpty().split(';', '|', ' ').map(::normalizeTeamNumber).filter(String::isNotBlank),
                    blueTeams = rowMap["blue_teams"].orEmpty().split(';', '|', ' ').map(::normalizeTeamNumber).filter(String::isNotBlank),
                    redTotalScore = rowMap["red_score"]?.toIntOrNull(),
                    blueTotalScore = rowMap["blue_score"]?.toIntOrNull(),
                    redFoulPointsAwarded = rowMap["red_foul"]?.toIntOrNull() ?: 0,
                    blueFoulPointsAwarded = rowMap["blue_foul"]?.toIntOrNull() ?: 0,
                    source = "csv",
                    updatedAtMs = nowMs,
                )
            }
        }
        return EventScoutingBundle(league = league, matchResults = matches, standObservations = stand, updatedAtMs = nowMs)
    }

    fun exportPicklistAndOprCsv(
        rows: List<RankedPicklistTeamRow>,
        oprSnapshot: EventOprSnapshot,
    ): String = buildString {
        appendLine("rank,team,tier,status,composite_score,np_opr,total_opr,dpr,ccwm,auto_opr,endgame_opr,drivetrain,auto_zone,disconnects,notes")
        rows.forEach { row ->
            val o = row.opr
            fun fmt(v: Double?): String = v?.let { String.format(Locale.US, "%.2f", it) } ?: ""
            appendLine(
                listOf(
                    row.displayRank.toString(),
                    row.teamNumber,
                    row.override.tier.shortBadge,
                    row.override.draftStatus.label,
                    String.format(Locale.US, "%.1f", row.compositePickScore),
                    fmt(o?.npOpr),
                    fmt(o?.totalOpr),
                    fmt(o?.dpr),
                    fmt(o?.ccwm),
                    fmt(o?.autoOpr),
                    fmt(o?.endgameOpr),
                    row.pitProfile?.drivetrain?.label.orEmpty(),
                    row.pitProfile?.preferredAutoZone?.label.orEmpty(),
                    (row.standSummary?.disconnectCount ?: 0).toString(),
                    row.override.coachNote.ifBlank { row.synergySummary },
                ).joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" },
            )
        }
    }

    fun buildPrintablePicklistMarkdown(
        eventLabel: String,
        ourTeam: String,
        preset: PicklistWeightPreset,
        confidence: OprSampleConfidence,
        rows: List<RankedPicklistTeamRow>,
    ): String = buildString {
        appendLine("# Alliance Selection Floor Sheet — $eventLabel")
        appendLine("- **Our Team**: `$ourTeam` | **Preset**: `${preset.label}` | **OPR Confidence**: `${confidence.label}`")
        appendLine()
        appendLine("| # | Team | Tier | Status | Score | NP-OPR | ccWM | Auto Zone Synergy | Notes / Reliability |")
        appendLine("| ---: | --- | --- | --- | ---: | ---: | ---: | --- | --- |")
        rows.forEach { r ->
            val np = r.opr?.npOpr?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
            val ccwm = r.opr?.ccwm?.let { String.format(Locale.US, "%+.1f", it) } ?: "—"
            val note = r.override.coachNote.ifBlank { r.pitProfile?.autoSummary ?: r.synergySummary }.take(60)
            appendLine(
                "| ${r.displayRank} | ${r.teamNumber} | ${r.override.tier.shortBadge} | ${r.override.draftStatus.label} | " +
                    "${String.format(Locale.US, "%.1f", r.compositePickScore)} | $np | $ccwm | ${r.autoZoneSynergyLabel} | $note |",
            )
        }
    }

    fun buildPrintablePicklistHtml(
        eventLabel: String,
        ourTeam: String,
        preset: PicklistWeightPreset,
        confidence: OprSampleConfidence,
        rows: List<RankedPicklistTeamRow>,
    ): String = buildString {
        appendLine("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">")
        appendLine("<title>Alliance Selection Picklist — ${escapeHtml(eventLabel)}</title>")
        appendLine(
            "<style>@page{size:letter portrait;margin:0.4in;}body{font-family:system-ui,-apple-system,sans-serif;margin:16px;color:#111827;background:#fff;}" +
                "h1{font-size:18px;margin:0 0 4px;} .meta{font-size:12px;color:#4b5563;margin-bottom:12px;}" +
                "table{width:100%;border-collapse:collapse;font-size:11px;}th,td{border:1px solid #d1d5db;padding:4px 6px;text-align:left;}" +
                "th{background:#f3f4f6;font-weight:700;}tr.picked{color:#6b7280;text-decoration:line-through;background:#f9fafb;}" +
                ".collision{color:#b45309;font-weight:600;}@media print{body{margin:0;}}</style></head><body>",
        )
        appendLine("<h1>Alliance Selection Picklist — ${escapeHtml(eventLabel)}</h1>")
        appendLine("<div class=\"meta\">Our Team: <strong>${escapeHtml(ourTeam)}</strong> · Preset: ${escapeHtml(preset.label)} · Model Confidence: ${escapeHtml(confidence.label)}</div>")
        appendLine("<table><thead><tr><th>#</th><th>Team</th><th>Tier</th><th>Status</th><th>Score</th><th>NP-OPR</th><th>ccWM</th><th>Auto Synergy</th><th>Notes</th></tr></thead><tbody>")
        rows.forEach { r ->
            val cls = if (r.override.draftStatus != AllianceDraftStatus.AVAILABLE) " class=\"picked\"" else ""
            val np = r.opr?.npOpr?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
            val ccwm = r.opr?.ccwm?.let { String.format(Locale.US, "%+.1f", it) } ?: "—"
            val synCls = if (r.hasAutoZoneCollision) " class=\"collision\"" else ""
            val note = r.override.coachNote.ifBlank { r.pitProfile?.autoSummary ?: r.synergySummary }
            appendLine(
                "<tr$cls><td>${r.displayRank}</td><td><strong>${escapeHtml(r.teamNumber)}</strong></td>" +
                    "<td>${escapeHtml(r.override.tier.shortBadge)}</td><td>${escapeHtml(r.override.draftStatus.label)}</td>" +
                    "<td>${String.format(Locale.US, "%.1f", r.compositePickScore)}</td><td>$np</td><td>$ccwm</td>" +
                    "<td$synCls>${escapeHtml(r.autoZoneSynergyLabel)}</td><td>${escapeHtml(note)}</td></tr>",
            )
        }
        appendLine("</tbody></table></body></html>")
    }

    fun buildMatchupBriefingMarkdown(report: MatchupBriefingReport, eventLabel: String): String = buildString {
        appendLine("# Pre-Match Strategy Briefing — ${report.compLevel.uppercase(Locale.US)} #${report.matchNumber} ($eventLabel)")
        appendLine("- **Projected NP Score**: Red `${String.format(Locale.US, "%.1f", report.projectedRedNpScore)}` vs Blue `${String.format(Locale.US, "%.1f", report.projectedBlueNpScore)}` (${report.confidence.label})")
        report.recordedResult?.takeIf { it.isPlayed }?.let { res ->
            appendLine("- **Official Result**: Red `${res.redTotalScore}` (`${res.redNonPenaltyScore()}` NP) — Blue `${res.blueTotalScore}` (`${res.blueNonPenaltyScore()}` NP)")
        }
        report.partnerAutoZoneWarning?.let { appendLine("- **WARNING**: $it") }
        appendLine()
        appendLine("## Talking Points")
        report.talkingPoints.forEach { appendLine("- $it") }
    }

    private fun parseIsoTimestampMs(raw: String): Long? =
        runCatching { OffsetDateTime.parse(raw.trim()).toInstant().toEpochMilli() }.getOrNull()

    private fun splitCsvRow(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"')
                    i++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    out += sb.toString()
                    sb.clear()
                }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    private fun escapeHtml(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
