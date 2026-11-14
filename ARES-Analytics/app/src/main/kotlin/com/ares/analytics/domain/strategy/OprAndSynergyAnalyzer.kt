package com.ares.analytics.domain.strategy

import com.ares.analytics.shared.models.League
import java.util.Locale
import kotlin.math.abs

enum class OprSampleConfidence(val label: String) {
    NONE("No Scored Matches"),
    EARLY_SAMPLE("Early Sample (Regularized)"),
    FULL_RANK("Full-Rank Least Squares"),
}

data class TeamOprMetrics(
    val teamNumber: String,
    val matchesPlayed: Int,
    val totalOpr: Double,
    val npOpr: Double,
    val dpr: Double,
    val ccwm: Double,
    val autoOpr: Double?,
    val teleopOpr: Double?,
    val endgameOpr: Double?,
    val avgAllianceScore: Double,
)

data class EventOprSnapshot(
    val league: League,
    val playedMatchCount: Int,
    val teamCount: Int,
    val confidence: OprSampleConfidence,
    val metricsByTeam: Map<String, TeamOprMetrics>,
)

data class TeamStandScoutingSummary(
    val teamNumber: String,
    val observationsCount: Int,
    val autoSuccessRate: Double,
    val meanCycles: Double,
    val highEndgameRate: Double,
    val disconnectCount: Int,
    val disconnectRate: Double,
    val avgDefenseRating: Double,
    val avgDriverRating: Double,
    val topTags: List<ScoutingQuickTag>,
    val distinctScouts: Set<String>,
)

data class RankedPicklistTeamRow(
    val displayRank: Int,
    val teamNumber: String,
    val compositePickScore: Double,
    val opr: TeamOprMetrics?,
    val pitProfile: PitTeamProfile?,
    val standSummary: TeamStandScoutingSummary?,
    val override: TeamPicklistOverride,
    val autoZoneSynergyLabel: String,
    val hasAutoZoneCollision: Boolean,
    val reliabilityPenaltyPoints: Double,
    val synergySummary: String,
)

data class MatchupTeamBrief(
    val teamNumber: String,
    val isOurTeam: Boolean,
    val opr: TeamOprMetrics?,
    val pitProfile: PitTeamProfile?,
    val standSummary: TeamStandScoutingSummary?,
)

data class MatchupBriefingReport(
    val matchNumber: Int,
    val compLevel: String,
    val ourTeamNumber: String?,
    val ourAlliance: String?,
    val redTeams: List<MatchupTeamBrief>,
    val blueTeams: List<MatchupTeamBrief>,
    val projectedRedNpScore: Double,
    val projectedBlueNpScore: Double,
    val projectedMarginForOurAlliance: Double?,
    val projectedRedAuto: Double?,
    val projectedBlueAuto: Double?,
    val projectedRedEndgame: Double?,
    val projectedBlueEndgame: Double?,
    val recordedResult: EventMatchResult?,
    val partnerAutoZoneWarning: String?,
    val talkingPoints: List<String>,
    val confidence: OprSampleConfidence,
)

private data class AllianceEquationRow(
    val teams: List<String>,
    val targetValue: Double,
)

/**
 * Deterministic mean-centered Ridge-regularized OPR/DPR/ccWM solver, stand-scouting aggregator,
 * pre-match matchup analyzer, and Alliance Selection picklist ranker.
 */
object OprAndSynergyAnalyzer {

