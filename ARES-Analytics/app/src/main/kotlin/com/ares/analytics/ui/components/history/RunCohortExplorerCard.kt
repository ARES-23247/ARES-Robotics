@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ares.analytics.ui.components.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.domain.analysis.CohortComparisonResult
import com.ares.analytics.domain.analysis.CohortMetricSpec
import com.ares.analytics.domain.analysis.CohortSplitMode
import com.ares.analytics.domain.analysis.CohortStatisticsAnalyzer
import com.ares.analytics.domain.analysis.CohortSummaryStats
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionSummary
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
import com.ares.analytics.ui.theme.AresTextTertiary
import java.util.Locale

@Composable
fun RunCohortExplorerCard(
    sessions: List<Session>,
    summaries: Map<String, SessionSummary>,
    diagnosticsMap: Map<String, Map<String, Double>>,
    motorNames: List<String>,
    onLogCohortToNotebook: ((CohortComparisonResult) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val metrics = remember(motorNames) { CohortStatisticsAnalyzer.defaultMetrics(motorNames) }
    val discoveredTags = remember(sessions) { CohortStatisticsAnalyzer.discoverTags(sessions) }

    var selectedMetricId by remember { mutableStateOf(metrics.first().id) }
    var splitMode by remember { mutableStateOf(CohortSplitMode.CHRONOLOGICAL_HALVES) }
    var primaryTag by remember { mutableStateOf("") }
    var secondaryTag by remember { mutableStateOf("") }
    var metricMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(discoveredTags) {
        if (primaryTag.isBlank() && discoveredTags.isNotEmpty()) {
            primaryTag = discoveredTags.first()
        }
        if (secondaryTag.isBlank() && discoveredTags.size >= 2) {
            secondaryTag = discoveredTags[1]
        }
    }

    val selectedMetric: CohortMetricSpec = metrics.firstOrNull { it.id == selectedMetricId } ?: metrics.first()
    val comparison = remember(sessions, summaries, diagnosticsMap, selectedMetric, splitMode, primaryTag, secondaryTag) {
        CohortStatisticsAnalyzer.compareCohorts(
            sessions = sessions,
            summariesById = summaries,
            diagnosticsById = diagnosticsMap,
            metric = selectedMetric,
            mode = splitMode,
            primaryTag = primaryTag,
            secondaryTag = secondaryTag,
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Insights, contentDescription = null, tint = AresCyan, modifier = Modifier.size(20.dp))
                    Column {
                        Text(
                            "Multi-Run Cohort Distribution Explorer",
                            color = AresTextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                        Text(
                            "Compare five-number quartiles (Min–Q1–Median–Q3–Max), IQR outliers, and effect size across run cohorts.",
                            color = AresTextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
                if (onLogCohortToNotebook != null) {
                    Button(
                        onClick = { onLogCohortToNotebook(comparison) },
                        enabled = comparison.cohortA != null && comparison.cohortB != null,
                        colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Log Cohort to Notebook", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    OutlinedButton(
                        onClick = { metricMenuExpanded = true },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.6f)),
                    ) {
                        Text(
                            "Metric: ${selectedMetric.label} (${selectedMetric.unit})",
                            color = AresTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = AresCyan)
                    }
                    DropdownMenu(
                        expanded = metricMenuExpanded,
                        onDismissRequest = { metricMenuExpanded = false },
                    ) {
                        metrics.forEach { m ->
                            DropdownMenuItem(
                                text = { Text("${m.label} (${m.unit})") },
                                onClick = {
                                    selectedMetricId = m.id
                                    metricMenuExpanded = false
                                },
                            )
                        }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CohortSplitMode.entries.forEach { mode ->
                        FilterChip(
                            selected = splitMode == mode,
                            onClick = { splitMode = mode },
                            label = { Text(mode.label, fontSize = 11.sp) },
                        )
                    }
                }
            }

            if (splitMode == CohortSplitMode.TAG_VS_TAG || splitMode == CohortSplitMode.TAG_VS_REST) {
                CohortTagSelectorRow(
                    discoveredTags = discoveredTags,
                    splitMode = splitMode,
                    primaryTag = primaryTag,
                    secondaryTag = secondaryTag,
                    onSelectPrimary = { primaryTag = it },
                    onSelectSecondary = { secondaryTag = it },
                )
            }

            CohortBoxplotCanvas(
                cohortA = comparison.cohortA,
                cohortB = comparison.cohortB,
                metric = selectedMetric,
            )

            if (comparison.cohortA != null || comparison.cohortB != null) {
                CohortStatsSummaryTable(
                    cohortA = comparison.cohortA,
                    cohortB = comparison.cohortB,
                    metric = selectedMetric,
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = AresSurface,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, AresBorder),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = comparison.plainEnglishTakeaway,
                        color = AresTextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    val badgeColor = when (comparison.improved) {
                        true -> AresGreen
                        false -> AresRed
                        null -> AresAmber
                    }
                    Surface(
                        color = badgeColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.5f)),
                    ) {
                        Text(
                            text = comparison.effectMagnitudeLabel,
                            color = badgeColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CohortTagSelectorRow(
    discoveredTags: List<String>,
    splitMode: CohortSplitMode,
    primaryTag: String,
    secondaryTag: String,
    onSelectPrimary: (String) -> Unit,
    onSelectSecondary: (String) -> Unit,
) {
    if (discoveredTags.isEmpty()) {
        Text(
            "No cohort tags found on workspace runs yet. Click any Run # header below (or set tags in Import Center) to tag runs like `pid-v1`, `pid-v2`, or `comp`.",
            color = AresAmber,
            fontSize = 11.sp,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (splitMode == CohortSplitMode.TAG_VS_TAG) "Cohort A Tag:" else "Target Tag:",
                color = AresTextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            discoveredTags.forEach { tag ->
                FilterChip(
                    selected = primaryTag.equals(tag, ignoreCase = true),
                    onClick = { onSelectPrimary(tag) },
                    label = { Text(tag, fontSize = 11.sp) },
                )
            }
        }
        if (splitMode == CohortSplitMode.TAG_VS_TAG) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Cohort B Tag:",
                    color = AresTextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                discoveredTags.forEach { tag ->
                    FilterChip(
                        selected = secondaryTag.equals(tag, ignoreCase = true),
                        onClick = { onSelectSecondary(tag) },
                        label = { Text(tag, fontSize = 11.sp) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CohortBoxplotCanvas(
    cohortA: CohortSummaryStats?,
    cohortB: CohortSummaryStats?,
    metric: CohortMetricSpec,
) {
    val textMeasurer = rememberTextMeasurer()
    val nonEmpty = listOfNotNull(cohortA, cohortB)
    if (nonEmpty.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .background(AresSurface, RoundedCornerShape(8.dp))
                .border(1.dp, AresBorder, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "No recorded ${metric.label} samples in the selected cohorts.",
                color = AresTextTertiary,
                fontSize = 12.sp,
            )
        }
        return
    }

    val rawMin = nonEmpty.minOf { it.min }
    val rawMax = nonEmpty.maxOf { it.max }
    val span = (rawMax - rawMin).takeIf { it > 1e-6 } ?: (kotlin.math.abs(rawMax).coerceAtLeast(1.0) * 0.2)
    val domainMin = rawMin - span * 0.06
    val domainMax = rawMax + span * 0.06

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(148.dp)
            .background(AresSurface, RoundedCornerShape(8.dp))
            .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        val leftGutter = 156f
        val rightPad = 16f
        val plotWidth = (size.width - leftGutter - rightPad).coerceAtLeast(60f)
        val axisY = size.height - 20f

        fun xFor(value: Double): Float {
            val ratio = ((value - domainMin) / (domainMax - domainMin)).coerceIn(0.0, 1.0)
            return leftGutter + (ratio * plotWidth).toFloat()
        }

        val ticks = listOf(rawMin, (rawMin + rawMax) * 0.5, rawMax)
        ticks.forEach { tick ->
            val tx = xFor(tick)
            drawLine(
                color = AresBorder.copy(alpha = 0.6f),
                start = Offset(tx, 6f),
                end = Offset(tx, axisY),
                strokeWidth = 1f,
            )
            val label = "${String.format(Locale.US, "%.${metric.decimals}f", tick)} ${metric.unit}"
            val measured = textMeasurer.measure(
                label,
                style = TextStyle(color = AresTextTertiary, fontSize = 10.sp, fontFamily = FontFamily.Monospace),
            )
            val lx = (tx - measured.size.width / 2f).coerceIn(leftGutter - 10f, (size.width - measured.size.width).coerceAtLeast(0f))
            drawText(measured, topLeft = Offset(lx, axisY + 3f))
        }

        cohortA?.let { stats ->
            drawCohortBoxRow(stats, centerY = 34f, color = AresCyan, xFor = ::xFor, textMeasurer = textMeasurer)
        }
        cohortB?.let { stats ->
            drawCohortBoxRow(stats, centerY = 86f, color = AresGreen, xFor = ::xFor, textMeasurer = textMeasurer)
        }
    }
}

private fun DrawScope.drawCohortBoxRow(
    stats: CohortSummaryStats,
    centerY: Float,
    color: Color,
    xFor: (Double) -> Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
) {
    val labelText = "${stats.cohortName.take(18)} (n=${stats.count})"
    val measured = textMeasurer.measure(
        labelText,
        style = TextStyle(color = AresTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
    )
    drawText(measured, topLeft = Offset(0f, centerY - measured.size.height / 2f))

    val inliers = stats.samples.filterNot { it.isOutlier }.map { it.value }
    val whiskerLow = xFor(inliers.minOrNull() ?: stats.min)
    val whiskerHigh = xFor(inliers.maxOrNull() ?: stats.max)
    val q1X = xFor(stats.q1)
    val medX = xFor(stats.median)
    val q3X = xFor(stats.q3)
    val boxHalfH = 13f

    drawLine(color = color.copy(alpha = 0.85f), start = Offset(whiskerLow, centerY), end = Offset(whiskerHigh, centerY), strokeWidth = 2f)
    drawLine(color = color, start = Offset(whiskerLow, centerY - 8f), end = Offset(whiskerLow, centerY + 8f), strokeWidth = 2f)
    drawLine(color = color, start = Offset(whiskerHigh, centerY - 8f), end = Offset(whiskerHigh, centerY + 8f), strokeWidth = 2f)

    val boxWidth = (q3X - q1X).coerceAtLeast(3f)
    drawRect(
        color = color.copy(alpha = 0.22f),
        topLeft = Offset(q1X, centerY - boxHalfH),
        size = Size(boxWidth, boxHalfH * 2f),
    )
    drawRect(
        color = color,
        topLeft = Offset(q1X, centerY - boxHalfH),
        size = Size(boxWidth, boxHalfH * 2f),
        style = Stroke(width = 2f),
    )
    drawLine(
        color = AresTextPrimary,
        start = Offset(medX, centerY - boxHalfH - 2f),
        end = Offset(medX, centerY + boxHalfH + 2f),
        strokeWidth = 3f,
    )

    stats.samples.forEachIndexed { idx, sample ->
        val sx = xFor(sample.value)
        val jitterY = centerY + ((idx % 5) - 2) * 3.2f
        if (sample.isOutlier) {
            drawCircle(color = AresAmber, radius = 6.5f, center = Offset(sx, jitterY), style = Stroke(width = 2f))
        }
        drawCircle(color = if (sample.isOutlier) AresAmber else color, radius = 3.6f, center = Offset(sx, jitterY))
    }
}

@Composable
private fun CohortStatsSummaryTable(
    cohortA: CohortSummaryStats?,
    cohortB: CohortSummaryStats?,
    metric: CohortMetricSpec,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AresSurface, RoundedCornerShape(8.dp))
            .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Cohort", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.6f))
            Text("Min", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.9f))
            Text("Q1", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.9f))
            Text("Median", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.0f))
            Text("Q3", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.9f))
            Text("Max", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.9f))
            Text("Mean ± SD", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.4f))
            Text("Outliers", color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.9f))
        }
        HorizontalDivider(color = AresBorder)
        listOfNotNull(cohortA?.let { it to AresCyan }, cohortB?.let { it to AresGreen }).forEach { (s, tint) ->
            val d = metric.decimals
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${s.cohortName} (n=${s.count})", color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1.6f))
                Text(fmtStat(s.min, d), color = AresTextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.9f))
                Text(fmtStat(s.q1, d), color = AresTextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.9f))
                Text("${fmtStat(s.median, d)} ${metric.unit}", color = AresTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1.0f))
                Text(fmtStat(s.q3, d), color = AresTextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.9f))
                Text(fmtStat(s.max, d), color = AresTextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.9f))
                Text("${fmtStat(s.mean, d)} ± ${fmtStat(s.stdDev, d)}", color = AresTextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1.4f))
                Text(
                    if (s.outlierSessionIds.isEmpty()) "None" else "${s.outlierSessionIds.size} flagged",
                    color = if (s.outlierSessionIds.isEmpty()) AresTextTertiary else AresAmber,
                    fontSize = 11.sp,
                    fontWeight = if (s.outlierSessionIds.isEmpty()) FontWeight.Normal else FontWeight.Bold,
                    modifier = Modifier.weight(0.9f),
                )
            }
        }
    }
}

private fun fmtStat(v: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", v)
