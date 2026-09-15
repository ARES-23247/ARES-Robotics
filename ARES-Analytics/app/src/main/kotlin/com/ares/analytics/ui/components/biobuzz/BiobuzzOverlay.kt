package com.ares.analytics.ui.components.biobuzz

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.components.pathplanner.*
import com.ares.analytics.shared.models.League
import org.ares.biobuzz.*
import kotlin.math.cos

/** Presentation only. The existing FTC simulator owns all motion and game rules. */
internal fun DrawScope.drawBiobuzz(state: BiobuzzSnapshot, textMeasurer: TextMeasurer,
    w: Float, h: Float, fw: Double, fh: Double) {
    fun at(x: Double, y: Double) = getCanvasOffsetBase(Waypoint(x, y), w, h, fw, fh, League.FTC)
    for (hive in state.hives) drawHive(hive, w / fw, ::at)
    for ((i, flower) in state.flowers.withIndex()) {
        val p = at(flower.x, flower.y)
        drawCircle(AresGold, (0.055 * w / fw).toFloat(), p, style = Stroke(2f))
        flower.contents.lastOrNull()?.let { drawCircle(Color(0xFF000000L or it.color.toLong()), (it.diameter / 2 * w / fw).toFloat(), p) }
        val pollen = flower.contents.count { it == BallKind.POLLEN }
        val nectar = flower.contents.size - pollen
        val label = "F${i + 1} · ${pollen}P / ${nectar}N"
        val labelAt = p + Offset(if (p.x > w / 2) -100f else 15f, if (p.y > h / 2) -45f else 12f)
        drawText(textMeasurer, label, labelAt, style = TextStyle(color = Color.White, fontSize = 10.sp))
        // Left to right is bottom to top, so a blocker remains visible below pollen.
        for ((index, kind) in flower.contents.withIndex()) {
            val center = labelAt + Offset(5f + index * 11f, 22f)
            val radius = if (kind == BallKind.POLLEN) 3.5f else 4.5f
            drawCircle(Color(0xFF000000L or kind.color.toLong()), radius, center)
            if (kind != BallKind.POLLEN) drawCircle(Color.White, radius, center, style = Stroke(1f))
        }
    }
    for (ball in state.balls) if (ball.location == BallLocation.AIR) {
        val p = at(ball.x, ball.y)
        drawCircle(Color.White.copy(alpha = 0.5f), (ball.kind.diameter * w / fw).toFloat(), p, style = Stroke(1f))
        drawText(textMeasurer, "%.1fm".format(ball.z), p + Offset(9f, -16f), style = TextStyle(color = Color.White, fontSize = 9.sp))
    }

}

private fun DrawScope.drawHive(hive: HiveView, pixelsPerMeter: Double, at: (Double, Double) -> Offset) {
    val color = if (hive.red) AresRed else AresCyan
    val left = at(hive.x - BiobuzzField.CELL_OFFSET * cos(hive.angle), hive.y)
    val right = at(hive.x + BiobuzzField.CELL_OFFSET * cos(hive.angle), hive.y)
    drawLine(AresTextSecondary, left, right, 4f)
    for (cell in 0..1) {
        val sign = if (cell == 0) -1 else 1
        val x = hive.x + sign * BiobuzzField.CELL_OFFSET * cos(hive.angle)
        val halfX = BiobuzzField.CELL_DEPTH / 2
        val halfY = BiobuzzField.CELL_WIDTH / 2
        val p = Path().apply {
            val a = at(x - halfX, hive.y - halfY); moveTo(a.x, a.y)
            for ((xx, yy) in listOf(x - halfX to hive.y + halfY, x + halfX to hive.y + halfY, x + halfX to hive.y - halfY)) {
                val b = at(xx, yy); lineTo(b.x, b.y)
            }
            close()
        }
        drawPath(p, color.copy(alpha = if (cell == hive.upward) 0.55f else 0.16f))
        drawPath(p, color, style = Stroke(if (cell == hive.upward) 3f else 1f))
        if (cell == hive.upward) for ((i, kind) in hive.contents.withIndex()) {
            val center = at(x + (i / 5 - 1) * 0.08, hive.y + (i % 5 - 2) * 0.085)
            drawCircle(Color(0xFF000000L or kind.color.toLong()), (kind.diameter / 2 * pixelsPerMeter).toFloat(), center)
        }
    }
}
