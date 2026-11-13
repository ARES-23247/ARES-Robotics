package com.ares.analytics.service.integration

import com.ares.analytics.domain.notebook.NotebookSectionParser
import com.ares.analytics.domain.notebook.StructuredNotebookSections
import com.ares.analytics.domain.pit.BatteryHealthSummary
import com.ares.analytics.domain.pit.formatBatterySessionTag
import com.ares.analytics.domain.pit.normalizeBatteryId
import com.ares.analytics.shared.models.NotebookEvidenceReference
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.util.Sha256
import java.util.Locale

internal data class FormattedPitNotebookEntry(
    val entryIdSlug: String,
    val markdownBody: String,
    val evidence: List<NotebookEvidenceReference>,
)

internal object PitOpsNotebookCaptureFormatter {

    fun formatPitReadiness(
        projectLabel: String,
        matchBadge: String,
        batteryId: String?,
        connected: Boolean,
        streamLive: Boolean,
        observedSignalsSummary: List<String>,
        warningSignalsSummary: List<String>,
        checkedCrewItems: List<String>,
        uncheckedCrewItems: List<String>,
        receiptMarkdown: String,
    ): FormattedPitNotebookEntry {
        val cleanBattery = batteryId?.let(::normalizeBatteryId)?.takeIf(String::isNotEmpty)
        val badgeLabel = matchBadge.trim().ifBlank { "Pit Check" }
        val receiptHash = Sha256.hex(receiptMarkdown)
        val linkStatus = when {
            !connected -> "Offline (link disconnected)"
            streamLive -> "Connected (NT4 telemetry stream live)"
            else -> "Connected (telemetry heartbeat stale)"
        }

        val evidenceMd = buildString {
            appendLine("- **Project Profile**: `$projectLabel`")
            appendLine("- **Match / Assignment**: `$badgeLabel`")
            cleanBattery?.let { appendLine("- **Assigned Battery Pack**: `$it` (`${formatBatterySessionTag(it)}`)") }
            appendLine("- **Telemetry Link**: $linkStatus")
            appendLine("- **Passive Signals Observed (${observedSignalsSummary.size})**:")
            if (observedSignalsSummary.isEmpty()) {
                appendLine("  - None observed at capture time.")
            } else {
                observedSignalsSummary.take(12).forEach { appendLine("  - $it") }
            }
            if (warningSignalsSummary.isNotEmpty()) {
                appendLine("- **Warnings Flagged (${warningSignalsSummary.size})**:")
                warningSignalsSummary.take(10).forEach { appendLine("  - ⚠️ $it") }
            }
            appendLine("- **Manual Pit Crew Sign-Offs (${checkedCrewItems.size} checked / ${uncheckedCrewItems.size} pending)**:")
            checkedCrewItems.forEach { appendLine("  - [x] $it") }
            uncheckedCrewItems.forEach { appendLine("  - [ ] $it") }
        }.trim()

        val nextStep = when {
            warningSignalsSummary.isNotEmpty() ->
                "Resolve flagged telemetry warnings before robot placement on the field queue."
            uncheckedCrewItems.isNotEmpty() ->
                "Complete remaining ${uncheckedCrewItems.size} physical pit crew sign-off item(s) prior to match start."
            else ->
                "Hardware signals observed and physical pit crew checklist signed off; proceed to match queue."
        }

        val tags = buildList {
            add("pit-readiness")
            cleanBattery?.let { formatBatterySessionTag(it)?.let(::add) }
        }.distinct()

        val markdown = NotebookSectionParser.renderMarkdown(
            StructuredNotebookSections(
                title = "Pit Readiness Receipt — $badgeLabel",
                problemHypothesis = "Verify pre-match electrical/sensor readiness and crew physical sign-offs for $badgeLabel.",
                changeImplemented = "Executed passive read-only NT4 observation check and physical turnaround checklist" +
                    (cleanBattery?.let { " with pack `$it`." } ?: "."),
                measuredEvidence = evidenceMd,
                conclusionNextSteps = nextStep,
                tags = tags,
            )
        )

        val refs = buildList {
            add(
                NotebookEvidenceReference(
                    kind = "pit_receipt",
                    referenceId = "pit-receipt:${receiptHash.take(16)}",
                    sha256 = receiptHash,
                    label = badgeLabel,
                )
            )
            cleanBattery?.let { bat ->
                add(
                    NotebookEvidenceReference(
                        kind = "battery_pack",
                        referenceId = "battery:$bat",
                        label = "Assigned pack $bat",
                    )
                )
            }
        }

        val slug = "$badgeLabel-${receiptHash.take(8)}"
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { "check" }
        return FormattedPitNotebookEntry(
            entryIdSlug = slug,
            markdownBody = markdown,
            evidence = refs,
        )
    }

