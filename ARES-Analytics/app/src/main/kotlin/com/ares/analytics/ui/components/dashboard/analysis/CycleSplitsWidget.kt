package com.ares.analytics.ui.components.dashboard.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.analysis.CycleSplitAnalyzer
import com.ares.analytics.service.analysis.CycleSplitReport
import com.ares.analytics.ui.components.core.AresCard
import com.ares.analytics.ui.components.core.CardHeader
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val STATE_TOPIC_PATTERNS = listOf(
    "%/State",
    "%/CurrentState",
    "%/CurrentStep",
    "%/Phase",
    "Routine/%",
    "Autonomous/%",
    "Superstructure/%",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CycleSplitsWidget(
    databaseService: DatabaseService,
    primarySessionId: String?,
    compareSessionId: String? = null,
    onSeekReplayTimestamp: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var preferredTopic by remember(primarySessionId) { mutableStateOf<String?>(null) }

    val report by produceState(
        initialValue = CycleSplitReport(null, emptyList(), emptyList(), emptyList(), emptyList(), null),
        databaseService,
        primarySessionId,
        compareSessionId,
        preferredTopic,
    ) {
        if (primarySessionId.isNullOrBlank()) {
            value = CycleSplitReport(null, emptyList(), emptyList(), emptyList(), emptyList(), null)
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            val primaryFrames = databaseService.getTelemetryForKeyPatterns(primarySessionId, STATE_TOPIC_PATTERNS)
            val primaryActions = databaseService.getActionsForSession(primarySessionId)
            val compareFrames = if (!compareSessionId.isNullOrBlank()) {
                databaseService.getTelemetryForKeyPatterns(compareSessionId, STATE_TOPIC_PATTERNS)
            } else {
                emptyList()
            }
            CycleSplitAnalyzer.analyze(
                primaryFrames = primaryFrames,
                compareFrames = compareFrames,
                primaryActions = primaryActions,
                preferredTopic = preferredTopic,
            )
        }
    }

    AresCard(modifier = modifier.fillMaxSize(), backgroundColor = AresSurfaceElevated) {
        CardHeader(
            title = "Auto & TeleOp Cycle Splits",
            icon = Icons.Default.Timer,
            iconTint = AresCyan,
            statusText = report.cycleSummary?.let { "${it.cycleCount} cycles · p50 ${String.format("%.2fs", it.p50Ms / 1000.0)}" }
                ?: if (report.phaseSplits.isNotEmpty()) "${report.phaseSplits.size} phases" else "NO SPLITS",
            statusColor = if (report.phaseSplits.isNotEmpty()) AresGreen else AresTextTertiary,
        )

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (report.availableStateTopics.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AnalysisTopicSelectorChip(
                        label = "State Machine",
                        selectedTopic = report.selectedTopic,
                        availableTopics = report.availableStateTopics,
                        onSelect = { preferredTopic = it },
                    )
                    if (!compareSessionId.isNullOrBlank()) {
                        AnalysisMetricPill("Compare Run B", compareSessionId.take(10), AresGold)
                    }
                }
            }

            if (report.phaseSplits.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .background(AresBackground, RoundedCornerShape(8.dp))
                        .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (primarySessionId.isNullOrBlank()) {
                            "Select a recorded run (and optional Compare Run B) to segment Autonomous step splits and TeleOp scoring cycles."
                        } else {
                            "No Routine/*, Superstructure/*, or */State transitions recorded in this session."
                        },
                        color = AresTextTertiary,
                        fontSize = 11.sp,
                    )
                }
            } else {
                report.cycleSummary?.let { summary ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AnalysisMetricPill("Cycles", summary.cycleCount.toString(), AresCyan)
                        AnalysisMetricPill("Best Lap", String.format("%.2fs", summary.bestMs / 1000.0), AresGreen)
                        AnalysisMetricPill("p50 Lap", String.format("%.2fs", summary.p50Ms / 1000.0), AresTextPrimary)
                        AnalysisMetricPill("p95 Lap", String.format("%.2fs", summary.p95Ms / 1000.0), AresAmber)
                        summary.compareDeltaP50Ms?.let { delta ->
                            val signPrefix = if (delta > 0.0) "+" else ""
                            AnalysisMetricPill(
                                label = "vs Run B (p50)",
                                value = "$signPrefix${String.format("%.0f", delta)} ms",
                                valueColor = if (delta <= 0.0) AresGreen else AresAmber,
                            )
                        }
                    }
                }

                HorizontalDivider(color = AresBorder)

                val maxP95 = (report.phaseSplits.maxOfOrNull { it.p95DurationMs } ?: 1.0).coerceAtLeast(1.0)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    report.phaseSplits.forEach { split ->
                        val barFraction = (split.p50DurationMs / maxP95).toFloat().coerceIn(0.04f, 1f)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(AresBackground)
                                .border(1.dp, AresBorder.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                .clickable {
                                    report.instances.firstOrNull { it.stateName == split.stateName }?.let { inst ->
                                        onSeekReplayTimestamp(inst.startMs)
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${split.stateName} (×${split.count})",
                                    color = AresTextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "best ${String.format("%.0f", split.bestDurationMs)}ms · p50 ${String.format("%.0f", split.p50DurationMs)}ms · p95 ${String.format("%.0f", split.p95DurationMs)}ms",
                                        color = AresTextSecondary,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        textAlign = TextAlign.End,
                                    )
                                    split.compareDeltaMeanMs?.let { delta ->
                                        val prefix = if (delta > 0.0) "+" else ""
                                        Text(
                                            text = "${prefix}${String.format("%.0f", delta)}ms",
                                            color = if (delta <= 0.0) AresGreen else AresAmber,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .background(AresSurface, RoundedCornerShape(3.dp)),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(barFraction)
                                        .background(AresCyan, RoundedCornerShape(3.dp)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
