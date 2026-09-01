package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.flow.collect
import com.ares.analytics.ui.components.core.*

@Composable
fun EKFTelemetryCard(
    nt4ClientService: Nt4ClientService,
    modifier: Modifier = Modifier
) {

    // Ring buffers for chart data (last 200 points)
    val maxPoints = 200
    val driftXHistory = remember { mutableStateListOf<Double>() }
    val driftYHistory = remember { mutableStateListOf<Double>() }
    val covXHistory = remember { mutableStateListOf<Double>() }
    var currentCovX by remember { mutableStateOf(0.0) }
    var currentCovY by remember { mutableStateOf(0.0) }
    var currentCovTheta by remember { mutableStateOf(0.0) }

    LaunchedEffect(Unit) {
        nt4ClientService.uiTelemetryFlow.collect { frame ->
            val key = frame.key
                when {
                    key.endsWith("Drive/EKF_Drift_X") -> {
                        val value = frame.value
                        if (driftXHistory.size >= maxPoints) driftXHistory.removeAt(0)
                        driftXHistory.add(value)
                    }
                    key.endsWith("Drive/EKF_Drift_Y") -> {
                        val value = frame.value
                        if (driftYHistory.size >= maxPoints) driftYHistory.removeAt(0)
                        driftYHistory.add(value)
                    }
                    // The NT4 layer explodes the covariance array into indexed sub-topics
                    // (.../0, /1, /2), so read the scalars directly. The old single-topic
                    // DoubleArray branch could never match and silently dropped every update.
                    key.endsWith("Robot/Odometry/Covariance/0") -> {
                        currentCovX = frame.value
                        if (covXHistory.size >= maxPoints) covXHistory.removeAt(0)
                        covXHistory.add(currentCovX)
                    }
                    key.endsWith("Robot/Odometry/Covariance/1") -> currentCovY = frame.value
                    key.endsWith("Robot/Odometry/Covariance/2") -> currentCovTheta = frame.value
                }
            }
    }

    AresCard(
        modifier = modifier
            .fillMaxWidth()
            .height(300.dp),
        backgroundColor = AresSurface
    ) {
        CardHeader(
            title = "EKF Diagnostics",
            icon = Icons.AutoMirrored.Filled.ShowChart,
            iconTint = AresCyan,
            trailingContent = {
                Text(
                    "Cov(X): %.4f".format(currentCovX),
                    color = AresGold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        )

        Spacer(Modifier.height(16.dp))

            Box(modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AresBackground).border(1.dp, AresBorder, RoundedCornerShape(8.dp))) {
                Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    val w = size.width
                    val h = size.height

                    // Draw center zero-line
                    drawLine(color = AresBorder, start = Offset(0f, h / 2f), end = Offset(w, h / 2f), strokeWidth = 1f)

                    if (driftXHistory.isEmpty()) return@Canvas
                    val pointSpacing = w / (maxPoints - 1)
                    val maxAbsValue = 0.5f // Scale +/- 0.5 meters

                    fun drawLineChart(data: List<Double>, color: Color, stroke: Float = 2f) {
                        val path = Path()
                        data.forEachIndexed { index, value ->
                            val x = index * pointSpacing
                            // Map value to Y (-maxAbsValue -> h, maxAbsValue -> 0)
                            val normalizedY = (value.toFloat() / maxAbsValue).coerceIn(-1f, 1f)
                            val y = h / 2f - (normalizedY * (h / 2f))

                            if (index == 0) path.moveTo(x, y)
                            else path.lineTo(x, y)
                        }
                        drawPath(path, color, style = Stroke(width = stroke))
                    }

                    drawLineChart(driftXHistory, AresCyan, 2f)
                    drawLineChart(driftYHistory, AresError, 2f)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(AresCyan, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(4.dp))
                    Text("Drift X (m)", color = AresTextSecondary, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(AresError, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(4.dp))
                    Text("Drift Y (m)", color = AresTextSecondary, fontSize = 12.sp)
                }
            }
        }
    }

