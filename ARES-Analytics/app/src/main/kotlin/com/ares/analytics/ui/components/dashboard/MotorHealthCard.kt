package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.shared.TelemetryFrame
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.launch
import com.ares.analytics.ui.components.core.*

@Composable
fun MotorHealthCard(
    databaseService: DatabaseService,
    sessionId: String?,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var currentFrames by remember { mutableStateOf<List<TelemetryFrame>>(emptyList()) }

    LaunchedEffect(sessionId) {
        if (sessionId != null) {
            while (isActive) {
                currentFrames = databaseService.getTelemetryForKeyPatterns(
                    sessionId,
                    listOf("Hardware/Motors/%/Current%")
                )
                if (sessionId != "live-telemetry") break
                delay(1000)
            }
        } else {
            currentFrames = emptyList()
        }
    }

    AnalyticsCard(
        modifier = modifier
    ) {
        CardHeader(
            title = "Motor Current Draw",
            icon = Icons.Default.ElectricBolt,
            iconTint = AresGold
        )

        if (currentFrames.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Select a session with current telemetry to view motor logs.", color = AresTextTertiary, fontSize = 12.sp)
            }
        } else {
            val grouped = currentFrames.groupBy { it.key }

            Column(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Render top 2 motor current charts
                grouped.entries.take(2).forEach { (motorKey, frames) ->
                    val motorName = motorKey.split("/").lastOrNull()?.replace("current", "", ignoreCase = true) ?: "Motor"
                    Text(motorName, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AresTextSecondary)

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        MotorCurrentChart(frames = frames.sortedBy { it.timestampMs })
                    }
                }
            }
        }
    }
}

@Composable
private fun MotorCurrentChart(frames: List<TelemetryFrame>) {
    if (frames.size < 2) return
    val minTime = frames.first().timestampMs.toDouble()
    val maxTime = frames.last().timestampMs.toDouble()
    val timeRange = (maxTime - minTime).coerceAtLeast(1.0)
    val maxCurrent = (frames.maxOf { it.value }).coerceAtLeast(10.0)

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .border(1.dp, AresBorder, RoundedCornerShape(6.dp))
            .background(AresBackground)
    ) {
        val w = size.width
        val h = size.height

        // Draw horizontal grid lines
        val linesCount = 4
        for (i in 1 until linesCount) {
            val y = h * (i.toFloat() / linesCount)
            drawLine(color = AresBorder, start = Offset(0f, y), end = Offset(w, y), strokeWidth = 1f)
        }
        val splinePath = Path()
        val fillPath = Path()
        val firstFrame = frames.first()
        val firstX = ((firstFrame.timestampMs - minTime) / timeRange * w).toFloat()
        val firstY = (h - (firstFrame.value / maxCurrent * h)).toFloat().coerceIn(0f, h)

        splinePath.moveTo(firstX, firstY)
        fillPath.moveTo(firstX, h) // start at bottom left of fill path
        fillPath.lineTo(firstX, firstY)

        for (i in 1 until frames.size) {
            val f = frames[i]
            val x = ((f.timestampMs - minTime) / timeRange * w).toFloat()
            val y = (h - (f.value / maxCurrent * h)).toFloat().coerceIn(0f, h)
            splinePath.lineTo(x, y)
            fillPath.lineTo(x, y)
        }

        fillPath.lineTo(w, h) // complete fill shape back to bottom right
        fillPath.close()

        // 1. Draw Faded Gradient Fill under the line
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(AresGold.copy(alpha = 0.25f), Color.Transparent),
                startY = 0f,
                endY = h
            )
        )

        // 2. Draw Current Line
        drawPath(
            path = splinePath,
            color = AresGold,
            style = Stroke(width = 2.5f)
        )
    }
}
