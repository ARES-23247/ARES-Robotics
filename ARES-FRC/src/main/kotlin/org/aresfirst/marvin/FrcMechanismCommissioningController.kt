package org.aresfirst.marvin

import com.areslib.frc.FrcSwerveRobot
import org.aresfirst.marvin.hardware.FrcFlywheelTuningStatus
import org.aresfirst.marvin.hardware.FrcMechanismConfigurationStatus
import org.aresfirst.marvin.hardware.FrcMechanismHomingStatus
import org.aresfirst.marvin.marvin.ClearMechanismSafetyFault
import org.aresfirst.marvin.marvin.LatchMechanismSafetyFault
import org.aresfirst.marvin.marvin.SetMechanismSafetyInhibit
import org.aresfirst.marvin.marvin.marvin
import edu.wpi.first.wpilibj.DriverStation

/** Owns season mechanism configuration, homing, latching, and Disabled recovery evidence. */
internal class FrcMechanismCommissioningController(
    private val robot: FrcSwerveRobot,
    private val configurationDevices: Array<FrcMechanismConfigurationStatus>,
    private val configurationContractComplete: Boolean,
    private val homingDevices: Array<FrcMechanismHomingStatus>,
    private val flywheelTuningStatus: FrcFlywheelTuningStatus?,
) {
    private var configurationValid = false
    private var homingValid = false
    private var homingComboWasPressed = false

    init {
        refreshConfigurationAndHoming()
    }

    fun requirePeriodicHealth() {
        if (homingValid) {
            for (device in homingDevices) {
                if (!device.homed) {
                    homingValid = false
                    throw IllegalStateException(
                        "Relative mechanism reference was lost; Disabled safe-zero recovery is required"
                    )
                }
            }
        }
        if (flywheelTuningStatus?.lastTuningApplySuccessful == false) {
            throw IllegalStateException("Flywheel live tuning did not reach every motor")
        }
        configurationValid = configurationContractComplete && mechanismsConfigured(*configurationDevices)
        if (!configurationValid) {
            throw IllegalStateException(
                "A mechanism Talon reset or lost its verified configuration; restart and re-home"
            )
        }
    }

    fun applySafetyPolicy(source: String) {
        refreshConfigurationAndHoming()
        val configurationHealthy = configurationHealthy()
        val healthy = mechanismSafetyHealthy(configurationHealthy, homingValid, robot.fatalUpdateFailure)
        if (healthy) {
            // The reducer refuses this temporary clear while a distinct fault remains latched.
            robot.store.dispatch(SetMechanismSafetyInhibit(false))
        } else {
            robot.store.dispatch(
                LatchMechanismSafetyFault(
                    "Mechanism safety validation failed during $source " +
                        "(configured=$configurationHealthy, homed=$homingValid, " +
                        "fatalUpdate=${robot.fatalUpdateFailure != null})"
                )
            )
        }
        val persistentFault = robot.store.state.superstructure.marvin
        robot.telemetry.putBoolean("Safety/MechanismConfigurationValid", configurationHealthy)
        robot.telemetry.putBoolean("Safety/MechanismsHomed", homingValid)
        robot.telemetry.putBoolean("Safety/MechanismFaultLatched", persistentFault.mechanismSafetyFaultLatched)
        robot.telemetry.putString("Safety/MechanismFaultReason", persistentFault.mechanismSafetyFaultReason)
        when {
            !healthy -> {
                DriverStation.reportError(
                    "ARES: mechanism safety validation failed during $source " +
                        "(configured=$configurationHealthy, homed=$homingValid, " +
                        "fatalUpdate=${robot.fatalUpdateFailure != null}); outputs remain inhibited",
                    false,
                )
                robot.safeHardware()
            }
            persistentFault.mechanismSafetyFaultLatched -> {
                DriverStation.reportError(
                    "ARES: mechanism fault remains latched during $source; Disabled dual-operator " +
                        "recovery is required (${persistentFault.mechanismSafetyFaultReason})",
                    false,
                )
                robot.safeHardware()
            }
        }
    }

    fun isHardwarePermitted(): Boolean = mechanismSafetyHealthy(
        configurationHealthy(),
        homingValid,
        robot.fatalUpdateFailure,
    ) && !robot.store.state.superstructure.marvin.mechanismSafetyInhibited &&
        !robot.store.state.superstructure.marvin.mechanismSafetyFaultLatched

    fun handleHomingRequest(
        comboPressed: Boolean,
        isDisabled: Boolean,
        isTestEnabled: Boolean,
    ): Boolean {
        if (comboPressed && !homingComboWasPressed) {
            if (mechanismHomingRequestAllowed(isDisabled, isTestEnabled)) {
                robot.safeHardware()
                var allSucceeded = true
                for (device in homingDevices) {
                    if (!device.homeAtKnownZero()) allSucceeded = false
                }
                homingValid = allSucceeded && mechanismsHomed(*homingDevices)
                configurationValid = configurationContractComplete && mechanismsConfigured(*configurationDevices)
                if (mechanismSafetyHealthy(configurationHealthy(), homingValid, robot.fatalUpdateFailure)) {
                    robot.store.dispatch(ClearMechanismSafetyFault("Dual-operator Disabled safe-zero recovery"))
                }
                applySafetyPolicy("operator-confirmed safe-zero homing")
                if (homingValid) {
                    DriverStation.reportWarning(
                        "ARES: cowl, intake pivot, and climber safe zeros accepted",
                        false,
                    )
                }
            } else {
                DriverStation.reportError("ARES: mechanism recovery rejected outside Disabled", false)
            }
        }
        homingComboWasPressed = comboPressed
        return comboPressed
    }

    fun stopForDisable() {
        runCatching { robot.store.dispatch(SetMechanismSafetyInhibit(true)) }
        robot.safeHardware()
    }

    fun latchFault(reason: String) {
        runCatching { robot.store.dispatch(LatchMechanismSafetyFault(reason)) }
        robot.safeHardware()
    }

    private fun refreshConfigurationAndHoming() {
        configurationValid = configurationContractComplete && mechanismsConfigured(*configurationDevices)
        homingValid = mechanismsHomed(*homingDevices)
    }

    private fun configurationHealthy(): Boolean =
        configurationValid && flywheelTuningStatus?.lastTuningApplySuccessful != false
}
