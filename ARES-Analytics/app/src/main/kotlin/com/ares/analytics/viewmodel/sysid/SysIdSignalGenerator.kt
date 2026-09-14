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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import com.ares.analytics.viewmodel.CalibrationArmPhase
import com.ares.analytics.shared.models.TelemetryFrame

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
    private var motionGeneration = 0L
    private data class MotionAttempt(val generation: Long, val epoch: Long, val connection: Long, val mechanism: SysIdMechanism)

    private fun beginMotion() = MotionAttempt(++motionGeneration, nt4ClientService.telemetryStore.currentTargetEpoch(),
        nt4ClientService.controlConnectionEpoch, _state.value.selectedMechanism)

    private fun finishMotion(attempt: MotionAttempt) {
        if (attempt.generation != motionGeneration) return
        if (attempt.epoch != nt4ClientService.telemetryStore.currentTargetEpoch() ||
            attempt.connection != nt4ClientService.controlConnectionEpoch ||
            attempt.mechanism != _state.value.selectedMechanism) {
            connectionLost()
            return
        }
        try { requireMotionAuthorization() } catch (_: IllegalStateException) { connectionLost(); return }
        _state.update { it.copy(isRoutineRunning = true) }
    }
    private class ArmAttempt(val epoch: Long, val connection: Long?, val previousAcknowledgement: TelemetryFrame?)
    private var armAttempt: ArmAttempt? = null

    private fun currentAttempt(attempt: ArmAttempt): Boolean = armAttempt === attempt &&
        scope.isActive && nt4ClientService.isConnected.value && !nt4ClientService.isReplayActive.value &&
        _state.value.isRobotConnected && _state.value.calibrationModeEnabled &&
        nt4ClientService.telemetryStore.currentTargetEpoch() == attempt.epoch &&
        nt4ClientService.tuningConnectionId == attempt.connection

    internal fun hasActiveArmLease(): Boolean = armAttempt?.let { currentAttempt(it) && leaseJob?.isActive == true } == true

    /** A boolean observation cannot create a lease or identify the token it acknowledges. */
    internal suspend fun observeRobotArmed(frame: TelemetryFrame) {
        if (!nt4ClientService.telemetryStore.isCurrentNotifiedFrame(frame)) return
        val attempt = armAttempt
        val armed = frame.stringValue == null && frame.value == 1.0
        if (!armed) {
            if (_state.value.armPhase == CalibrationArmPhase.ARMED) disarm("Robot disarmed calibration")
            return
        }
        if (attempt == null || frame === attempt.previousAcknowledgement || !hasActiveArmLease()) return
        _state.update {
            if (it.armPhase != CalibrationArmPhase.ARMING && it.armPhase != CalibrationArmPhase.ARMED) it
            else it.copy(robotCalibrationArmed = true, armPhase = CalibrationArmPhase.ARMED,
                armStatus = "Robot reports calibration armed while the local lease is active")
        }
    }

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
        if (!current.isRobotConnected || !nt4ClientService.isConnected.value || nt4ClientService.isReplayActive.value ||
            !current.calibrationModeEnabled || current.isRoutineRunning || !scope.isActive) {
            _state.update { it.copy(errorMessage = "FTC must be connected, in the started tuning OpMode, and stopped before arming") }
            return
        }
        armAttempt = null
        motionGeneration++
        leaseJob?.cancel()
        leaseJob = null
        val attempt = ArmAttempt(nt4ClientService.telemetryStore.currentTargetEpoch(), nt4ClientService.tuningConnectionId,
            nt4ClientService.telemetryStore.latest("SysId/Armed"))
        armAttempt = attempt
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
        val ready = try {
            calibrationTransport.publishString(COMMAND_PUBUID, STOP_COMMAND) && currentAttempt(attempt) &&
                calibrationTransport.publishString(ENABLE_TOKEN_PUBUID, token) && currentAttempt(attempt) &&
                calibrationTransport.publishDouble(ENABLE_LEASE_PUBUID, firstSequence.toDouble()) && currentAttempt(attempt)
        } catch (cancelled: CancellationException) {
            if (armAttempt === attempt) connectionLost()
            throw cancelled
        } catch (failure: Exception) {
            if (armAttempt === attempt) {
                connectionLost()
                _state.update { it.copy(errorMessage = "Could not publish calibration lease: ${failure.message}") }
            }
            return
        }
        if (!ready) {
            if (armAttempt === attempt) {
                connectionLost()
                _state.update { it.copy(errorMessage = "NT4 clock synchronization or control context changed; try Arm again") }
            }
            return
        }
        val armedAtNanos = System.nanoTime()
        val renewal = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (isActive && armAttempt === attempt) {
                    delay(LEASE_RENEWAL_MS)
                    if (!currentAttempt(attempt)) {
                        if (armAttempt === attempt) connectionLost()
                        break
                    }
                    if (System.nanoTime() - armedAtNanos > MAX_ARM_SESSION_NANOS) {
                        expireArm()
                        break
                    }
                    if (!calibrationTransport.publishDouble(ENABLE_LEASE_PUBUID, nextLeaseSequence().toDouble())) {
                        if (armAttempt === attempt) connectionLost()
                        break
                    }
                }
            } catch (cancelled: CancellationException) {
                if (armAttempt === attempt) connectionLost()
                throw cancelled
            } catch (failure: Exception) {
                if (armAttempt === attempt) {
                    connectionLost()
                    _state.update { it.copy(errorMessage = "Calibration lease renewal failed: ${failure.message}") }
                }
            }
        }
        leaseJob = renewal
        renewal.start()
        // A valid response may have arrived while the final publish was suspended. Reconcile
        // the current notified frame now that the local lease has been successfully established.
        nt4ClientService.telemetryStore.latest("SysId/Armed")?.let { observeRobotArmed(it) }
    }

    suspend fun disarm(reason: String, sendStop: Boolean = true) {
        motionGeneration++
        armAttempt = null
        leaseJob?.cancel()
        leaseJob = null
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
        if (sendStop && _state.value.isRobotConnected) {
            val stopped = calibrationTransport.publishString(COMMAND_PUBUID, STOP_COMMAND)
            val revoked = calibrationTransport.publishString(ENABLE_TOKEN_PUBUID, "")
            check(stopped && revoked) { "Calibration STOP or token revocation publisher is not ready" }
        }
    }

    fun connectionLost() {
        motionGeneration++
        armAttempt = null
        leaseJob?.cancel()
        leaseJob = null
        _state.update {
            it.copy(
                armPhase = if (it.requiresNetworkArm) CalibrationArmPhase.DISARMED else CalibrationArmPhase.NOT_REQUIRED,
                robotCalibrationArmed = false,
                isRoutineRunning = false,
                isLoading = false,
                activeCalibration = "NONE",
                calibrationModeEnabled = false,
                capabilitiesKnown = false,
                supportedMechanisms = emptySet(),
                armStatus = "Disconnected; calibration lease revoked"
            )
        }
    }

    private fun nextLeaseSequence(): Long {
        leaseSequence = if (leaseSequence >= MAX_SAFE_SEQUENCE) 1L else leaseSequence + 1L
        return leaseSequence
    }

    private suspend fun expireArm() {
        motionGeneration++
        armAttempt = null
        leaseJob = null
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
        if (_state.value.isRobotConnected) {
            calibrationTransport.publishString(COMMAND_PUBUID, STOP_COMMAND)
            calibrationTransport.publishString(ENABLE_TOKEN_PUBUID, "")
        }
    }
    suspend fun startRoutine(mechanism: SysIdMechanism, routine: SysIdRoutine) {
        requireMotionAuthorization(mechanism)
        val attempt = beginMotion()
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
            finishMotion(attempt)
        } catch (error: CancellationException) {
            if (attempt.generation == motionGeneration) _state.update { it.copy(isRoutineRunning = false, isLoading = false) }
            throw error
        } catch (error: Exception) {
            if (attempt.generation == motionGeneration) _state.update {
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
            disarm("Operator stopped SysId")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.update { it.copy(errorMessage = "Could not stop SysId: ${error.message ?: "robot did not acknowledge stop"}") }
        }
    }

    suspend fun startCalibration(calibrationType: String) {
        requireMotionAuthorization()
        val attempt = beginMotion()
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
            finishMotion(attempt)
        } catch (error: CancellationException) {
            if (attempt.generation == motionGeneration) _state.update { it.copy(isRoutineRunning = false, isLoading = false) }
            throw error
        } catch (error: Exception) {
            if (attempt.generation == motionGeneration) _state.update {
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
            disarm("Operator stopped calibration")
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
        _state.update { it.copy(exportStatus = if (accepted) "Queued calibration results for the Tuning proposal board." else "No complete proposal could be queued. Review pending proposals and calibration results, then retry; no robot or source value changed.") }
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
        check(current.isRobotConnected && nt4ClientService.isConnected.value && !nt4ClientService.isReplayActive.value &&
            (!current.requiresNetworkArm || (current.calibrationModeEnabled && hasActiveArmLease() &&
                current.armPhase == CalibrationArmPhase.ARMED && current.robotCalibrationArmed))) {
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

internal class Nt4CalibrationCommandTransport(
    private val nt4ClientService: Nt4ClientService
) : CalibrationCommandTransport {
    override suspend fun publishString(pubuid: Int, value: String): Boolean =
        nt4ClientService.publishInputString(pubuid, value)

    override suspend fun publishDouble(pubuid: Int, value: Double): Boolean =
        nt4ClientService.publishInputDouble(pubuid, value)
}
