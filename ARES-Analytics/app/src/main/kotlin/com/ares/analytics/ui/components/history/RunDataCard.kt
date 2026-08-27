package com.ares.analytics.ui.components.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ares.analytics.shared.Session
import com.ares.analytics.shared.SessionSummary
import com.ares.analytics.ui.screens.RowDefinition
import com.ares.analytics.ui.theme.*
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
fun RunDataCard(
    rowDefinition: RowDefinition,
    sessions: List<Session>,
    summaries: Map<String, SessionSummary>,
    diagnosticsMap: Map<String, Map<String, Double>>,
    dateFormatter: DateTimeFormatter,
    onDismiss: () -> Unit
) {
    val dataPoints = remember(rowDefinition, sessions, summaries, diagnosticsMap) {
        sessions.mapIndexedNotNull { idx, s ->
            val sum = summaries[s.sessionId]
            val diag = diagnosticsMap[s.sessionId] ?: emptyMap()
            val valNum = rowDefinition.getNumericValue(s, sum, diag)
            if (valNum != null) {
                val label = s.matchNumber?.let { "M#$it" } ?: "Run ${idx + 1}"
                val detail = dateFormatter.format(Instant.ofEpochMilli(s.createdAt))
                Triple(label, valNum, detail)
            } else null
        }
    }
    val textMeasurer = rememberTextMeasurer()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(480.dp).border(1.dp, AresBorder, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = AresSurfaceElevated
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(rowDefinition.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AresTextPrimary)
                        Text("Historical trend across runs", fontSize = 12.sp, color = AresTextSecondary)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = AresTextPrimary)
                    }
                }

                if (dataPoints.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("No numerical data points to plot for this row.", color = AresTextSecondary)
                    }
                } else {
                    // Line Graph View
                    Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(8.dp)) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val paddingLeft = 60f
                            val paddingBottom = 40f
                            val paddingTop = 20f
                            val paddingRight = 20f
                            val graphW = w - paddingLeft - paddingRight
                            val graphH = h - paddingTop - paddingBottom
                            val yValues = dataPoints.map { it.second }
                            val yMin = yValues.minOrNull() ?: 0.0
                            val yMax = yValues.maxOrNull() ?: 12.0
                            val yRange = if (yMax - yMin < 1e-4) 1.0 else yMax - yMin

                            // 1. Draw Grid axes and labels
                            drawLine(
                                color = AresBorder,
                                start = Offset(paddingLeft, paddingTop),
                                end = Offset(paddingLeft, h - paddingBottom),
                                strokeWidth = 2f
                            )
                            drawLine(
                                color = AresBorder,
                                start = Offset(paddingLeft, h - paddingBottom),
                                end = Offset(w - paddingRight, h - paddingBottom),
                                strokeWidth = 2f
                            )

                            // Draw Y-axis labels
                            val divisions = 4
                            val textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            val labelStyle = TextStyle(
                                color = Color.Gray,
                                fontSize = 9.sp
                            )

                            for (i in 0..divisions) {
                                val frac = i.toDouble() / divisions
                                val yVal = yMin + frac * yRange
                                val yPos = h - paddingBottom - (frac * graphH).toFloat()

                                drawLine(
                                    color = AresBorder.copy(alpha = 0.3f),
                                    start = Offset(paddingLeft, yPos),
                                    end = Offset(w - paddingRight, yPos),
                                    strokeWidth = 1f
                                )
                                drawText(
                                    textMeasurer = textMeasurer,
                                    text = String.format("%.2f", yVal),
                                    style = textStyle,
                                    topLeft = Offset(5f, yPos - 6f)
                                )
                            }

                            // Draw Line and Points
                            val points = dataPoints.mapIndexed { idx, item ->
                                val xFrac = if (dataPoints.size > 1) idx.toFloat() / (dataPoints.size - 1) else 0.5f
                                val yFrac = ((item.second - yMin) / yRange).toFloat()
                                val px = paddingLeft + xFrac * graphW
                                val py = h - paddingBottom - yFrac * graphH
                                Offset(px, py)
                            }

                            if (points.size > 1) {
                                val path = Path().apply {
                                    moveTo(points[0].x, points[0].y)
                                    for (i in 1 until points.size) {
                                        lineTo(points[i].x, points[i].y)
                                    }
                                }
                                drawPath(
                                    path = path,
                                    color = AresCyan,
                                    style = Stroke(width = 3.dp.toPx())
                                )
                            }

                            // Draw points
                            points.forEachIndexed { idx, pt ->
                                val isAnomaly = rowDefinition.isAnomaly(dataPoints[idx].second)
                                drawCircle(
                                    color = if (isAnomaly) AresError else AresCyan,
                                    radius = 6.dp.toPx(),
                                    center = pt
                                )
                                drawCircle(
                                    color = AresSurfaceElevated,
                                    radius = 3.dp.toPx(),
                                    center = pt
                                )

                                // Label underneath point on x-axis
                                drawText(
                                    textMeasurer = textMeasurer,
                                    text = dataPoints[idx].first,
                                    style = labelStyle,
                                    topLeft = Offset(pt.x - 14f, h - 25f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
