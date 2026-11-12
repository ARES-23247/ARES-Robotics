package com.ares.analytics.ui.components.pathplanner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresRed
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.areslib.math.wrapAngle
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.round

/**
 * Bounded waypoint undo/redo stack with optional drag-stream coalescing so continuous pointer drags
 * collapse into a single `Ctrl+Z` / `Ctrl+Y` checkpoint while discrete edits remain distinct.
 */
class WaypointHistoryStack(
    private val maxEntries: Int = 50,
) {
    private val undoStack = ArrayDeque<List<Waypoint>>()
    private val redoStack = ArrayDeque<List<Waypoint>>()
    private var lastTimestampMs: Long? = null
    private var lastRecordedResult: List<Waypoint>? = null
    private var revision by mutableIntStateOf(0)

    val canUndo: Boolean
        get() {
            revision
            return undoStack.isNotEmpty()
        }

    val canRedo: Boolean
        get() {
            revision
            return redoStack.isNotEmpty()
        }

    val undoDepth: Int
        get() {
            revision
            return undoStack.size
        }

    fun record(
        previous: List<Waypoint>,
        next: List<Waypoint>,
        timestampMs: Long? = null,
        coalesceWindowMs: Long = 280L,
    ) {
        if (previous == next) return
        val prevTs = lastTimestampMs
        val coalesced = timestampMs != null &&
            prevTs != null &&
            (timestampMs - prevTs) in 0L..coalesceWindowMs &&
            undoStack.isNotEmpty() &&
            redoStack.isEmpty() &&
            lastRecordedResult == previous

        if (!coalesced) {
            if (undoStack.size >= maxEntries) {
                undoStack.removeFirst()
            }
            undoStack.addLast(previous.map { it.copy() })
        }
        redoStack.clear()
        lastTimestampMs = timestampMs
        lastRecordedResult = next.map { it.copy() }
        revision++
    }

    fun undo(current: List<Waypoint>): List<Waypoint>? {
        if (undoStack.isEmpty()) return null
        val target = undoStack.removeLast()
        if (redoStack.size >= maxEntries) {
            redoStack.removeFirst()
        }
        redoStack.addLast(current.map { it.copy() })
        lastTimestampMs = null
        lastRecordedResult = null
        revision++
        return target.map { it.copy() }
    }

    fun redo(current: List<Waypoint>): List<Waypoint>? {
        if (redoStack.isEmpty()) return null
        val target = redoStack.removeLast()
        if (undoStack.size >= maxEntries) {
            undoStack.removeFirst()
        }
        undoStack.addLast(current.map { it.copy() })
        lastTimestampMs = null
        lastRecordedResult = null
        revision++
        return target.map { it.copy() }
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        lastTimestampMs = null
        lastRecordedResult = null
        revision++
    }
}

internal fun snapCoordinateMeters(meters: Double, stepMeters: Double = 0.05): Double {
    if (!meters.isFinite() || stepMeters <= 0.0) return meters
    val snapped = round(meters / stepMeters) * stepMeters
    return if (snapped == -0.0) 0.0 else snapped
}

internal fun snapHeadingRadians(radians: Double, stepDegrees: Double = 15.0): Double {
    if (!radians.isFinite() || stepDegrees <= 0.0) return radians
    val snappedDeg = round(Math.toDegrees(radians) / stepDegrees) * stepDegrees
    return wrapAngle(Math.toRadians(snappedDeg))
}

internal fun computePolylineLengthMeters(points: List<Waypoint>): Double {
    if (points.size < 2) return 0.0
    var sum = 0.0
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        val seg = hypot(b.x - a.x, b.y - a.y)
        if (seg.isFinite()) sum += seg
    }
    return sum
}

/**
 * Mirrors waypoints across the alliance symmetry line using CCW-positive radian conventions:
 * - `FRC` (Blue corner origin `[0, fieldWidthM] x [0, fieldHeightM]`): `x' = fieldWidthM - x`, `y' = y`, `θ' = wrapAngle(π - θ)`
 * - `FTC` / `XRP` (center origin): `x' = x`, `y' = -y`, `θ' = wrapAngle(-θ)`
 */
internal fun mirrorWaypointsAcrossAlliance(
    waypoints: List<Waypoint>,
    league: League,
    fieldWidthM: Double,
    @Suppress("UNUSED_PARAMETER") fieldHeightM: Double,
): List<Waypoint> {
    if (waypoints.isEmpty()) return emptyList()
    return waypoints.mapIndexed { index, wp ->
        val effectiveHeading = wp.headingRad
            ?: wp.rotationDeg?.let(Math::toRadians)
            ?: if (waypoints.size > 1) resolveHeading(waypoints, index) else null
        when (league) {
            League.FRC -> {
                val mirroredHeading = effectiveHeading?.let { wrapAngle(Math.PI - it) }
                wp.copy(
                    x = fieldWidthM - wp.x,
                    y = wp.y,
                    headingRad = mirroredHeading,
                    rotationDeg = mirroredHeading?.let(Math::toDegrees),
                )
            }
            League.FTC, League.XRP -> {
                val mirroredHeading = effectiveHeading?.let { wrapAngle(-it) }
                wp.copy(
                    x = wp.x,
                    y = -wp.y,
                    headingRad = mirroredHeading,
                    rotationDeg = mirroredHeading?.let(Math::toDegrees),
                )
            }
        }
    }
}

private val MirrorDashEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)

