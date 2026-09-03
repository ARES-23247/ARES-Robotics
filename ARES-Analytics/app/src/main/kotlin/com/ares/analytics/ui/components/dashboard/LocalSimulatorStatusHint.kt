package com.ares.analytics.ui.components.dashboard

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.models.Session
import com.ares.analytics.ui.theme.*

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
    val message = when {
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
        !isConnected -> "Launch the physics server here. When it connects, choose a TeleOp and Start driving."
        !isFtc && autonomousDisplayState == FrcAutonomousDisplayState.COMPLETE ->
            "${robotSelectedAuto.ifBlank { requestedAuto ?: "Generated autonomous" }} completed; outputs are neutral. Stop exits Autonomous mode."
        !isFtc && autonomousDisplayState == FrcAutonomousDisplayState.BLOCKED ->
            "${robotSelectedAuto.ifBlank { requestedAuto ?: "Generated autonomous" }} was blocked and outputs were neutralized. Stop, fix the reported cause, then retry."
        !isFtc && autonomousDisplayState == FrcAutonomousDisplayState.RUNNING ->
            "Running ${robotSelectedAuto.ifBlank { requestedAuto ?: "generated autonomous" }}. Stop returns every output to neutral."
        !isFtc && frcDriverStationState == FRC_WAITING_FOR_CONTROL_STATE ->
            "ARES is establishing a fresh neutral control lease before it enables the simulation-only Driver Station."
        !isFtc && isKeyboardDriveArmed ->
            "FRC TeleOp is enabled and field-centric control is armed: W drives toward the opposing station. Loopback only."
        !isFtc ->
            if (availableAutos.isEmpty()) {
                "Waiting for the robot's compiled autonomous catalog. Start driving enables TeleOp; no external WPILib window is required."
            } else {
                "Choose an autonomous above, then Run auto—or Start driving for TeleOp. No external WPILib window is required."
            }
        startFailure != null -> "$startFailure. Stop, confirm the selected TeleOp, and try again."
        !isRunning -> "Choose a TeleOp or Autonomous OpMode, then start it. The simulator can be online while no OpMode is running."
        isFtcAutonomousSelection -> "Running the selected FTC Autonomous OpMode. Stop returns every output to neutral."
        isKeyboardDriveArmed && !receiverReady ->
            "${simulatorDriveReceiverStatus(driveReceiverStatusCode)}. ARES is sending neutral frames to restore the safe control lease."
        useGamepad -> "Move the sticks directly while armed. Dashboard drive frames are blocked for non-loopback targets."
        else -> "${activeOpModeDisplayName ?: selectedOpMode?.substringAfterLast('.')}: W drives toward the opposing station, A/D strafe, and ←/→ rotate. Loopback only."
    }

    val textColor = when {
        recordingFailure != null -> AresError
        recordingSession != null -> AresAmber
        recordingMessage != null -> AresGreen
        isRunning && isKeyboardDriveArmed -> AresGreen
        else -> AresTextSecondary
    }

    Text(
        text = message,
        color = textColor,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        maxLines = 1,
        modifier = modifier,
    )
}
