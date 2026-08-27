package com.ares.analytics.ui.components.subsystems

import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemHomingMethod

/** One deliberate fault a student can inject into the generated safety-contract preview. */
enum class SubsystemFaultScenario(val label: String, val explanation: String) {
    HEALTHY("Healthy signals", "All required cached signals are fresh and valid."),
    STALE_FEEDBACK("Stale feedback", "The last complete input snapshot is older than the declared timeout."),
    INVALID_FEEDBACK("Invalid feedback", "A required sample is non-finite, outside its reviewed range, or unreadable."),
    CONFIGURATION_FAILURE("Configuration failure", "At least one required device did not initialize successfully."),
    INVALID_CURRENT("Invalid current reading", "A required current sample is missing, negative, stale, or non-finite."),
    NOT_HOMED("Not homed", "The mechanism has not established its configured reference."),
    NOT_CALIBRATED("Not calibrated", "The required calibration confirmation has not completed."),
    FAILED_OUTPUT_WRITE("Failed output write", "The adapter could not apply an actuator command and latched an output fault."),
}

/** Explainable result used by the interactive builder lab; it never commands a robot. */
data class SubsystemFaultLabResult(
    val outputPermitted: Boolean,
    val status: String,
    val recovery: String,
)

fun evaluateSubsystemFaultScenario(
    document: SubsystemDocument,
    scenario: SubsystemFaultScenario,
): SubsystemFaultLabResult {
    if (scenario == SubsystemFaultScenario.HEALTHY) {
        return SubsystemFaultLabResult(
            outputPermitted = true,
            status = "Motion is permitted within the controller's output bounds and interlocks.",
            recovery = "No recovery is needed. The IO adapter still rejects non-finite commands and failed writes.",
        )
    }
    val applicable = when (scenario) {
        SubsystemFaultScenario.INVALID_CURRENT -> document.safety.requiresCurrentMonitoring
        SubsystemFaultScenario.NOT_HOMED -> document.safety.homing.method != SubsystemHomingMethod.NONE
        SubsystemFaultScenario.NOT_CALIBRATED -> document.safety.requiresCalibration
        else -> true
    }
    if (!applicable) {
        return SubsystemFaultLabResult(
            outputPermitted = true,
            status = "This safety requirement is not enabled for the current descriptor.",
            recovery = "Enable the matching safety requirement if this mechanism depends on that evidence.",
        )
    }
    return when (scenario) {
        SubsystemFaultScenario.FAILED_OUTPUT_WRITE -> SubsystemFaultLabResult(
            outputPermitted = false,
            status = "Neutral is commanded and the output fault remains latched.",
            recovery = if (document.safety.requiresExplicitNeutralRecovery) {
                "After repairing the cause, request explicit neutral recovery. Motion remains held until a later new target command."
            } else {
                "Repair the cause and verify a successful neutral write before issuing another target."
            },
        )
        SubsystemFaultScenario.NOT_HOMED -> SubsystemFaultLabResult(
            outputPermitted = false,
            status = "Normal motion is blocked. Only the bounded homing state machine may command its reviewed homing output.",
            recovery = "Request homing, maintain valid evidence for the dwell time, and establish zero before normal motion.",
        )
        SubsystemFaultScenario.NOT_CALIBRATED -> SubsystemFaultLabResult(
            outputPermitted = false,
            status = "Motion is held neutral because calibration evidence is absent.",
            recovery = "Verify the mechanism at neutral, then use the explicit calibration confirmation action.",
        )
        SubsystemFaultScenario.STALE_FEEDBACK,
        SubsystemFaultScenario.INVALID_FEEDBACK,
        SubsystemFaultScenario.CONFIGURATION_FAILURE,
        SubsystemFaultScenario.INVALID_CURRENT -> SubsystemFaultLabResult(
            outputPermitted = false,
            status = "The controller and IO boundary hold every declared actuator at its safe neutral.",
            recovery = "Restore healthy evidence. Any latched write fault still requires its separate neutral recovery workflow.",
        )
        SubsystemFaultScenario.HEALTHY -> error("Handled above")
    }
}
