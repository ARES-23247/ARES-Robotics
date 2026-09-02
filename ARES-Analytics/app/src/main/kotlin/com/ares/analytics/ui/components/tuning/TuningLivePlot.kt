package com.ares.analytics.ui.components.tuning

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.ares.analytics.service.AlignedDataRow
import com.ares.analytics.ui.theme.*

/**
 * Real-time canvas waveform plot rendering velocity response samples across time.
 */
@Composable
fun TuningLivePlot(
    samples: List<AlignedDataRow>,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(130.dp)
            .background(AresSurfaceElevated, RoundedCornerShape(8.dp))
            .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
    ) {
        if (samples.size < 2) return@Canvas
        val maxTime = samples.maxOf { it.timestampMs }
        val minTime = samples.minOf { it.timestampMs }
        val dt = (maxTime - minTime).toDouble()
        val minVelocity = minOf(0.0, samples.minOf { it.velocity })
        val maxVelocity = maxOf(0.0, samples.maxOf { it.velocity })
        val velocityRange = (maxVelocity - minVelocity).coerceAtLeast(1.0)
        val path = Path()
        val zeroY = (size.height - ((0.0 - minVelocity) / velocityRange) * size.height).toFloat()

        drawLine(AresBorder, Offset(0f, zeroY), Offset(size.width, zeroY), 1.dp.toPx())

        samples.forEachIndexed { index, sample ->
            val x = if (dt > 0) ((sample.timestampMs - minTime) / dt * size.width).toFloat() else 0f
            val normalizedVelocity = (sample.velocity - minVelocity) / velocityRange
            val y = (size.height - normalizedVelocity * size.height).toFloat()

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        drawPath(
            path = path,
            color = AresCyan,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}
