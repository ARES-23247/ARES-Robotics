package com.ares.analytics.domain.notebook

import com.ares.analytics.shared.models.EngineeringNotebookEntry
import com.ares.analytics.shared.models.NotebookEntryType

/**
 * FIRST-rubric aligned structured sections for an Engineering Notebook entry.
 *
 * Maps cleanly to both FTC Engineering Portfolio criteria (Problem/Hypothesis -> Engineering Change ->
 * Measured Telemetry Evidence -> Conclusion & Next Step) and FRC Engineering Design Process logs,
 * while round-tripping deterministically to and from plain Markdown.
 */
data class StructuredNotebookSections(
    val title: String = "",
    val problemHypothesis: String = "",
    val changeImplemented: String = "",
    val measuredEvidence: String = "",
    val conclusionNextSteps: String = "",
    val tags: List<String> = emptyList(),
)

object NotebookSectionParser {
    private const val PROBLEM_HEADING = "Problem & Hypothesis"
    private const val CHANGE_HEADING = "Design, Software & Gain Changes"
    private const val EVIDENCE_HEADING = "Measured Telemetry Evidence"
    private const val CONCLUSION_HEADING = "Conclusion & Next Steps"

    fun defaultTemplate(entryType: NotebookEntryType, robotName: String = ""): StructuredNotebookSections {
        val prefix = robotName.trim().takeIf(String::isNotBlank)?.let { "$it — " }.orEmpty()
        return when (entryType) {
            NotebookEntryType.ENGINEERING_NOTE -> StructuredNotebookSections(
                title = "${prefix}Engineering Iteration Note",
                problemHypothesis = "State the mechanism, control, or strategy problem observed and what improvement you expect.",
                changeImplemented = "Describe the CAD, wiring, control law, or autonomous trajectory change.",
                measuredEvidence = "Cite recorded run IDs, battery voltage, loop timing, or tracking error measurements.",
                conclusionNextSteps = "Record whether the hypothesis held and define the next safe verification step.",
            )
            NotebookEntryType.SESSION_SUMMARY -> StructuredNotebookSections(
                title = "${prefix}Match / Practice Run Review",
                problemHypothesis = "Goal of this session (autonomous consistency, cycle time, or driver practice).",
                changeImplemented = "Configuration and alliance setup used during this session.",
                measuredEvidence = "Key telemetry metrics, spatial field observations, and threshold alerts.",
                conclusionNextSteps = "Pit checklist or software action items before the next match.",
            )
            NotebookEntryType.SOFTWARE_CHANGE -> StructuredNotebookSections(
                title = "${prefix}Control / Software Change Verification",
                problemHypothesis = "Why this single bounded software or tuning parameter change was proposed.",
                changeImplemented = "Exact parameter/code delta and configuration snapshot hash.",
                measuredEvidence = "Before-vs-after paired simulation or robot telemetry results.",
                conclusionNextSteps = "Accept, revise, or roll back; physical hardware validation remains separate.",
            )
            NotebookEntryType.ROBOT_ISSUE -> StructuredNotebookSections(
                title = "${prefix}Robot Fault & Pit Resolution Log",
                problemHypothesis = "Observed symptom, fault alert, or intermittent hardware behavior.",
                changeImplemented = "Inspection findings and mechanical/electrical/software remediation.",
                measuredEvidence = "Post-fix Pit Self-Test or replay verification measurements.",
                conclusionNextSteps = "Follow-up monitoring rule for upcoming matches.",
            )
        }
    }

    fun renderMarkdown(sections: StructuredNotebookSections): String = buildString {
        val cleanTitle = sections.title.trim().ifBlank { "Untitled Engineering Entry" }
        appendLine("# $cleanTitle")
        val normalizedTags = sections.tags
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (normalizedTags.isNotEmpty()) {
            appendLine()
            appendLine("Tags: ${normalizedTags.joinToString(", ") { "`$it`" }}")
        }
        appendSection(PROBLEM_HEADING, sections.problemHypothesis)
        appendSection(CHANGE_HEADING, sections.changeImplemented)
        appendSection(EVIDENCE_HEADING, sections.measuredEvidence)
        appendSection(CONCLUSION_HEADING, sections.conclusionNextSteps)
    }.trimEnd() + "\n"

    private fun StringBuilder.appendSection(heading: String, body: String) {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        appendLine()
        appendLine("## $heading")
        appendLine()
        appendLine(trimmed)
    }

