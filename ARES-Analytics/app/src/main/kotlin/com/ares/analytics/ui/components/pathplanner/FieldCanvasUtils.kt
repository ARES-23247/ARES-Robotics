package com.ares.analytics.ui.components.pathplanner

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.theme.*
import com.areslib.math.wrapAngle

enum class EditorMode {
    SELECT, ADD_WAYPOINT, DRAW_POLYGON, DRAW_CIRCLE, DRAW_RECTANGLE, PLACE_GAME_PIECE, PLACE_APRILTAG, PLACE_FIELD_WAYPOINT, ERASER
}

// Precomputed Cache Data Structures for zero-allocation rendering performance

class PathCacheHolder {
    var splinePoints: List<Waypoint> = emptyList()
    var actualPoints: List<Waypoint> = emptyList()
    var w: Float = 0f
    var h: Float = 0f
    var splinePath: Path? = null
    var actualPath: Path? = null
    var actualLastDrawnIndex: Int = -1
    val reusableArrowPath = Path()
    val reusableXAxisPath = Path()
    val reusableYAxisPath = Path()
    val reusablePath = Path()
    val dashEffect10 = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
    val dashEffect5 = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
    val dashEffect4 = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
}

// Convert coordinates base models

fun getCanvasOffsetBase(
    wp: Waypoint,
    canvasW: Float,
    canvasH: Float,
    fieldW: Double,
    fieldH: Double,
    league: League
): Offset {
    return if (league == League.FTC || league == League.XRP) {
        if (league == League.XRP) {
            return Offset(
                ((wp.x / fieldW + 0.5) * canvasW).toFloat(),
                ((0.5 - wp.y / fieldH) * canvasH).toFloat(),
            )
        }
        val x = (-wp.y / fieldW + 0.5) * canvasW
        val y = (-wp.x / fieldH + 0.5) * canvasH
        Offset(x.toFloat(), y.toFloat())
    } else {
        val x = (wp.x / fieldW) * canvasW
        val y = ((fieldH - wp.y) / fieldH) * canvasH
        Offset(x.toFloat(), y.toFloat())
    }
}

fun getRobotCoordBase(
    offset: Offset,
    canvasW: Float,
    canvasH: Float,
    fieldW: Double,
    fieldH: Double,
    league: League
): Waypoint {
    return if (league == League.FTC || league == League.XRP) {
        if (league == League.XRP) {
            return Waypoint(
                (offset.x / canvasW - 0.5) * fieldW,
                (0.5 - offset.y / canvasH) * fieldH,
            )
        }
        val ry = -(offset.x / canvasW - 0.5) * fieldW
        val rx = -(offset.y / canvasH - 0.5) * fieldH
        Waypoint(rx, ry)
    } else {
        val rx = (offset.x / canvasW) * fieldW
        val ry = fieldH - (offset.y / canvasH) * fieldH
        Waypoint(rx, ry)
    }
}

fun getTransformedCanvasOffset(
    wp: Waypoint,
    w: Float,
    h: Float,
    fieldWidthM: Double,
    fieldHeightM: Double,
    league: League,
    zoomScale: Float,
    panOffset: Offset,
    viewRotationDeg: Float = 0f
): Offset {
    val base = getCanvasOffsetBase(wp, w, h, fieldWidthM, fieldHeightM, league)
    // Apply zoom scale and pan offset
    var x = base.x * zoomScale + panOffset.x
    var y = base.y * zoomScale + panOffset.y
    // Apply view rotation around canvas center (matching the draw transform)
    if (viewRotationDeg != 0f) {
        val cx = w / 2f; val cy = h / 2f
        val rad = Math.toRadians(viewRotationDeg.toDouble())
        val cosR = kotlin.math.cos(rad).toFloat()
        val sinR = kotlin.math.sin(rad).toFloat()
        val dx = x - cx; val dy = y - cy
        x = cx + dx * cosR - dy * sinR
        y = cy + dx * sinR + dy * cosR
    }
    return Offset(x, y)
}

