package com.ares.analytics.ui.components.dashboard
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.di.KeyboardDriveState
import com.ares.analytics.service.GamepadService
import com.ares.analytics.ui.theme.*

@Composable
fun SingleGamepadVisualizer(
    title: String,
    gamepadId: String,
    currentFrame: ReplayFrame?,
    nt4ClientService: Nt4ClientService?,
    keyboardControlEnabled: Boolean,
    keyboardState: com.ares.analytics.di.KeyboardDriveState?,
    gamepadStateFlow: kotlinx.coroutines.flow.StateFlow<com.ares.analytics.service.GamepadState>?,
    modifier: Modifier = Modifier
) {
    val replayHasGamepadData = currentFrame?.values?.keys?.any { it.startsWith("$gamepadId/") } ?: false
    var lx by remember { mutableStateOf(0.0) }
    var ly by remember { mutableStateOf(0.0) }
    var rx by remember { mutableStateOf(0.0) }
    var ry by remember { mutableStateOf(0.0) }
    var lt by remember { mutableStateOf(0.0) }
    var rt by remember { mutableStateOf(0.0) }
    var btnA by remember { mutableStateOf(false) }
    var btnB by remember { mutableStateOf(false) }
    var btnX by remember { mutableStateOf(false) }
    var btnY by remember { mutableStateOf(false) }
    var dpadUp by remember { mutableStateOf(false) }
    var dpadDown by remember { mutableStateOf(false) }
    var dpadLeft by remember { mutableStateOf(false) }
    var dpadRight by remember { mutableStateOf(false) }
    var lb by remember { mutableStateOf(false) }
    var rb by remember { mutableStateOf(false) }
    var btnStart by remember { mutableStateOf(false) }
    var btnBack by remember { mutableStateOf(false) }
    var btnLS by remember { mutableStateOf(false) }
    var btnRS by remember { mutableStateOf(false) }

    if (currentFrame != null && !keyboardControlEnabled) {
        LaunchedEffect(currentFrame, gamepadId) {
            lx = currentFrame.values["$gamepadId/LeftStick_X"] ?: 0.0
            ly = currentFrame.values["$gamepadId/LeftStick_Y"] ?: 0.0
            rx = currentFrame.values["$gamepadId/RightStick_X"] ?: 0.0
            ry = currentFrame.values["$gamepadId/RightStick_Y"] ?: 0.0
            lt = currentFrame.values["$gamepadId/LeftTrigger"] ?: 0.0
            rt = currentFrame.values["$gamepadId/RightTrigger"] ?: 0.0
            btnA = (currentFrame.values["$gamepadId/A"] ?: 0.0) > 0.5
            btnB = (currentFrame.values["$gamepadId/B"] ?: 0.0) > 0.5
            btnX = (currentFrame.values["$gamepadId/X"] ?: 0.0) > 0.5
            btnY = (currentFrame.values["$gamepadId/Y"] ?: 0.0) > 0.5
            dpadUp = (currentFrame.values["$gamepadId/DpadUp"] ?: 0.0) > 0.5
            dpadDown = (currentFrame.values["$gamepadId/DpadDown"] ?: 0.0) > 0.5
            dpadLeft = (currentFrame.values["$gamepadId/DpadLeft"] ?: 0.0) > 0.5
            dpadRight = (currentFrame.values["$gamepadId/DpadRight"] ?: 0.0) > 0.5
            lb = (currentFrame.values["$gamepadId/LeftBumper"] ?: 0.0) > 0.5
            rb = (currentFrame.values["$gamepadId/RightBumper"] ?: 0.0) > 0.5
            btnStart = gamepadMenuPressed(currentFrame.values["$gamepadId/Start"], currentFrame.values["$gamepadId/Options"])
            btnBack = gamepadMenuPressed(currentFrame.values["$gamepadId/Back"], currentFrame.values["$gamepadId/Share"])
            btnLS = (currentFrame.values["$gamepadId/LeftStickButton"] ?: 0.0) > 0.5
            btnRS = (currentFrame.values["$gamepadId/RightStickButton"] ?: 0.0) > 0.5
        }
    }
    else if (nt4ClientService != null && !keyboardControlEnabled) {
            // Collect in the LaunchedEffect itself so switching to local keyboard control
            // cancels this remote-telemetry writer immediately. Launching into a remembered
            // composition scope leaves the old collector alive, allowing remote zeroes and the
            // held keyboard value to alternate on the same visual state.
            LaunchedEffect(nt4ClientService, gamepadId) {
                val menuButtons = GamepadMenuButtons(gamepadId)
                nt4ClientService.uiTelemetryFlow.collect { frame ->
                    val key = frame.key
                    val value = frame.value
                    when (key) {
                        "$gamepadId/LeftStick_X" -> lx = value
                        "$gamepadId/LeftStick_Y" -> ly = value
                        "$gamepadId/RightStick_X" -> rx = value
                        "$gamepadId/RightStick_Y" -> ry = value
                        "$gamepadId/LeftTrigger" -> lt = value
                        "$gamepadId/RightTrigger" -> rt = value
                        "$gamepadId/A" -> btnA = value > 0.5
                        "$gamepadId/B" -> btnB = value > 0.5
                        "$gamepadId/X" -> btnX = value > 0.5
                        "$gamepadId/Y" -> btnY = value > 0.5
                        "$gamepadId/DpadUp" -> dpadUp = value > 0.5
                        "$gamepadId/DpadDown" -> dpadDown = value > 0.5
                        "$gamepadId/DpadLeft" -> dpadLeft = value > 0.5
                        "$gamepadId/DpadRight" -> dpadRight = value > 0.5
                        "$gamepadId/LeftBumper" -> lb = value > 0.5
                        "$gamepadId/RightBumper" -> rb = value > 0.5
                        "$gamepadId/Start", "$gamepadId/Options", "$gamepadId/Back", "$gamepadId/Share" -> {
                            menuButtons.accept(key, value)
                            btnStart = menuButtons.startPressed
                            btnBack = menuButtons.backPressed
                        }
                        "$gamepadId/LeftStickButton" -> btnLS = value > 0.5
                        "$gamepadId/RightStickButton" -> btnRS = value > 0.5
                    }
                }
            }
        }

        if (keyboardControlEnabled && (keyboardState != null || gamepadStateFlow != null)) {
        LaunchedEffect(Unit) {
            while (true) {
                val g = gamepadStateFlow?.value
                when {
                    keyboardState?.useGamepad != false && g != null && g.connected -> {
                        lx = g.leftStickX.toDouble()
                        ly = -g.leftStickY.toDouble()
                        rx = g.rightStickX.toDouble()
                        ry = -g.rightStickY.toDouble()
                        lb = g.leftBumper
                        rb = g.rightBumper
                        lt = g.leftTrigger.toDouble()
                        rt = g.rightTrigger.toDouble()
                        btnA = g.a
                        btnB = g.b
                        btnX = g.x
                        btnY = g.y
                        dpadUp = g.dpadUp
                        dpadDown = g.dpadDown
                        dpadLeft = g.dpadLeft
                        dpadRight = g.dpadRight
                    }
                    keyboardState != null -> {
                        lx = when {
                            keyboardState.isAPressed -> -1.0
                            keyboardState.isDPressed -> 1.0
                            else -> 0.0
                        }
                        ly = when {
                            keyboardState.isWPressed -> -1.0
                            keyboardState.isSPressed -> 1.0
                            else -> 0.0
                        }
                        rx = when {
                            keyboardState.isLeftPressed -> -1.0
                            keyboardState.isRightPressed -> 1.0
                            else -> 0.0
                        }
                        ry = when {
                            keyboardState.isUpPressed -> -1.0
                            keyboardState.isDownPressed -> 1.0
                            else -> 0.0
                        }
                        lb = keyboardState.isQPressed
                        rb = keyboardState.isEPressed
                        lt = 0.0
                        rt = if (keyboardState.isShiftPressed) 1.0 else 0.0
                        btnA = keyboardState.isJPressed
                        btnB = keyboardState.isLPressed
                        btnX = keyboardState.isUPressed
                        btnY = keyboardState.isIPressed
                    }
                }
                kotlinx.coroutines.delay(20)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(AresBackground, RoundedCornerShape(8.dp))
            .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = AresTextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (currentFrame != null && !replayHasGamepadData) {
            Text(
                "No $gamepadId topics in this recording; centered controls below mean missing data.",
                color = AresTextTertiary,
                fontSize = 9.sp,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f

                // Scale the controller to fit half the width now
                val scale = minOf(size.width / 380f, size.height / 200f).coerceAtMost(1f)

                // Draw PS4 Body using Path
                val bodyPath = androidx.compose.ui.graphics.Path().apply {
                    // Center body
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(
                        left = cx - 110f * scale,
                        top = cy - 50f * scale,
                        right = cx + 110f * scale,
                        bottom = cy + 40f * scale,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(20f * scale)
                    ))
                }
                val leftGrip = androidx.compose.ui.graphics.Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(
                        left = cx - 140f * scale,
                        top = cy - 30f * scale,
                        right = cx - 80f * scale,
                        bottom = cy + 90f * scale,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(30f * scale)
                    ))
                }
                val rightGrip = androidx.compose.ui.graphics.Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(
                        left = cx + 80f * scale,
                        top = cy - 30f * scale,
                        right = cx + 140f * scale,
                        bottom = cy + 90f * scale,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(30f * scale)
                    ))
                }
                val combinedGrips = androidx.compose.ui.graphics.Path().apply {
                    op(bodyPath, leftGrip, androidx.compose.ui.graphics.PathOperation.Union)
                }
                val finalBodyPath = androidx.compose.ui.graphics.Path().apply {
                    op(combinedGrips, rightGrip, androidx.compose.ui.graphics.PathOperation.Union)
                }

                drawPath(
                    path = finalBodyPath,
                    color = AresSurfaceElevated
                )
                drawPath(
                    path = finalBodyPath,
                    color = if (keyboardControlEnabled) AresGreen else AresBorder,
                    style = Stroke(width = 2.5f * scale)
                )

                // D-Pad (Top Left)
                val dpadCenter = Offset(cx - 85f * scale, cy - 10f * scale)
                val dpadSize = 12f * scale
                drawRect(color = AresSurface, topLeft = Offset(dpadCenter.x - dpadSize * 2.5f, dpadCenter.y - dpadSize / 2f), size = Size(dpadSize * 5f, dpadSize))
                drawRect(color = AresSurface, topLeft = Offset(dpadCenter.x - dpadSize / 2f, dpadCenter.y - dpadSize * 2.5f), size = Size(dpadSize, dpadSize * 5f))
                drawRect(color = if (dpadLeft) AresCyan else AresBorder, topLeft = Offset(dpadCenter.x - dpadSize * 2.5f, dpadCenter.y - dpadSize / 2f), size = Size(dpadSize, dpadSize))
                drawRect(color = if (dpadRight) AresCyan else AresBorder, topLeft = Offset(dpadCenter.x + dpadSize * 1.5f, dpadCenter.y - dpadSize / 2f), size = Size(dpadSize, dpadSize))
                drawRect(color = if (dpadUp) AresCyan else AresBorder, topLeft = Offset(dpadCenter.x - dpadSize / 2f, dpadCenter.y - dpadSize * 2.5f), size = Size(dpadSize, dpadSize))
                drawRect(color = if (dpadDown) AresCyan else AresBorder, topLeft = Offset(dpadCenter.x - dpadSize / 2f, dpadCenter.y + dpadSize * 1.5f), size = Size(dpadSize, dpadSize))

                // Face Buttons (Top Right)
                val buttonsCenter = Offset(cx + 85f * scale, cy - 10f * scale)
                val btnRadius = 9f * scale
                val btnOffset = 18f * scale
                drawCircle(color = if (btnY) AresGold else AresSurface, radius = btnRadius, center = Offset(buttonsCenter.x, buttonsCenter.y - btnOffset))
                drawCircle(color = AresBorder, radius = btnRadius, center = Offset(buttonsCenter.x, buttonsCenter.y - btnOffset), style = Stroke(width = 1.5f * scale))
                drawCircle(color = if (btnA) AresGold else AresSurface, radius = btnRadius, center = Offset(buttonsCenter.x, buttonsCenter.y + btnOffset))
                drawCircle(color = AresBorder, radius = btnRadius, center = Offset(buttonsCenter.x, buttonsCenter.y + btnOffset), style = Stroke(width = 1.5f * scale))
                drawCircle(color = if (btnX) AresRed else AresSurface, radius = btnRadius, center = Offset(buttonsCenter.x - btnOffset, buttonsCenter.y))
                drawCircle(color = AresBorder, radius = btnRadius, center = Offset(buttonsCenter.x - btnOffset, buttonsCenter.y), style = Stroke(width = 1.5f * scale))
                drawCircle(color = if (btnB) AresCyan else AresSurface, radius = btnRadius, center = Offset(buttonsCenter.x + btnOffset, buttonsCenter.y))
                drawCircle(color = AresBorder, radius = btnRadius, center = Offset(buttonsCenter.x + btnOffset, buttonsCenter.y), style = Stroke(width = 1.5f * scale))

                // Left Stick (Bottom Left Grip)
                val stickRadius = 24f * scale
                val leftStickCenter = Offset(cx - 50f * scale, cy + 40f * scale)
                drawCircle(color = AresSurface, radius = stickRadius, center = leftStickCenter)
                if (btnLS) drawCircle(color = AresCyan.copy(alpha = 0.3f), radius = stickRadius, center = leftStickCenter)
                drawCircle(color = AresBorder, radius = stickRadius, center = leftStickCenter, style = Stroke(width = 1.5f * scale))
                val lNodeX = leftStickCenter.x + (lx * stickRadius * 0.7).toFloat()
                val lNodeY = leftStickCenter.y + (ly * stickRadius * 0.7).toFloat()
                drawCircle(color = if (keyboardControlEnabled) AresGreen else AresCyan, radius = 10f * scale, center = Offset(lNodeX, lNodeY))

                // Right Stick (Bottom Right Grip)
                val rightStickCenter = Offset(cx + 50f * scale, cy + 40f * scale)
                drawCircle(color = AresSurface, radius = stickRadius, center = rightStickCenter)
                if (btnRS) drawCircle(color = AresCyan.copy(alpha = 0.3f), radius = stickRadius, center = rightStickCenter)
                drawCircle(color = AresBorder, radius = stickRadius, center = rightStickCenter, style = Stroke(width = 1.5f * scale))
                val rNodeX = rightStickCenter.x + (rx * stickRadius * 0.7).toFloat()
                val rNodeY = rightStickCenter.y + (ry * stickRadius * 0.7).toFloat()
                drawCircle(color = if (keyboardControlEnabled) AresGreen else AresCyan, radius = 10f * scale, center = Offset(rNodeX, rNodeY))

                // Triggers (Top Edge L2/R2)
                val triggerW = 50f * scale
                val triggerH = 14f * scale
                val triggerTop = cy - 74f * scale
                drawRect(color = AresSurface, topLeft = Offset(cx - 110f * scale, triggerTop), size = Size(triggerW, triggerH))
                drawRect(color = if (keyboardControlEnabled) AresGreen else AresCyan, topLeft = Offset(cx - 110f * scale, triggerTop), size = Size(triggerW * lt.toFloat(), triggerH))
                drawRect(color = AresBorder, topLeft = Offset(cx - 110f * scale, triggerTop), size = Size(triggerW, triggerH), style = Stroke(width = 1.5f * scale))

                drawRect(color = AresSurface, topLeft = Offset(cx + 60f * scale, triggerTop), size = Size(triggerW, triggerH))
                drawRect(color = if (keyboardControlEnabled) AresGreen else AresCyan, topLeft = Offset(cx + 60f * scale, triggerTop), size = Size(triggerW * rt.toFloat(), triggerH))
                drawRect(color = AresBorder, topLeft = Offset(cx + 60f * scale, triggerTop), size = Size(triggerW, triggerH), style = Stroke(width = 1.5f * scale))

                // Bumpers (Top Edge L1/R1, directly below triggers)
                val bumperTop = cy - 60f * scale
                val bumperH = 10f * scale
                drawRoundRect(color = if (lb) AresCyan else AresSurface, topLeft = Offset(cx - 110f * scale, bumperTop), size = Size(triggerW, bumperH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * scale, 4f * scale))
                drawRoundRect(color = AresBorder, topLeft = Offset(cx - 110f * scale, bumperTop), size = Size(triggerW, bumperH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * scale, 4f * scale), style = Stroke(width = 1.5f * scale))

                drawRoundRect(color = if (rb) AresCyan else AresSurface, topLeft = Offset(cx + 60f * scale, bumperTop), size = Size(triggerW, bumperH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * scale, 4f * scale))
                drawRoundRect(color = AresBorder, topLeft = Offset(cx + 60f * scale, bumperTop), size = Size(triggerW, bumperH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * scale, 4f * scale), style = Stroke(width = 1.5f * scale))

                // Touchpad (Center Top)
                val touchPadW = 70f * scale
                val touchPadH = 45f * scale
                drawRoundRect(color = AresSurface, topLeft = Offset(cx - touchPadW / 2f, cy - 45f * scale), size = Size(touchPadW, touchPadH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * scale))
                drawRoundRect(color = AresBorder, topLeft = Offset(cx - touchPadW / 2f, cy - 45f * scale), size = Size(touchPadW, touchPadH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * scale), style = Stroke(width = 1.5f * scale))

                // Share / Options (Small buttons beside touchpad)
                val centerBtnW = 10f * scale
                val centerBtnH = 16f * scale
                // Share
                drawRoundRect(color = if (btnBack) AresCyan else AresSurface, topLeft = Offset(cx - 50f * scale, cy - 35f * scale), size = Size(centerBtnW, centerBtnH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * scale))
                drawRoundRect(color = AresBorder, topLeft = Offset(cx - 50f * scale, cy - 35f * scale), size = Size(centerBtnW, centerBtnH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * scale), style = Stroke(width = 1.5f * scale))
                // Options
                drawRoundRect(color = if (btnStart) AresCyan else AresSurface, topLeft = Offset(cx + 40f * scale, cy - 35f * scale), size = Size(centerBtnW, centerBtnH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * scale))
                drawRoundRect(color = AresBorder, topLeft = Offset(cx + 40f * scale, cy - 35f * scale), size = Size(centerBtnW, centerBtnH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * scale), style = Stroke(width = 1.5f * scale))
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "L (${"%.2f".format(lx)}, ${"%.2f".format(ly)})",
                    color = AresTextSecondary,
                    fontSize = 9.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 1
                )
                Text(
                    text = "R (${"%.2f".format(rx)}, ${"%.2f".format(ry)})",
                    color = AresTextSecondary,
                    fontSize = 9.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 1
                )
            }
            Text(
                text = "LT ${"%.2f".format(lt)}   RT ${"%.2f".format(rt)}",
                color = AresTextSecondary,
                fontSize = 9.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                maxLines = 1
            )
        }
    }
}

/** Combines the physical menu-button spellings used by Xbox and PlayStation telemetry. */
internal fun gamepadMenuPressed(primary: Double?, alternate: Double?): Boolean =
    (primary != null && primary.isFinite() && primary > 0.5) ||
        (alternate != null && alternate.isFinite() && alternate > 0.5)

/** Retains the independent menu-button topics arriving as live telemetry deltas. */
internal class GamepadMenuButtons(prefix: String) {
    private val start = "$prefix/Start"
    private val options = "$prefix/Options"
    private val back = "$prefix/Back"
    private val share = "$prefix/Share"
    private var startValue = false
    private var optionsValue = false
    private var backValue = false
    private var shareValue = false
    val startPressed: Boolean get() = startValue || optionsValue
    val backPressed: Boolean get() = backValue || shareValue

    fun accept(key: String, value: Double): Boolean {
        when (key) {
            start -> startValue = gamepadMenuPressed(value, null)
            options -> optionsValue = gamepadMenuPressed(value, null)
            back -> backValue = gamepadMenuPressed(value, null)
            share -> shareValue = gamepadMenuPressed(value, null)
            else -> return false
        }
        return true
    }
}
