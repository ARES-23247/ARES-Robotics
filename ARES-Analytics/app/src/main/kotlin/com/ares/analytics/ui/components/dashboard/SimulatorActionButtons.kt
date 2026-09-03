package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.models.Session
import com.ares.analytics.ui.theme.*

@Composable
internal fun SimulatorActionButtons(
    primaryAction: LocalSimulatorPrimaryAction,
    canLaunchSimulator: Boolean,
    isFtc: Boolean,
    selectedOpMode: String?,
    isFtcAutonomousSelection: Boolean,
    onPrimaryActionClick: () -> Unit,
    isConnected: Boolean,
    requestedAuto: String?,
    starting: Boolean,
    isAutonomousRunning: Boolean,
    autonomousDisplayState: FrcAutonomousDisplayState,
    onRunFrcAutoClick: () -> Unit,
    recordingSession: Session?,
    recordingBusy: Boolean,
    isRunning: Boolean,
    onToggleRecording: () -> Unit,
    onStopClick: () -> Unit,
    useGamepad: Boolean,
    onToggleGamepad: () -> Unit,
    isKeyboardDriveArmed: Boolean,
    receiverReady: Boolean,
    onToggleArmDrive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Primary Launch / Start Driving button
        Button(
            onClick = onPrimaryActionClick,
            enabled = when (primaryAction) {
                LocalSimulatorPrimaryAction.LAUNCH_SIMULATOR,
                LocalSimulatorPrimaryAction.VERIFY_AND_LAUNCH -> canLaunchSimulator
                LocalSimulatorPrimaryAction.START_DRIVING -> !isFtc || selectedOpMode != null
                else -> false
            },
            modifier = Modifier.height(32.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 4.dp),
        ) {
            if (
                primaryAction == LocalSimulatorPrimaryAction.WAIT_FOR_CONNECTION ||
                primaryAction == LocalSimulatorPrimaryAction.VERIFYING_PROJECT ||
                primaryAction == LocalSimulatorPrimaryAction.STARTING_TELEOP
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), color = AresOnAccent, strokeWidth = 2.dp)
            } else {
                Icon(
                    if (
                        primaryAction == LocalSimulatorPrimaryAction.LAUNCH_SIMULATOR ||
                        primaryAction == LocalSimulatorPrimaryAction.VERIFY_AND_LAUNCH
                    ) {
                        Icons.Default.DesktopWindows
                    } else {
                        Icons.Default.PlayArrow
                    },
                    null,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                if (primaryAction == LocalSimulatorPrimaryAction.START_DRIVING && isFtcAutonomousSelection) {
                    "Run autonomous"
                } else {
                    primaryAction.label
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // FRC Run Auto button
        if (!isFtc) {
            OutlinedButton(
                onClick = onRunFrcAutoClick,
                enabled = isConnected && requestedAuto != null && !starting && !isAutonomousRunning,
                modifier = Modifier.height(32.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AresCyan),
                border = BorderStroke(1.dp, AresCyan),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    when (autonomousDisplayState) {
                        FrcAutonomousDisplayState.COMPLETE -> "Auto complete"
                        FrcAutonomousDisplayState.BLOCKED -> "Auto blocked"
                        FrcAutonomousDisplayState.RUNNING -> "Auto running"
                        FrcAutonomousDisplayState.INACTIVE -> "Run auto"
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Recording button
        OutlinedButton(
            onClick = onToggleRecording,
            enabled = isConnected && (isRunning || isAutonomousRunning) && !recordingBusy,
            modifier = Modifier.height(32.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (recordingSession != null) AresError else AresCyan,
            ),
            border = BorderStroke(1.dp, if (recordingSession != null) AresError else AresCyan),
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
        ) {
            if (recordingBusy) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    if (recordingSession == null) Icons.Default.FiberManualRecord else Icons.Default.Stop,
                    contentDescription = if (recordingSession == null) "Record simulation run" else "Stop and save simulation run",
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(if (recordingSession == null) "Record run" else "Stop & save", fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }

        // Stop button
        OutlinedButton(
            onClick = onStopClick,
            enabled = isConnected,
            modifier = Modifier.height(32.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AresError),
            border = BorderStroke(1.dp, AresError),
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(
                Icons.Default.Stop,
                if (isFtc) "Stop simulated OpMode" else "Disable simulated FRC Driver Station",
                modifier = Modifier.size(16.dp),
            )
        }

        // Input mode toggle (keyboard / gamepad)
        OutlinedButton(
            onClick = onToggleGamepad,
            enabled = isRunning,
            modifier = Modifier.height(32.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AresTextPrimary),
            border = BorderStroke(1.dp, AresBorder),
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(
                if (useGamepad) Icons.Default.Gamepad else Icons.Default.Keyboard,
                if (useGamepad) "Use keyboard input" else "Use gamepad input",
                modifier = Modifier.size(16.dp),
            )
        }

        // Arm drive button
        OutlinedButton(
            onClick = onToggleArmDrive,
            enabled = isRunning,
            modifier = Modifier.height(32.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (isKeyboardDriveArmed) AresGreen else AresTextPrimary,
            ),
            border = BorderStroke(1.dp, if (isKeyboardDriveArmed) AresGreen else AresBorder),
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                when {
                    !isKeyboardDriveArmed -> "Arm control"
                    receiverReady -> "ARMED"
                    else -> "RECONNECTING"
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
