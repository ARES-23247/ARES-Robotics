package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.di.KeyboardDriveState
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.RobotTopicContract
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.SIMULATION_SESSION_TAG
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

private const val TELEOP_LIST_TOPIC = "ARES/DriverStation/TeleOpList"
private const val SELECTED_OPMODE_TOPIC = "ARES/DriverStation/SelectedOpMode"
private const val DRIVER_STATION_COMMAND_TOPIC = "ARES/DriverStation/Command"
private const val ACTIVE_OPMODE_CLASS_TOPIC = "ARES/DriverStation/ActiveOpModeClass"
private const val ACTIVE_OPMODE_DISPLAY_NAME_TOPIC = "ARES/DriverStation/ActiveOpModeDisplayName"
private const val ACTIVE_OPMODE_STATE_TOPIC = "ARES/DriverStation/ActiveOpModeState"
private const val FRC_DRIVER_STATION_COMMAND_TOPIC = "ARES/Simulation/FrcDriverStationCommand"
private const val FRC_DRIVER_STATION_STATE_TOPIC = "ARES/Simulation/FrcDriverStationState"
private const val FRC_ENABLE_TELEOP_COMMAND = "ENABLE_TELEOP"
private const val FRC_ENABLE_AUTONOMOUS_COMMAND = "ENABLE_AUTONOMOUS"
private const val FRC_DISABLE_COMMAND = "DISABLE"
private const val FRC_TELEOP_ENABLED_STATE = "TELEOP_ENABLED"
private const val FRC_AUTONOMOUS_ENABLED_STATE = "AUTONOMOUS_ENABLED"
private const val FRC_WAITING_FOR_CONTROL_STATE = "WAITING_FOR_CONTROL"
private const val TELEOP_INIT_STATE = "TELEOP_INIT"
private const val TELEOP_RUNNING_STATE = "TELEOP_RUNNING"
private const val OPMODE_ACK_TIMEOUT_MS = 5_000L

internal fun preferredSimulatorTeleOp(teleOps: List<String>): String? =
    teleOps.firstOrNull { it.endsWith(".ARESStarterTeleOp") || it == "ARESStarterTeleOp" }
        ?: teleOps.firstOrNull { it.endsWith(".ARESMecanumTeleOp") || it == "ARESMecanumTeleOp" }
        ?: teleOps.firstOrNull { it.endsWith(".ARESRemoteDriveOpMode") || it == "ARESRemoteDriveOpMode" }
        ?: teleOps.firstOrNull { !it.isAuxiliarySimulatorOpMode() }
        ?: teleOps.firstOrNull()

private fun String.isAuxiliarySimulatorOpMode(): Boolean {
    val simpleName = substringAfterLast('.')
    return simpleName == "AresHardwareTestOpMode" ||
        simpleName == "NullOpMode" ||
        simpleName.contains("Diagnostic", ignoreCase = true) ||
        simpleName.contains("Calibration", ignoreCase = true) ||
        simpleName.contains("Tuning", ignoreCase = true)
}

internal fun simulatorOpModeAcknowledged(
    selectedOpMode: String?,
    activeOpMode: String?,
    activeState: String?,
    expectedState: String,
): Boolean = selectedOpMode != null && selectedOpMode == activeOpMode && activeState == expectedState

internal fun simulatorDriveReceiverReady(statusCode: Int?, leaseAgeMs: Long?): Boolean =
    (statusCode == 2 || statusCode == 3) && leaseAgeMs != null && leaseAgeMs in 0..500L

internal fun simulatorDriveReceiverStatus(statusCode: Int?): String = when (statusCode) {
    0 -> "WAITING FOR CONTROL"
    1 -> "WAITING FOR NEUTRAL"
    2 -> "CONTROL READY"
    3 -> "CONTROL ACTIVE"
    4 -> "CONTROL LEASE EXPIRED"
    5 -> "INVALID CONTROL FRAME"
    6 -> "OUT-OF-ORDER CONTROL"
    else -> "CONTROL LINK UNKNOWN"
}

