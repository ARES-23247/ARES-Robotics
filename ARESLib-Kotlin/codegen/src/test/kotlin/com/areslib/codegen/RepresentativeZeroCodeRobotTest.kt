package com.areslib.codegen

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.controls.ControlBindingDocument
import com.areslib.controls.ControlEvent
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControlSourceDocument
import com.areslib.controls.ControlSourceKind
import com.areslib.controls.ControlTargetDocument
import com.areslib.controls.ControlTargetKind
import com.areslib.controls.ControllerAnchorDocument
import com.areslib.controls.ControllerAssignment
import com.areslib.controls.ControllerControlDocument
import com.areslib.controls.ControllerControlTypeDocument
import com.areslib.controls.ControllerInputMappingDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.controls.ControllerProfileDocument
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineStep
import com.areslib.subsystem.InterlockComparison
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemControlStrategy
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemInterlockDocument
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import com.areslib.subsystem.mergeSubsystemCapabilities
import com.areslib.subsystem.subsystemTargetActionKey
import com.areslib.subsystem.subsystemTargetCapabilities
import com.areslib.superstructure.StateTransitionEdge
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.SuperstructureFieldReference
import com.areslib.superstructure.SuperstructureStatePreset
import com.areslib.superstructure.SuperstructureSubsystemTarget
import com.areslib.superstructure.validateSuperstructureProject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Proves the cross-feature contract used by the GUI's representative no-code robot journey. */
class RepresentativeZeroCodeRobotTest {
    @Test
    fun `representative mechanisms compose into FTC and FRC control routine and superstructure runtimes`() {
        listOf(SubsystemPlatform.FTC, SubsystemPlatform.FRC).forEach(::verifyPlatform)
    }

    private fun verifyPlatform(platform: SubsystemPlatform) {
        val elevator = template(SubsystemTemplate.CURRENT_HOMED_MECHANISM, "elevator", "Elevator", platform)
        val flywheel = template(SubsystemTemplate.FLYWHEEL_SHOOTER, "flywheel", "Flywheel", platform)
        val intakeBase = hystereticIntake(platform)
        val servo = template(SubsystemTemplate.POSITIONAL_SERVO, "wrist", "Wrist", platform)
        val elevatorMeasurement = elevator.stateFields.first { it.role == SubsystemFieldRole.MEASUREMENT }
        val intake = intakeBase.copy(
            interlocks = listOf(
                SubsystemInterlockDocument(
                    interlockId = "elevator-clear",
                    targetSubsystemUid = elevator.uid,
                    targetFieldId = elevatorMeasurement.fieldId,
                    comparison = InterlockComparison.GREATER_THAN,
                    thresholdValue = 0.25,
                    forbiddenZoneDescription = "Intake cannot run while the elevator is above its clearance height.",
                ),
            ),
        )
        val subsystems = listOf(elevator, flywheel, intake, servo)
        val baseActions = listOf("machine.ready", "machine.score", "machine.stow", "machine.recover")
            .map { key -> ActionDescriptor(key, key.substringAfter('.').replaceFirstChar(Char::uppercase), "Generated scoring-machine transition.") }
        val catalog = mergeSubsystemCapabilities(
            CapabilityCatalogDocument(projectId = "representative-${platform.name.lowercase()}", actions = baseActions),
            subsystems,
        )
        val superstructure = superstructure(subsystems)
        assertTrue(validateSuperstructureProject(superstructure, subsystems, catalog.actions.map { it.key }.toSet()).isEmpty())

        val routine = RoutineDocument(
            documentId = "score-cycle",
            name = "Score cycle",
            steps = listOf(
                RoutineStep.action("machine.ready"),
                RoutineStep.wait(0.15),
                RoutineStep.action("machine.score"),
                RoutineStep.wait(0.25),
                RoutineStep.action("machine.stow"),
            ),
        )
        val profile = controllerProfile()
        val controls = ControlSchemeDocument(
            documentId = "competition",
            name = "Competition",
            controllers = listOf(ControllerAssignment("operator", "Operator", profile.documentId, 0)),
            bindings = listOf(
                ControlBindingDocument(
                    bindingId = "score-cycle",
                    displayName = "Run score cycle",
                    source = ControlSourceDocument(ControlSourceKind.BUTTON, "operator", listOf("a")),
                    event = ControlEvent.PRESS,
                    target = ControlTargetDocument(ControlTargetKind.ROUTINE, routine.documentId),
                ),
                ControlBindingDocument(
                    bindingId = "home-elevator",
                    displayName = "Home elevator",
                    source = ControlSourceDocument(ControlSourceKind.BUTTON, "operator", listOf("b")),
                    event = ControlEvent.PRESS,
                    target = ControlTargetDocument(
                        ControlTargetKind.ACTION,
                        subsystemTargetActionKey("elevator", "homingRequested"),
                        arguments = mapOf("value" to "true"),
                    ),
                ),
            ),
        )
        val targetPlatform = when (platform) {
            SubsystemPlatform.FTC -> ControllerInputPlatform.FTC
            SubsystemPlatform.FRC -> ControllerInputPlatform.FRC
            SubsystemPlatform.XRP -> ControllerInputPlatform.XRP
        }
        val generated = AresKotlinProjectGenerator.generate(
            KotlinProjectCodegenRequest(
                packageName = "org.example.${platform.name.lowercase()}.generated",
                catalog = catalog,
                routines = listOf(routine),
                controlSchemes = listOf(controls),
                controllerProfiles = listOf(profile),
                targetInputPlatform = targetPlatform,
                subsystemActions = subsystemTargetCapabilities(subsystems),
                subsystemRegistryFqn = "org.example.subsystems.GeneratedSubsystemRegistry",
                generatedActionRegistryBindings = baseActions.associate { it.key to "org.example.superstructure.GeneratedSuperstructureRegistry" },
            ),
        )
        assertTrue(generated.source.contains("score-cycle"))
        assertTrue(generated.source.contains("subsystem.elevator.set.homingRequested"))
        assertTrue(generated.source.contains("GeneratedSubsystemRegistry"))
        assertTrue(generated.source.contains("GeneratedSuperstructureRegistry"))

        val codegenTarget = SubsystemKotlinCodegenTarget(platform, "org.example.subsystems")
        val artifacts = subsystems.flatMap { SubsystemKotlinGenerator.generate(it, codegenTarget) }
        val registry = SubsystemKotlinGenerator.generateRegistry(subsystems, codegenTarget).content
        assertTrue(artifacts.any { it.content.contains("/OutputFaultLatched") })
        assertTrue(artifacts.any { it.content.contains("/ConfigurationHealthy") })
        assertTrue(registry.contains("interlocksPermitIntake"))
        assertTrue(registry.contains("return false"), "Unresolved or unhealthy interlock state must fail closed")
        assertTrue(subsystems.all { document -> artifacts.any { it.relativePath.contains(document.kotlinTypeName) } })
        assertTrue(intake.controlLoops.single().strategy == SubsystemControlStrategy.BANG_BANG)
        assertTrue(intake.controlLoops.single().hysteresis > 0.0)
    }

