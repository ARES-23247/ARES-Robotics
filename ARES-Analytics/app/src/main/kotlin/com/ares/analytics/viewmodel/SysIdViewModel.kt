package com.ares.analytics.viewmodel

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.DriverAnalysisService
import com.ares.analytics.service.DriverProfileAnalysisResult
import com.ares.analytics.service.SysIdService
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.AlignedDataRow
import com.ares.analytics.service.AutoTunerService
import com.ares.analytics.service.AutoTuningDigitalTwin
import com.ares.analytics.service.DigitalTwinEvaluation
import com.ares.analytics.service.TuningApplyState
import com.ares.analytics.shared.models.CalculatedSummary
import com.ares.analytics.shared.TelemetryMetricCatalog
import com.areslib.control.assist.SysIdMechanism
import com.areslib.control.assist.SysIdRoutine
import com.ares.analytics.viewmodel.sysid.SysIdDataCollector
import com.ares.analytics.viewmodel.sysid.SysIdRegressionSolver
import com.ares.analytics.viewmodel.sysid.SysIdSignalGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ares.analytics.service.tuning.TuningProposalInbox

enum class CalibrationArmPhase { NOT_REQUIRED, DISARMED, ARMING, ARMED }

data class SysIdState(
    val sessionId: String? = null,
    val summary: CalculatedSummary? = null,
    val jitterResult: DriverProfileAnalysisResult? = null,
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

    // Standalone file upload analysis
    val localAnalysisResult: CalculatedSummary? = null,
    val fileAnalysisError: String? = null,
    val tuningRecommendation: AutoTunerService.TuningRecommendation? = null,
    /** Hardware-free walkthrough evidence; never eligible for robot tuning promotion. */
    val simulationEvaluation: DigitalTwinEvaluation? = null,
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

    data class LoadSession(val sessionId: String?) : SysIdIntent()

    data class ApplyToRobotCode(
        val recommendedExponent: Double,
        val recommendedSlewRate: Double,
        val projectPath: String
    ) : SysIdIntent()

    object ClearExportStatus : SysIdIntent()

    // Live routine controls

    data class SetMechanism(val mechanism: SysIdMechanism) : SysIdIntent()

    object RunSimulationPreview : SysIdIntent()

    data class ConfigurePlatform(val requiresNetworkArm: Boolean) : SysIdIntent()

    object ArmCalibration : SysIdIntent()

    data class DisarmCalibration(val reason: String = "Operator disarmed") : SysIdIntent()

    data class StartRoutine(val routine: SysIdRoutine) : SysIdIntent()

    object StopRoutine : SysIdIntent()

    // Standalone log analysis

    data class LoadLocalLogFile(val fileContent: String) : SysIdIntent()

    object ClearLocalAnalysis : SysIdIntent()

    // New Auto-Tuning/Calibration intents

    data class StartCalibration(val calibrationType: String) : SysIdIntent()

    object StopCalibration : SysIdIntent()

    data class SetLinearDriveDistance(val distance: Double) : SysIdIntent()

    data class ApplyCalibration(val calibrationType: String) : SysIdIntent()

    data class ApproveRecommendation(val recommendation: AutoTunerService.TuningRecommendation) : SysIdIntent()

    object RollbackRecommendation : SysIdIntent()
}

