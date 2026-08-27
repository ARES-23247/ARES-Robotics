@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ares.analytics.ui.components.tuning

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.tuning.ExperimentDecision
import com.ares.analytics.service.tuning.ExperimentDirection
import com.ares.analytics.service.tuning.ExperimentMetricGoal
import com.ares.analytics.service.tuning.ExperimentPhase
import com.ares.analytics.service.tuning.canAcceptSimulationResult
import com.ares.analytics.service.tuning.ExperimentValue
import com.ares.analytics.service.tuning.displayValue
import com.ares.analytics.service.tuning.numericValue
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.viewmodel.TuningState
import com.ares.analytics.viewmodel.tuning.GuidedTuningExperimentIntent
import com.ares.analytics.viewmodel.tuning.GuidedTuningExperimentState
import com.ares.analytics.viewmodel.tuning.GuidedTuningExperimentViewModel
import com.ares.analytics.viewmodel.tuning.guidedExperimentLabel
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun GuidedTuningExperimentPanel(
    viewModel: GuidedTuningExperimentViewModel,
    state: GuidedTuningExperimentState,
    tuningState: TuningState,
    canLaunchSimulator: Boolean,
    canApplyCandidateToSimulator: Boolean,
    simulatorStatus: String,
    onLaunchSimulator: () -> Unit,
    onApplyCandidateToSimulator: () -> Unit,
    onOpenDashboard: () -> Unit,
    onStopSimulator: () -> Unit,
    onOpenGuidedRunReview: () -> Unit,
    onOpenReplay: (String, Long) -> Unit,
    onOpenAdvancedProfiles: () -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Guided tuning experiment", color = AresTextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Change one declared value, test it in simulation, compare evidence, then accept, revise, reject, or roll back.",
                    color = AresTextSecondary,
                )
            }
            OutlinedButton(onClick = onOpenAdvancedProfiles) { Text("Advanced profiles & calibration") }
        }
        ExperimentFlowBoundary()
        state.errorMessage?.let { MessageBanner(it, AresError, Icons.Default.Warning) }
        state.statusMessage?.let { MessageBanner(it, AresGreen, Icons.Default.CheckCircle) }
        if (state.seed == null) {
            EmptyExperimentStart(state, viewModel, onOpenGuidedRunReview)
            return@Column
        }
        EvidenceStep(state, onOpenReplay)
        PlanStep(state, tuningState, viewModel)
        SnapshotAndSimulationStep(
            state,
            viewModel,
            tuningState,
            canLaunchSimulator,
            canApplyCandidateToSimulator,
            simulatorStatus,
            onLaunchSimulator,
            onApplyCandidateToSimulator,
            onOpenDashboard,
            onStopSimulator,
        )
        CandidateStep(state, viewModel)
        DecisionStep(state, viewModel, onOpenAdvancedProfiles)
    }
}

@Composable
private fun ExperimentFlowBoundary() {
    Surface(
        color = AresCyan.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(10.dp),
    ) {
        FlowRow(
            Modifier.fillMaxWidth().padding(12.dp).semantics {
                contentDescription = "Guided tuning flow: evidence, one bounded change, configuration snapshot, simulation, paired run comparison, decision"
            },
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowStage("1 · EVIDENCE", "Observation, not cause")
            FlowStage("2 · ONE CHANGE", "Typed and bounded")
            FlowStage("3 · SNAPSHOT", "Profile + robot config")
            FlowStage("4 · SIMULATE", "No physical claim")
            FlowStage("5 · COMPARE", "Exact run evidence")
            FlowStage("6 · DECIDE", "Accept, revise, reject, rollback")
        }
    }
}

