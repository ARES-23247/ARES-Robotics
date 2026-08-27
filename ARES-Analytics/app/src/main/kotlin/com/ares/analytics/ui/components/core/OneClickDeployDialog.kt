package com.ares.analytics.ui.components.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DeployExecutionPhase
import com.ares.analytics.service.DeployExecutionState
import com.ares.analytics.shared.League
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary

/**
 * 1-Click Wireless ADB and OTA Robot Deployment Dialog.
 *
 * Provides real-time visual progress through the connection, compilation, and APK flashing stages
 * without requiring the student to open a command line or Android Studio.
 */
@Composable
fun OneClickDeployDialog(
    state: DeployExecutionState,
    projectPath: String,
    league: League,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            if (state.phase != DeployExecutionPhase.CONNECTING &&
                state.phase != DeployExecutionPhase.BUILDING &&
                state.phase != DeployExecutionPhase.INSTALLING
            ) {
                onDismiss()
            }
        },
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    when (state.phase) {
                        DeployExecutionPhase.CONNECTING -> Icons.Default.Wifi
                        DeployExecutionPhase.BUILDING, DeployExecutionPhase.INSTALLING -> Icons.Default.CloudUpload
                        DeployExecutionPhase.SUCCEEDED -> Icons.Default.CheckCircle
                        DeployExecutionPhase.FAILED, DeployExecutionPhase.CANCELED -> Icons.Default.Error
                        DeployExecutionPhase.IDLE -> Icons.Default.HourglassTop
                    },
                    contentDescription = null,
                    tint = when (state.phase) {
                        DeployExecutionPhase.SUCCEEDED -> AresGreen
                        DeployExecutionPhase.FAILED -> AresError
                        else -> AresCyan
                    },
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = when (state.phase) {
                        DeployExecutionPhase.CONNECTING -> "Connecting to Robot..."
                        DeployExecutionPhase.BUILDING -> "Verifying and Building Robot Code..."
                        DeployExecutionPhase.INSTALLING -> "Installing Robot Code..."
                        DeployExecutionPhase.SUCCEEDED -> "Deployment Complete!"
                        DeployExecutionPhase.FAILED -> "Deployment Failed"
                        DeployExecutionPhase.CANCELED -> "Deployment Canceled"
                        DeployExecutionPhase.IDLE -> "Confirm physical robot deployment"
                    },
                    color = AresTextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.phase == DeployExecutionPhase.IDLE) {
                    Text(
                        text = if (league == League.FTC) {
                            "ARES will connect only to Control Hub 192.168.43.1:5555, regenerate project plumbing, run FTC and simulator tests, build the APK, install it, and verify the package."
                        } else {
                            "ARES will regenerate project plumbing, run the FRC tests and build checks, then deploy through this project's configured RoboRIO target."
                        },
                        color = AresTextPrimary,
                        fontSize = 13.sp,
                    )
                    Text(
                        text = "Project: $projectPath",
                        color = AresTextSecondary,
                        fontSize = 12.sp,
                    )
                    Text(
                        text = "This installs code on physical hardware. It does not enable the robot or start an OpMode. Keep the robot safely supported and disabled, then verify the selected program before enabling.",
                        color = AresError,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = if (state.phase == DeployExecutionPhase.IDLE) {
                        "Choose Deploy now only when the physical target and project above are correct."
                    } else {
                        state.message
                    },
                    color = AresTextSecondary,
                    fontSize = 13.sp,
                )

                if (state.phase == DeployExecutionPhase.CONNECTING ||
                    state.phase == DeployExecutionPhase.BUILDING ||
                    state.phase == DeployExecutionPhase.INSTALLING
                ) {
                    LinearProgressIndicator(
                        progress = { state.progressPercent },
                        modifier = Modifier.fillMaxWidth(),
                        color = AresCyan,
                        trackColor = AresBackground,
                    )
                }

                if (state.phase != DeployExecutionPhase.IDLE) Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = AresBackground,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        DeployStepItem(
                            stepName = if (league == League.FTC) "1. Verify the selected Control Hub" else "1. Verify the selected robot project",
                            isDone = state.phase == DeployExecutionPhase.BUILDING ||
                                state.phase == DeployExecutionPhase.INSTALLING ||
                                state.phase == DeployExecutionPhase.SUCCEEDED,
                            isRunning = state.phase == DeployExecutionPhase.CONNECTING,
                        )
                        DeployStepItem(
                            stepName = "2. Generate, verify, test, and build",
                            isDone = state.phase == DeployExecutionPhase.INSTALLING ||
                                state.phase == DeployExecutionPhase.SUCCEEDED,
                            isRunning = state.phase == DeployExecutionPhase.BUILDING,
                        )
                        DeployStepItem(
                            stepName = "3. Install and verify robot code",
                            isDone = state.phase == DeployExecutionPhase.SUCCEEDED,
                            isRunning = state.phase == DeployExecutionPhase.INSTALLING,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (state.phase == DeployExecutionPhase.IDLE) {
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = AresError, contentColor = AresOnAccent),
                ) {
                    Text("Deploy now")
                }
            } else if (state.phase == DeployExecutionPhase.SUCCEEDED ||
                state.phase == DeployExecutionPhase.FAILED ||
                state.phase == DeployExecutionPhase.CANCELED
            ) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                ) {
                    Text("Close")
                }
            } else {
                OutlinedButton(onClick = onCancel) {
                    Text("Cancel Deploy")
                }
            }
        },
        dismissButton = {
            if (state.phase == DeployExecutionPhase.IDLE) {
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        },
        containerColor = AresSurface,
    )
}

@Composable
private fun DeployStepItem(stepName: String, isDone: Boolean, isRunning: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stepName,
            color = when {
                isDone -> AresGreen
                isRunning -> AresCyan
                else -> AresTextSecondary
            },
            fontSize = 12.sp,
            fontWeight = if (isRunning) FontWeight.Bold else FontWeight.Normal,
        )
        if (isDone) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AresGreen, modifier = Modifier.size(16.dp))
        } else if (isRunning) {
            CircularProgressIndicator(color = AresCyan, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        }
    }
}