    fun extractTitle(markdown: String, fallbackId: String = "Notebook Entry"): String {
        val lines = markdown.lines()
        val h1 = lines.firstOrNull { it.trimStart().startsWith("# ") }
            ?.trimStart()
            ?.removePrefix("# ")
            ?.trim()
            ?.takeIf(String::isNotBlank)
        if (h1 != null) return h1
        val firstNonBlank = lines.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.removePrefix("## ")
            ?.take(96)
            ?.takeIf(String::isNotBlank)
        return firstNonBlank ?: fallbackId
    }

    fun extractTitle(entry: EngineeringNotebookEntry): String =
        extractTitle(entry.markdownBody, entry.entryId)

    /**
     * Parses structured sections from Markdown, gracefully mapping legacy/automated headings
     * (`Observations`, `Measurements`, `Changes`, `Verification`, `Alerts`, `Interpretation and next steps`)
     * so no content is ever dropped when switching between Raw Markdown and Guided Form views.
     */
    fun parseMarkdown(markdown: String): StructuredNotebookSections {
        if (markdown.isBlank()) return StructuredNotebookSections()
        val lines = markdown.replace("\r\n", "\n").lines()
        var title = ""
        val tags = mutableListOf<String>()
        val preamble = mutableListOf<String>()
        val rawSections = mutableListOf<Pair<String, MutableList<String>>>()
        var currentHeading: String? = null
        var currentLines = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            when {
                title.isEmpty() && trimmed.startsWith("# ") && !trimmed.startsWith("## ") -> {
                    title = trimmed.removePrefix("# ").trim()
                }
                currentHeading == null && trimmed.startsWith("Tags:", ignoreCase = true) -> {
                    val rawTagPart = trimmed.substringAfter(':').trim()
                    rawTagPart.split(',')
                        .map { it.trim().removeSurrounding("`").trim() }
                        .filter { it.isNotEmpty() }
                        .forEach(tags::add)
                }
                trimmed.startsWith("## ") -> {
                    val headingName = trimmed.removePrefix("## ").trim()
                    currentHeading = headingName
                    currentLines = mutableListOf()
                    rawSections.add(headingName to currentLines)
                }
                else -> {
                    if (currentHeading == null) {
                        preamble.add(line)
                    } else {
                        currentLines.add(line)
                    }
                }
            }
        }

        val problemParts = mutableListOf<String>()
        val changeParts = mutableListOf<String>()
        val evidenceParts = mutableListOf<String>()
        val conclusionParts = mutableListOf<String>()

        val preambleText = preamble.joinToString("\n").trim()
        if (preambleText.isNotEmpty()) {
            problemParts.add(preambleText)
        }

        for ((heading, bodyLines) in rawSections) {
            val content = bodyLines.joinToString("\n").trim()
            if (content.isEmpty()) continue
            val lower = heading.lowercase()
            when {
                lower.contains("problem") || lower.contains("hypothesis") || lower.contains("goal") || lower.contains("question") ->
                    problemParts.add(content)
                lower.contains("change") || lower.contains("design") || lower.contains("gain") || lower.contains("implementation") || lower.contains("remediation") ->
                    changeParts.add(content)
                lower.contains("evidence") || lower.contains("observation") || lower.contains("measurement") ||
                    lower.contains("verification") || lower.contains("alert") || lower.contains("metric") || lower.contains("cohort") ->
                    evidenceParts.add(if (isCanonicalEvidenceHeading(lower)) content else "### $heading\n$content")
                lower.contains("conclusion") || lower.contains("next") || lower.contains("interpretation") ||
                    lower.contains("improvement status") || lower.contains("decision") ->
                    conclusionParts.add(content)
                else ->
                    evidenceParts.add("### $heading\n$content")
            }
        }

        return StructuredNotebookSections(
            title = title,
            problemHypothesis = problemParts.joinToString("\n\n").trim(),
            changeImplemented = changeParts.joinToString("\n\n").trim(),
            measuredEvidence = evidenceParts.joinToString("\n\n").trim(),
            conclusionNextSteps = conclusionParts.joinToString("\n\n").trim(),
            tags = tags.distinct(),
        )
    }

    private fun isCanonicalEvidenceHeading(lowerHeading: String): Boolean =
        lowerHeading == EVIDENCE_HEADING.lowercase()
}
