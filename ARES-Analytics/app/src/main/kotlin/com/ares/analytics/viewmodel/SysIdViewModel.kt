package com.ares.analytics.viewmodel

import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.AlignedDataRow
import com.ares.analytics.service.AutoTunerService
import com.ares.analytics.service.AutoTuningDigitalTwin
import com.ares.analytics.service.DigitalTwinEvaluation
import com.ares.analytics.service.TuningApplyState
import com.ares.analytics.shared.models.CalculatedSummary
import com.areslib.control.assist.SysIdMechanism
import com.areslib.control.assist.SysIdRoutine
import com.ares.analytics.viewmodel.sysid.SysIdDataCollector
import com.ares.analytics.viewmodel.sysid.SysIdRegressionSolver
import com.ares.analytics.viewmodel.sysid.SysIdSignalGenerator
import com.ares.analytics.viewmodel.sysid.SysIdSimulationPreview
import com.ares.analytics.viewmodel.sysid.CalibrationCommandTransport
import com.ares.analytics.viewmodel.sysid.Nt4CalibrationCommandTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.ares.analytics.service.tuning.TuningProposalInbox

enum class CalibrationArmPhase { NOT_REQUIRED, DISARMED, ARMING, ARMED }

data class SysIdState(
    val summary: CalculatedSummary? = null,
    val exportStatus: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,

    // Robot connection and live routines
    val isRobotConnected: Boolean = false,
    val isRoutineRunning: Boolean = false,
    val requiresNetworkArm: Boolean = true,
    val calibrationModeEnabled: Boolean = false,
    /** Live mechanisms explicitly advertised by the connected runtime. Empty never implies support. */
    val supportedMechanisms: Set<SysIdMechanism> = emptySet(),
    val capabilitiesKnown: Boolean = false,
    val robotCalibrationArmed: Boolean = false,
    val armPhase: CalibrationArmPhase = CalibrationArmPhase.DISARMED,
    val armStatus: String = "Select the FTC tuning OpMode and press Play before arming",
    val selectedMechanism: SysIdMechanism = SysIdMechanism.LINEAR,
    val liveSamples: List<AlignedDataRow> = emptyList(),

    val tuningRecommendation: AutoTunerService.TuningRecommendation? = null,
    /** Hardware-free walkthrough evidence; never eligible for robot tuning promotion. */
    val simulationEvaluation: DigitalTwinEvaluation? = null,
    val isSimulationRunning: Boolean = false,
    val simulationMessage: String = "Run this teaching model before connecting a robot.",
    val tuningApplyState: TuningApplyState = TuningApplyState(),

    // New Auto-Tuning/Calibration features
    val activeCalibration: String = "NONE", // "NONE", "PINPOINT_SPIN", "TRACK_WIDTH_SPIN", "VISION_CALIBRATION", "LINEAR_DRIVE"
    val liveCalibrationData: List<DoubleArray> = emptyList(),
    val recommendedPinpointXOffsetMm: Double? = null,
    val recommendedPinpointYOffsetMm: Double? = null,
    val recommendedTrackWidthMeters: Double? = null,
    val recommendedVisionStdDevsX: Double? = null,
    val recommendedVisionStdDevsY: Double? = null,
    val recommendedVisionStdDevsHeading: Double? = null,
    val recommendedTicksPerMeter: Double? = null,

    // For linear drive calibration distance input
    val linearDriveActualDistanceMeters: Double = 2.0
)

sealed class SysIdIntent {

    object ClearExportStatus : SysIdIntent()

    // Live routine controls

    data class SetMechanism(val mechanism: SysIdMechanism) : SysIdIntent()

    object RunSimulationPreview : SysIdIntent()

    data class ConfigurePlatform(val requiresNetworkArm: Boolean) : SysIdIntent()

    object ArmCalibration : SysIdIntent()

    data class DisarmCalibration(val reason: String = "Operator disarmed") : SysIdIntent()

    data class StartRoutine(val routine: SysIdRoutine) : SysIdIntent()

    object StopRoutine : SysIdIntent()

    // New Auto-Tuning/Calibration intents

    data class StartCalibration(val calibrationType: String) : SysIdIntent()

    object StopCalibration : SysIdIntent()