internal fun DrawScope.drawMirroredAlliancePreview(
    mirroredWaypoints: List<Waypoint>,
    mirroredPath: List<Waypoint>,
    robotDimensions: RobotDimensions,
    w: Float,
    h: Float,
    fieldWidthM: Double,
    fieldHeightM: Double,
    league: League,
) {
    val trajectory = mirroredPath.ifEmpty { mirroredWaypoints }
    if (trajectory.size >= 2) {
        val path = Path()
        trajectory.forEachIndexed { idx, pt ->
            val offset = getCanvasOffsetBase(pt, w, h, fieldWidthM, fieldHeightM, league)
            if (idx == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
        }
        drawPath(
            path = path,
            color = AresRed.copy(alpha = 0.72f),
            style = Stroke(width = 2.5f, pathEffect = MirrorDashEffect),
        )
    }

    val lengthPx = ((robotDimensions.lengthMeters / fieldWidthM) * w).toFloat().coerceAtLeast(10f)
    val widthPx = ((robotDimensions.widthMeters / fieldHeightM) * h).toFloat().coerceAtLeast(10f)

    mirroredWaypoints.forEachIndexed { idx, wp ->
        val center = getCanvasOffsetBase(wp, w, h, fieldWidthM, fieldHeightM, league)
        val headingRadians = wp.headingRad ?: wp.rotationDeg?.let(Math::toRadians) ?: 0.0
        val canvasHeading = -Math.toDegrees(headingRadians).toFloat() - if (league == League.FTC) 90f else 0f

        withTransform({
            translate(center.x, center.y)
            rotate(canvasHeading, pivot = Offset.Zero)
        }) {
            val topLeft = Offset(-lengthPx / 2f, -widthPx / 2f)
            val rectSize = Size(lengthPx, widthPx)
            drawRoundRect(
                color = AresRed.copy(alpha = if (idx == 0) 0.22f else 0.14f),
                topLeft = topLeft,
                size = rectSize,
                cornerRadius = CornerRadius(4f, 4f),
            )
            drawRoundRect(
                color = AresRed.copy(alpha = 0.78f),
                topLeft = topLeft,
                size = rectSize,
                cornerRadius = CornerRadius(4f, 4f),
                style = Stroke(width = 1.6f, pathEffect = MirrorDashEffect),
            )
            drawLine(
                color = AresGold.copy(alpha = 0.85f),
                start = Offset.Zero,
                end = Offset(lengthPx * 0.44f, 0f),
                strokeWidth = 2f,
            )
        }
        drawCircle(
            color = AresRed,
            radius = 4.5f,
            center = center,
        )
    }
}

@Composable
internal fun PathPlannerCanvasHud(
    cursorPose: Waypoint?,
    selectedHeadingDeg: Double?,
    pathLengthMeters: Double,
    etaSeconds: Double,
    isShiftSnapActive: Boolean,
    showAllianceMirror: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleAllianceMirror: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = AresBackground.copy(alpha = 0.90f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, AresBorder),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val coordLabel = if (cursorPose != null) {
                    val headingSuffix = selectedHeadingDeg?.let {
                        String.format(Locale.US, " · θ %.1f°", it)
                    }.orEmpty()
                    String.format(Locale.US, "X %.2fm  Y %.2fm%s", cursorPose.x, cursorPose.y, headingSuffix)
                } else {
                    "Hover field for (x, y)"
                }
                Text(
                    text = coordLabel,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = if (cursorPose != null) AresCyan else AresTextSecondary,
                )
                Text(
                    text = String.format(Locale.US, "Len %.2fm · ETA %.2fs", pathLengthMeters, etaSeconds),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = AresTextPrimary,
                )
                if (isShiftSnapActive) {
                    Surface(
                        color = AresGold.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, AresGold.copy(alpha = 0.6f)),
                    ) {
                        Text(
                            text = "SNAP 5cm / 15°",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = AresGold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HudCompactActionButton(
                    label = "Undo",
                    enabled = canUndo,
                    active = false,
                    activeColor = AresCyan,
                    icon = {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo waypoint edit", modifier = Modifier.size(13.dp))
                    },
                    onClick = onUndo,
                )
                HudCompactActionButton(
                    label = "Redo",
                    enabled = canRedo,
                    active = false,
                    activeColor = AresCyan,
                    icon = {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo waypoint edit", modifier = Modifier.size(13.dp))
                    },
                    onClick = onRedo,
                )
                HudCompactActionButton(
                    label = if (showAllianceMirror) "Mirror ON" else "Blue↔Red Mirror",
                    enabled = true,
                    active = showAllianceMirror,
                    activeColor = AresRed,
                    icon = {
                        Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = "Toggle alliance mirror preview", modifier = Modifier.size(13.dp))
                    },
                    onClick = onToggleAllianceMirror,
                )
            }
        }
    }
}

@Composable
private fun HudCompactActionButton(
    label: String,
    enabled: Boolean,
    active: Boolean,
    activeColor: Color,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp),
        modifier = Modifier.height(24.dp),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(
            width = 1.dp,
            color = when {
                !enabled -> AresBorder.copy(alpha = 0.45f)
                active -> activeColor
                else -> AresBorder
            },
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) activeColor.copy(alpha = 0.16f) else AresSurfaceElevated.copy(alpha = 0.7f),
            contentColor = if (active) activeColor else AresTextPrimary,
            disabledContentColor = AresTextSecondary.copy(alpha = 0.45f),
        ),
    ) {
        icon()
        Spacer(Modifier.width(4.dp))
        Text(text = label, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