internal fun frcSimulatorTeleOpEnabled(state: String?): Boolean =
    state?.trim()?.uppercase() == FRC_TELEOP_ENABLED_STATE

internal fun frcSimulatorAutonomousEnabled(state: String?): Boolean =
    state?.trim()?.uppercase() == FRC_AUTONOMOUS_ENABLED_STATE

internal enum class FrcAutonomousDisplayState {
    INACTIVE,
    RUNNING,
    COMPLETE,
    BLOCKED,
}

internal fun frcAutonomousDisplayState(
    driverStationState: String?,
    autonomousStatus: String?,
): FrcAutonomousDisplayState {
    if (!frcSimulatorAutonomousEnabled(driverStationState)) return FrcAutonomousDisplayState.INACTIVE
    return when (autonomousStatus?.trim()?.uppercase()) {
        "COMPLETE" -> FrcAutonomousDisplayState.COMPLETE
        "BLOCKED", "FAILED", "CANCELLED" -> FrcAutonomousDisplayState.BLOCKED
        else -> FrcAutonomousDisplayState.RUNNING
    }
}

internal fun preferredSimulatorAutonomous(
    available: List<String>,
    requested: String?,
    robotSelected: String?,
): String? = requested?.takeIf { it in available }
    ?: robotSelected?.takeIf { it in available }
    ?: available.firstOrNull { it == "do-nothing" }
    ?: available.firstOrNull()

internal fun decodeSimulatorTeleOps(value: String?): List<String> =
    value?.let { encoded ->
        runCatching { Json.decodeFromString<List<String>>(encoded) }.getOrDefault(emptyList())
    }.orEmpty()

internal enum class LocalSimulatorPrimaryAction(val label: String) {
    LAUNCH_SIMULATOR("Launch simulator"),
    VERIFY_AND_LAUNCH("Verify & launch"),
    VERIFYING_PROJECT("Building simulator"),
    WAIT_FOR_CONNECTION("Connecting"),
    START_DRIVING("Start driving"),
    STARTING_TELEOP("Starting"),
    TELEOP_RUNNING("Running"),
}

internal fun localSimulatorPrimaryAction(
    isConnected: Boolean,
    isSimulatorProcessRunning: Boolean,
    isLaunchPreparationRunning: Boolean,
    launchRequiresVerification: Boolean,
    isTeleOpStarting: Boolean,
    isTeleOpRunning: Boolean,
): LocalSimulatorPrimaryAction = when {
    !isConnected && isLaunchPreparationRunning -> LocalSimulatorPrimaryAction.VERIFYING_PROJECT
    !isConnected && isSimulatorProcessRunning -> LocalSimulatorPrimaryAction.WAIT_FOR_CONNECTION
    !isConnected && launchRequiresVerification -> LocalSimulatorPrimaryAction.VERIFY_AND_LAUNCH
    !isConnected -> LocalSimulatorPrimaryAction.LAUNCH_SIMULATOR
    isTeleOpStarting -> LocalSimulatorPrimaryAction.STARTING_TELEOP
    isTeleOpRunning -> LocalSimulatorPrimaryAction.TELEOP_RUNNING
    else -> LocalSimulatorPrimaryAction.START_DRIVING
}

internal enum class LocalSimulatorLaunchRequest {
    NONE,
    START_SIMULATOR,
    VERIFY_THEN_START,
}

internal fun localSimulatorLaunchRequest(
    canRunSimulation: Boolean,
    canRunBuild: Boolean,
    isBuildRunning: Boolean,
    isSimulatorRunning: Boolean,
    isSimulatorOnline: Boolean,
    isLaunchPending: Boolean,
): LocalSimulatorLaunchRequest = when {
    isBuildRunning || isSimulatorRunning || isSimulatorOnline || isLaunchPending -> LocalSimulatorLaunchRequest.NONE
    canRunSimulation -> LocalSimulatorLaunchRequest.START_SIMULATOR
    canRunBuild -> LocalSimulatorLaunchRequest.VERIFY_THEN_START
    else -> LocalSimulatorLaunchRequest.NONE
}

