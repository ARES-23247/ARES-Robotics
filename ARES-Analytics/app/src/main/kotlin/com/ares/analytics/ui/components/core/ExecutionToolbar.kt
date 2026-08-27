package com.ares.analytics.ui.components.core

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.*
import com.ares.analytics.service.CliDriverLauncher
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor

enum class TargetSelection(val label: String) {
    LIVE_ROBOT("Live Robot"),
    LOCAL_SIM("Local Sim")
}

// rememberPlainTooltipPositionProvider: the recommended
// rememberTooltipPositionProvider is not shipped by this Compose
// version; migrate at the next Compose bump.
@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExecutionToolbar(
    projectPath: String,
    targetSelection: TargetSelection,
    targetIp: String,
    isLiveRobotOnline: Boolean,
    isLocalSimOnline: Boolean,
    isBuildRunning: Boolean,
    isSimRunning: Boolean,
    buildEnabled: Boolean = true,
    buildDisabledReason: String? = null,
    simulationEnabled: Boolean = true,
    simulationDisabledReason: String? = null,
    onTargetChanged: (TargetSelection) -> Unit,
    onTargetIpChanged: (String) -> Unit,
    onRunBuild: () -> Unit,
    onRunSim: () -> Unit,
    onStopAll: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    var targetAddressDialogOpen by remember { mutableStateOf(false) }
    var editedTargetIp by remember(targetIp) { mutableStateOf(targetIp) }
    val launchCli: () -> Unit = {
        try {
            CliDriverLauncher.launch(projectPath, targetIp)
        } catch (e: Exception) {
            System.err.println("Failed to launch CLI: ${e.message}")
        }
    }
    Row(
        modifier = modifier
            .background(AresSurfaceElevated, RoundedCornerShape(8.dp))
            .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Target Dropdown
        var dropdownExpanded by remember { mutableStateOf(false) }
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { dropdownExpanded = true }
                    .background(AresSurface)
                    .border(1.dp, AresBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (targetSelection == TargetSelection.LIVE_ROBOT) Icons.Default.PrecisionManufacturing else Icons.Default.Computer,
                    contentDescription = null,
                    tint = AresCyan,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (compact && targetSelection == TargetSelection.LIVE_ROBOT) "Robot" else targetSelection.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = AresTextPrimary
                )

                // Status uses text and shape; color is supplemental.
                val isOnline = if (targetSelection == TargetSelection.LIVE_ROBOT) isLiveRobotOnline else isLocalSimOnline
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(if (isOnline) AresGreen else AresTextSecondary.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)
                )
                if (!compact) {
                    Text(
                        if (isOnline) "Online" else "Offline",
                        color = AresTextSecondary,
                        fontSize = 11.sp,
                    )
                }

                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = AresTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }

            DropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false },
                modifier = Modifier.background(AresSurfaceElevated).border(1.dp, AresBorder)
            ) {
                TargetSelection.entries.forEach { target ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (target == TargetSelection.LIVE_ROBOT) Icons.Default.PrecisionManufacturing else Icons.Default.Computer,
                                    contentDescription = null,
                                    tint = if (target == targetSelection) AresCyan else AresTextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(target.label, color = AresTextPrimary)
                                Spacer(modifier = Modifier.width(8.dp))
                                val isTargetOnline = if (target == TargetSelection.LIVE_ROBOT) isLiveRobotOnline else isLocalSimOnline
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(if (isTargetOnline) AresGreen else AresTextSecondary.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(if (isTargetOnline) "Online" else "Offline", color = AresTextSecondary, fontSize = 11.sp)
                            }
                        },
                        onClick = {
                            onTargetChanged(target)
                            dropdownExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(4.dp))
        VerticalDivider(modifier = Modifier.height(24.dp), color = AresBorder)
        Spacer(modifier = Modifier.width(4.dp))

        // Compile-only project verification. Physical deployment is always a separate workflow.
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = {
                PlainTooltip {
                    Text(
                        if (!buildEnabled && !isBuildRunning) {
                            buildDisabledReason ?: "Complete the required Robot Studio stages before verification."
                        } else {
                            "Verify generated ownership, run tests, and build a package. Nothing is deployed. (Ctrl+B)"
                        }
                    )
                }
            },
            state = rememberTooltipState()
        ) {
            Button(
                onClick = onRunBuild,
                enabled = buildEnabled && !isBuildRunning,
                modifier = Modifier.height(32.dp),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) {
                if (isBuildRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        color = AresOnAccent,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    if (isBuildRunning) "Verifying" else if (compact) "Build" else "Verify & build",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
            }
        }

        // Launch Simulator Button
        val simIconTint by animateColorAsState(targetValue = if (isSimRunning) AresGreen else AresCyan)
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = {
                PlainTooltip {
                    Text(
                        if (!simulationEnabled && !isSimRunning) {
                            simulationDisabledReason ?: "Verify the current project before simulation."
                        } else {
                            "Launch Desktop Simulator (Ctrl+D)"
                        }
                    )
                }
            },
            state = rememberTooltipState()
        ) {
            IconButton(
                onClick = onRunSim,
                enabled = simulationEnabled && !isSimRunning,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DesktopWindows,
                    contentDescription = "Launch Simulator",
                    tint = simIconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))
        VerticalDivider(modifier = Modifier.height(20.dp), color = AresBorder.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.width(4.dp))

        if (compact) {
            Box {
                IconButton(onClick = { overflowExpanded = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, "Execution options", tint = AresTextPrimary, modifier = Modifier.size(19.dp))
                }
                DropdownMenu(
                    expanded = overflowExpanded,
                    onDismissRequest = { overflowExpanded = false },
                    modifier = Modifier.background(AresSurfaceElevated),
                ) {
                    DropdownMenuItem(
                        text = { Text("Target address: $targetIp", color = AresTextPrimary) },
                        leadingIcon = { Icon(Icons.Default.SettingsEthernet, null, tint = AresCyan) },
                        onClick = {
                            overflowExpanded = false
                            editedTargetIp = targetIp
                            targetAddressDialogOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Open CLI driver", color = AresTextPrimary) },
                        leadingIcon = { Icon(Icons.Default.Keyboard, null, tint = AresCyan) },
                        onClick = {
                            overflowExpanded = false
                            launchCli()
                        },
                    )
                }
            }
        } else {
            // Target IP input and CLI Driver Launch
            BasicTextField(
                value = targetIp,
                onValueChange = onTargetIpChanged,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = AresTextPrimary, fontSize = 12.sp),
                cursorBrush = SolidColor(AresCyan),
                modifier = Modifier
                    .width(90.dp)
                    .height(26.dp)
                    .background(AresSurface, RoundedCornerShape(4.dp))
                    .border(1.dp, AresBorder.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
                decorationBox = { innerTextField ->
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        innerTextField()
                    }
                }
            )

            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Launch Interactive CLI Driver Shell") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = launchCli, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Keyboard,
                        contentDescription = "Launch CLI Driver",
                        tint = AresCyan,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Stop Button
        val isAnyRunning = isBuildRunning || isSimRunning
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("Stop the Analytics-managed build or simulator (Ctrl+Shift+K)") } },
            state = rememberTooltipState()
        ) {
            IconButton(
                onClick = onStopAll,
                enabled = isAnyRunning,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Stop",
                    tint = if (isAnyRunning) AresError else AresTextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    if (targetAddressDialogOpen) {
        AlertDialog(
            onDismissRequest = { targetAddressDialogOpen = false },
            title = { Text("Live robot address") },
            text = {
                OutlinedTextField(
                    value = editedTargetIp,
                    onValueChange = { editedTargetIp = it },
                    label = { Text("IP address or host") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onTargetIpChanged(editedTargetIp.trim())
                    targetAddressDialogOpen = false
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { targetAddressDialogOpen = false }) { Text("Cancel") }
            },
        )
    }
}
