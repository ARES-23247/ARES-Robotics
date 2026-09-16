package com.ares.analytics.ui.components.core

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.*

data class ChartSeries(
    val name: String,
    val points: List<Pair<Double, Double>>, // (X, Y)
    val color: Color,
    val strokeWidthDp: Float = 2f,
    val fillGradient: Boolean = false
)

/**
 * Standardized Canvas chart container for rendering time-series telemetry, control loops, and trends.
 */
@Composable
fun ChartContainer(
    seriesList: List<ChartSeries>,
    modifier: Modifier = Modifier,
    showGridLines: Boolean = true,
    showCenterZeroLine: Boolean = false,
    gridSteps: Int = 4,
    emptyMessage: String = "No telemetry data logged for this period."
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(6.dp))
            .background(AresBackground)
            .border(1.dp, AresBorder, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        val validSeries = seriesList.filter { it.points.size >= 2 }
        if (validSeries.isEmpty()) {
            Text(emptyMessage, color = AresTextTertiary, fontSize = 11.sp)
            return@Box
        }

        Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
            val w = size.width
            val h = size.height

            val allPoints = validSeries.flatMap { it.points }
            val minX = allPoints.minOf { it.first }
            val maxX = allPoints.maxOf { it.first }
            val rangeX = (maxX - minX).coerceAtLeast(1.0)

            var minY = allPoints.minOf { it.second }
            var maxY = allPoints.maxOf { it.second }
            if (minY == maxY) {
                minY -= 1.0
                maxY += 1.0
            }
            val rangeY = (maxY - minY).coerceAtLeast(1.0)

            // Draw horizontal grid lines
            if (showGridLines) {
                for (i in 1 until gridSteps) {
                    val y = h * (i.toFloat() / gridSteps)
                    drawLine(color = AresBorder, start = Offset(0f, y), end = Offset(w, y), strokeWidth = 1f)
                }
            }

            // Draw center zero line if requested
            if (showCenterZeroLine) {
                val zeroY = h - ((0.0 - minY) / rangeY * h).toFloat()
                if (zeroY in 0f..h) {
                    drawLine(color = AresBorder, start = Offset(0f, zeroY), end = Offset(w, zeroY), strokeWidth = 1.5f)
                }
            }

            // Plot each series
            validSeries.forEach { series ->
                val linePath = Path()
                val fillPath = Path()

                series.points.forEachIndexed { index, (xVal, yVal) ->
                    val px = ((xVal - minX) / rangeX * w).toFloat()
                    val py = (h - ((yVal - minY) / rangeY * h)).toFloat().coerceIn(0f, h)

                    if (index == 0) {
                        linePath.moveTo(px, py)
                        fillPath.moveTo(px, h)
                        fillPath.lineTo(px, py)
                    } else {
                        linePath.lineTo(px, py)
                        fillPath.lineTo(px, py)
                    }
                }

                if (series.fillGradient) {
                    fillPath.lineTo(w, h)
                    fillPath.close()
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(series.color.copy(alpha = 0.2f), Color.Transparent),
                            startY = 0f,
                            endY = h
                        )
                    )
                }

                drawPath(path = linePath, color = series.color, style = Stroke(width = series.strokeWidthDp.dp.toPx()))
            }
        }
    }
}
