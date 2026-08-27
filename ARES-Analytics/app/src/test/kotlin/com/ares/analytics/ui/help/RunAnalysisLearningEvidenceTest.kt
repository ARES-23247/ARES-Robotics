package com.ares.analytics.ui.help

import com.ares.analytics.service.GuidedRunAnalysisReport
import com.ares.analytics.service.GuidedRunConfidence
import com.ares.analytics.service.GuidedRunEvidenceContext
import com.ares.analytics.service.GuidedRunMetric
import com.ares.analytics.service.RunEvidenceSourceKind
import com.ares.analytics.service.RunSourceEvidence
import com.ares.analytics.service.RunAlignmentAnchor
import com.ares.analytics.service.RunAlignmentKind
import com.ares.analytics.service.RunAlignmentOption
import com.ares.analytics.service.RunComparisonReport
import com.ares.analytics.service.SessionComparison
import com.ares.analytics.shared.Session
import com.ares.analytics.viewmodel.runanalysis.GuidedRunAnalysisState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunAnalysisLearningEvidenceTest {
    private val session = Session("run-1", "23247", "decode", "gobilda", 1_000L)

    @Test
    fun `current workspace report maps to narrow evidence facts`() {
        val report = GuidedRunAnalysisReport(
            session = session,
            source = RunSourceEvidence(
                kind = RunEvidenceSourceKind.IMPORTED_FILE,
                explanation = "Imported from preserved run.jsonl",
                sourceName = "run.jsonl",
            ),
            evidenceContext = GuidedRunEvidenceContext(
                freshnessStatus = "Historical recording",
                startTimestampMs = 1_000L,
                endTimestampMs = 2_000L,
                confidence = GuidedRunConfidence.MODERATE,
                confidenceExplanation = "Source and timeline are present",
            ),
            summary = null,
            metrics = listOf(GuidedRunMetric("Duration", "1.0", "s", "Persisted timeline")),
            alerts = emptyList(),
            findings = emptyList(),
            comparison = SessionComparison(listOf("baseline-1"), emptyList()),
            regressions = emptyList(),
            driverReview = null,
            missingSignals = emptyList(),
            limitations = listOf("Historical evidence does not prove current safety."),
            nextActions = emptyList(),
        )
        val snapshot = GuidedRunAnalysisState(
            loadingSessions = false,
            sessions = listOf(session),
            selectedSessionId = session.sessionId,
            report = report,
            exportMessage = "Saved evidence.md",
        ).toAcademyRunAnalysisSnapshot()

        assertTrue(snapshot.isAvailable)
        assertTrue(snapshot.hasWorkspaceRuns)
        assertTrue(snapshot.hasSelectedRun)
        assertTrue(snapshot.hasSourceEvidence)
        assertTrue(snapshot.hasGuidedReport)
        assertTrue(snapshot.hasQuantitativeEvidence)
        assertTrue(snapshot.hasBaselineComparison)
        assertTrue(snapshot.hasLimitations)
        assertTrue(snapshot.hasExportedReport)
    }

    @Test
    fun `stale report for another selection cannot earn evidence`() {
        val other = session.copy(sessionId = "run-2")
        val report = GuidedRunAnalysisReport(
            session = session,
            source = RunSourceEvidence(RunEvidenceSourceKind.LOCAL_SESSION_WITHOUT_REPORT, "Local source"),
            evidenceContext = GuidedRunEvidenceContext(
                "Historical recording",
                null,
                null,
                GuidedRunConfidence.INSUFFICIENT,
                "Missing timeline",
            ),
            summary = null,
            metrics = listOf(GuidedRunMetric("Frames", "1", "records", "DuckDB")),
            alerts = emptyList(),
            findings = emptyList(),
            comparison = null,
            regressions = emptyList(),
            driverReview = null,
            missingSignals = listOf("Timeline"),
            limitations = listOf("Incomplete source record"),
            nextActions = emptyList(),
        )
        val snapshot = GuidedRunAnalysisState(
            loadingSessions = false,
            sessions = listOf(session, other),
            selectedSessionId = other.sessionId,
            report = report,
            exportMessage = "Saved stale.md",
        ).toAcademyRunAnalysisSnapshot()

        assertTrue(snapshot.hasWorkspaceRuns)
        assertTrue(snapshot.hasSelectedRun)
        assertFalse(snapshot.hasSourceEvidence)
        assertFalse(snapshot.hasGuidedReport)
        assertFalse(snapshot.hasQuantitativeEvidence)
        assertFalse(snapshot.hasExportedReport)
    }

    @Test
    fun `current comparison export earns evidence without a single-run export`() {
        val other = session.copy(sessionId = "run-2")
        val alignment = RunAlignmentOption("run-start", RunAlignmentKind.RUN_START, "Run start", "First sample")
        val comparison = RunComparisonReport(
            sessions = listOf(session, other),
            primarySessionId = session.sessionId,
            selectedAlignment = alignment,
            availableAlignments = listOf(alignment),
            anchors = listOf(
                RunAlignmentAnchor(session.sessionId, 1_000L, "Run start"),
                RunAlignmentAnchor(other.sessionId, 1_000L, "Run start"),
            ),
            trajectories = emptyList(),
            metrics = emptyList(),
            faults = emptyList(),
            findings = emptyList(),
            limitations = listOf("Historical comparison"),
        )

        val snapshot = GuidedRunAnalysisState(
            loadingSessions = false,
            sessions = listOf(session, other),
            selectedSessionId = session.sessionId,
            comparisonSessionIds = listOf(other.sessionId),
            comparisonReport = comparison,
            comparisonExportMessage = "Saved comparison.md",
        ).toAcademyRunAnalysisSnapshot()

        assertTrue(snapshot.hasBaselineComparison)
        assertTrue(snapshot.hasLimitations)
        assertTrue(snapshot.hasExportedReport)
    }
}
