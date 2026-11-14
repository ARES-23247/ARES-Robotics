package com.ares.analytics.service.integration

import com.ares.analytics.domain.notebook.NotebookSectionParser
import com.ares.analytics.domain.notebook.StructuredNotebookSections
import com.ares.analytics.domain.strategy.MatchupBriefingReport
import com.ares.analytics.domain.strategy.OprSampleConfidence
import com.ares.analytics.domain.strategy.PicklistWeightPreset
import com.ares.analytics.domain.strategy.RankedPicklistTeamRow
import com.ares.analytics.shared.models.NotebookEvidenceReference
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.util.Sha256
import java.util.Locale

internal data class FormattedStrategyNotebookEntry(
    val entryIdSlug: String,
    val markdownBody: String,
    val evidence: List<NotebookEvidenceReference>,
)

internal object StrategyNotebookCaptureFormatter {

    fun formatPreMatchBriefing(
        workspace: WorkspaceConfig,
        report: MatchupBriefingReport,
    ): FormattedStrategyNotebookEntry {
        val eventLabel = workspace.eventCode?.takeIf(String::isNotBlank) ?: "Event"
        val matchLabel = "${report.compLevel.uppercase(Locale.US)} #${report.matchNumber}"
        val sideText = report.ourAlliance?.let { "Our Alliance: **$it**" } ?: "Observer View"

        val evidenceMd = buildString {
            appendLine("- **Event / Profile**: `$eventLabel` · Team `${workspace.teamId}` · $sideText")
            appendLine(
                "- **Projected Non-Penalty Score**: Red `${String.format(Locale.US, "%.1f", report.projectedRedNpScore)}` vs " +
                    "Blue `${String.format(Locale.US, "%.1f", report.projectedBlueNpScore)}` (${report.confidence.label})",
            )
            if (report.projectedRedAuto != null && report.projectedBlueAuto != null) {
                appendLine(
                    "- **Projected Auto Points**: Red `${String.format(Locale.US, "%.1f", report.projectedRedAuto)}` vs " +
                        "Blue `${String.format(Locale.US, "%.1f", report.projectedBlueAuto)}`",
                )
            }
            if (report.projectedRedEndgame != null && report.projectedBlueEndgame != null) {
                appendLine(
                    "- **Projected Endgame Points**: Red `${String.format(Locale.US, "%.1f", report.projectedRedEndgame)}` vs " +
                        "Blue `${String.format(Locale.US, "%.1f", report.projectedBlueEndgame)}`",
                )
            }
            report.recordedResult?.takeIf { it.isPlayed }?.let { res ->
                appendLine(
                    "- **Official Result**: Red `${res.redTotalScore}` (`${res.redNonPenaltyScore()}` NP) vs " +
                        "Blue `${res.blueTotalScore}` (`${res.blueNonPenaltyScore()}` NP)",
                )
            }
            appendLine()
            appendLine("| Alliance | Team | NP-OPR | ccWM | Auto Zone | Endgame | Stand Reliability |")
            appendLine("| --- | --- | ---: | ---: | --- | --- | --- |")
            report.redTeams.forEach { t -> appendTeamBriefRow("RED", t) }
            report.blueTeams.forEach { t -> appendTeamBriefRow("BLUE", t) }
        }.trim()

        val talkingSummary = report.talkingPoints.joinToString(" ") { "• $it" }
        val markdown = NotebookSectionParser.renderMarkdown(
            StructuredNotebookSections(
                title = "Pre-Match Strategy Plan — $matchLabel ($eventLabel)",
                problemHypothesis = "Align autonomous start lanes, cycle targets, and defensive assignments ahead of $matchLabel.",
                changeImplemented = "Synthesized NP-OPR/ccWM event regression with pit start-zone data and stand scouting logs.",
                measuredEvidence = evidenceMd,
                conclusionNextSteps = talkingSummary,
                tags = listOf("match-strategy", "match-${report.matchNumber}", eventLabel.lowercase(Locale.US)),
            ),
        )
        val contentHash = Sha256.hex(markdown)
        val slug = "${eventLabel}-${report.compLevel}-${report.matchNumber}"
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { "match-${report.matchNumber}" }
        return FormattedStrategyNotebookEntry(
            entryIdSlug = slug,
            markdownBody = markdown,
            evidence = listOf(
                NotebookEvidenceReference(
                    kind = "matchup_briefing",
                    referenceId = "briefing:${report.compLevel}:${report.matchNumber}:${contentHash.take(8)}",
                    sha256 = contentHash,
                    label = "$matchLabel strategy snapshot",
                ),
            ),
        )
    }

