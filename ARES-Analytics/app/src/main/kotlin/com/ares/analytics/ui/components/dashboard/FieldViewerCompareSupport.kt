package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.service.analysis.*
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.components.pathplanner.Waypoint
import com.ares.analytics.ui.components.pathplanner.getCanvasOffsetBase
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot

internal data class FieldCompareAndHoverOverlayState(
    val compareTrajectory: List<Waypoint> = emptyList(),
    val comparePlayheadGhost: Waypoint? = null,
    val hoverPrimaryGhost: Waypoint? = null,
    val hoverCompareGhost: Waypoint? = null,
)

@Composable
internal fun rememberFieldCompareAndHoverOverlayState(
    databaseService: DatabaseService?,
    primarySessionId: String?,
    compareSessionId: String?,
    currentFrame: ReplayFrame?,
    crosshairCoordinator: DashboardCrosshairCoordinator?,
): FieldCompareAndHoverOverlayState {
    val activePrimarySession = currentFrame?.sessionId ?: primarySessionId
    val hoverState = crosshairCoordinator?.hover?.collectAsState()?.value

    val tracesAndAlignment by produceState<Triple<List<TimedPoseSample>, List<TimedPoseSample>, RunOverlayAlignment?>>(
        initialValue = Triple(emptyList(), emptyList(), null),
        databaseService,
        activePrimarySession,
        compareSessionId,
    ) {
        if (databaseService == null || activePrimarySession.isNullOrBlank()) {
            value = Triple(emptyList(), emptyList(), null)
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            val primaryRange = databaseService.getSessionTimestampRange(activePrimarySession)
            val primaryTrace = if (primaryRange != null && primaryRange.second >= primaryRange.first) {
                databaseService.getReplayPoseTrace(activePrimarySession, primaryRange.first, primaryRange.second, 400)
                    .map { TimedPoseSample(it.timestampMs, it.x, it.y, it.headingRadians) }
            } else {
                emptyList()
            }

            if (compareSessionId.isNullOrBlank() || primaryRange == null) {
                return@withContext Triple(primaryTrace, emptyList(), null)
            }
            val compareRange = databaseService.getSessionTimestampRange(compareSessionId)
                ?: return@withContext Triple(primaryTrace, emptyList(), null)
            val compareTrace = if (compareRange.second >= compareRange.first) {
                databaseService.getReplayPoseTrace(compareSessionId, compareRange.first, compareRange.second, 400)
                    .map { TimedPoseSample(it.timestampMs, it.x, it.y, it.headingRadians) }
            } else {
                emptyList()
            }

            val primarySpeeds = estimatePoseSpeedSamples(primaryTrace)
            val compareSpeeds = estimatePoseSpeedSamples(compareTrace)
            val alignment = RunOverlayAlignmentEngine.computeAlignment(
                mode = OverlayAlignmentMode.FIRST_MOTION_OR_ENABLE,
                primarySessionStartMs = primaryRange.first,
                compareSessionStartMs = compareRange.first,
                primaryMotionSamples = primarySpeeds,
                compareMotionSamples = compareSpeeds,
            )
            Triple(primaryTrace, compareTrace, alignment)
        }
    }

    val (primaryTrace, compareTrace, alignment) = tracesAndAlignment

    return remember(primaryTrace, compareTrace, alignment, currentFrame?.playheadMs, hoverState?.timestampMs) {
        val compareWaypoints = compareTrace.map { Waypoint(it.x, it.y, it.headingRadians) }
        val compareAtPlayhead = if (currentFrame != null && alignment != null && compareTrace.isNotEmpty()) {
            val alignedCompareTs = alignment.mapPrimaryToCompareTimestamp(currentFrame.playheadMs)
            RunOverlayAlignmentEngine.interpolatePoseAt(compareTrace, alignedCompareTs)
                ?.let { Waypoint(it.x, it.y, it.headingRadians) }
        } else {
            compareWaypoints.lastOrNull()
        }

        val hoverTs = hoverState?.timestampMs
        val hoverPrimary = if (hoverTs != null && primaryTrace.isNotEmpty()) {
            RunOverlayAlignmentEngine.interpolatePoseAt(primaryTrace, hoverTs)
                ?.let { Waypoint(it.x, it.y, it.headingRadians) }
        } else {
            null
        }
        val hoverCompare = if (hoverTs != null && alignment != null && compareTrace.isNotEmpty()) {
            val alignedHoverTs = alignment.mapPrimaryToCompareTimestamp(hoverTs)
            RunOverlayAlignmentEngine.interpolatePoseAt(compareTrace, alignedHoverTs)
                ?.let { Waypoint(it.x, it.y, it.headingRadians) }
        } else {
            null
        }

        FieldCompareAndHoverOverlayState(
            compareTrajectory = compareWaypoints,
            comparePlayheadGhost = compareAtPlayhead,
            hoverPrimaryGhost = hoverPrimary,
            hoverCompareGhost = hoverCompare,
        )
    }
}

