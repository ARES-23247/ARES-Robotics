package com.ares.analytics.service

import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.models.AnalysisDiagnostic
import com.ares.analytics.shared.models.TelemetryFrame
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
        var resolvedTags = session.tags
        try {
            val allFrames = databaseService.getTelemetryForFilters(
                sessionId = session.sessionId,
                keys = buildList {
                    addAll(TelemetryMetricCatalog.DRIVE_VOLTAGE.keys)
                    addAll(TelemetryMetricCatalog.DRIVE_VELOCITY.keys)
                    addAll(TelemetryMetricCatalog.DRIVE_ACCELERATION.keys)
                    addAll(SummarySysIdDiagnostics.extraInputKeys)
                    addAll(SummaryLocalizationDiagnostics.inputKeys)
                },
                prefixes = listOf("Hardware/Motors/%", "Vision/%", "Path/%"),
                maxFrames = MAX_DIAGNOSTIC_FRAMES,
                maxFramesPerTopic = MAX_DIAGNOSTIC_FRAMES_PER_TOPIC,
            )
            val framesToInsert = health.map { (key, value) ->
                TelemetryFrame(session.createdAt, session.sessionId, key, value)
            }.toMutableList()
            if (allFrames.isEmpty()) return persistDiagnostics(session, framesToInsert, newTags)

            // Recorded voltage/speed fits share validated source-time alignment.
            for (diagnostic in SummarySysIdDiagnostics(allFrames, session.sessionId, sysIdService::analyzeRawData).calculate()) {
                framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, diagnostic.key, diagnostic.value, diagnostic.stringValue))
            }

            // 5. Driver Jitter Analysis
            val j = driverAnalysisService.analyzeDriverJitter(session.sessionId)
            if (j.peakFrequencyHz > 0.1) {
                framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/Driver/RecommendedExponent", j.recommendedExponent))
                framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/Driver/RecommendedSlewRate", if (j.recommendedSlewRate == Double.MAX_VALUE) 999.0 else j.recommendedSlewRate))
                framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/Driver/PeakJitterFrequency", j.peakFrequencyHz))
                framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/Driver/JitterPresent", if (j.hasJitter) 1.0 else 0.0))
            }

            // 6â€“7. Recorded localization and path statistics; no covariance/root-cause diagnosis.
            val localization = SummaryLocalizationDiagnostics(allFrames, session.sessionId).calculate()
            for ((key, value) in localization) {
                framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, key, value))
            }
            if ((localization["Diagnostics/Auto/CrossTrackRMSE"] ?: 0.0) > 0.06 ||
                (localization["Diagnostics/Auto/MaxCrossTrackM"] ?: 0.0) > 0.15) {
                newTags.add("PathDeviation")
            }

            resolvedTags = persistDiagnostics(session, framesToInsert, newTags)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.printStackTrace()
        }
        return resolvedTags
    }

    private suspend fun persistDiagnostics(session: Session, frames: List<TelemetryFrame>, tags: List<String>): List<String> {
        val uniqueTags = tags.distinct()
        databaseService.replaceAnalysisDiagnostics(session.sessionId, frames.map { frame ->
            AnalysisDiagnostic(frame.sessionId, frame.key, frame.value, frame.stringValue)
        })
        if (uniqueTags != session.tags) databaseService.updateSessionTags(session.sessionId, uniqueTags)
        return uniqueTags
    }

    private companion object {
        /**
         * Secondary diagnostic algorithms operate on a deterministic, per-topic sample. Core
         * summary and health values remain exact SQL aggregates. These bounds prevent a long WPILOG
         * from materializing millions of JVM objects while retaining endpoints and uniform
         * coverage for every ordinary topic.
         */
        const val MAX_DIAGNOSTIC_FRAMES = 100_000
        const val MAX_DIAGNOSTIC_FRAMES_PER_TOPIC = 2_048
    }
}
