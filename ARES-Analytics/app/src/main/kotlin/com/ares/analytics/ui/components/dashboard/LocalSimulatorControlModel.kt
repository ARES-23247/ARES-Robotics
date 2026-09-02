package com.ares.analytics.ui.components.dashboard

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

internal const val TELEOP_LIST_TOPIC = "ARES/DriverStation/TeleOpList"
internal const val AUTONOMOUS_LIST_TOPIC = "ARES/DriverStation/AutonomousList"
internal const val SELECTED_OPMODE_TOPIC = "ARES/DriverStation/SelectedOpMode"
internal const val DRIVER_STATION_COMMAND_TOPIC = "ARES/DriverStation/Command"
internal const val ACTIVE_OPMODE_CLASS_TOPIC = "ARES/DriverStation/ActiveOpModeClass"
internal const val ACTIVE_OPMODE_DISPLAY_NAME_TOPIC = "ARES/DriverStation/ActiveOpModeDisplayName"
internal const val ACTIVE_OPMODE_STATE_TOPIC = "ARES/DriverStation/ActiveOpModeState"
internal const val FRC_DRIVER_STATION_COMMAND_TOPIC = "ARES/Simulation/FrcDriverStationCommand"
internal const val FRC_DRIVER_STATION_STATE_TOPIC = "ARES/Simulation/FrcDriverStationState"
internal const val FRC_ENABLE_TELEOP_COMMAND = "ENABLE_TELEOP"
internal const val FRC_ENABLE_AUTONOMOUS_COMMAND = "ENABLE_AUTONOMOUS"
internal const val FRC_DISABLE_COMMAND = "DISABLE"
internal const val FRC_TELEOP_ENABLED_STATE = "TELEOP_ENABLED"
internal const val FRC_AUTONOMOUS_ENABLED_STATE = "AUTONOMOUS_ENABLED"
internal const val FRC_WAITING_FOR_CONTROL_STATE = "WAITING_FOR_CONTROL"
internal const val TELEOP_INIT_STATE = "TELEOP_INIT"
internal const val TELEOP_RUNNING_STATE = "TELEOP_RUNNING"
internal const val AUTONOMOUS_INIT_STATE = "AUTO_INIT"
internal const val AUTONOMOUS_RUNNING_STATE = "AUTO_RUNNING"
internal const val OPMODE_ACK_TIMEOUT_MS = 5_000L

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

internal data class SimulatorOpModeSnapshot(
    val activeOpMode: String?,
    val activeState: String?,
    val autonomousStatus: String? = null,
)

internal suspend fun awaitSimulatorOpModeAcknowledgement(
    selectedOpMode: String,
    expectedState: String,
    isConnected: () -> Boolean,
    snapshot: () -> SimulatorOpModeSnapshot,
    acceptedAutonomousStatuses: Set<String> = emptySet(),
    timeoutMs: Long = OPMODE_ACK_TIMEOUT_MS,
): Boolean = withTimeoutOrNull(timeoutMs) {
    while (isConnected()) {
        val current = snapshot()
        val lifecycleAcknowledged = simulatorOpModeAcknowledged(
                selectedOpMode = selectedOpMode,
                activeOpMode = current.activeOpMode,
                activeState = current.activeState,
                expectedState = expectedState,
            )
        val statusAcknowledged = current.autonomousStatus
            ?.trim()
            ?.uppercase()
            ?.let(acceptedAutonomousStatuses::contains) == true
        if (lifecycleAcknowledged || statusAcknowledged) {
            return@withTimeoutOrNull true
        }
        delay(20)
    }
    false
} ?: false

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

internal fun ftcAutonomousDisplayState(autonomousStatus: String?): FrcAutonomousDisplayState =
    when (autonomousStatus?.trim()?.uppercase()) {
        "RUNNING" -> FrcAutonomousDisplayState.RUNNING
        "COMPLETE" -> FrcAutonomousDisplayState.COMPLETE
        "BLOCKED", "FAILED", "CANCELLED" -> FrcAutonomousDisplayState.BLOCKED
        else -> FrcAutonomousDisplayState.INACTIVE
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

internal fun decodeSimulatorOpModes(value: String?): List<String> =
    value?.let { encoded ->
        runCatching { Json.decodeFromString<List<String>>(encoded) }.getOrDefault(emptyList())
    }.orEmpty()

internal enum class FtcSimulatorOpModeKind { TELEOP, AUTONOMOUS }

internal fun ftcSimulatorOpModeKind(
    selected: String?,
    teleOps: List<String>,
    autonomousOpModes: List<String>,
): FtcSimulatorOpModeKind? = when (selected) {
    in autonomousOpModes -> FtcSimulatorOpModeKind.AUTONOMOUS
    in teleOps -> FtcSimulatorOpModeKind.TELEOP
    else -> null
}

internal fun FtcSimulatorOpModeKind.initState(): String = when (this) {
    FtcSimulatorOpModeKind.TELEOP -> TELEOP_INIT_STATE
    FtcSimulatorOpModeKind.AUTONOMOUS -> AUTONOMOUS_INIT_STATE
}

internal fun FtcSimulatorOpModeKind.runningState(): String = when (this) {
    FtcSimulatorOpModeKind.TELEOP -> TELEOP_RUNNING_STATE
    FtcSimulatorOpModeKind.AUTONOMOUS -> AUTONOMOUS_RUNNING_STATE
}

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
