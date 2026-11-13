package com.ares.analytics.domain.pit

import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.shared.models.isSimulationSessionTags
import java.util.Locale

/**
 * Pure analytical engine aggregating DuckDB battery specs, Battery Beak check-ins, and
 * session telemetry summaries (`avgBatteryResistance`, `minBatteryVoltage`) into competition grades,
 * degradation trends, and next-match pack recommendations.
 */
object BatteryFleetAnalyzer {

    private data class LeagueThresholds(
        val primeMaxMohm: Double,
        val healthyMaxMohm: Double,
        val retireMohm: Double,
        val primeMinVoltage: Double,
        val healthyMinVoltage: Double,
        val retireMinVoltage: Double,
        val chargedRestingFloorVoltage: Double,
        val retireSlopeMohmPerRun: Double,
    )

    private fun thresholdsFor(league: League): LeagueThresholds = when (league) {
        League.FRC -> LeagueThresholds(
            primeMaxMohm = 16.0,
            healthyMaxMohm = 22.0,
            retireMohm = 30.0,
            primeMinVoltage = 10.8,
            healthyMinVoltage = 10.2,
            retireMinVoltage = 9.2,
            chargedRestingFloorVoltage = 12.5,
            retireSlopeMohmPerRun = 2.0,
        )
        League.FTC -> LeagueThresholds(
            primeMaxMohm = 35.0,
            healthyMaxMohm = 52.0,
            retireMohm = 70.0,
            primeMinVoltage = 11.2,
            healthyMinVoltage = 10.5,
            retireMinVoltage = 9.5,
            chargedRestingFloorVoltage = 12.4,
            retireSlopeMohmPerRun = 3.0,
        )
        League.XRP -> LeagueThresholds(
            primeMaxMohm = 120.0,
            healthyMaxMohm = 180.0,
            retireMohm = 260.0,
            primeMinVoltage = 4.8,
            healthyMinVoltage = 4.4,
            retireMinVoltage = 4.0,
            chargedRestingFloorVoltage = 4.8,
            retireSlopeMohmPerRun = 6.0,
        )
    }