/**
 * An always-visible control path for the FTC simulator and the FRC starter simulator.
 *
 * Starting the simulator only creates the physics/NT4 server. Motion additionally requires a
 * running TeleOp and an armed local-control surface. For FRC, a simulation-only bridge owns the
 * Driver Station state so students do not need a separate WPILib window. The transport rejects
 * drive frames for every non-loopback target, so this strip cannot become a physical-robot path.
 */
@Composable
fun LocalSimulatorControlBar(
    nt4Client: Nt4ClientService,
    keyboardDriveState: KeyboardDriveState,
    league: League,
    teamId: String,
    seasonId: String,
    robotId: String,
    isConnected: Boolean,
    isSimulatorProcessRunning: Boolean,
    isLaunchPreparationRunning: Boolean,
    launchRequiresVerification: Boolean,
    canLaunchSimulator: Boolean,
    simulatorLaunchDisabledReason: String?,
    onLaunchSimulator: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var teleOps by remember(nt4Client) {
        mutableStateOf(decodeSimulatorTeleOps(nt4Client.latestValues[TELEOP_LIST_TOPIC]?.stringValue))
    }
    var selectedOpMode by remember(nt4Client) {
        mutableStateOf(
            nt4Client.latestValues[SELECTED_OPMODE_TOPIC]?.stringValue
                ?.takeIf { it.isNotBlank() }
                ?: preferredSimulatorTeleOp(teleOps),
        )
    }
    var command by remember(nt4Client) {
        mutableStateOf(nt4Client.latestValues[DRIVER_STATION_COMMAND_TOPIC]?.stringValue ?: "STOP")
    }
    var activeOpMode by remember(nt4Client) {
        mutableStateOf(nt4Client.latestValues[ACTIVE_OPMODE_CLASS_TOPIC]?.stringValue?.takeIf(String::isNotBlank))
    }
    var activeOpModeDisplayName by remember(nt4Client) {
        mutableStateOf(nt4Client.latestValues[ACTIVE_OPMODE_DISPLAY_NAME_TOPIC]?.stringValue?.takeIf(String::isNotBlank))
    }
    var activeOpModeState by remember(nt4Client) {
        mutableStateOf(nt4Client.latestValues[ACTIVE_OPMODE_STATE_TOPIC]?.stringValue)
    }
    var frcDriverStationState by remember(nt4Client) {
        mutableStateOf(nt4Client.latestValues[FRC_DRIVER_STATION_STATE_TOPIC]?.stringValue)
    }
    var availableAutos by remember(nt4Client) {
        mutableStateOf(
            parseAvailableAutoDocuments(
                nt4Client.latestValues[RobotTopicContract.AVAILABLE_AUTONOMOUS_ROUTINES]?.stringValue,
            ),
        )
    }
    var robotSelectedAuto by remember(nt4Client) {
        mutableStateOf(
            nt4Client.latestValues[RobotTopicContract.SELECTED_AUTONOMOUS_ROUTINE]
                ?.stringValue
                .orEmpty(),
        )
    }
    var requestedAuto by remember(nt4Client) {
        mutableStateOf(preferredSimulatorAutonomous(availableAutos, null, robotSelectedAuto))
    }
    var autonomousStatus by remember(nt4Client) {
        mutableStateOf(
            nt4Client.latestValues[RobotTopicContract.AUTONOMOUS_STATUS]
                ?.stringValue
                ?.ifBlank { "Idle" }
                ?: "Waiting for robot",
        )
    }
    var driveReceiverStatusCode by remember(nt4Client) {
        mutableStateOf(nt4Client.driveInputAcknowledgement.value?.statusCode)
    }
    var driveReceiverLeaseAgeMs by remember(nt4Client) {
        mutableStateOf(nt4Client.driveInputAcknowledgement.value?.leaseAgeMs)
    }
    var starting by remember { mutableStateOf(false) }
    var startFailure by remember { mutableStateOf<String?>(null) }
    var selectorExpanded by remember { mutableStateOf(false) }
    var autoSelectorExpanded by remember { mutableStateOf(false) }
    var startJob by remember { mutableStateOf<Job?>(null) }
    var recordingBusy by remember { mutableStateOf(false) }
    var recordingMessage by remember { mutableStateOf<String?>(null) }
    var recordingFailure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val connectedNow by rememberUpdatedState(isConnected)
    val recordingSession by nt4Client.currentSession.collectAsState()

    suspend fun stopAndSaveRecording() {
        if (recordingSession == null) return
        recordingBusy = true
        recordingFailure = null
        try {
            nt4Client.stopRecordingSession()
            recordingMessage = "Simulation run saved. Refresh the guided experiment to compare it."
        } catch (failure: Exception) {
            recordingFailure = failure.message ?: "The simulation run could not be saved."
        } finally {
            recordingBusy = false
        }
    }

    LaunchedEffect(nt4Client) {
        nt4Client.uiTelemetryFlow.collect { frame ->
            when (frame.key.trimStart('/')) {
                TELEOP_LIST_TOPIC -> frame.stringValue?.let { encoded ->
                    teleOps = decodeSimulatorTeleOps(encoded)
                    if (selectedOpMode !in teleOps) selectedOpMode = preferredSimulatorTeleOp(teleOps)
                }
                SELECTED_OPMODE_TOPIC -> frame.stringValue?.takeIf { it.isNotBlank() }?.let {
                    selectedOpMode = it
                }
                DRIVER_STATION_COMMAND_TOPIC -> frame.stringValue?.let {
                    command = it
                }
                ACTIVE_OPMODE_CLASS_TOPIC -> activeOpMode = frame.stringValue?.takeIf(String::isNotBlank)
                ACTIVE_OPMODE_DISPLAY_NAME_TOPIC -> activeOpModeDisplayName = frame.stringValue?.takeIf(String::isNotBlank)
                ACTIVE_OPMODE_STATE_TOPIC -> activeOpModeState = frame.stringValue
                FRC_DRIVER_STATION_STATE_TOPIC -> frcDriverStationState = frame.stringValue
                RobotTopicContract.AVAILABLE_AUTONOMOUS_ROUTINES -> {
                    availableAutos = parseAvailableAutoDocuments(frame.stringValue)
                    requestedAuto = preferredSimulatorAutonomous(
                        available = availableAutos,
                        requested = requestedAuto,
                        robotSelected = robotSelectedAuto,
                    )
                }
                RobotTopicContract.SELECTED_AUTONOMOUS_ROUTINE -> {
                    robotSelectedAuto = frame.stringValue.orEmpty()
                    requestedAuto = preferredSimulatorAutonomous(
                        available = availableAutos,
                        requested = requestedAuto,
                        robotSelected = robotSelectedAuto,
                    )
                }
                RobotTopicContract.AUTONOMOUS_STATUS -> {
                    autonomousStatus = frame.stringValue.orEmpty().ifBlank { "Idle" }
                }
            }
        }
    }

    LaunchedEffect(nt4Client) {
        nt4Client.driveInputAcknowledgement.collect { acknowledgement ->
            driveReceiverStatusCode = acknowledgement?.statusCode
            driveReceiverLeaseAgeMs = acknowledgement?.leaseAgeMs
        }
    }

    LaunchedEffect(isConnected) {
        if (!isConnected) {
            startJob?.cancel()
            startJob = null
            starting = false
            startFailure = null
            keyboardDriveState.disarm()
        }
    }

    val isFtc = league == League.FTC
    val isRunning = isConnected && (
        if (isFtc) {
            simulatorOpModeAcknowledged(
                selectedOpMode = selectedOpMode,
                activeOpMode = activeOpMode,
                activeState = activeOpModeState,
                expectedState = TELEOP_RUNNING_STATE,
            )
        } else {
            frcSimulatorTeleOpEnabled(frcDriverStationState)
        }
    ) && !starting
    val isAutonomousRunning = isConnected && !isFtc && frcSimulatorAutonomousEnabled(frcDriverStationState) && !starting
    val autonomousDisplayState = if (isConnected && !isFtc) {
        frcAutonomousDisplayState(frcDriverStationState, autonomousStatus)
    } else {
        FrcAutonomousDisplayState.INACTIVE
    }
    val receiverReady = simulatorDriveReceiverReady(
        statusCode = driveReceiverStatusCode,
        leaseAgeMs = driveReceiverLeaseAgeMs,
    )
    val primaryAction = localSimulatorPrimaryAction(
        isConnected = isConnected,
        isSimulatorProcessRunning = isSimulatorProcessRunning,
        isLaunchPreparationRunning = isLaunchPreparationRunning,
        launchRequiresVerification = launchRequiresVerification,
        isTeleOpStarting = starting,
        isTeleOpRunning = isRunning,
    )
    val statusText = when {
        !isConnected && isSimulatorProcessRunning -> "CONNECTING"
        !isConnected -> "OFFLINE"
        starting -> "STARTING"
        startFailure != null -> "START FAILED"
        isRunning && keyboardDriveState.enabled && !receiverReady -> "CONTROL RECOVERING"
        isRunning && keyboardDriveState.enabled -> simulatorDriveReceiverStatus(driveReceiverStatusCode)
        autonomousDisplayState == FrcAutonomousDisplayState.COMPLETE -> "AUTONOMOUS COMPLETE"
        autonomousDisplayState == FrcAutonomousDisplayState.BLOCKED -> "AUTONOMOUS BLOCKED"
        autonomousDisplayState == FrcAutonomousDisplayState.RUNNING -> "AUTONOMOUS RUNNING"
        isRunning -> "TELEOP RUNNING"
        !isFtc && frcDriverStationState == FRC_WAITING_FOR_CONTROL_STATE -> "WAITING FOR SAFE CONTROL"
        command == "INIT" -> "INITIALIZED"
        else -> "WAITING FOR TELEOP"
    }
    val statusColor = when {
        !isConnected -> AresTextSecondary
        startFailure != null || autonomousDisplayState == FrcAutonomousDisplayState.BLOCKED -> AresError
        starting || command == "INIT" || (isRunning && keyboardDriveState.enabled && !receiverReady) -> AresAmber
        isRunning || isAutonomousRunning -> AresGreen
        else -> AresCyan
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AresSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (isRunning || isAutonomousRunning) AresGreen else AresBorder),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(8.dp).background(statusColor, CircleShape))
                Text(
                    if (isFtc) "Local FTC Simulator" else "Local FRC Simulator",
                    color = AresTextPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(statusText, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)

                Box(Modifier.weight(1f)) {
                    Surface(
                        onClick = {
                            if (isConnected && !starting) {
                                if (isFtc && teleOps.isNotEmpty()) selectorExpanded = true
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
                                    selectedOpMode?.substringAfterLast('.')
                                        ?: if (isConnected) "Waiting for TeleOp list…" else "Simulator is not running"
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
                        teleOps.forEach { opMode ->
                            DropdownMenuItem(
                                text = { Text(opMode.substringAfterLast('.'), color = AresTextPrimary) },
                                onClick = {
                                    selectedOpMode = opMode
                                    selectorExpanded = false
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
                                    requestedAuto = autoId
                                    autoSelectorExpanded = false
                                },
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        if (
                            primaryAction == LocalSimulatorPrimaryAction.LAUNCH_SIMULATOR ||
                            primaryAction == LocalSimulatorPrimaryAction.VERIFY_AND_LAUNCH
                        ) {
                            onLaunchSimulator()
                            return@Button
                        }
                        if (primaryAction != LocalSimulatorPrimaryAction.START_DRIVING) return@Button
                        val opMode = selectedOpMode
                        if (isFtc && opMode == null) return@Button
                        startJob?.cancel()
                        startJob = scope.launch {
                            starting = true
                            startFailure = null
                            keyboardDriveState.disarm()
                            try {
                                if (!isFtc) {
                                    nt4Client.publishString(FRC_DRIVER_STATION_COMMAND_TOPIC, FRC_ENABLE_TELEOP_COMMAND)
                                    val running = withTimeoutOrNull(OPMODE_ACK_TIMEOUT_MS) {
                                        while (connectedNow && !frcSimulatorTeleOpEnabled(frcDriverStationState)) delay(20)
                                        connectedNow
                                    } == true
                                    if (!running) {
                                        startFailure = when (frcDriverStationState) {
                                            FRC_WAITING_FOR_CONTROL_STATE ->
                                                "The FRC simulator did not receive a fresh neutral control handshake"
                                            else -> "The FRC simulator did not enable TeleOp"
                                        }
                                        return@launch
                                    }
                                    keyboardDriveState.enabled = true
                                    return@launch
                                }

                                checkNotNull(opMode)
                                nt4Client.publishString(SELECTED_OPMODE_TOPIC, opMode)
                                nt4Client.publishString(DRIVER_STATION_COMMAND_TOPIC, "INIT")
                                command = "INIT"
                                val initialized = withTimeoutOrNull(OPMODE_ACK_TIMEOUT_MS) {
                                    while (connectedNow && !simulatorOpModeAcknowledged(
                                            selectedOpMode = opMode,
                                            activeOpMode = activeOpMode,
                                            activeState = activeOpModeState,
                                            expectedState = TELEOP_INIT_STATE,
                                        )) {
                                        delay(20)
                                    }
                                    connectedNow
                                } == true
                                if (!initialized) {
                                    startFailure = "The simulator did not initialize ${opMode.substringAfterLast('.')}"
                                    return@launch
                                }

                                nt4Client.publishString(DRIVER_STATION_COMMAND_TOPIC, "START")
                                command = "START"
                                val running = withTimeoutOrNull(OPMODE_ACK_TIMEOUT_MS) {
                                    while (connectedNow && !simulatorOpModeAcknowledged(
                                            selectedOpMode = opMode,
                                            activeOpMode = activeOpMode,
                                            activeState = activeOpModeState,
                                            expectedState = TELEOP_RUNNING_STATE,
                                        )) {
                                        delay(20)
                                    }
                                    connectedNow
                                } == true
                                if (!running) {
                                    startFailure = "The simulator did not start ${opMode.substringAfterLast('.')}"
                                    return@launch
                                }
                                keyboardDriveState.enabled = true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                startFailure = error.message ?: "Simulator TeleOp start failed"
                                keyboardDriveState.disarm()
                            } finally {
                                starting = false
                            }
                        }
                    },
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
                    Text(primaryAction.label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                if (!isFtc) {
                    OutlinedButton(
                        onClick = {
                            if (starting || isAutonomousRunning) return@OutlinedButton
                            val selection = requestedAuto ?: return@OutlinedButton
                            startJob?.cancel()
                            startJob = scope.launch {
                                starting = true
                                startFailure = null
                                keyboardDriveState.disarm()
                                try {
                                    nt4Client.publishString(RobotTopicContract.FRC_AUTONOMOUS_REQUEST, selection)
                                    nt4Client.publishString(
                                        RobotTopicContract.FRC_SMART_DASHBOARD_AUTONOMOUS_REQUEST,
                                        selection,
                                    )
                                    // Give the NT4 request one bounded transport interval before autonomousInit
                                    // locks the generated selection.
                                    delay(150)
                                    nt4Client.publishString(
                                        FRC_DRIVER_STATION_COMMAND_TOPIC,
                                        FRC_ENABLE_AUTONOMOUS_COMMAND,
                                    )
                                    val running = withTimeoutOrNull(OPMODE_ACK_TIMEOUT_MS) {
                                        while (
                                            connectedNow &&
                                            (!frcSimulatorAutonomousEnabled(frcDriverStationState) ||
                                                robotSelectedAuto != selection)
                                        ) {
                                            delay(20)
                                        }
                                        connectedNow
                                    } == true
                                    if (!running) {
                                        startFailure = if (frcSimulatorAutonomousEnabled(frcDriverStationState)) {
                                            "The robot did not lock autonomous '$selection'"
                                        } else {
                                            "The FRC simulator did not start autonomous"
                                        }
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    startFailure = error.message ?: "Simulator autonomous start failed"
                                } finally {
                                    starting = false
                                }
                            }
                        },
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

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            recordingBusy = true
                            recordingFailure = null
                            try {
                                if (recordingSession == null) {
                                    val session = nt4Client.startRecordingSession(
                                        teamId = teamId,
                                        seasonId = seasonId,
                                        robotId = robotId,
                                        tags = listOf(SIMULATION_SESSION_TAG, "studio-experiment"),
                                    )
                                    recordingMessage = "Recording simulation run ${session.sessionId.take(8)}…"
                                } else {
                                    stopAndSaveRecording()
                                }
                            } catch (failure: Exception) {
                                recordingFailure = failure.message ?: "Recording could not be changed."
                            } finally {
                                recordingBusy = false
                            }
                        }
                    },
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

                OutlinedButton(
                    onClick = {
                        startJob?.cancel()
                        startJob = null
                        starting = false
                        command = "STOP"
                        keyboardDriveState.disarm()
                        scope.launch {
                            stopAndSaveRecording()
                            nt4Client.publishString(
                                if (isFtc) DRIVER_STATION_COMMAND_TOPIC else FRC_DRIVER_STATION_COMMAND_TOPIC,
                                if (isFtc) "STOP" else FRC_DISABLE_COMMAND,
                            )
                        }
                    },
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

                OutlinedButton(
                    onClick = {
                        keyboardDriveState.releaseAll()
                        keyboardDriveState.useGamepad = !keyboardDriveState.useGamepad
                    },
                    enabled = isRunning,
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AresTextPrimary),
                    border = BorderStroke(1.dp, AresBorder),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Icon(
                        if (keyboardDriveState.useGamepad) Icons.Default.Gamepad else Icons.Default.Keyboard,
                        if (keyboardDriveState.useGamepad) "Use keyboard input" else "Use gamepad input",
                        modifier = Modifier.size(16.dp),
                    )
                }

                OutlinedButton(
                    onClick = {
                        if (keyboardDriveState.enabled) keyboardDriveState.disarm()
                        else keyboardDriveState.enabled = true
                    },
                    enabled = isRunning,
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (keyboardDriveState.enabled) AresGreen else AresTextPrimary,
                    ),
                    border = BorderStroke(1.dp, if (keyboardDriveState.enabled) AresGreen else AresBorder),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        when {
                            !keyboardDriveState.enabled -> "Arm control"
                            receiverReady -> "ARMED"
                            else -> "RECONNECTING"
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Text(
                when {
                    recordingFailure != null -> recordingFailure!!
                    recordingSession != null -> recordingMessage ?: "Recording this simulation run. Stop and save before comparing."
                    recordingMessage != null -> recordingMessage!!
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
                    !isFtc && keyboardDriveState.enabled ->
                        "FRC TeleOp is enabled and field-centric control is armed: W drives toward the opposing station. Loopback only."
                    !isFtc ->
                        if (availableAutos.isEmpty()) {
                            "Waiting for the robot's compiled autonomous catalog. Start driving enables TeleOp; no external WPILib window is required."
                        } else {
                            "Choose an autonomous above, then Run auto—or Start driving for TeleOp. No external WPILib window is required."
                        }
                    startFailure != null -> "$startFailure. Stop, confirm the selected TeleOp, and try again."
                    !isRunning -> "Choose a TeleOp, then Start driving. The simulator can be online while no OpMode is running."
                    keyboardDriveState.enabled && !receiverReady ->
                        "${simulatorDriveReceiverStatus(driveReceiverStatusCode)}. ARES is sending neutral frames to restore the safe control lease."
                    keyboardDriveState.useGamepad -> "Move the sticks directly while armed. Dashboard drive frames are blocked for non-loopback targets."
                    else -> "${activeOpModeDisplayName ?: selectedOpMode?.substringAfterLast('.')}: W drives toward the opposing station, A/D strafe, and ←/→ rotate. Loopback only."
                },
                color = when {
                    recordingFailure != null -> AresError
                    recordingSession != null -> AresAmber
                    recordingMessage != null -> AresGreen
                    isRunning && keyboardDriveState.enabled -> AresGreen
                    else -> AresTextSecondary
                },
                fontSize = 10.sp,
                lineHeight = 13.sp,
                maxLines = 1,
            )
        }
    }
}
