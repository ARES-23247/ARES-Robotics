package com.ares.analytics.service

internal object RunComparisonMarkdownExporter {
    fun renderMarkdown(report: RunComparisonReport): String = buildString {
        appendLine("# ARES mentor/student run comparison")
        appendLine()
        appendLine("Alignment: ${report.selectedAlignment.label.safeComparisonMarkdown()}")
        appendLine("Primary run: ${report.primarySessionId.safeComparisonMarkdown()}")
        appendLine()
        appendLine("> Historical correlation is not proof of cause, and this report is not a physical robot safety certification.")
        appendLine()
        appendLine("## Selected runs and anchors")
        report.sessions.forEach { session ->
            val anchor = report.anchors.first { it.sessionId == session.sessionId }
            appendLine("- ${session.shortRunLabel().safeComparisonMarkdown()} (`${session.sessionId.safeComparisonMarkdown()}`): ${anchor.label.safeComparisonMarkdown()} at ${anchor.absoluteTimestampMs} ms")
        }
        appendLine()
        appendLine("## Comparable telemetry")
        report.metrics.forEach { metric ->
            appendLine("### ${metric.label.safeComparisonMarkdown()} (${metric.unit.safeComparisonMarkdown()})")
            appendLine(metric.explanation.safeComparisonMarkdown())
            metric.series.forEach { series ->
                appendLine("- ${series.runLabel.safeComparisonMarkdown()}: min ${series.summary.minimum.formatComparison()}, max ${series.summary.maximum.formatComparison()}, average ${series.summary.average.formatComparison()}, p95 ${series.summary.p95.formatComparison()} from ${series.summary.sampleCount} samples; topics: ${series.sourceTopics.joinToString().safeComparisonMarkdown()}")
            }
        }
        appendLine()
        appendLine("## Guided findings")
        if (report.findings.isEmpty()) appendLine("- No configured material difference was found. This does not prove the runs are equivalent.")
        report.findings.forEach { finding ->
            appendLine("### ${finding.title.safeComparisonMarkdown()}")
            appendLine("- Claim type: ${finding.kind.label}")
            appendLine("- Explanation: ${finding.explanation.safeComparisonMarkdown()}")
            appendLine("- Replay evidence: session `${finding.evidence.sessionId.safeComparisonMarkdown()}`, timestamp ${finding.evidence.absoluteTimestampMs} ms, aligned ${finding.evidence.alignedTimeMs} ms, topics ${finding.evidence.topics.joinToString().safeComparisonMarkdown()}, window ±${finding.evidence.evidenceWindowMs} ms")
        }
        appendLine()
        appendLine("## Fault and alert records")
        report.faults.forEach { fault ->
            appendLine("- ${fault.runLabel.safeComparisonMarkdown()}: ${fault.alertCount} persisted alert(s)${fault.alertKeys.takeIf(List<String>::isNotEmpty)?.joinToString(prefix = " — ")?.safeComparisonMarkdown().orEmpty()}")
        }
        appendLine()
        appendLine("## Evidence boundaries")
        report.limitations.forEach { appendLine("- ${it.safeComparisonMarkdown()}") }
    }

    private fun String.oneLineComparison(): String = replace(Regex("[\\r\\n]+"), " ").trim()
    private fun String.safeComparisonMarkdown(): String = oneLineComparison()
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("`", "&#96;")
        .replace("[", "&#91;")
        .replace("]", "&#93;")
        .replace("|", "\\|")
    private fun Double.formatComparison(): String = "%.3f".format(this)
}
