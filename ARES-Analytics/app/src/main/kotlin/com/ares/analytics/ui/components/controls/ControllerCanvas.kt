package com.ares.analytics.ui.components.controls

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.GamepadState
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresRed
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.areslib.controls.ControllerControlDocument
import com.areslib.controls.ControllerControlTypeDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.controls.ControllerProfileDocument
import com.areslib.controls.ControllerSurfaceDocument
import kotlin.math.abs

/** Procedural controller art: resolution-independent, theme-aware, and safe to edit offline. */
@Composable
fun ControllerCanvas(
    profile: ControllerProfileDocument,
    surface: ControllerSurfaceDocument,
    selectedControlId: String?,
    chordControlIds: Set<String>,
    boundControlIds: Set<String>,
    targetPlatform: ControllerInputPlatform,
    liveState: GamepadState,
    onControlSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    boundActionLabels: Map<String, List<String>> = emptyMap(),
) {
    val controls = profile.controls.filter { it.surface == surface }
    val calloutControlIds = buildSet {
        addAll(boundControlIds)
        addAll(boundActionLabels.filterValues { it.isNotEmpty() }.keys)
        addAll(chordControlIds)
        selectedControlId?.let(::add)
    }
    val callouts = controllerCallouts(controls.filter { it.controlId in calloutControlIds })
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().height(360.dp)
            .background(Color.Black.copy(alpha = .18f), RoundedCornerShape(14.dp))
            .border(1.dp, AresBorder, RoundedCornerShape(14.dp)),
    ) {
        val calloutHeights = ControllerCalloutSide.entries.associateWith { side ->
            val count = callouts.count { it.side == side }
            val spacing = if (count <= 1) {
                42.dp
            } else {
                maxHeight * ((CALLOUT_BOTTOM_Y - CALLOUT_TOP_Y) / (count - 1))
            }
            minOf(42.dp, (spacing - 2.dp).coerceAtLeast(20.dp))
        }
        Canvas(Modifier.fillMaxSize()) {
            val bodyStartX = size.width * CONTROLLER_BODY_START_X
            val bodyWidth = size.width * CONTROLLER_BODY_WIDTH
            fun bodyX(normalized: Double) = bodyStartX + bodyWidth * normalized.toFloat()
            fun bodyY(normalized: Double) = size.height * (
                CONTROLLER_BODY_START_Y + CONTROLLER_BODY_HEIGHT * normalized.toFloat()
            )

            val outline = Path().apply {
                moveTo(bodyX(.18), bodyY(.18))
                cubicTo(bodyX(.08), bodyY(.23), bodyX(.03), bodyY(.62), bodyX(.15), bodyY(.84))
                cubicTo(bodyX(.25), bodyY(.94), bodyX(.32), bodyY(.76), bodyX(.40), bodyY(.70))
                lineTo(bodyX(.60), bodyY(.70))
                cubicTo(bodyX(.68), bodyY(.76), bodyX(.75), bodyY(.94), bodyX(.85), bodyY(.84))
                cubicTo(bodyX(.97), bodyY(.62), bodyX(.92), bodyY(.23), bodyX(.82), bodyY(.18))
                cubicTo(bodyX(.68), bodyY(.08), bodyX(.32), bodyY(.08), bodyX(.18), bodyY(.18))
                close()
            }
            drawPath(outline, AresSurface.copy(alpha = .82f))
            drawPath(outline, AresBorder.copy(alpha = .82f), style = Stroke(4f))
            if (surface == ControllerSurfaceDocument.FRONT) {
                val stickRadius = bodyWidth * .09f
                drawCircle(AresBorder.copy(alpha = .45f), stickRadius, Offset(bodyX(.35), bodyY(.68)), style = Stroke(3f))
                drawCircle(AresBorder.copy(alpha = .45f), stickRadius, Offset(bodyX(.65), bodyY(.68)), style = Stroke(3f))
                val dpadCenter = Offset(bodyX(.23), bodyY(.50))
                drawRoundRect(
                    AresBorder.copy(alpha = .36f),
                    topLeft = Offset(dpadCenter.x - bodyWidth * .095f, dpadCenter.y - bodyWidth * .030f),
                    size = androidx.compose.ui.geometry.Size(bodyWidth * .19f, bodyWidth * .06f),
                    cornerRadius = CornerRadius(10f),
                )
                drawRoundRect(
                    AresBorder.copy(alpha = .36f),
                    topLeft = Offset(dpadCenter.x - bodyWidth * .030f, dpadCenter.y - bodyWidth * .095f),
                    size = androidx.compose.ui.geometry.Size(bodyWidth * .06f, bodyWidth * .19f),
                    cornerRadius = CornerRadius(10f),
                )
                drawRoundRect(
                    AresBorder.copy(alpha = .24f),
                    topLeft = Offset(bodyX(.40), bodyY(.24)),
                    size = androidx.compose.ui.geometry.Size(bodyWidth * .20f, size.height * CONTROLLER_BODY_HEIGHT * .25f),
                    cornerRadius = CornerRadius(18f),
                    style = Stroke(2f),
                )
            } else {
                drawRoundRect(
                    AresBorder.copy(alpha = .25f),
                    topLeft = Offset(bodyX(.31), bodyY(.22)),
                    size = androidx.compose.ui.geometry.Size(bodyWidth * .38f, size.height * CONTROLLER_BODY_HEIGHT * .58f),
                    cornerRadius = CornerRadius(36f),
                    style = Stroke(3f),
                )
            }

            callouts.forEach { callout ->
                val control = callout.control
                val actionLabels = boundActionLabels[control.controlId].orEmpty()
                val selected = control.controlId == selectedControlId
                val active = control.isActive(liveState)
                val lineColor = when {
                    active -> AresCyan
                    selected -> AresGold
                    actionLabels.isNotEmpty() -> AresCyan.copy(alpha = .62f)
                    else -> AresBorder.copy(alpha = .28f)
                }
                val anchor = Offset(
                    bodyX(control.anchor.x) + control.canvasCollisionOffsetX(controls).dp.toPx(),
                    bodyY(control.anchor.y) + control.canvasCollisionOffsetY(controls).dp.toPx(),
                )
                val calloutY = size.height * callout.normalizedY
                val endX = size.width * if (callout.side == ControllerCalloutSide.LEFT) {
                    CALLOUT_INNER_LEFT_X
                } else {
                    CALLOUT_INNER_RIGHT_X
                }
                val elbowX = (anchor.x + endX) / 2f
                val leader = Path().apply {
                    moveTo(anchor.x, anchor.y)
                    lineTo(elbowX, anchor.y)
                    lineTo(elbowX, calloutY)
                    lineTo(endX, calloutY)
                }
                drawPath(leader, lineColor, style = Stroke(if (selected || active) 2.5f else 1.5f))
            }
        }

        controls.forEach { control ->
            val callout = callouts.firstOrNull { it.control.controlId == control.controlId }
            val active = control.isActive(liveState)
            val selected = control.controlId == selectedControlId
            val inChord = control.controlId in chordControlIds
            val bound = control.controlId in boundControlIds
            val actionLabels = boundActionLabels[control.controlId].orEmpty()
            val hasBindings = bound || actionLabels.isNotEmpty()

            val borderColor = when {
                active -> AresCyan
                selected -> AresGold
                inChord -> AresGold
                hasBindings -> AresCyan
                else -> AresBorder
            }

            val backgroundColor = when {
                active -> AresCyan.copy(alpha = 0.35f)
                selected -> AresGold.copy(alpha = 0.25f)
                inChord -> AresGold.copy(alpha = 0.20f)
                hasBindings -> AresCyan.copy(alpha = 0.14f)
                else -> Color.Black.copy(alpha = 0.75f)
            }

            val textColor = when {
                active -> AresCyan
                selected -> AresGold
                inChord -> AresGold
                hasBindings -> AresTextPrimary
                else -> AresTextTertiary
            }

            val collisionOffsetX = control.canvasCollisionOffsetX(controls)
            val collisionOffsetY = control.canvasCollisionOffsetY(controls)
            val targetMapped = control.mappings.any { it.platform == targetPlatform }
            val markerShape = when (control.type) {
                ControllerControlTypeDocument.BUTTON -> CircleShape
                else -> RoundedCornerShape(7.dp)
            }
            val faceAccent = control.controllerFaceAccent()
            val markerBorderColor = when {
                active || selected || inChord -> borderColor
                faceAccent != null -> faceAccent
                else -> borderColor
            }
            val markerTextColor = faceAccent?.takeUnless { active || selected || inChord } ?: textColor

            Box(
                modifier = Modifier
                    .offset(
                        x = maxWidth * (CONTROLLER_BODY_START_X + CONTROLLER_BODY_WIDTH * control.anchor.x.toFloat()) - 17.dp + collisionOffsetX.dp,
                        y = maxHeight * (CONTROLLER_BODY_START_Y + CONTROLLER_BODY_HEIGHT * control.anchor.y.toFloat()) - 13.dp + collisionOffsetY.dp,
                    )
                    .size(width = 34.dp, height = 26.dp)
                    .background(backgroundColor, markerShape)
                    .border(if (active || selected || hasBindings || faceAccent != null) 1.5.dp else 1.dp, markerBorderColor, markerShape)
                    .clickable { onControlSelected(control.controlId) }
                    .padding(horizontal = 3.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = control.controllerMarkerLabel() + if (targetMapped) "" else "!",
                    color = markerTextColor,
                    fontSize = 9.sp,
                    fontWeight = if (hasBindings || selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
            }

            if (callout != null) {
                val calloutHeight = calloutHeights.getValue(callout.side)
                val denseCallouts = calloutHeight < 38.dp
                Box(
                    modifier = Modifier
                        .offset(
                            x = if (callout.side == ControllerCalloutSide.LEFT) 8.dp else maxWidth * CALLOUT_INNER_RIGHT_X + 6.dp,
                            y = maxHeight * callout.normalizedY - calloutHeight / 2,
                        )
                        .width(maxWidth * CALLOUT_WIDTH)
                        .height(calloutHeight)
                        .background(backgroundColor, RoundedCornerShape(8.dp))
                        .border(if (active || selected || hasBindings) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
                        .clickable { onControlSelected(control.controlId) }
                        .padding(horizontal = if (denseCallouts) 5.dp else 7.dp, vertical = if (denseCallouts) 1.dp else 4.dp),
                ) {
                    Row(
                        Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            control.controllerMarkerLabel(),
                            color = borderColor,
                            fontSize = if (denseCallouts) 8.sp else 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Column(Modifier.weight(1f)) {
                            val actionSummary = when {
                                actionLabels.isEmpty() -> "Unassigned"
                                actionLabels.size == 1 -> actionLabels.first()
                                else -> "${actionLabels.first()} +${actionLabels.size - 1}"
                            }
                            if (denseCallouts) {
                                Text(
                                    "${control.displayName}${if (targetMapped) "" else " !"} · $actionSummary",
                                    color = if (actionLabels.isEmpty()) textColor else AresCyan,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                Text(
                                    control.displayName + if (targetMapped) "" else " · setup needed",
                                    color = textColor,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    actionSummary,
                                    color = if (actionLabels.isEmpty()) AresTextTertiary else AresCyan,
                                    fontSize = 8.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal enum class ControllerCalloutSide { LEFT, RIGHT }

internal data class ControllerCallout(
    val control: ControllerControlDocument,
    val side: ControllerCalloutSide,
    val normalizedY: Float,
)

/** Stable physical ordering keeps the diagram predictable while preventing label collisions. */
internal fun controllerCallouts(
    controls: List<ControllerControlDocument>,
): List<ControllerCallout> {
    val sides = controls.groupBy { control ->
        if (control.anchor.x <= .5) ControllerCalloutSide.LEFT else ControllerCalloutSide.RIGHT
    }
    return ControllerCalloutSide.entries.flatMap { side ->
        val sorted = sides[side].orEmpty().sortedWith(compareBy({ it.anchor.y }, { it.anchor.x }, { it.controlId }))
        sorted.mapIndexed { index, control ->
            ControllerCallout(control, side, controllerCalloutY(index, sorted.size))
        }
    }
}

internal fun controllerCalloutY(index: Int, count: Int): Float {
    if (count <= 1) return .5f
    return CALLOUT_TOP_Y + (CALLOUT_BOTTOM_Y - CALLOUT_TOP_Y) * index / (count - 1)
}

internal fun ControllerControlDocument.controllerMarkerLabel(): String = when (controlId) {
    "btn_a" -> "A"
    "btn_b" -> "B"
    "btn_x" -> "X"
    "btn_y" -> "Y"
    "bumper_l" -> "LB"
    "bumper_r" -> "RB"
    "dpad_up" -> "D↑"
    "dpad_down" -> "D↓"
    "dpad_left" -> "D←"
    "dpad_right" -> "D→"
    "left_bumper" -> "LB"
    "right_bumper" -> "RB"
    "left_trigger" -> "LT"
    "right_trigger" -> "RT"
    "left_stick_x" -> "LX"
    "left_stick_y" -> "LY"
    "right_stick_x" -> "RX"
    "right_stick_y" -> "RY"
    "left_stick" -> "LS"
    "right_stick" -> "RS"
    "left_stick_button" -> "L3"
    "right_stick_button" -> "R3"
    else -> displayName.take(3)
}

private fun ControllerControlDocument.controllerFaceAccent(): Color? = when (controlId.lowercase()) {
    "a", "btn_a" -> AresGreen
    "b", "btn_b" -> AresRed
    "x", "btn_x" -> AresCyan
    "y", "btn_y" -> AresGold
    else -> null
}

private const val CONTROLLER_BODY_START_X = .235f
private const val CONTROLLER_BODY_WIDTH = .53f
private const val CONTROLLER_BODY_START_Y = .08f
private const val CONTROLLER_BODY_HEIGHT = .84f
private const val CALLOUT_INNER_LEFT_X = .225f
private const val CALLOUT_INNER_RIGHT_X = .775f
private const val CALLOUT_WIDTH = .215f
private const val CALLOUT_TOP_Y = .075f
private const val CALLOUT_BOTTOM_Y = .925f

/**
 * Some controller catalogs place the horizontal and vertical axes at the same visual stick
 * center. Their full beginner-facing labels then overlap even though both controls are valid.
 * Separate only those same-stick/same-row axis callouts; catalogs that already provide distinct
 * anchors retain their authored layout.
 */
internal fun ControllerControlDocument.canvasCollisionOffsetY(
    controls: Collection<ControllerControlDocument>,
): Float {
    if (type != ControllerControlTypeDocument.AXIS) return 0f
    val axis = when {
        controlId.endsWith("_x") -> 'x'
        controlId.endsWith("_y") -> 'y'
        else -> return 0f
    }
    val stickId = controlId.removeSuffix("_x").removeSuffix("_y")
    val sharesVisualRow = controls.any { peer ->
        peer !== this &&
            peer.type == ControllerControlTypeDocument.AXIS &&
            peer.controlId.removeSuffix("_x").removeSuffix("_y") == stickId &&
            kotlin.math.abs(peer.anchor.y - anchor.y) < 0.04
    }
    if (!sharesVisualRow) return 0f
    return if (axis == 'x') -28f else 28f
}

/** Separates controller-specific extras that intentionally share a physical region (for example Vader LM/LB). */
internal fun ControllerControlDocument.canvasCollisionOffsetX(
    controls: Collection<ControllerControlDocument>,
): Float {
    val family = controllerMarkerFamily() ?: return 0f
    val cluster = controls.filter { it.controllerMarkerFamily() == family }.sortedBy { it.controlId }
    if (cluster.size <= 1) return 0f
    val index = cluster.indexOfFirst { it === this }.takeIf { it >= 0 }
        ?: cluster.indexOfFirst { it.controlId == controlId }
    if (index < 0) return 0f
    return (index - (cluster.size - 1) / 2f) * 22f
}

private fun ControllerControlDocument.controllerMarkerFamily(): String? = when {
    controlId.startsWith("left_stick") -> "left-stick"
    controlId.startsWith("right_stick") -> "right-stick"
    controlId in setOf("left_bumper", "lm") -> "left-shoulder"
    controlId in setOf("right_bumper", "rm") -> "right-shoulder"
    else -> null
}

fun ControllerControlDocument.isActive(state: GamepadState, threshold: Float = .55f): Boolean {
    if (!state.connected) return false
    val mapping = mappings.firstOrNull { it.platform == ControllerInputPlatform.DESKTOP_GLFW }
    val buttonIndex = mapping?.buttonIndex
    if (buttonIndex != null) return state.rawButtons.getOrElse(buttonIndex) { false }
    val axisIndex = mapping?.axisIndex
    if (axisIndex != null) return abs(state.rawAxes.getOrElse(axisIndex) { 0f }) >= threshold
    return when (controlId) {
        "a" -> state.a
        "b" -> state.b
        "x" -> state.x
        "y" -> state.y
        "left_bumper" -> state.leftBumper
        "right_bumper" -> state.rightBumper
        "dpad_up" -> state.dpadUp
        "dpad_down" -> state.dpadDown
        "dpad_left" -> state.dpadLeft
        "dpad_right" -> state.dpadRight
        "left_trigger" -> state.leftTrigger >= threshold
        "right_trigger" -> state.rightTrigger >= threshold
        else -> false
    }
}