fun getRobotCoordFromScreen(
    screenOffset: Offset,
    w: Float,
    h: Float,
    fieldWidthM: Double,
    fieldHeightM: Double,
    league: League,
    zoomScale: Float,
    panOffset: Offset,
    viewRotationDeg: Float = 0f
): Waypoint {
    var sx = screenOffset.x; var sy = screenOffset.y
    // Reverse view rotation around canvas center
    if (viewRotationDeg != 0f) {
        val cx = w / 2f; val cy = h / 2f
        val rad = Math.toRadians(-viewRotationDeg.toDouble()) // negative to reverse
        val cosR = kotlin.math.cos(rad).toFloat()
        val sinR = kotlin.math.sin(rad).toFloat()
        val dx = sx - cx; val dy = sy - cy
        sx = cx + dx * cosR - dy * sinR
        sy = cy + dx * sinR + dy * cosR
    }
    // Reverse pan & zoom
    val baseOffset = Offset(
        x = (sx - panOffset.x) / zoomScale,
        y = (sy - panOffset.y) / zoomScale
    )
    return getRobotCoordBase(baseOffset, w, h, fieldWidthM, fieldHeightM, league)
}

/**
 * Reverse-transforms screen pointer coordinates to base (untransformed) canvas coordinates.
 * This allows hit-testing using getCanvasOffsetBase positions directly.
 */
fun getBaseCanvasFromScreen(
    screenOffset: Offset,
    w: Float,
    h: Float,
    zoomScale: Float,
    panOffset: Offset,
    viewRotationDeg: Float = 0f
): Offset {
    var sx = screenOffset.x; var sy = screenOffset.y
    if (viewRotationDeg != 0f) {
        val cx = w / 2f; val cy = h / 2f
        val rad = Math.toRadians(-viewRotationDeg.toDouble())
        val cosR = kotlin.math.cos(rad).toFloat()
        val sinR = kotlin.math.sin(rad).toFloat()
        val dx = sx - cx; val dy = sy - cy
        sx = cx + dx * cosR - dy * sinR
        sy = cy + dx * sinR + dy * cosR
    }
    return Offset(
        x = (sx - panOffset.x) / zoomScale,
        y = (sy - panOffset.y) / zoomScale
    )
}

/**
 * Converts a pixel-space drag delta directly to a field-coordinate delta.
 * Uses the `dragAmount` vector from Compose's `detectDragGestures` to avoid
 * absolute coordinate conversion issues (DPI scaling, rotate modifier, etc.).
 *
 * For FTC:
 *   canvasX = (-fieldY / fieldW + 0.5) * canvasW  ⇒  deltaFieldY = -deltaCanvasX / canvasW * fieldW
 *   canvasY = (-fieldX / fieldH + 0.5) * canvasH  ⇒  deltaFieldX = -deltaCanvasY / canvasH * fieldH
 */
fun getDragDeltaInFieldCoords(
    dragAmount: Offset,
    canvasW: Float,
    canvasH: Float,
    fieldW: Double,
    fieldH: Double,
    league: League,
    zoomScale: Float,
    viewRotationDeg: Float = 0f
): Waypoint {
    var dxPx = dragAmount.x
    var dyPx = dragAmount.y
    if (viewRotationDeg != 0f) {
        val rad = Math.toRadians(-viewRotationDeg.toDouble())
        val cosR = kotlin.math.cos(rad).toFloat()
        val sinR = kotlin.math.sin(rad).toFloat()
        val rotatedX = dxPx * cosR - dyPx * sinR
        val rotatedY = dxPx * sinR + dyPx * cosR
        dxPx = rotatedX
        dyPx = rotatedY
    }

    return if (league == League.FTC || league == League.XRP) {
        if (league == League.XRP) {
            return Waypoint(
                (dxPx / canvasW) * fieldW / zoomScale,
                -(dyPx / canvasH) * fieldH / zoomScale,
            )
        }
        val dx = -(dyPx / canvasH) * fieldH / zoomScale
        val dy = -(dxPx / canvasW) * fieldW / zoomScale
        Waypoint(dx, dy)
    } else {
        val dx = (dxPx / canvasW) * fieldW / zoomScale
        val dy = -(dyPx / canvasH) * fieldH / zoomScale
        Waypoint(dx, dy)
    }
}