    private fun template(template: SubsystemTemplate, id: String, type: String, platform: SubsystemPlatform) =
        SubsystemTemplates.create(template, id, type, platform)

    private fun hystereticIntake(platform: SubsystemPlatform): SubsystemDocument {
        val base = template(SubsystemTemplate.INTAKE_CONVEYOR, "intake", "Intake", platform)
        return base.copy(
            stateFields = base.stateFields.map { field ->
                if (field.fieldId == "target") {
                    field.copy(
                        displayName = "Target conveyor speed",
                        unit = "rad/s",
                        minimum = -100.0,
                        maximum = 100.0,
                    )
                } else {
                    field
                }
            },
            controlLoops = base.controlLoops.map { loop ->
                loop.copy(
                    strategy = SubsystemControlStrategy.BANG_BANG,
                    measurementFieldId = "velocity",
                    tolerance = 2.0,
                    hysteresis = 0.5,
                )
            },
        )
    }

    private fun superstructure(subsystems: List<SubsystemDocument>): SuperstructureDocument {
        fun preset(id: String, active: Boolean): SuperstructureStatePreset = SuperstructureStatePreset(
            stateId = id,
            subsystemTargets = subsystems.map { subsystem ->
                val target = subsystem.stateFields.first { it.role == SubsystemFieldRole.TARGET }
                val safeValue = target.defaultNumber ?: target.defaultInt?.toDouble() ?: 0.0
                val activeValue = if (active) target.maximum?.coerceAtMost(1.0) ?: 0.5 else safeValue
                SuperstructureSubsystemTarget(
                    target = SuperstructureFieldReference(subsystem.uid, target.uid),
                    constantDoubleValue = activeValue,
                )
            },
        )
        return SuperstructureDocument(
            superstructureId = "scoring-machine",
            initialStateId = "STOW",
            faultStateId = "FAULT",
            states = listOf(preset("STOW", false), preset("READY", true), preset("SCORE", true), preset("FAULT", false)),
            transitions = listOf(
                StateTransitionEdge("ready", "STOW", "READY", actionKey = "machine.ready"),
                StateTransitionEdge("score", "READY", "SCORE", actionKey = "machine.score"),
                StateTransitionEdge("stow-from-score", "SCORE", "STOW", actionKey = "machine.stow"),
                StateTransitionEdge("stow-from-ready", "READY", "STOW", actionKey = "machine.stow"),
                StateTransitionEdge("recover", "FAULT", "STOW", actionKey = "machine.recover"),
            ),
        )
    }

    private fun controllerProfile(): ControllerProfileDocument {
        fun button(id: String, index: Int) = ControllerControlDocument(
            controlId = id,
            displayName = id.uppercase(),
            type = ControllerControlTypeDocument.BUTTON,
            anchor = ControllerAnchorDocument(0.5, 0.5),
            mappings = listOf(
                ControllerInputMappingDocument(ControllerInputPlatform.FTC, buttonIndex = index),
                ControllerInputMappingDocument(ControllerInputPlatform.FRC, buttonIndex = index),
            ),
        )
        return ControllerProfileDocument(
            documentId = "standard-gamepad",
            displayName = "Standard gamepad",
            controls = listOf(button("a", 0), button("b", 1)),
        )
    }
}