    fun analyzeFleet(
        league: League,
        specs: List<BatterySpecRecord>,
        checkins: List<BatteryCheckinRecord>,
        sessions: List<Session>,
        summaries: List<SessionSummary>,
    ): List<BatteryHealthSummary> {
        val summaryById = summaries.associateBy { it.sessionId }
        val specById = specs.associateBy { normalizeBatteryId(it.batteryId) }.filterKeys(String::isNotEmpty)

        val checkinsByBattery = checkins
            .mapNotNull { rec -> normalizeBatteryId(rec.batteryId).takeIf(String::isNotEmpty)?.let { it to rec } }
            .groupBy({ it.first }, { it.second.sortedCopyCheckinMarker() })
            .mapValues { (_, list) -> list.sortedByDescending { it.recordedAtMs } }

        val runsByBattery = linkedMapOf<String, MutableList<BatteryRunSample>>()
        for (session in sessions.sortedBy { it.createdAt }) {
            val batteryId = extractBatteryIdFromTags(session.tags) ?: continue
            val sum = summaryById[session.sessionId]
            val minV = sum?.minBatteryVoltage?.takeIf { it.isFinite() && it > 1.0 }
            val mohms = sum?.avgBatteryResistance?.takeIf { it.isFinite() && it > 0.0 && it < 2.0 }?.times(1000.0)
            val isSim = session.tags.isSimulationSessionTags()
            runsByBattery.getOrPut(batteryId) { mutableListOf() }.add(
                BatteryRunSample(
                    sessionId = session.sessionId,
                    matchNumber = session.matchNumber,
                    createdAtMs = session.createdAt,
                    durationMs = session.durationMs,
                    minVoltage = minV,
                    internalResistanceMilliohms = mohms,
                    isSimulation = isSim,
                )
            )
        }

        val allIds = (specById.keys + checkinsByBattery.keys + runsByBattery.keys).distinct().sorted()
        val thresholds = thresholdsFor(league)

        return allIds.map { id ->
            val explicitSpec = specById[id]
            val spec = explicitSpec ?: BatterySpecRecord.defaultForLeague(
                batteryId = id,
                teamId = "",
                seasonId = "",
                robotId = "",
                league = league,
            )
            val batteryCheckins = checkinsByBattery[id].orEmpty()
            val allRuns = runsByBattery[id].orEmpty()
            val hardwareRuns = allRuns.filterNot { it.isSimulation }
            val simCount = allRuns.size - hardwareRuns.size

            val latestCheckin = batteryCheckins.firstOrNull()
            val latestRestingV = batteryCheckins.firstNotNullOfOrNull { it.restingVoltage?.takeIf(Double::isFinite) }
            val latestBeakMohm = batteryCheckins.firstNotNullOfOrNull { it.beakResistanceMilliohms?.takeIf { r -> r.isFinite() && r > 0.0 } }

            val resistanceSamples = hardwareRuns.mapNotNull { it.internalResistanceMilliohms }
            val medianTelemetryMohm = medianOrNull(resistanceSamples)
            val latestTelemetryMohm = hardwareRuns.lastOrNull { it.internalResistanceMilliohms != null }?.internalResistanceMilliohms
            val resistanceSlope = olsSlopeOrNull(resistanceSamples)

            val minVoltageSamples = hardwareRuns.mapNotNull { it.minVoltage }
            val worstMinV = minVoltageSamples.minOrNull()
            val latestMinV = hardwareRuns.lastOrNull { it.minVoltage != null }?.minVoltage
            val lastUsedMs = hardwareRuns.maxOfOrNull { it.createdAtMs } ?: latestCheckin?.recordedAtMs

            val grade = classifyGrade(
                thresholds = thresholds,
                effectiveMohm = medianTelemetryMohm ?: latestBeakMohm,
                worstMinVoltage = worstMinV,
                resistanceSlope = resistanceSlope,
                latestRestingVoltage = latestRestingV,
            )

            val recommendation = buildRecommendation(
                status = spec.status,
                grade = grade,
                thresholds = thresholds,
                effectiveMohm = medianTelemetryMohm ?: latestBeakMohm,
                worstMinVoltage = worstMinV,
                latestRestingVoltage = latestRestingV,
                resistanceSlope = resistanceSlope,
                hardwareCycleCount = hardwareRuns.size,
                isDiscoveredFromTagsOnly = explicitSpec == null,
            )

            BatteryHealthSummary(
                batteryId = id,
                label = spec.label.ifBlank { id },
                nominalVoltage = spec.nominalVoltage,
                capacityAh = spec.capacityAh,
                status = spec.status,
                grade = grade,
                hardwareCycleCount = hardwareRuns.size,
                simulationRunCount = simCount,
                latestRestingVoltage = latestRestingV,
                latestBeakMilliohms = latestBeakMohm,
                medianTelemetryMilliohms = medianTelemetryMohm,
                latestTelemetryMilliohms = latestTelemetryMohm,
                resistanceSlopeMilliohmsPerRun = resistanceSlope,
                worstMinVoltage = worstMinV,
                latestMinVoltage = latestMinV,
                lastUsedAtMs = lastUsedMs,
                recommendation = recommendation,
                recentRuns = allRuns.sortedByDescending { it.createdAtMs }.take(12),
                isDiscoveredFromTagsOnly = explicitSpec == null,
            )
        }
    }

    /** Picks the strongest available Ready pack for an upcoming competition match. */
    fun recommendNextMatchBattery(summaries: List<BatteryHealthSummary>): BatteryHealthSummary? {
        val candidates = summaries.filter {
            it.status == BatteryPoolStatus.READY && it.grade != BatteryHealthGrade.RETIRE_OR_INSPECT
        }
        if (candidates.isEmpty()) return null
        return candidates.sortedWith(
            compareBy<BatteryHealthSummary>(
                { gradePriority(it.grade) },
                { it.medianTelemetryMilliohms ?: it.latestBeakMilliohms ?: Double.MAX_VALUE },
                { -(it.latestRestingVoltage ?: -1.0) },
                { it.lastUsedAtMs ?: 0L },
            )
        ).firstOrNull()
    }

    private fun gradePriority(grade: BatteryHealthGrade): Int = when (grade) {
        BatteryHealthGrade.COMPETITION_PRIME -> 0
        BatteryHealthGrade.HEALTHY -> 1
        BatteryHealthGrade.UNTESTED -> 2
        BatteryHealthGrade.PRACTICE_ONLY -> 3
        BatteryHealthGrade.RETIRE_OR_INSPECT -> 4
    }

