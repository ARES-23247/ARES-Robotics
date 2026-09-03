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
import com.ares.analytics.service.project.persistence.AutonomousCatalogProjectRepository
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    projectPath: String,
    isConnected: Boolean,
    isSimulatorProcessRunning: Boolean,
    isLaunchPreparationRunning: Boolean,
    launchRequiresVerification: Boolean,
    canLaunchSimulator: Boolean,
    simulatorLaunchDisabledReason: String?,
    onLaunchSimulator: () -> Unit,
    onRecordingSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var teleOps by remember(nt4Client) {
        mutableStateOf(decodeSimulatorOpModes(nt4Client.latestValues[TELEOP_LIST_TOPIC]?.stringValue))
    }
    var autonomousOpModes by remember(nt4Client) {
        mutableStateOf(decodeSimulatorOpModes(nt4Client.latestValues[AUTONOMOUS_LIST_TOPIC]?.stringValue))
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
    var ftcRoutineSelectorExpanded by remember { mutableStateOf(false) }
    var autonomousLabels by remember(projectPath) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var startJob by remember { mutableStateOf<Job?>(null) }
    var recordingBusy by remember { mutableStateOf(false) }
    var recordingMessage by remember { mutableStateOf<String?>(null) }
    var recordingFailure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val connectedNow by rememberUpdatedState(isConnected)
    val recordingSession by nt4Client.currentSession.collectAsState()
    LaunchedEffect(projectPath) {
        val localEntries = withContext(Dispatchers.IO) {
            AutonomousCatalogProjectRepository().load(projectPath).getOrNull()?.entries.orEmpty()
                .filter { it.enabled }
                .sortedWith(compareBy({ it.sortOrder }, { it.entryId }))
        }
        autonomousLabels = localEntries.associate { it.entryId to it.displayName }
        val localIds = localEntries.map { it.entryId }
        if (availableAutos.isEmpty() && localIds.isNotEmpty()) availableAutos = localIds
        requestedAuto = preferredSimulatorAutonomous(availableAutos, requestedAuto, robotSelectedAuto)
    }
    var autonomousDetail by remember(nt4Client) {
        mutableStateOf(nt4Client.latestValues[RobotTopicContract.AUTONOMOUS_DETAIL]?.stringValue.orEmpty())
    }
    suspend fun stopAndSaveRecording() {
        if (recordingSession == null) return
        recordingBusy = true
        recordingFailure = null
        try {
            nt4Client.stopRecordingSession()
            onRecordingSaved()
            recordingMessage = "Simulation run saved and available in Recorded Sessions."
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
                    teleOps = decodeSimulatorOpModes(encoded)
                    if (selectedOpMode !in teleOps && selectedOpMode !in autonomousOpModes) {
                        selectedOpMode = preferredSimulatorTeleOp(teleOps) ?: autonomousOpModes.firstOrNull()
                    }
                }
                AUTONOMOUS_LIST_TOPIC -> frame.stringValue?.let { encoded ->
                    autonomousOpModes = decodeSimulatorOpModes(encoded)
                    if (selectedOpMode !in teleOps && selectedOpMode !in autonomousOpModes) {
                        selectedOpMode = preferredSimulatorTeleOp(teleOps) ?: autonomousOpModes.firstOrNull()
                    }
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
                RobotTopicContract.AUTONOMOUS_DETAIL -> autonomousDetail = frame.stringValue.orEmpty()
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
    val ftcModeKind = ftcSimulatorOpModeKind(selectedOpMode, teleOps, autonomousOpModes)
    val isFtcAutonomousSelection = ftcModeKind == FtcSimulatorOpModeKind.AUTONOMOUS
    val isRunning = isConnected && (
        if (isFtc) {
            val expectedState = ftcModeKind?.runningState()
            simulatorOpModeAcknowledged(
                selectedOpMode = selectedOpMode,
                activeOpMode = activeOpMode,
                activeState = activeOpModeState,
                expectedState = expectedState.orEmpty(),
            ) && expectedState != null
        } else {
            frcSimulatorTeleOpEnabled(frcDriverStationState)
        }
    ) && !starting
    val isAutonomousRunning = isConnected && !starting && if (isFtc) {
        isFtcAutonomousSelection && isRunning
    } else {
        frcSimulatorAutonomousEnabled(frcDriverStationState)
    }
    val autonomousDisplayState = if (isConnected && !isFtc) {
        frcAutonomousDisplayState(frcDriverStationState, autonomousStatus)
    } else if (isConnected && isFtcAutonomousSelection) {
        ftcAutonomousDisplayState(autonomousStatus)
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
        isRunning && !isFtcAutonomousSelection && keyboardDriveState.enabled && !receiverReady -> "CONTROL RECOVERING"
        isRunning && !isFtcAutonomousSelection && keyboardDriveState.enabled -> simulatorDriveReceiverStatus(driveReceiverStatusCode)
        autonomousDisplayState == FrcAutonomousDisplayState.COMPLETE -> "AUTONOMOUS COMPLETE"
        autonomousDisplayState == FrcAutonomousDisplayState.BLOCKED -> "AUTONOMOUS BLOCKED"
        autonomousDisplayState == FrcAutonomousDisplayState.RUNNING -> "AUTONOMOUS RUNNING"
        isFtcAutonomousSelection && isRunning -> "AUTONOMOUS RUNNING"
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

                SimulatorModeDropdown(
                    isFtc = isFtc,
                    isConnected = isConnected,
                    starting = starting,
                    selectedOpMode = selectedOpMode,
                    onSelectedOpModeChanged = { selectedOpMode = it },
                    teleOps = teleOps,
                    autonomousOpModes = autonomousOpModes,
                    availableAutos = availableAutos,
                    requestedAuto = requestedAuto,
                    onRequestedAutoChanged = { requestedAuto = it },
                    onDisarmDrive = { keyboardDriveState.disarm() },
                    modifier = Modifier.weight(1f),
                )

                if (isFtcAutonomousSelection) {
                    FtcAutonomousRoutineSelector(
                        expanded = ftcRoutineSelectorExpanded,
                        enabled = isConnected && !starting,
                        availableAutos = availableAutos,
                        labels = autonomousLabels,
                        selectedAuto = requestedAuto,
                        onExpandedChange = { ftcRoutineSelectorExpanded = it },
                        onSelected = { requestedAuto = it },
                    )
                }

                val onPrimaryActionClick: () -> Unit = {
                    if (
                        primaryAction == LocalSimulatorPrimaryAction.LAUNCH_SIMULATOR ||
                        primaryAction == LocalSimulatorPrimaryAction.VERIFY_AND_LAUNCH
                    ) {
                        onLaunchSimulator()
                    } else if (primaryAction == LocalSimulatorPrimaryAction.START_DRIVING) {
                        val opMode = selectedOpMode
                        if (!isFtc || opMode != null) {
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
                                    val modeKind = requireNotNull(
                                        ftcSimulatorOpModeKind(opMode, teleOps, autonomousOpModes)
                                    ) { "The selected FTC OpMode is no longer available" }
                                    val autonomousSelection = if (modeKind == FtcSimulatorOpModeKind.AUTONOMOUS) {
                                        requireNotNull(requestedAuto) { "Choose an autonomous routine" }
                                    } else null
                                    autonomousSelection?.let { selection ->
                                        nt4Client.publishString(RobotTopicContract.FTC_AUTONOMOUS_REQUEST, selection)
                                        delay(150)
                                    }
                                    nt4Client.publishString(SELECTED_OPMODE_TOPIC, opMode)
                                    nt4Client.publishString(DRIVER_STATION_COMMAND_TOPIC, "INIT")
                                    command = "INIT"
                                    val initialized = awaitSimulatorOpModeAcknowledgement(
                                        selectedOpMode = opMode,
                                        expectedState = modeKind.initState(),
                                        isConnected = { connectedNow },
                                        snapshot = {
                                            SimulatorOpModeSnapshot(
                                                activeOpMode = nt4Client.latestValues[ACTIVE_OPMODE_CLASS_TOPIC]
                                                    ?.stringValue
                                                    ?.takeIf(String::isNotBlank),
                                                activeState = nt4Client.latestValues[ACTIVE_OPMODE_STATE_TOPIC]?.stringValue,
                                                autonomousStatus = nt4Client.latestValues[RobotTopicContract.AUTONOMOUS_STATUS]
                                                    ?.stringValue,
                                            )
                                        },
                                    )
                                    if (!initialized) {
                                        startFailure = "The simulator did not initialize ${opMode.substringAfterLast('.')}"
                                        return@launch
                                    }

                                    nt4Client.publishString(DRIVER_STATION_COMMAND_TOPIC, "START")
                                    command = "START"
                                    val running = awaitSimulatorOpModeAcknowledgement(
                                        selectedOpMode = opMode,
                                        expectedState = modeKind.runningState(),
                                        isConnected = { connectedNow },
                                        snapshot = {
                                            SimulatorOpModeSnapshot(
                                                activeOpMode = nt4Client.latestValues[ACTIVE_OPMODE_CLASS_TOPIC]
                                                    ?.stringValue
                                                    ?.takeIf(String::isNotBlank),
                                                activeState = nt4Client.latestValues[ACTIVE_OPMODE_STATE_TOPIC]?.stringValue,
                                                autonomousStatus = nt4Client.latestValues[RobotTopicContract.AUTONOMOUS_STATUS]
                                                    ?.stringValue,
                                            )
                                        },
                                        acceptedAutonomousStatuses = if (modeKind == FtcSimulatorOpModeKind.AUTONOMOUS) {
                                            setOf("RUNNING", "COMPLETE", "BLOCKED", "FAILED", "CANCELLED")
                                        } else emptySet(),
                                    )
                                    if (!running) {
                                        startFailure = "The simulator did not start ${opMode.substringAfterLast('.')}"
                                        return@launch
                                    }
                                    if (modeKind == FtcSimulatorOpModeKind.AUTONOMOUS &&
                                        ftcAutonomousDisplayState(
                                            nt4Client.latestValues[RobotTopicContract.AUTONOMOUS_STATUS]?.stringValue,
                                        ) == FrcAutonomousDisplayState.BLOCKED
                                    ) {
                                        startFailure = autonomousDetail.ifBlank {
                                            "Autonomous '${autonomousSelection.orEmpty()}' was blocked or failed"
                                        }
                                        return@launch
                                    }
                                    keyboardDriveState.enabled = modeKind == FtcSimulatorOpModeKind.TELEOP
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    startFailure = error.message ?: "Simulator TeleOp start failed"
                                    keyboardDriveState.disarm()
                                } finally {
                                    starting = false
                                }
                            }
                        }
                    }
                }

                val onRunFrcAutoClick: () -> Unit = {
                    if (!starting && !isAutonomousRunning) {
                        val selection = requestedAuto
                        if (selection != null) {
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
                        }
                    }
                }

                val onToggleRecording: () -> Unit = {
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
                }

                val onStopClick: () -> Unit = {
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
                }

                SimulatorActionButtons(
                    primaryAction = primaryAction,
                    canLaunchSimulator = canLaunchSimulator,
                    isFtc = isFtc,
                    selectedOpMode = selectedOpMode,
                    isFtcAutonomousSelection = isFtcAutonomousSelection,
                    onPrimaryActionClick = onPrimaryActionClick,
                    isConnected = isConnected,
                    requestedAuto = requestedAuto,
                    starting = starting,
                    isAutonomousRunning = isAutonomousRunning,
                    autonomousDisplayState = autonomousDisplayState,
                    onRunFrcAutoClick = onRunFrcAutoClick,
                    recordingSession = recordingSession,
                    recordingBusy = recordingBusy,
                    isRunning = isRunning,
                    onToggleRecording = onToggleRecording,
                    onStopClick = onStopClick,
                    useGamepad = keyboardDriveState.useGamepad,
                    onToggleGamepad = {
                        keyboardDriveState.releaseAll()
                        keyboardDriveState.useGamepad = !keyboardDriveState.useGamepad
                    },
                    isKeyboardDriveArmed = keyboardDriveState.enabled,
                    receiverReady = receiverReady,
                    onToggleArmDrive = {
                        if (keyboardDriveState.enabled) keyboardDriveState.disarm()
                        else keyboardDriveState.enabled = true
                    },
                )
            }

            LocalSimulatorStatusHint(
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
                isKeyboardDriveArmed = keyboardDriveState.enabled,
                useGamepad = keyboardDriveState.useGamepad,
                startFailure = startFailure,
                isRunning = isRunning,
                isFtcAutonomousSelection = isFtcAutonomousSelection,
                receiverReady = receiverReady,
                driveReceiverStatusCode = driveReceiverStatusCode,
                activeOpModeDisplayName = activeOpModeDisplayName,
                selectedOpMode = selectedOpMode,
            )
        }
    }
}
