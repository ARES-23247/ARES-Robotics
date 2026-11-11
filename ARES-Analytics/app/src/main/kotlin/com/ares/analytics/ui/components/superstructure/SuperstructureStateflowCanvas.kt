package com.ares.analytics.ui.components.superstructure

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.viewmodel.superstructure.*
import com.ares.analytics.viewmodel.superstructure.SuperstructureCoordinationPreset
import com.ares.analytics.viewmodel.superstructure.SuperstructureStudioState
import com.ares.analytics.viewmodel.superstructure.SuperstructureStudioViewModel
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.TransitionTriggerKind
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Composable
fun StateflowGraphCanvas(
    state: SuperstructureStudioState,
    draft: SuperstructureDocument,
    viewModel: SuperstructureStudioViewModel,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val nodeWidth = 180f
    val nodeHeight = 70f

    val nodePositions = remember(draft.states, draft.nodeLayouts) {
        val map = mutableMapOf<String, Offset>()
        val cols = 3
        draft.states.forEachIndexed { index, statePreset ->
            val layout = draft.nodeLayouts[statePreset.stateId]
            if (layout != null) {
                map[statePreset.stateId] = Offset(layout.x.toFloat(), layout.y.toFloat())
            } else {
                val col = index % cols
                val row = index / cols
                map[statePreset.stateId] = Offset(30f + col * 220f, 25f + row * 110f)
            }
        }
        map
    }

    var draggedNodeId by remember { mutableStateOf<String?>(null) }

    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
        border = BorderStroke(1.dp, AresBorder),
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize()) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(draft.states, draft.nodeLayouts) {
                        detectTapGestures { tapOffset ->
                            val clicked = nodePositions.entries.firstOrNull { (_, pos) ->
                                tapOffset.x >= pos.x && tapOffset.x <= pos.x + nodeWidth &&
                                    tapOffset.y >= pos.y && tapOffset.y <= pos.y + nodeHeight
                            }
                            if (clicked != null) {
                                viewModel.selectState(clicked.key)
                            }
                        }
                    }
                    .pointerInput(draft.states, draft.nodeLayouts) {
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                val hit = nodePositions.entries.firstOrNull { (_, pos) ->
                                    startOffset.x >= pos.x && startOffset.x <= pos.x + nodeWidth &&
                                        startOffset.y >= pos.y && startOffset.y <= pos.y + nodeHeight
                                }
                                if (hit != null) {
                                    draggedNodeId = hit.key
                                    viewModel.selectState(hit.key)
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                draggedNodeId?.let { id ->
                                    val currentPos = nodePositions[id] ?: Offset.Zero
                                    val newPos = currentPos + dragAmount
                                    val clampedX = max(10f, min(1400f, newPos.x))
                                    val clampedY = max(10f, min(900f, newPos.y))
                                    viewModel.moveStateNode(id, clampedX.toDouble(), clampedY.toDouble())
                                }
                            },
                            onDragEnd = { draggedNodeId = null },
                            onDragCancel = { draggedNodeId = null },
                        )
                    },
            ) {
                clipRect {
                    val dotSpacing = 24f
                    val dotColor = AresBorder.copy(alpha = 0.35f)
                    var x = 0f
                    while (x < size.width) {
                        var y = 0f
                        while (y < size.height) {
                            drawCircle(dotColor, radius = 1f, center = Offset(x, y))
                            y += dotSpacing
                        }
                        x += dotSpacing
                    }

                    draft.transitions.forEach { edge ->
                        val srcPos = nodePositions[edge.sourceStateId] ?: return@forEach
                        val tgtPos = nodePositions[edge.targetStateId] ?: return@forEach

                        val srcCenter = Offset(srcPos.x + nodeWidth / 2f, srcPos.y + nodeHeight / 2f)
                        val tgtCenter = Offset(tgtPos.x + nodeWidth / 2f, tgtPos.y + nodeHeight / 2f)

                        val dx = tgtCenter.x - srcCenter.x
                        val dy = tgtCenter.y - srcCenter.y
                        val dist = hypot(dx, dy)
                        if (dist < 1f) return@forEach

                        val nx = dx / dist
                        val ny = dy / dist

                        val startPt = Offset(srcCenter.x + nx * (nodeWidth / 2.2f), srcCenter.y + ny * (nodeHeight / 2.2f))
                        val endPt = Offset(tgtCenter.x - nx * (nodeWidth / 2.2f), tgtCenter.y - ny * (nodeHeight / 2.2f))

                        val perpX = -ny * 25f
                        val perpY = nx * 25f

                        val ctrl1 = Offset(startPt.x + dx * 0.35f + perpX, startPt.y + dy * 0.35f + perpY)
                        val ctrl2 = Offset(startPt.x + dx * 0.65f + perpX, startPt.y + dy * 0.65f + perpY)

                        val curveColor = when (edge.triggerKind) {
                            TransitionTriggerKind.ACTION_REQUEST -> AresCyan
                            TransitionTriggerKind.SENSOR_CONDITION_AUTO -> AresGreen
                            TransitionTriggerKind.TIME_ELAPSED -> AresGold
                        }

                        val path = Path().apply {
                            moveTo(startPt.x, startPt.y)
                            cubicTo(ctrl1.x, ctrl1.y, ctrl2.x, ctrl2.y, endPt.x, endPt.y)
                        }
                        drawPath(path, color = curveColor.copy(alpha = 0.85f), style = Stroke(width = 2.2f, cap = StrokeCap.Round))

                        val arrowAngle = atan2(endPt.y - ctrl2.y, endPt.x - ctrl2.x)
                        val arrowSize = 9f
                        val p1 = endPt
                        val p2 = Offset(endPt.x - arrowSize * cos(arrowAngle - 0.45f), endPt.y - arrowSize * sin(arrowAngle - 0.45f))
                        val p3 = Offset(endPt.x - arrowSize * cos(arrowAngle + 0.45f), endPt.y - arrowSize * sin(arrowAngle + 0.45f))

                        val arrowPath = Path().apply {
                            moveTo(p1.x, p1.y)
                            lineTo(p2.x, p2.y)
                            lineTo(p3.x, p3.y)
                            close()
                        }
                        drawPath(arrowPath, color = curveColor, style = Fill)
                    }

                    draft.states.forEach { statePreset ->
                        val pos = nodePositions[statePreset.stateId] ?: Offset.Zero
                        val isSelected = statePreset.stateId == state.selectedStateId
                        val isInitial = statePreset.stateId == draft.initialStateId
                        val isFault = statePreset.stateId == draft.faultStateId

                        val borderColor = when {
                            isSelected -> AresCyan
                            isInitial -> AresGreen
                            isFault -> AresError
                            else -> AresBorder
                        }

                        drawRoundRect(
                            color = AresSurface,
                            topLeft = pos,
                            size = Size(nodeWidth, nodeHeight),
                            cornerRadius = CornerRadius(8f, 8f),
                        )
                        drawRoundRect(
                            color = borderColor,
                            topLeft = pos,
                            size = Size(nodeWidth, nodeHeight),
                            cornerRadius = CornerRadius(8f, 8f),
                            style = Stroke(if (isSelected) 2.5f else 1.2f),
                        )

                        drawText(
                            textMeasurer,
                            statePreset.displayName.ifBlank { statePreset.stateId },
                            topLeft = Offset(pos.x + 10f, pos.y + 8f),
                            style = TextStyle(color = AresTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        )
                        drawText(
                            textMeasurer,
                            "${statePreset.subsystemTargets.size} targets",
                            topLeft = Offset(pos.x + 10f, pos.y + 32f),
                            style = TextStyle(color = AresTextSecondary, fontSize = 9.sp),
                        )
                    }
                }
            }
        }
    }
}