@Composable
private fun FlowStage(title: String, explanation: String) {
    Column(Modifier.width(155.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = AresCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(explanation, color = AresTextSecondary, fontSize = 10.sp)
    }
}

@Composable
private fun EmptyExperimentStart(
    state: GuidedTuningExperimentState,
    viewModel: GuidedTuningExperimentViewModel,
    onOpenGuidedRunReview: () -> Unit,
) {
    StepCard("Start from measured evidence", "Guided experiments begin with a finding from two compatible runs, not a guessed constant.") {
        Button(
            onClick = onOpenGuidedRunReview,
            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
        ) {
            Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("Open Guided Run Review")
        }
        if (state.experiments.isNotEmpty()) {
            HorizontalDivider(color = AresBorder)
            Text("Continue a saved local experiment", color = AresTextPrimary, fontWeight = FontWeight.Bold)
            state.experiments.take(6).forEach { experiment ->
                OutlinedButton(
                    onClick = { viewModel.onIntent(GuidedTuningExperimentIntent.LoadExperiment(experiment.uid)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(experiment.title, color = AresTextPrimary)
                        Text(
                            "${experiment.phase.name.replace('_', ' ')} · ${experiment.change.displayName} · ${experiment.uid.takeLast(8)}",
                            color = AresTextSecondary,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EvidenceStep(state: GuidedTuningExperimentState, onOpenReplay: (String, Long) -> Unit) {
    val finding = requireNotNull(state.seed).finding
    StepCard("1 · Select and understand the evidence", "ARES preserves the claim type, timestamp, and topics. It does not turn correlation into cause.") {
        Text(finding.kind.label, color = if (finding.kind.name == "CORRELATION") AresAmber else AresGreen, fontWeight = FontWeight.Bold)
        Text(finding.title, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(finding.explanation, color = AresTextSecondary)
        Text(
            "${finding.evidence.absoluteTimestampMs} ms · ${finding.evidence.topics.joinToString()}",
            color = AresTextTertiary,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
        OutlinedButton(onClick = { onOpenReplay(finding.evidence.sessionId, finding.evidence.absoluteTimestampMs) }) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text("Inspect exact replay evidence")
        }
    }
}

@Composable
private fun PlanStep(
    state: GuidedTuningExperimentState,
    tuningState: TuningState,
    viewModel: GuidedTuningExperimentViewModel,
) {
    val editable = tuningState.rows.filter { row ->
        row.sourceTypedValue?.numericValue() != null &&
            row.declaration.type.name in setOf("DOUBLE", "INT") &&
            row.declaration.applyPolicy.name != "READ_ONLY_VENDOR"
    }
    StepCard("2 · Plan one controlled change", "Only one declared numeric value can change. ARES limits the candidate to a small step inside the component's declared bounds.") {
        OutlinedTextField(
            value = state.question,
            onValueChange = { viewModel.onIntent(GuidedTuningExperimentIntent.SetQuestion(it)) },
            enabled = state.experiment == null,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Question: what are we trying to learn?") },
            minLines = 1,
        )
        var parameterMenu by remember { mutableStateOf(false) }
        var metricMenu by remember { mutableStateOf(false) }
        val selectedRow = editable.firstOrNull { it.declaration.uid == state.parameterUid }
        Box {
            OutlinedButton(onClick = { parameterMenu = true }, enabled = state.experiment == null) {
                Text(selectedRow?.declaration?.displayName ?: "Choose a typed parameter")
                Spacer(Modifier.width(5.dp))
                Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(17.dp))
            }
            DropdownMenu(parameterMenu, { parameterMenu = false }) {
                editable.forEach { row ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(row.declaration.displayName)
                                Text(
                                    "${row.declaration.componentUid} · ${row.sourceTypedValue?.displayValue()} ${row.declaration.unit.orEmpty()}",
                                    color = AresTextSecondary,
                                    fontSize = 10.sp,
                                )
                            }
                        },
                        onClick = {
                            parameterMenu = false
                            viewModel.onIntent(GuidedTuningExperimentIntent.SelectParameter(row.declaration.uid))
                        },
                    )
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExperimentDirection.entries.forEach { direction ->
                val selected = state.direction == direction
                if (selected) {
                    Button(
                        onClick = {},
                        enabled = state.experiment == null,
                        colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                    ) { Text(direction.name.lowercase().replaceFirstChar(Char::uppercaseChar)) }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.onIntent(GuidedTuningExperimentIntent.SetDirection(direction)) },
                        enabled = state.experiment == null,
                    ) { Text(direction.name.lowercase().replaceFirstChar(Char::uppercaseChar)) }
                }
            }
        }
        state.proposalPreview?.let { proposal ->
            Surface(color = AresSurfaceElevated, shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Bounded candidate", color = AresCyan, fontWeight = FontWeight.Bold)
                    Text(
                        "${proposal.before.displayValue()} → ${proposal.proposed.displayValue()} ${proposal.declaration.unit.orEmpty()}",
                        color = AresTextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                    )
                    Text(
                        "Step ${proposal.stepLimit.uiNumber()} ${proposal.declaration.unit.orEmpty()} · declared range " +
                            "${proposal.declaration.minimum.uiNumberOr("unbounded")} to ${proposal.declaration.maximum.uiNumberOr("unbounded")} · " +
                            proposal.declaration.applyPolicy.name.replace('_', ' '),
                        color = AresTextSecondary,
                        fontSize = 10.sp,
                    )
                }
            }
        }
        OutlinedTextField(
            value = state.hypothesis,
            onValueChange = { viewModel.onIntent(GuidedTuningExperimentIntent.SetHypothesis(it)) },
            enabled = state.experiment == null,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Experiment hypothesis. State what one bounded parameter change should improve in the selected metric."
            },
            label = { Text("Prediction: if we make this change, what should improve?") },
            minLines = 2,
        )
        Box {
            OutlinedButton(onClick = { metricMenu = true }, enabled = state.experiment == null) {
                Text(state.selectedMetric?.let { "${it.label} · ${it.statistic.name.lowercase()}" } ?: "Choose an outcome metric")
                Spacer(Modifier.width(5.dp))
                Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(17.dp))
            }
            DropdownMenu(metricMenu, { metricMenu = false }) {
                state.seed?.availableMetrics.orEmpty().forEach { metric ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(metric.label)
                                Text(
                                    "${metric.statistic.name.lowercase()} · ${metric.goal.name.lowercase().replace('_', ' ')} · ${metric.unit}",
                                    color = AresTextSecondary,
                                    fontSize = 10.sp,
                                )
                            }
                        },
                        onClick = {
                            metricMenu = false
                            viewModel.onIntent(GuidedTuningExperimentIntent.SelectMetric(metric.id))
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = state.successThresholdText,
            onValueChange = { viewModel.onIntent(GuidedTuningExperimentIntent.SetSuccessThreshold(it)) },
            enabled = state.experiment == null && state.selectedMetric?.goal != ExperimentMetricGoal.OBSERVE_ONLY,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Minimum improvement required (%)") },
            supportingText = { Text("A result below this threshold is inconclusive, even if it moved in the preferred direction.") },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.heldConstantsText,
            onValueChange = { viewModel.onIntent(GuidedTuningExperimentIntent.SetHeldConstants(it)) },
            enabled = state.experiment == null,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("What will stay the same? One condition per line") },
            minLines = 3,
        )
        OutlinedTextField(
            value = state.safetyNotes,
            onValueChange = { viewModel.onIntent(GuidedTuningExperimentIntent.SetSafetyNotes(it)) },
            enabled = state.experiment == null,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Simulation safety and stop conditions") },
            minLines = 2,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.requestPeerReview,
                onCheckedChange = { viewModel.onIntent(GuidedTuningExperimentIntent.SetPeerReviewRequested(it)) },
                enabled = state.experiment == null,
            )
            Column {
                Text("Ask a teammate for a second review (optional)", color = AresTextPrimary)
                Text("Records a collaboration prompt; it is never permission or approval.", color = AresTextSecondary, fontSize = 10.sp)
            }
        }
        Button(
            onClick = { viewModel.onIntent(GuidedTuningExperimentIntent.CreateAndStage) },
            enabled = state.experiment == null && state.proposalPreview != null && state.question.isNotBlank() &&
                state.hypothesis.isNotBlank() && state.heldConstantsText.isNotBlank() && state.safetyNotes.isNotBlank() &&
                state.selectedMetric != null && !state.isWorking,
            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
        ) {
            Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("Snapshot configuration & stage one proposal")
        }
    }
}

@Composable
private fun SnapshotAndSimulationStep(
    state: GuidedTuningExperimentState,
    viewModel: GuidedTuningExperimentViewModel,
    tuningState: TuningState,
    canLaunchSimulator: Boolean,
    canApplyCandidateToSimulator: Boolean,
    simulatorStatus: String,
    onLaunchSimulator: () -> Unit,
    onApplyCandidateToSimulator: () -> Unit,
    onOpenDashboard: () -> Unit,
    onStopSimulator: () -> Unit,
) {
    val experiment = state.experiment ?: return
    StepCard("3 · Test the candidate in Local Sim", "The snapshot proves which canonical profile and robot descriptors the experiment started from. It does not modify them.") {
        Text("Snapshot ${experiment.snapshot.snapshotSha256.take(16)}", color = AresCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(
            "Profile ${experiment.snapshot.profileContentSha256.take(16)} · ${experiment.snapshot.resolvedProfileValues.size} typed values · ${experiment.snapshot.configurationFiles.size} configuration files",
            color = AresTextSecondary,
            fontSize = 10.sp,
        )
        Text("Canonical profile unchanged", color = AresGreen, fontWeight = FontWeight.Bold)
        Surface(
            color = AresAmber.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, AresAmber.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                "Simulation first: launch the simulator, apply the staged live-safe value only after the simulator acknowledges it, run the same routine, and record a new run. Physical testing remains a separate later step.",
                color = AresTextSecondary,
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                fontSize = 11.sp,
            )
        }
        Text("Simulator: $simulatorStatus", color = if (simulatorStatus.contains("running", true)) AresGreen else AresTextSecondary)
        val liveSafe = experiment.change.applyPolicy == "LIVE_SAFE"
        if (!liveSafe) {
            MessageBanner(
                "${experiment.change.displayName} requires ${experiment.change.applyPolicy.lowercase().replace('_', ' ')}. " +
                    "ARES will not pretend it can be injected into a running simulator; restart through the generated project workflow instead.",
                AresAmber,
                Icons.Default.Warning,
            )
        }
        tuningState.errorMessage?.let { MessageBanner(it, AresError, Icons.Default.Warning) }
        if (tuningState.saveStatus.isNotBlank()) {
            MessageBanner(tuningState.saveStatus, AresGreen, Icons.Default.CheckCircle)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onLaunchSimulator,
                enabled = canLaunchSimulator,
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("Launch verified Local Sim")
            }
            Button(
                onClick = onApplyCandidateToSimulator,
                enabled = liveSafe && canApplyCandidateToSimulator,
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                modifier = Modifier.semantics {
                    contentDescription = "Apply the one bounded candidate to Local Sim and wait for an explicit runtime acknowledgement"
                },
            ) {
                Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("Apply candidate to Local Sim")
            }
            OutlinedButton(onClick = onOpenDashboard) { Text("Open simulator dashboard") }
            OutlinedButton(onClick = onStopSimulator) { Text("Stop simulator") }
            OutlinedButton(onClick = { viewModel.onIntent(GuidedTuningExperimentIntent.RefreshCandidateRuns) }) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Refresh recorded runs")
            }
        }
        if (liveSafe && !canApplyCandidateToSimulator) {
            Text(
                "To apply: launch Local Sim, wait until its loopback NT4 connection is online, then return here. ARES never sends this guided change to a live robot.",
                color = AresTextSecondary,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun CandidateStep(state: GuidedTuningExperimentState, viewModel: GuidedTuningExperimentViewModel) {
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
private fun DecisionStep(
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
private fun StepCard(title: String, explanation: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(explanation, color = AresTextSecondary, fontSize = 11.sp)
            HorizontalDivider(color = AresBorder)
            content()
        }
    }
}

@Composable
private fun MessageBanner(text: String, color: androidx.compose.ui.graphics.Color, icon: androidx.compose.ui.graphics.vector.ImageVector) {
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

private fun chooseExperimentReportFile(uid: String): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Export mentor/student tuning experiment"
        fileFilter = FileNameExtensionFilter("Markdown report (*.md)", "md")
        selectedFile = File("ares-tuning-${uid.takeLast(8)}.md")
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
    val selected = chooser.selectedFile
    return if (selected.extension.equals("md", true)) selected else File(selected.parentFile, "${selected.name}.md")
}

private fun ExperimentValue.displayValue(): String = toTuningValue().displayValue()

private fun Double.uiNumber(): String = BigDecimal.valueOf(this)
    .setScale(6, RoundingMode.HALF_UP)
    .stripTrailingZeros()
    .toPlainString()

private fun Double?.uiNumberOr(fallback: String): String = this?.takeIf(Double::isFinite)?.uiNumber() ?: fallback