    fun solveOprMetrics(
        league: League,
        results: List<EventMatchResult>,
    ): EventOprSnapshot {
        val played = results
            .filter { it.isPlayed }
            .mapNotNull { match ->
                val red = match.redTeams.map(::normalizeTeamNumber).filter(String::isNotBlank)
                val blue = match.blueTeams.map(::normalizeTeamNumber).filter(String::isNotBlank)
                if (red.isEmpty() || blue.isEmpty()) null
                else match.copy(redTeams = red, blueTeams = blue)
            }

        if (played.isEmpty()) {
            return EventOprSnapshot(
                league = league,
                playedMatchCount = 0,
                teamCount = 0,
                confidence = OprSampleConfidence.NONE,
                metricsByTeam = emptyMap(),
            )
        }

        val allTeams = played
            .flatMap { it.redTeams + it.blueTeams }
            .distinct()
            .sortedWith(teamNumberComparator)
        val n = allTeams.size
        if (n == 0) {
            return EventOprSnapshot(league, 0, 0, OprSampleConfidence.NONE, emptyMap())
        }

        val matchCounts = HashMap<String, Int>(n)
        val allianceScoreSum = HashMap<String, Double>(n)
        played.forEach { m ->
            val rScore = m.redTotalScore!!.toDouble()
            val bScore = m.blueTotalScore!!.toDouble()
            m.redTeams.forEach { team ->
                matchCounts[team] = (matchCounts[team] ?: 0) + 1
                allianceScoreSum[team] = (allianceScoreSum[team] ?: 0.0) + rScore
            }
            m.blueTeams.forEach { team ->
                matchCounts[team] = (matchCounts[team] ?: 0) + 1
                allianceScoreSum[team] = (allianceScoreSum[team] ?: 0.0) + bScore
            }
        }

        val totalRows = buildList {
            played.forEach { m ->
                add(AllianceEquationRow(m.redTeams, m.redTotalScore!!.toDouble()))
                add(AllianceEquationRow(m.blueTeams, m.blueTotalScore!!.toDouble()))
            }
        }
        val npRows = buildList {
            played.forEach { m ->
                add(AllianceEquationRow(m.redTeams, (m.redNonPenaltyScore() ?: m.redTotalScore!!).toDouble()))
                add(AllianceEquationRow(m.blueTeams, (m.blueNonPenaltyScore() ?: m.blueTotalScore!!).toDouble()))
            }
        }
        val dprRows = buildList {
            played.forEach { m ->
                add(AllianceEquationRow(m.redTeams, (m.blueNonPenaltyScore() ?: m.blueTotalScore!!).toDouble()))
                add(AllianceEquationRow(m.blueTeams, (m.redNonPenaltyScore() ?: m.redTotalScore!!).toDouble()))
            }
        }

        val fullRank = isWellConditionedFullRank(allTeams, npRows, matchCounts)
        val confidence = if (fullRank) OprSampleConfidence.FULL_RANK else OprSampleConfidence.EARLY_SAMPLE
        val lambda = if (fullRank) 1e-4 else 0.35

        val totalVec = solveMeanCenteredRidge(allTeams, totalRows, lambda)
        val npVec = solveMeanCenteredRidge(allTeams, npRows, lambda)
        val dprVec = solveMeanCenteredRidge(allTeams, dprRows, lambda)

        val autoPlayed = played.filter { it.redAutoPoints != null && it.blueAutoPoints != null }
        val telePlayed = played.filter { it.redTeleopPoints != null && it.blueTeleopPoints != null }
        val endPlayed = played.filter { it.redEndgamePoints != null && it.blueEndgamePoints != null }

        val minPhaseCount = (played.size + 1) / 2
        val autoVec = if (autoPlayed.size >= minPhaseCount) {
            solveMeanCenteredRidge(
                allTeams,
                autoPlayed.flatMap {
                    listOf(
                        AllianceEquationRow(it.redTeams, it.redAutoPoints!!.toDouble()),
                        AllianceEquationRow(it.blueTeams, it.blueAutoPoints!!.toDouble()),
                    )
                },
                lambda = if (isWellConditionedFullRank(allTeams, autoPlayed.flatMap {
                        listOf(
                            AllianceEquationRow(it.redTeams, it.redAutoPoints!!.toDouble()),
                            AllianceEquationRow(it.blueTeams, it.blueAutoPoints!!.toDouble()),
                        )
                    }, matchCounts)) 1e-4 else 0.35,
            )
        } else null

        val teleVec = if (telePlayed.size >= minPhaseCount) {
            solveMeanCenteredRidge(
                allTeams,
                telePlayed.flatMap {
                    listOf(
                        AllianceEquationRow(it.redTeams, it.redTeleopPoints!!.toDouble()),
                        AllianceEquationRow(it.blueTeams, it.blueTeleopPoints!!.toDouble()),
                    )
                },
                lambda = 0.35,
            )
        } else null

        val endVec = if (endPlayed.size >= minPhaseCount) {
            solveMeanCenteredRidge(
                allTeams,
                endPlayed.flatMap {
                    listOf(
                        AllianceEquationRow(it.redTeams, it.redEndgamePoints!!.toDouble()),
                        AllianceEquationRow(it.blueTeams, it.blueEndgamePoints!!.toDouble()),
                    )
                },
                lambda = if (isWellConditionedFullRank(allTeams, endPlayed.flatMap {
                        listOf(
                            AllianceEquationRow(it.redTeams, it.redEndgamePoints!!.toDouble()),
                            AllianceEquationRow(it.blueTeams, it.blueEndgamePoints!!.toDouble()),
                        )
                    }, matchCounts)) 1e-4 else 0.35,
            )
        } else null

        val metrics = allTeams.indices.associate { idx ->
            val team = allTeams[idx]
            val count = (matchCounts[team] ?: 0).coerceAtLeast(1)
            val avgScore = (allianceScoreSum[team] ?: 0.0) / count.toDouble()
            val np = npVec[idx]
            val dpr = dprVec[idx]
            team to TeamOprMetrics(
                teamNumber = team,
                matchesPlayed = count,
                totalOpr = totalVec[idx],
                npOpr = np,
                dpr = dpr,
                ccwm = np - dpr,
                autoOpr = autoVec?.get(idx),
                teleopOpr = teleVec?.get(idx),
                endgameOpr = endVec?.get(idx),
                avgAllianceScore = avgScore,
            )
        }

        return EventOprSnapshot(
            league = league,
            playedMatchCount = played.size,
            teamCount = n,
            confidence = confidence,
            metricsByTeam = metrics,
        )
    }

