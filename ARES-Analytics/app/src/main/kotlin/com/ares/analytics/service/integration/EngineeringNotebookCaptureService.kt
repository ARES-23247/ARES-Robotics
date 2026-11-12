package com.ares.analytics.service.integration

import com.ares.analytics.domain.analysis.CohortComparisonResult
import com.ares.analytics.domain.analysis.MatchCoachingInsight
import com.ares.analytics.domain.analysis.SpatialFieldHeatmap
import com.ares.analytics.domain.notebook.EngineeringPortfolioCompiler
import com.ares.analytics.domain.notebook.NotebookSectionParser
import com.ares.analytics.domain.notebook.StructuredNotebookSections
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.ExportService
import com.ares.analytics.service.GuidedRunAnalysisReport
import com.ares.analytics.service.RunComparisonReport
import com.ares.analytics.service.tuning.GuidedTuningExperiment
import com.ares.analytics.service.verification.RobotVerificationReport
import com.ares.analytics.service.verification.VerificationLayer
import com.ares.analytics.service.verification.VerificationResultStatus
import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.models.EngineeringNotebookEntry
import com.ares.analytics.shared.models.EngineeringNotebookHasher
import com.ares.analytics.shared.models.IntegrationWorkspaceIdentity
import com.ares.analytics.shared.models.NotebookEntryType
import com.ares.analytics.shared.models.NotebookEvidenceReference
import com.ares.analytics.shared.models.NotebookReviewState
import com.ares.analytics.shared.models.NotebookVisibility
import com.ares.analytics.shared.models.PublicationReceipt
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionAnnotation
import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.shared.models.allowsAutomaticExternalUpdates
import com.ares.analytics.shared.models.isSimulationSessionTags
import com.ares.analytics.util.Sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.util.Locale
import java.util.UUID

/**
 * Workspace-scoped Engineering Notebook capture, revision, human sign-off, and season portfolio service.
 *
 * Persists directly to `engineering_notebook_entries` via [DatabaseService] so manual entries,
 * 1-click analytical captures, and automated session drafts share one canonical revision log,
 * SHA-256 content hash chain, and external outbox publishing contract.
 */
