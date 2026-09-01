package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DriverAnalysisService
import com.ares.analytics.service.DriverCoachingReport
import com.ares.analytics.service.DriverReviewConfidence
import com.ares.analytics.ui.components.core.AresCard
import com.ares.analytics.ui.components.core.AresMessageCard
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary

@Composable
fun DriverMotionReviewWidget(
    analysisService: DriverAnalysisService,
    sessionId: String?,
    modifier: Modifier = Modifier
) {
    val result by produceState<Result<DriverCoachingReport>?>(null, sessionId) {
        value = sessionId?.let { id -> runCatching { analysisService.analyzeDriverCoaching(id) } }
    }
    when {
        sessionId == null -> AresMessageCard("Driver motion review", "Select an imported or replayed run to review synchronized chassis-motion patterns.", modifier, Icons.Default.Info)
        result == null -> AresMessageCard("Driver motion review", "Reviewing synchronized drive samples…", modifier, Icons.Default.Info)
        result!!.isFailure -> AresMessageCard("Driver motion review unavailable", result!!.exceptionOrNull()?.message ?: "The selected run could not be reviewed.", modifier, Icons.Default.Info)
        else -> DriverMotionReviewCard(result!!.getOrThrow(), modifier)
    }
}

@Composable
fun DriverMotionReviewCard(report: DriverCoachingReport, modifier: Modifier = Modifier) {
    val confidenceText = when (report.confidence) {
        DriverReviewConfidence.INSUFFICIENT -> "Insufficient evidence"
        DriverReviewConfidence.LIMITED -> "Limited evidence"
        DriverReviewConfidence.STRONG -> "Strong data coverage"
    }
    val confidenceColor = when (report.confidence) {
        DriverReviewConfidence.INSUFFICIENT -> AresAmber
        DriverReviewConfidence.LIMITED -> AresCyan
        DriverReviewConfidence.STRONG -> AresGreen
    }
    AresCard(modifier = modifier.fillMaxWidth(), contentPadding = 14.dp, contentSpacing = 10.dp) {
        Text("Driver motion review", color = AresTextPrimary, fontWeight = FontWeight.Bold)
        Text("Evidence-based practice prompts from synchronized chassis speeds. This is not a driver score and does not infer wheel slip, energy use, or match cycles.", color = AresTextSecondary, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ReviewMetric("Coverage", "${"%.0f".format(report.coverageFraction * 100.0)}%")
            ReviewMetric("Duration", "${"%.1f".format(report.durationSeconds)} s")
            ReviewMetric("Translate + turn", "${"%.0f".format(report.simultaneousTranslationRotationFraction * 100.0)}%")
            ReviewMetric("Large reversals", "${"%.0f".format(report.directionReversalRatePerMinute)}/min")
        }
        Text(confidenceText, color = confidenceColor, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        report.observations.forEach { observation ->
            AresCard(backgroundColor = AresSurface, contentPadding = 10.dp, contentSpacing = 4.dp) {
                Text(observation.title, color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
                Text("Observed: ${observation.evidence}", color = AresTextSecondary, fontSize = 11.sp)
                Text("Try next: ${observation.practiceIdea}", color = AresTextPrimary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ReviewMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = AresTextTertiary, fontSize = 9.sp)
        Text(value, color = AresTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}
