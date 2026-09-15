package org.aresfirst.marvin.robot

import com.areslib.control.assist.FlywheelSysIdAdapter
import com.areslib.control.assist.SysIdManager
import com.areslib.control.assist.SysIdMechanism
import com.areslib.control.assist.SysIdRoutine
import com.areslib.hardware.actuator.FlywheelIO
import org.aresfirst.marvin.hardware.FrcFlywheelTuningStatus
import com.areslib.state.RobotState
import com.areslib.telemetry.ITelemetry

/**
 * FRC test-mode executor for the shared flywheel SysId contract.
 *
 * [update] never infers hardware authorization from tuning mode alone. The season composition root
 * must pass its verified configuration, homing, fatal-loop, and mechanism-latch decision through
 * `hardwareSafetyPermitted`; losing that permission stops a running routine before another voltage
 * is written.
 */
class FrcSysIdController(
    private val telemetry: ITelemetry,
    private val flywheel: FlywheelIO
) {
    private val adapter = FlywheelSysIdAdapter(flywheel)
    private val manager = SysIdManager()
    private val sample = DoubleArray(5)
    private val emptySample = DoubleArray(0)
    private var hasSample = false
    private var sampleTimestampMs = 0L
    private var lastCommand = ""
    private var lastTuning = com.areslib.state.MechanismTuningState()

    fun update(
        timestampMs: Long,
        state: RobotState,
        enabledForTuning: Boolean,
        hardwareSafetyPermitted: Boolean,
        powerScale: Double = 1.0,
        brownedOut: Boolean = false
    ) {
        val powerSafe = !brownedOut && powerScale.isFinite() && powerScale in MINIMUM_SYSID_POWER_SCALE..1.0
        val command = telemetry.getString("SysId/Command", "")
        val newCommand = command != lastCommand
        if (newCommand) lastCommand = command
        if (!hardwareSafetyPermitted) {
            stopRoutine("MECHANISM_HARDWARE_SAFETY_INHIBITED")
            return
        }
        val tuning = state.tuning.subsystem.flywheel
        val tuningStatus = flywheel as? FrcFlywheelTuningStatus
        if (enabledForTuning && (tuning != lastTuning || tuningStatus?.lastTuningApplySuccessful == false)) {
            flywheel.configureVelocityController(tuning.velocityGains, tuning.feedforward)
            if (tuningStatus?.lastTuningApplySuccessful != false) {
                lastTuning = tuning
            } else {
                stopRoutine("FLYWHEEL_TUNING_APPLY_FAILED")
                return
            }
        }

        if (newCommand) {
            hasSample = false
            manager.stop()
            adapter.stop()
            when {
                command == "STOP" || command.isBlank() -> Unit
                !enabledForTuning -> telemetry.putString("SysId/Error", "FRC_SYSID_REQUIRES_TEST_ENABLED")
                !powerSafe -> telemetry.putString("SysId/Error", "SYSID_REQUIRES_FULL_POWER")
                command.startsWith("START_FLYWHEEL_") -> {
                    val routine = runCatching {
                        SysIdRoutine.valueOf(command.removePrefix("START_FLYWHEEL_"))
                    }.getOrDefault(SysIdRoutine.NONE)
                    if (routine != SysIdRoutine.NONE) {
                        val pose = state.drive.poseEstimator
                        manager.start(SysIdMechanism.FLYWHEEL, routine, timestampMs,
                            pose.estimatedPoseX, pose.estimatedPoseY, pose.estimatedPoseHeading)
                    }
                }
                command.startsWith("START_") -> telemetry.putString("SysId/Error", "UNSUPPORTED_FRC_MECHANISM")
            }
        }

        if (!manager.isActive()) {
            hasSample = false
            telemetry.putString("SysId/Status", "NONE")
            telemetry.putDoubleArray("SysId/Data", emptySample)
            return
        }
        val pose = state.drive.poseEstimator
        val currentAmps = flywheel.currentAmps
        val validCurrent = flywheel.isCurrentReadingValid(currentAmps)
        val velocity = adapter.velocity
        val validVelocity = flywheel.velocityValid && velocity.isFinite()
        if (!enabledForTuning || !powerSafe || !validVelocity ||
            !manager.checkSafety(pose.estimatedPoseX, pose.estimatedPoseY, pose.estimatedPoseHeading, timestampMs,
                if (validCurrent) currentAmps else Double.NaN)) {
            stopRoutine(
                when {
                    !enabledForTuning -> "FRC_SYSID_REQUIRES_TEST_ENABLED"
                    !powerSafe -> "SYSID_POWER_DERATING_ABORT"
                    !validVelocity -> "INVALID_FLYWHEEL_MEASUREMENT"
                    !validCurrent -> "INVALID_FLYWHEEL_CURRENT"
                    else -> "SYSID_ABORTED"
                }
            )
            return
        }

        val voltage = manager.update(timestampMs, velocity)
        if (!manager.isActive()) {
            stopRoutine("INVALID_SYSID_SAMPLE")
            return
        }
        adapter.setCharacterizationVoltage(voltage)
        if (!hasSample || timestampMs != sampleTimestampMs) {
            sampleTimestampMs = timestampMs
            hasSample = true
            sample[0] = timestampMs.toDouble()
            sample[1] = voltage
            sample[2] = manager.accumulatedPosition
            sample[3] = velocity
            sample[4] = manager.calculatedAcceleration
        }
        telemetry.putString("SysId/Status", manager.activeRoutine.name)
        telemetry.putDoubleArray("SysId/Data", sample)
    }

    fun stop() = stopRoutine()

    private fun stopRoutine(error: String? = null) {
        hasSample = false
        manager.stop()
        adapter.stop()
        telemetry.putString("SysId/Status", "NONE")
        telemetry.putDoubleArray("SysId/Data", emptySample)
        if (error != null) telemetry.putString("SysId/Error", error)
    }

    private companion object {
        const val MINIMUM_SYSID_POWER_SCALE = 0.999
    }
}