private fun estimatePoseSpeedSamples(trace: List<TimedPoseSample>): List<TimedScalarSample> {
    if (trace.size < 2) return emptyList()
    val out = ArrayList<TimedScalarSample>(trace.size - 1)
    for (i in 1 until trace.size) {
        val dtSec = (trace[i].timestampMs - trace[i - 1].timestampMs) / 1000.0
        if (dtSec > 1e-4) {
            val dist = hypot(trace[i].x - trace[i - 1].x, trace[i].y - trace[i - 1].y)
            out += TimedScalarSample(trace[i].timestampMs, dist / dtSec)
        }
    }
    return out
}

internal fun DrawScope.drawFieldCompareAndHoverOverlay(
    overlayState: FieldCompareAndHoverOverlayState,
    canvasW: Float,
    canvasH: Float,
    fieldWidthM: Double,
    fieldHeightM: Double,
    league: League,
    robotDimensions: RobotDimensions,
) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 7f), 0f)
    if (overlayState.compareTrajectory.size >= 2) {
        val path = Path()
        overlayState.compareTrajectory.forEachIndexed { idx, wp ->
            val pt = getCanvasOffsetBase(wp, canvasW, canvasH, fieldWidthM, fieldHeightM, league)
            if (idx == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
        }
        drawPath(
            path = path,
            color = AresGold.copy(alpha = 0.7f),
            style = Stroke(width = 2.4.dp.toPx(), pathEffect = dash),
        )
    }

    overlayState.comparePlayheadGhost?.let { ghost ->
        drawGhostRobotBox(
            wp = ghost,
            canvasW = canvasW,
            canvasH = canvasH,
            fieldWidthM = fieldWidthM,
            fieldHeightM = fieldHeightM,
            league = league,
            robotDimensions = robotDimensions,
            outlineColor = AresGold,
            fillAlpha = 0.16f,
        )
    }

    overlayState.hoverCompareGhost?.let { hoverCmp ->
        drawGhostRobotBox(
            wp = hoverCmp,
            canvasW = canvasW,
            canvasH = canvasH,
            fieldWidthM = fieldWidthM,
            fieldHeightM = fieldHeightM,
            league = league,
            robotDimensions = robotDimensions,
            outlineColor = AresAmber,
            fillAlpha = 0.22f,
        )
    }

    overlayState.hoverPrimaryGhost?.let { hoverPrimary ->
        drawGhostRobotBox(
            wp = hoverPrimary,
            canvasW = canvasW,
            canvasH = canvasH,
            fieldWidthM = fieldWidthM,
            fieldHeightM = fieldHeightM,
            league = league,
            robotDimensions = robotDimensions,
            outlineColor = Color.White,
            fillAlpha = 0.24f,
        )
    }
}

private fun DrawScope.drawGhostRobotBox(
    wp: Waypoint,
    canvasW: Float,
    canvasH: Float,
    fieldWidthM: Double,
    fieldHeightM: Double,
    league: League,
    robotDimensions: RobotDimensions,
    outlineColor: Color,
    fillAlpha: Float,
) {
    val dims = robotDimensions.normalized()
    val pxPerMeter = minOf(canvasW / fieldWidthM.toFloat(), canvasH / fieldHeightM.toFloat())
    val robotLen = dims.lengthMeters.toFloat() * pxPerMeter
    val robotWid = dims.widthMeters.toFloat() * pxPerMeter
    val center = getCanvasOffsetBase(wp, canvasW, canvasH, fieldWidthM, fieldHeightM, league)
    val leagueHeadingOffset = if (league == League.FTC) 90f else 0f
    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)

    drawContext.canvas.save()
    drawContext.transform.rotate(
        degrees = -Math.toDegrees(wp.headingRad ?: 0.0).toFloat() - leagueHeadingOffset,
        pivot = center,
    )
    val topLeft = Offset(center.x - robotLen / 2f, center.y - robotWid / 2f)
    drawRect(color = outlineColor.copy(alpha = fillAlpha), topLeft = topLeft, size = Size(robotLen, robotWid))
    drawRect(
        color = outlineColor.copy(alpha = 0.9f),
        topLeft = topLeft,
        size = Size(robotLen, robotWid),
        style = Stroke(width = 1.8.dp.toPx(), pathEffect = dash),
    )
    drawLine(
        color = outlineColor,
        start = Offset(center.x + robotLen / 2f, center.y - robotWid * 0.35f),
        end = Offset(center.x + robotLen / 2f, center.y + robotWid * 0.35f),
        strokeWidth = 3.dp.toPx(),
    )
    drawContext.canvas.restore()
}

@Composable
internal fun BoxScope.FieldCompareBadge(
    compareSessionId: String?,
    hoverPose: Waypoint?,
) {
    if (compareSessionId.isNullOrBlank() && hoverPose == null) return
    Row(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(8.dp)
            .background(AresBackground.copy(alpha = 0.88f), RoundedCornerShape(6.dp))
            .border(1.dp, AresBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!compareSessionId.isNullOrBlank()) {
            Text(
                text = "Run B: ${compareSessionId.take(8)} (Gold Dashed)",
                color = AresGold,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
        hoverPose?.let { pose ->
            Text(
                text = "Hover (${String.format("%.2f", pose.x)}m, ${String.format("%.2f", pose.y)}m, ${String.format("%.0f°", Math.toDegrees(pose.headingRad ?: 0.0))})",
                color = Color.White,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