    fun summarizeStandObservations(
        observations: List<StandMatchObservation>,
    ): Map<String, TeamStandScoutingSummary> =
        observations
            .mapNotNull { obs ->
                val clean = normalizeTeamNumber(obs.teamNumber)
                if (clean.isBlank()) null else obs.copy(teamNumber = clean)
            }
            .groupBy { it.teamNumber }
            .mapValues { (team, list) ->
                val count = list.size.coerceAtLeast(1)
                val autoAttempts = list.count { it.autoAttempted }.coerceAtLeast(1)
                val autoSuccess = list.count { it.autoAttempted && it.autoSucceeded }.toDouble() / autoAttempts.toDouble()
                val meanCycles = list.map { it.estimatedCycles.coerceAtLeast(0).toDouble() }.average()
                val highEndRate = list.count {
                    it.endgameAchieved == EndgameTier.HIGH_CLIMB || it.endgameAchieved == EndgameTier.LOW_CLIMB
                }.toDouble() / count.toDouble()
                val disconnects = list.count { it.diedOrCommsLost }
                val avgDef = list.map { it.defenseRating.coerceIn(0, 5).toDouble() }.average()
                val avgDrv = list.map { it.driverRating.coerceIn(1, 5).toDouble() }.average()
                val tagCounts = list.flatMap { it.quickTags }.groupingBy { it }.eachCount()
                val topTags = tagCounts.entries
                    .sortedWith(compareByDescending<Map.Entry<ScoutingQuickTag, Int>> { it.value }.thenBy { it.key.ordinal })
                    .map { it.key }
                    .take(3)
                TeamStandScoutingSummary(
                    teamNumber = team,
                    observationsCount = list.size,
                    autoSuccessRate = autoSuccess,
                    meanCycles = meanCycles,
                    highEndgameRate = highEndRate,
                    disconnectCount = disconnects,
                    disconnectRate = disconnects.toDouble() / count.toDouble(),
                    avgDefenseRating = avgDef,
                    avgDriverRating = avgDrv,
                    topTags = topTags,
                    distinctScouts = list.mapNotNull { it.scoutAuthor.trim().takeIf(String::isNotBlank) }.toSet(),
                )
            }

