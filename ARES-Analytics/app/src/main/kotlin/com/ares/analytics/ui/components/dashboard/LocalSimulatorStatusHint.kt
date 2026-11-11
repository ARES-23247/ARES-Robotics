package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.di.KeyboardDriveState
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.Session
import com.ares.analytics.ui.help.StudentFriendlyDiagnosticCallout
import com.ares.analytics.ui.help.translateDiagnosticForStudent
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

internal fun formatLocalSimulatorStatusHint(
    recordingFailure: String? = null,
    recordingSession: Session? = null,
    recordingMessage: String? = null,
    isConnected: Boolean,
    isLaunchPreparationRunning: Boolean = false,
    isSimulatorProcessRunning: Boolean = false,
    canLaunchSimulator: Boolean = true,
    simulatorLaunchDisabledReason: String? = null,
    launchRequiresVerification: Boolean = false,
    isFtc: Boolean = true,
    autonomousDisplayState: FrcAutonomousDisplayState = FrcAutonomousDisplayState.INACTIVE,
    robotSelectedAuto: String = "",
    requestedAuto: String? = null,
    availableAutos: List<String> = emptyList(),
    frcDriverStationState: String? = null,
    isKeyboardDriveArmed: Boolean = false,
    useGamepad: Boolean = false,
    startFailure: String? = null,
    isRunning: Boolean = false,
    isFtcAutonomousSelection: Boolean = false,
    receiverReady: Boolean = false,
    driveReceiverStatusCode: Int? = null,
    activeOpModeDisplayName: String? = null,
    selectedOpMode: String? = null,
): String = when {
    recordingFailure != null -> recordingFailure
    recordingSession != null -> recordingMessage ?: "Recording this simulation run. Stop and save before comparing."
    recordingMessage != null -> recordingMessage
    !isConnected && isLaunchPreparationRunning ->
        "Building and verifying the current robot project. The first launch can take about a minute; the simulator starts automatically when this finishes."
    !isConnected && isSimulatorProcessRunning -> "Simulator process started. Waiting for NT4 on 127.0.0.1:5810…"
    !isConnected && !canLaunchSimulator -> simulatorLaunchDisabledReason
        ?.takeIf { it.isNotBlank() }
        ?: "Verify & build the current robot project before launching its simulator."
    !isConnected && launchRequiresVerification -> "Verify the current project, then launch its simulator automatically. No code is deployed."
    !isConnected -> "Step 1: Click 'Launch simulator' above to start the local physics server."
    !isFtc && autonomousDisplayState == FrcAutonomousDisplayState.COMPLETE ->
        "${robotSelectedAuto.ifBlank { requestedAuto ?: "Generated autonomous" }} completed; outputs are neutral. Stop exits Autonomous mode."
    !isFtc && autonomousDisplayState == FrcAutonomousDisplayState.BLOCKED ->
        "${robotSelectedAuto.ifBlank { requestedAuto ?: "Generated autonomous" }} was blocked and outputs were neutralized. Stop, fix the reported cause, then retry."
    !isFtc && autonomousDisplayState == FrcAutonomousDisplayState.RUNNING ->
        "Running ${robotSelectedAuto.ifBlank { requestedAuto ?: "generated autonomous" }}. Stop returns every output to neutral."
    !isFtc && frcDriverStationState == FRC_WAITING_FOR_CONTROL_STATE ->
        "ARES is establishing a fresh neutral control lease before it enables the simulation-only Driver Station."
    startFailure != null -> "$startFailure. Stop, confirm the selected TeleOp, and try again."
    !isFtc && !isRunning ->
        if (availableAutos.isEmpty()) {
            "Step 2: Click 'Start driving' to enable TeleOp (no external WPILib Driver Station window needed)."
        } else {
            "Step 2: Choose an autonomous above and click 'Run auto', or click 'Start driving' for TeleOp."
        }
    !isRunning -> "Step 2: Choose a TeleOp or Autonomous OpMode above, then click 'Start driving'."
    isFtcAutonomousSelection -> "Running the selected FTC Autonomous OpMode. Stop returns every output to neutral."
    !isKeyboardDriveArmed ->
        "Controls are currently DISARMED (clicking outside or opening a drawer disarms for safety). Click 'Arm control' above to drive."
    !receiverReady ->
        "${simulatorDriveReceiverStatus(driveReceiverStatusCode)}. ARES is sending neutral frames to restore the safe control lease."
    useGamepad -> "Gamepad armed: move the controller sticks directly. Loopback simulation only."
    !isFtc -> "FRC TeleOp armed: W drives toward the opposing station, A/D strafe, and ←/→ rotate. Loopback only."
    else -> "${activeOpModeDisplayName ?: selectedOpMode?.substringAfterLast('.') ?: "TeleOp"} armed: W drives toward the opposing station, A/D strafe, and ←/→ rotate. Loopback only."
}