/** Coordinates SysId signal generation, frame collection, regression, and optional source updates. */
class SysIdViewModel(
    private val databaseService: DatabaseService,
    private val sysIdService: SysIdService,
    private val driverAnalysisService: DriverAnalysisService,
    private val autoTunerService: AutoTunerService,
    val nt4ClientService: Nt4ClientService,
    private val scope: CoroutineScope,
    tuningProposalInbox: TuningProposalInbox? = null,
    private val digitalTwin: AutoTuningDigitalTwin = AutoTuningDigitalTwin(),
) {
    private val _state = MutableStateFlow(SysIdState())
    val state: StateFlow<SysIdState> = _state.asStateFlow()

    private val regressionSolver = SysIdRegressionSolver(nt4ClientService, _state)
    private val signalGenerator = SysIdSignalGenerator(nt4ClientService, _state, scope, tuningProposalInbox = tuningProposalInbox)
    private val dataCollector = SysIdDataCollector(
        nt4ClientService,
        sysIdService,
        autoTunerService,
        _state,
        scope,
        regressionSolver,
        onRoutineCompleted = { signalGenerator.disarm("Routine complete") }
    )

    init {
        dataCollector.startCollecting()
        scope.launch {
            nt4ClientService.isConnected.collect { connected ->
                _state.update {
                    it.copy(
                        isRobotConnected = connected,
                        supportedMechanisms = if (connected) it.supportedMechanisms else emptySet(),
                        capabilitiesKnown = if (connected) it.capabilitiesKnown else false,
                    )
                }
                if (!connected) signalGenerator.connectionLost()
            }
        }
        scope.launch {
            nt4ClientService.telemetryFlow.collect { frame ->
                when (frame.key) {
                    "SysId/ModeEnabled" -> {
                        val enabled = frame.value != 0.0
                        _state.update { it.copy(calibrationModeEnabled = enabled) }
                        if (!enabled && _state.value.requiresNetworkArm) {
                            signalGenerator.disarm("FTC calibration mode is not enabled", sendStop = false)
                        }
                    }
                    "SysId/Armed" -> {
                        val armed = frame.value != 0.0
                        _state.update {
                            it.copy(
                                robotCalibrationArmed = armed,
                                armPhase = when {
                                    !it.requiresNetworkArm -> CalibrationArmPhase.NOT_REQUIRED
                                    armed -> CalibrationArmPhase.ARMED
                                    it.armPhase == CalibrationArmPhase.ARMED -> CalibrationArmPhase.DISARMED
                                    else -> it.armPhase
                                },
                                armStatus = when {
                                    armed -> "FTC robot acknowledged the fresh calibration lease"
                                    it.armPhase == CalibrationArmPhase.ARMED -> "FTC robot disarmed calibration"
                                    else -> it.armStatus
                                }
                            )
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
            when (intent) {
                is SysIdIntent.LoadSession -> {
                    val sessionId = intent.sessionId
                    _state.update { it.copy(sessionId = sessionId, isLoading = true, summary = null, jitterResult = null, errorMessage = null) }
                    if (sessionId != null) {
                        try {
                            withContext(Dispatchers.IO) {
                                val summaryResult = sysIdService.analyzeMotorData(
                                    sessionId = sessionId,
                                    voltageKey = TelemetryMetricCatalog.DRIVE_VOLTAGE.canonicalKey,
                                    velocityKey = TelemetryMetricCatalog.DRIVE_VELOCITY.canonicalKey,
                                    accelerationKey = TelemetryMetricCatalog.DRIVE_ACCELERATION.canonicalKey
                                )
                                val jitterResult = driverAnalysisService.analyzeDriverJitter(
                                    sessionId = sessionId
                                )
                                _state.update {
                                    it.copy(
                                        summary = summaryResult,
                                        jitterResult = jitterResult,
                                        isLoading = false
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            _state.update { it.copy(isLoading = false, errorMessage = e.message ?: "Failed to perform analysis") }
                        }
                    } else {
                        _state.update { it.copy(isLoading = false) }
                    }
                }
                is SysIdIntent.ApplyToRobotCode -> {
                    signalGenerator.applyToRobotCode(intent.recommendedExponent, intent.recommendedSlewRate)
                }
                is SysIdIntent.ClearExportStatus -> {
                    _state.update { it.copy(exportStatus = "") }
                }
                is SysIdIntent.SetMechanism -> {
                    _state.update {
                        it.copy(
                            selectedMechanism = intent.mechanism,
                            simulationEvaluation = null,
                            simulationMessage = "Run the ${intent.mechanism.name.lowercase()} teaching model before connecting a robot.",
                        )
                    }
                }
                is SysIdIntent.RunSimulationPreview -> {
                    val mechanism = _state.value.selectedMechanism
                    val evaluation = withContext(Dispatchers.Default) {
                        digitalTwin.evaluate(AutoTuningDigitalTwin.teachingScenario(mechanism)) { selected, samples, source ->
                            autoTunerService.analyzeSamples(selected, samples, source)
                        }
                    }
                    val passed = evaluation.recoveredWithinTolerance && evaluation.closedLoop?.stable == true
                    _state.update {
                        it.copy(
                            simulationEvaluation = evaluation,
                            simulationMessage = if (passed) {
                                "Simulation verified: the workflow recovered this known teaching plant and its bounded closed-loop preview stayed stable."
                            } else {
                                "Simulation needs review: inspect data quality and the bounded prediction before any measured experiment."
                            },
                        )
                    }
                }
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
                    signalGenerator.startRoutine(_state.value.selectedMechanism, intent.routine)
                }
                is SysIdIntent.StopRoutine -> {
                    signalGenerator.stopRoutine()
                    signalGenerator.disarm("Operator stopped SysId")
                }
                is SysIdIntent.LoadLocalLogFile -> {
                    _state.update { it.copy(isLoading = true, fileAnalysisError = null, localAnalysisResult = null) }
                    try {
                        val rows = withContext(Dispatchers.IO) {
                            dataCollector.parseLogFile(intent.fileContent)
                        }
                        if (rows.size < 10) {
                            _state.update { it.copy(isLoading = false, fileAnalysisError = "Not enough valid data rows found in file (minimum 10 required)") }
                        } else {
                            val summary = withContext(Dispatchers.IO) {
                                sysIdService.analyzeRawData(rows)
                            }
                            _state.update { it.copy(isLoading = false, localAnalysisResult = summary) }
                        }
                    } catch (e: Exception) {
                        _state.update { it.copy(isLoading = false, fileAnalysisError = "Failed to parse file: ${e.message}") }
                    }
                }
                is SysIdIntent.ClearLocalAnalysis -> {
                    _state.update { it.copy(localAnalysisResult = null, fileAnalysisError = null) }
                }
                is SysIdIntent.StartCalibration -> {
                    if (!motionCommandsAllowed()) {
                        _state.update { it.copy(errorMessage = "Calibration is not safely armed") }
                        return@launch
                    }
                    dataCollector.clearBuffer()
                    signalGenerator.startCalibration(intent.calibrationType)
                }
                is SysIdIntent.StopCalibration -> {
                    signalGenerator.stopCalibration()
                    signalGenerator.disarm("Operator aborted calibration")
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
                (current.armPhase == CalibrationArmPhase.ARMED && current.robotCalibrationArmed))
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
