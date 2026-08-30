package com.ares.analytics.service

import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.shared.models.WorkspaceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class RunEvidenceSourceKind(val label: String) {
    IMPORTED_FILE("Imported file with preserved report"),
    WORKSPACE_DRIVE_OBJECT("Workspace Google Drive object"),
    LOCAL_SESSION_WITHOUT_REPORT("Local session with incomplete source record"),
    AMBIGUOUS_IMPORT_REPORT("Conflicting import reports"),
}

data class RunSourceEvidence(
    val kind: RunEvidenceSourceKind,
    val explanation: String,
    val sourceName: String? = null,
    val decoder: String? = null,
    val sha256: String? = null,
    val acceptedRecords: Long? = null,
    val rejectedRecords: Long? = null,
    val warnings: List<String> = emptyList(),
)

data class GuidedRunMetric(
    val label: String,
    val value: String,
    val unit: String,
    val evidenceSource: String,
)

enum class GuidedRunConfidence(val label: String) {
    INSUFFICIENT("Insufficient evidence"),
    LIMITED("Limited evidence"),
    MODERATE("Moderate evidence"),
}

/** Honest evidence coverage; this never claims that a historical run proves current robot safety. */
data class GuidedRunEvidenceContext(
    val freshnessStatus: String,
    val startTimestampMs: Long?,
    val endTimestampMs: Long?,
    val confidence: GuidedRunConfidence,
    val confidenceExplanation: String,
)

data class GuidedRunFinding(
    val title: String,
    val statusText: String,
    val observedEvidence: String,
    val interpretationLimit: String,
    val possibleCauses: List<String>,
    val safeVerificationSteps: List<String>,
    val timestampSeconds: Double? = null,
    val absoluteTimestampMs: Long? = null,
    val sourceTopics: List<String> = emptyList(),
)

enum class GuidedRunDestination {
    IMPORTS,
    DASHBOARD_REPLAY,
    TUNING,
    ACADEMY,
    RUN_HISTORY,
}

data class GuidedRunNextAction(
    val title: String,
    val reason: String,
    val destination: GuidedRunDestination,
)

data class GuidedRunAnalysisReport(
    val session: Session,
    val source: RunSourceEvidence,
    val evidenceContext: GuidedRunEvidenceContext,
    val summary: SessionSummary?,
    val metrics: List<GuidedRunMetric>,
    val alerts: List<AlertRecord>,
    val findings: List<GuidedRunFinding>,
    val comparison: SessionComparison?,
    val regressions: List<RegressionSignal>,
    val driverReview: DriverCoachingReport?,
    val missingSignals: List<String>,
    val limitations: List<String>,
    val nextActions: List<GuidedRunNextAction>,
)

interface GuidedRunAnalysisRepository {
    suspend fun listWorkspaceSessions(workspace: WorkspaceConfig): List<Session>
    suspend fun analyze(workspace: WorkspaceConfig, sessionId: String): GuidedRunAnalysisReport
    suspend fun exportMarkdown(report: GuidedRunAnalysisReport, destination: File)
}

/**
 * Composes existing deterministic analyzers into one read-only, workspace-scoped novice review.
 * Measured observations and possible causes stay separate all the way to the UI and export.
 */
