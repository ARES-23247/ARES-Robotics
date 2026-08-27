package com.ares.analytics.service.commissioning

import com.areslib.subsystem.SubsystemDocument

enum class CommissioningSimulationStatus {
    NOT_AVAILABLE,
    VERIFIED,
    NEEDS_REVIEW,
}

data class CommissioningSimulationSummary(
    val status: CommissioningSimulationStatus,
    val controllerCount: Int,
    val scenarioCount: Int,
    val failures: List<String>,
    val performanceWarnings: List<String>,
) {
    val verified: Boolean get() = status == CommissioningSimulationStatus.VERIFIED
}

/**
 * Runs every descriptor-owned controller through deterministic nominal and fail-closed scenarios.
 * This proves only generated-model behavior; it never reads or commands hardware.
 */
class CommissioningVerificationService {
    fun verify(subsystems: List<SubsystemDocument>): CommissioningSimulationSummary {
        var controllerCount = 0
        var scenarioCount = 0
        val failures = ArrayList<String>()
        val warnings = ArrayList<String>()

        subsystems.sortedBy(SubsystemDocument::documentId).forEach { subsystem ->
            subsystem.controlLoops.sortedBy { it.uid }.forEach { loop ->
                controllerCount += 1
                val targetUnit = subsystem.stateFields.firstOrNull { it.fieldId == loop.targetFieldId }?.unit
                val plant = defaultCommissioningPlant(loop, targetUnit)
                commissioningScenariosFor(loop).forEach { scenario ->
                    scenarioCount += 1
                    val result = runCatching { simulateSubsystemCommissioning(loop, plant, scenario) }
                        .getOrElse { error ->
                            failures += "${subsystem.displayName} / ${loop.uid} / ${scenario.displayName}: ${error.message ?: "simulation failed"}"
                            return@forEach
                        }
                    if (!result.metrics.bounded || result.metrics.neutralizedOnFault == false ||
                        result.metrics.neutralRecoverySucceeded == false
                    ) {
                        failures += "${subsystem.displayName} / ${loop.uid} / ${scenario.displayName}: ${result.metrics.statusMessage}"
                    } else if (scenario == SubsystemCommissioningScenario.NOMINAL && !result.metrics.enteredTolerance &&
                        result.metrics.finalError != null
                    ) {
                        warnings += "${subsystem.displayName} / ${loop.uid}: bounded but did not enter tolerance in the teaching model"
                    }
                }
            }
        }

        val status = when {
            controllerCount == 0 -> CommissioningSimulationStatus.NOT_AVAILABLE
            failures.isEmpty() -> CommissioningSimulationStatus.VERIFIED
            else -> CommissioningSimulationStatus.NEEDS_REVIEW
        }
        return CommissioningSimulationSummary(status, controllerCount, scenarioCount, failures, warnings)
    }
}
