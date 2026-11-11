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
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.di.KeyboardDriveState
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.RobotTopicContract
import com.ares.analytics.service.project.persistence.AutonomousCatalogProjectRepository
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.SIMULATION_SESSION_TAG
import com.ares.analytics.shared.models.Session
import com.ares.analytics.ui.help.StudentFriendlyDiagnosticCallout
import com.ares.analytics.ui.help.translateDiagnosticForStudent
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

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
    val controller = rememberLocalSimulatorSessionController(
        nt4Client = nt4Client,
        keyboardDriveState = keyboardDriveState,
        league = league,
        projectPath = projectPath,
        isConnected = isConnected,
    )
    val scope = rememberCoroutineScope()
    val connectedNow by rememberUpdatedState(isConnected)
    val recordingSession by nt4Client.currentSession.collectAsState()

    val isFtc = controller.isFtc
    val isFtcAutonomousSelection = controller.isFtcAutonomousSelection
    val isRunning = controller.isRunning(isConnected)
    val isAutonomousRunning = controller.isAutonomousRunning(isConnected)
    val autonomousDisplayState = controller.autonomousDisplayState(isConnected)
    val receiverReady = controller.receiverReady
    val primaryAction = localSimulatorPrimaryAction(
        isConnected = isConnected,
        isSimulatorProcessRunning = isSimulatorProcessRunning,
        isLaunchPreparationRunning = isLaunchPreparationRunning,
        launchRequiresVerification = launchRequiresVerification,
        isTeleOpStarting = controller.starting,
        isTeleOpRunning = isRunning,
    )
    val statusText = controller.statusText(
        isConnected = isConnected,
        isSimulatorProcessRunning = isSimulatorProcessRunning,
        keyboardDriveEnabled = keyboardDriveState.enabled,
    )
    val statusColor = controller.statusColor(
        isConnected = isConnected,
        keyboardDriveEnabled = keyboardDriveState.enabled,
    )
    val readinessSteps = computeSimulatorReadinessSteps(
        isConnected = isConnected,
        isSimulatorProcessRunning = isSimulatorProcessRunning,
        isLaunchPreparationRunning = isLaunchPreparationRunning,
        isStarting = controller.starting,
        isRunning = isRunning,
        isAutonomousRunning = isAutonomousRunning,
        isAutonomousSelection = isFtcAutonomousSelection,
        isKeyboardDriveArmed = keyboardDriveState.enabled,
        receiverReady = receiverReady,
    )

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
                    starting = controller.starting,
                    selectedOpMode = controller.selectedOpMode,
                    onSelectedOpModeChanged = { controller.selectedOpMode = it },
                    teleOps = controller.teleOps,
                    autonomousOpModes = controller.autonomousOpModes,
                    availableAutos = controller.availableAutos,
                    requestedAuto = controller.requestedAuto,
                    onRequestedAutoChanged = { controller.requestedAuto = it },
                    onDisarmDrive = { keyboardDriveState.disarm() },
                    modifier = Modifier.weight(1f),
                )

                if (isFtcAutonomousSelection) {
                    FtcAutonomousRoutineSelector(
                        expanded = controller.ftcRoutineSelectorExpanded,
                        enabled = isConnected && !controller.starting,
                        availableAutos = controller.availableAutos,
                        labels = controller.autonomousLabels,
                        selectedAuto = controller.requestedAuto,
                        onExpandedChange = { controller.ftcRoutineSelectorExpanded = it },
                        onSelected = { controller.requestedAuto = it },
                    )
                }

                SimulatorActionButtons(
                    primaryAction = primaryAction,
                    canLaunchSimulator = canLaunchSimulator,
                    isFtc = isFtc,
                    selectedOpMode = controller.selectedOpMode,
                    isFtcAutonomousSelection = isFtcAutonomousSelection,
                    onPrimaryActionClick = {
                        controller.handlePrimaryActionClick(
                            primaryAction = primaryAction,
                            scope = scope,
                            nt4Client = nt4Client,
                            keyboardDriveState = keyboardDriveState,
                            isConnected = { connectedNow },
                            onLaunchSimulator = onLaunchSimulator,
                        )
                    },
                    isConnected = isConnected,
                    requestedAuto = controller.requestedAuto,
                    starting = controller.starting,
                    isAutonomousRunning = isAutonomousRunning,
                    autonomousDisplayState = autonomousDisplayState,
                    onRunFrcAutoClick = {
                        controller.handleRunFrcAutoClick(
                            isConnected = isConnected,
                            scope = scope,
                            nt4Client = nt4Client,
                            keyboardDriveState = keyboardDriveState,
                            connectedNow = { connectedNow },
                        )
                    },
                    recordingSession = recordingSession,
                    recordingBusy = controller.recordingBusy,
                    isRunning = isRunning,
                    onToggleRecording = {
                        controller.handleToggleRecording(
                            scope = scope,
                            nt4Client = nt4Client,
                            recordingSession = recordingSession,
                            teamId = teamId,
                            seasonId = seasonId,
                            robotId = robotId,
                            onRecordingSaved = onRecordingSaved,
                        )
                    },
                    onStopClick = {
                        controller.handleStopClick(
                            scope = scope,
                            nt4Client = nt4Client,
                            keyboardDriveState = keyboardDriveState,
                            recordingSession = recordingSession,
                            onRecordingSaved = onRecordingSaved,
                        )
                    },
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

            SimulatorReadinessStepperRow(steps = readinessSteps)

            LocalSimulatorStatusHint(
                recordingFailure = controller.recordingFailure,
                recordingSession = recordingSession,
                recordingMessage = controller.recordingMessage,
                isConnected = isConnected,
                isLaunchPreparationRunning = isLaunchPreparationRunning,
                isSimulatorProcessRunning = isSimulatorProcessRunning,
                canLaunchSimulator = canLaunchSimulator,
                simulatorLaunchDisabledReason = simulatorLaunchDisabledReason,
                launchRequiresVerification = launchRequiresVerification,
                isFtc = isFtc,
                autonomousDisplayState = autonomousDisplayState,
                robotSelectedAuto = controller.robotSelectedAuto,
                requestedAuto = controller.requestedAuto,
                availableAutos = controller.availableAutos,
                frcDriverStationState = controller.frcDriverStationState,
                isKeyboardDriveArmed = keyboardDriveState.enabled,
                useGamepad = keyboardDriveState.useGamepad,
                startFailure = controller.startFailure,
                isRunning = isRunning,
                isFtcAutonomousSelection = isFtcAutonomousSelection,
                receiverReady = receiverReady,
                driveReceiverStatusCode = controller.driveReceiverStatusCode,
                activeOpModeDisplayName = controller.activeOpModeDisplayName,
                selectedOpMode = controller.selectedOpMode,
            )
        }
    }
}