    fun rankAlliancePicklist(
        ourTeamNumber: String,
        ourPitProfile: PitTeamProfile?,
        oprSnapshot: EventOprSnapshot,
        pitProfiles: Map<String, PitTeamProfile>,
        standSummaries: Map<String, TeamStandScoutingSummary>,
        overrides: Map<String, TeamPicklistOverride>,
        preset: PicklistWeightPreset,
        scheduledTeams: Set<String> = emptySet(),
    ): List<RankedPicklistTeamRow> {
        val cleanOurTeam = normalizeTeamNumber(ourTeamNumber)
        val allCandidates = buildSet {
            addAll(oprSnapshot.metricsByTeam.keys)
            addAll(pitProfiles.keys)
            addAll(standSummaries.keys)
            addAll(overrides.keys)
            scheduledTeams.map(::normalizeTeamNumber).filter(String::isNotBlank).forEach(::add)
        }.filter { it.isNotBlank() && it != cleanOurTeam }

        if (allCandidates.isEmpty()) return emptyList()

        val npValues = allCandidates.mapNotNull { oprSnapshot.metricsByTeam[it]?.npOpr }
        val autoValues = allCandidates.mapNotNull { oprSnapshot.metricsByTeam[it]?.autoOpr }
        val endgameValues = allCandidates.mapNotNull { oprSnapshot.metricsByTeam[it]?.endgameOpr }

        val ourZone = ourPitProfile?.preferredAutoZone ?: AutoStartZone.UNKNOWN

        val unsorted = allCandidates.map { team ->
            val opr = oprSnapshot.metricsByTeam[team]
            val pit = pitProfiles[team]
            val stand = standSummaries[team]
            val ovr = overrides[team] ?: TeamPicklistOverride(teamNumber = team)

            val npNorm = if (opr != null) minMaxNormalize(opr.npOpr, npValues) else 0.35
            val autoNorm = when {
                opr?.autoOpr != null && autoValues.isNotEmpty() -> {
                    val oprPart = minMaxNormalize(opr.autoOpr, autoValues)
                    if (stand != null) 0.70 * oprPart + 0.30 * stand.autoSuccessRate else oprPart
                }
                stand != null -> stand.autoSuccessRate
                pit != null && pit.autoSummary.isNotBlank() -> 0.55
                else -> 0.35
            }
            val endgameNorm = when {
                opr?.endgameOpr != null && endgameValues.isNotEmpty() -> {
                    val oprPart = minMaxNormalize(opr.endgameOpr, endgameValues)
                    val pitPart = pit?.endgameCapability?.scoreWeight ?: 0.3
                    0.70 * oprPart + 0.30 * pitPart
                }
                stand != null && pit != null -> 0.60 * stand.highEndgameRate + 0.40 * pit.endgameCapability.scoreWeight
                stand != null -> stand.highEndgameRate
                pit != null -> pit.endgameCapability.scoreWeight
                else -> 0.30
            }
            val defenseDriverNorm = when {
                stand != null -> {
                    val driverPart = (stand.avgDriverRating / 5.0).coerceIn(0.0, 1.0)
                    val defensePart = (stand.avgDefenseRating / 5.0).coerceIn(0.0, 1.0)
                    0.65 * driverPart + 0.35 * defensePart
                }
                pit?.primaryRole == RobotRole.DEFENSE_SPECIALIST -> 0.70
                else -> 0.45
            }

            val candZone = pit?.preferredAutoZone ?: AutoStartZone.UNKNOWN
            val (synergyNorm, hasCollision, zoneLabel) = evaluateAutoZoneSynergy(ourZone, candZone)

            val topTags = stand?.topTags.orEmpty()
            val reliabilityPenalty = (
                ((stand?.disconnectRate ?: 0.0) * 28.0) +
                    (if (ScoutingQuickTag.BROWNOUT_PRONE in topTags) 5.0 else 0.0) +
                    (if (ScoutingQuickTag.TIPPY in topTags) 3.0 else 0.0)
                ).coerceIn(0.0, 32.0)

            val rawWeighted = (
                preset.npOprWeight * npNorm +
                    preset.autoWeight * autoNorm +
                    preset.endgameWeight * endgameNorm +
                    preset.synergyWeight * synergyNorm +
                    preset.defenseDriverWeight * defenseDriverNorm
                ) * 100.0
            val composite = (rawWeighted - reliabilityPenalty).coerceIn(0.0, 100.0)

            val synergyNotes = buildList {
                add(zoneLabel)
                pit?.primaryRole?.let { add(it.label) }
                if ((stand?.disconnectCount ?: 0) > 0) add("${stand?.disconnectCount} disconnect(s)")
            }.joinToString(" · ")

            RankedPicklistTeamRow(
                displayRank = 0,
                teamNumber = team,
                compositePickScore = composite,
                opr = opr,
                pitProfile = pit,
                standSummary = stand,
                override = ovr,
                autoZoneSynergyLabel = zoneLabel,
                hasAutoZoneCollision = hasCollision,
                reliabilityPenaltyPoints = reliabilityPenalty,
                synergySummary = synergyNotes,
            )
        }

        val sorted = unsorted.sortedWith(
            compareBy<RankedPicklistTeamRow> { it.override.tier.rankOrder }
                .thenBy { it.override.pinnedRank ?: Int.MAX_VALUE }
                .thenByDescending { it.compositePickScore }
                .thenByDescending { it.opr?.npOpr ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.teamNumber.toIntOrNull() ?: Int.MAX_VALUE }
                .thenBy { it.teamNumber }
        )

        return sorted.mapIndexed { idx, row -> row.copy(displayRank = idx + 1) }
    }