fun cubicHermite(p0: Double, v0: Double, p1: Double, v1: Double, t: Double): Double {
    val t2 = t * t
    val t3 = t2 * t
    val h00 = 2 * t3 - 3 * t2 + 1
    val h10 = t3 - 2 * t2 + t
    val h01 = -2 * t3 + 3 * t2
    val h11 = t3 - t2
    return h00 * p0 + h10 * v0 + h01 * p1 + h11 * v1
}

private val scratchXArrowPath = Path()
private val scratchYArrowPath = Path()

fun DrawScope.drawCoordinateAxes(
    canvasW: Float,
    canvasH: Float,
    fieldW: Double,
    fieldH: Double,
    league: League,
    textMeasurer: TextMeasurer
) {
    val originOffset = getCanvasOffsetBase(Waypoint(0.0, 0.0), canvasW, canvasH, fieldW, fieldH, league)
    val xAxisOffset = getCanvasOffsetBase(Waypoint(0.8, 0.0), canvasW, canvasH, fieldW, fieldH, league)
    val yAxisOffset = getCanvasOffsetBase(Waypoint(0.0, 0.8), canvasW, canvasH, fieldW, fieldH, league)
    val strokeW = 3.dp.toPx()
    val arrowSize = 10.dp.toPx()

    // X Axis (Red)
    drawLine(color = AresRed, start = originOffset, end = xAxisOffset, strokeWidth = strokeW)
    // X Axis Arrow Head
    val xAngle = kotlin.math.atan2((xAxisOffset.y - originOffset.y).toDouble(), (xAxisOffset.x - originOffset.x).toDouble())
    val xArrowPath = scratchXArrowPath.apply {
        reset()
        moveTo(xAxisOffset.x, xAxisOffset.y)
        lineTo(
            xAxisOffset.x - arrowSize * kotlin.math.cos(xAngle - Math.PI / 6).toFloat(),
            xAxisOffset.y - arrowSize * kotlin.math.sin(xAngle - Math.PI / 6).toFloat()
        )
        lineTo(
            xAxisOffset.x - arrowSize * kotlin.math.cos(xAngle + Math.PI / 6).toFloat(),
            xAxisOffset.y - arrowSize * kotlin.math.sin(xAngle + Math.PI / 6).toFloat()
        )
        close()
    }
    drawPath(path = xArrowPath, color = AresRed)

    // Y Axis (Green)
    drawLine(color = AresGreen, start = originOffset, end = yAxisOffset, strokeWidth = strokeW)
    // Y Axis Arrow Head
    val yAngle = kotlin.math.atan2((yAxisOffset.y - originOffset.y).toDouble(), (yAxisOffset.x - originOffset.x).toDouble())
    val yArrowPath = scratchYArrowPath.apply {
        reset()
        moveTo(yAxisOffset.x, yAxisOffset.y)
        lineTo(
            yAxisOffset.x - arrowSize * kotlin.math.cos(yAngle - Math.PI / 6).toFloat(),
            yAxisOffset.y - arrowSize * kotlin.math.sin(yAngle - Math.PI / 6).toFloat()
        )
        lineTo(
            yAxisOffset.x - arrowSize * kotlin.math.cos(yAngle + Math.PI / 6).toFloat(),
            yAxisOffset.y - arrowSize * kotlin.math.sin(yAngle + Math.PI / 6).toFloat()
        )
        close()
    }
    drawPath(path = yArrowPath, color = AresGreen)

    // Axis Labels
    val textStyle = TextStyle(
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold
    )
    val textLayoutX = textMeasurer.measure("X", textStyle)
    val textLayoutY = textMeasurer.measure("Y", textStyle)

    drawText(
        textMeasurer = textMeasurer,
        text = "X",
        style = textStyle,
        topLeft = Offset(
            xAxisOffset.x + arrowSize * kotlin.math.cos(xAngle).toFloat(),
            xAxisOffset.y + arrowSize * kotlin.math.sin(xAngle).toFloat() - textLayoutX.size.height / 2f
        )
    )

    drawText(
        textMeasurer = textMeasurer,
        text = "Y",
        style = textStyle,
        topLeft = Offset(
            yAxisOffset.x + arrowSize * kotlin.math.cos(yAngle).toFloat() - textLayoutY.size.width / 2f,
            yAxisOffset.y + arrowSize * kotlin.math.sin(yAngle).toFloat()
        )
    )
}
