package com.ares.analytics.domain.notebook

import com.ares.analytics.shared.models.EngineeringNotebookEntry
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.NotebookEntryType
import com.ares.analytics.shared.models.NotebookReviewState
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionSummary
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

data class SeasonMetricProgressionRow(
    val metricLabel: String,
    val unit: String,
    val lowerIsBetter: Boolean,
    val baselineSessionLabel: String,
    val latestSessionLabel: String,
    val baselineValue: Double,
    val latestValue: Double,
    val delta: Double,
    val improved: Boolean,
    val formattedBaseline: String,
    val formattedLatest: String,
    val formattedDelta: String,
)

/**
 * Deterministic compiler producing standalone Markdown (`.md`) and print-styled HTML (`.html`)
 * FIRST Engineering Portfolios from workspace notebook entries and quantitative run summaries.
 *
 * Preserves evidence boundaries: simulation vs. physical runs remain explicitly labeled, and
 * SHA-256 content hashes + review states are cited for every notebook entry.
 */
object EngineeringPortfolioCompiler {
    private val utcFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.US).withZone(ZoneOffset.UTC)

    fun computeSeasonProgression(
        sessions: List<Session>,
        summaries: List<SessionSummary>,
    ): List<SeasonMetricProgressionRow> {
        if (sessions.size < 2 || summaries.size < 2) return emptyList()
        val summaryById = summaries.associateBy(SessionSummary::sessionId)
        val ordered = sessions
            .filter { summaryById.containsKey(it.sessionId) }
            .sortedBy(Session::createdAt)
        if (ordered.size < 2) return emptyList()

        val firstSession = ordered.first()
        val latestSession = ordered.last()
        val firstSummary = summaryById.getValue(firstSession.sessionId)
        val latestSummary = summaryById.getValue(latestSession.sessionId)
        val firstLabel = sessionShortLabel(firstSession)
        val latestLabel = sessionShortLabel(latestSession)

        data class Spec(
            val label: String,
            val unit: String,
            val lowerIsBetter: Boolean,
            val decimals: Int,
            val scale: Double = 1.0,
            val extractor: (SessionSummary) -> Double,
        )

        val specs = listOf(
            Spec("Min Battery Voltage", "V", lowerIsBetter = false, decimals = 2) { it.minBatteryVoltage },
            Spec("Avg Loop Time", "ms", lowerIsBetter = true, decimals = 2) { it.avgLoopTimeMs },
            Spec("P95 Loop Time", "ms", lowerIsBetter = true, decimals = 2) { it.p95LoopTimeMs },
            Spec("Max EKF Localization Drift", "m", lowerIsBetter = true, decimals = 3) { it.maxEkfDrift },
            Spec("Avg Cross-Track Error", "cm", lowerIsBetter = true, decimals = 1, scale = 100.0) { it.avgCrossTrackError },
            Spec("Vision Acceptance Rate", "%", lowerIsBetter = false, decimals = 1, scale = 100.0) { it.visionAcceptanceRate },
        )

        return specs.mapNotNull { spec ->
            val rawBase = spec.extractor(firstSummary)
            val rawLatest = spec.extractor(latestSummary)
            if (!rawBase.isFinite() || !rawLatest.isFinite()) return@mapNotNull null
            if (abs(rawBase) < 1e-9 && abs(rawLatest) < 1e-9) return@mapNotNull null
            val baseVal = rawBase * spec.scale
            val latestVal = rawLatest * spec.scale
            val delta = latestVal - baseVal
            val improved = if (spec.lowerIsBetter) delta < -1e-6 else delta > 1e-6
            val pattern = "%.${spec.decimals}f"
            val signedPattern = "%+.${spec.decimals}f"
            SeasonMetricProgressionRow(
                metricLabel = spec.label,
                unit = spec.unit,
                lowerIsBetter = spec.lowerIsBetter,
                baselineSessionLabel = firstLabel,
                latestSessionLabel = latestLabel,
                baselineValue = baseVal,
                latestValue = latestVal,
                delta = delta,
                improved = improved,
                formattedBaseline = "${String.format(Locale.US, pattern, baseVal)} ${spec.unit}",
                formattedLatest = "${String.format(Locale.US, pattern, latestVal)} ${spec.unit}",
                formattedDelta = "${String.format(Locale.US, signedPattern, delta)} ${spec.unit}",
            )
        }
    }

    fun compileMarkdownPortfolio(
        workspaceName: String,
        teamId: String,
        seasonId: String,
        robotId: String,
        league: League,
        entries: List<EngineeringNotebookEntry>,
        sessions: List<Session> = emptyList(),
        summaries: List<SessionSummary> = emptyList(),
        generatedAtMs: Long,
    ): String = buildString {
        val displayRobot = workspaceName.trim().ifBlank { robotId }
        val orderedEntries = entries.sortedBy(EngineeringNotebookEntry::createdAtMs)
        val progression = computeSeasonProgression(sessions, summaries)
        val approvedCount = orderedEntries.count {
            it.reviewState == NotebookReviewState.APPROVED || it.reviewState == NotebookReviewState.PUBLISHED
        }

        appendLine("# Team $teamId — $displayRobot Engineering Portfolio ($seasonId)")
        appendLine()
        appendLine("- **League**: ${league.name}")
        appendLine("- **Robot ID**: `$robotId`")
        appendLine("- **Compiled**: ${formatUtc(generatedAtMs)}")
        appendLine("- **Entries Included**: ${orderedEntries.size} ($approvedCount human-approved, ${orderedEntries.size - approvedCount} draft/reviewed)")
        appendLine("- **Recorded Workspace Runs**: ${sessions.size}")
        appendLine()
        appendLine("> **Evidence Integrity Notice**: Every entry below carries a canonical SHA-256 content digest and explicit telemetry/configuration references. Simulation runs are tagged `simulation` and never claim physical hardware certification.")

        if (orderedEntries.isNotEmpty()) {
            appendLine()
            appendLine("## Table of Contents")
            appendLine()
            orderedEntries.forEachIndexed { index, entry ->
                val title = NotebookSectionParser.extractTitle(entry)
                appendLine("${index + 1}. **${escapeMdInline(title)}** (`${entry.entryType.displayLabel()}` · r${entry.revision} · `${entry.reviewState.name}`)")
            }
        }

        if (progression.isNotEmpty()) {
            val firstLabel = progression.first().baselineSessionLabel
            val latestLabel = progression.first().latestSessionLabel
            appendLine()
            appendLine("## Season Quantitative Progression ($firstLabel → $latestLabel)")
            appendLine()
            appendLine("| Metric | Baseline ($firstLabel) | Latest ($latestLabel) | Delta | Direction |")
            appendLine("|---|---|---|---|---|")
            progression.forEach { row ->
                val status = when {
                    abs(row.delta) <= 1e-6 -> "Unchanged"
                    row.improved -> "Improved"
                    else -> "Watch / Regressed"
                }
                appendLine("| ${row.metricLabel} | ${row.formattedBaseline} | ${row.formattedLatest} | ${row.formattedDelta} | $status |")
            }
        }

        appendLine()
        appendLine("## Engineering Design & Verification Entries")
        if (orderedEntries.isEmpty()) {
            appendLine()
            appendLine("_No engineering notebook entries have been recorded for this workspace yet._")
        } else {
            orderedEntries.forEachIndexed { index, entry ->
                val title = NotebookSectionParser.extractTitle(entry)
                appendLine()
                appendLine("---")
                appendLine()
                appendLine("### ${index + 1}. ${escapeMdInline(title)}")
                appendLine()
                appendLine(
                    "- **Entry ID**: `${entry.entryId}` (Revision `r${entry.revision}`) · " +
                        "**Type**: `${entry.entryType.displayLabel()}` · " +
                        "**Review State**: `${entry.reviewState.name}`"
                )
                appendLine(
                    "- **Updated**: ${formatUtc(entry.updatedAtMs)} · " +
                        "**Author**: ${entry.humanAuthorId?.takeIf(String::isNotBlank) ?: "ARES Deterministic Draft"} · " +
                        "**Reviewer**: ${entry.humanReviewerId?.takeIf(String::isNotBlank) ?: "Pending human sign-off"}"
                )
                appendLine("- **SHA-256**: `${entry.contentHash}`")
                appendLine()
                appendLine(stripLeadingH1(entry.markdownBody))
                if (entry.evidence.isNotEmpty()) {
                    appendLine()
                    appendLine("**Cited Evidence Artifacts:**")
                    entry.evidence.forEach { ref ->
                        val shaSuffix = ref.sha256?.take(12)?.let { " (sha256 `$it…`)" }.orEmpty()
                        val labelPart = ref.label?.takeIf(String::isNotBlank)?.let { " — ${escapeMdInline(it)}" }.orEmpty()
                        appendLine("- `[${ref.kind}]` `${ref.referenceId}`$labelPart$shaSuffix")
                    }
                }
            }
        }
    }.trimEnd() + "\n"

    fun compileHtmlPortfolio(
        workspaceName: String,
        teamId: String,
        seasonId: String,
        robotId: String,
        league: League,
        entries: List<EngineeringNotebookEntry>,
        sessions: List<Session> = emptyList(),
        summaries: List<SessionSummary> = emptyList(),
        generatedAtMs: Long,
    ): String {
        val displayRobot = workspaceName.trim().ifBlank { robotId }
        val orderedEntries = entries.sortedBy(EngineeringNotebookEntry::createdAtMs)
        val progression = computeSeasonProgression(sessions, summaries)
        val approvedCount = orderedEntries.count {
            it.reviewState == NotebookReviewState.APPROVED || it.reviewState == NotebookReviewState.PUBLISHED
        }

        return buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang=\"en\">")
            appendLine("<head>")
            appendLine("  <meta charset=\"UTF-8\" />")
            appendLine("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />")
            appendLine("  <title>${escapeHtml("Team $teamId — $displayRobot Engineering Portfolio ($seasonId)")}</title>")
            appendLine("  <style>")
            appendLine(PORTFOLIO_CSS)
            appendLine("  </style>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("  <div class=\"container\">")
            appendLine("    <header class=\"hero\">")
            appendLine("      <div class=\"eyebrow\">FIRST ENGINEERING PORTFOLIO &amp; EVIDENCE DOSSIER</div>")
            appendLine("      <h1>${escapeHtml("Team $teamId · $displayRobot")}</h1>")
            appendLine("      <p class=\"subtitle\">Season ${escapeHtml(seasonId)} · League ${escapeHtml(league.name)} · Robot ID <code>${escapeHtml(robotId)}</code> · Compiled ${escapeHtml(formatUtc(generatedAtMs))}</p>")
            appendLine("      <div class=\"kpi-row\">")
            appendLine("        <div class=\"kpi\"><span class=\"kpi-val\">${orderedEntries.size}</span><span class=\"kpi-lbl\">Notebook Entries</span></div>")
            appendLine("        <div class=\"kpi\"><span class=\"kpi-val\">$approvedCount</span><span class=\"kpi-lbl\">Human-Approved</span></div>")
            appendLine("        <div class=\"kpi\"><span class=\"kpi-val\">${sessions.size}</span><span class=\"kpi-lbl\">Recorded Runs</span></div>")
            appendLine("      </div>")
            appendLine("      <p class=\"notice\">Every entry below carries a verifiable SHA-256 content hash and linked run/configuration evidence. Simulator runs remain distinct from supervised physical robot validation.</p>")
            appendLine("    </header>")

            if (orderedEntries.isNotEmpty()) {
                appendLine("    <section class=\"card\">")
                appendLine("      <h2>Table of Contents</h2>")
                appendLine("      <ol class=\"toc\">")
                orderedEntries.forEachIndexed { idx, entry ->
                    val title = NotebookSectionParser.extractTitle(entry)
                    appendLine(
                        "        <li><a href=\"#entry-${idx + 1}\">${escapeHtml(title)}</a> " +
                            "<span class=\"badge\">${escapeHtml(entry.entryType.displayLabel())}</span> " +
                            "<span class=\"badge state-${entry.reviewState.name.lowercase()}\">${escapeHtml(entry.reviewState.name)} · r${entry.revision}</span></li>"
                    )
                }
                appendLine("      </ol>")
                appendLine("    </section>")
            }

            if (progression.isNotEmpty()) {
                val firstLabel = progression.first().baselineSessionLabel
                val latestLabel = progression.first().latestSessionLabel
                appendLine("    <section class=\"card\">")
                appendLine("      <h2>Quantitative Season Progression (${escapeHtml(firstLabel)} &rarr; ${escapeHtml(latestLabel)})</h2>")
                appendLine("      <table>")
                appendLine("        <thead><tr><th>Metric</th><th>Baseline (${escapeHtml(firstLabel)})</th><th>Latest (${escapeHtml(latestLabel)})</th><th>Delta</th><th>Assessment</th></tr></thead>")
                appendLine("        <tbody>")
                progression.forEach { row ->
                    val cls = when {
                        abs(row.delta) <= 1e-6 -> "neutral"
                        row.improved -> "good"
                        else -> "warn"
                    }
                    val label = when {
                        abs(row.delta) <= 1e-6 -> "Unchanged"
                        row.improved -> "Improved"
                        else -> "Watch / Regressed"
                    }
                    appendLine(
                        "          <tr><td>${escapeHtml(row.metricLabel)}</td>" +
                            "<td>${escapeHtml(row.formattedBaseline)}</td>" +
                            "<td>${escapeHtml(row.formattedLatest)}</td>" +
                            "<td class=\"$cls\">${escapeHtml(row.formattedDelta)}</td>" +
                            "<td class=\"$cls\">$label</td></tr>"
                    )
                }
                appendLine("        </tbody>")
                appendLine("      </table>")
                appendLine("    </section>")
            }

            orderedEntries.forEachIndexed { idx, entry ->
                val title = NotebookSectionParser.extractTitle(entry)
                appendLine("    <article id=\"entry-${idx + 1}\" class=\"card entry-card\">")
                appendLine("      <div class=\"entry-header\">")
                appendLine("        <div>")
                appendLine("          <div class=\"entry-meta\">Entry #${idx + 1} · <code>${escapeHtml(entry.entryId)}</code> · Revision r${entry.revision} · ${escapeHtml(formatUtc(entry.updatedAtMs))}</div>")
                appendLine("          <h2>${escapeHtml(title)}</h2>")
                appendLine("        </div>")
                appendLine("        <div class=\"badges\">")
                appendLine("          <span class=\"badge\">${escapeHtml(entry.entryType.displayLabel())}</span>")
                appendLine("          <span class=\"badge state-${entry.reviewState.name.lowercase()}\">${escapeHtml(entry.reviewState.name)}</span>")
                appendLine("        </div>")
                appendLine("      </div>")
                appendLine("      <div class=\"entry-body\">")
                appendLine(renderMarkdownBodyToSafeHtml(stripLeadingH1(entry.markdownBody)))
                appendLine("      </div>")
                if (entry.evidence.isNotEmpty()) {
                    appendLine("      <div class=\"evidence-box\">")
                    appendLine("        <strong>Linked Telemetry &amp; Artifact Evidence (${entry.evidence.size})</strong>")
                    appendLine("        <ul>")
                    entry.evidence.forEach { ref ->
                        val sha = ref.sha256?.take(12)?.let { " · sha256 <code>${escapeHtml(it)}…</code>" }.orEmpty()
                        val lbl = ref.label?.takeIf(String::isNotBlank)?.let { " — ${escapeHtml(it)}" }.orEmpty()
                        appendLine("          <li><code>[${escapeHtml(ref.kind)}] ${escapeHtml(ref.referenceId)}</code>$lbl$sha</li>")
                    }
                    appendLine("        </ul>")
                    appendLine("      </div>")
                }
                appendLine(
                    "      <footer class=\"entry-footer\">Author: ${escapeHtml(entry.humanAuthorId?.takeIf(String::isNotBlank) ?: "ARES Deterministic Draft")} · " +
                        "Reviewer: ${escapeHtml(entry.humanReviewerId?.takeIf(String::isNotBlank) ?: "Pending")} · " +
                        "SHA-256: <code>${escapeHtml(entry.contentHash.take(24))}…</code></footer>"
                )
                appendLine("    </article>")
            }

            appendLine("  </div>")
            appendLine("</body>")
            appendLine("</html>")
        }
    }

    fun NotebookEntryType.displayLabel(): String = when (this) {
        NotebookEntryType.ENGINEERING_NOTE -> "Engineering Note"
        NotebookEntryType.SESSION_SUMMARY -> "Session Debrief"
        NotebookEntryType.SOFTWARE_CHANGE -> "Software / Gain Change"
        NotebookEntryType.ROBOT_ISSUE -> "Robot Issue"
    }

    private fun sessionShortLabel(session: Session): String {
        session.matchNumber?.let { return "Match #$it" }
        return "Run ${session.sessionId.take(8)}"
    }

    private fun stripLeadingH1(markdown: String): String {
        val lines = markdown.replace("\r\n", "\n").lines()
        val firstNonBlankIdx = lines.indexOfFirst { it.isNotBlank() }
        if (firstNonBlankIdx >= 0 && lines[firstNonBlankIdx].trimStart().startsWith("# ") &&
            !lines[firstNonBlankIdx].trimStart().startsWith("## ")
        ) {
            return lines.drop(firstNonBlankIdx + 1).joinToString("\n").trim()
        }
        return markdown.trim()
    }

    internal fun renderMarkdownBodyToSafeHtml(markdown: String): String = buildString {
        val lines = markdown.lines()
        var inList = false
        var inTable = false
        fun closeBlocks() {
            if (inList) {
                appendLine("</ul>")
                inList = false
            }
            if (inTable) {
                appendLine("</tbody></table>")
                inTable = false
            }
        }
        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            when {
                trimmed.isEmpty() -> closeBlocks()
                trimmed.startsWith("### ") -> {
                    closeBlocks()
                    appendLine("<h4>${formatInlineHtml(trimmed.removePrefix("### ").trim())}</h4>")
                }
                trimmed.startsWith("## ") -> {
                    closeBlocks()
                    appendLine("<h3>${formatInlineHtml(trimmed.removePrefix("## ").trim())}</h3>")
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    if (inTable) {
                        appendLine("</tbody></table>")
                        inTable = false
                    }
                    if (!inList) {
                        appendLine("<ul>")
                        inList = true
                    }
                    appendLine("<li>${formatInlineHtml(trimmed.drop(2).trim())}</li>")
                }
                trimmed.startsWith("|") && trimmed.endsWith("|") -> {
                    if (inList) {
                        appendLine("</ul>")
                        inList = false
                    }
                    val cells = trimmed.removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
                    val isSeparator = cells.all { cell -> cell.isNotEmpty() && cell.all { ch -> ch == '-' || ch == ':' } }
                    if (!isSeparator) {
                        if (!inTable) {
                            appendLine("<table><thead><tr>")
                            cells.forEach { append("<th>${formatInlineHtml(it)}</th>") }
                            appendLine("</tr></thead><tbody>")
                            inTable = true
                        } else {
                            append("<tr>")
                            cells.forEach { append("<td>${formatInlineHtml(it)}</td>") }
                            appendLine("</tr>")
                        }
                    }
                }
                else -> {
                    closeBlocks()
                    appendLine("<p>${formatInlineHtml(trimmed)}</p>")
                }
            }
        }
        closeBlocks()
    }

    private fun formatInlineHtml(text: String): String {
        val escaped = escapeHtml(text)
        val withCode = Regex("`([^`]+)`").replace(escaped) { "<code>${it.groupValues[1]}</code>" }
        return Regex("\\*\\*([^*]+)\\*\\*").replace(withCode) { "<strong>${it.groupValues[1]}</strong>" }
    }

    private fun escapeMdInline(text: String): String =
        text.replace("\n", " ").replace("\r", " ").trim()

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun formatUtc(epochMs: Long): String =
        utcFormatter.format(Instant.ofEpochMilli(epochMs.coerceAtLeast(0L)))

    private const val PORTFOLIO_CSS = """
    :root { color-scheme: dark light; }
    body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #0d1117; color: #e6edf3; line-height: 1.55; }
    .container { max-width: 960px; margin: 0 auto; padding: 28px 20px 56px; }
    .hero { background: #161b22; border: 1px solid #30363d; border-radius: 12px; padding: 24px; margin-bottom: 20px; }
    .eyebrow { color: #38bdf8; font-size: 11px; font-weight: 700; letter-spacing: 0.08em; }
    h1 { margin: 6px 0 4px; font-size: 26px; }
    h2 { margin: 0 0 10px; font-size: 19px; color: #f0f6fc; }
    h3 { margin: 16px 0 6px; font-size: 15px; color: #38bdf8; }
    h4 { margin: 12px 0 4px; font-size: 13px; color: #cbd5e1; }
    .subtitle { margin: 0 0 14px; color: #8b949e; font-size: 13px; }
    .kpi-row { display: flex; gap: 16px; flex-wrap: wrap; margin-bottom: 12px; }
    .kpi { background: #0d1117; border: 1px solid #30363d; border-radius: 8px; padding: 10px 16px; min-width: 130px; }
    .kpi-val { display: block; font-size: 22px; font-weight: 700; color: #38bdf8; }
    .kpi-lbl { font-size: 11px; color: #8b949e; }
    .notice { margin: 0; font-size: 12px; color: #9ca3af; }
    .card { background: #161b22; border: 1px solid #30363d; border-radius: 12px; padding: 20px; margin-bottom: 16px; }
    .entry-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; border-bottom: 1px solid #30363d; padding-bottom: 10px; margin-bottom: 12px; }
    .entry-meta { font-size: 11px; color: #8b949e; margin-bottom: 4px; }
    .badges { display: flex; gap: 6px; flex-wrap: wrap; }
    .badge { font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: 999px; background: #21262d; color: #c9d1d9; border: 1px solid #30363d; }
    .state-approved, .state-published { background: rgba(34,197,94,0.16); color: #4ade80; border-color: rgba(34,197,94,0.45); }
    .state-draft { background: rgba(245,158,11,0.16); color: #fbbf24; border-color: rgba(245,158,11,0.45); }
    table { width: 100%; border-collapse: collapse; margin: 10px 0; font-size: 13px; }
    th, td { border: 1px solid #30363d; padding: 8px 10px; text-align: left; }
    th { background: #21262d; font-weight: 600; }
    .good { color: #4ade80; font-weight: 600; }
    .warn { color: #fbbf24; font-weight: 600; }
    .neutral { color: #9ca3af; }
    code { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 12px; background: #21262d; padding: 1px 5px; border-radius: 4px; }
    .evidence-box { background: #0d1117; border: 1px solid #30363d; border-radius: 8px; padding: 12px; margin-top: 14px; font-size: 12px; }
    .evidence-box ul { margin: 6px 0 0; padding-left: 18px; }
    .entry-footer { margin-top: 12px; padding-top: 8px; border-top: 1px solid #30363d; font-size: 11px; color: #8b949e; }
    .toc li { margin-bottom: 6px; }
    a { color: #38bdf8; text-decoration: none; }
    @media print {
      body { background: #ffffff; color: #111827; font-size: 11pt; }
      .container { max-width: 100%; padding: 0; }
      .hero, .card, .kpi, .evidence-box { background: #ffffff !important; border-color: #d1d5db !important; color: #111827 !important; break-inside: avoid; page-break-inside: avoid; }
      h1, h2, h3, h4 { color: #111827 !important; }
      .subtitle, .entry-meta, .entry-footer, .notice, .kpi-lbl { color: #4b5563 !important; }
      .kpi-val, a { color: #0369a1 !important; }
      th { background: #f3f4f6 !important; color: #111827 !important; }
      th, td { border-color: #d1d5db !important; }
      code, .badge { background: #f3f4f6 !important; color: #111827 !important; border-color: #d1d5db !important; }
      .good { color: #15803d !important; }
      .warn { color: #b45309 !important; }
    }
    """
}