    fun formatAlliancePicklistRationale(
        workspace: WorkspaceConfig,
        preset: PicklistWeightPreset,
        confidence: OprSampleConfidence,
        rows: List<RankedPicklistTeamRow>,
    ): FormattedStrategyNotebookEntry {
        val eventLabel = workspace.eventCode?.takeIf(String::isNotBlank) ?: "Event"
        val topRows = rows.take(16)
        val tableMd = if (topRows.isEmpty()) {
            "- No candidate teams scored or scouted yet."
        } else {
            buildString {
                appendLine("- **Scoring Model**: `${preset.label}` · **OPR Confidence**: `${confidence.label}`")
                appendLine(
                    "- **Weights**: NP-OPR `${(preset.npOprWeight * 100).toInt()}%`, Auto `${(preset.autoWeight * 100).toInt()}%`, " +
                        "Endgame `${(preset.endgameWeight * 100).toInt()}%`, Synergy `${(preset.synergyWeight * 100).toInt()}%`, " +
                        "Defense/Driver `${(preset.defenseDriverWeight * 100).toInt()}%`",
                )
                appendLine()
                appendLine("| Rank | Team | Tier | Status | Composite | NP-OPR | ccWM | Auto Synergy |")
                appendLine("| ---: | --- | --- | --- | ---: | ---: | ---: | --- |")
                topRows.forEach { r ->
                    val np = r.opr?.npOpr?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
                    val ccwm = r.opr?.ccwm?.let { String.format(Locale.US, "%+.1f", it) } ?: "—"
                    appendLine(
                        "| ${r.displayRank} | `${r.teamNumber}` | ${r.override.tier.shortBadge} | ${r.override.draftStatus.label} | " +
                            "${String.format(Locale.US, "%.1f", r.compositePickScore)} | $np | $ccwm | ${r.autoZoneSynergyLabel} |",
                    )
                }
            }.trim()
        }

        val topThree = topRows.filter { it.override.draftStatus.name == "AVAILABLE" }.take(3)
        val conclusion = if (topThree.isNotEmpty()) {
            val summary = topThree.joinToString(", ") { "#${it.displayRank} Team ${it.teamNumber} (${String.format(Locale.US, "%.1f", it.compositePickScore)})" }
            "Top available candidates at lock time: $summary. Verify bumper clearance and auto delays before calling on field."
        } else {
            "Review Tier 1/Tier 2 candidate ordering and update Live Selection Draft status during alliance selection."
        }

        val markdown = NotebookSectionParser.renderMarkdown(
            StructuredNotebookSections(
                title = "Alliance Selection Picklist Rationale — $eventLabel (${preset.label})",
                problemHypothesis = "Quantify alliance partner selection using NP-OPR, endgame ceiling, reliability penalties, and auto-lane complementarity.",
                changeImplemented = "Evaluated ${rows.size} tournament candidate(s) under `${preset.label}` weighting with manual coach tier/pin overrides.",
                measuredEvidence = tableMd,
                conclusionNextSteps = conclusion,
                tags = listOf("alliance-selection", "picklist", eventLabel.lowercase(Locale.US)),
            ),
        )
        val contentHash = Sha256.hex(markdown)
        val slug = "${eventLabel}-${preset.name}"
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { "picklist" }
        return FormattedStrategyNotebookEntry(
            entryIdSlug = slug,
            markdownBody = markdown,
            evidence = listOf(
                NotebookEvidenceReference(
                    kind = "alliance_picklist",
                    referenceId = "picklist:${contentHash.take(12)}",
                    sha256 = contentHash,
                    label = "${rows.size} candidates (${preset.label})",
                ),
            ),
        )
    }

    private fun StringBuilder.appendTeamBriefRow(
        side: String,
        t: com.ares.analytics.domain.strategy.MatchupTeamBrief,
    ) {
        val np = t.opr?.npOpr?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
        val ccwm = t.opr?.ccwm?.let { String.format(Locale.US, "%+.1f", it) } ?: "—"
        val zone = t.pitProfile?.preferredAutoZone?.label ?: "Unknown"
        val end = t.pitProfile?.endgameCapability?.label ?: "Unknown"
        val rel = t.standSummary?.let { s -> "${s.disconnectCount} drops (${s.observationsCount} obs)" } ?: "No stand logs"
        val tag = if (t.isOurTeam) " (OUR ROBOT)" else ""
        appendLine("| $side | `${t.teamNumber}`$tag | $np | $ccwm | $zone | $end | $rel |")
    }
}
