package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.launch
import com.ares.analytics.ui.components.core.*

@Composable
fun TrendsCard(
    databaseService: DatabaseService,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var summaries by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    var selectedBatteryFilter by remember { mutableStateOf("All") }

    LaunchedEffect(Unit) {
        scope.launch {
            summaries = databaseService.getAllSessionSummaries().sortedBy { it.createdAt }
        }
    }
    val filteredSummaries = remember(summaries, selectedBatteryFilter) {
        if (selectedBatteryFilter == "All") {
            summaries
        } else {
            val targetTag = "battery-${selectedBatteryFilter.removePrefix("Battery ")}"
            summaries.filter { it.tags.contains(targetTag) }
        }
    }

    AresCard(
        modifier = modifier
    ) {
        CardHeader(
            title = "Battery Health Trend",
            icon = Icons.AutoMirrored.Filled.TrendingDown,
            iconTint = AresGold,
            showDivider = false
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf("All", "Battery A", "Battery B", "Battery C", "Battery D").forEach { label ->
                val isSelected = selectedBatteryFilter == label
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) AresCyan else AresSurfaceElevated)
                        .border(1.dp, if (isSelected) AresCyan else AresBorder, RoundedCornerShape(6.dp))
                        .clickable { selectedBatteryFilter = label }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        label,
                        color = if (isSelected) AresOnAccent else AresTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        HorizontalDivider(color = AresBorder, thickness = 1.dp)

        if (filteredSummaries.size < 2) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Need at least 2 sessions to compute battery trend lines.", color = AresTextTertiary, fontSize = 12.sp)
            }
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                BatteryTrendChart(summaries = filteredSummaries)
            }
        }
    }
}

@Composable
private fun BatteryTrendChart(summaries: List<SessionSummary>) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .border(1.dp, AresBorder, RoundedCornerShape(6.dp))
            .background(AresBackground)
    ) {
        val w = size.width
        val h = size.height
        val n = summaries.size
        val xValues = DoubleArray(n) { it.toDouble() }
        val yValues = DoubleArray(n) { summaries[it].minBatteryVoltage }
        val minY = 10.0 // bottom limit for battery voltages
        val maxY = 13.5 // upper limit

        // 1. Draw Points
        val points = mutableListOf<Offset>()
        for (i in 0 until n) {
            val cx = (i.toDouble() / (n - 1).toDouble() * w).toFloat()
            val cy = (h - ((yValues[i] - minY) / (maxY - minY) * h)).toFloat().coerceIn(0f, h)
            val offset = Offset(cx, cy)
            points.add(offset)
            val batteryTag = summaries[i].tags.firstOrNull { it.startsWith("battery-") } ?: "battery-A"
            val color = when (batteryTag.removePrefix("battery-").uppercase()) {
                "A" -> AresCyan
                "B" -> AresGold
                "C" -> AresRed
                "D" -> AresGreen
                else -> AresCyan
            }
            drawCircle(color = color, radius = 5.dp.toPx(), center = offset)
        }

        // 2. Compute Linear Regression (y = m * x + c)
        val xMean = xValues.average()
        val yMean = yValues.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            num += (xValues[i] - xMean) * (yValues[i] - yMean)
            den += (xValues[i] - xMean) * (xValues[i] - xMean)
        }
        val m = if (den != 0.0) num / den else 0.0
        val c = yMean - m * xMean

        // Draw trend line
        val startX = 0f
        val startYVal = m * 0.0 + c
        val startY = (h - ((startYVal - minY) / (maxY - minY) * h)).toFloat().coerceIn(0f, h)
        val endX = w
        val endYVal = m * (n - 1).toDouble() + c
        val endY = (h - ((endYVal - minY) / (maxY - minY) * h)).toFloat().coerceIn(0f, h)

        drawLine(
            color = AresGold,
            start = Offset(startX, startY),
            end = Offset(endX, endY),
            strokeWidth = 3f
        )
    }
}
