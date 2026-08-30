package com.ares.analytics.service.hardware

import com.ares.analytics.service.drivebase.DriveHardwareRole
import com.ares.analytics.shared.models.League

/** One hold-to-run motor check exposed by the FTC Driver Station diagnostic OpMode. */
data class FtcMotorDirectionCheck(
    val role: DriveHardwareRole,
    val displayName: String,
    val hardwareMapName: String,
    val gamepadControl: String,
    val configuredDirection: String,
)

/** A deliberately non-executable motion proposal that must be reviewed on the disabled robot. */
data class UnarmedPulseProposal(
    val safeNeutralOutput: Double,
    val maximumDurationMs: Long = 250L,
    val maximumTravelFromNeutralFraction: Double = 0.10,
)

/** Descriptor-derived guidance for commissioning one subsystem device. */
data class SubsystemCommissioningCheck(
    val itemUid: String,
    val subsystemName: String,
    val deviceName: String,
    val hardwareAddress: String,
    val readOnlySignals: List<String>,
    val controlStrategies: List<String>,
    val homingMethod: String?,
    val homingEvidence: List<String>,
    val requiresCalibration: Boolean,
    val requiresCurrentMonitoring: Boolean,
    val pulseProposal: UnarmedPulseProposal?,
    val followerOnly: Boolean,
)

/** Descriptor-derived setup instructions; this model never commands hardware. */
data class HardwareCommissioningPlan(
    val hardwareMapEntries: List<HardwareInventoryItem>,
    val ftcMotorChecks: List<FtcMotorDirectionCheck>,
    val ftcDiagnosticAvailable: Boolean,
    val ftcDiagnosticBlockReason: String? = null,
    val subsystemChecks: List<SubsystemCommissioningCheck> = emptyList(),
) {
    /** Plain-text checklist suitable for a team wiring sheet or Driver Station setup. */
    val clipboardText: String
        get() = buildString {
            appendLine("ARES hardware configuration")
            appendLine("Driver Station > Configure Robot > Hardware")
            hardwareMapEntries.forEach { item ->
                append("- ").append(item.displayName).append(": ")
                append(item.address.ifBlank { "NOT CONFIGURED" })
                append(" (").append(item.role).append(')')
                appendLine()
                item.configurationDetails.forEach { detail ->
                    append("    • ").appendLine(detail)
                }
            }
            if (ftcMotorChecks.isNotEmpty()) {
                appendLine()
                appendLine("ARES Drivetrain Diagnostic (hold to run; release to stop)")
                ftcMotorChecks.forEach { check ->
                    append("- ").append(check.gamepadControl).append(": ")
                    append(check.displayName).append(" [").append(check.hardwareMapName).append("]")
                    append("; configured ").append(check.configuredDirection)
                    appendLine()
                }
            }
            if (subsystemChecks.isNotEmpty()) {
                appendLine()
                appendLine("Subsystem commissioning (simulation/read-only until explicitly authorized)")
                subsystemChecks.forEach { check ->
                    append("- ").append(check.subsystemName).append(" / ").append(check.deviceName)
                    append(": ").appendLine(check.hardwareAddress.ifBlank { "NOT CONFIGURED" })
                    if (check.readOnlySignals.isNotEmpty()) append("    Observe: ").appendLine(check.readOnlySignals.joinToString())
                    check.homingMethod?.let { append("    Homing: ").appendLine(it.lowercase().replace('_', ' ')) }
                    check.pulseProposal?.let { proposal ->
                        append("    UNARMED proposal: no more than ")
                        append(proposal.maximumDurationMs).append(" ms, within ")
                        append((proposal.maximumTravelFromNeutralFraction * 100).toInt()).append("% of neutral ")
                        appendLine(proposal.safeNeutralOutput)
                    }
                }
            }
        }.trimEnd()
}

/**
 * Builds the novice commissioning workflow from the exact reviewed descriptor snapshot.
 *
 * A diagnostic is offered only for an FTC mecanum inventory with one configured motor in each
 * canonical wheel role. Ambiguous or incomplete mappings fail closed instead of guessing.
 */
fun HardwareSetupSnapshot.commissioningPlan(): HardwareCommissioningPlan {
    val hardwareMapEntries = items
        .filter { it.addressKind == HardwareAddressKind.FTC_HARDWARE_MAP }
        .sortedWith(compareBy<HardwareInventoryItem> { it.owner.ordinal }.thenBy { it.displayName.lowercase() })
    val subsystemChecks = items
        .filter { it.owner == HardwareInventoryOwner.SUBSYSTEM }
        .map { item ->
            SubsystemCommissioningCheck(
                itemUid = item.uid,
                subsystemName = item.ownerDisplayName,
                deviceName = item.displayName,
                hardwareAddress = item.addressDescription,
                readOnlySignals = item.measurementFieldIds,
                controlStrategies = item.controlStrategies,
                homingMethod = item.homingMethod,
                homingEvidence = item.homingEvidence,
                requiresCalibration = item.requiresCalibration,
                requiresCurrentMonitoring = item.requiresCurrentMonitoring,
                pulseProposal = item.safeOutput?.takeIf { item.isMotionActuator && !item.isFollower }?.let {
                    UnarmedPulseProposal(safeNeutralOutput = it)
                },
                followerOnly = item.isFollower,
            )
        }

    if (league != League.FTC) {
        return HardwareCommissioningPlan(
            hardwareMapEntries = emptyList(),
            ftcMotorChecks = emptyList(),
            ftcDiagnosticAvailable = false,
            ftcDiagnosticBlockReason = "The ARES Drivetrain Diagnostic is currently available for FTC mecanum projects only.",
            subsystemChecks = subsystemChecks,
        )
    }

    val controls = listOf(
        DriveHardwareRole.FRONT_LEFT_DRIVE to "A / Cross",
        DriveHardwareRole.FRONT_RIGHT_DRIVE to "B / Circle",
        DriveHardwareRole.REAR_LEFT_DRIVE to "X / Square",
        DriveHardwareRole.REAR_RIGHT_DRIVE to "Y / Triangle",
    )
    val driveItemsByRole = items
        .filter { it.owner == HardwareInventoryOwner.DRIVEBASE }
        .groupBy(HardwareInventoryItem::roleKey)
    val checks = controls.mapNotNull { (role, control) ->
        val matches = driveItemsByRole[role.name].orEmpty()
        val item = matches.singleOrNull()?.takeIf {
            it.addressKind == HardwareAddressKind.FTC_HARDWARE_MAP && it.address.isNotBlank()
        } ?: return@mapNotNull null
        FtcMotorDirectionCheck(
            role = role,
            displayName = item.displayName,
            hardwareMapName = item.address,
            gamepadControl = control,
            configuredDirection = if (item.inverted) "REVERSED" else "NORMAL",
        )
    }
    val available = checks.size == controls.size
    return HardwareCommissioningPlan(
        hardwareMapEntries = hardwareMapEntries,
        ftcMotorChecks = checks,
        ftcDiagnosticAvailable = available,
        ftcDiagnosticBlockReason = if (available) null else {
            "Configure exactly one front-left, front-right, rear-left, and rear-right motor before running the diagnostic."
        },
        subsystemChecks = subsystemChecks,
    )
}
