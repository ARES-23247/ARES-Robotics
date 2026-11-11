package com.ares.analytics.ui.components.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.ares.analytics.di.KeyboardDriveState
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.RobotTopicContract
import com.ares.analytics.service.project.persistence.AutonomousCatalogProjectRepository
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.SIMULATION_SESSION_TAG
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresTextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Stateful session controller for [LocalSimulatorControlBar].
 *
 * Encapsulates FTC OpMode lifecycle (`INIT` -> `START` -> `STOP`), FRC Driver Station simulation
 * handshake (`ENABLE_TELEOP`, `ENABLE_AUTONOMOUS`, `DISABLE`), autonomous catalog resolution,
 * and simulation run recording so [LocalSimulatorControlBar] remains a focused declarative view.
 */
internal class LocalSimulatorSessionController(
    val league: League,
    initialTeleOps: List<String> = emptyList(),
    initialAutonomousOpModes: List<String> = emptyList(),
    initialSelectedOpMode: String? = null,
    initialCommand: String = "STOP",
    initialActiveOpMode: String? = null,
    initialActiveOpModeDisplayName: String? = null,
    initialActiveOpModeState: String? = null,
    initialFrcDriverStationState: String? = null,
    initialAvailableAutos: List<String> = emptyList(),
    initialRobotSelectedAuto: String = "",
    initialAutonomousStatus: String = "Waiting for robot",
    initialAutonomousDetail: String = "",
    initialDriveReceiverStatusCode: Int? = null,
    initialDriveReceiverLeaseAgeMs: Long? = null,
) {
    var teleOps by mutableStateOf(initialTeleOps)
    var autonomousOpModes by mutableStateOf(initialAutonomousOpModes)
    var selectedOpMode by mutableStateOf(
        initialSelectedOpMode?.takeIf { it.isNotBlank() } ?: preferredSimulatorTeleOp(initialTeleOps),
    )
    var command by mutableStateOf(initialCommand)
    var activeOpMode by mutableStateOf(initialActiveOpMode?.takeIf(String::isNotBlank))
    var activeOpModeDisplayName by mutableStateOf(initialActiveOpModeDisplayName?.takeIf(String::isNotBlank))
    var activeOpModeState by mutableStateOf(initialActiveOpModeState)
    var frcDriverStationState by mutableStateOf(initialFrcDriverStationState)
    var availableAutos by mutableStateOf(initialAvailableAutos)
    var robotSelectedAuto by mutableStateOf(initialRobotSelectedAuto)
    var requestedAuto by mutableStateOf(
        preferredSimulatorAutonomous(initialAvailableAutos, null, initialRobotSelectedAuto),
    )
    var autonomousStatus by mutableStateOf(initialAutonomousStatus.ifBlank { "Idle" })
    var autonomousDetail by mutableStateOf(initialAutonomousDetail)
    var driveReceiverStatusCode by mutableStateOf(initialDriveReceiverStatusCode)
    var driveReceiverLeaseAgeMs by mutableStateOf(initialDriveReceiverLeaseAgeMs)

    var starting by mutableStateOf(false)
    var startFailure by mutableStateOf<String?>(null)
    var ftcRoutineSelectorExpanded by mutableStateOf(false)
    var autonomousLabels by mutableStateOf<Map<String, String>>(emptyMap())
    var startJob by mutableStateOf<Job?>(null)
    var recordingBusy by mutableStateOf(false)
    var recordingMessage by mutableStateOf<String?>(null)
    var recordingFailure by mutableStateOf<String?>(null)

    val isFtc: Boolean
        get() = league == League.FTC

    val ftcModeKind: FtcSimulatorOpModeKind?
        get() = ftcSimulatorOpModeKind(selectedOpMode, teleOps, autonomousOpModes)

    val isFtcAutonomousSelection: Boolean
        get() = ftcModeKind == FtcSimulatorOpModeKind.AUTONOMOUS

    fun isRunning(isConnected: Boolean): Boolean = isConnected && (
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

    fun isAutonomousRunning(isConnected: Boolean): Boolean = isConnected && !starting && if (isFtc) {
        isFtcAutonomousSelection && isRunning(isConnected)
    } else {
        frcSimulatorAutonomousEnabled(frcDriverStationState)
    }

    fun autonomousDisplayState(isConnected: Boolean): FrcAutonomousDisplayState =
        if (isConnected && !isFtc) {
            frcAutonomousDisplayState(frcDriverStationState, autonomousStatus)
        } else if (isConnected && isFtcAutonomousSelection) {
            ftcAutonomousDisplayState(autonomousStatus)
        } else {
            FrcAutonomousDisplayState.INACTIVE
        }

    val receiverReady: Boolean
        get() = simulatorDriveReceiverReady(
            statusCode = driveReceiverStatusCode,
            leaseAgeMs = driveReceiverLeaseAgeMs,
        )

    fun statusText(
        isConnected: Boolean,
        isSimulatorProcessRunning: Boolean,
        keyboardDriveEnabled: Boolean,
    ): String {
        val running = isRunning(isConnected)
        val autoDisplay = autonomousDisplayState(isConnected)
        return when {
            !isConnected && isSimulatorProcessRunning -> "CONNECTING"
            !isConnected -> "OFFLINE"
            starting -> "STARTING"
            startFailure != null -> "START FAILED"
            running && !isFtcAutonomousSelection && keyboardDriveEnabled && !receiverReady -> "CONTROL RECOVERING"
            running && !isFtcAutonomousSelection && keyboardDriveEnabled -> simulatorDriveReceiverStatus(driveReceiverStatusCode)
            autoDisplay == FrcAutonomousDisplayState.COMPLETE -> "AUTONOMOUS COMPLETE"
            autoDisplay == FrcAutonomousDisplayState.BLOCKED -> "AUTONOMOUS BLOCKED"
            autoDisplay == FrcAutonomousDisplayState.RUNNING -> "AUTONOMOUS RUNNING"
            isFtcAutonomousSelection && running -> "AUTONOMOUS RUNNING"
            running -> "TELEOP RUNNING"
            !isFtc && frcDriverStationState == FRC_WAITING_FOR_CONTROL_STATE -> "WAITING FOR SAFE CONTROL"
            command == "INIT" -> "INITIALIZED"
            else -> "WAITING FOR TELEOP"
        }
    }

    fun statusColor(
        isConnected: Boolean,
        keyboardDriveEnabled: Boolean,
    ): Color {
        val running = isRunning(isConnected)
        val autoRunning = isAutonomousRunning(isConnected)
        val autoDisplay = autonomousDisplayState(isConnected)
        return when {
            !isConnected -> AresTextSecondary
            startFailure != null || autoDisplay == FrcAutonomousDisplayState.BLOCKED -> AresError
            starting || command == "INIT" || (running && keyboardDriveEnabled && !receiverReady) -> AresAmber
            running || autoRunning -> AresGreen
            else -> AresCyan
        }
    }

    fun applyTelemetryFrame(frame: TelemetryFrame) {
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

    fun applyLocalAutonomousCatalog(localIds: List<String>, labels: Map<String, String>) {
        autonomousLabels = labels
        if (availableAutos.isEmpty() && localIds.isNotEmpty()) {
            availableAutos = localIds
        }
        requestedAuto = preferredSimulatorAutonomous(availableAutos, requestedAuto, robotSelectedAuto)
    }

    fun onDisconnected(keyboardDriveState: KeyboardDriveState) {
        startJob?.cancel()
        startJob = null
        starting = false
        startFailure = null
        keyboardDriveState.disarm()
    }

    suspend fun stopAndSaveRecording(
        nt4Client: Nt4ClientService,
        recordingSession: Session?,
        onRecordingSaved: () -> Unit,
    ) {
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

    fun handlePrimaryActionClick(
        primaryAction: LocalSimulatorPrimaryAction,
        scope: CoroutineScope,
        nt4Client: Nt4ClientService,
        keyboardDriveState: KeyboardDriveState,
        isConnected: () -> Boolean,
        onLaunchSimulator: () -> Unit,
    ) {
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
                                while (isConnected() && !frcSimulatorTeleOpEnabled(frcDriverStationState)) delay(20)
                                isConnected()
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
                            isConnected = isConnected,
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
                            isConnected = isConnected,
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

    fun handleRunFrcAutoClick(
        isConnected: Boolean,
        scope: CoroutineScope,
        nt4Client: Nt4ClientService,
        keyboardDriveState: KeyboardDriveState,
        connectedNow: () -> Boolean,
    ) {
        if (!starting && !isAutonomousRunning(isConnected)) {
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
                                connectedNow() &&
                                (!frcSimulatorAutonomousEnabled(frcDriverStationState) ||
                                    robotSelectedAuto != selection)
                            ) {
                                delay(20)
                            }
                            connectedNow()
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

    fun handleToggleRecording(
        scope: CoroutineScope,
        nt4Client: Nt4ClientService,
        recordingSession: Session?,
        teamId: String,
        seasonId: String,
        robotId: String,
        onRecordingSaved: () -> Unit,
    ) {
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
                    stopAndSaveRecording(nt4Client, recordingSession, onRecordingSaved)
                }
            } catch (failure: Exception) {
                recordingFailure = failure.message ?: "Recording could not be changed."
            } finally {
                recordingBusy = false
            }
        }
    }

    fun handleStopClick(
        scope: CoroutineScope,
        nt4Client: Nt4ClientService,
        keyboardDriveState: KeyboardDriveState,
        recordingSession: Session?,
        onRecordingSaved: () -> Unit,
    ) {
        startJob?.cancel()
        startJob = null
        starting = false
        command = "STOP"
        keyboardDriveState.disarm()
        scope.launch {
            stopAndSaveRecording(nt4Client, recordingSession, onRecordingSaved)
            nt4Client.publishString(
                if (isFtc) DRIVER_STATION_COMMAND_TOPIC else FRC_DRIVER_STATION_COMMAND_TOPIC,
                if (isFtc) "STOP" else FRC_DISABLE_COMMAND,
            )
        }
    }
}

@Composable
internal fun rememberLocalSimulatorSessionController(
    nt4Client: Nt4ClientService,
    keyboardDriveState: KeyboardDriveState,
    league: League,
    projectPath: String,
    isConnected: Boolean,
): LocalSimulatorSessionController {
    val controller = remember(nt4Client, league) {
        val initialTeleOps = decodeSimulatorOpModes(nt4Client.latestValues[TELEOP_LIST_TOPIC]?.stringValue)
        val initialAutos = parseAvailableAutoDocuments(
            nt4Client.latestValues[RobotTopicContract.AVAILABLE_AUTONOMOUS_ROUTINES]?.stringValue,
        )
        val initialRobotAuto = nt4Client.latestValues[RobotTopicContract.SELECTED_AUTONOMOUS_ROUTINE]
            ?.stringValue
            .orEmpty()
        LocalSimulatorSessionController(
            league = league,
            initialTeleOps = initialTeleOps,
            initialAutonomousOpModes = decodeSimulatorOpModes(nt4Client.latestValues[AUTONOMOUS_LIST_TOPIC]?.stringValue),
            initialSelectedOpMode = nt4Client.latestValues[SELECTED_OPMODE_TOPIC]?.stringValue,
            initialCommand = nt4Client.latestValues[DRIVER_STATION_COMMAND_TOPIC]?.stringValue ?: "STOP",
            initialActiveOpMode = nt4Client.latestValues[ACTIVE_OPMODE_CLASS_TOPIC]?.stringValue,
            initialActiveOpModeDisplayName = nt4Client.latestValues[ACTIVE_OPMODE_DISPLAY_NAME_TOPIC]?.stringValue,
            initialActiveOpModeState = nt4Client.latestValues[ACTIVE_OPMODE_STATE_TOPIC]?.stringValue,
            initialFrcDriverStationState = nt4Client.latestValues[FRC_DRIVER_STATION_STATE_TOPIC]?.stringValue,
            initialAvailableAutos = initialAutos,
            initialRobotSelectedAuto = initialRobotAuto,
            initialAutonomousStatus = nt4Client.latestValues[RobotTopicContract.AUTONOMOUS_STATUS]?.stringValue
                ?.ifBlank { "Idle" } ?: "Waiting for robot",
            initialAutonomousDetail = nt4Client.latestValues[RobotTopicContract.AUTONOMOUS_DETAIL]?.stringValue.orEmpty(),
            initialDriveReceiverStatusCode = nt4Client.driveInputAcknowledgement.value?.statusCode,
            initialDriveReceiverLeaseAgeMs = nt4Client.driveInputAcknowledgement.value?.leaseAgeMs,
        )
    }

    LaunchedEffect(projectPath) {
        val localEntries = withContext(Dispatchers.IO) {
            AutonomousCatalogProjectRepository().load(projectPath).getOrNull()?.entries.orEmpty()
                .filter { it.enabled }
                .sortedWith(compareBy({ it.sortOrder }, { it.entryId }))
        }
        controller.applyLocalAutonomousCatalog(
            localIds = localEntries.map { it.entryId },
            labels = localEntries.associate { it.entryId to it.displayName },
        )
    }

    LaunchedEffect(nt4Client) {
        nt4Client.uiTelemetryFlow.collect { frame ->
            controller.applyTelemetryFrame(frame)
        }
    }

    LaunchedEffect(nt4Client) {
        nt4Client.driveInputAcknowledgement.collect { acknowledgement ->
            controller.driveReceiverStatusCode = acknowledgement?.statusCode
            controller.driveReceiverLeaseAgeMs = acknowledgement?.leaseAgeMs
        }
    }

    LaunchedEffect(isConnected) {
        if (!isConnected) {
            controller.onDisconnected(keyboardDriveState)
        }
    }

    return controller
}
