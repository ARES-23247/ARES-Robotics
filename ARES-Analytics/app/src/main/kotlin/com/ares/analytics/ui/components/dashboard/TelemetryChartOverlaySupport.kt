package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.analysis.*
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

internal data class CompareChartOverlayData(
    val alignment: RunOverlayAlignment? = null,
    val alignedComparePointsByKey: Map<String, List<TelemetryPoint>> = emptyMap(),
)

@Composable
internal fun rememberCompareChartOverlayData(
    databaseService: DatabaseService?,
    primarySessionId: String?,
    compareSessionId: String?,
    selectedKeys: List<String>,
    alignmentMode: OverlayAlignmentMode,
    manualOffsetMs: Long,
): CompareChartOverlayData {
    val state by produceState(
        initialValue = CompareChartOverlayData(),
        databaseService,
        primarySessionId,
        compareSessionId,
        selectedKeys,
        alignmentMode,
        manualOffsetMs,
    ) {
        if (databaseService == null ||
            primarySessionId.isNullOrBlank() ||
            compareSessionId.isNullOrBlank() ||
            selectedKeys.isEmpty()
        ) {
            value = CompareChartOverlayData()
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            val primaryRange = databaseService.getSessionTimestampRange(primarySessionId) ?: return@withContext CompareChartOverlayData()
            val compareRange = databaseService.getSessionTimestampRange(compareSessionId) ?: return@withContext CompareChartOverlayData()

            val motionProbeKey = selectedKeys.first()
            val primaryMotion = SignalMathEngine.fromFrames(
                databaseService.getTelemetrySeries(primarySessionId, motionProbeKey, primaryRange.first, primaryRange.second, 600),
            )
            val compareMotion = SignalMathEngine.fromFrames(
                databaseService.getTelemetrySeries(compareSessionId, motionProbeKey, compareRange.first, compareRange.second, 600),
            )
            val alignment = RunOverlayAlignmentEngine.computeAlignment(
                mode = alignmentMode,
                primarySessionStartMs = primaryRange.first,
                compareSessionStartMs = compareRange.first,
                primaryMotionSamples = primaryMotion,
                compareMotionSamples = compareMotion,
                manualOffsetMs = manualOffsetMs,
            )

            val byKey = selectedKeys.associateWith { key ->
                val rawCompare = SignalMathEngine.fromFrames(
                    databaseService.getTelemetrySeries(
                        sessionId = compareSessionId,
                        key = key,
                        startMs = compareRange.first,
                        endMs = compareRange.second,
                        maxPoints = 1_500,
                    ).ifEmpty {
                        databaseService.getTelemetrySeries(
                            sessionId = compareSessionId,
                            key = "/$key",
                            startMs = compareRange.first,
                            endMs = compareRange.second,
                            maxPoints = 1_500,
                        )
                    },
                )
                RunOverlayAlignmentEngine.projectCompareSeriesToPrimaryTimeline(rawCompare, alignment)
                    .map { TelemetryPoint(it.timestampMs, it.value) }
            }
            CompareChartOverlayData(alignment = alignment, alignedComparePointsByKey = byKey)
        }
    }
    return state
}

