package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.di.KeyboardDriveState
import com.ares.analytics.service.XrpLinkService
import com.ares.analytics.service.XrpPeerIdentity
import com.ares.analytics.service.XrpRequestedMode
import com.ares.analytics.service.hardware.HardwareReviewStatus
import com.ares.analytics.service.hardware.HardwareSetupService
import com.ares.analytics.service.project.persistence.AutonomousCatalogProjectRepository
import com.ares.analytics.shared.models.League
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Explicit control surface for the leased `ares-xrp/1` JSON link (local simulator or operator-authorized
 * physical XRP over Wi-Fi); it never publishes NT4 commands.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    isLocalSimulator: Boolean = true,
    liveHostIp: String = "192.168.4.1",
    gamepadConnected: Boolean = false,
    hardwareSetupService: HardwareSetupService? = null,
    onOpenHardwareSetup: (() -> Unit)? = null,
) {
    val request by xrpLink.controlRequest.collectAsState()
    val peer by xrpLink.peerIdentity.collectAsState()
    val connectionError by xrpLink.connectionError.collectAsState()
    val liveAuthorized by xrpLink.liveControlAuthorized.collectAsState()
    var autos by remember(projectPath) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selectedAuto by remember(projectPath) { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var hardwareReviewStatus by remember(projectPath, isLocalSimulator) { mutableStateOf<HardwareReviewStatus?>(null) }

    LaunchedEffect(projectPath, isConnected) {
        autos = withContext(Dispatchers.IO) {
            AutonomousCatalogProjectRepository().load(projectPath).getOrNull()?.entries.orEmpty()
                .filter { it.enabled }
                .sortedWith(compareBy({ it.sortOrder }, { it.entryId }))
                .map { it.entryId to it.displayName }
        }
        selectedAuto = selectedAuto?.takeIf { chosen -> autos.any { it.first == chosen } }
            ?: autos.firstOrNull()?.first
    }

    LaunchedEffect(projectPath, isLocalSimulator, hardwareSetupService) {
        hardwareReviewStatus = if (!isLocalSimulator && hardwareSetupService != null) {
            withContext(Dispatchers.IO) {
                runCatching { hardwareSetupService.inspect(projectPath, League.XRP).reviewStatus }.getOrNull()
            }
        } else {
            null
        }
    }

    val controlsAuthorized = isConnected && (isLocalSimulator || liveAuthorized)
    val isMotionMode = request.mode == XrpRequestedMode.TELEOP || request.mode == XrpRequestedMode.AUTONOMOUS
    val statusDotColor = when {
        controlsAuthorized && isMotionMode -> AresGreen
        controlsAuthorized -> AresCyan
        isConnected -> AresAmber
        else -> AresTextTertiary
    }
    val borderColor = if (controlsAuthorized && isMotionMode) AresGreen else AresBorder

    Surface(
        modifier = modifier,
        color = AresSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            XrpControlHeaderRow(
                isLocalSimulator = isLocalSimulator,
                isConnected = isConnected,
                isSimulatorProcessRunning = isSimulatorProcessRunning,
                isLaunchPreparationRunning = isLaunchPreparationRunning,
                canLaunchSimulator = canLaunchSimulator,
                liveAuthorized = liveAuthorized,
                liveHostIp = liveHostIp,
                peer = peer,
                statusDotColor = statusDotColor,
                onLaunchSimulator = onLaunchSimulator,
                onAuthorizeLive = { xrpLink.authorizeLiveControl() },
                onDisarmLive = {
                    keyboardDriveState.disarm(preserveInputSource = true)
                    xrpLink.disarmLiveControl()
                },
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        keyboardDriveState.releaseAll()
                        keyboardDriveState.useGamepad = !keyboardDriveState.useGamepad
                    },
                    modifier = Modifier.testTag("xrp-input-mode-button"),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = if (keyboardDriveState.useGamepad) Icons.Default.Gamepad else Icons.Default.Keyboard,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (keyboardDriveState.useGamepad) {
                            if (gamepadConnected) "Input: Gamepad (Ready)" else "Input: Gamepad (No controller)"
                        } else {
                            "Input: Keyboard (WASD)"
                        },
                        fontSize = 12.sp,
                    )
                }
                Button(
                    onClick = {
                        keyboardDriveState.enabled = false
                        keyboardDriveState.releaseAll()
                        xrpLink.requestInitialize()
                    },
                    enabled = controlsAuthorized && request.mode != XrpRequestedMode.INITIALIZE,
                    modifier = Modifier.testTag("xrp-init-button"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("Initialize / recover", fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        // Arm keyboard/gamepad input BEFORE advancing the control revision so the
                        // 20 ms publisher cannot emit STOP under the new revision and latch lockout.
                        keyboardDriveState.enabled = true
                        xrpLink.requestTeleOp()
                    },
                    enabled = controlsAuthorized && request.mode != XrpRequestedMode.TELEOP,
                    modifier = Modifier.testTag("xrp-start-teleop-button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AresGreen,
                        contentColor = AresOnAccent,
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Start TeleOp", fontSize = 12.sp)
                }

                Box {
                    OutlinedButton(
                        onClick = { menuOpen = true },
                        enabled = autos.isNotEmpty(),
                        modifier = Modifier.testTag("xrp-auto-selector-button"),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            autos.firstOrNull { it.first == selectedAuto }?.second ?: "No autonomous routine",
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        autos.forEach { (id, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                modifier = Modifier.testTag("xrp-auto-option-$id"),
                                onClick = {
                                    selectedAuto = id
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        keyboardDriveState.enabled = false
                        keyboardDriveState.releaseAll()
                        selectedAuto?.let(xrpLink::requestAutonomous)
                    },
                    enabled = controlsAuthorized && selectedAuto != null,
                    modifier = Modifier.testTag("xrp-start-auto-button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AresCyan,
                        contentColor = AresOnAccent,
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Start autonomous", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = {
                        keyboardDriveState.enabled = false
                        keyboardDriveState.releaseAll()
                        xrpLink.requestStop()
                    },
                    enabled = controlsAuthorized || (isConnected && (request.mode != XrpRequestedMode.STOPPED || keyboardDriveState.enabled)),
                    modifier = Modifier.testTag("xrp-stop-button"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AresError),
                    border = BorderStroke(1.dp, if (isConnected) AresError.copy(alpha = 0.7f) else AresBorder),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop", fontSize = 12.sp)
                }
            }

            XrpControlStatusFooter(
                isLocalSimulator = isLocalSimulator,
                isConnected = isConnected,
                liveAuthorized = liveAuthorized,
                canLaunchSimulator = canLaunchSimulator,
                simulatorLaunchDisabledReason = simulatorLaunchDisabledReason,
                connectionError = connectionError,
                peer = peer,
                requestMode = request.mode,
                requestedAutoName = autos.firstOrNull { it.first == request.autonomousId }?.second ?: request.autonomousId,
                useGamepad = keyboardDriveState.useGamepad,
                hardwareReviewStatus = hardwareReviewStatus,
                onOpenHardwareSetup = onOpenHardwareSetup,
            )
        }
    }
}

@Composable
private fun XrpControlHeaderRow(
    isLocalSimulator: Boolean,
    isConnected: Boolean,
    isSimulatorProcessRunning: Boolean,
    isLaunchPreparationRunning: Boolean,
    canLaunchSimulator: Boolean,
    liveAuthorized: Boolean,
    liveHostIp: String,
    peer: XrpPeerIdentity?,
    statusDotColor: androidx.compose.ui.graphics.Color,
    onLaunchSimulator: () -> Unit,
    onAuthorizeLive: () -> Unit,
    onDisarmLive: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(statusDotColor, CircleShape))
        val headline = if (isLocalSimulator) {
            "XRP simulator · " + when {
                isConnected -> "Connected to ${peer?.projectId ?: "verified project"}"
                isSimulatorProcessRunning -> "Connecting"
                else -> "Offline"
            }
        } else {
            "XRP Driver Station · " + if (isConnected) {
                "Connected to ${peer?.projectId ?: "verified XRP"} @ $liveHostIp"
            } else {
                "Waiting for XRP @ $liveHostIp:${XrpLinkService.DEFAULT_PORT}"
            }
        }
        Text(
            text = headline,
            color = AresTextPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (isLocalSimulator) {
            if (!isSimulatorProcessRunning) {
                Button(
                    onClick = onLaunchSimulator,
                    enabled = canLaunchSimulator && !isLaunchPreparationRunning,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(if (isLaunchPreparationRunning) "Preparing…" else "Launch simulator", fontSize = 12.sp)
                }
            }
        } else if (!liveAuthorized) {
            Button(
                onClick = onAuthorizeLive,
                enabled = isConnected,
                modifier = Modifier.testTag("xrp-authorize-live-button"),
                colors = ButtonDefaults.buttonColors(containerColor = AresAmber, contentColor = AresOnAccent),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("Authorize Live XRP", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            OutlinedButton(
                onClick = onDisarmLive,
                modifier = Modifier.testTag("xrp-disarm-live-button"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AresError),
                border = BorderStroke(1.dp, AresError),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("Disarm Live XRP", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun XrpControlStatusFooter(
    isLocalSimulator: Boolean,
    isConnected: Boolean,
    liveAuthorized: Boolean,
    canLaunchSimulator: Boolean,
    simulatorLaunchDisabledReason: String?,
    connectionError: String?,
    peer: XrpPeerIdentity?,
    requestMode: XrpRequestedMode,
    requestedAutoName: String?,
    useGamepad: Boolean,
    hardwareReviewStatus: HardwareReviewStatus?,
    onOpenHardwareSetup: (() -> Unit)?,
) {
    if (!isLocalSimulator && peer != null) {
        Text(
            text = "Peer: ${peer.drivetrainType} · ${peer.boardType ?: "XRP"} · " +
                "MicroPython ${peer.micropythonVersion ?: "unknown"} · XRPLib ${peer.xrplibVersion ?: "unknown"}",
            color = AresTextSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
    if (!isLocalSimulator && isConnected && !liveAuthorized) {
        Text(
            text = "Telemetry-only · Click Authorize Live XRP to enable Initialize, TeleOp, and Autonomous commands over Wi-Fi.",
            color = AresAmber,
            fontSize = 12.sp,
        )
    }
    if (!isLocalSimulator && hardwareReviewStatus != null && hardwareReviewStatus != HardwareReviewStatus.CURRENT) {
        Surface(
            color = AresSurfaceElevated,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, AresAmber.copy(alpha = 0.6f)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = AresAmber, modifier = Modifier.size(16.dp))
                Text(
                    text = "Hardware Setup review is ${hardwareReviewStatus.name.lowercase().replace('_', ' ')} — verify wiring and directions before arming.",
                    color = AresAmber,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                if (onOpenHardwareSetup != null) {
                    TextButton(
                        onClick = onOpenHardwareSetup,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("Review Hardware Setup", fontSize = 11.sp)
                    }
                }
            }
        }
    }
    if (isLocalSimulator && !canLaunchSimulator) {
        simulatorLaunchDisabledReason?.let { Text(it, color = AresTextSecondary, fontSize = 12.sp) }
    }
    if (!isConnected) {
        connectionError?.let { Text(it, color = AresError, fontSize = 12.sp) }
    }
    Text(
        text = when (requestMode) {
            XrpRequestedMode.STOPPED -> "Stopped · outputs are neutral"
            XrpRequestedMode.INITIALIZE -> "Initialized · faults cleared only after a successful neutral write"
            XrpRequestedMode.TELEOP -> "TeleOp · leased ${if (useGamepad) "gamepad" else "keyboard"} control"
            XrpRequestedMode.AUTONOMOUS -> "Autonomous · ${requestedAutoName ?: "selected routine"}"
        },
        color = AresTextSecondary,
        fontSize = 12.sp,
    )
}