class GuidedRunAnalysisService(
    private val databaseService: DatabaseService,
    private val importArchiveService: ImportArchiveService,
    private val advancedAnalyticsService: AdvancedAnalyticsService,
    private val diagnosticCoachService: DiagnosticCoachService,
    private val driverAnalysisService: DriverAnalysisService,
) : GuidedRunAnalysisRepository {
    override suspend fun listWorkspaceSessions(workspace: WorkspaceConfig): List<Session> = withContext(Dispatchers.IO) {
        databaseService.getSessions()
            .filter { it.belongsTo(workspace) }
            .sortedByDescending(Session::createdAt)
    }

    override suspend fun analyze(workspace: WorkspaceConfig, sessionId: String): GuidedRunAnalysisReport =
        withContext(Dispatchers.IO) {
            require(sessionId.isNotBlank()) { "Select a run before starting the guided review" }
            val session = databaseService.getSessions().firstOrNull { it.sessionId == sessionId }
                ?: throw IllegalArgumentException("The selected run no longer exists in the local database")
            require(session.belongsTo(workspace)) {
                "The selected run belongs to another team, season, or robot workspace"
            }

            val persistedSummary = databaseService.getSessionSummary(sessionId)
            val summaryIdentityMatches = persistedSummary == null || persistedSummary.belongsTo(workspace)
            val summary = persistedSummary?.takeIf { summaryIdentityMatches }
            val timestampRange = databaseService.getSessionTimestampRange(sessionId)
            val topicCount = databaseService.getDistinctTelemetryKeys(sessionId).size
            val frameCount = databaseService.countTelemetryFrames(sessionId)
            val alerts = databaseService.getAlerts(sessionId).sortedBy(AlertRecord::triggerTimestampMs)
            val source = sourceEvidence(workspace, sessionId, summary)
            val diagnosticResult = runCatching { diagnosticCoachService.analyze(sessionId) }
            val advancedResult = if (summaryIdentityMatches) {
                advancedAnalyticsService.analyzeAgainstRecent(sessionId)
            } else {
                OperationResult.Unavailable(
                    "SUMMARY_IDENTITY_MISMATCH",
                    "The persisted summary does not match the selected team, season, and robot",
                )
            }
            val driverResult = runCatching { driverAnalysisService.analyzeDriverCoaching(sessionId) }

            val diagnostic = diagnosticResult.getOrNull()
            val advanced = (advancedResult as? OperationResult.Success)?.value
            val findings = diagnostic?.findings.orEmpty().map { finding ->
                GuidedRunFinding(
                    title = finding.title,
                    statusText = finding.severity.name.lowercase().replaceFirstChar(Char::uppercaseChar),
                    observedEvidence = finding.observation,
                    interpretationLimit = finding.thresholdContext,
                    possibleCauses = finding.possibleCauses,
                    safeVerificationSteps = finding.verificationSteps,
                    timestampSeconds = finding.timestampSeconds,
                    absoluteTimestampMs = (finding.timestampSeconds * 1_000.0).toLong(),
                    sourceTopics = listOf(finding.topic),
                )
            }
            val missingSignals = buildList {
                addAll(diagnostic?.missingSignals.orEmpty())
                if (timestampRange == null) add("Telemetry timeline")
                if (topicCount == 0) add("Telemetry topics")
                if (summary == null) add("Precomputed session summary")
            }.distinct()
            val evidenceContext = buildEvidenceContext(
                timestampRange = timestampRange,
                topicCount = topicCount,
                source = source,
                summary = summary,
                diagnosticSucceeded = diagnostic != null,
            )
            val limitations = buildList {
                add("This is historical desktop evidence, not a live freshness or physical-safety check.")
                if (source.kind == RunEvidenceSourceKind.LOCAL_SESSION_WITHOUT_REPORT) {
                    add("The original file or capture method was not preserved with this session.")
                }
                if (source.kind == RunEvidenceSourceKind.AMBIGUOUS_IMPORT_REPORT) {
                    add("More than one import report claims this session, so file provenance is ambiguous.")
                }
                if (!summaryIdentityMatches) {
                    add("The persisted summary identity did not match this workspace, so it was excluded from metrics and baseline comparison.")
                }
                diagnosticResult.exceptionOrNull()?.message?.let {
                    add("The pit evidence screen could not run: ${it.oneLine()}.")
                }
                when (advancedResult) {
                    is OperationResult.Failure -> add("Advanced comparison failed: ${advancedResult.message.oneLine()}.")
                    is OperationResult.Unavailable -> add("Advanced comparison is unavailable: ${advancedResult.message.oneLine()}.")
                    is OperationResult.Success -> Unit
                }
                driverResult.exceptionOrNull()?.message?.let {
                    add("Driver-motion review could not run: ${it.oneLine()}.")
                }
                if (advanced?.comparison == null) {
                    add("No compatible baseline from the same team, season, and robot was available.")
                }
            }.distinct()
            val metrics = buildList {
                val durationMs = session.durationMs.takeIf { it > 0L }
                    ?: timestampRange?.let { (start, end) -> (end - start).coerceAtLeast(0L) }
                    ?: 0L
                add(GuidedRunMetric("Run duration", "%.3f".format(durationMs / 1_000.0), "s", "Persisted session/timestamp range"))
                add(GuidedRunMetric("Telemetry frames", frameCount.toString(), "records", "Local DuckDB"))
                add(GuidedRunMetric("Telemetry topics", topicCount.toString(), "topics", "Distinct persisted keys"))
                add(GuidedRunMetric("Alerts", alerts.size.toString(), "events", "Persisted alert timeline"))
                timestampRange?.let { (start, end) ->
                    add(GuidedRunMetric("Source timestamp range", "$start to $end", "ms", "Imported frame timestamps"))
                }
            }
            val nextActions = buildNextActions(source, findings, missingSignals, advanced)

            GuidedRunAnalysisReport(
                session = session,
                source = source,
                evidenceContext = evidenceContext,
                summary = summary,
                metrics = metrics,
                alerts = alerts,
                findings = findings,
                comparison = advanced?.comparison,
                regressions = advanced?.regressions.orEmpty(),
                driverReview = driverResult.getOrNull(),
                missingSignals = missingSignals,
                limitations = limitations,
                nextActions = nextActions,
            )
        }

    override suspend fun exportMarkdown(report: GuidedRunAnalysisReport, destination: File) = withContext(Dispatchers.IO) {
        writeFileAtomically(destination) { temporary ->
            temporary.writeText(renderMarkdown(report))
        }
    }

    fun renderMarkdown(report: GuidedRunAnalysisReport): String = buildString {
        appendLine("# ARES guided run review")
        appendLine()
        appendLine("Session: ${report.session.sessionId.safeMarkdown()}")
        appendLine("Robot: ${report.session.teamId.safeMarkdown()} / ${report.session.seasonId.safeMarkdown()} / ${report.session.robotId.safeMarkdown()}")
        appendLine("Source: ${report.source.kind.label}")
        appendLine("Source detail: ${report.source.explanation.safeMarkdown()}")
        appendLine("Freshness: ${report.evidenceContext.freshnessStatus.safeMarkdown()}")
        appendLine("Interpretation confidence: ${report.evidenceContext.confidence.label} — ${report.evidenceContext.confidenceExplanation.safeMarkdown()}")
        report.source.sourceName?.let { appendLine("Source file/object: ${it.safeMarkdown()}") }
        report.source.decoder?.let { appendLine("Decoder: ${it.safeMarkdown()}") }
        report.source.sha256?.let { appendLine("SHA-256: ${it.safeMarkdown()}") }
        appendLine()
        appendLine("> Historical desktop evidence does not prove current physical safety.")
        appendLine()
        appendLine("## Measured evidence")
        report.metrics.forEach { appendLine("- ${it.label}: ${it.value} ${it.unit} (${it.evidenceSource})") }
        if (report.alerts.isEmpty()) appendLine("- No persisted alerts. This does not prove that no fault occurred.")
        report.alerts.forEach { alert ->
            appendLine("- Alert ${alert.ruleKey.safeMarkdown()} at ${alert.triggerTimestampMs} ms; peak ${alert.peakValue}")
        }
        appendLine()
        appendLine("## Threshold findings and hypotheses")
        if (report.findings.isEmpty()) appendLine("- No configured screening threshold was crossed. This is not a health verdict.")
        report.findings.forEach { finding ->
            appendLine("### ${finding.title.safeMarkdown()}")
            finding.timestampSeconds?.let { appendLine("- Recorded timestamp: ${"%.3f".format(it)} s") }
            finding.absoluteTimestampMs?.let { appendLine("- Replay evidence timestamp: $it ms") }
            if (finding.sourceTopics.isNotEmpty()) appendLine("- Source topics: ${finding.sourceTopics.joinToString { it.safeMarkdown() }}")
            appendLine("- Observed: ${finding.observedEvidence.safeMarkdown()}")
            appendLine("- Interpretation limit: ${finding.interpretationLimit.safeMarkdown()}")
            appendLine("- Possible causes to verify: ${finding.possibleCauses.joinToString { it.safeMarkdown() }}")
            appendLine("- Safe verification: ${finding.safeVerificationSteps.joinToString("; ") { it.safeMarkdown() }}")
        }
        appendLine()
        appendLine("## Same-robot comparison")
        val comparison = report.comparison
        if (comparison == null) appendLine("- No compatible baseline was available.")
        else {
            appendLine("- Baselines: ${comparison.baselineSessionIds.joinToString { it.safeMarkdown() }}")
            report.regressions.forEach { regression ->
                appendLine("- ${regression.metric.safeMarkdown()}: ${"%.1f".format(regression.percentRegression)}% regression (${regression.severity})")
            }
            if (report.regressions.isEmpty()) appendLine("- No configured material regression was detected.")
        }
        appendLine()
        appendLine("## Safe next actions")
        report.nextActions.forEach { appendLine("- ${it.title.safeMarkdown()}: ${it.reason.safeMarkdown()}") }
        appendLine()
        appendLine("## Limitations")
        report.limitations.forEach { appendLine("- ${it.safeMarkdown()}") }
    }

    private fun sourceEvidence(
        workspace: WorkspaceConfig,
        sessionId: String,
        summary: SessionSummary?,
    ): RunSourceEvidence {
        val archiveResult = runCatching { importArchiveService.load(workspace.projectPath) }
        val reports = archiveResult.getOrNull()?.imported.orEmpty()
            .mapNotNull(ImportArchiveEntry::report)
            .filter { it.sessionId == sessionId }
        if (reports.size > 1) {
            return RunSourceEvidence(
                kind = RunEvidenceSourceKind.AMBIGUOUS_IMPORT_REPORT,
                explanation = "${reports.size} persisted import reports claim this session; do not choose one silently.",
                warnings = reports.flatMap(ImportReport::warnings).distinct(),
            )
        }
        reports.singleOrNull()?.let { report ->
            return RunSourceEvidence(
                kind = RunEvidenceSourceKind.IMPORTED_FILE,
                explanation = "ARES preserved the decoder result and source digest when this file was imported.",
                sourceName = report.sourceName,
                decoder = report.decoder,
                sha256 = report.sourceSha256,
                acceptedRecords = report.acceptedRecords,
                rejectedRecords = report.rejectedRecords,
                warnings = report.warnings,
            )
        }
        if (summary?.cloudFileId != null) {
            return RunSourceEvidence(
                kind = RunEvidenceSourceKind.WORKSPACE_DRIVE_OBJECT,
                explanation = "This session came from the selected workspace Drive destination; Google permissions remain authoritative.",
                sourceName = summary.cloudFileName,
                decoder = "Parquet workspace object",
                sha256 = summary.cloudSha256,
            )
        }
        return RunSourceEvidence(
            kind = RunEvidenceSourceKind.LOCAL_SESSION_WITHOUT_REPORT,
            explanation = archiveResult.exceptionOrNull()?.message?.let {
                "The import archive could not be read: ${it.oneLine()}."
            } ?: "ARES has the session data but no persisted import report or Drive object identity for it.",
        )
    }

    private fun buildNextActions(
        source: RunSourceEvidence,
        findings: List<GuidedRunFinding>,
        missingSignals: List<String>,
        advanced: AdvancedAnalyticsReport?,
    ): List<GuidedRunNextAction> = buildList {
        if (source.kind == RunEvidenceSourceKind.LOCAL_SESSION_WITHOUT_REPORT ||
            source.kind == RunEvidenceSourceKind.AMBIGUOUS_IMPORT_REPORT
        ) {
            add(GuidedRunNextAction("Preserve source provenance", "Use Log Imports for the next capture so the decoder, digest, and accepted-record count are retained.", GuidedRunDestination.IMPORTS))
        }
        if (missingSignals.isNotEmpty()) {
            add(GuidedRunNextAction("Review missing telemetry", "Open the Academy evidence lesson before interpreting absent ${missingSignals.joinToString()}.", GuidedRunDestination.ACADEMY))
        }
        findings.firstOrNull()?.let { finding ->
            add(GuidedRunNextAction("Verify the first threshold finding", finding.safeVerificationSteps.firstOrNull() ?: "Inspect the recorded interval before changing configuration.", GuidedRunDestination.DASHBOARD_REPLAY))
        }
        if (advanced?.tuningSuggestions?.isNotEmpty() == true) {
            add(GuidedRunNextAction("Review a tuning proposal", "Open Tuning to compare current, requested, and canonical values; this review does not apply a change.", GuidedRunDestination.TUNING))
        }
        add(GuidedRunNextAction("Inspect the exact timeline", "Replay the selected run with units and timestamps before accepting a cause hypothesis.", GuidedRunDestination.DASHBOARD_REPLAY))
        add(GuidedRunNextAction("Preserve this review", "Export the evidence summary and keep the original session unchanged.", GuidedRunDestination.RUN_HISTORY))
    }.distinctBy(GuidedRunNextAction::title)

    private fun buildEvidenceContext(
        timestampRange: Pair<Long, Long>?,
        topicCount: Int,
        source: RunSourceEvidence,
        summary: SessionSummary?,
        diagnosticSucceeded: Boolean,
    ): GuidedRunEvidenceContext {
        val freshness = timestampRange?.let { (start, end) ->
            "Historical recording from $start ms to $end ms; it is not a live reading."
        } ?: "No persisted timestamp range is available, so freshness cannot be assessed."
        val confidence = when {
            timestampRange == null || topicCount == 0 -> GuidedRunConfidence.INSUFFICIENT
            source.kind == RunEvidenceSourceKind.AMBIGUOUS_IMPORT_REPORT ||
                source.kind == RunEvidenceSourceKind.LOCAL_SESSION_WITHOUT_REPORT ||
                summary == null || !diagnosticSucceeded -> GuidedRunConfidence.LIMITED
            else -> GuidedRunConfidence.MODERATE
        }
        val explanation = when (confidence) {
            GuidedRunConfidence.INSUFFICIENT ->
                "The persisted timeline or topic set is missing; do not infer normal behavior from absent data."
            GuidedRunConfidence.LIMITED ->
                "The run can be inspected, but source identity, summary coverage, or threshold screening is incomplete."
            GuidedRunConfidence.MODERATE ->
                "The source and timeline support threshold screening and same-robot comparison, but they do not establish causation or physical safety."
        }
        return GuidedRunEvidenceContext(
            freshnessStatus = freshness,
            startTimestampMs = timestampRange?.first,
            endTimestampMs = timestampRange?.second,
            confidence = confidence,
            confidenceExplanation = explanation,
        )
    }

    private fun Session.belongsTo(workspace: WorkspaceConfig): Boolean =
        teamId == workspace.teamId && seasonId == workspace.seasonId && robotId == workspace.robotId

    private fun SessionSummary.belongsTo(workspace: WorkspaceConfig): Boolean =
        teamId == workspace.teamId && seasonId == workspace.seasonId && robotId == workspace.robotId
}

private fun String.oneLine(): String = replace(Regex("[\\r\\n]+"), " ").trim()
private fun String.safeMarkdown(): String = oneLine().replace("|", "\\|")