    data class SetLinearDriveDistance(val distance: Double) : SysIdIntent()

    data class ApplyCalibration(val calibrationType: String) : SysIdIntent()

    data class ApproveRecommendation(val recommendation: AutoTunerService.TuningRecommendation) : SysIdIntent()

    object RollbackRecommendation : SysIdIntent()
}

/** Coordinates live SysId, isolated teaching previews and reviewed tuning proposals. */
class SysIdViewModel(
    private val autoTunerService: AutoTunerService,
    val nt4ClientService: Nt4ClientService,
    private val scope: CoroutineScope,
    tuningProposalInbox: TuningProposalInbox? = null,
    digitalTwin: AutoTuningDigitalTwin = AutoTuningDigitalTwin(),
    calibrationTransport: CalibrationCommandTransport = Nt4CalibrationCommandTransport(nt4ClientService),
    previewDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(SysIdState())
    val state: StateFlow<SysIdState> = _state.asStateFlow()

    private val simulationPreview = SysIdSimulationPreview(_state, scope, previewDispatcher, digitalTwin, autoTunerService)
    private val regressionSolver = SysIdRegressionSolver(_state)
    private val signalGenerator = SysIdSignalGenerator(nt4ClientService, _state, scope, calibrationTransport, tuningProposalInbox)
    private val dataCollector = SysIdDataCollector(
        nt4ClientService,
        autoTunerService,
        _state,
        scope,
        regressionSolver,
        onRoutineCompleted = { signalGenerator.disarm("Routine complete") }
    )

    private data class ControlIdentity(val connected: Boolean, val replay: Boolean, val epoch: Long, val connection: Long)
    private var controlIdentity: ControlIdentity? = null
    private val controlIdentityLock = Any()

    /** Read current identity at use time: a queued collector event may describe an older target. */
    private fun reconcileControlIdentity(): Boolean = synchronized(controlIdentityLock) {
        val connected = nt4ClientService.isConnected.value
        val replay = nt4ClientService.isReplayActive.value
        val epoch = nt4ClientService.telemetryStore.currentTargetEpoch()
        val connection = nt4ClientService.controlConnectionEpoch
        val previous = controlIdentity
        if (previous == null || previous.connected != connected || previous.replay != replay ||
            previous.epoch != epoch || previous.connection != connection) {
            val current = ControlIdentity(connected, replay, epoch, connection)
            controlIdentity = current
            signalGenerator.connectionLost()
            dataCollector.clearBuffer()
            _state.update { it.copy(isRobotConnected = current.connected,
                armStatus = if (current.connected) "Live control context changed; fresh capabilities and calibration mode required"
                    else "Disconnected; calibration lease revoked") }
        }
        connected && !replay
    }

    init {
        reconcileControlIdentity()
        dataCollector.startCollecting()
        scope.launch {
            combine(nt4ClientService.isConnected, nt4ClientService.isReplayActive,
                nt4ClientService.telemetryStore.targetEpochs) { _, _, _ -> Unit }
                .collect { reconcileControlIdentity() }
        }
        scope.launch {
            nt4ClientService.telemetryFlow.collect { frame ->
                when (frame.key) {
                    "SysId/ModeEnabled", "SysId/Armed", "SysId/SupportedMechanisms", "SysId/Error" -> Unit
                    else -> return@collect
                }
                if (!reconcileControlIdentity() || !nt4ClientService.telemetryStore.isCurrentNotifiedFrame(frame)) return@collect
                when (frame.key) {
                    "SysId/ModeEnabled" -> {
                        val enabled = frame.stringValue == null && frame.value == 1.0
                        _state.update { it.copy(calibrationModeEnabled = enabled) }
                        if (!enabled && _state.value.requiresNetworkArm) {
                            signalGenerator.disarm("FTC calibration mode is not enabled", sendStop = false)
                        }
                    }
                    "SysId/Armed" -> {
                        try { signalGenerator.observeRobotArmed(frame) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            _state.update { it.copy(errorMessage = "Calibration disarmed locally; STOP publication failed: ${failure.message}") }
                        }
                    }
                    "SysId/SupportedMechanisms" -> {
                        val supported = parseSupportedSysIdMechanisms(frame.stringValue.orEmpty())
                        _state.update {
                            it.copy(
                                supportedMechanisms = supported,
                                capabilitiesKnown = true,
                                errorMessage = it.errorMessage?.takeUnless { message ->
                                    message.startsWith("Live SysId is unavailable")
                                },
                            )
                        }
                    }
                    "SysId/Error" -> frame.stringValue?.takeIf(String::isNotBlank)?.let { error ->
                        _state.update { it.copy(errorMessage = "Robot calibration safety: $error") }
                    }
                }
            }
        }
        scope.launch {
            autoTunerService.applyState.collect { workflow ->
                _state.update { it.copy(tuningApplyState = workflow) }
            }
        }
    }

    fun onIntent(intent: SysIdIntent) {
        scope.launch {
            reconcileControlIdentity()
            when (intent) {
                is SysIdIntent.ClearExportStatus -> {
                    _state.update { it.copy(exportStatus = "") }
                }
                is SysIdIntent.SetMechanism -> simulationPreview.selectMechanism(intent.mechanism)
                is SysIdIntent.RunSimulationPreview -> simulationPreview.start()
                is SysIdIntent.ConfigurePlatform -> {
                    signalGenerator.configurePlatform(intent.requiresNetworkArm)
                }
                is SysIdIntent.ArmCalibration -> signalGenerator.arm()
                is SysIdIntent.DisarmCalibration -> signalGenerator.disarm(intent.reason)
                is SysIdIntent.StartRoutine -> {
                    if (!motionCommandsAllowed()) {
                        _state.update { it.copy(errorMessage = liveMotionBlockReason(it)) }
                        return@launch
                    }
                    dataCollector.clearBuffer()
                    simulationPreview.cancelPending()
                    signalGenerator.startRoutine(_state.value.selectedMechanism, intent.routine)
                }
                is SysIdIntent.StopRoutine -> {
                    dataCollector.clearBuffer()
                    signalGenerator.stopRoutine()
                }
                is SysIdIntent.StartCalibration -> {
                    if (!motionCommandsAllowed()) {
                        _state.update { it.copy(errorMessage = "Calibration is not safely armed") }
                        return@launch
                    }
                    dataCollector.clearBuffer()
                    simulationPreview.cancelPending()
                    signalGenerator.startCalibration(intent.calibrationType)
                }
                is SysIdIntent.StopCalibration -> {
                    dataCollector.clearBuffer()
                    signalGenerator.stopCalibration()
                }
                is SysIdIntent.SetLinearDriveDistance -> {
                    _state.update { it.copy(linearDriveActualDistanceMeters = intent.distance) }
                }
                is SysIdIntent.ApplyCalibration -> {
                    signalGenerator.applyCalibration(intent.calibrationType)
                }
                is SysIdIntent.ApproveRecommendation -> {
                    autoTunerService.approveAndApplyGains(intent.recommendation)
                }
                is SysIdIntent.RollbackRecommendation -> {
                    autoTunerService.rollback()
                }
            }
        }
    }

    private fun motionCommandsAllowed(): Boolean = _state.value.let { current ->
        current.isRobotConnected && current.capabilitiesKnown &&
            current.selectedMechanism in current.supportedMechanisms &&
            (!current.requiresNetworkArm ||
                (current.calibrationModeEnabled && signalGenerator.hasActiveArmLease() &&
                    current.armPhase == CalibrationArmPhase.ARMED && current.robotCalibrationArmed))
    }

    private fun liveMotionBlockReason(current: SysIdState): String = when {
        !current.isRobotConnected -> "Connect a robot or simulator before running a measured SysId routine"
        !current.capabilitiesKnown -> "Live SysId is unavailable until the connected runtime advertises its supported mechanisms"
        current.selectedMechanism !in current.supportedMechanisms ->
            "Live ${current.selectedMechanism.name.lowercase()} SysId is not implemented by this robot runtime. The hardware-free lesson is still available."
        else -> "Calibration is not safely armed"
    }
}

internal fun parseSupportedSysIdMechanisms(raw: String): Set<SysIdMechanism> = raw
    .split(',', ';')
    .asSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .mapNotNull { token -> SysIdMechanism.entries.firstOrNull { it.name.equals(token, ignoreCase = true) } }
    .toSet()