@Composable
internal fun TelemetryChartCompareBar(
    compareSessionId: String,
    mode: OverlayAlignmentMode,
    manualOffsetMs: Long,
    onSelectMode: (OverlayAlignmentMode) -> Unit,
    onAdjustManualOffset: (Long) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AresBackground, RoundedCornerShape(8.dp))
            .border(1.dp, AresGold.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "A/B Overlay · Run B (${compareSessionId.take(8)}) — Dashed",
                color = AresGold,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            OverlayAlignmentMode.entries.forEach { candidate ->
                val selected = mode == candidate
                Surface(
                    onClick = { onSelectMode(candidate) },
                    shape = RoundedCornerShape(5.dp),
                    color = if (selected) AresGold.copy(alpha = 0.2f) else AresSurface,
                    border = BorderStroke(1.dp, if (selected) AresGold else AresBorder),
                ) {
                    Text(
                        text = candidate.label,
                        color = if (selected) AresGold else AresTextSecondary,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
        }

        if (mode == OverlayAlignmentMode.MANUAL_OFFSET_MS) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(-250L to "-250", -50L to "-50", 50L to "+50", 250L to "+250").forEach { (delta, label) ->
                    Surface(
                        onClick = { onAdjustManualOffset(manualOffsetMs + delta) },
                        shape = RoundedCornerShape(4.dp),
                        color = AresSurface,
                        border = BorderStroke(1.dp, AresBorder),
                    ) {
                        Text(label, color = AresTextPrimary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                    }
                }
                Text(
                    text = "${if (manualOffsetMs >= 0) "+" else ""}${manualOffsetMs}ms",
                    color = AresCyan,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.telemetryChartHoverSync(
    widgetId: String,
    crosshairCoordinator: DashboardCrosshairCoordinator?,
    minTimestampMs: Long,
    maxTimestampMs: Long,
    horizontalInsetPx: Float,
    plotContainerWidthPx: Float,
): Modifier {
    if (crosshairCoordinator == null) return this
    return this
        .onPointerEvent(PointerEventType.Move) { event ->
            val pos = event.changes.firstOrNull()?.position ?: return@onPointerEvent
            val plotWidth = (plotContainerWidthPx - 2f * horizontalInsetPx).coerceAtLeast(1f)
            val fraction = ((pos.x - horizontalInsetPx) / plotWidth).coerceIn(0f, 1f)
            val span = (maxTimestampMs - minTimestampMs).coerceAtLeast(1L)
            val hoverTs = minTimestampMs + (fraction * span.toFloat()).toLong()
            crosshairCoordinator.updateHover(widgetId, hoverTs)
        }
        .onPointerEvent(PointerEventType.Exit) {
            crosshairCoordinator.clearHover(widgetId)
        }
}

internal fun DrawScope.drawCompareSeriesAndCrosshair(
    snapshot: TelemetryChartSnapshot,
    selectedKeys: List<String>,
    comparePointsByKey: Map<String, List<TelemetryPoint>>,
    channelColors: List<Color>,
    minX: Long,
    maxX: Long,
    hoverTimestampMs: Long?,
    comparePath: Path,
    dashEffect: PathEffect,
) {
    val spanX = max(1L, maxX - minX).toFloat()
    val bandHeight = size.height / snapshot.groups.size.coerceAtLeast(1)

    if (comparePointsByKey.isNotEmpty()) {
        snapshot.groups.forEachIndexed { groupIndex, group ->
            val bandTop = bandHeight * groupIndex
            val yRange = (group.bounds.max - group.bounds.min).takeIf { it > 1e-9 } ?: 1.0
            group.series.forEach { series ->
                val comparePts = comparePointsByKey[series.key].orEmpty()
                if (comparePts.size >= 2) {
                    val channelIdx = selectedKeys.indexOf(series.key).coerceAtLeast(0)
                    val color = channelColors[channelIdx % channelColors.size].copy(alpha = 0.72f)
                    comparePath.reset()
                    var started = false
                    for (pt in comparePts) {
                        if (pt.timestampMs < minX - 2_000L || pt.timestampMs > maxX + 2_000L) continue
                        val px = ((pt.timestampMs - minX) / spanX) * size.width
                        val converted = convertTelemetryChartValue(pt.value, series.sourceUnit, series.displayUnit)
                        val yPct = ((converted - group.bounds.min) / yRange).toFloat().coerceIn(-0.05f, 1.05f)
                        val py = bandTop + bandHeight - yPct * bandHeight
                        if (!started) {
                            comparePath.moveTo(px, py)
                            started = true
                        } else {
                            comparePath.lineTo(px, py)
                        }
                    }
                    if (started) {
                        drawPath(
                            path = comparePath,
                            color = color,
                            style = Stroke(width = 1.8.dp.toPx(), pathEffect = dashEffect),
                        )
                    }
                }
            }
        }
    }

    if (hoverTimestampMs != null && hoverTimestampMs in minX..maxX) {
        val hx = ((hoverTimestampMs - minX) / spanX) * size.width
        drawLine(
            color = AresCyan.copy(alpha = 0.85f),
            start = Offset(hx, 0f),
            end = Offset(hx, size.height),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = dashEffect,
        )
    }
}

@Composable
internal fun BoxScope.TelemetryChartHoverReadout(
    hoverTimestampMs: Long?,
    minX: Long,
    maxX: Long,
    snapshot: TelemetryChartSnapshot,
    selectedKeys: List<String>,
    comparePointsByKey: Map<String, List<TelemetryPoint>>,
    channelColors: List<Color>,
) {
    val ts = hoverTimestampMs?.takeIf { it in minX..maxX } ?: return
    Column(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(10.dp)
            .background(AresBackground.copy(alpha = 0.92f), RoundedCornerShape(6.dp))
            .border(1.dp, AresCyan.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val relSec = (ts - minX) / 1000.0
        Text(
            text = "Cursor +${String.format("%.2fs", relSec)}",
            color = AresCyan,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
        snapshot.groups.forEach { group ->
            group.series.forEach { series ->
                val primaryVal = interpolateChartPointAt(series.points, ts)
                if (primaryVal != null) {
                    val idx = selectedKeys.indexOf(series.key).coerceAtLeast(0)
                    val disp = convertTelemetryChartValue(primaryVal, series.sourceUnit, series.displayUnit)
                    val cmpVal = comparePointsByKey[series.key]?.let { interpolateChartPointAt(it, ts) }
                        ?.let { convertTelemetryChartValue(it, series.sourceUnit, series.displayUnit) }
                    val cmpSuffix = cmpVal?.let { " | B:${String.format("%.2f", it)}" }.orEmpty()
                    Text(
                        text = "${series.key.substringAfterLast('/')}: ${String.format("%.2f", disp)} ${group.unitSymbol}$cmpSuffix",
                        color = channelColors[idx % channelColors.size],
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

internal fun interpolateChartPointAt(points: List<TelemetryPoint>, timestampMs: Long): Double? {
    if (points.isEmpty()) return null
    if (timestampMs <= points.first().timestampMs) return points.first().value
    if (timestampMs >= points.last().timestampMs) return points.last().value
    var lo = 0
    var hi = points.lastIndex
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val midTs = points[mid].timestampMs
        when {
            midTs < timestampMs -> lo = mid + 1
            midTs > timestampMs -> hi = mid - 1
            else -> return points[mid].value
        }
    }
    val left = points[hi.coerceAtLeast(0)]
    val right = points[lo.coerceAtMost(points.lastIndex)]
    val span = (right.timestampMs - left.timestampMs).toDouble()
    val alpha = if (span > 0.0) ((timestampMs - left.timestampMs) / span).coerceIn(0.0, 1.0) else 0.0
    return left.value + alpha * (right.value - left.value)
}