    fun buildMatchupBriefing(
        matchNumber: Int,
        compLevel: String,
        redAlliance: List<String>,
        blueAlliance: List<String>,
        ourTeamNumber: String?,
        oprSnapshot: EventOprSnapshot,
        pitProfiles: Map<String, PitTeamProfile>,
        standSummaries: Map<String, TeamStandScoutingSummary>,
        recordedResult: EventMatchResult? = null,
    ): MatchupBriefingReport {
        val cleanOur = ourTeamNumber?.let(::normalizeTeamNumber)?.takeIf(String::isNotBlank)
        val redList = redAlliance.map(::normalizeTeamNumber).filter(String::isNotBlank)
        val blueList = blueAlliance.map(::normalizeTeamNumber).filter(String::isNotBlank)

        val redBriefs = redList.map { team ->
            MatchupTeamBrief(
                teamNumber = team,
                isOurTeam = team == cleanOur,
                opr = oprSnapshot.metricsByTeam[team],
                pitProfile = pitProfiles[team],
                standSummary = standSummaries[team],
            )
        }
        val blueBriefs = blueList.map { team ->
            MatchupTeamBrief(
                teamNumber = team,
                isOurTeam = team == cleanOur,
                opr = oprSnapshot.metricsByTeam[team],
                pitProfile = pitProfiles[team],
                standSummary = standSummaries[team],
            )
        }

        val ourAlliance = when {
            cleanOur == null -> null
            cleanOur in redList -> "RED"
            cleanOur in blueList -> "BLUE"
            else -> null
        }

        val meanNp = oprSnapshot.metricsByTeam.values.map { it.npOpr }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val projectedRedNp = redBriefs.sumOf { it.opr?.npOpr ?: meanNp }
        val projectedBlueNp = blueBriefs.sumOf { it.opr?.npOpr ?: meanNp }
        val projectedMargin = when (ourAlliance) {
            "RED" -> projectedRedNp - projectedBlueNp
            "BLUE" -> projectedBlueNp - projectedRedNp
            else -> null
        }

        val redAutoProj = redBriefs.mapNotNull { it.opr?.autoOpr }.takeIf { it.isNotEmpty() }?.sum()
        val blueAutoProj = blueBriefs.mapNotNull { it.opr?.autoOpr }.takeIf { it.isNotEmpty() }?.sum()
        val redEndProj = redBriefs.mapNotNull { it.opr?.endgameOpr }.takeIf { it.isNotEmpty() }?.sum()
        val blueEndProj = blueBriefs.mapNotNull { it.opr?.endgameOpr }.takeIf { it.isNotEmpty() }?.sum()

        val ourBriefs = if (ourAlliance == "RED") redBriefs else if (ourAlliance == "BLUE") blueBriefs else emptyList()
        val oppBriefs = if (ourAlliance == "RED") blueBriefs else if (ourAlliance == "BLUE") redBriefs else blueBriefs
        val ourProfile = cleanOur?.let { pitProfiles[it] }
        val ourZone = ourProfile?.preferredAutoZone ?: AutoStartZone.UNKNOWN

        val partnerCollision = if (ourZone in setOf(AutoStartZone.LEFT, AutoStartZone.CENTER, AutoStartZone.RIGHT)) {
            ourBriefs.firstOrNull { !it.isOurTeam && it.pitProfile?.preferredAutoZone == ourZone }
        } else null

        val partnerAutoZoneWarning = partnerCollision?.let { partner ->
            "Auto-Zone Collision Risk: Partner Team ${partner.teamNumber} also starts in ${ourZone.label}. Coordinate delayed start or alternate lane before queuing."
        }

        val talkingPoints = buildList {
            if (partnerAutoZoneWarning != null) {
                add(partnerAutoZoneWarning)
            } else if (ourBriefs.any { !it.isOurTeam && it.pitProfile != null }) {
                val partners = ourBriefs.filter { !it.isOurTeam }.joinToString(", ") {
                    "${it.teamNumber} (${it.pitProfile?.preferredAutoZone?.label ?: "Unverified"})"
                }
                add("Alliance Auto Lanes: Coordinate with partner(s) $partners.")
            }

            val topThreat = oppBriefs.maxByOrNull { it.opr?.npOpr ?: Double.NEGATIVE_INFINITY }
            if (topThreat != null && topThreat.opr != null) {
                add(
                    "Primary Scoring Threat: Team ${topThreat.teamNumber} leads opponents with " +
                        "${String.format(Locale.US, "%.1f", topThreat.opr.npOpr)} NP-OPR and " +
                        "${String.format(Locale.US, "%.1f", topThreat.opr.ccwm)} ccWM.",
                )
            }

            val defender = oppBriefs.firstOrNull {
                (it.standSummary?.avgDefenseRating ?: 0.0) >= 3.0 ||
                    it.pitProfile?.primaryRole == RobotRole.DEFENSE_SPECIALIST
            }
            if (defender != null) {
                add("Defense Alert: Team ${defender.teamNumber} frequently plays heavy defense; protect cycle lanes cleanly to avoid fouls.")
            }

            val unstable = (ourBriefs + oppBriefs).filter { (it.standSummary?.disconnectCount ?: 0) > 0 }
            if (unstable.isNotEmpty()) {
                val summary = unstable.joinToString(", ") { "${it.teamNumber} (${it.standSummary?.disconnectCount} drops)" }
                add("Reliability Watch: Prior comms/disconnect issues observed on $summary.")
            }

            if (redAutoProj != null && blueAutoProj != null && abs(redAutoProj - blueAutoProj) >= 4.0) {
                val autoLeader = if (redAutoProj > blueAutoProj) "Red" else "Blue"
                add("Auto Edge: $autoLeader alliance projects ahead by ${String.format(Locale.US, "%.1f", abs(redAutoProj - blueAutoProj))} auto pts.")
            }

            if (isEmpty()) {
                add("Sync additional match scores or add Pit/Stand observations to sharpen projected phase edges.")
            }
        }

        return MatchupBriefingReport(
            matchNumber = matchNumber,
            compLevel = compLevel,
            ourTeamNumber = cleanOur,
            ourAlliance = ourAlliance,
            redTeams = redBriefs,
            blueTeams = blueBriefs,
            projectedRedNpScore = projectedRedNp,
            projectedBlueNpScore = projectedBlueNp,
            projectedMarginForOurAlliance = projectedMargin,
            projectedRedAuto = redAutoProj,
            projectedBlueAuto = blueAutoProj,
            projectedRedEndgame = redEndProj,
            projectedBlueEndgame = blueEndProj,
            recordedResult = recordedResult,
            partnerAutoZoneWarning = partnerAutoZoneWarning,
            talkingPoints = talkingPoints,
            confidence = oprSnapshot.confidence,
        )
    }

