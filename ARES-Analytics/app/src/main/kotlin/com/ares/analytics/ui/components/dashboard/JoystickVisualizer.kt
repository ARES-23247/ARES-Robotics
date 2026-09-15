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
fun JoystickVisualizer(
    nt4ClientService: Nt4ClientService,
    modifier: Modifier = Modifier
) {
    // ...
}
@Composable
fun JoystickVisualizer(
    currentFrame: ReplayFrame?,
    nt4ClientService: Nt4ClientService? = null,
    keyboardDriveState: KeyboardDriveState? = null,
    gamepadService: GamepadService? = null,
    onOpenKeybindings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val keyboardState = keyboardDriveState ?: remember { KeyboardDriveState() }
    val keyboardControlEnabled = keyboardState.enabled
    val gamepad1StateFlow = gamepadService?.gamepad1State
    val gamepad2StateFlow = gamepadService?.gamepad2State

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AresSurface)
            .border(
                width = if (keyboardControlEnabled) 2.dp else 1.dp,
                color = if (keyboardControlEnabled) AresGreen else AresBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Gamepad Monitor",
            color = AresTextPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onOpenKeybindings,
                enabled = currentFrame == null,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AresSurfaceElevated),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    "Configure controls",
                    color = AresTextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = {
                    keyboardState.releaseAll()
                    keyboardState.useGamepad = !keyboardState.useGamepad
                },
                enabled = currentFrame == null,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (keyboardState.useGamepad) AresCyan else AresSurfaceElevated,
                    contentColor = if (keyboardState.useGamepad) AresOnAccent else AresTextPrimary,
                ),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    if (keyboardState.useGamepad) "Input: Gamepad" else "Input: Keyboard",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (nt4ClientService != null) {
                Button(
                    onClick = {
                        if (keyboardControlEnabled) keyboardState.disarm() else keyboardState.enabled = true
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (keyboardControlEnabled) AresGold else AresCyan,
                        contentColor = AresOnAccent,
                    ),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        if (keyboardControlEnabled) "Disarm control" else "Arm control",
                        color = AresBackground,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (currentFrame != null) {
            Text(
                "Replay snapshot — controls are view-only.",
                color = AresCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (keyboardControlEnabled) {
            Text(
                if (keyboardState.useGamepad) {
                    "Move the gamepad sticks directly while armed. Drive publication is restricted to the loopback simulator."
                } else {
                    "Field-centric keyboard: W drives toward the opposing station and A/D strafe. Loopback simulator only."
                },
                color = AresGold,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SingleGamepadVisualizer(
                title = "Gamepad 1 (Driver)",
                gamepadId = "Gamepad1",
                currentFrame = currentFrame,
                nt4ClientService = nt4ClientService,
                keyboardControlEnabled = keyboardControlEnabled,
                keyboardState = keyboardState,
                gamepadStateFlow = gamepad1StateFlow,
                modifier = Modifier.weight(1f)
            )
            SingleGamepadVisualizer(
                title = "Gamepad 2 (Operator)",
                gamepadId = "Gamepad2",
                currentFrame = currentFrame,
                nt4ClientService = nt4ClientService,
                keyboardControlEnabled = false,
                keyboardState = null,
                gamepadStateFlow = gamepad2StateFlow,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