class EngineeringNotebookCaptureService(
    private val databaseService: DatabaseService,
    private val exportService: ExportService,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun listWorkspaceEntries(
        workspace: WorkspaceConfig,
        limit: Int = 500,
    ): List<EngineeringNotebookEntry> = withContext(Dispatchers.IO) {
        val target = workspace.toNotebookWorkspaceIdentity()
        val boundedLimit = limit.coerceIn(1, 1_000)
        databaseService.integrations.listLatestNotebookEntries(boundedLimit)
            .filter { entry -> entry.workspace == target }
    }

    suspend fun listRevisions(entryId: String): List<EngineeringNotebookEntry> = withContext(Dispatchers.IO) {
        if (entryId.isBlank()) emptyList()
        else databaseService.integrations.listNotebookRevisions(entryId.trim())
    }

    suspend fun listReceipts(entryId: String): List<PublicationReceipt> = withContext(Dispatchers.IO) {
        if (entryId.isBlank()) emptyList()
        else databaseService.integrations.listPublicationReceipts(entryId.trim())
    }

    suspend fun saveEditedEntry(
        workspace: WorkspaceConfig,
        entryId: String?,
        entryType: NotebookEntryType,
        markdownBody: String,
        evidence: List<NotebookEvidenceReference>,
        visibility: NotebookVisibility = NotebookVisibility.TEAM,
        authorId: String? = null,
        externalUpdatesAllowed: Boolean = true,
    ): EngineeringNotebookEntry = withContext(Dispatchers.IO) {
        val normalizedId = sanitizeEntryId(entryId) ?: "note-${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val workspaceIdentity = workspace.toNotebookWorkspaceIdentity()
        val latest = databaseService.integrations.getLatestNotebookRevision(normalizedId)
        val nextRevision = (latest?.revision ?: 0) + 1
        val sanitizedBody = redactSecrets(markdownBody.trim())
            .ifBlank { "# Untitled Engineering Entry\n" }
            .take(MAX_DRAFT_MARKDOWN_CHARACTERS)
        val sanitizedEvidence = evidence.map(::sanitizeEvidenceReference)
            .distinctBy { "${it.kind}|${it.referenceId}" }
        val cleanAuthor = authorId?.trim()?.takeIf(String::isNotBlank)?.take(128)
        val nowMs = maxOf(clock.millis(), latest?.updatedAtMs ?: 0L, latest?.createdAtMs ?: 0L)
        val createdAtMs = latest?.createdAtMs ?: nowMs

        val contentHash = EngineeringNotebookHasher.sha256(
            entryId = normalizedId,
            revision = nextRevision,
            entryType = entryType,
            workspace = workspaceIdentity,
            markdownBody = sanitizedBody,
            evidence = sanitizedEvidence,
            visibility = visibility,
            humanAuthorId = cleanAuthor,
            aiProvenance = null,
        )
        val entry = EngineeringNotebookEntry(
            entryId = normalizedId,
            revision = nextRevision,
            entryType = entryType,
            workspace = workspaceIdentity,
            markdownBody = sanitizedBody,
            evidence = sanitizedEvidence,
            visibility = visibility,
            reviewState = NotebookReviewState.DRAFT,
            humanAuthorId = cleanAuthor,
            humanReviewerId = null,
            aiProvenance = null,
            contentHash = contentHash,
            createdAtMs = createdAtMs,
            updatedAtMs = nowMs,
        )
        val allowExternal = externalUpdatesAllowed &&
            sanitizedEvidence.none { it.label?.contains("simulation", ignoreCase = true) == true }
        databaseService.saveEngineeringNotebookRevision(
            entry = entry,
            commitRange = null,
            externalUpdatesAllowed = allowExternal,
        )
        entry
    }

    suspend fun approveRevision(
        entryId: String,
        revision: Int,
        reviewerId: String,
    ): EngineeringNotebookEntry = withContext(Dispatchers.IO) {
        val existing = databaseService.integrations.getNotebookRevision(entryId.trim(), revision)
            ?: throw IllegalArgumentException("Notebook revision $entryId r$revision was not found")
        val reviewer = reviewerId.trim().ifBlank { "team-reviewer" }.take(128)
        val updatedAt = maxOf(clock.millis(), existing.createdAtMs, existing.updatedAtMs)
        val approved = existing.copy(
            reviewState = NotebookReviewState.APPROVED,
            humanReviewerId = reviewer,
            updatedAtMs = updatedAt,
        )
        databaseService.integrations.saveNotebookRevision(approved)
        approved
    }

    suspend fun captureGuidedRunReview(
        workspace: WorkspaceConfig,
        report: GuidedRunAnalysisReport,
        comparison: RunComparisonReport? = null,
        authorId: String? = null,
    ): EngineeringNotebookEntry {
        val session = report.session
        val isSim = session.tags.isSimulationSessionTags()
        val runLabel = session.matchNumber?.let { "Match #$it" } ?: "Run ${session.sessionId.take(8)}"
        val envTag = if (isSim) "simulation" else "robot-telemetry"
        val tags = (listOf("guided-review", envTag) + session.tags).distinct()

        val evidenceLines = buildList {
            add("- **Source**: ${report.source.kind.label} (${report.source.explanation})")
            add("- **Confidence**: ${report.evidenceContext.confidence.label} — ${report.evidenceContext.confidenceExplanation}")
            report.metrics.forEach { m ->
                add("- **${m.label}**: `${m.value} ${m.unit}` _(${m.evidenceSource})_")
            }
            if (report.findings.isNotEmpty()) {
                add("")
                add("### Diagnostic Observations")
                report.findings.take(6).forEach { f ->
                    add("- **[${f.statusText}] ${f.title}**: ${f.observedEvidence}")
                }
            }
            comparison?.let { comp ->
                add("")
                add("### Paired Run Comparison (`${comp.selectedAlignment.label}`)")
                comp.findings.take(5).forEach { cf ->
                    add("- **[${cf.kind.label}] ${cf.title}**: ${cf.explanation}")
                }
            }
        }.joinToString("\n")

        val nextSteps = buildList {
            report.nextActions.forEach { a -> add("- **${a.title}**: ${a.reason}") }
            report.limitations.take(3).forEach { lim -> add("- _Boundary_: $lim") }
        }.joinToString("\n").ifBlank { "Review replay evidence and verify one bounded adjustment before the next run." }

        val markdown = NotebookSectionParser.renderMarkdown(
            StructuredNotebookSections(
                title = "$runLabel — Guided Evidence Review",
                problemHypothesis = "Evaluate recorded ${if (isSim) "simulation" else "robot"} run `${session.sessionId.take(12)}` for loop timing, battery health, localization stability, and fault triggers.",
                changeImplemented = "Analyzed persisted run configuration (${session.allianceColor?.uppercase(Locale.US) ?: "Unassigned alliance"}, tags: ${session.tags.joinToString().ifBlank { "none" }}).",
                measuredEvidence = evidenceLines,
                conclusionNextSteps = nextSteps,
                tags = tags,
            )
        )

        val evidenceRefs = buildList {
            add(
                NotebookEvidenceReference(
                    kind = "session",
                    referenceId = "session:${session.sessionId}",
                    sha256 = report.source.sha256,
                    label = "$runLabel ($envTag)",
                )
            )
            comparison?.sessions
                ?.filter { it.sessionId != session.sessionId }
                ?.forEach { other ->
                    add(
                        NotebookEvidenceReference(
                            kind = "session",
                            referenceId = "session:${other.sessionId}",
                            label = "Comparison run ${other.sessionId.take(8)}",
                        )
                    )
                }
            report.alerts.take(5).forEach { alert ->
                add(
                    NotebookEvidenceReference(
                        kind = "alert",
                        referenceId = "alert:${alert.alertId}",
                        label = "${alert.ruleKey} peak=${alert.peakValue}",
                    )
                )
            }
        }

        return saveEditedEntry(
            workspace = workspace,
            entryId = "guided-run-${sanitizeSlug(session.sessionId, 24)}",
            entryType = NotebookEntryType.SESSION_SUMMARY,
            markdownBody = markdown,
            evidence = evidenceRefs,
            authorId = authorId,
            externalUpdatesAllowed = report.summary?.allowsAutomaticExternalUpdates() ?: !isSim,
        )
    }

    suspend fun captureMatchDebrief(
        workspace: WorkspaceConfig,
        session: Session,
        summary: SessionSummary?,
        insights: List<MatchCoachingInsight>,
        heatmap: SpatialFieldHeatmap,
        alerts: List<AlertRecord>,
        annotations: List<SessionAnnotation>,
        authorId: String? = null,
    ): EngineeringNotebookEntry {
        val isSim = session.tags.isSimulationSessionTags()
        val matchLabel = session.matchNumber?.let { "Match #$it" } ?: "Session ${session.sessionId.take(8)}"
        val alliance = session.allianceColor?.uppercase(Locale.US) ?: "PRACTICE"

        val evidenceLines = buildList {
            add("- **Spatial Trajectory**: `${String.format(Locale.US, "%.1f", heatmap.totalDistanceMeters)} m` traveled, peak speed `${String.format(Locale.US, "%.2f", heatmap.peakSpeedMps)} m/s`, max field voltage sag `${String.format(Locale.US, "%.2f", heatmap.maxSagVolts)} V` (${heatmap.poseSampleCount} pose samples)")
            summary?.let { s ->
                add("- **Electrical & Control**: Min battery `${String.format(Locale.US, "%.2f", s.minBatteryVoltage)} V`, P95 loop `${String.format(Locale.US, "%.2f", s.p95LoopTimeMs)} ms`, Max EKF drift `${String.format(Locale.US, "%.3f", s.maxEkfDrift)} m`")
            }
            if (insights.isNotEmpty()) {
                add("")
                add("### Replay-Linked Coaching Insights")
                insights.take(6).forEach { insight ->
                    add("- **[${insight.severity.name}] ${insight.title}** (`${insight.metricBadge}`): ${insight.detail}")
                }
            }
            if (annotations.isNotEmpty()) {
                add("")
                add("### Operator & Scout Annotations")
                annotations.take(5).forEach { note ->
                    add("- `${note.effectiveTimestampMs()} ms`: ${note.text}")
                }
            }
        }.joinToString("\n")

        val markdown = NotebookSectionParser.renderMarkdown(
            StructuredNotebookSections(
                title = "$matchLabel ($alliance) — Match Strategy & Pit Debrief",
                problemHypothesis = "Review spatial field occupancy, cycle bottlenecks, electrical sag zones, and fault alerts from $matchLabel.",
                changeImplemented = "Executed $alliance match configuration (tags: ${session.tags.joinToString().ifBlank { "none" }}).",
                measuredEvidence = evidenceLines,
                conclusionNextSteps = insights.firstOrNull()?.let { "Priority pit focus before next match: ${it.title} — ${it.detail}" }
                    ?: "Complete pit battery swap and mechanical fastener check before queuing.",
                tags = (listOf("match-debrief", alliance.lowercase(Locale.US)) + session.tags).distinct(),
            )
        )

        val refs = buildList {
            add(NotebookEvidenceReference("session", "session:${session.sessionId}", label = "$matchLabel ($alliance)"))
            alerts.take(5).forEach { a ->
                add(NotebookEvidenceReference("alert", "alert:${a.alertId}", label = "${a.ruleKey} peak=${a.peakValue}"))
            }
        }

        return saveEditedEntry(
            workspace = workspace,
            entryId = "match-debrief-${sanitizeSlug(session.sessionId, 24)}",
            entryType = NotebookEntryType.SESSION_SUMMARY,
            markdownBody = markdown,
            evidence = refs,
            authorId = authorId,
            externalUpdatesAllowed = !isSim,
        )
    }

    suspend fun captureTuningExperiment(
        workspace: WorkspaceConfig,
        experiment: GuidedTuningExperiment,
        authorId: String? = null,
    ): EngineeringNotebookEntry {
        val beforeStr = formatExperimentNumber(experiment.change.before.doubleValue) ?: experiment.change.before.intValue?.toString()
            ?: experiment.change.before.booleanValue?.toString() ?: experiment.change.before.textValue.orEmpty()
        val proposedStr = formatExperimentNumber(experiment.change.proposed.doubleValue) ?: experiment.change.proposed.intValue?.toString()
            ?: experiment.change.proposed.booleanValue?.toString() ?: experiment.change.proposed.textValue.orEmpty()
        val unitSuffix = experiment.change.unit.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()

        val evalSection = experiment.evaluation?.let { ev ->
            buildString {
                appendLine("- **Baseline Run**: `${experiment.baselineSessionId}` -> `${formatExperimentNumber(ev.baselineValue) ?: "n/a"} ${ev.unit}`")
                appendLine("- **Candidate Run**: `${experiment.candidateSessionId ?: "unrecorded"}` -> `${formatExperimentNumber(ev.candidateValue) ?: "n/a"} ${ev.unit}`")
                appendLine("- **Measured Delta**: `${formatExperimentNumber(ev.absoluteDelta) ?: "n/a"} ${ev.unit}` (`${formatExperimentNumber(ev.percentDelta) ?: "n/a"}%`) — Outcome: **${ev.outcome.name}**")
                appendLine("- **Summary**: ${ev.summary}")
                ev.limitations.take(3).forEach { appendLine("- _Boundary_: $it") }
            }.trim()
        } ?: "Candidate simulation evaluation has not been completed yet (baseline `${experiment.baselineSessionId}`)."

        val markdown = NotebookSectionParser.renderMarkdown(
            StructuredNotebookSections(
                title = "Tuning Experiment — ${experiment.title}",
                problemHypothesis = listOfNotNull(
                    experiment.question.takeIf(String::isNotBlank)?.let { "**Question**: $it" },
                    "**Hypothesis**: ${experiment.hypothesis}",
                    "**Triggering Observation**: [${experiment.finding.claimKind}] ${experiment.finding.title} — ${experiment.finding.explanation}",
                ).joinToString("\n\n"),
                changeImplemented = buildString {
                    appendLine("- **Parameter**: `${experiment.change.displayName}` (`${experiment.change.key}`)")
                    appendLine("- **Single Bounded Step**: `$beforeStr$unitSuffix` -> `$proposedStr$unitSuffix` (policy `${experiment.change.applyPolicy}`)")
                    appendLine("- **Configuration Snapshot SHA-256**: `${experiment.snapshot.snapshotSha256}`")
                    if (experiment.heldConstants.isNotEmpty()) {
                        appendLine("- **Held Constants**: ${experiment.heldConstants.joinToString(", ")}")
                    }
                }.trim(),
                measuredEvidence = evalSection,
                conclusionNextSteps = buildString {
                    appendLine("- **Decision**: `${experiment.decision.name}` (Phase `${experiment.phase.name}`)")
                    if (experiment.decisionNote.isNotBlank()) appendLine("- **Rationale**: ${experiment.decisionNote}")
                    if (experiment.nextTest.isNotBlank()) appendLine("- **Next Safe Verification Step**: ${experiment.nextTest}")
                    appendLine("- _Simulation Boundary_: Local simulation results never prove causation or substitute for supervised physical robot validation.")
                }.trim(),
                tags = listOf("tuning-experiment", "simulation", experiment.change.key.substringAfterLast('/')),
            )
        )

        val refs = buildList {
            add(NotebookEvidenceReference("session", "session:${experiment.baselineSessionId}", label = "Baseline simulation run"))
            experiment.candidateSessionId?.takeIf(String::isNotBlank)?.let { candId ->
                add(NotebookEvidenceReference("session", "session:$candId", label = "Candidate simulation run"))
            }
            add(
                NotebookEvidenceReference(
                    kind = "snapshot",
                    referenceId = "profile:${experiment.snapshot.profileUid}",
                    sha256 = experiment.snapshot.snapshotSha256,
                    label = "Tuning snapshot (${experiment.change.key})",
                )
            )
        }

        return saveEditedEntry(
            workspace = workspace,
            entryId = "tuning-${sanitizeSlug(experiment.uid, 32)}",
            entryType = NotebookEntryType.SOFTWARE_CHANGE,
            markdownBody = markdown,
            evidence = refs,
            authorId = authorId,
            externalUpdatesAllowed = false,
        )
    }

    suspend fun captureVerificationReport(
        workspace: WorkspaceConfig,
        report: RobotVerificationReport,
        authorId: String? = null,
    ): EngineeringNotebookEntry {
        val layerSummary = VerificationLayer.entries.mapNotNull { layer ->
            val items = report.items.filter { it.layer == layer }
            if (items.isEmpty()) return@mapNotNull null
            val passed = items.count { it.status == VerificationResultStatus.PASSED }
            val failed = items.count { it.status == VerificationResultStatus.FAILED }
            val blocked = items.count { it.status == VerificationResultStatus.BLOCKED }
            "- **${layer.name.replace('_', ' ')}**: $passed/${items.size} passed" +
                (if (failed > 0) ", $failed failed" else "") +
                (if (blocked > 0) ", $blocked blocked" else "")
        }.joinToString("\n")

        val notableFailures = report.items
            .filter { it.status == VerificationResultStatus.FAILED || it.status == VerificationResultStatus.BLOCKED }
            .take(8)
            .joinToString("\n") { "- **[${it.status.name}] ${it.title}**: ${it.explanation}" }

        val markdown = NotebookSectionParser.renderMarkdown(
            StructuredNotebookSections(
                title = "Robot Verification Gate — ${report.passedCount}/${report.items.size} Checks Passed",
                problemHypothesis = "Verify canonical `.ares` robot architecture, generated safety contracts, simulator checks, and compilation before physical hardware testing.",
                changeImplemented = "Executed `${report.provenance.command.joinToString(" ")}` against `.ares` content hash `${report.provenance.canonicalContentHash.take(16)}…` (ARES `${report.provenance.aresVersion}`, Studio `${report.provenance.studioVersion}`).",
                measuredEvidence = listOfNotNull(
                    "- **Totals**: ${report.passedCount} passed · ${report.failedCount} failed · ${report.blockedCount} blocked · ${report.notRunCount} not measured (build exit `${report.provenance.buildExitCode}`)",
                    layerSummary,
                    notableFailures.takeIf(String::isNotBlank)?.let { "### Attention Items\n$it" },
                ).joinToString("\n\n"),
                conclusionNextSteps = if (report.readyForPhysicalValidation) {
                    "Software and simulation gates passed. Proceed to supervised Pit Self-Test (`PIT_DIAGNOSTICS`) to verify physical port mapping and phase direction."
                } else {
                    "Resolve failing or blocked verification checks and re-run `Verify & build` before deploying to physical hardware."
                },
                tags = listOf("verification", report.league.name.lowercase(Locale.US)),
            )
        )

        val refs = listOf(
            NotebookEvidenceReference(
                kind = "verification",
                referenceId = "verification:${report.provenance.runId}",
                sha256 = report.provenance.canonicalContentHash,
                label = "${report.passedCount}/${report.items.size} passed (exit ${report.provenance.buildExitCode})",
            )
        )

        return saveEditedEntry(
            workspace = workspace,
            entryId = "verification-${sanitizeSlug(report.provenance.runId, 28)}",
            entryType = NotebookEntryType.SOFTWARE_CHANGE,
            markdownBody = markdown,
            evidence = refs,
            authorId = authorId,
            externalUpdatesAllowed = true,
        )
    }

    suspend fun captureCohortComparison(
        workspace: WorkspaceConfig,
        comparison: CohortComparisonResult,
        authorId: String? = null,
    ): EngineeringNotebookEntry {
        val markdown = NotebookSectionParser.renderMarkdown(
            StructuredNotebookSections(
                title = "Cohort Distribution Study — ${comparison.metricLabel} (${comparison.splitDescription})",
                problemHypothesis = "Compare multi-run telemetry distributions across `${comparison.splitDescription}` for **${comparison.metricLabel}** (`${comparison.unit}`) rather than relying on a single run.",
                changeImplemented = "Partitioned recorded workspace runs using `${comparison.splitMode.label}`.",
                measuredEvidence = comparison.markdownEvidenceBlock,
                conclusionNextSteps = "${comparison.plainEnglishTakeaway} Confirm outlier runs in Replay before locking competition configuration.",
                tags = listOf("cohort-analytics", comparison.metricId),
            )
        )

        val refs = buildList {
            comparison.cohortA?.samples?.take(6)?.forEach { s ->
                add(NotebookEvidenceReference("session", "session:${s.sessionId}", label = "${comparison.cohortA.cohortName}: ${s.sessionLabel}"))
            }
            comparison.cohortB?.samples?.take(6)?.forEach { s ->
                add(NotebookEvidenceReference("session", "session:${s.sessionId}", label = "${comparison.cohortB.cohortName}: ${s.sessionLabel}"))
            }
        }.distinctBy { it.referenceId }

        val slug = Sha256.prefixHex("${comparison.metricId}|${comparison.splitDescription}", byteCount = 6)
        return saveEditedEntry(
            workspace = workspace,
            entryId = "cohort-${sanitizeSlug(comparison.metricId, 20)}-$slug",
            entryType = NotebookEntryType.ENGINEERING_NOTE,
            markdownBody = markdown,
            evidence = refs,
            authorId = authorId,
            externalUpdatesAllowed = true,
        )
    }

    suspend fun captureSingleSessionSummary(
        workspace: WorkspaceConfig,
        session: Session,
        summary: SessionSummary?,
        authorId: String? = null,
    ): EngineeringNotebookEntry {
        val runLabel = session.matchNumber?.let { "Match #$it" } ?: "Run ${session.sessionId.take(8)}"
        val isSim = session.tags.isSimulationSessionTags()
        val metricsMd = if (summary != null) {
            buildString {
                appendLine("- **Duration**: `${String.format(Locale.US, "%.1f", summary.durationMs / 1000.0)} s`")
                appendLine("- **Min Battery Voltage**: `${String.format(Locale.US, "%.2f", summary.minBatteryVoltage)} V`")
                appendLine("- **Avg / P95 Loop Time**: `${String.format(Locale.US, "%.2f", summary.avgLoopTimeMs)} ms` / `${String.format(Locale.US, "%.2f", summary.p95LoopTimeMs)} ms`")
                appendLine("- **Max EKF Drift**: `${String.format(Locale.US, "%.3f", summary.maxEkfDrift)} m`")
                appendLine("- **Avg Cross-Track Error**: `${String.format(Locale.US, "%.1f", summary.avgCrossTrackError * 100.0)} cm`")
            }.trim()
        } else {
            "- Recorded duration `${String.format(Locale.US, "%.1f", session.durationMs / 1000.0)} s` (precomputed summary pending)."
        }

        val markdown = NotebookSectionParser.renderMarkdown(
            StructuredNotebookSections(
                title = "$runLabel — Run Summary Note",
                problemHypothesis = "Document baseline telemetry observations for $runLabel.",
                changeImplemented = "Alliance: `${session.allianceColor ?: "Practice"}` · Tags: `${session.tags.joinToString().ifBlank { "none" }}`.",
                measuredEvidence = metricsMd,
                conclusionNextSteps = "Add student/mentor engineering interpretation and next test action.",
                tags = (listOf("run-summary") + session.tags).distinct(),
            )
        )
        return saveEditedEntry(
            workspace = workspace,
            entryId = "run-note-${sanitizeSlug(session.sessionId, 24)}",
            entryType = NotebookEntryType.SESSION_SUMMARY,
            markdownBody = markdown,
            evidence = listOf(
                NotebookEvidenceReference("session", "session:${session.sessionId}", label = runLabel)
            ),
            authorId = authorId,
            externalUpdatesAllowed = !isSim,
        )
    }

    suspend fun buildWorkspacePortfolioMarkdown(
        workspace: WorkspaceConfig,
        entries: List<EngineeringNotebookEntry>,
    ): String = withContext(Dispatchers.IO) {
        val (sessions, summaries) = loadWorkspaceSessionsAndSummaries(workspace)
        val id = workspace.toNotebookWorkspaceIdentity()
        EngineeringPortfolioCompiler.compileMarkdownPortfolio(
            workspaceName = workspace.robotName.ifBlank { id.robotId },
            teamId = id.teamId,
            seasonId = id.seasonId,
            robotId = id.robotId,
            league = workspace.league,
            entries = entries,
            sessions = sessions,
            summaries = summaries,
            generatedAtMs = clock.millis(),
        )
    }

    suspend fun buildWorkspacePortfolioHtml(
        workspace: WorkspaceConfig,
        entries: List<EngineeringNotebookEntry>,
    ): String = withContext(Dispatchers.IO) {
        val (sessions, summaries) = loadWorkspaceSessionsAndSummaries(workspace)
        val id = workspace.toNotebookWorkspaceIdentity()
        EngineeringPortfolioCompiler.compileHtmlPortfolio(
            workspaceName = workspace.robotName.ifBlank { id.robotId },
            teamId = id.teamId,
            seasonId = id.seasonId,
            robotId = id.robotId,
            league = workspace.league,
            entries = entries,
            sessions = sessions,
            summaries = summaries,
            generatedAtMs = clock.millis(),
        )
    }

    suspend fun exportWorkspacePortfolioMarkdown(
        workspace: WorkspaceConfig,
        entries: List<EngineeringNotebookEntry>,
        destination: File,
    ) {
        val content = buildWorkspacePortfolioMarkdown(workspace, entries)
        exportService.writeTextReportAtomically(destination, content)
    }

    suspend fun exportWorkspacePortfolioHtml(
        workspace: WorkspaceConfig,
        entries: List<EngineeringNotebookEntry>,
        destination: File,
    ) {
        val content = buildWorkspacePortfolioHtml(workspace, entries)
        exportService.writeTextReportAtomically(destination, content)
    }

    private suspend fun loadWorkspaceSessionsAndSummaries(
        workspace: WorkspaceConfig,
    ): Pair<List<Session>, List<SessionSummary>> {
        val sessions = if (workspace.teamId.isNotBlank() && workspace.seasonId.isNotBlank() && workspace.robotId.isNotBlank()) {
            databaseService.getSessionsForWorkspace(workspace.teamId, workspace.seasonId, workspace.robotId)
        } else {
            emptyList()
        }
        if (sessions.isEmpty()) return emptyList<Session>() to emptyList()
        val ids = sessions.mapTo(HashSet(), Session::sessionId)
        val summaries = databaseService.getAllSessionSummaries().filter { it.sessionId in ids }
        return sessions to summaries
    }

    private fun sanitizeEvidenceReference(ref: NotebookEvidenceReference): NotebookEvidenceReference =
        NotebookEvidenceReference(
            kind = ref.kind.filterNot(Char::isISOControl).trim().ifBlank { "artifact" }.take(64),
            referenceId = ref.referenceId.filterNot(Char::isISOControl).trim().ifBlank { "unknown" }.take(256),
            sha256 = ref.sha256?.trim()?.takeIf(String::isNotBlank)?.take(128),
            label = ref.label?.let(::redactSecrets)?.filterNot(Char::isISOControl)?.trim()?.take(256),
            uri = ref.uri?.filterNot(Char::isISOControl)?.trim()?.takeIf(String::isNotBlank)?.take(512),
        )

    private fun sanitizeEntryId(raw: String?): String? = raw
        ?.trim()
        ?.filterNot(Char::isISOControl)
        ?.replace(Regex("[^A-Za-z0-9._:-]"), "-")
        ?.take(128)
        ?.takeIf(String::isNotBlank)

    private fun sanitizeSlug(raw: String, maxLen: Int): String = raw
        .trim()
        .replace(Regex("[^A-Za-z0-9._-]"), "-")
        .trim('-')
        .ifBlank { "item" }
        .take(maxLen)

    private fun formatExperimentNumber(value: Double?): String? = when {
        value == null || !value.isFinite() -> null
        else -> BigDecimal.valueOf(value).setScale(5, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}

fun WorkspaceConfig.toNotebookWorkspaceIdentity(): IntegrationWorkspaceIdentity =
    IntegrationWorkspaceIdentity(
        teamId = teamId.trim().ifBlank { "unassigned-team" },
        seasonId = seasonId.trim().ifBlank { "default-season" },
        robotId = robotId.trim().ifBlank { id.trim().ifBlank { "default-robot" } },
    )
