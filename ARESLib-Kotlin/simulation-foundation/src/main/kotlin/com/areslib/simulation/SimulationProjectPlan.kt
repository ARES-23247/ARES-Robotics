package com.areslib.simulation

import com.areslib.drivetrain.DrivetrainDocument
import com.areslib.drivetrain.DrivetrainKind
import com.areslib.project.schema.AresControllerTarget
import com.areslib.project.schema.AresProjectTarget
import com.areslib.project.schema.AresSimulatorTarget
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemSimulationSupport

/**
 * A concrete league simulator product. These are intentionally not one universal physics engine:
 * FTC owns its OpMode/Driver Station model while FRC owns WPILib/HAL and TimedRobot behavior.
 */
public enum class SimulationProductId(public val stableId: String, public val displayName: String) {
    FTC_DESKTOP_OPMODE("ftc.desktop-opmode", "FTC desktop OpMode simulator"),
    FRC_WPILIB_DESKTOP("frc.wpilib-desktop", "FRC WPILib desktop simulator"),
    XRP_DESKTOP("xrp.desktop", "XRP desktop simulator"),
}

/** Proven behavior a simulator product can supply without substituting a generic device model. */
public enum class SimulationCapability {
    FTC_OPMODE_LIFECYCLE,
    FRC_TIMED_ROBOT_LIFECYCLE,
    MECANUM_DRIVETRAIN_PHYSICS,
    CTRE_SWERVE_DRIVETRAIN_PHYSICS,
    DIFFERENTIAL_DRIVETRAIN_PHYSICS,
    GENERATED_SUBSYSTEM_ADAPTER,
    HAND_AUTHORED_SUBSYSTEM_ADAPTER,
    FIELD_CONFIGURATION,
    NT4_TELEMETRY,
}

public data class SimulationProductContract(
    val id: SimulationProductId,
    val controller: AresControllerTarget,
    val simulator: AresSimulatorTarget,
    val capabilities: Set<SimulationCapability>,
)

public data class SimulationCompatibilityIssue(
    val code: String,
    val documentId: String?,
    val message: String,
)

/** Deterministic simulator selection derived from canonical project documents. */
public data class SimulationProjectPlan(
    val target: AresProjectTarget,
    val product: SimulationProductContract,
    val requiredCapabilities: Set<SimulationCapability>,
    val issues: List<SimulationCompatibilityIssue>,
) {
    public val isSupported: Boolean get() = issues.isEmpty()
}

public object SimulationProducts {
    public val FTC: SimulationProductContract = SimulationProductContract(
        id = SimulationProductId.FTC_DESKTOP_OPMODE,
        controller = AresControllerTarget.FTC_CONTROL_HUB,
        simulator = AresSimulatorTarget.FTC,
        capabilities = setOf(
            SimulationCapability.FTC_OPMODE_LIFECYCLE,
            SimulationCapability.MECANUM_DRIVETRAIN_PHYSICS,
            SimulationCapability.GENERATED_SUBSYSTEM_ADAPTER,
            SimulationCapability.HAND_AUTHORED_SUBSYSTEM_ADAPTER,
            SimulationCapability.FIELD_CONFIGURATION,
            SimulationCapability.NT4_TELEMETRY,
        ),
    )

    public val FRC: SimulationProductContract = SimulationProductContract(
        id = SimulationProductId.FRC_WPILIB_DESKTOP,
        controller = AresControllerTarget.FRC_ROBORIO,
        simulator = AresSimulatorTarget.FRC,
        capabilities = setOf(
            SimulationCapability.FRC_TIMED_ROBOT_LIFECYCLE,
            SimulationCapability.CTRE_SWERVE_DRIVETRAIN_PHYSICS,
            SimulationCapability.GENERATED_SUBSYSTEM_ADAPTER,
            SimulationCapability.HAND_AUTHORED_SUBSYSTEM_ADAPTER,
            SimulationCapability.FIELD_CONFIGURATION,
            SimulationCapability.NT4_TELEMETRY,
        ),
    )

    public val XRP: SimulationProductContract = SimulationProductContract(
        id = SimulationProductId.XRP_DESKTOP,
        controller = AresControllerTarget.XRP_PICO,
        simulator = AresSimulatorTarget.XRP,
        capabilities = setOf(
            SimulationCapability.DIFFERENTIAL_DRIVETRAIN_PHYSICS,
            SimulationCapability.MECANUM_DRIVETRAIN_PHYSICS,
            SimulationCapability.GENERATED_SUBSYSTEM_ADAPTER,
            SimulationCapability.HAND_AUTHORED_SUBSYSTEM_ADAPTER,
            SimulationCapability.FIELD_CONFIGURATION,
            SimulationCapability.NT4_TELEMETRY,
        ),
    )

