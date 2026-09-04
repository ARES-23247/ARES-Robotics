package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ares.analytics.di.KeyboardDriveState
import com.ares.analytics.service.XrpLinkService
import com.ares.analytics.service.XrpRequestedMode
import com.ares.analytics.service.project.persistence.AutonomousCatalogProjectRepository
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicit control surface for the leased XRP JSON link; it never publishes NT4 commands. */
@Composable
internal fun XrpSimulatorControlBar(
    xrpLink: XrpLinkService,
    keyboardDriveState: KeyboardDriveState,
    projectPath: String,
    isConnected: Boolean,
    isSimulatorProcessRunning: Boolean,
    isLaunchPreparationRunning: Boolean,
    canLaunchSimulator: Boolean,
    simulatorLaunchDisabledReason: String?,
    onLaunchSimulator: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val request by xrpLink.controlRequest.collectAsState()
    val peer by xrpLink.peerIdentity.collectAsState()
    val connectionError by xrpLink.connectionError.collectAsState()
    var autos by remember(projectPath) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selectedAuto by remember(projectPath) { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(projectPath) {
        autos = withContext(Dispatchers.IO) {
            AutonomousCatalogProjectRepository().load(projectPath).getOrNull()?.entries.orEmpty()
                .filter { it.enabled }
                .sortedWith(compareBy({ it.sortOrder }, { it.entryId }))
                .map { it.entryId to it.displayName }
        }
        selectedAuto = selectedAuto?.takeIf { chosen -> autos.any { it.first == chosen } }
            ?: autos.firstOrNull()?.first
    }

    Surface(modifier = modifier, color = AresSurface, border = androidx.compose.foundation.BorderStroke(1.dp, AresBorder)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "XRP simulator · " + when {
                        isConnected -> "Connected to ${peer?.projectId ?: "verified project"}"
                        isSimulatorProcessRunning -> "Connecting"
                        else -> "Offline"
                    },
                )
                if (!isSimulatorProcessRunning) {
                    Button(
                        onClick = onLaunchSimulator,
                        enabled = canLaunchSimulator && !isLaunchPreparationRunning,
                    ) { Text(if (isLaunchPreparationRunning) "Preparing…" else "Launch simulator") }
                }
                Button(
                    onClick = {
                        keyboardDriveState.disarm()
                        xrpLink.requestInitialize()
                    },
                    enabled = isConnected && request.mode != XrpRequestedMode.INITIALIZE,
                ) { Text("Initialize / recover") }
                Button(
                    onClick = {
                        xrpLink.requestTeleOp()
                        keyboardDriveState.enabled = true
                    },
                    enabled = isConnected && request.mode != XrpRequestedMode.TELEOP,
                ) { Text("Start TeleOp") }
                Column {
                    OutlinedButton(onClick = { menuOpen = true }, enabled = autos.isNotEmpty()) {
                        Text(autos.firstOrNull { it.first == selectedAuto }?.second ?: "No autonomous routine")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        autos.forEach { (id, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { selectedAuto = id; menuOpen = false })
                        }
                    }
                }
                Button(
                    onClick = {
                        keyboardDriveState.disarm()
                        selectedAuto?.let(xrpLink::requestAutonomous)
                    },
                    enabled = isConnected && selectedAuto != null,
                ) { Text("Start autonomous") }
                OutlinedButton(
                    onClick = {
                        keyboardDriveState.disarm()
                        xrpLink.requestStop()
                    },
                    enabled = isConnected && request.mode != XrpRequestedMode.STOPPED,
                ) { Text("Stop") }
            }
            simulatorLaunchDisabledReason?.takeIf { !canLaunchSimulator }?.let { Text(it) }
            connectionError?.takeIf { !isConnected }?.let { Text(it, color = AresError) }
            Text(
                when (request.mode) {
                    XrpRequestedMode.STOPPED -> "Stopped · outputs are neutral"
                    XrpRequestedMode.INITIALIZE -> "Initialized · faults cleared only after a successful neutral write"
                    XrpRequestedMode.TELEOP -> "TeleOp · leased keyboard/gamepad control"
                    XrpRequestedMode.AUTONOMOUS -> "Autonomous · ${autos.firstOrNull { it.first == request.autonomousId }?.second ?: request.autonomousId}"
                },
            )
        }
    }
}
