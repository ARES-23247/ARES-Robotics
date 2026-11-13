package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.domain.analysis.*
import com.ares.analytics.domain.pit.EventScheduleCodec
import com.ares.analytics.service.MatchInfo
import com.ares.analytics.service.toScheduledMatchItem
import com.ares.analytics.shared.models.Session
import com.ares.analytics.ui.theme.*
import java.util.Locale

@Composable
internal fun EventProgressionStrip(
    seriesList: List<EventProgressionMetricSeries>,
    selectedSessionId: String?,
    onSelectSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (seriesList.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        seriesList.forEach { series ->
            val trendColor = when (series.trend) {
                ProgressionTrend.IMPROVING -> AresGreen
                ProgressionTrend.STABLE -> AresCyan
                ProgressionTrend.REGRESSING -> AresAmber
            }
            Card(
                modifier = Modifier.width(205.dp),
                colors = CardDefaults.cardColors(containerColor = AresSurface),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, AresBorder),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(series.title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AresTextSecondary)
                        Text(series.deltaFormatted, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = trendColor)
                    }
                    Text(series.latestFormatted, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = AresTextPrimary)
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp)
                            .clickable {
                                val next = series.points.lastOrNull()?.sessionId
                                if (next != null) onSelectSession(next)
                            }
                    ) {
                        val pts = series.points
                        if (pts.isEmpty()) return@Canvas
                        val minV = pts.minOf { it.value }
                        val maxV = pts.maxOf { it.value }
                        val span = (maxV - minV).takeIf { it > 1e-6 } ?: 1.0
                        val path = Path()
                        pts.forEachIndexed { idx, pt ->
                            val x = if (pts.size == 1) size.width / 2f else (idx.toFloat() / (pts.size - 1)) * size.width
                            val norm = ((pt.value - minV) / span).toFloat()
                            val y = size.height - (norm * (size.height - 8f) + 4f)
                            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        if (pts.size >= 2) {
                            drawPath(path, color = trendColor.copy(alpha = 0.85f), style = Stroke(width = 2f, cap = StrokeCap.Round))
                        }
                        pts.forEachIndexed { idx, pt ->
                            val x = if (pts.size == 1) size.width / 2f else (idx.toFloat() / (pts.size - 1)) * size.width
                            val norm = ((pt.value - minV) / span).toFloat()
                            val y = size.height - (norm * (size.height - 8f) + 4f)
                            val isSelected = pt.sessionId == selectedSessionId
                            drawCircle(
                                color = if (isSelected) AresGold else trendColor,
                                radius = if (isSelected) 4.5f else 2.5f,
                                center = Offset(x, y),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SpatialFieldHeatmapCard(
    heatmap: SpatialFieldHeatmap?,
    mode: StrategyHeatmapMode,
    onModeChange: (StrategyHeatmapMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, AresBorder),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "SPATIAL FIELD HEATMAP & TRAJECTORY (${heatmap?.league?.name ?: "FIELD"})",
                        style = MaterialTheme.typography.labelMedium,
                        color = AresCyan,
                        fontWeight = FontWeight.Bold,
                    )
                    if (heatmap != null && heatmap.poseSampleCount > 0) {
                        Text(
                            text = String.format(
                                Locale.US,
                                "%d pose samples · %.1f m traveled · %.2f m/s peak · %.2f V max sag",
                                heatmap.poseSampleCount,
                                heatmap.totalDistanceMeters,
                                heatmap.peakSpeedMps,
                                heatmap.maxSagVolts,
                            ),
                            fontSize = 11.sp,
                            color = AresTextSecondary,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StrategyHeatmapMode.entries.forEach { candidate ->
                        val selected = candidate == mode
                        OutlinedButton(
                            onClick = { onModeChange(candidate) },
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, if (selected) AresCyan else AresBorder),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) AresCyan.copy(alpha = 0.14f) else Color.Transparent,
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(28.dp),
                        ) {
                            Text(
                                text = candidate.label,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (selected) AresCyan else AresTextSecondary,
                            )
                        }
                    }
                }
            }

            if (heatmap == null || heatmap.poseSampleCount == 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .border(1.dp, AresBorder, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No field pose telemetry (ARES/EstimatedPose or Drive/Pose_*) recorded in this session.",
                        color = AresTextSecondary,
                        fontSize = 12.sp,
                    )
                }
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF0A0F18), RoundedCornerShape(8.dp))
                        .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
                ) {
                    val w = size.width
                    val h = size.height
                    val cellW = w / heatmap.cols
                    val cellH = h / heatmap.rows
                    val maxMetric = heatmap.maxCellMetric(mode)

                    // Alliance side tint hints
                    drawRect(Color(0xFF2979FF).copy(alpha = 0.06f), Offset(0f, 0f), Size(w * 0.5f, h))
                    drawRect(Color(0xFFFF5252).copy(alpha = 0.06f), Offset(w * 0.5f, 0f), Size(w * 0.5f, h))

                    // Heatmap cells (row 0 is minY at bottom, row rows-1 is maxY at top)
                    heatmap.cells.forEach { cell ->
                        val intensity = (cell.metricFor(mode) / maxMetric).coerceIn(0.0, 1.0).toFloat()
                        if (intensity > 0.01f) {
                            val fill = heatmapColorFor(mode, intensity)
                            val x = cell.col * cellW
                            val y = (heatmap.rows - 1 - cell.row) * cellH
                            drawRect(
                                color = fill,
                                topLeft = Offset(x + 1f, y + 1f),
                                size = Size((cellW - 2f).coerceAtLeast(1f), (cellH - 2f).coerceAtLeast(1f)),
                            )
                        }
                    }

                    // Grid lines
                    for (i in 1 until heatmap.cols) {
                        drawLine(AresBorder.copy(alpha = 0.35f), Offset(i * cellW, 0f), Offset(i * cellW, h), 1f)
                    }
                    for (j in 1 until heatmap.rows) {
                        drawLine(AresBorder.copy(alpha = 0.35f), Offset(0f, j * cellH), Offset(w, j * cellH), 1f)
                    }
                    // Center field divider
                    drawLine(AresTextSecondary.copy(alpha = 0.35f), Offset(w * 0.5f, 0f), Offset(w * 0.5f, h), 1.5f)

                    // Trajectory polyline
                    val bounds = heatmap.bounds
                    fun toCanvas(pt: StrategyPosePoint): Offset {
                        val nx = ((pt.x - bounds.minX) / bounds.widthMeters).coerceIn(0.0, 1.0).toFloat()
                        val ny = (1.0 - ((pt.y - bounds.minY) / bounds.heightMeters).coerceIn(0.0, 1.0)).toFloat()
                        return Offset(nx * w, ny * h)
                    }

                    if (heatmap.trajectory.size >= 2) {
                        val path = Path()
                        heatmap.trajectory.forEachIndexed { idx, pt ->
                            val pos = toCanvas(pt)
                            if (idx == 0) path.moveTo(pos.x, pos.y) else path.lineTo(pos.x, pos.y)
                        }
                        drawPath(
                            path = path,
                            color = Color.White.copy(alpha = 0.65f),
                            style = Stroke(width = 2f, cap = StrokeCap.Round),
                        )
                    }
                    heatmap.trajectory.firstOrNull()?.let { start ->
                        drawCircle(AresGreen, radius = 5.5f, center = toCanvas(start))
                    }
                    heatmap.trajectory.lastOrNull()?.let { end ->
                        drawCircle(AresGold, radius = 5.5f, center = toCanvas(end))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "X: [%.2f m .. %.2f m] · Y: [%.2f m .. %.2f m] · ● Start (Green) · ● End (Gold)",
                            heatmap.bounds.minX,
                            heatmap.bounds.maxX,
                            heatmap.bounds.minY,
                            heatmap.bounds.maxY,
                        ),
                        fontSize = 10.sp,
                        color = AresTextSecondary,
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "Peak cell: %.2f %s",
                            heatmap.maxCellMetric(mode),
                            mode.unitLabel,
                        ),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AresCyan,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CoachingInsightCardItem(
    insight: MatchCoachingInsight,
    sessionStartMs: Long,
    onJumpToReplay: (Long) -> Unit,
) {
    val accent = when (insight.severity) {
        CoachingSeverity.CRITICAL -> AresError
        CoachingSeverity.WARNING -> AresGold
        CoachingSeverity.TIP -> AresCyan
        CoachingSeverity.EXCELLENT -> AresGreen
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.7f)),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = insight.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = AresTextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = AresSurface, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, AresBorder)) {
                        Text(
                            text = insight.metricBadge,
                            color = AresTextPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                    Surface(color = accent.copy(alpha = 0.18f), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            text = insight.severity.name,
                            color = accent,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Text(insight.detail, style = MaterialTheme.typography.bodySmall, color = AresTextSecondary, fontSize = 11.sp)
            if (insight.jumpTimestampMs != null) {
                val relSec = ((insight.jumpTimestampMs - sessionStartMs).coerceAtLeast(0L)) / 1000.0
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(
                        onClick = { onJumpToReplay(insight.jumpTimestampMs) },
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.65f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = accent, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = String.format(Locale.US, "Jump to Replay (t = +%.2fs)", relSec),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                        )
                    }
                }
            }
        }
    }
}

