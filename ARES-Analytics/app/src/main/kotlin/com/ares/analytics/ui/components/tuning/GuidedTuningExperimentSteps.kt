@file:OptIn(ExperimentalLayoutApi::class)
package com.ares.analytics.ui.components.tuning

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.tuning.canAcceptSimulationResult
import com.ares.analytics.service.tuning.displayValue
import com.ares.analytics.service.tuning.ExperimentDecision
import com.ares.analytics.service.tuning.ExperimentPhase
import com.ares.analytics.service.tuning.ExperimentValue
import com.ares.analytics.ui.components.core.AresCard
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.util.DesktopFileChoosers
import com.ares.analytics.viewmodel.SysIdIntent
import com.ares.analytics.viewmodel.SysIdState
import com.ares.analytics.viewmodel.SysIdViewModel
import com.ares.analytics.viewmodel.tuning.guidedExperimentLabel
import com.ares.analytics.viewmodel.tuning.GuidedTuningExperimentIntent
import com.ares.analytics.viewmodel.tuning.GuidedTuningExperimentState
import com.ares.analytics.viewmodel.tuning.GuidedTuningExperimentViewModel
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
internal fun CandidateStep(state: GuidedTuningExperimentState, viewModel: GuidedTuningExperimentViewModel) {
    val experiment = state.experiment ?: return
    StepCard("4 · Compare baseline and candidate", "ARES accepts only a new Local Sim recording from this workspace made after the configuration snapshot.") {
        Text("Baseline · ${experiment.baselineSessionId}", color = AresTextSecondary, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        var candidatesOpen by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { candidatesOpen = true }, enabled = state.candidateRuns.isNotEmpty()) {
                Text(state.candidateRuns.firstOrNull { it.sessionId == state.selectedCandidateSessionId }?.guidedExperimentLabel() ?: "No new simulation run yet")
                Spacer(Modifier.width(5.dp))
                Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(17.dp))
            }
            DropdownMenu(candidatesOpen, { candidatesOpen = false }) {
                state.candidateRuns.forEach { run ->
                    DropdownMenuItem(
                        text = { Text(run.guidedExperimentLabel()) },
                        onClick = {
                            candidatesOpen = false
                            viewModel.onIntent(GuidedTuningExperimentIntent.SelectCandidateRun(run.sessionId))
                        },
                    )
                }
            }
        }
        Button(
            onClick = { viewModel.onIntent(GuidedTuningExperimentIntent.EvaluateCandidate) },
            enabled = state.selectedCandidateSessionId != null && !state.isWorking,
            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
        ) {
            if (state.isWorking) CircularProgressIndicator(Modifier.size(16.dp), color = AresOnAccent, strokeWidth = 2.dp)
            else Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("Compare baseline and candidate")
        }
        experiment.evaluation?.let { evaluation ->
            Surface(
                color = AresSurfaceElevated,
                border = BorderStroke(1.dp, if (evaluation.improvedIntendedMetric == true) AresGreen else AresAmber),
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(experiment.metric.metricLabel, color = AresTextPrimary, fontWeight = FontWeight.Bold)
                    Text(
                        "${evaluation.baselineValue.uiNumberOr("unavailable")} → " +
                            "${evaluation.candidateValue.uiNumberOr("unavailable")} ${evaluation.unit}",
                        color = AresCyan,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 17.sp,
                    )
                    Text(evaluation.summary, color = AresTextSecondary)
                    Text("Correlation remains unproven; simulator evidence is not physical validation.", color = AresAmber, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
internal fun DecisionStep(
    state: GuidedTuningExperimentState,
    viewModel: GuidedTuningExperimentViewModel,
    onOpenAdvancedProfiles: () -> Unit,
) {
    val experiment = state.experiment ?: return
    var note by remember(experiment.uid, experiment.decisionNote) { mutableStateOf(experiment.decisionNote) }
    var nextTest by remember(experiment.uid, experiment.nextTest) { mutableStateOf(experiment.nextTest) }
    StepCard("5 · Record the engineering decision", "Accepting preserves simulation evidence only. Canonical promotion remains a separate reviewed diff; rollback removes the local proposal.") {
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Why accept, revise, reject, or roll back?") },
            minLines = 2,
        )
        OutlinedTextField(
            value = nextTest,
            onValueChange = { nextTest = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("What is the next safe test or optional peer-review step?") },
            minLines = 2,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.onIntent(GuidedTuningExperimentIntent.Decide(ExperimentDecision.ACCEPT, note, nextTest)) },
                enabled = experiment.canAcceptSimulationResult() && note.isNotBlank() && nextTest.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AresGreen, contentColor = AresOnAccent),
            ) { Text("Accept simulation result") }
            OutlinedButton(
                onClick = { viewModel.onIntent(GuidedTuningExperimentIntent.Decide(ExperimentDecision.REVISE, note, nextTest)) },
                enabled = experiment.evaluation != null && note.isNotBlank() && nextTest.isNotBlank(),
            ) { Text("Revise experiment") }
            OutlinedButton(
                onClick = { viewModel.onIntent(GuidedTuningExperimentIntent.Decide(ExperimentDecision.REJECT, note, nextTest)) },
                enabled = experiment.evaluation != null && note.isNotBlank() && nextTest.isNotBlank(),
            ) { Text("Reject candidate") }
            OutlinedButton(
                onClick = { viewModel.onIntent(GuidedTuningExperimentIntent.Decide(ExperimentDecision.ROLL_BACK, note, nextTest)) },
                enabled = note.isNotBlank() && nextTest.isNotBlank(),
            ) {
                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Roll back proposal")
            }
        }
        if (experiment.evaluation != null && !experiment.canAcceptSimulationResult()) {
            Text(
                "Accept stays unavailable unless the selected recorded metric measurably improves. Choose Revise to change the experiment or Roll back to remove the candidate.",
                color = AresAmber,
                fontSize = 10.sp,
            )
        }
        if (experiment.phase == ExperimentPhase.ACCEPTED) {
            Button(onClick = onOpenAdvancedProfiles) { Text("Review separate canonical promotion") }
        }
        if (experiment.phase == ExperimentPhase.REVISION_REQUESTED) {
            Button(
                onClick = { viewModel.onIntent(GuidedTuningExperimentIntent.StartRevision) },
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) { Text("Start revised one-change experiment") }
        }
        OutlinedButton(
            onClick = {
                chooseExperimentReportFile(experiment.uid)?.let {
                    viewModel.onIntent(GuidedTuningExperimentIntent.ExportReport(it))
                }
            },
        ) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text("Export mentor/student engineering report")
        }
    }
}

