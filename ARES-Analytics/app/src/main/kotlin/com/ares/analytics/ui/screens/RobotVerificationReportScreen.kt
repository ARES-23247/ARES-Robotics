package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.verification.RobotVerificationReport
import com.ares.analytics.service.verification.VerificationLayer
import com.ares.analytics.service.verification.VerificationReportItem
import com.ares.analytics.service.verification.VerificationResultStatus
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresRed
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary

/** One student-facing view over generated, platform, simulator, build, and physical evidence. */
@Composable
fun RobotVerificationReportScreen(
    report: RobotVerificationReport?,
    isRunning: Boolean,
    onRunVerification: () -> Unit,
) {
    var advanced by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Verification", color = AresTextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "One report for Robot Builder checks, independent platform tests, simulation, build, and physical readiness.",
                        color = AresTextSecondary,
                        fontSize = 12.sp,
                    )
                }
                Button(
                    onClick = onRunVerification,
                    enabled = !isRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                ) {
                    Text(if (isRunning) "Verification running…" else "Run Verify & build", fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AresSurface),
                border = BorderStroke(1.dp, AresBorder),
                shape = RoundedCornerShape(10.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("How to read this report", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                    Text(
                        "Generated checks test the robot definition you built. Platform checks test ARES itself. " +
                            "Simulation evidence never claims that wiring or physical hardware was tested.",
                        color = AresTextSecondary,
                        fontSize = 12.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = advanced, onCheckedChange = { advanced = it })
                        Column {
                            Text("Advanced details", color = AresTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Show test identities, result files, and process evidence.", color = AresTextSecondary, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        if (report == null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
                    border = BorderStroke(1.dp, AresBorder),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("No verification run yet", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                        Text(
                            "Run Verify & build. ARES will generate disposable tests from the robot documents, run independent project tests, and compile the package without deploying.",
                            color = AresTextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        } else {
            item {
                VerificationSummary(report)
            }
            if (advanced) {
                item { VerificationProvenance(report) }
            }
            VerificationLayer.entries.forEach { layer ->
                val layerItems = report.items.filter { it.layer == layer }
                if (layerItems.isNotEmpty()) {
                    item {
                        Text(layer.studentLabel(), color = AresTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    items(layerItems, key = { it.id }) { result ->
                        VerificationResultCard(result, advanced)
                    }
                }
            }
        }
    }
}

@Composable
private fun VerificationProvenance(report: RobotVerificationReport) {
    val run = report.provenance
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Run evidence", color = AresTextPrimary, fontWeight = FontWeight.Bold)
            Text("Run: ${run.runId}", color = AresTextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text(
                "Canonical content: ${run.canonicalContentHash}",
                color = AresTextSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "ARES ${run.aresVersion} • generator ${run.generatorVersion} • Studio ${run.studioVersion}",
                color = AresTextSecondary,
                fontSize = 10.sp,
            )
            run.gitRevision?.let {
                Text("Git revision: $it", color = AresTextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Text(
                "${run.startedAt} → ${run.finishedAt} • exit ${run.buildExitCode}",
                color = AresTextSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(run.command.joinToString(" "), color = AresTextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun VerificationSummary(report: RobotVerificationReport) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (report.failedCount == 0 && report.blockedCount == 0) "Verification completed" else "Verification needs attention",
                color = AresTextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${report.passedCount} passed • ${report.failedCount} failed • ${report.blockedCount} blocked • ${report.notRunCount} not measured",
                color = AresTextSecondary,
                fontSize = 12.sp,
            )
            Text(
                if (report.readyForPhysicalValidation) {
                    "Ready for a supervised physical-validation checklist; no physical validation is claimed."
                } else {
                    "Resolve failed, blocked, or unmeasured requirements before physical testing."
                },
                color = if (report.readyForPhysicalValidation) AresGreen else AresAmber,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun VerificationResultCard(item: VerificationReportItem, advanced: Boolean) {
    val statusColor = item.status.color()
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.65f)),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(item.title, color = AresTextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    "${item.status.symbol()} ${item.status.label()}",
                    color = statusColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(item.explanation, color = AresTextSecondary, fontSize = 11.sp)
            Text(item.evidenceLevel.label, color = AresCyan, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            if (advanced) {
                HorizontalDivider(color = AresBorder)
                Text("Source: ${item.source}", color = AresTextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                if (item.advancedDetails.isNotBlank()) {
                    Text(item.advancedDetails, color = AresTextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

private fun VerificationLayer.studentLabel(): String = when (this) {
    VerificationLayer.CONFIGURATION -> "Configuration checks"
    VerificationLayer.GENERATED_BEHAVIOR -> "Robot Builder behavior tests"
    VerificationLayer.SIMULATOR -> "Simulator checks"
    VerificationLayer.PLATFORM_INTEGRATION -> "ARES platform integration"
    VerificationLayer.BUILD -> "Build result"
    VerificationLayer.PHYSICAL_VALIDATION -> "Physical validation boundary"
}

private fun VerificationResultStatus.label(): String = when (this) {
    VerificationResultStatus.PASSED -> "Passed"
    VerificationResultStatus.FAILED -> "Failed"
    VerificationResultStatus.BLOCKED -> "Blocked"
    VerificationResultStatus.NOT_RUN -> "Not measured"
}

private fun VerificationResultStatus.symbol(): String = when (this) {
    VerificationResultStatus.PASSED -> "✓"
    VerificationResultStatus.FAILED -> "×"
    VerificationResultStatus.BLOCKED -> "!"
    VerificationResultStatus.NOT_RUN -> "—"
}

private fun VerificationResultStatus.color(): Color = when (this) {
    VerificationResultStatus.PASSED -> AresGreen
    VerificationResultStatus.FAILED -> AresRed
    VerificationResultStatus.BLOCKED -> AresAmber
    VerificationResultStatus.NOT_RUN -> AresTextSecondary
}
