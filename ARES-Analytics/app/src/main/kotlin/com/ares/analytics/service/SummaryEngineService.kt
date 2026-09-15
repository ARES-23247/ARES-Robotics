package com.ares.analytics.service

import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.models.AnalysisDiagnostic
import com.ares.analytics.service.db.AnalysisTelemetryGroup
import com.ares.analytics.shared.TelemetryMetricCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Computes statistical summaries from logged match telemetry sessions.
 *
 * Utilizes vectorized DuckDB SQL aggregation queries (`PERCENTILE_CONT`, `MIN`, `MAX`, `AVG`) to extract match performance metrics
 * without pulling raw time-series frame arrays into JVM memory space. Missing scalar metrics retain
 * the legacy zero sentinel, which must not be interpreted as measured zero.
 *
 * ### Computed Mathematical Metrics & Physical Units:
 * - **Minimum Battery Voltage**: $\min(V_{\text{batt}})$ in Volts ($V$)
 * - **Apparent Battery Resistance**: $R = -\Delta V / \Delta I$ for qualifying paired intervals, in Ohms ($\Omega$).
 * - **Maximum EKF Position Drift**: maximum norm of odometry minus the EKF estimate, in Meters ($m$).
 * - **Average & P95 Control Loop Timing**: $t_{\text{loop}}$ and $P_{95}(t_{\text{loop}})$ in Milliseconds ($ms$)
 * - **Vision Acceptance Rate & Latency**: Vision measurement acceptance fraction (0 to 1) and optical processing latency ($ms$)
 * - **Average Cross-Track Error**: $\bar{e}_{\text{ct}} = \frac{1}{N} \sum |e_{\text{ct}}|$ in Meters ($m$)
 * - **Motor Currents**: Per-motor current averages ($A$). Thermal estimates are currently unavailable.
 *
 * ### Thread Safety & Performance Guarantees:
 * Executes SQL aggregate calculations on `Dispatchers.Default`. Stores summary metrics in the DuckDB `session_summaries` table.
 *
 * @param databaseService Primary DuckDB database management service.
 * @param sysIdService System identification service for motor parameter characterization.
 * @param driverAnalysisService Driver control analysis service.
 *
 * @see SessionSummary
 * @see DatabaseService
 * @see SysIdService
 */