@Composable
internal fun LocalSimulatorStatusHint(
    recordingFailure: String?,
    recordingSession: Session?,
    recordingMessage: String?,
    isConnected: Boolean,
    isLaunchPreparationRunning: Boolean,
    isSimulatorProcessRunning: Boolean,
    canLaunchSimulator: Boolean,
    simulatorLaunchDisabledReason: String?,
    launchRequiresVerification: Boolean,
    isFtc: Boolean,
    autonomousDisplayState: FrcAutonomousDisplayState,
    robotSelectedAuto: String,
    requestedAuto: String?,
    availableAutos: List<String>,
    frcDriverStationState: String?,
    isKeyboardDriveArmed: Boolean,
    useGamepad: Boolean,
    startFailure: String?,
    isRunning: Boolean,
    isFtcAutonomousSelection: Boolean,
    receiverReady: Boolean,
    driveReceiverStatusCode: Int?,
    activeOpModeDisplayName: String?,
    selectedOpMode: String?,
    modifier: Modifier = Modifier,
) {
    val message = formatLocalSimulatorStatusHint(
        recordingFailure = recordingFailure,
        recordingSession = recordingSession,
        recordingMessage = recordingMessage,
        isConnected = isConnected,
        isLaunchPreparationRunning = isLaunchPreparationRunning,
        isSimulatorProcessRunning = isSimulatorProcessRunning,
        canLaunchSimulator = canLaunchSimulator,
        simulatorLaunchDisabledReason = simulatorLaunchDisabledReason,
        launchRequiresVerification = launchRequiresVerification,
        isFtc = isFtc,
        autonomousDisplayState = autonomousDisplayState,
        robotSelectedAuto = robotSelectedAuto,
        requestedAuto = requestedAuto,
        availableAutos = availableAutos,
        frcDriverStationState = frcDriverStationState,
        isKeyboardDriveArmed = isKeyboardDriveArmed,
        useGamepad = useGamepad,
        startFailure = startFailure,
        isRunning = isRunning,
        isFtcAutonomousSelection = isFtcAutonomousSelection,
        receiverReady = receiverReady,
        driveReceiverStatusCode = driveReceiverStatusCode,
        activeOpModeDisplayName = activeOpModeDisplayName,
        selectedOpMode = selectedOpMode,
    )

    val textColor = when {
        recordingFailure != null || startFailure != null -> AresError
        recordingSession != null || (isRunning && !isFtcAutonomousSelection && !isKeyboardDriveArmed) -> AresAmber
        recordingMessage != null -> AresGreen
        isRunning && isKeyboardDriveArmed -> AresGreen
        else -> AresTextSecondary
    }

    val showKeycapBar = isConnected && isRunning && !isFtcAutonomousSelection && !useGamepad
    val diagnosticNeedsTranslation = recordingFailure != null ||
        startFailure != null ||
        (!isFtc && autonomousDisplayState == FrcAutonomousDisplayState.BLOCKED) ||
        (!isConnected && !canLaunchSimulator && !simulatorLaunchDisabledReason.isNullOrBlank())

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = message,
            color = textColor,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            maxLines = 1,
        )
        if (diagnosticNeedsTranslation) {
            StudentFriendlyDiagnosticCallout(
                explanation = translateDiagnosticForStudent(message),
            )
        }
        if (showKeycapBar) {
            SimulatorKeycapLegendRow(isArmed = isKeyboardDriveArmed && receiverReady)
        }
    }
}

@Composable
private fun SimulatorKeycapLegendRow(isArmed: Boolean) {
    val pillBorderColor: Color = if (isArmed) AresGreen.copy(alpha = 0.55f) else AresBorder
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isArmed) "KEYBOARD ACTIVE:" else "KEYBOARD MAP (ARM TO DRIVE):",
            color = if (isArmed) AresGreen else AresAmber,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        defaultSimulatorKeycapGuide.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.keys,
                    color = if (isArmed) AresCyan else AresTextPrimary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(AresSurfaceElevated, RoundedCornerShape(4.dp))
                        .border(1.dp, pillBorderColor, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
                Text(
                    text = item.action,
                    color = AresTextSecondary,
                    fontSize = 9.sp,
                )
            }
        }
    }
}