    private fun evaluateAutoZoneSynergy(
        ourZone: AutoStartZone,
        candidateZone: AutoStartZone,
    ): Triple<Double, Boolean, String> {
        if (candidateZone == AutoStartZone.FLEXIBLE) {
            return Triple(1.0, false, "Flexible Auto Lane")
        }
        val concrete = setOf(AutoStartZone.LEFT, AutoStartZone.CENTER, AutoStartZone.RIGHT)
        if (ourZone in concrete && candidateZone in concrete) {
            return if (ourZone == candidateZone) {
                Triple(0.20, true, "Collision: ${candidateZone.label}")
            } else {
                Triple(0.95, false, "Complements (${candidateZone.label})")
            }
        }
        return Triple(0.55, false, "Auto Zone: ${candidateZone.label}")
    }

    private fun minMaxNormalize(value: Double, population: List<Double>): Double {
        if (population.isEmpty()) return 0.5
        val min = population.min()
        val max = population.max()
        val span = max - min
        return if (span <= 1e-6) 0.5 else ((value - min) / span).coerceIn(0.0, 1.0)
    }

    private fun isWellConditionedFullRank(
        teams: List<String>,
        rows: List<AllianceEquationRow>,
        matchCounts: Map<String, Int>,
    ): Boolean {
        val n = teams.size
        if (rows.size < n + 2) return false
        if (teams.any { (matchCounts[it] ?: 0) < 2 }) return false
        val indexByTeam = teams.withIndex().associate { it.value to it.index }
        val matrix = Array(n) { DoubleArray(n) }
        rows.forEach { row ->
            val indices = row.teams.mapNotNull { indexByTeam[it] }
            for (i in indices) {
                for (j in indices) {
                    matrix[i][j] += 1.0
                }
            }
        }
        return minimumGaussianPivot(matrix) > 1e-5
    }

