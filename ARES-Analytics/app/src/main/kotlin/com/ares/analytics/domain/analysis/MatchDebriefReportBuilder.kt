package com.ares.analytics.domain.analysis

import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionAnnotation
import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.shared.models.TelemetryFrame
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Generates standalone offline Pit Debrief reports in Markdown (`.md`) and self-contained dark-themed HTML (`.html`)
 * for full recorded sessions or bounded Replay `[In, Out]` time slices.
 */
object MatchDebriefReportBuilder {

    private val isoUtcFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).withZone(ZoneOffset.UTC)

    fun buildSessionMarkdownDebrief(
        league: League,
        session: Session,
        summary: SessionSummary?,
        insights: List<MatchCoachingInsight>,
        heatmap: SpatialFieldHeatmap?,
        alerts: List<AlertRecord>,
        annotations: List<SessionAnnotation>,
        sessionStartMs: Long,
    ): String = buildString {
        val matchBadge = buildMatchBadge(session)
        appendLine("# ARES Match & Pit Debrief — $matchBadge")
        appendLine()
        appendLine("- **League / Robot**: `${league.name}` · Team `${session.teamId}` · Robot `${session.robotId}` · Season `${session.seasonId}`")
        appendLine("- **Session ID**: `${session.sessionId}`")
        appendLine("- **Recorded**: ${formatEpochUtc(session.createdAt)} · **Duration**: ${formatSeconds(session.durationMs)}")
        if (session.tags.isNotEmpty()) {
            appendLine("- **Tags**: ${session.tags.joinToString(", ") { "`$it`" }}")
        }
        appendLine()

        if (summary != null) {
            appendLine("## Aggregate Match KPIs")
            appendLine()
            appendLine("| Metric | Value |")
            appendLine("|---|---|")
            appendLine("| Minimum Battery Voltage | ${String.format(Locale.US, "%.2f V", summary.minBatteryVoltage)} |")
            appendLine("| Est. Battery Internal Resistance | ${String.format(Locale.US, "%.1f mΩ", summary.avgBatteryResistance * 1000.0)} |")
            appendLine("| Control Loop Avg / p95 | ${String.format(Locale.US, "%.2f ms / %.2f ms", summary.avgLoopTimeMs, summary.p95LoopTimeMs)} |")
            appendLine("| Vision Acceptance Rate | ${String.format(Locale.US, "%.1f%% (%.1f ms latency)", summary.visionAcceptanceRate * 100.0, summary.avgVisionLatencyMs)} |")
            appendLine("| Avg Cross-Track Error | ${String.format(Locale.US, "%.1f cm", summary.avgCrossTrackError * 100.0)} |")
            appendLine("| Max EKF Drift | ${String.format(Locale.US, "%.3f m", summary.maxEkfDrift)} |")
            appendLine()
        }

        if (heatmap != null && heatmap.poseSampleCount > 0) {
            appendLine("## Spatial Field Coverage")
            appendLine()
            appendLine(
                "- **Pose Samples**: ${heatmap.poseSampleCount} · **Total Distance**: ${String.format(Locale.US, "%.2f m", heatmap.totalDistanceMeters)} · " +
                    "**Peak Speed**: ${String.format(Locale.US, "%.2f m/s", heatmap.peakSpeedMps)} · " +
                    "**Peak Local Voltage Sag**: ${String.format(Locale.US, "%.2f V", heatmap.maxSagVolts)}"
            )
            appendLine()
        }

        appendLine("## Driver & Subsystem Coaching Insights")
        appendLine()
        if (insights.isEmpty()) {
            appendLine("_No coaching insights generated for this session._")
            appendLine()
        } else {
            insights.forEach { insight ->
                val jumpLabel = insight.jumpTimestampMs?.let { ts ->
                    val relSec = ((ts - sessionStartMs).coerceAtLeast(0L)) / 1000.0
                    String.format(Locale.US, " _(Replay @ +%.2fs)_", relSec)
                }.orEmpty()
                appendLine("- **[${insight.severity.name} · ${insight.category}] ${insight.title}** (`${insight.metricBadge}`)$jumpLabel")
                appendLine("  ${insight.detail}")
            }
            appendLine()
        }

        appendLine("## Pinned Replay Notes (${annotations.size})")
        appendLine()
        if (annotations.isEmpty()) {
            appendLine("_No pit or replay notes pinned._")
            appendLine()
        } else {
            annotations.sortedBy { it.effectiveTimestampMs() }.forEach { note ->
                val relSec = ((note.effectiveTimestampMs() - sessionStartMs).coerceAtLeast(0L)) / 1000.0
                val author = note.authorId?.takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty()
                appendLine("- **`+${String.format(Locale.US, "%.3fs", relSec)}`**$author: ${note.text}")
            }
            appendLine()
        }

        appendLine("## Triggered Alerts (${alerts.size})")
        appendLine()
        if (alerts.isEmpty()) {
            appendLine("_No telemetry alert rules triggered._")
        } else {
            alerts.sortedBy { it.triggerTimestampMs }.forEach { alert ->
                val relSec = ((alert.triggerTimestampMs - sessionStartMs).coerceAtLeast(0L)) / 1000.0
                appendLine(
                    "- **`+${String.format(Locale.US, "%.2fs", relSec)}`** · `${alert.ruleKey}` — peak `${String.format(Locale.US, "%.2f", alert.peakValue)}` (${alert.durationMs} ms)"
                )
            }
        }
    }

    fun buildSessionHtmlDebrief(
        league: League,
        session: Session,
        summary: SessionSummary?,
        insights: List<MatchCoachingInsight>,
        heatmap: SpatialFieldHeatmap?,
        alerts: List<AlertRecord>,
        annotations: List<SessionAnnotation>,
        sessionStartMs: Long,
    ): String {
        val matchBadge = escapeHtml(buildMatchBadge(session))
        return buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang=\"en\"><head><meta charset=\"UTF-8\">")
            appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
            appendLine("<title>ARES Pit Debrief — $matchBadge</title>")
            appendLine(
                """
                <style>
                  body { background:#0b0f17; color:#e6edf3; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif; margin:0; padding:28px; line-height:1.5; }
                  .wrap { max-width:980px; margin:0 auto; }
                  .card { background:#131a26; border:1px solid #263244; border-radius:10px; padding:18px 20px; margin-bottom:16px; }
                  h1 { margin:0 0 6px 0; font-size:22px; color:#58e6ff; }
                  h2 { margin:0 0 12px 0; font-size:15px; text-transform:uppercase; letter-spacing:0.06em; color:#9fb1c9; }
                  .meta { color:#9fb1c9; font-size:13px; }
                  .kpis { display:grid; grid-template-columns:repeat(auto-fit,minmax(150px,1fr)); gap:10px; }
                  .kpi { background:#0e1420; border:1px solid #223046; border-radius:8px; padding:10px 12px; }
                  .kpi-label { font-size:11px; color:#8ba0bc; text-transform:uppercase; }
                  .kpi-val { font-size:18px; font-weight:700; color:#f0f6fc; margin-top:4px; }
                  .insight { border-left:4px solid #58e6ff; background:#0e1420; border-radius:6px; padding:10px 14px; margin-bottom:10px; }
                  .sev-CRITICAL { border-left-color:#ff5252; }
                  .sev-WARNING { border-left-color:#ffb300; }
                  .sev-EXCELLENT { border-left-color:#00e676; }
                  .badge { display:inline-block; font-size:11px; font-weight:700; padding:2px 8px; border-radius:999px; background:#1e293b; color:#cbd5e1; margin-left:6px; }
                  ul { margin:0; padding-left:18px; }
                  li { margin-bottom:6px; font-size:13px; }
                  code { background:#1c2536; padding:1px 5px; border-radius:4px; font-size:12px; }
                </style>
                """.trimIndent()
            )
            appendLine("</head><body><div class=\"wrap\">")
            appendLine("<div class=\"card\">")
            appendLine("<h1>ARES Match &amp; Pit Debrief — $matchBadge</h1>")
            appendLine(
                "<div class=\"meta\">League <code>${escapeHtml(league.name)}</code> · Team <code>${escapeHtml(session.teamId)}</code> · " +
                    "Robot <code>${escapeHtml(session.robotId)}</code> · Session <code>${escapeHtml(session.sessionId)}</code> · " +
                    "Recorded ${escapeHtml(formatEpochUtc(session.createdAt))} (${escapeHtml(formatSeconds(session.durationMs))})</div>"
            )
            appendLine("</div>")

            if (summary != null) {
                appendLine("<div class=\"card\"><h2>Aggregate Match KPIs</h2><div class=\"kpis\">")
                appendKpi("Min Battery", String.format(Locale.US, "%.2f V", summary.minBatteryVoltage))
                appendKpi("Est. R_int", String.format(Locale.US, "%.1f mΩ", summary.avgBatteryResistance * 1000.0))
                appendKpi("Loop p95", String.format(Locale.US, "%.2f ms", summary.p95LoopTimeMs))
                appendKpi("Vision Accept", String.format(Locale.US, "%.1f%%", summary.visionAcceptanceRate * 100.0))
                appendKpi("Cross-Track Err", String.format(Locale.US, "%.1f cm", summary.avgCrossTrackError * 100.0))
                if (heatmap != null && heatmap.poseSampleCount > 0) {
                    appendKpi("Distance / Peak v", String.format(Locale.US, "%.1f m / %.2f m/s", heatmap.totalDistanceMeters, heatmap.peakSpeedMps))
                }
                appendLine("</div></div>")
            }

            appendLine("<div class=\"card\"><h2>Driver &amp; Subsystem Coaching Insights (${insights.size})</h2>")
            if (insights.isEmpty()) {
                appendLine("<div class=\"meta\">No coaching insights generated.</div>")
            } else {
                insights.forEach { insight ->
                    val jumpStr = insight.jumpTimestampMs?.let { ts ->
                        val relSec = ((ts - sessionStartMs).coerceAtLeast(0L)) / 1000.0
                        String.format(Locale.US, " · Replay @ +%.2fs", relSec)
                    }.orEmpty()
                    appendLine("<div class=\"insight sev-${insight.severity.name}\">")
                    appendLine(
                        "<div><strong>${escapeHtml(insight.title)}</strong>" +
                            "<span class=\"badge\">${escapeHtml(insight.severity.name)}</span>" +
                            "<span class=\"badge\">${escapeHtml(insight.metricBadge)}</span>" +
                            "<span class=\"meta\">${escapeHtml(jumpStr)}</span></div>"
                    )
                    appendLine("<div class=\"meta\" style=\"margin-top:4px;\">${escapeHtml(insight.detail)}</div>")
                    appendLine("</div>")
                }
            }
            appendLine("</div>")

            appendLine("<div class=\"card\"><h2>Pinned Replay Notes (${annotations.size}) &amp; Alerts (${alerts.size})</h2><ul>")
            annotations.sortedBy { it.effectiveTimestampMs() }.forEach { note ->
                val relSec = ((note.effectiveTimestampMs() - sessionStartMs).coerceAtLeast(0L)) / 1000.0
                appendLine("<li><code>+${String.format(Locale.US, "%.3fs", relSec)}</code> <strong>Note</strong>: ${escapeHtml(note.text)}</li>")
            }
            alerts.sortedBy { it.triggerTimestampMs }.forEach { alert ->
                val relSec = ((alert.triggerTimestampMs - sessionStartMs).coerceAtLeast(0L)) / 1000.0
                appendLine(
                    "<li><code>+${String.format(Locale.US, "%.2fs", relSec)}</code> <strong>Alert</strong> <code>${escapeHtml(alert.ruleKey)}</code> — " +
                        "peak ${String.format(Locale.US, "%.2f", alert.peakValue)} (${alert.durationMs} ms)</li>"
                )
            }
            if (annotations.isEmpty() && alerts.isEmpty()) {
                appendLine("<li class=\"meta\">No pinned notes or alerts in this session.</li>")
            }
            appendLine("</ul></div>")
            appendLine("</div></body></html>")
        }
    }

    /** Builds a focused Markdown debrief for an `[inMs, outMs]` Replay slice. */
    fun buildSliceMarkdownDebrief(
        sessionId: String,
        league: League,
        sessionStartMs: Long,
        inMs: Long,
        outMs: Long,
        sliceFrames: List<TelemetryFrame>,
        sliceAlerts: List<AlertRecord>,
        sliceAnnotations: List<SessionAnnotation>,
    ): String = buildString {
        val relIn = ((inMs - sessionStartMs).coerceAtLeast(0L)) / 1000.0
        val relOut = ((outMs - sessionStartMs).coerceAtLeast(0L)) / 1000.0
        val spanSec = ((outMs - inMs).coerceAtLeast(0L)) / 1000.0
        appendLine("# ARES Replay Time-Slice Debrief")
        appendLine()
        appendLine("- **Session**: `$sessionId` (`${league.name}`)")
        appendLine(
            String.format(
                Locale.US,
                "- **Slice Window**: `+%.3fs` → `+%.3fs` (span `%.3fs`, epoch `%d..%d`)",
                relIn,
                relOut,
                spanSec,
                inMs,
                outMs,
            )
        )
        appendLine("- **Telemetry Samples in Slice**: ${sliceFrames.size} across ${sliceFrames.map { it.key }.distinct().size} topics")
        appendLine()

        val batteryVals = sliceFrames.filter { frame ->
            val k = frame.key.lowercase(Locale.ROOT)
            (k.contains("batteryvoltage") || k.contains("battery_voltage") || k.endsWith("/vin") || k == "vin") && frame.value.isFinite()
        }.map { it.value }
        val loopVals = sliceFrames.filter { frame ->
            val k = frame.key.lowercase(Locale.ROOT)
            (k.contains("looptime") || k.contains("loop_time")) && frame.value.isFinite()
        }.map { it.value }
        val currentVals = sliceFrames.filter { frame ->
            val k = frame.key.lowercase(Locale.ROOT)
            k.contains("current") && frame.value.isFinite()
        }.map { it.value }

        appendLine("## Slice Signal Highlights")
        appendLine()
        if (batteryVals.isNotEmpty()) {
            appendLine(
                String.format(
                    Locale.US,
                    "- **Battery Voltage**: min `%.2f V` · max `%.2f V` · sag `%.2f V`",
                    batteryVals.min(),
                    batteryVals.max(),
                    batteryVals.max() - batteryVals.min(),
                )
            )
        }
        if (loopVals.isNotEmpty()) {
            appendLine(
                String.format(
                    Locale.US,
                    "- **Control Loop Time**: avg `%.2f ms` · peak `%.2f ms`",
                    loopVals.average(),
                    loopVals.max(),
                )
            )
        }
        if (currentVals.isNotEmpty()) {
            appendLine(
                String.format(
                    Locale.US,
                    "- **Peak Motor / Bus Current**: `%.2f A`",
                    currentVals.max(),
                )
            )
        }
        if (batteryVals.isEmpty() && loopVals.isEmpty() && currentVals.isEmpty()) {
            appendLine("- _No standard battery, loop-time, or current scalars recorded inside this window._")
        }
        appendLine()

        appendLine("## Pinned Notes in Slice (${sliceAnnotations.size})")
        appendLine()
        if (sliceAnnotations.isEmpty()) {
            appendLine("_None._")
        } else {
            sliceAnnotations.sortedBy { it.effectiveTimestampMs() }.forEach { note ->
                val rel = ((note.effectiveTimestampMs() - sessionStartMs).coerceAtLeast(0L)) / 1000.0
                appendLine("- **`+${String.format(Locale.US, "%.3fs", rel)}`**: ${note.text}")
            }
        }
        appendLine()

        appendLine("## Alerts in Slice (${sliceAlerts.size})")
        appendLine()
        if (sliceAlerts.isEmpty()) {
            appendLine("_None._")
        } else {
            sliceAlerts.sortedBy { it.triggerTimestampMs }.forEach { alert ->
                val rel = ((alert.triggerTimestampMs - sessionStartMs).coerceAtLeast(0L)) / 1000.0
                appendLine("- **`+${String.format(Locale.US, "%.2fs", rel)}`** · `${alert.ruleKey}` (peak `${String.format(Locale.US, "%.2f", alert.peakValue)}`)")
            }
        }
    }

    private fun StringBuilder.appendKpi(label: String, value: String) {
        appendLine("<div class=\"kpi\"><div class=\"kpi-label\">${escapeHtml(label)}</div><div class=\"kpi-val\">${escapeHtml(value)}</div></div>")
    }

    private fun buildMatchBadge(session: Session): String {
        val alliance = session.allianceColor?.trim()?.takeIf(String::isNotBlank)?.uppercase(Locale.US)
        val matchPart = session.matchNumber?.let { "Match #$it" } ?: session.sessionId.take(12)
        return if (alliance != null) "$matchPart ($alliance)" else matchPart
    }

    private fun formatEpochUtc(epochMs: Long): String =
        if (epochMs <= 0L) "Unknown time" else isoUtcFormatter.format(Instant.ofEpochMilli(epochMs))

    private fun formatSeconds(durationMs: Long): String =
        String.format(Locale.US, "%.2f s", durationMs.coerceAtLeast(0L) / 1000.0)

    private fun escapeHtml(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }
}
