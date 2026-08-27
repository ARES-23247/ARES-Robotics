package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
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
fun VisionQualityCard(
    databaseService: DatabaseService,
    sessionId: String?,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var visionFrames by remember { mutableStateOf<List<TelemetryFrame>>(emptyList()) }

    LaunchedEffect(sessionId) {
        if (sessionId != null) {
            scope.launch {
                visionFrames = databaseService.getTelemetryForKeyPatterns(
                    sessionId,
                    listOf("%Vision%Innovation%")
                )
            }
        } else {
            visionFrames = emptyList()
        }
    }

    AnalyticsCard(
        modifier = modifier
    ) {
        CardHeader(
            title = "Vision EKF Innovation",
            icon = Icons.Default.Visibility,
            iconTint = AresCyan
        )

        if (visionFrames.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Select a session with EKF innovation logs to view vision tracking quality.", color = AresTextTertiary, fontSize = 12.sp)
            }
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                VisionInnovationChart(frames = visionFrames.sortedBy { it.timestampMs })
            }
        }
    }
}

@Composable
private fun VisionInnovationChart(frames: List<TelemetryFrame>) {
    if (frames.size < 2) return
    val minTime = frames.first().timestampMs.toDouble()
    val maxTime = frames.last().timestampMs.toDouble()
    val timeRange = (maxTime - minTime).coerceAtLeast(1.0)
    val maxInnovation = (frames.maxOf { it.value }).coerceAtLeast(0.2) // minimum 20cm limit

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .border(1.dp, AresBorder, RoundedCornerShape(6.dp))
            .background(AresBackground)
    ) {
        val w = size.width
        val h = size.height

        // Draw horizontal grid lines (showing center 0 line, and ± innovation thresholds)
        val center = h / 2f
        drawLine(color = AresBorder, start = Offset(0f, center), end = Offset(w, center), strokeWidth = 1.5f)
        val threshYTop = center - (0.05 / maxInnovation * (h / 2f)).toFloat()
        val threshYBottom = center + (0.05 / maxInnovation * (h / 2f)).toFloat()

        // Threshold line at 5cm (dashed warning line)
        drawLine(
            color = AresAmber.copy(alpha = 0.5f),
            start = Offset(0f, threshYTop),
            end = Offset(w, threshYTop),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
        )
        drawLine(
            color = AresAmber.copy(alpha = 0.5f),
            start = Offset(0f, threshYBottom),
            end = Offset(w, threshYBottom),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
        )
        val linePath = Path()
        val firstFrame = frames.first()
        val firstX = ((firstFrame.timestampMs - minTime) / timeRange * w).toFloat()
        val firstY = (center - (firstFrame.value / maxInnovation * (h / 2f))).toFloat().coerceIn(0f, h)

        linePath.moveTo(firstX, firstY)

        for (i in 1 until frames.size) {
            val f = frames[i]
            val x = ((f.timestampMs - minTime) / timeRange * w).toFloat()
            val y = (center - (f.value / maxInnovation * (h / 2f))).toFloat().coerceIn(0f, h)
            linePath.lineTo(x, y)
        }

        drawPath(
            path = linePath,
            color = AresCyan,
            style = Stroke(width = 2f)
        )
    }
}