private fun heatmapColorFor(mode: StrategyHeatmapMode, intensity: Float): Color {
    val alpha = (0.18f + 0.68f * intensity).coerceIn(0.18f, 0.86f)
    return when (mode) {
        StrategyHeatmapMode.OCCUPANCY -> if (intensity < 0.5f) {
            AresCyan.copy(alpha = alpha)
        } else {
            AresPurple.copy(alpha = alpha)
        }
        StrategyHeatmapMode.SPEED -> if (intensity < 0.55f) {
            AresCyan.copy(alpha = alpha)
        } else {
            AresGreen.copy(alpha = alpha)
        }
        StrategyHeatmapMode.VOLTAGE_SAG -> if (intensity < 0.5f) {
            AresGold.copy(alpha = alpha)
        } else {
            AresError.copy(alpha = alpha)
        }
    }
}

@Composable
internal fun TournamentScheduleContextCard(
    matches: List<MatchInfo>,
    teamId: String,
    sessions: List<Session>,
    selectedSession: Session?,
    onOpenPitOps: (() -> Unit)?,
) {
    if (matches.isEmpty()) return
    val rows = EventScheduleCodec.buildQueueRows(
        items = matches.map { it.toScheduledMatchItem("cache") },
        teamId = teamId,
        sessions = sessions,
    )
    val selectedRow = selectedSession?.matchNumber?.let { num ->
        rows.firstOrNull { it.item.matchNumber == num }
    }
    val nextRow = rows.firstOrNull { it.isNextUnplayedForTeam } ?: rows.firstOrNull()
    val displayRow = selectedRow ?: nextRow ?: return
    val allianceColor = when (displayRow.ourAlliance) {
        "red" -> AresRed
        "blue" -> AresCyan
        else -> AresGold
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, allianceColor.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = allianceColor, modifier = Modifier.size(18.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val headerLabel = if (selectedRow != null) {
                        "Scheduled Match Context · ${displayRow.item.compLevel.uppercase(Locale.US)} #${displayRow.item.matchNumber}"
                    } else {
                        "Next Queued Match · ${displayRow.item.compLevel.uppercase(Locale.US)} #${displayRow.item.matchNumber}"
                    }
                    Text(headerLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AresTextPrimary)
                    val partnerText = if (displayRow.partnerTeams.isEmpty()) "Solo/Unlisted" else displayRow.partnerTeams.joinToString(", ")
                    val oppText = if (displayRow.opponentTeams.isEmpty()) "Unlisted" else displayRow.opponentTeams.joinToString(", ")
                    val rolePrefix = displayRow.ourAlliance?.uppercase(Locale.US)?.let { "$it ALLIANCE · " } ?: "Teams · "
                    Text(
                        "${rolePrefix}Partner: $partnerText | Opponents: $oppText",
                        fontSize = 11.sp,
                        color = AresTextSecondary,
                    )
                }
            }
            if (onOpenPitOps != null) {
                OutlinedButton(
                    onClick = onOpenPitOps,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.65f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("Pit Match Queue", fontSize = 11.sp, color = AresCyan, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