class SummaryEngineService(
    private val databaseService: DatabaseService,
    private val sysIdService: SysIdService,
    private val driverAnalysisService: DriverAnalysisService,
    private val onSummaryPersisted: suspend (SessionSummary, List<AlertRecord>) -> Unit = { _, _ -> },
) {

    suspend fun generateSummary(session: Session): SessionSummary = withContext(Dispatchers.Default) {
        val aggregates = SummaryMetricAggregator(databaseService::executeQueryWithParams).read(session.sessionId)
        val minBattery = aggregates["battery"]
        val maxDrift = aggregates["drift"]
        val avgLoop = aggregates["loop"]
        val p95Loop = aggregates["loop_p95"]
        val visionRate = aggregates["acceptance"]
        val avgCrossTrack = aggregates["cross_track"]
        val avgVisionLat = aggregates["latency"]
        val avgResistance = aggregates["resistance"]
        val motorCurrentAverages = aggregates.motorCurrents
        val detectedModes = aggregates.opModes

        // Motor thermal estimation requires sequential state (temperature depends on previous temperature)
        // and cannot be vectorized into SQL without recursive CTEs.
        val maxMotorTemps = emptyMap<String, Double>()
        val health = SummaryHealthAggregator(databaseService::executeQueryWithParams).read(session.sessionId)
        val diagnosticTags = calculateAndSaveDiagnostics(session, health, aggregates.metrics["battery"])
        val finalTags = (session.tags + detectedModes + diagnosticTags).distinct()
        val summary = SessionSummary(
            sessionId = session.sessionId,
            teamId = session.teamId,
            seasonId = session.seasonId,
            robotId = session.robotId,
            createdAt = session.createdAt,
            durationMs = session.durationMs,
            minBatteryVoltage = minBattery,
            maxEkfDrift = maxDrift,
            avgLoopTimeMs = avgLoop,
            p95LoopTimeMs = p95Loop,
            motorCurrentAverages = motorCurrentAverages,
            visionAcceptanceRate = visionRate,
            avgCrossTrackError = avgCrossTrack,
            avgBatteryResistance = avgResistance,
            maxMotorTemps = maxMotorTemps,
            avgVisionLatencyMs = avgVisionLat,
            tags = finalTags,
            matchNumber = session.matchNumber,
            allianceColor = session.allianceColor
        )

        databaseService.insertSessionSummary(summary)
        try {
            onSummaryPersisted(summary, databaseService.getAlerts(summary.sessionId))
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            // Notebook drafting is additive. A local or remote integration failure must never
            // turn a successfully persisted robot analysis into a failed log import.
            System.err.println("[SummaryEngineService] Engineering notebook draft failed: ${failure.message}")
        }
        summary
    }

    private suspend fun calculateAndSaveDiagnostics(session: Session, health: Map<String, Double>, battery: Double?): List<String> {
        val newTags = session.tags.toMutableList()
        fun above(metric: String, threshold: Double) = (health["Diagnostics/System/$metric"] ?: 0.0) > threshold
        if (above("RecordingGapsOver1s", 0.0)) newTags.add("RecordingGaps")
        if (above("LoopSamplesOver40Ms", 5.0)) newTags.add("SlowLoopSamples")
        if (battery != null && battery < 9.5) newTags.add("LowBattery")
        if (above("PeakCANErrorCounter", 0.0) || above("CANBusOffIncrements", 0.0)) newTags.add("CANBusFault")
        if ((health["Diagnostics/System/MaxCANBusUtilization"] ?: 0.0) >= 0.90) newTags.add("CANBusSaturated")
        if (above("BrownoutGuardTripIncrements", 0.0)) newTags.add("BrownoutGuardActivity")
        if (above("MotorFaultObserved", 0.0)) newTags.add("MotorFault")
        val diagnostics = health.map { (key, value) -> AnalysisDiagnostic(session.sessionId, key, value) }.toMutableList()
        fun status(family: String, value: String) {
            diagnostics.add(AnalysisDiagnostic(session.sessionId, "Diagnostics/$family/InputStatus", 0.0, value))
        }
        val groups = listOf(
            AnalysisTelemetryGroup("SysId", buildList {
                addAll(TelemetryMetricCatalog.DRIVE_VOLTAGE.keys)
                addAll(TelemetryMetricCatalog.DRIVE_VELOCITY.keys)
                addAll(TelemetryMetricCatalog.DRIVE_ACCELERATION.keys)
                addAll(SummarySysIdDiagnostics.extraInputKeys)
            }, listOf(SummarySysIdDiagnostics.motorTopicPattern)),
            AnalysisTelemetryGroup("EKF", SummaryLocalizationDiagnostics.ekfInputKeys),
            AnalysisTelemetryGroup("Auto", SummaryLocalizationDiagnostics.pathInputKeys),
        )
        val inputs = try {
            databaseService.getAnalysisTelemetry(session.sessionId, groups)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            // Input failure must not resurrect old fits or discard independently computed health.
            for (group in groups) status(group.id, "read_failed")
            emptyMap()
        }
        for ((family, input) in inputs) {
            diagnostics.add(AnalysisDiagnostic(session.sessionId, "Diagnostics/$family/InputSourceRows", input.sourceRows.toDouble()))
            if (!input.complete) {
                status(family, input.status)
                continue
            }
            try {
                // Assemble each family before adding anything, so a failed fit cannot leave a
                // partially generated family mixed with its unavailable marker.
                val calculated = if (family == "SysId") {
                    SummarySysIdDiagnostics(input.frames, session.sessionId, sysIdService::analyzeRawData).calculate()
                } else {
                    SummaryLocalizationDiagnostics(input.frames, session.sessionId).calculate().map { (key, value) ->
                        AnalysisDiagnostic(session.sessionId, key, value)
                    }
                }
                diagnostics.addAll(calculated)
                status(family, input.status)
                if (calculated.any { (it.key == "Diagnostics/Auto/CrossTrackRMSE" && it.value > 0.06) ||
                        (it.key == "Diagnostics/Auto/MaxCrossTrackM" && it.value > 0.15) }) newTags.add("PathDeviation")
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                status(family, "analysis_failed")
            }
        }
        try {
            val j = driverAnalysisService.analyzeDriverJitter(session.sessionId)
            diagnostics.addAll(recordedDriverDiagnostics(session.sessionId, j))
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            status("Driver", "analysis_failed")
        }
        return persistDiagnostics(session, diagnostics, newTags)
    }

    private suspend fun persistDiagnostics(session: Session, diagnostics: List<AnalysisDiagnostic>, tags: List<String>): List<String> {
        val uniqueTags = tags.distinct()
        databaseService.replaceAnalysisDiagnostics(session.sessionId, diagnostics)
        if (uniqueTags != session.tags) databaseService.updateSessionTags(session.sessionId, uniqueTags)
        return uniqueTags
    }
}
