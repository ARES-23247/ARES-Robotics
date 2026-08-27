package com.ares.analytics.viewmodel.sysid

import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.viewmodel.SysIdState
import com.areslib.control.assist.SysIdMechanism
import com.areslib.control.assist.SysIdRoutine
import com.ares.analytics.service.tuning.ExternalTuningProposal
import com.ares.analytics.service.tuning.TuningParameterKeys
import com.ares.analytics.service.tuning.TuningProposalInbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import com.ares.analytics.viewmodel.CalibrationArmPhase

/** Publishes SysId routine commands and calibration controls through NT4. */
class SysIdSignalGenerator(
    private val nt4ClientService: Nt4ClientService,
    private val _state: MutableStateFlow<SysIdState>,
    private val scope: CoroutineScope,
    private val calibrationTransport: CalibrationCommandTransport = Nt4CalibrationCommandTransport(nt4ClientService),
    private val tuningProposalInbox: TuningProposalInbox? = null
) {
    private var leaseJob: Job? = null
    private var leaseSequence = 0L

    suspend fun configurePlatform(requiresNetworkArm: Boolean) {
        disarm("Workspace changed", sendStop = true)
        _state.update {
            it.copy(
                requiresNetworkArm = requiresNetworkArm,
                armPhase = if (requiresNetworkArm) CalibrationArmPhase.DISARMED else CalibrationArmPhase.NOT_REQUIRED,
                armStatus = if (requiresNetworkArm) {
                    "Select the FTC tuning OpMode and press Play before arming"
                } else {
                    "FRC authorization is enforced by Test mode and robot hardware health"
                }
            )
        }
    }

    suspend fun arm() {
        val current = _state.value
        if (!current.requiresNetworkArm) return
        if (!current.isRobotConnected || !current.calibrationModeEnabled || current.isRoutineRunning) {
            _state.update { it.copy(errorMessage = "FTC must be connected, in the started tuning OpMode, and stopped before arming") }
            return
        }
        leaseJob?.cancel()
        _state.update {
            it.copy(
                armPhase = CalibrationArmPhase.ARMING,
                robotCalibrationArmed = false,
                armStatus = "Sending STOP and a fresh calibration lease…",
                errorMessage = null
            )
        }
        val token = "ares-${UUID.randomUUID()}"
        val firstSequence = nextLeaseSequence()
        val ready = calibrationTransport.publishString(COMMAND_PUBUID, STOP_COMMAND) &&
            calibrationTransport.publishString(ENABLE_TOKEN_PUBUID, token) &&
            calibrationTransport.publishDouble(ENABLE_LEASE_PUBUID, firstSequence.toDouble())
        if (!ready) {
            connectionLost()
            _state.update { it.copy(errorMessage = "NT4 clock synchronization is not ready; try Arm again") }
            return
        }
        val armedAtNanos = System.nanoTime()
        leaseJob = scope.launch {
            while (isActive) {
                delay(LEASE_RENEWAL_MS)
                if (System.nanoTime() - armedAtNanos > MAX_ARM_SESSION_NANOS) {
                    expireArm()
                    break
                }
                if (!calibrationTransport.publishDouble(ENABLE_LEASE_PUBUID, nextLeaseSequence().toDouble())) {
                    connectionLost()
                    break
                }
            }
        }
    }

    suspend fun disarm(reason: String, sendStop: Boolean = true) {
        leaseJob?.cancel()
        leaseJob = null
        if (sendStop && _state.value.isRobotConnected) {
            calibrationTransport.publishString(COMMAND_PUBUID, STOP_COMMAND)
            calibrationTransport.publishString(ENABLE_TOKEN_PUBUID, "")
        }
        _state.update {
            it.copy(
                armPhase = if (it.requiresNetworkArm) CalibrationArmPhase.DISARMED else CalibrationArmPhase.NOT_REQUIRED,
                robotCalibrationArmed = false,
                isRoutineRunning = false,
                isLoading = false,
                activeCalibration = "NONE",
                armStatus = reason
            )
        }
    }

    fun connectionLost() {
        leaseJob?.cancel()
        leaseJob = null
        _state.update {
            it.copy(
                armPhase = if (it.requiresNetworkArm) CalibrationArmPhase.DISARMED else CalibrationArmPhase.NOT_REQUIRED,
                robotCalibrationArmed = false,
                isRoutineRunning = false,
                isLoading = false,
                activeCalibration = "NONE",
                armStatus = "Disconnected; calibration lease revoked"
            )
        }
    }

    private fun nextLeaseSequence(): Long {
        leaseSequence = if (leaseSequence >= MAX_SAFE_SEQUENCE) 1L else leaseSequence + 1L
        return leaseSequence
    }

    private suspend fun expireArm() {
        leaseJob = null
        if (_state.value.isRobotConnected) {
            calibrationTransport.publishString(COMMAND_PUBUID, STOP_COMMAND)
            calibrationTransport.publishString(ENABLE_TOKEN_PUBUID, "")
        }
        _state.update {
            it.copy(
                armPhase = CalibrationArmPhase.DISARMED,
                robotCalibrationArmed = false,
                isRoutineRunning = false,
                isLoading = false,
                activeCalibration = "NONE",
                armStatus = "Calibration arm timed out"
            )
        }
    }
    suspend fun applyToRobotCode(recommendedExponent: Double, recommendedSlewRate: Double) {
        val slewVal = if (recommendedSlewRate == Double.MAX_VALUE) 999.0 else recommendedSlewRate
        val accepted = tuningProposalInbox?.submit(ExternalTuningProposal(
            source = "Driver analysis",
            summary = "Review driver response recommendations before any live test or profile promotion.",
            values = mapOf(TuningParameterKeys.DRIVER_DEADBAND_EXPONENT to recommendedExponent, TuningParameterKeys.DRIVER_SLEW_RATE_LIMIT to slewVal)
        )) == true
        _state.update { it.copy(exportStatus = if (accepted) "Sent recommendations to the Tuning proposal board." else "Open Tuning before sending recommendations; no robot or source value changed.") }
    }

    suspend fun startRoutine(mechanism: SysIdMechanism, routine: SysIdRoutine) {
        requireMotionAuthorization(mechanism)
        _state.update {
            it.copy(
                liveSamples = emptyList(),
                liveCalibrationData = emptyList(),
                isRoutineRunning = false,
                summary = null,
                isLoading = true,
                errorMessage = null,
            )
        }
        val cmd = "START_${mechanism.name}_${routine.name}"
        try {
            check(calibrationTransport.publishString(COMMAND_PUBUID, cmd)) {
                "NT4 publisher is not ready"
            }
            _state.update { it.copy(isRoutineRunning = true) }
        } catch (error: CancellationException) {
            _state.update { it.copy(isRoutineRunning = false, isLoading = false) }
            throw error
        } catch (error: Exception) {
            _state.update {
                it.copy(
                    isRoutineRunning = false,
                    isLoading = false,
                    errorMessage = "Could not start SysId: ${error.message ?: "robot did not accept the command"}",
                )
            }
        }
    }

    suspend fun stopRoutine() {
        try {
            check(calibrationTransport.publishString(COMMAND_PUBUID, "STOP")) {
                "NT4 publisher is not ready"
            }
            _state.update { it.copy(isRoutineRunning = false, isLoading = false) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.update { it.copy(errorMessage = "Could not stop SysId: ${error.message ?: "robot did not acknowledge stop"}") }
        }
    }

    suspend fun startCalibration(calibrationType: String) {
        requireMotionAuthorization()
        _state.update {
            it.copy(
                liveSamples = emptyList(),
                liveCalibrationData = emptyList(),
                isRoutineRunning = false,
                activeCalibration = calibrationType,
                isLoading = true,
                errorMessage = null,
                recommendedPinpointXOffsetMm = null,
                recommendedPinpointYOffsetMm = null,
                recommendedTrackWidthMeters = null,
                recommendedVisionStdDevsX = null,
                recommendedVisionStdDevsY = null,
                recommendedVisionStdDevsHeading = null,
                recommendedTicksPerMeter = null
            )
        }
        try {
            check(calibrationTransport.publishString(COMMAND_PUBUID, "START_${calibrationType}")) {
                "NT4 publisher is not ready"
            }
            _state.update { it.copy(isRoutineRunning = true) }
        } catch (error: CancellationException) {
            _state.update { it.copy(isRoutineRunning = false, isLoading = false) }
            throw error
        } catch (error: Exception) {
            _state.update {
                it.copy(
                    isRoutineRunning = false,
                    isLoading = false,
                    activeCalibration = "NONE",
                    errorMessage = "Could not start calibration: ${error.message ?: "robot did not accept the command"}",
                )
            }
        }
    }

    suspend fun stopCalibration() {
        try {
            check(calibrationTransport.publishString(COMMAND_PUBUID, "STOP")) {
                "NT4 publisher is not ready"
            }
            _state.update { it.copy(isRoutineRunning = false, activeCalibration = "NONE", isLoading = false) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.update { it.copy(errorMessage = "Could not stop calibration: ${error.message ?: "robot did not acknowledge stop"}") }
        }
    }

    suspend fun applyCalibration(calibrationType: String) {
        val values = when (calibrationType) {
                "PINPOINT_SPIN" -> {
                    val x = _state.value.recommendedPinpointXOffsetMm
                    val y = _state.value.recommendedPinpointYOffsetMm
                    if (x != null && y != null) {
                        mapOf(TuningParameterKeys.PINPOINT_X_OFFSET to x, TuningParameterKeys.PINPOINT_Y_OFFSET to y)
                    } else emptyMap()
                }
                "TRACK_WIDTH_SPIN" -> _state.value.recommendedTrackWidthMeters?.let { mapOf(TuningParameterKeys.DRIVE_TRACK_WIDTH to it) }.orEmpty()
                "VISION_CALIBRATION" -> {
                    val sx = _state.value.recommendedVisionStdDevsX
                    val sy = _state.value.recommendedVisionStdDevsY
                    val sh = _state.value.recommendedVisionStdDevsHeading
                    if (sx != null && sy != null && sh != null) {
                        mapOf(TuningParameterKeys.VISION_STD_DEVS_X to sx, TuningParameterKeys.VISION_STD_DEVS_Y to sy, TuningParameterKeys.VISION_STD_DEVS_HEADING to sh)
                    } else emptyMap()
                }
                "LINEAR_DRIVE" -> _state.value.recommendedTicksPerMeter?.let { mapOf(TuningParameterKeys.FTC_TICKS_PER_METER to it) }.orEmpty()
                else -> emptyMap()
            }
        val accepted = values.isNotEmpty() && tuningProposalInbox?.submit(ExternalTuningProposal(
            source = "Calibration workflow",
            summary = "$calibrationType result. Attach the recorded run and its SHA-256 in Tuning before promotion.",
            values = values
        )) == true
        _state.update { it.copy(exportStatus = if (accepted) "Sent calibration results to the Tuning proposal board." else "No complete calibration proposal was available; no robot or source value changed.") }
    }

    private fun requireMotionAuthorization(mechanism: SysIdMechanism? = null) {
        val current = _state.value
        check(current.capabilitiesKnown) {
            "Connected runtime has not advertised live SysId capabilities"
        }
        if (mechanism != null) {
            check(mechanism in current.supportedMechanisms) {
                "Connected runtime does not support ${mechanism.name.lowercase()} SysId"
            }
        }
        check(current.isRobotConnected && (!current.requiresNetworkArm ||
            (current.armPhase == CalibrationArmPhase.ARMED && current.robotCalibrationArmed))) {
            "Calibration motion requires an acknowledged FTC arm lease"
        }
    }

    private companion object {
        const val COMMAND_PUBUID = 1015
        const val ENABLE_TOKEN_PUBUID = 1016
        const val ENABLE_LEASE_PUBUID = 1017
        const val STOP_COMMAND = "STOP"
        const val LEASE_RENEWAL_MS = 200L
        const val MAX_SAFE_SEQUENCE = 9_007_199_254_740_991L
        const val MAX_ARM_SESSION_NANOS = 60_000_000_000L
    }
}

interface CalibrationCommandTransport {
    suspend fun publishString(pubuid: Int, value: String): Boolean
    suspend fun publishDouble(pubuid: Int, value: Double): Boolean
}

private class Nt4CalibrationCommandTransport(
    private val nt4ClientService: Nt4ClientService
) : CalibrationCommandTransport {
    override suspend fun publishString(pubuid: Int, value: String): Boolean =
        nt4ClientService.publishInputString(pubuid, value)

    override suspend fun publishDouble(pubuid: Int, value: Double): Boolean =
        nt4ClientService.publishInputDouble(pubuid, value)
}
