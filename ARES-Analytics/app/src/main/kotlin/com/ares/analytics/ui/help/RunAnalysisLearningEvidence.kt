package com.ares.analytics.ui.help

import com.ares.analytics.viewmodel.runanalysis.GuidedRunAnalysisState

/** Maps workspace-scoped persisted-run analysis to narrow Academy evidence. */
fun GuidedRunAnalysisState.toAcademyRunAnalysisSnapshot(): AcademyRunAnalysisSnapshot {
    val selected = selectedSessionId?.takeIf { id -> sessions.any { it.sessionId == id } }
    val currentReport = report?.takeIf { it.session.sessionId == selected }
    val currentComparison = comparisonReport?.takeIf {
        it.primarySessionId == selected && it.sessions.map { session -> session.sessionId }.containsAll(comparisonSessionIds)
    }
    return AcademyRunAnalysisSnapshot(
        isAvailable = !loadingSessions && error == null,
        hasWorkspaceRuns = sessions.isNotEmpty(),
        hasSelectedRun = selected != null,
        hasSourceEvidence = currentReport?.source?.explanation?.isNotBlank() == true,
        hasGuidedReport = currentReport != null,
        hasQuantitativeEvidence = currentReport?.metrics?.isNotEmpty() == true || currentComparison?.metrics?.isNotEmpty() == true,
        hasBaselineComparison = currentReport?.comparison != null || currentComparison?.sessions?.size?.let { it >= 2 } == true,
        hasLimitations = currentReport?.limitations?.isNotEmpty() == true || currentComparison?.limitations?.isNotEmpty() == true,
        hasExportedReport =
            (exportMessage?.startsWith("Saved ") == true && currentReport != null) ||
                (comparisonExportMessage?.startsWith("Saved ") == true && currentComparison != null),
    )
}
