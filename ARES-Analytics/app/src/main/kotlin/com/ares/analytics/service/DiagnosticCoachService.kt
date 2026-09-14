package com.ares.analytics.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class DiagnosticSeverity { INFORMATION, REVIEW, URGENT }

data class DiagnosticFinding(
    val id: String,
    val title: String,
    val severity: DiagnosticSeverity,
    val timestampSeconds: Double,
    val observation: String,
    val thresholdContext: String,
    val possibleCauses: List<String>,
    val verificationSteps: List<String>,
    val topic: String
)

data class PitDiagnosticSummary(
    val findings: List<DiagnosticFinding>,
    val missingSignals: List<String>,
    val evidenceNotice: String = "These are telemetry screening observations, not root-cause diagnoses or proof that the robot is safe."
) {
    val urgentCount: Int get() = findings.count { it.severity == DiagnosticSeverity.URGENT }
    val reviewCount: Int get() = findings.count { it.severity == DiagnosticSeverity.REVIEW }
}

/**
 * Produces an evidence-limited pit checklist from imported telemetry.
 *
 * It reports only thresholds directly supported by the selected signal. Possible causes are
 * hypotheses to verify; they are never presented as diagnoses.
 */
class DiagnosticCoachService(private val databaseService: DatabaseService) {
    suspend fun analyze(sessionId: String): PitDiagnosticSummary = withContext(Dispatchers.Default) {
        require(sessionId.isNotBlank()) { "Select a recorded session before running the checklist" }
        val snapshot = DiagnosticCoachSnapshotReader { sql, parameters ->
            databaseService.executeTelemetryQueryWithParams(sessionId, sql, parameters)
        }.read(sessionId)
        val health = coachHealth(snapshot)
        val generated = DiagnosticCoachSnapshotReader(databaseService::executeQueryWithParams).readGenerated(sessionId)
        val localization = DiagnosticCoachLocalization(
            snapshot.localization(sessionId), generated.metrics, sessionId, generated.families,
        )
        health.copy(findings = health.findings + listOfNotNull(localization.ekfFinding(), localization.pathFinding()))
    }

    companion object {
        const val BATTERY_REVIEW_VOLTS = 10.5
        const val BATTERY_URGENT_VOLTS = 9.5
        const val CURRENT_REVIEW_AMPS = 40.0
        const val CURRENT_REVIEW_DURATION_US = 500_000L
        const val MAX_SAMPLE_GAP_US = 200_000L
        const val LOOP_TIME_REVIEW_MS = 35.0
        const val LOOP_TIME_URGENT_MS = 50.0
    }
}