    private fun classifyGrade(
        thresholds: LeagueThresholds,
        effectiveMohm: Double?,
        worstMinVoltage: Double?,
        resistanceSlope: Double?,
        latestRestingVoltage: Double?,
    ): BatteryHealthGrade {
        if (effectiveMohm == null && worstMinVoltage == null) {
            return BatteryHealthGrade.UNTESTED
        }
        if ((effectiveMohm != null && effectiveMohm >= thresholds.retireMohm) ||
            (worstMinVoltage != null && worstMinVoltage < thresholds.retireMinVoltage) ||
            (resistanceSlope != null && resistanceSlope > thresholds.retireSlopeMohmPerRun)
        ) {
            return BatteryHealthGrade.RETIRE_OR_INSPECT
        }
        val mohmPrimeOk = effectiveMohm == null || effectiveMohm < thresholds.primeMaxMohm
        val voltPrimeOk = worstMinVoltage == null || worstMinVoltage >= thresholds.primeMinVoltage
        if (mohmPrimeOk && voltPrimeOk && (effectiveMohm != null && worstMinVoltage != null ||
                (latestRestingVoltage != null && latestRestingVoltage >= thresholds.chargedRestingFloorVoltage))
        ) {
            return BatteryHealthGrade.COMPETITION_PRIME
        }
        val mohmHealthyOk = effectiveMohm == null || effectiveMohm < thresholds.healthyMaxMohm
        val voltHealthyOk = worstMinVoltage == null || worstMinVoltage >= thresholds.healthyMinVoltage
        if (mohmHealthyOk && voltHealthyOk) {
            return BatteryHealthGrade.HEALTHY
        }
        return BatteryHealthGrade.PRACTICE_ONLY
    }

    private fun buildRecommendation(
        status: BatteryPoolStatus,
        grade: BatteryHealthGrade,
        thresholds: LeagueThresholds,
        effectiveMohm: Double?,
        worstMinVoltage: Double?,
        latestRestingVoltage: Double?,
        resistanceSlope: Double?,
        hardwareCycleCount: Int,
        isDiscoveredFromTagsOnly: Boolean,
    ): String {
        if (status == BatteryPoolStatus.RETIRED) {
            return "Marked Retired — keep out of match rotation."
        }
        val chargeNote = if (latestRestingVoltage != null && latestRestingVoltage < thresholds.chargedRestingFloorVoltage) {
            " Resting voltage ${"%.2f".format(Locale.US, latestRestingVoltage)} V is below full-charge floor (${"%.1f".format(Locale.US, thresholds.chargedRestingFloorVoltage)} V) — charge before queuing."
        } else {
            ""
        }
        val base = when (grade) {
            BatteryHealthGrade.COMPETITION_PRIME ->
                "Strong competition pack across $hardwareCycleCount hardware run(s)" +
                    (effectiveMohm?.let { " (~${"%.1f".format(Locale.US, it)} mΩ)" }.orEmpty()) + "."
            BatteryHealthGrade.HEALTHY ->
                "Serviceable match pack across $hardwareCycleCount run(s); monitor voltage sag under sustained load."
            BatteryHealthGrade.PRACTICE_ONLY ->
                "Elevated sag/resistance (${worstMinVoltage?.let { "Vmin=${"%.2f".format(Locale.US, it)}V" } ?: ""}) — demote to pit/practice rotation."
            BatteryHealthGrade.RETIRE_OR_INSPECT -> when {
                resistanceSlope != null && resistanceSlope > thresholds.retireSlopeMohmPerRun ->
                    "Rising resistance trend (+${"%.1f".format(Locale.US, resistanceSlope)} mΩ/run) — load-test cell health and inspect Anderson/XT lugs."
                else ->
                    "Exceeds resistance/brownout safety bounds — inspect wiring/cells and retire from matches."
            }
            BatteryHealthGrade.UNTESTED ->
                "No hardware load telemetry yet; log a Beak check-in or tag a hardware run with bat:<ID>."
        }
        val discoveryNote = if (isDiscoveredFromTagsOnly) " (Auto-discovered from session tags — save to roster to track pool status.)" else ""
        return base + chargeNote + discoveryNote
    }

    private fun BatteryCheckinRecord.sortedCopyCheckinMarker(): BatteryCheckinRecord = this

    private fun medianOrNull(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private fun olsSlopeOrNull(values: List<Double>): Double? {
        val n = values.size
        if (n < 3) return null
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumXY = 0.0
        values.forEachIndexed { idx, y ->
            val x = idx.toDouble()
            sumX += x
            sumY += y
            sumXX += x * x
            sumXY += x * y
        }
        val denom = n * sumXX - sumX * sumX
        if (denom <= 1e-12) return null
        val slope = (n * sumXY - sumX * sumY) / denom
        return if (slope.isFinite()) slope else null
    }
}