    fun formatBatteryFleetSummary(
        workspace: WorkspaceConfig,
        summaries: List<BatteryHealthSummary>,
        recommendedBatteryId: String?,
    ): FormattedPitNotebookEntry {
        val robotLabel = workspace.robotName.ifBlank { workspace.robotId }
        val primeCount = summaries.count { it.grade.name == "COMPETITION_PRIME" }
        val retireCount = summaries.count { it.grade.name == "RETIRE_OR_INSPECT" }
        val tableMd = if (summaries.isEmpty()) {
            "- No batteries registered or tagged with `bat:<ID>` yet."
        } else {
            buildString {
                recommendedBatteryId?.let { appendLine("**Recommended Next-Match Pack**: `$it`\n") }
                appendLine("| Pack | Status | Grade | Hardware Cycles | Resting V | Beak mΩ | Telemetry mΩ | Worst Vmin |")
                appendLine("| :--- | :--- | :--- | ---: | ---: | ---: | ---: | ---: |")
                summaries.forEach { s ->
                    val rest = s.latestRestingVoltage?.let { String.format(Locale.US, "%.2f V", it) } ?: "—"
                    val beak = s.latestBeakMilliohms?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
                    val tele = s.medianTelemetryMilliohms?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
                    val vmin = s.worstMinVoltage?.let { String.format(Locale.US, "%.2f V", it) } ?: "—"
                    appendLine("| `${s.batteryId}` | ${s.status.label} | ${s.grade.label} | ${s.hardwareCycleCount} | $rest | $beak | $tele | $vmin |")
                }
            }.trim()
        }

        val markdown = NotebookSectionParser.renderMarkdown(
            StructuredNotebookSections(
                title = "Battery Fleet Health Audit — $robotLabel",
                problemHypothesis = "Track competition pack internal resistance degradation, resting voltage, and brownout margin across matches.",
                changeImplemented = "Aggregated ${summaries.size} battery pack(s) from DuckDB check-ins and hardware telemetry summaries ($primeCount Competition Prime, $retireCount flagged Retire/Inspect).",
                measuredEvidence = tableMd,
                conclusionNextSteps = recommendedBatteryId?.let {
                    "Queue pack `$it` for the next match; inspect or relegate any Practice/Retire packs."
                } ?: "Log a Battery Beak check-in and tag hardware sessions with `bat:<ID>` to establish baseline R_int.",
                tags = listOf("battery-fleet", "pit-ops"),
            )
        )

        val sessionRefs = summaries
            .flatMap { it.recentRuns }
            .filterNot { it.isSimulation }
            .distinctBy { it.sessionId }
            .take(8)
            .map { run ->
                NotebookEvidenceReference(
                    kind = "session",
                    referenceId = "session:${run.sessionId}",
                    label = run.matchNumber?.let { "Match #$it" } ?: "Session ${run.sessionId.take(8)}",
                )
            }
        val contentHash = Sha256.hex(markdown)
        val slug = "${workspace.seasonId}-${workspace.robotId}"
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { "workspace" }
        return FormattedPitNotebookEntry(
            entryIdSlug = slug,
            markdownBody = markdown,
            evidence = listOf(
                NotebookEvidenceReference(
                    kind = "battery_fleet_snapshot",
                    referenceId = "battery-fleet:${contentHash.take(12)}",
                    sha256 = contentHash,
                    label = "${summaries.size} battery packs audited",
                )
            ) + sessionRefs,
        )
    }
}
