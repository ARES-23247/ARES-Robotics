package com.ares.analytics.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws a directional vector arrow from [start] to [end] with a customizable arrowhead.
 *
 * @param start The origin of the arrow.
 * @param end The target tip of the arrow.
 * @param color Stroke and fill color.
 * @param strokeWidth Width of the shaft and arrowhead stroke.
 * @param arrowHeadLength Length of the arrowhead wings in pixels.
 * @param arrowHeadAngleRad Half-angle of the arrowhead opening in radians (default ~30 degrees).
 * @param filledHead Whether to render a solid filled triangular arrowhead or an open stroked arrowhead.
 */
fun DrawScope.drawVectorArrow(
    start: Offset,
    end: Offset,
    color: Color,
    strokeWidth: Float = 2f,
    arrowHeadLength: Float = 10f,
    arrowHeadAngleRad: Double = Math.PI / 6.0,
    filledHead: Boolean = false
) {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSq = dx * dx + dy * dy
    if (lengthSq < 1e-4f) return

    // Draw main arrow shaft
    drawLine(
        color = color,
        start = start,
        end = end,
        strokeWidth = strokeWidth
    )

    val angle = atan2(dy.toDouble(), dx.toDouble())
    val wing1Angle = angle - Math.PI + arrowHeadAngleRad
    val wing2Angle = angle - Math.PI - arrowHeadAngleRad

    val wing1 = Offset(
        (end.x + arrowHeadLength * cos(wing1Angle)).toFloat(),
        (end.y + arrowHeadLength * sin(wing1Angle)).toFloat()
    )
    val wing2 = Offset(
        (end.x + arrowHeadLength * cos(wing2Angle)).toFloat(),
        (end.y + arrowHeadLength * sin(wing2Angle)).toFloat()
    )

    if (filledHead) {
        val path = Path().apply {
            moveTo(end.x, end.y)
            lineTo(wing1.x, wing1.y)
            lineTo(wing2.x, wing2.y)
            close()
        }
        drawPath(path = path, color = color, style = Fill)
    } else {
        drawLine(color = color, start = end, end = wing1, strokeWidth = strokeWidth)
        drawLine(color = color, start = end, end = wing2, strokeWidth = strokeWidth)
    }
}