@Composable
internal fun SimulatorReadinessStepperRow(
    steps: List<SimulatorReadinessStep>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEach { step ->
            val accentColor = when (step.status) {
                SimulatorStepStatus.COMPLETE -> AresGreen
                SimulatorStepStatus.ACTIVE -> AresCyan
                SimulatorStepStatus.PENDING -> AresTextTertiary
            }
            val bgColor = when (step.status) {
                SimulatorStepStatus.COMPLETE -> AresGreen.copy(alpha = 0.08f)
                SimulatorStepStatus.ACTIVE -> AresCyan.copy(alpha = 0.12f)
                SimulatorStepStatus.PENDING -> AresSurfaceElevated
            }
            val borderColor = when (step.status) {
                SimulatorStepStatus.COMPLETE -> AresGreen.copy(alpha = 0.45f)
                SimulatorStepStatus.ACTIVE -> AresCyan.copy(alpha = 0.65f)
                SimulatorStepStatus.PENDING -> AresBorder.copy(alpha = 0.6f)
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .background(bgColor, RoundedCornerShape(7.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(7.dp))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(
                            if (step.status == SimulatorStepStatus.COMPLETE) AresGreen else accentColor.copy(alpha = 0.2f),
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (step.status == SimulatorStepStatus.COMPLETE) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = AresOnAccent,
                            modifier = Modifier.size(11.dp),
                        )
                    } else {
                        Text(
                            text = step.stepNumber.toString(),
                            color = accentColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = step.title,
                        color = if (step.status == SimulatorStepStatus.PENDING) AresTextSecondary else AresTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = if (step.status == SimulatorStepStatus.ACTIVE) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        text = step.actionHint,
                        color = accentColor,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
