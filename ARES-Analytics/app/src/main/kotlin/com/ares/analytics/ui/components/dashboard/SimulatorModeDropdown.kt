package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.*

@Composable
internal fun SimulatorModeDropdown(
    isFtc: Boolean,
    isConnected: Boolean,
    starting: Boolean,
    selectedOpMode: String?,
    onSelectedOpModeChanged: (String) -> Unit,
    teleOps: List<String>,
    autonomousOpModes: List<String>,
    availableAutos: List<String>,
    requestedAuto: String?,
    onRequestedAutoChanged: (String) -> Unit,
    onDisarmDrive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectorExpanded by remember { mutableStateOf(false) }
    var autoSelectorExpanded by remember { mutableStateOf(false) }

    Box(modifier) {
        Surface(
            onClick = {
                if (isConnected && !starting) {
                    if (isFtc && (teleOps.isNotEmpty() || autonomousOpModes.isNotEmpty())) selectorExpanded = true
                    if (!isFtc && availableAutos.isNotEmpty()) autoSelectorExpanded = true
                }
            },
            modifier = Modifier.fillMaxWidth().height(32.dp),
            color = AresSurfaceElevated,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, AresBorder),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (!isFtc) {
                        when {
                            !isConnected -> "FRC simulator is not running"
                            requestedAuto != null -> "Auto: $requestedAuto"
                            availableAutos.isEmpty() -> "Waiting for compiled autonomous catalog…"
                            else -> "Choose an autonomous routine"
                        }
                    } else {
                        selectedOpMode?.let { selected ->
                            val label = if (selected in autonomousOpModes) "Auto" else "TeleOp"
                            "$label: ${selected.substringAfterLast('.')}"
                        } ?: if (isConnected) "Waiting for OpMode lists…" else "Simulator is not running"
                    },
                    color = if (
                        (isFtc && selectedOpMode == null) ||
                        (!isFtc && requestedAuto == null)
                    ) AresTextSecondary else AresTextPrimary,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
                if (isFtc || (!isFtc && availableAutos.isNotEmpty())) {
                    Icon(Icons.Default.ArrowDropDown, null, tint = AresTextSecondary, modifier = Modifier.size(17.dp))
                }
            }
        }
        DropdownMenu(
            expanded = selectorExpanded,
            onDismissRequest = { selectorExpanded = false },
            modifier = Modifier.background(AresSurfaceElevated),
        ) {
            if (teleOps.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("TeleOp", color = AresTextSecondary, fontWeight = FontWeight.Bold) },
                    onClick = {},
                    enabled = false,
                )
            }
            teleOps.forEach { opMode ->
                DropdownMenuItem(
                    text = { Text(opMode.substringAfterLast('.'), color = AresTextPrimary) },
                    onClick = {
                        onSelectedOpModeChanged(opMode)
                        selectorExpanded = false
                    },
                )
            }
            if (autonomousOpModes.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Autonomous", color = AresTextSecondary, fontWeight = FontWeight.Bold) },
                    onClick = {},
                    enabled = false,
                )
            }
            autonomousOpModes.forEach { opMode ->
                DropdownMenuItem(
                    text = { Text(opMode.substringAfterLast('.'), color = AresTextPrimary) },
                    onClick = {
                        onSelectedOpModeChanged(opMode)
                        selectorExpanded = false
                        onDisarmDrive()
                    },
                )
            }
        }
        DropdownMenu(
            expanded = autoSelectorExpanded,
            onDismissRequest = { autoSelectorExpanded = false },
            modifier = Modifier.background(AresSurfaceElevated),
        ) {
            availableAutos.forEach { autoId ->
                DropdownMenuItem(
                    text = { Text(autoId, color = AresTextPrimary) },
                    onClick = {
                        onRequestedAutoChanged(autoId)
                        autoSelectorExpanded = false
                    },
                )
            }
        }
    }
}