@Composable
internal fun StepCard(title: String, explanation: String, content: @Composable () -> Unit) {
    AresCard(
        backgroundColor = AresSurface,
        contentPadding = 16.dp,
        contentSpacing = 10.dp,
    ) {
        Text(title, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(explanation, color = AresTextSecondary, fontSize = 11.sp)
        HorizontalDivider(color = AresBorder)
        content()
    }
}

@Composable
internal fun MessageBanner(text: String, color: androidx.compose.ui.graphics.Color, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.09f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(17.dp))
        Text(text, color = color, fontSize = 11.sp)
    }
}

internal fun chooseExperimentReportFile(uid: String): File? =
    DesktopFileChoosers.chooseSaveFile(
        dialogTitle = "Export mentor/student tuning experiment",
        defaultFileName = "ares-tuning-${uid.takeLast(8)}.md",
        filterDescription = "Markdown report (*.md)",
        extensions = listOf("md")
    )

internal fun ExperimentValue.displayValue(): String = toTuningValue().displayValue()

internal fun Double.uiNumber(): String = BigDecimal.valueOf(this)
    .setScale(6, RoundingMode.HALF_UP)
    .stripTrailingZeros()
    .toPlainString()

internal fun Double?.uiNumberOr(fallback: String): String = this?.takeIf(Double::isFinite)?.uiNumber() ?: fallback