    public fun forTarget(target: AresProjectTarget): SimulationProductContract = when (target.simulator) {
        AresSimulatorTarget.FTC -> FTC
        AresSimulatorTarget.FRC -> FRC
        AresSimulatorTarget.XRP -> XRP
    }
}

/**
 * Pure compatibility planner shared by code generation, Studio, and tests. It never launches a
 * process and never infers a generic substitute for an unsupported drivetrain or mechanism.
 */
public object SimulationProjectPlanner {
    @JvmStatic
    public fun plan(
        target: AresProjectTarget,
        drivetrain: DrivetrainDocument?,
        subsystems: Collection<SubsystemDocument>,
    ): SimulationProjectPlan {
        val product = SimulationProducts.forTarget(target)
        val required = linkedSetOf(
            SimulationCapability.FIELD_CONFIGURATION,
            SimulationCapability.NT4_TELEMETRY,
            when (target.simulator) {
                AresSimulatorTarget.FTC -> SimulationCapability.FTC_OPMODE_LIFECYCLE
                AresSimulatorTarget.FRC -> SimulationCapability.FRC_TIMED_ROBOT_LIFECYCLE
                AresSimulatorTarget.XRP -> null
            },
        ).filterNotNullTo(linkedSetOf())
        val issues = mutableListOf<SimulationCompatibilityIssue>()

        if (product.controller != target.controller) {
            issues += SimulationCompatibilityIssue(
                code = "controller_simulator_mismatch",
                documentId = null,
                message = "${target.controller} cannot run in ${product.id.displayName}; choose the matching league simulator.",
            )
        }

        drivetrain?.let { document ->
            val capability = when (document.kind) {
                DrivetrainKind.FTC_MECANUM -> SimulationCapability.MECANUM_DRIVETRAIN_PHYSICS
                DrivetrainKind.FRC_CTRE_SWERVE -> SimulationCapability.CTRE_SWERVE_DRIVETRAIN_PHYSICS
                DrivetrainKind.DIFFERENTIAL -> SimulationCapability.DIFFERENTIAL_DRIVETRAIN_PHYSICS
                DrivetrainKind.ADVANCED_CUSTOM -> null
            }
            if (capability == null) {
                issues += SimulationCompatibilityIssue(
                    code = "unsupported_drivetrain_simulation",
                    documentId = document.uid,
                    message = "${document.displayName} has no implemented desktop physics adapter. ARES will not substitute a generic drivetrain.",
                )
            } else {
                required += capability
                if (capability !in product.capabilities) {
                    issues += SimulationCompatibilityIssue(
                        code = "drivetrain_simulator_mismatch",
                        documentId = document.uid,
                        message = "${document.displayName} requires $capability, which ${product.id.displayName} does not provide.",
                    )
                }
            }
        }

        val expectedSubsystemPlatform = when (target.controller) {
            AresControllerTarget.FTC_CONTROL_HUB -> SubsystemPlatform.FTC
            AresControllerTarget.FRC_ROBORIO -> SubsystemPlatform.FRC
            AresControllerTarget.XRP_PICO -> SubsystemPlatform.XRP
        }
        subsystems.sortedBy(SubsystemDocument::documentId).forEach { subsystem ->
            if (subsystem.platform != expectedSubsystemPlatform) {
                issues += SimulationCompatibilityIssue(
                    code = "subsystem_simulator_mismatch",
                    documentId = subsystem.documentId,
                    message = "${subsystem.displayName} targets ${subsystem.platform}, not ${target.controller}.",
                )
                return@forEach
            }
            val simulation = subsystem.implementation.simulation
            val capability = when (simulation.support) {
                SubsystemSimulationSupport.GENERATED_MOCK -> SimulationCapability.GENERATED_SUBSYSTEM_ADAPTER
                SubsystemSimulationSupport.HAND_AUTHORED_MOCK,
                SubsystemSimulationSupport.HAND_AUTHORED_SIMULATOR -> SimulationCapability.HAND_AUTHORED_SUBSYSTEM_ADAPTER
                SubsystemSimulationSupport.UNAVAILABLE -> null
            }
            if (capability == null) {
                issues += SimulationCompatibilityIssue(
                    code = "subsystem_simulation_unavailable",
                    documentId = subsystem.documentId,
                    message = "${subsystem.displayName} declares no simulator adapter. Add one or exclude the mechanism before launching simulation.",
                )
            } else {
                required += capability
                if (capability !in product.capabilities) {
                    issues += SimulationCompatibilityIssue(
                        code = "subsystem_capability_unavailable",
                        documentId = subsystem.documentId,
                        message = "${subsystem.displayName} requires $capability, which ${product.id.displayName} does not provide.",
                    )
                }
            }
        }

        return SimulationProjectPlan(
            target = target,
            product = product,
            requiredCapabilities = required,
            issues = issues.distinctBy { Triple(it.code, it.documentId, it.message) },
        )
    }
}