    /**
     * Solves `(A^T A + lambda * I) * delta = A^T (y - |row| * mu)` and returns `mu + delta`,
     * preventing small/disconnected early-season schedules from collapsing toward 0.
     */
    private fun solveMeanCenteredRidge(
        teams: List<String>,
        rows: List<AllianceEquationRow>,
        lambda: Double,
    ): DoubleArray {
        val n = teams.size
        if (n == 0 || rows.isEmpty()) return DoubleArray(n)
        val indexByTeam = teams.withIndex().associate { it.value to it.index }

        var sumScores = 0.0
        var sumRobots = 0.0
        rows.forEach { r ->
            sumScores += r.targetValue
            sumRobots += r.teams.size.toDouble()
        }
        val mu = if (sumRobots > 0.0) sumScores / sumRobots else 0.0

        val ata = Array(n) { DoubleArray(n) }
        val rhs = DoubleArray(n)
        rows.forEach { row ->
            val indices = row.teams.mapNotNull { indexByTeam[it] }
            val residual = row.targetValue - row.teams.size.toDouble() * mu
            for (i in indices) {
                rhs[i] += residual
                for (j in indices) {
                    ata[i][j] += 1.0
                }
            }
        }
        for (i in 0 until n) {
            ata[i][i] += lambda
        }

        val delta = solveLinearSystem(ata, rhs)
        return DoubleArray(n) { i -> mu + delta[i] }
    }

    private fun minimumGaussianPivot(source: Array<DoubleArray>): Double {
        val n = source.size
        val a = Array(n) { r -> source[r].clone() }
        var minPivot = Double.POSITIVE_INFINITY
        for (col in 0 until n) {
            var pivotRow = col
            var maxVal = abs(a[col][col])
            for (r in col + 1 until n) {
                val candidate = abs(a[r][col])
                if (candidate > maxVal) {
                    maxVal = candidate
                    pivotRow = r
                }
            }
            if (maxVal < minPivot) minPivot = maxVal
            if (maxVal <= 1e-9) return 0.0
            if (pivotRow != col) {
                val tmp = a[col]
                a[col] = a[pivotRow]
                a[pivotRow] = tmp
            }
            val pivot = a[col][col]
            for (r in col + 1 until n) {
                val factor = a[r][col] / pivot
                for (c in col until n) {
                    a[r][c] -= factor * a[col][c]
                }
            }
        }
        return minPivot
    }

    private fun solveLinearSystem(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray {
        val n = rhs.size
        val a = Array(n) { r -> matrix[r].clone() }
        val b = rhs.clone()
        for (col in 0 until n) {
            var pivotRow = col
            var maxVal = abs(a[col][col])
            for (r in col + 1 until n) {
                val candidate = abs(a[r][col])
                if (candidate > maxVal) {
                    maxVal = candidate
                    pivotRow = r
                }
            }
            if (pivotRow != col) {
                val tmpRow = a[col]
                a[col] = a[pivotRow]
                a[pivotRow] = tmpRow
                val tmpB = b[col]
                b[col] = b[pivotRow]
                b[pivotRow] = tmpB
            }
            val pivot = a[col][col]
            if (abs(pivot) <= 1e-12) continue
            for (r in col + 1 until n) {
                val factor = a[r][col] / pivot
                for (c in col until n) {
                    a[r][c] -= factor * a[col][c]
                }
                b[r] -= factor * b[col]
            }
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var sum = b[i]
            for (j in i + 1 until n) {
                sum -= a[i][j] * x[j]
            }
            val diag = a[i][i]
            x[i] = if (abs(diag) > 1e-12) sum / diag else 0.0
        }
        return x
    }

    private val teamNumberComparator = Comparator<String> { a, b ->
        val ai = a.toIntOrNull()
        val bi = b.toIntOrNull()
        when {
            ai != null && bi != null -> ai.compareTo(bi)
            ai != null -> -1
            bi != null -> 1
            else -> a.compareTo(b)
        }
    }
}
