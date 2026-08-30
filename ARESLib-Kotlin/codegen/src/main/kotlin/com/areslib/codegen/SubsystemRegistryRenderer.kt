package com.areslib.codegen

import com.areslib.subsystem.SubsystemCapabilityOperation
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemImplementationKind
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.isAresGenerated
import com.areslib.subsystem.subsystemTargetCapabilities
import com.areslib.subsystem.SubsystemSchema

/** Renders only the generated subsystem composition/action/interlock registry. */
internal object SubsystemRegistryRenderer {
    fun render(documents: List<SubsystemDocument>, target: SubsystemKotlinCodegenTarget): String {
        val projectIssues = SubsystemSchema.validateAll(documents)
        require(projectIssues.isEmpty()) {
            projectIssues.joinToString("; ") { "${it.path}: ${it.message}" }
        }
        documents.forEach { document ->
            require(document.platform == target.platform) {
                "Subsystem '${document.documentId}' targets ${document.platform}, not ${target.platform}"
            }
            require(SubsystemSchema.validate(document).isEmpty()) { "Subsystem '${document.documentId}' is invalid" }
        }
        val generatedDocuments = documents.filter { it.implementation.kind.isAresGenerated() }
        val handAuthoredDocuments = documents.filter {
            it.implementation.kind == SubsystemImplementationKind.HAND_AUTHORED
        }
        val imports = generatedDocuments.sortedBy { it.documentId }.flatMap { document ->
            val segment = document.documentId.replace('-', '_')
            val pkg = "${target.basePackage}.$segment"
            buildList {
                add("$pkg.${document.kotlinTypeName}Subsystem")
                add("$pkg.${document.kotlinTypeName}State")
                add("$pkg.${platformPrefix(document.platform)}${document.kotlinTypeName}IO")
                if (document.generateMockIo) add("$pkg.Mock${document.kotlinTypeName}IO")
            }
        }.distinct().sorted()
        val factories = generatedDocuments.sortedBy { it.documentId }.joinToString("\n") { document ->
            val ioFactory = when (target.platform) {
                SubsystemPlatform.FTC ->
                    "Ftc${document.kotlinTypeName}IO(hardwareMap)"
                SubsystemPlatform.FRC -> if (document.generateMockIo) {
                    "if (isReal) Frc${document.kotlinTypeName}IO() else Mock${document.kotlinTypeName}IO()"
                } else {
                    "if (isReal) Frc${document.kotlinTypeName}IO() else null"
                }
            }
            val prefix = ("Subsystems/${document.documentId}").quoted()
            val subsystemFactory = if (target.platform == SubsystemPlatform.FRC && !document.generateMockIo) {
                "run { val io = $ioFactory; if (io == null) null else { hardwareRegistry.registerTelemetryDevice($prefix, io); ${document.kotlinTypeName}Subsystem(io) } }"
            } else {
                "run { val io = $ioFactory; hardwareRegistry.registerTelemetryDevice($prefix, io); ${document.kotlinTypeName}Subsystem(io) }"
            }
            "    GeneratedSubsystemRegistrySupport.install(this, ${document.documentId.quoted()}, ${document.requiredAtStartup}) { $subsystemFactory }"
        }
        val actionCases = generatedDocuments.sortedBy { it.documentId }.flatMapIndexed { resourceIndex, document ->
            val resourceExpression = "TaskResources.generatedSubsystem($resourceIndex)"
            subsystemTargetCapabilities(listOf(document)).map { capability ->
                when (capability.operation) {
                    SubsystemCapabilityOperation.SET_FIELD ->
                        registryActionCase(document, requireNotNull(document.field(capability.fieldId)), resourceExpression)
                    SubsystemCapabilityOperation.CYCLE_INDICATOR_COLOR_FORWARD ->
                        registryIndicatorCycleActionCase(
                            document,
                            requireNotNull(document.field(capability.fieldId)),
                            resourceExpression,
                            forward = true,
                        )
                    SubsystemCapabilityOperation.CYCLE_INDICATOR_COLOR_BACKWARD ->
                        registryIndicatorCycleActionCase(
                            document,
                            requireNotNull(document.field(capability.fieldId)),
                            resourceExpression,
                            forward = false,
                        )
                    SubsystemCapabilityOperation.SET_HOMING_REQUEST -> registryHomingActionCase(document, resourceExpression)
                    SubsystemCapabilityOperation.REQUEST_NEUTRAL_RECOVERY ->
                        registryNeutralRecoveryActionCase(document, resourceExpression)
                    SubsystemCapabilityOperation.CONFIRM_CALIBRATION ->
                        registryCalibrationConfirmationActionCase(document, resourceExpression)
                }
            }
        }.joinToString("\n")
        val actionFactory = if (actionCases.isBlank()) {
            """@Suppress("UNUSED_PARAMETER")
fun createActionTask(actionKey: String, value: Any?): Task? = null"""
        } else {
            """fun createActionTask(actionKey: String, value: Any?): Task? = when (actionKey) {
$actionCases
    else -> null
}"""
        }
        val interlockFunctions = generatedDocuments.filter { it.interlocks.isNotEmpty() }
            .sortedBy { it.documentId }
            .joinToString("\n\n") { document ->
                SubsystemKotlinGenerator.registryInterlockFunction(document, generatedDocuments)
            }
        val body = if (generatedDocuments.isEmpty()) {
            val parameter = if (target.platform == SubsystemPlatform.FTC) {
                "hardwareMap: HardwareMap, hardwareRegistry: HardwareRegistry"
            } else {
                "isReal: Boolean, hardwareRegistry: HardwareRegistry"
            }
            """@Suppress("UNUSED_PARAMETER")
fun createAll($parameter): List<Subsystem> = emptyList()"""
        } else when (target.platform) {
            SubsystemPlatform.FTC -> """fun createAll(hardwareMap: HardwareMap, hardwareRegistry: HardwareRegistry): List<Subsystem> = buildList {
$factories
}"""
            SubsystemPlatform.FRC -> """fun createAll(isReal: Boolean, hardwareRegistry: HardwareRegistry): List<Subsystem> = buildList {
$factories
}"""
        }
        return buildString {
            append("package ${target.basePackage}\n\n")
            if (actionCases.isNotBlank()) {
                append("import com.areslib.action.RobotAction\n")
                append("import com.areslib.sequencer.StateActionTask\n")
                append("import com.areslib.sequencer.TaskResources\n")
            }
            if (generatedDocuments.any { it.interlocks.isNotEmpty() }) {
                append("import com.areslib.state.RobotState\n")
            }
            append("import com.areslib.sequencer.Task\n")
            append("import com.areslib.subsystem.GeneratedSubsystemRegistrySupport\n")
            append("import com.areslib.subsystem.Subsystem\n")
            append("import com.areslib.hardware.HardwareRegistry\n")
            if (target.platform == SubsystemPlatform.FTC) {
                append("import com.qualcomm.robotcore.hardware.HardwareMap\n")
            }
            imports.forEach { append("import $it\n") }
            append("\n/** Generated composition root. The season shell registers every returned subsystem. */\n")
            append("object GeneratedSubsystemRegistry {\n")
            if (handAuthoredDocuments.isNotEmpty()) {
                append("    // USER-OWNED hand-authored subsystems are registered by the season composition root:\n")
                handAuthoredDocuments.sortedBy { it.documentId }.forEach { document ->
                    append("    // - ${document.documentId}: ${document.implementation.subsystemClassName}\n")
                }
            }
            append(body.prependIndent("    "))
            append("\n\n")
            append(actionFactory.prependIndent("    "))
            if (interlockFunctions.isNotBlank()) {
                append("\n\n")
                append(interlockFunctions.prependIndent("    "))
            }
            append("\n}\n")
        }
    }
}
