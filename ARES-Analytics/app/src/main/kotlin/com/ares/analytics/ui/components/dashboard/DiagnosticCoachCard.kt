package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DiagnosticCoachService
import com.ares.analytics.service.DiagnosticSeverity
import com.ares.analytics.service.PitDiagnosticSummary
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary

@Composable
fun DiagnosticChecklistWidget(
    service: DiagnosticCoachService,
    sessionId: String?,
    modifier: Modifier = Modifier
) {
    val result by produceState<Result<PitDiagnosticSummary>?>(null, sessionId) {
        value = sessionId?.let { runCatching { service.analyze(it) } }
    }
    when {
        sessionId == null -> DiagnosticMessageCard("Pit evidence checklist", "Select a recorded session to screen available telemetry.", modifier)
        result == null -> DiagnosticMessageCard("Pit evidence checklist", "Screening the selected session…", modifier)
        result!!.isFailure -> DiagnosticMessageCard("Checklist unavailable", result!!.exceptionOrNull()?.message ?: "The selected run could not be screened.", modifier)
        else -> DiagnosticChecklistCard(result!!.getOrThrow(), modifier)
    }
}

@Composable
fun DiagnosticChecklistCard(summary: PitDiagnosticSummary, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
        border = BorderStroke(1.dp, AresBorder)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Pit evidence checklist", color = AresTextPrimary, fontWeight = FontWeight.Bold)
            Text(summary.evidenceNotice, color = AresTextSecondary, fontSize = 12.sp)
            if (summary.findings.isEmpty()) {
                Text("No configured screening threshold was crossed. This is not proof of health or safety.", color = AresTextPrimary)
            }
            summary.findings.forEach { finding ->
                val statusText = when (finding.severity) {
                    DiagnosticSeverity.URGENT -> "Urgent review"
                    DiagnosticSeverity.REVIEW -> "Review"
                    DiagnosticSeverity.INFORMATION -> "Information"
                }
                val statusColor = if (finding.severity == DiagnosticSeverity.URGENT) AresError else AresAmber
                Card(colors = CardDefaults.cardColors(containerColor = AresSurface), border = BorderStroke(1.dp, AresBorder)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(finding.title, color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
                            Text(statusText, color = statusColor, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                        Text("Observed: ${finding.observation}", color = AresTextSecondary, fontSize = 11.sp)
                        Text(finding.thresholdContext, color = AresTextTertiary, fontSize = 10.sp)
                        Text("Possible causes to verify: ${finding.possibleCauses.joinToString()}", color = AresTextSecondary, fontSize = 11.sp)
                        Text("Verify next: ${finding.verificationSteps.joinToString(" • ")}", color = AresTextPrimary, fontSize = 11.sp)
                    }
                }
            }
            if (summary.missingSignals.isNotEmpty()) {
                Text("Not available in this run: ${summary.missingSignals.joinToString()}.", color = AresTextTertiary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun DiagnosticMessageCard(title: String, message: String, modifier: Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
        border = BorderStroke(1.dp, AresBorder)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = AresTextPrimary, fontWeight = FontWeight.Bold)
            Text(message, color = AresTextSecondary, fontSize = 12.sp)
        }
    }
}
