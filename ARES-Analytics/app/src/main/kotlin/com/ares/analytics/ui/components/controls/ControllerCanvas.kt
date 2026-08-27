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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.GamepadState
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
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
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().height(360.dp)
            .background(Color.Black.copy(alpha = .18f), RoundedCornerShape(14.dp))
            .border(1.dp, AresBorder, RoundedCornerShape(14.dp)),
    ) {
        Canvas(Modifier.fillMaxSize().padding(24.dp)) {
            val outline = Path().apply {
                moveTo(size.width * .18f, size.height * .18f)
                cubicTo(size.width * .08f, size.height * .23f, size.width * .03f, size.height * .62f, size.width * .15f, size.height * .84f)
                cubicTo(size.width * .25f, size.height * .94f, size.width * .32f, size.height * .76f, size.width * .40f, size.height * .70f)
                lineTo(size.width * .60f, size.height * .70f)
                cubicTo(size.width * .68f, size.height * .76f, size.width * .75f, size.height * .94f, size.width * .85f, size.height * .84f)
                cubicTo(size.width * .97f, size.height * .62f, size.width * .92f, size.height * .23f, size.width * .82f, size.height * .18f)
                cubicTo(size.width * .68f, size.height * .08f, size.width * .32f, size.height * .08f, size.width * .18f, size.height * .18f)
                close()
            }
            drawPath(outline, AresBorder.copy(alpha = .65f), style = Stroke(4f))
            if (surface == ControllerSurfaceDocument.FRONT) {
                drawCircle(AresBorder.copy(alpha = .30f), size.minDimension * .105f, Offset(size.width * .35f, size.height * .68f), style = Stroke(3f))
                drawCircle(AresBorder.copy(alpha = .30f), size.minDimension * .105f, Offset(size.width * .65f, size.height * .68f), style = Stroke(3f))
                drawRoundRect(
                    AresBorder.copy(alpha = .24f),
                    topLeft = Offset(size.width * .40f, size.height * .24f),
                    size = androidx.compose.ui.geometry.Size(size.width * .20f, size.height * .25f),
                    cornerRadius = CornerRadius(18f),
                    style = Stroke(2f),
                )
            } else {
                drawRoundRect(
                    AresBorder.copy(alpha = .25f),
                    topLeft = Offset(size.width * .31f, size.height * .22f),
                    size = androidx.compose.ui.geometry.Size(size.width * .38f, size.height * .58f),
                    cornerRadius = CornerRadius(36f),
                    style = Stroke(3f),
                )
            }
        }

        controls.forEach { control ->
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

            val targetMapped = control.mappings.any { it.platform == targetPlatform }
            val collisionOffsetY = control.canvasCollisionOffsetY(controls)

            Box(
                modifier = Modifier
                    .offset(
                        x = maxWidth * control.anchor.x.toFloat() - 32.dp,
                        y = maxHeight * control.anchor.y.toFloat() - 20.dp + collisionOffsetY.dp,
                    )
                    .background(backgroundColor, RoundedCornerShape(8.dp))
                    .border(if (active || selected || hasBindings) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
                    .clickable { onControlSelected(control.controlId) }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = control.displayName + if (targetMapped) "" else " !",
                            color = textColor,
                            fontSize = 10.sp,
                            fontWeight = if (hasBindings || selected) FontWeight.Bold else FontWeight.Normal,
                        )
                        if (actionLabels.size > 1) {
                            Surface(
                                color = AresCyan.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(3.dp),
                            ) {
                                Text(
                                    text = "• ${actionLabels.size}",
                                    color = AresCyan,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }

                    if (actionLabels.isNotEmpty()) {
                        val firstAction = actionLabels.first()
                        Text(
                            text = firstAction.take(14),
                            color = AresCyan,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

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
