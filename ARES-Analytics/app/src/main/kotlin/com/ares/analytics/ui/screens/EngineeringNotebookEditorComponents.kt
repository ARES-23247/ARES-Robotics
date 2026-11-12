@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.domain.notebook.EngineeringPortfolioCompiler.displayLabel
import com.ares.analytics.domain.notebook.StructuredNotebookSections
import com.ares.analytics.shared.models.NotebookEntryType
import com.ares.analytics.shared.models.NotebookEvidenceReference
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary

@Composable
internal fun GuidedRubricFormEditor(
    draftType: NotebookEntryType,
    sections: StructuredNotebookSections,
    authorId: String,
    onTypeChange: (NotebookEntryType) -> Unit,
    onAuthorChange: (String) -> Unit,
    onUpdateSections: ((StructuredNotebookSections) -> StructuredNotebookSections) -> Unit,
) {
    var tagsText by remember(sections.tags) { mutableStateOf(sections.tags.joinToString(", ")) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            color = AresCyan.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "FIRST Engineering Portfolio Rubric Alignment",
                    color = AresCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Judges look for a clear engineering loop: state the observed problem & testable hypothesis, " +
                        "document the specific CAD/code/gain change, cite measured telemetry evidence, and record the next safe step.",
                    color = AresTextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NotebookEntryType.entries.forEach { type ->
                FilterChip(
                    selected = draftType == type,
                    onClick = { onTypeChange(type) },
                    label = { Text(type.displayLabel(), fontSize = 11.sp) },
                )
            }
        }

        OutlinedTextField(
            value = sections.title,
            onValueChange = { title -> onUpdateSections { it.copy(title = title) } },
            label = { Text("Entry Title (shown in Table of Contents)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = authorId,
                onValueChange = onAuthorChange,
                label = { Text("Student / Mentor Author") },
                placeholder = { Text("e.g. Maya (Controls Lead)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = tagsText,
                onValueChange = { raw ->
                    tagsText = raw
                    val parsed = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    onUpdateSections { it.copy(tags = parsed) }
                },
                label = { Text("Tags (comma-separated)") },
                placeholder = { Text("e.g. intake-v2, auto, kP-tuning") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        RubricField(
            stepBadge = "1 · PROBLEM & HYPOTHESIS",
            label = "Observed symptom, engineering question, and expected measurable outcome",
            value = sections.problemHypothesis,
            onValueChange = { v -> onUpdateSections { it.copy(problemHypothesis = v) } },
            minLines = 3,
        )
        RubricField(
            stepBadge = "2 · DESIGN, SOFTWARE & GAIN CHANGES",
            label = "Exact mechanical, electrical, trajectory, or bounded control parameter change",
            value = sections.changeImplemented,
            onValueChange = { v -> onUpdateSections { it.copy(changeImplemented = v) } },
            minLines = 3,
        )
        RubricField(
            stepBadge = "3 · MEASURED TELEMETRY EVIDENCE",
            label = "Recorded run metrics, paired before/after deltas, cohort quantiles, or verification checks",
            value = sections.measuredEvidence,
            onValueChange = { v -> onUpdateSections { it.copy(measuredEvidence = v) } },
            minLines = 4,
        )
        RubricField(
            stepBadge = "4 · CONCLUSION & NEXT SAFE STEP",
            label = "Did the evidence support the hypothesis? What is the next safe pit or field verification?",
            value = sections.conclusionNextSteps,
            onValueChange = { v -> onUpdateSections { it.copy(conclusionNextSteps = v) } },
            minLines = 3,
        )
    }
}

@Composable
private fun RubricField(
    stepBadge: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    minLines: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stepBadge, color = AresCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, fontSize = 11.sp) },
            minLines = minLines,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun RawMarkdownEditorPanel(
    markdown: String,
    authorId: String,
    onAuthorChange: (String) -> Unit,
    onMarkdownChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = authorId,
                onValueChange = onAuthorChange,
                label = { Text("Author") },
                singleLine = true,
                modifier = Modifier.width(280.dp),
            )
            Text(
                "${markdown.length} chars · Canonical Markdown",
                color = AresTextTertiary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        OutlinedTextField(
            value = markdown,
            onValueChange = onMarkdownChange,
            label = { Text("Markdown Body (switches losslessly back to Guided FIRST Rubric)") },
            minLines = 16,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = AresTextPrimary,
                lineHeight = 18.sp,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun FormattedNotebookPreviewPanel(markdown: String) {
    Surface(
        color = AresSurfaceElevated,
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (markdown.isBlank()) {
                Text("Empty notebook entry.", color = AresTextSecondary, fontSize = 12.sp)
            } else {
                markdown.replace("\r\n", "\n").lines().forEach { line ->
                    val trimmed = line.trim()
                    when {
                        trimmed.isEmpty() -> Spacer(Modifier.height(4.dp))
                        trimmed.startsWith("# ") && !trimmed.startsWith("## ") -> Text(
                            trimmed.removePrefix("# ").trim(),
                            color = AresTextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        trimmed.startsWith("## ") -> {
                            HorizontalDivider(color = AresBorder.copy(alpha = 0.6f), modifier = Modifier.padding(top = 6.dp))
                            Text(
                                trimmed.removePrefix("## ").trim(),
                                color = AresCyan,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        trimmed.startsWith("### ") -> Text(
                            trimmed.removePrefix("### ").trim(),
                            color = AresGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        trimmed.startsWith("|") -> Text(
                            trimmed,
                            color = AresTextPrimary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        trimmed.startsWith("- ") || trimmed.startsWith("* ") -> Text(
                            "• ${trimmed.drop(2).trim()}",
                            color = AresTextPrimary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                        else -> Text(
                            trimmed,
                            color = AresTextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun NotebookEvidenceSection(
    evidence: List<NotebookEvidenceReference>,
    onAddReference: (String, String, String?) -> Unit,
    onRemoveReference: (String) -> Unit,
    onOpenReplaySession: (String) -> Unit,
    onOpenGuidedReviewSession: (String) -> Unit,
) {
    var showAddForm by remember { mutableStateOf(false) }
    var newKind by remember { mutableStateOf("session") }
    var newRefId by remember { mutableStateOf("") }
    var newLabel by remember { mutableStateOf("") }

    Surface(
        color = AresSurface,
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Link, contentDescription = null, tint = AresCyan, modifier = Modifier.size(16.dp))
                    Text(
                        "Linked Telemetry & Artifact Evidence (${evidence.size})",
                        color = AresTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                    )
                }
                OutlinedButton(
                    onClick = { showAddForm = !showAddForm },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (showAddForm) "Close" else "Link Evidence", fontSize = 11.sp)
                }
            }

            if (showAddForm) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newKind,
                        onValueChange = { newKind = it },
                        label = { Text("Kind") },
                        singleLine = true,
                        modifier = Modifier.width(110.dp),
                    )
                    OutlinedTextField(
                        value = newRefId,
                        onValueChange = { newRefId = it },
                        label = { Text("Reference ID (e.g. session:abc123)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = newLabel,
                        onValueChange = { newLabel = it },
                        label = { Text("Label (optional)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            if (newRefId.isNotBlank()) {
                                onAddReference(newKind, newRefId, newLabel)
                                newRefId = ""
                                newLabel = ""
                                showAddForm = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                    ) {
                        Text("Attach", fontSize = 11.sp)
                    }
                }
            }

            if (evidence.isEmpty()) {
                Text(
                    "No linked artifacts yet. Capture from Guided Run Review, Match Strategy, Tuning, Verification, or Run History to attach verifiable SHA-256 / session references automatically.",
                    color = AresTextTertiary,
                    fontSize = 11.sp,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    evidence.forEach { ref ->
                        val sessionId = ref.referenceId.removePrefix("session:").takeIf {
                            ref.kind == "session" || ref.referenceId.startsWith("session:")
                        }
                        Surface(
                            color = AresSurfaceElevated,
                            border = BorderStroke(1.dp, AresBorder),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    "[${ref.kind}] ${ref.referenceId.take(28)}",
                                    color = AresCyan,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                                ref.label?.takeIf(String::isNotBlank)?.let { lbl ->
                                    Text(lbl.take(36), color = AresTextSecondary, fontSize = 11.sp)
                                }
                                ref.sha256?.take(8)?.let { sha ->
                                    Text("#$sha", color = AresAmber, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                                if (!sessionId.isNullOrBlank()) {
                                    IconButton(
                                        onClick = { onOpenReplaySession(sessionId) },
                                        modifier = Modifier.size(22.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = "Open Replay for $sessionId",
                                            tint = AresGreen,
                                            modifier = Modifier.size(15.dp),
                                        )
                                    }
                                    IconButton(
                                        onClick = { onOpenGuidedReviewSession(sessionId) },
                                        modifier = Modifier.size(22.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Analytics,
                                            contentDescription = "Open Guided Review for $sessionId",
                                            tint = AresCyan,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { onRemoveReference(ref.referenceId) },
                                    modifier = Modifier.size(20.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove evidence link",
                                        tint = AresTextTertiary,
                                        modifier = Modifier.size(13.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
