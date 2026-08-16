package com.areslib.codegen

import com.areslib.subsystem.SubsystemControlLoopDocument
import com.areslib.subsystem.SubsystemControlStrategy
import com.areslib.subsystem.SubsystemCapabilityOperation
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemFeedforwardKind
import com.areslib.subsystem.SubsystemFollowerDocument
import com.areslib.subsystem.SubsystemFollowerTransform
import com.areslib.subsystem.FaultRecoveryActionKind
import com.areslib.subsystem.SubsystemHardwareDocument
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemHomingComparison
import com.areslib.subsystem.SubsystemHomingMethod
import com.areslib.subsystem.SubsystemImplementationKind
import com.areslib.subsystem.InterlockComparison
import com.areslib.subsystem.SubsystemMeasurementSource
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemStateFieldDocument
import com.areslib.subsystem.SubsystemValueType
import com.areslib.subsystem.subsystemCalibrationConfirmationActionKey
import com.areslib.subsystem.subsystemNeutralRecoveryActionKey
import com.areslib.subsystem.subsystemTargetActionKey
import com.areslib.subsystem.subsystemTargetCapabilities
import com.areslib.subsystem.validateSubsystemDocument
import com.areslib.subsystem.validateSubsystemDocuments

enum class GeneratedSubsystemSourceSet { MAIN, TEST }

enum class SubsystemArtifactGroup { DOMAIN, CONTROL, HARDWARE, SIMULATION, GENERATED_PLUMBING, VERIFICATION }
enum class SubsystemArtifactOwnership { USER_OWNED, GENERATED_STARTER, GENERATED_DO_NOT_EDIT }
enum class SubsystemArtifact {
    DEFINITION,
    STATE,
    IO_CONTRACT,
    CONTROLLER,
    SUBSYSTEM_LIFECYCLE,
    PLATFORM_IO,
    MOCK_IO,
    CONTRACT_TEST,
    REGISTRY,
}

data class GeneratedSubsystemFile(
    val relativePath: String,
    val content: String,
    val sourceSet: GeneratedSubsystemSourceSet = GeneratedSubsystemSourceSet.MAIN,
    val artifact: SubsystemArtifact,
    val group: SubsystemArtifactGroup,
    val ownership: SubsystemArtifactOwnership,
    val description: String,
)

data class SubsystemKotlinCodegenTarget(
    val platform: SubsystemPlatform,
    val basePackage: String,
)

/** Deterministic Kotlin source generator shared by Gradle, Analytics preview, and tests. */
object SubsystemKotlinGenerator {
    fun generate(document: SubsystemDocument, target: SubsystemKotlinCodegenTarget): List<GeneratedSubsystemFile> {
        val issues = validateSubsystemDocument(document)
        require(issues.isEmpty()) { issues.joinToString("; ") { "${it.path}: ${it.message}" } }
        require(document.implementation.kind == SubsystemImplementationKind.GENERATED_STARTER) {
            "Subsystem '${document.documentId}' is hand-authored USER-OWNED source; ARES will not generate or replace its Kotlin starters"
        }
        require(document.platform == target.platform) {
            "Subsystem '${document.documentId}' targets ${document.platform}, not ${target.platform}"
        }
        require(target.basePackage.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))) {
            "Invalid subsystem base package '${target.basePackage}'"
        }

        val packageSegment = document.documentId.replace('-', '_')
        val pkg = "${target.basePackage}.$packageSegment"
        val directory = packageSegment
        val files = mutableListOf(
            generated("$directory/${document.kotlinTypeName}Definition.kt", definitionSource(document, pkg), SubsystemArtifact.DEFINITION,
                SubsystemArtifactGroup.GENERATED_PLUMBING, "Declarative DSL mirror used for review and content hashing."),
            starter("$directory/${document.kotlinTypeName}State.kt", stateSource(document, pkg), SubsystemArtifact.STATE,
                SubsystemArtifactGroup.DOMAIN, "Immutable Redux state and safety observations owned by the subsystem."),
            starter("$directory/${document.kotlinTypeName}IO.kt", ioSource(document, pkg), SubsystemArtifact.IO_CONTRACT,
                SubsystemArtifactGroup.HARDWARE, "Cached, fail-closed boundary shared by physical and simulated adapters."),
            starter("$directory/${document.kotlinTypeName}Controller.kt", controllerSource(document, pkg), SubsystemArtifact.CONTROLLER,
                SubsystemArtifactGroup.CONTROL, "Allocation-free policy that converts immutable state into safe IO commands."),
            starter("$directory/${document.kotlinTypeName}Subsystem.kt", subsystemSource(document, pkg), SubsystemArtifact.SUBSYSTEM_LIFECYCLE,
                SubsystemArtifactGroup.CONTROL, "Lifecycle bridge that separates cached reads, Redux updates, and output writes."),
            starter("$directory/${platformPrefix(document.platform)}${document.kotlinTypeName}IO.kt", hardwareIoSource(document, pkg),
                SubsystemArtifact.PLATFORM_IO, SubsystemArtifactGroup.HARDWARE,
                "Platform adapter that owns devices, cached reads, configuration, and output faults."),
        )
        if (document.generateMockIo) {
            files += starter("$directory/Mock${document.kotlinTypeName}IO.kt", mockIoSource(document, pkg),
                SubsystemArtifact.MOCK_IO, SubsystemArtifactGroup.SIMULATION,
                "Deterministic simulator adapter with the same safety and recovery semantics as hardware.")
        }
        if (document.generateTest) {
            files += generated(
                "$directory/${document.kotlinTypeName}GeneratedTest.kt",
                testSource(document, pkg),
                SubsystemArtifact.CONTRACT_TEST,
                SubsystemArtifactGroup.VERIFICATION,
                "Generated contract suite for startup, faults, recovery, parity, cleanup, and hot paths.",
                GeneratedSubsystemSourceSet.TEST,
            )
        }
        return files.sortedWith(compareBy<GeneratedSubsystemFile> { it.sourceSet.ordinal }.thenBy { it.relativePath })
    }

    /** Generates the stable composition root consumed by the season robot shell. */
    fun generateRegistry(
        documents: List<SubsystemDocument>,
        target: SubsystemKotlinCodegenTarget,
    ): GeneratedSubsystemFile {
        val projectIssues = validateSubsystemDocuments(documents)
        require(projectIssues.isEmpty()) {
            projectIssues.joinToString("; ") { "${it.path}: ${it.message}" }
        }
        documents.forEach { document ->
            require(document.platform == target.platform) {
                "Subsystem '${document.documentId}' targets ${document.platform}, not ${target.platform}"
            }
            require(validateSubsystemDocument(document).isEmpty()) { "Subsystem '${document.documentId}' is invalid" }
        }
        val generatedDocuments = documents.filter {
            it.implementation.kind == SubsystemImplementationKind.GENERATED_STARTER
        }
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
            val factory = when (target.platform) {
                SubsystemPlatform.FTC ->
                    "${document.kotlinTypeName}Subsystem(Ftc${document.kotlinTypeName}IO(hardwareMap))"
                SubsystemPlatform.FRC -> if (document.generateMockIo) {
                    "${document.kotlinTypeName}Subsystem(if (isReal) Frc${document.kotlinTypeName}IO() else Mock${document.kotlinTypeName}IO())"
                } else {
                    "if (isReal) ${document.kotlinTypeName}Subsystem(Frc${document.kotlinTypeName}IO()) else null"
                }
            }
            "    GeneratedSubsystemRegistrySupport.install(this, ${document.documentId.quoted()}, ${document.requiredAtStartup}) { $factory }"
        }
        val actionCases = generatedDocuments.sortedBy { it.documentId }.flatMapIndexed { resourceIndex, document ->
            val resourceExpression = "TaskResources.generatedSubsystem($resourceIndex)"
            subsystemTargetCapabilities(listOf(document)).map { capability ->
                when (capability.operation) {
                    SubsystemCapabilityOperation.SET_FIELD ->
                        registryActionCase(document, requireNotNull(document.field(capability.fieldId)), resourceExpression)
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
fun createActionTask(actionKey: String, value: Any): Task? = null"""
        } else {
            """fun createActionTask(actionKey: String, value: Any): Task? = when (actionKey) {
$actionCases
    else -> null
}"""
        }
        val interlockFunctions = generatedDocuments.filter { it.interlocks.isNotEmpty() }
            .sortedBy { it.documentId }
            .joinToString("\n\n") { document -> registryInterlockFunction(document, generatedDocuments) }
        val body = if (generatedDocuments.isEmpty()) {
            val parameter = if (target.platform == SubsystemPlatform.FTC) "hardwareMap: HardwareMap" else "isReal: Boolean"
            """@Suppress("UNUSED_PARAMETER")
fun createAll($parameter): List<Subsystem> = emptyList()"""
        } else when (target.platform) {
            SubsystemPlatform.FTC -> """fun createAll(hardwareMap: HardwareMap): List<Subsystem> = buildList {
$factories
}"""
            SubsystemPlatform.FRC -> {
                """fun createAll(isReal: Boolean): List<Subsystem> = buildList {
$factories
}"""
            }
        }
        val source = buildString {
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
        return generated(
            "GeneratedSubsystemRegistry.kt",
            source,
            SubsystemArtifact.REGISTRY,
            SubsystemArtifactGroup.GENERATED_PLUMBING,
            "Mechanical composition for generated starters, with explicit hand-authored registration reminders.",
        )
    }

    private fun registryInterlockFunction(
        owner: SubsystemDocument,
        documents: List<SubsystemDocument>,
    ): String {
        val checks = owner.interlocks.mapIndexed { index, interlock ->
            val target = documents.single { it.uid == interlock.targetSubsystemUid }
            val field = target.stateFields.single { it.fieldId == interlock.targetFieldId }
            val snapshot = "interlockState$index"
            val lockoutCondition = when (interlock.comparison) {
                InterlockComparison.LESS_THAN -> "$snapshot.${field.fieldId}.toDouble() < ${interlock.thresholdValue.kotlinDouble()}"
                InterlockComparison.GREATER_THAN -> "$snapshot.${field.fieldId}.toDouble() > ${interlock.thresholdValue.kotlinDouble()}"
                InterlockComparison.EQUALS_STATE -> when (field.type) {
                    SubsystemValueType.DOUBLE,
                    SubsystemValueType.INT ->
                        "kotlin.math.abs($snapshot.${field.fieldId}.toDouble() - ${interlock.thresholdValue.kotlinDouble()}) <= 1e-9"
                    SubsystemValueType.BOOLEAN ->
                        "$snapshot.${field.fieldId} == ${interlock.targetStateName!!.lowercase()}"
                    SubsystemValueType.STRING ->
                        "$snapshot.${field.fieldId} == ${interlock.targetStateName!!.quoted()}"
                }
                InterlockComparison.NOT_EQUALS_STATE -> when (field.type) {
                    SubsystemValueType.DOUBLE,
                    SubsystemValueType.INT ->
                        "kotlin.math.abs($snapshot.${field.fieldId}.toDouble() - ${interlock.thresholdValue.kotlinDouble()}) > 1e-9"
                    SubsystemValueType.BOOLEAN ->
                        "$snapshot.${field.fieldId} != ${interlock.targetStateName!!.lowercase()}"
                    SubsystemValueType.STRING ->
                        "$snapshot.${field.fieldId} != ${interlock.targetStateName!!.quoted()}"
                }
            }
            """val $snapshot = robotState.superstructure.subsystems[${target.documentId.quoted()}] as? ${target.kotlinTypeName}State ?: return false
if (!$snapshot.feedbackValid || $lockoutCondition) return false"""
        }.joinToString("\n")
        return """/** Fails closed when a referenced subsystem snapshot is missing, stale, or inside a forbidden zone. */
fun interlocksPermit${owner.kotlinTypeName}(robotState: RobotState): Boolean {
${checks.prependIndent("    ")}
    return true
}"""
    }

    private fun starter(
        path: String,
        source: String,
        artifact: SubsystemArtifact,
        group: SubsystemArtifactGroup,
        description: String,
    ) = GeneratedSubsystemFile(
        path,
        ownershipHeader(SubsystemArtifactOwnership.GENERATED_STARTER, description) + source,
        artifact = artifact,
        group = group,
        ownership = SubsystemArtifactOwnership.GENERATED_STARTER,
        description = description,
    )

    private fun generated(
        path: String,
        source: String,
        artifact: SubsystemArtifact,
        group: SubsystemArtifactGroup,
        description: String,
        sourceSet: GeneratedSubsystemSourceSet = GeneratedSubsystemSourceSet.MAIN,
    ) = GeneratedSubsystemFile(
        path,
        ownershipHeader(SubsystemArtifactOwnership.GENERATED_DO_NOT_EDIT, description) + source,
        sourceSet,
        artifact,
        group,
        SubsystemArtifactOwnership.GENERATED_DO_NOT_EDIT,
        description,
    )

    private fun ownershipHeader(ownership: SubsystemArtifactOwnership, description: String): String = when (ownership) {
        SubsystemArtifactOwnership.USER_OWNED ->
            "// ARES OWNERSHIP: USER-OWNED\n// $description\n"
        SubsystemArtifactOwnership.GENERATED_STARTER ->
            "// ARES OWNERSHIP: GENERATED STARTER\n// $description\n" +
                "// Review and customize this file. Regeneration never replaces it without an explicit diff confirmation.\n"
        SubsystemArtifactOwnership.GENERATED_DO_NOT_EDIT ->
            "// ARES OWNERSHIP: GENERATED - DO NOT EDIT\n// $description\n" +
                "// Edit the .aressubsystem document and regenerate instead.\n"
    }

    private fun definitionSource(document: SubsystemDocument, pkg: String): String {
        val fieldLines = document.stateFields.joinToString("\n") { field ->
            "        val ${field.fieldId} = state.${field.dslFunction()}(\n" +
                "            id = ${field.fieldId.quoted()},\n" +
                "            displayName = ${field.displayName.quoted()},\n" +
                "            role = SubsystemFieldRole.${field.role},\n" +
                "            default = ${field.defaultDslLiteral()},${field.optionalStateArguments()}\n" +
                "        )"
        }
        val hardwareLines = document.hardware.sortedWith(compareBy { it.following != null }).joinToString("\n") { device ->
            val body = buildList {
                device.connection.hardwareMapName?.let { add("hardwareMapName = ${it.quoted()}") }
                device.connection.canId?.let { add("canId = $it") }
                if (document.platform == SubsystemPlatform.FRC && device.kind == SubsystemHardwareKind.MOTOR) {
                    add("canBus = ${device.connection.canBus.quoted()}")
                }
                device.connection.channel?.let { add("channel = $it") }
                if (!device.required) add("required = false")
                if (device.inverted) add("inverted = true")
                device.currentLimitAmps?.let { add("currentLimitAmps = ${it.kotlinDouble()}") }
                device.safeOutput?.let { add("safeOutput = ${it.kotlinDouble()}") }
                device.measurements.forEach { measurement ->
                    val fieldId = measurement.fieldId
                    val arguments = buildList {
                        add(fieldId)
                        add("SubsystemMeasurementSource.${measurement.source}")
                        if (measurement.scale != 1.0) add("scale = ${measurement.scale.kotlinDouble()}")
                        if (measurement.offset != 0.0) add("offset = ${measurement.offset.kotlinDouble()}")
                        measurement.maxAgeMs?.let { add("maxAgeMs = ${it}L") }
                        measurement.validMinimum?.let { add("validMinimum = ${it.kotlinDouble()}") }
                        measurement.validMaximum?.let { add("validMaximum = ${it.kotlinDouble()}") }
                    }
                    add("measurement(${arguments.joinToString()})")
                }
                device.following?.let { follower ->
                    add("follow(${follower.leaderId}, com.areslib.subsystem.SubsystemFollowerTransform.${follower.transform})")
                }
            }.joinToString("\n") { "            $it" }
            "        val ${device.hardwareId} = hardware.${device.dslFunction()}(${device.hardwareId.quoted()}, ${device.displayName.quoted()}) {\n$body\n        }"
        }
        val controlLines = document.controlLoops.joinToString("\n") { loop ->
            val measurement = loop.measurementFieldId?.let { ", $it" }.orEmpty()
            val body = buildList {
                if (loop.kP != 0.0) add("kP = ${loop.kP.kotlinDouble()}")
                if (loop.kI != 0.0) add("kI = ${loop.kI.kotlinDouble()}")
                if (loop.kD != 0.0) add("kD = ${loop.kD.kotlinDouble()}")
                if (loop.feedforward.kind != SubsystemFeedforwardKind.NONE) {
                    add("feedforward.kind = com.areslib.subsystem.SubsystemFeedforwardKind.${loop.feedforward.kind}")
                    if (loop.feedforward.kS != 0.0) add("feedforward.kS = ${loop.feedforward.kS.kotlinDouble()}")
                    if (loop.feedforward.kV != 0.0) add("feedforward.kV = ${loop.feedforward.kV.kotlinDouble()}")
                    if (loop.feedforward.kA != 0.0) add("feedforward.kA = ${loop.feedforward.kA.kotlinDouble()}")
                    if (loop.feedforward.kG != 0.0) add("feedforward.kG = ${loop.feedforward.kG.kotlinDouble()}")
                    loop.feedforward.velocityFieldId?.let { add("feedforward.velocityField = $it") }
                    loop.feedforward.accelerationFieldId?.let { add("feedforward.accelerationField = $it") }
                    loop.feedforward.gravityAngleFieldId?.let { add("feedforward.gravityAngleField = $it") }
                    loop.feedforward.linkageJoint?.let { add("feedforward.linkageJoint = $it") }
                }
                if (loop.derivativeFilterTimeConstantSeconds != 0.02) {
                    add("derivativeFilterTimeConstantSeconds = ${loop.derivativeFilterTimeConstantSeconds.kotlinDouble()}")
                }
                if (loop.tolerance != 0.0) add("tolerance = ${loop.tolerance.kotlinDouble()}")
                if (loop.minimumOutput != -12.0) add("minimumOutput = ${loop.minimumOutput.kotlinDouble()}")
                if (loop.maximumOutput != 12.0) add("maximumOutput = ${loop.maximumOutput.kotlinDouble()}")
            }.joinToString("\n") { "            $it" }
            "        control.${loop.dslFunction()}(${loop.loopId.quoted()}, ${loop.displayName.quoted()}, ${loop.actuatorId}, ${loop.targetFieldId}$measurement) {\n$body\n        }"
        }
        val descriptionLine = document.description.takeIf { it.isNotBlank() }
            ?.let { "        description = ${it.quoted()}\n" }.orEmpty()
        val mockLine = if (!document.generateMockIo) "        generateMockIo = false\n" else ""
        val testLine = if (!document.generateTest) "        generateTest = false\n" else ""
        val requiredLine = if (!document.requiredAtStartup) "        requiredAtStartup = false\n" else ""
        val resourceLine = document.autonomousResourceKey?.let { "        autonomousResourceKey = ${it.quoted()}\n" }.orEmpty()
        val safetyLines = buildList {
            add("            feedbackTimeoutMs = ${document.safety.feedbackTimeoutMs?.let { "${it}L" } ?: "null"}")
            if (document.safety.requiresCalibration) add("            requiresCalibration = true")
            if (!document.safety.requiresConfigurationHealth) add("            requiresConfigurationHealth = false")
            if (document.safety.requiresCurrentMonitoring) add("            requiresCurrentMonitoring = true")
            if (!document.safety.latchOutputFaults) add("            latchOutputFaults = false")
            if (!document.safety.requiresExplicitNeutralRecovery) add("            requiresExplicitNeutralRecovery = false")
            if (!document.safety.telemetryEnabled) add("            telemetryEnabled = false")
            if (!document.safety.zeroAllocationPeriodic) add("            zeroAllocationPeriodic = false")
        }.joinToString("\n")
        val homingLine = homingDsl(document)
        val hash = SubsystemDocumentCodec.contentHash(document)
        return """
            package $pkg

            import com.areslib.subsystem.SubsystemFieldRole
            import com.areslib.subsystem.SubsystemHomingComparison
            import com.areslib.subsystem.SubsystemHomingEvidenceDocument
            import com.areslib.subsystem.SubsystemMeasurementSource
            import com.areslib.subsystem.SubsystemPlatform
            import com.areslib.subsystem.SubsystemTemplate
            import com.areslib.subsystem.subsystem

            /** Generated from `.ares/subsystems/${document.documentId}.aressubsystem`; safe to read and learn from. */
            object ${document.kotlinTypeName}Definition {
                const val CONTENT_SHA256: String = "$hash"

                val document = subsystem(${document.documentId.quoted()}, ${document.kotlinTypeName.quoted()}, SubsystemPlatform.${document.platform}) {
                    template = SubsystemTemplate.${document.template}
                    displayName = ${document.displayName.quoted()}
            $descriptionLine$requiredLine$mockLine$testLine$resourceLine                    safety.apply {
$safetyLines
                    }
$fieldLines

            $hardwareLines

            $homingLine

            $controlLines
                }
            }
        """.trimIndent() + "\n"
    }

    private fun stateSource(document: SubsystemDocument, pkg: String): String {
        val fields = document.stateFields.joinToString(",\n") { field ->
            val bounds = listOfNotNull(field.minimum?.let { "min=$it" }, field.maximum?.let { "max=$it" })
                .joinToString(", ").takeIf(String::isNotBlank)?.let { "; $it" }.orEmpty()
            val unit = field.unit?.let { " in $it" }.orEmpty()
            "    /** ${field.displayName}: ${field.role.name.lowercase()}$unit$bounds. */\n" +
                "    val ${field.fieldId}: ${field.kotlinType()} = ${field.defaultKotlinLiteral()}"
        }
        val separator = if (fields.isBlank()) "" else ",\n"
        val safetyRequests = buildString {
            if (document.hasSafetyRequestHandshake()) {
                append(
                    "\n    /** Advances for every explicit target command and releases a controller neutral hold. */\n" +
                        "    val commandSequence: Long = 0L,"
                )
            }
            if (document.safety.requiresExplicitNeutralRecovery) {
                append(
                    "\n    /** Explicit one-shot neutral request; success holds neutral until the next target command. */\n" +
                        "    val neutralRecoveryRequestSequence: Long = 0L,"
                )
            }
            if (document.safety.requiresCalibration) {
                append(
                    "\n    /** Explicit calibration confirmation; success holds neutral until the next target command. */\n" +
                        "    val calibrationConfirmationRequestSequence: Long = 0L,"
                )
            }
        }
        return """
            package $pkg

            import com.areslib.state.SubsystemState

            /** Immutable state owned by the ${document.displayName} subsystem. */
            data class ${document.kotlinTypeName}State(
            $fields$separator    /** True only when every required cached control sample is fresh and finite. */
                val feedbackValid: Boolean = false,
                /** Receiver timestamp of the newest complete cached input snapshot. */
                val feedbackTimestampMs: Long = 0L,
                /** True only after every required device configuration has succeeded. */
                val configurationHealthy: Boolean = ${(!document.safety.requiresConfigurationHealth)},
                /** True after the configured homing reference has been established. */
                val homed: Boolean = ${(!document.requiresHoming())},
                /** Explicit operator/autonomous request to run the bounded homing state machine. */
                val homingRequested: Boolean = false,
                /** Latched when homing times out or cannot safely write/reset; cancel before retrying. */
                val homingFaultLatched: Boolean = false,
                /** True after mechanism calibration has been explicitly established. */
                val calibrated: Boolean = ${(!document.safety.requiresCalibration)},
                /** True only when required cached current samples are finite and fresh. */
                val currentReadingValid: Boolean = ${(!document.safety.requiresCurrentMonitoring)},
                /** Latched after a failed output write until an explicit successful neutral recovery. */
                val outputFaultLatched: Boolean = false,$safetyRequests
            ) : SubsystemState
        """.trimIndent() + "\n"
    }

    private fun ioSource(document: SubsystemDocument, pkg: String): String {
        val measurements = document.hardware.flatMap { device ->
            device.measurements.mapNotNull { measurement ->
                document.field(measurement.fieldId)?.let { field -> measurement to field }
            }
        }.distinctBy { it.second.fieldId }.map { (measurement, field) ->
            val unit = field.unit?.let { " Unit: $it." }.orEmpty()
            "    /** Cached ${field.displayName} from ${measurement.source.name.lowercase()}.$unit */\n" +
                "    val ${field.fieldId}: ${field.kotlinType()}"
        }
        val commands = document.actuatorLeaders().map { device ->
            val safe = requireNotNull(device.safeOutput)
            "    /** Commands ${device.displayName}; non-finite values fail neutral. Declared neutral: $safe. */\n" +
                "    fun ${device.commandName()}(value: Double)"
        }
        val members = (measurements + commands).joinToString("\n")
        return """
            package $pkg

            import com.areslib.hardware.SubsystemIO

            /**
             * Cached hardware boundary shared by physical and simulated adapters.
             * Getters never perform direct device reads; [refresh] owns one complete input snapshot.
             */
            interface ${document.kotlinTypeName}IO : SubsystemIO, AutoCloseable {
                /** Complete cached snapshot validity; false on any failed or non-finite required read. */
                val feedbackValid: Boolean
                /** Receiver timestamp for the complete cached snapshot, using RobotClock. */
                val feedbackTimestampMs: Long
                /** Required device configuration health. */
                val configurationHealthy: Boolean
                /** Homing-reference validity; always true when homing is not required. */
                val homed: Boolean
                /** True only while every configured cached homing condition is currently satisfied. */
                val homingConditionMet: Boolean
                /** Timeout/write/reset failure latch; a neutral cancel is required before retry. */
                val homingFaultLatched: Boolean
                /** Calibration validity; always true when calibration is not required. */
                val calibrated: Boolean
                /** Cached current validity; always true when current monitoring is not required. */
                val currentReadingValid: Boolean
                /** Failed-write latch. Non-neutral commands are rejected while true. */
                val outputFaultLatched: Boolean
            $members

                /** Applies every declared neutral and clears the fault latch only after complete success. */
                fun recoverWithNeutral(): Boolean
                /** Applies only the bounded descriptor-selected anti-jam output. */
                fun commandAutomaticRecovery(value: Double): Boolean
                /** Latches an exhausted/unsafe recovery and commands neutral. */
                fun latchOutputFault()
                /** Marks an explicitly completed calibration; generated code never infers calibration. */
                fun establishCalibration()
                /** Applies only the bounded generated homing output, bypassing the normal homed permit. */
                fun commandHoming(): Boolean
                /** Neutralizes, establishes the configured zero reference, and marks the mechanism homed. */
                fun establishHome(): Boolean
                /** Latches a failed homing attempt after neutralizing. */
                fun failHoming()
                /** Applies neutral and clears the homing fault so a later explicit request can retry. */
                fun cancelHoming(): Boolean
            }
        """.trimIndent() + "\n"
    }

    private fun controllerSource(document: SubsystemDocument, pkg: String): String {
        val stateFields = document.controlLoops.filter { it.strategy in PID_STRATEGIES }.joinToString("\n") { loop ->
            "    private var ${loop.loopId}Integral = 0.0\n" +
                "    private var ${loop.loopId}PreviousError = 0.0\n" +
                "    private var ${loop.loopId}Derivative = 0.0\n" +
                "    private var ${loop.loopId}HasPreviousError = false"
        }
        val loopBodies = document.controlLoops.joinToString("\n\n") { loop -> controllerLoop(document, loop) }
        val reset = document.controlLoops.filter { it.strategy in PID_STRATEGIES }.joinToString("\n") { loop ->
            "        ${loop.loopId}Integral = 0.0\n" +
                "        ${loop.loopId}PreviousError = 0.0\n" +
                "        ${loop.loopId}Derivative = 0.0\n" +
                "        ${loop.loopId}HasPreviousError = false"
        }.ifBlank { "        // This subsystem has no stateful PID loops." }
        val requestState = buildString {
            if (document.hasSafetyRequestHandshake()) {
                append("    private var neutralHoldCommandSequence = Long.MIN_VALUE\n")
            }
            if (document.safety.requiresExplicitNeutralRecovery) {
                append("    private var handledNeutralRecoveryRequestSequence = 0L\n")
            }
            if (document.safety.requiresCalibration) {
                append("    private var handledCalibrationConfirmationRequestSequence = 0L\n")
            }
            if (document.safety.faultRecovery.enabled) {
                append("    private var jamEvidenceSinceMs = Long.MIN_VALUE\n")
                append("    private var automaticRecoveryStartedAtMs = Long.MIN_VALUE\n")
                append("    private var automaticRecoveryRetries = 0\n")
            }
        }.trimEnd()
        val requestHandling = buildString {
            if (document.safety.requiresExplicitNeutralRecovery) {
                append(
                    """
                    if (state.neutralRecoveryRequestSequence > 0L &&
                        state.neutralRecoveryRequestSequence != handledNeutralRecoveryRequestSequence
                    ) {
                        handledNeutralRecoveryRequestSequence = state.neutralRecoveryRequestSequence
                        reset()
                        if (!safetyRequestPermitted(state, now)) {
                            io.safe()
                            return
                        }
                        // IO owns the latch: a failed neutral must never clear it.
                        if (io.recoverWithNeutral()) {
                            neutralHoldCommandSequence = state.commandSequence
${if (document.safety.faultRecovery.enabled) "                            resetAutomaticRecovery()" else ""}
                        }
                        return
                    }

                    """.trimIndent().prependIndent("        ")
                )
            }
            if (document.safety.requiresCalibration) {
                append(
                    """
                    if (state.calibrationConfirmationRequestSequence > 0L &&
                        state.calibrationConfirmationRequestSequence != handledCalibrationConfirmationRequestSequence
                    ) {
                        handledCalibrationConfirmationRequestSequence = state.calibrationConfirmationRequestSequence
                        reset()
                        val mayCalibrate = safetyRequestPermitted(state, now) && !state.outputFaultLatched
                        if (!mayCalibrate || !io.recoverWithNeutral()) {
                            io.safe()
                            return
                        }
                        io.establishCalibration()
                        neutralHoldCommandSequence = state.commandSequence
                        return
                    }

                    """.trimIndent().prependIndent("        ")
                )
            }
        }.trimEnd()
        val neutralHoldHandling = if (document.hasSafetyRequestHandshake()) {
            """
                    if (neutralHoldCommandSequence != Long.MIN_VALUE) {
                        if (state.commandSequence == neutralHoldCommandSequence) {
                            reset()
                            io.safe()
                            return
                        }
                        neutralHoldCommandSequence = Long.MIN_VALUE
                    }
            """.trimIndent()
        } else ""
        val automaticRecoveryHandling = if (document.safety.faultRecovery.enabled) {
            "        if (handleAutomaticRecovery(state, scale, interlocksPermitted, now)) return"
        } else ""
        val automaticRecoveryHelper = automaticRecoveryControllerHelper(document)
        val safetyRequestHelper = if (
            document.hasSafetyRequestHandshake()
        ) {
            val feedbackTimeoutMs = document.safety.feedbackTimeoutMs ?: Long.MAX_VALUE
            """

                private fun safetyRequestPermitted(
                    state: ${document.kotlinTypeName}State,
                    now: Long,
                ): Boolean {
                    val feedbackAgeMs = if (now >= state.feedbackTimestampMs) {
                        now - state.feedbackTimestampMs
                    } else {
                        Long.MAX_VALUE
                    }
                    return state.feedbackValid && feedbackAgeMs <= ${feedbackTimeoutMs}L &&
                        state.configurationHealthy && state.currentReadingValid
                }
            """.trimEnd()
        } else ""
        return """
            package $pkg

            import com.areslib.util.RobotClock
            import kotlin.math.abs
            import kotlin.math.sign

            /** Allocation-free controller generated from the visual/hand-authored subsystem DSL. */
            class ${document.kotlinTypeName}Controller(private val io: ${document.kotlinTypeName}IO) {
                private var lastTimestampMs = 0L
                private var homingStartedAtMs = Long.MIN_VALUE
                private var homingEvidenceSinceMs = Long.MIN_VALUE
            $requestState
            $stateFields

                /**
                 * Applies one allocation-free control step from immutable [state]. [scale] is the
                 * current brownout/current-budget multiplier; invalid or unsafe input commands neutral.
                 */
                fun update(
                    state: ${document.kotlinTypeName}State,
                    scale: Double,
                    interlocksPermitted: Boolean = true,
                ) {
                    val now = RobotClock.currentTimeMillis()
            $requestHandling
            $neutralHoldHandling
                    if (${document.requiresHoming()} && !state.homed) {
                        updateHoming(state, scale, now)
                        return
                    }
                    resetHomingAttempt()
$automaticRecoveryHandling
                    val safetyPermit = interlocksPermitted && state.feedbackValid && state.configurationHealthy && state.homed &&
                        state.calibrated && state.currentReadingValid && !state.outputFaultLatched
                    if (!scale.isFinite() || scale <= 0.0 || !safetyPermit) {
                        reset()
                        io.safe()
                        return
                    }
                    val dtSeconds = if (lastTimestampMs == 0L) 0.02 else ((now - lastTimestampMs) / 1000.0).coerceIn(0.001, 0.1)
                    lastTimestampMs = now

            $loopBodies
                }

                /** Clears controller history; callers must still command IO neutral. */
                fun reset() {
                    lastTimestampMs = 0L
                    resetHomingAttempt()
${if (document.safety.faultRecovery.enabled) "                    resetAutomaticRecovery()" else ""}
            $reset
                }

                private fun updateHoming(state: ${document.kotlinTypeName}State, scale: Double, now: Long) {
                    val permitted = state.homingRequested && !state.homingFaultLatched &&
                        state.feedbackValid && state.configurationHealthy && state.currentReadingValid &&
                        !state.outputFaultLatched && scale.isFinite() && scale > 0.0
                    if (!permitted) {
                        if (!state.homingRequested && state.homingFaultLatched) io.cancelHoming() else io.safe()
                        resetHomingAttempt()
                        return
                    }
                    if (homingStartedAtMs == Long.MIN_VALUE) homingStartedAtMs = now
                    if (now < homingStartedAtMs || now - homingStartedAtMs > ${document.safety.homing.timeoutMs}L) {
                        io.failHoming()
                        resetHomingAttempt()
                        return
                    }
                    if (!io.commandHoming()) {
                        io.failHoming()
                        resetHomingAttempt()
                        return
                    }
                    if (!io.homingConditionMet) {
                        homingEvidenceSinceMs = Long.MIN_VALUE
                        return
                    }
                    if (homingEvidenceSinceMs == Long.MIN_VALUE) homingEvidenceSinceMs = now
                    if (now >= homingEvidenceSinceMs && now - homingEvidenceSinceMs >= ${document.safety.homing.dwellMs}L) {
                        if (!io.establishHome()) io.failHoming()
                        resetHomingAttempt()
                    }
                }

                private fun resetHomingAttempt() {
                    homingStartedAtMs = Long.MIN_VALUE
                    homingEvidenceSinceMs = Long.MIN_VALUE
                }
            $safetyRequestHelper
            $automaticRecoveryHelper
            }
        """.trimIndent() + "\n"
    }

    private fun controllerLoop(document: SubsystemDocument, loop: SubsystemControlLoopDocument): String {
        val actuator = document.hardware.first { it.hardwareId == loop.actuatorId }
        val targetField = document.stateFields.first { it.fieldId == loop.targetFieldId }
        val rawTarget = "state.${loop.targetFieldId}.toDouble()"
        val target = targetField.clampedExpression(rawTarget)
        val command = "io.${actuator.commandName()}"
        return when (loop.strategy) {
            SubsystemControlStrategy.DIRECT ->
                "        $command((($target).takeIf(Double::isFinite) ?: 0.0).coerceIn(${loop.minimumOutput.kotlinDouble()}, ${loop.maximumOutput.kotlinDouble()}) * scale)"
            SubsystemControlStrategy.SERVO_POSITION ->
                "        $command((($target).takeIf(Double::isFinite) ?: 0.0).coerceIn(0.0, 1.0))"
            SubsystemControlStrategy.BANG_BANG -> {
                val measurement = "state.${requireNotNull(loop.measurementFieldId)}.toDouble()"
                """        val ${loop.loopId}Target = $target
        val ${loop.loopId}Measurement = $measurement
        val ${loop.loopId}Error = ${loop.loopId}Target - ${loop.loopId}Measurement
        val ${loop.loopId}Output = when {
            !${loop.loopId}Target.isFinite() || !${loop.loopId}Measurement.isFinite() -> 0.0
            abs(${loop.loopId}Error) <= ${loop.tolerance.kotlinDouble()} -> 0.0
            ${loop.loopId}Error > 0.0 -> ${loop.maximumOutput.kotlinDouble()}
            else -> ${loop.minimumOutput.kotlinDouble()}
        }
        $command(${loop.loopId}Output * scale)"""
            }
            SubsystemControlStrategy.POSITION_PID, SubsystemControlStrategy.VELOCITY_PID -> {
                val measurement = "state.${requireNotNull(loop.measurementFieldId)}.toDouble()"
                val feedforward = feedforwardExpression(document, loop)
                """        val ${loop.loopId}Target = $target
        val ${loop.loopId}Measurement = $measurement
        if (!${loop.loopId}Target.isFinite() || !${loop.loopId}Measurement.isFinite()) {
            ${loop.loopId}Integral = 0.0
            ${loop.loopId}Derivative = 0.0
            ${loop.loopId}HasPreviousError = false
            $command(0.0)
        } else {
            val ${loop.loopId}Error = ${loop.loopId}Target - ${loop.loopId}Measurement
            val ${loop.loopId}RawDerivative = if (${loop.loopId}HasPreviousError) {
                (${loop.loopId}Error - ${loop.loopId}PreviousError) / dtSeconds
            } else {
                0.0
            }
            val ${loop.loopId}DerivativeAlpha = dtSeconds / (${loop.derivativeFilterTimeConstantSeconds.kotlinDouble()} + dtSeconds)
            ${loop.loopId}Derivative += ${loop.loopId}DerivativeAlpha * (${loop.loopId}RawDerivative - ${loop.loopId}Derivative)
            ${loop.loopId}PreviousError = ${loop.loopId}Error
            ${loop.loopId}HasPreviousError = true
            val ${loop.loopId}CandidateIntegral = ${loop.loopId}Integral + ${loop.loopId}Error * dtSeconds
$feedforward
            val ${loop.loopId}Unclamped = ${loop.kP.kotlinDouble()} * ${loop.loopId}Error + ${loop.kI.kotlinDouble()} * ${loop.loopId}CandidateIntegral + ${loop.kD.kotlinDouble()} * ${loop.loopId}Derivative + ${loop.loopId}Feedforward
            val ${loop.loopId}Output = ${loop.loopId}Unclamped.coerceIn(${loop.minimumOutput.kotlinDouble()}, ${loop.maximumOutput.kotlinDouble()})
            if (${loop.loopId}Unclamped == ${loop.loopId}Output || sign(${loop.loopId}Error) != sign(${loop.loopId}Unclamped - ${loop.loopId}Output)) {
                ${loop.loopId}Integral = ${loop.loopId}CandidateIntegral
            }
            $command(${loop.loopId}Output * scale)
        }"""
            }
        }
    }

    private fun subsystemSource(document: SubsystemDocument, pkg: String): String {
        val copies = document.hardware.flatMap { it.measurements }.mapNotNull { measurement ->
            val field = document.field(measurement.fieldId) ?: return@mapNotNull null
            "            ${field.fieldId} = io.${field.fieldId}"
        }.distinct().joinToString(",\n")
        val setters = document.stateFields.filter { it.role == SubsystemFieldRole.TARGET }.joinToString("\n\n") { field ->
            val cap = field.fieldId.pascalCase()
            val nextCommand = if (document.hasSafetyRequestHandshake()) {
                """
        val nextCommandSequence = if (current.commandSequence == Long.MAX_VALUE) 1L else current.commandSequence + 1L
        store.dispatch(RobotAction.UpdateNamedSubsystemState(
            ID,
            current.copy(${field.fieldId} = value, commandSequence = nextCommandSequence),
        ))
                """.trimIndent()
            } else {
                "store.dispatch(RobotAction.UpdateNamedSubsystemState(ID, current.copy(${field.fieldId} = value)))"
            }
            """    fun set$cap(store: Store, value: ${field.kotlinType()}) {
        val current = state(store.state)
        $nextCommand
    }"""
        }
        val feedbackTimeoutMs = document.safety.feedbackTimeoutMs ?: Long.MAX_VALUE
        val registryPackage = pkg.substringBeforeLast('.')
        val interlockPermit = if (document.interlocks.isEmpty()) {
            "true"
        } else {
            "GeneratedSubsystemRegistry.interlocksPermit${document.kotlinTypeName}(state)"
        }
        return """
            package $pkg

            import com.areslib.Store
            import com.areslib.action.RobotAction
            import com.areslib.state.RobotState
            import com.areslib.subsystem.Subsystem
            import $registryPackage.GeneratedSubsystemRegistry

            /** Robot-loop host. Hardware reads, Redux updates, and output writes remain separated. */
            class ${document.kotlinTypeName}Subsystem(private val io: ${document.kotlinTypeName}IO) : Subsystem {
                private val controller = ${document.kotlinTypeName}Controller(io)

                /** Copies the already-refreshed hardware snapshot into immutable Redux state. */
                override fun readSensors(store: Store, timestampMs: Long) {
                    val snapshotAgeMs = if (timestampMs >= io.feedbackTimestampMs) {
                        timestampMs - io.feedbackTimestampMs
                    } else {
                        Long.MAX_VALUE
                    }
                    val updated = state(store.state).copy(
            $copies${if (copies.isBlank()) "" else ","}
                        feedbackValid = io.feedbackValid && snapshotAgeMs <= ${feedbackTimeoutMs}L,
                        feedbackTimestampMs = io.feedbackTimestampMs,
                        configurationHealthy = io.configurationHealthy,
                        homed = io.homed,
                        homingFaultLatched = io.homingFaultLatched,
                        calibrated = io.calibrated,
                        currentReadingValid = io.currentReadingValid,
                        outputFaultLatched = io.outputFaultLatched,
                    )
                    store.dispatch(RobotAction.UpdateNamedSubsystemState(ID, updated, timestampMs))
                }

                /** Applies immutable state to IO through the safety-gated controller. */
                override fun writeOutputs(state: RobotState, scale: Double) {
                    controller.update(state(state), scale, $interlockPermit)
                }

            $setters

                /** Resets controller history, commands neutral, and releases owned IO idempotently. */
                override fun close() {
                    controller.reset()
                    io.safe()
                    io.close()
                }

                companion object {
                    const val ID: String = ${document.documentId.quoted()}

                    fun state(robotState: RobotState): ${document.kotlinTypeName}State =
                        robotState.superstructure.subsystems[ID] as? ${document.kotlinTypeName}State ?: ${document.kotlinTypeName}State()
                }
            }
        """.trimIndent() + "\n"
    }

    private fun hardwareIoSource(document: SubsystemDocument, pkg: String): String = when (document.platform) {
        SubsystemPlatform.FTC -> ftcIoSource(document, pkg)
        SubsystemPlatform.FRC -> frcIoSource(document, pkg)
    }

    private fun ftcIoSource(document: SubsystemDocument, pkg: String): String {
        val imports = linkedSetOf(
            "com.areslib.hardware.HardwareRegistry",
            "com.areslib.util.RobotClock",
            "com.qualcomm.robotcore.hardware.HardwareMap",
        )
        document.hardware.forEach { device ->
            imports += when (device.kind) {
                SubsystemHardwareKind.MOTOR -> "com.qualcomm.robotcore.hardware.DcMotorEx"
                SubsystemHardwareKind.POSITIONAL_SERVO -> "com.qualcomm.robotcore.hardware.Servo"
                SubsystemHardwareKind.CONTINUOUS_SERVO -> "com.qualcomm.robotcore.hardware.CRServo"
                SubsystemHardwareKind.DIGITAL_INPUT -> "com.qualcomm.robotcore.hardware.DigitalChannel"
                SubsystemHardwareKind.ANALOG_INPUT -> "com.qualcomm.robotcore.hardware.AnalogInput"
                SubsystemHardwareKind.COLOR_SENSOR -> "com.qualcomm.robotcore.hardware.ColorSensor"
            }
        }
        if (document.hardware.any {
                it.inverted && (it.kind == SubsystemHardwareKind.MOTOR ||
                    it.kind == SubsystemHardwareKind.CONTINUOUS_SERVO)
            }
        ) {
            imports += "com.qualcomm.robotcore.hardware.DcMotorSimple"
        }
        if (document.hardware.any { device -> device.measurements.any { it.source == SubsystemMeasurementSource.MOTOR_CURRENT_AMPS } }) {
            imports += "org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit"
        }
        val fields = document.hardware.joinToString("\n") { device ->
            val type = device.ftcType()
            val name = requireNotNull(device.connection.hardwareMapName)
            val initializer = if (device.required) {
                "hardwareMap.get($type::class.java, ${name.quoted()})"
            } else {
                "try { hardwareMap.get($type::class.java, ${name.quoted()}) } catch (_: Exception) { null }"
            }
            "    private val ${device.hardwareId}: $type? = $initializer"
        }
        val cached = document.hardware.flatMap { it.measurements }.mapNotNull { measurement ->
            val field = document.field(measurement.fieldId) ?: return@mapNotNull null
            "    private var cached${field.fieldId.pascalCase()}: ${field.kotlinType()} = ${field.defaultKotlinLiteral()}\n" +
                "    override val ${field.fieldId}: ${field.kotlinType()} get() = cached${field.fieldId.pascalCase()}"
        }.distinct().joinToString("\n")
        val configure = document.hardware.mapNotNull { device ->
            when {
                (device.kind == SubsystemHardwareKind.MOTOR ||
                    device.kind == SubsystemHardwareKind.CONTINUOUS_SERVO) && device.inverted ->
                    "            ${device.hardwareId}?.direction = DcMotorSimple.Direction.REVERSE"
                device.kind == SubsystemHardwareKind.POSITIONAL_SERVO && device.inverted ->
                    "            ${device.hardwareId}?.direction = Servo.Direction.REVERSE"
                device.kind == SubsystemHardwareKind.DIGITAL_INPUT ->
                    "            ${device.hardwareId}?.mode = DigitalChannel.Mode.INPUT"
                else -> null
            }
        }.joinToString("\n").ifBlank { "            // No one-time device configuration is required." }
        val readings = document.hardware.flatMap { device -> device.measurements.map { device to it } }.mapNotNull { (device, measurement) ->
            val field = document.field(measurement.fieldId) ?: return@mapNotNull null
            val read = when (measurement.source) {
                SubsystemMeasurementSource.MOTOR_POSITION_NATIVE ->
                    "${device.hardwareId}?.currentPosition?.toDouble() ?: 0.0"
                SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND ->
                    "${device.hardwareId}?.velocity ?: 0.0"
                SubsystemMeasurementSource.MOTOR_CURRENT_AMPS ->
                    "${device.hardwareId}?.getCurrent(CurrentUnit.AMPS) ?: 0.0"
                SubsystemMeasurementSource.DIGITAL_STATE -> "${device.hardwareId}?.state ?: false"
                SubsystemMeasurementSource.ANALOG_VOLTAGE -> "${device.hardwareId}?.voltage ?: 0.0"
                SubsystemMeasurementSource.COLOR_ARGB -> "${device.hardwareId}?.argb() ?: 0"
            }
            val converted = if (field.type == SubsystemValueType.DOUBLE) {
                "($read) * ${measurement.scale.kotlinDouble()} + ${measurement.offset.kotlinDouble()}"
            } else read
            val next = "next${field.fieldId.pascalCase()}"
            val finiteCheck = if (field.type == SubsystemValueType.DOUBLE) {
                buildString {
                    append("\n            require($next.isFinite()) { ${("Non-finite ${field.displayName}").quoted()} }")
                    measurement.validMinimum?.let { append("\n            require($next >= ${it.kotlinDouble()}) { ${("${field.displayName} below its valid minimum").quoted()} }") }
                    measurement.validMaximum?.let { append("\n            require($next <= ${it.kotlinDouble()}) { ${("${field.displayName} above its valid maximum").quoted()} }") }
                }
            } else ""
            "            val $next = $converted$finiteCheck"
        }.distinct().joinToString("\n").ifBlank { "            // This subsystem has no readable sensors." }
        val commitReadings = document.hardware.flatMap { it.measurements }.mapNotNull { measurement ->
            document.field(measurement.fieldId)?.let { field ->
                "            cached${field.fieldId.pascalCase()} = next${field.fieldId.pascalCase()}"
            }
        }.distinct().joinToString("\n")
        val homingCondition = homingConditionExpression(document, "cached")
        val currentFields = document.hardware.flatMap { device ->
            device.measurements.filter { it.source == SubsystemMeasurementSource.MOTOR_CURRENT_AMPS }
        }.map { it.fieldId }.distinct()
        val currentValidity = if (document.safety.requiresCurrentMonitoring) {
            currentFields.joinToString(" && ") {
                "cached${it.pascalCase()}.isFinite() && cached${it.pascalCase()} >= 0.0"
            }.ifBlank { "false" }
        } else "true"
        val telemetry = telemetryBody(document)
        val commands = document.actuatorLeaders().joinToString("\n\n") { device ->
            val neutral = requireNotNull(device.safeOutput).kotlinDouble()
            val assignment = (listOf(device to "requested") + document.followersOf(device.hardwareId).map { follower ->
                follower to follower.following!!.transformedExpression("requested")
            }).joinToString("\n            ") { (target, expression) ->
                when (target.kind) {
                    SubsystemHardwareKind.MOTOR -> "requireNotNull(${target.hardwareId}) { ${("Missing ${target.displayName}").quoted()} }.power = (($expression) / 12.0).coerceIn(-1.0, 1.0)"
                    SubsystemHardwareKind.POSITIONAL_SERVO -> "requireNotNull(${target.hardwareId}) { ${("Missing ${target.displayName}").quoted()} }.position = ($expression).coerceIn(0.0, 1.0)"
                    SubsystemHardwareKind.CONTINUOUS_SERVO -> "requireNotNull(${target.hardwareId}) { ${("Missing ${target.displayName}").quoted()} }.power = ($expression).coerceIn(-1.0, 1.0)"
                    else -> error("Not an actuator")
                }
            }
            """    override fun ${device.commandName()}(value: Double) {
        val requested = value.takeIf(Double::isFinite) ?: $neutral
        if (outputFaultLatched && requested != $neutral) return
        if (requested != $neutral && (!configurationHealthy || !homed || !calibrated ||
                !feedbackValid || !currentReadingValid)) return
        try {
            $assignment
        } catch (_: Exception) {
                    outputFaultLatched = ${document.safety.latchOutputFaults}
            safe()
        }
    }"""
        }
        val neutralWrites = document.hardware.filter { it.kind.isActuator() }
            .joinToString("\n") { device ->
                val neutral = requireNotNull(device.safeOutput).kotlinDouble()
                val assignment = when (device.kind) {
                    SubsystemHardwareKind.MOTOR -> "${device.hardwareId}?.power = ($neutral / 12.0).coerceIn(-1.0, 1.0)"
                    SubsystemHardwareKind.POSITIONAL_SERVO -> "${device.hardwareId}?.position = $neutral.coerceIn(0.0, 1.0)"
                    SubsystemHardwareKind.CONTINUOUS_SERVO -> "${device.hardwareId}?.power = $neutral.coerceIn(-1.0, 1.0)"
                    else -> error("Not an FTC actuator")
                }
                "        try { $assignment } catch (_: Exception) { succeeded = false }"
            }
            .ifBlank { "        // Sensor-only subsystem: neutral is already satisfied." }
        return """
            package $pkg

            ${imports.sorted().joinToString("\n") { "import $it" }}

            /**
             * FTC adapter starter. All SDK reads occur in [refresh], all getters are cached, and
             * failed writes latch until [recoverWithNeutral] successfully applies every neutral.
             */
            class Ftc${document.kotlinTypeName}IO(hardwareMap: HardwareMap) : ${document.kotlinTypeName}IO {
            $fields
            $cached
                override var feedbackValid: Boolean = false
                    private set
                override var feedbackTimestampMs: Long = 0L
                    private set
                override var configurationHealthy: Boolean = false
                    private set
                override var homed: Boolean = ${(!document.requiresHoming())}
                    private set
                override var homingConditionMet: Boolean = false
                    private set
                override var homingFaultLatched: Boolean = false
                    private set
                override var calibrated: Boolean = ${(!document.safety.requiresCalibration)}
                    private set
                override var currentReadingValid: Boolean = ${(!document.safety.requiresCurrentMonitoring)}
                    private set
                override var outputFaultLatched: Boolean = false
                    private set
                private var closed = false

                init {
                    configurationHealthy = try {
            $configure
                        true
                    } catch (_: Exception) {
                        false
                    }
                    HardwareRegistry.registerDevice(${("Subsystems/${document.documentId}").quoted()}, this)
                }

                override fun refresh() {
                    if (closed) return
                    try {
            $readings
            $commitReadings
                        feedbackTimestampMs = RobotClock.currentTimeMillis()
                        feedbackValid = true
                        currentReadingValid = $currentValidity
                        homingConditionMet = $homingCondition
                    } catch (_: Exception) {
                        feedbackValid = false
                        currentReadingValid = ${(!document.safety.requiresCurrentMonitoring)}
                    }
                }

            $commands

                override fun safe() {
                    if (!applyNeutral()) outputFaultLatched = ${document.safety.latchOutputFaults}
                }

                override fun recoverWithNeutral(): Boolean {
                    val recovered = applyNeutral()
                    if (recovered) outputFaultLatched = false
                    return recovered
                }

${automaticRecoveryMethods(document)}

                override fun establishCalibration() {
                    if (configurationHealthy) calibrated = true
                }

${homingMethods(document, isFtc = true)}

                private fun applyNeutral(): Boolean {
                    var succeeded = true
            $neutralWrites
                    return succeeded
                }

                override fun logTelemetry(telemetry: com.areslib.telemetry.ITelemetry, prefix: String) {
$telemetry
                }

                override fun close() {
                    if (closed) return
                    closed = true
                    safe()
                }
            }
        """.trimIndent() + "\n"
    }

    private fun frcIoSource(document: SubsystemDocument, pkg: String): String {
        val imports = linkedSetOf(
            "com.areslib.hardware.HardwareRegistry",
            "com.areslib.util.RobotClock",
        )
        document.hardware.forEach { device ->
            imports += when (device.kind) {
                SubsystemHardwareKind.MOTOR -> "com.ctre.phoenix6.hardware.TalonFX"
                SubsystemHardwareKind.POSITIONAL_SERVO -> "edu.wpi.first.wpilibj.Servo"
                SubsystemHardwareKind.CONTINUOUS_SERVO -> "edu.wpi.first.wpilibj.motorcontrol.PWMSparkMax"
                SubsystemHardwareKind.DIGITAL_INPUT -> "edu.wpi.first.wpilibj.DigitalInput"
                SubsystemHardwareKind.ANALOG_INPUT -> "edu.wpi.first.wpilibj.AnalogInput"
                SubsystemHardwareKind.COLOR_SENSOR -> error("FRC color sensors are rejected by validation")
            }
        }
        if (document.hardware.any { it.kind == SubsystemHardwareKind.MOTOR }) {
            imports += "com.ctre.phoenix6.configs.TalonFXConfiguration"
        }
        val fields = document.hardware.joinToString("\n") { device ->
            val constructor = when (device.kind) {
                SubsystemHardwareKind.MOTOR -> "TalonFX(${device.connection.canId}, ${device.connection.canBus.quoted()})"
                SubsystemHardwareKind.POSITIONAL_SERVO -> "Servo(${device.connection.channel})"
                SubsystemHardwareKind.CONTINUOUS_SERVO -> "PWMSparkMax(${device.connection.channel})"
                SubsystemHardwareKind.DIGITAL_INPUT -> "DigitalInput(${device.connection.channel})"
                SubsystemHardwareKind.ANALOG_INPUT -> "AnalogInput(${device.connection.channel})"
                SubsystemHardwareKind.COLOR_SENSOR -> error("Unsupported")
            }
            "    private val ${device.hardwareId} = $constructor"
        }
        val cached = document.hardware.flatMap { it.measurements }.mapNotNull { measurement ->
            val field = document.field(measurement.fieldId) ?: return@mapNotNull null
            "    private var cached${field.fieldId.pascalCase()}: ${field.kotlinType()} = ${field.defaultKotlinLiteral()}\n" +
                "    override val ${field.fieldId}: ${field.kotlinType()} get() = cached${field.fieldId.pascalCase()}"
        }.distinct().joinToString("\n")
        val init = document.hardware.filter {
            it.kind == SubsystemHardwareKind.MOTOR ||
                (it.kind == SubsystemHardwareKind.CONTINUOUS_SERVO && it.inverted)
        }
            .joinToString("\n") { device ->
                if (device.kind == SubsystemHardwareKind.CONTINUOUS_SERVO) {
                    return@joinToString "        ${device.hardwareId}.setInverted(true)"
                }
                val configName = "${device.hardwareId}Configuration"
                buildString {
                    append("        val $configName = TalonFXConfiguration()\n")
                    if (device.inverted) {
                        append("        $configName.MotorOutput.Inverted = com.ctre.phoenix6.signals.InvertedValue.Clockwise_Positive\n")
                    }
                    device.currentLimitAmps?.let { limit ->
                        append("        $configName.CurrentLimits.SupplyCurrentLimitEnable = true\n")
                        append("        $configName.CurrentLimits.SupplyCurrentLimit = ${limit.kotlinDouble()}\n")
                    }
                    append("            check(${device.hardwareId}.configurator.apply($configName).isOK) { ${("Failed to configure ${device.displayName}").quoted()} }\n")
                    append("            ${device.hardwareId}.optimizeBusUtilization()")
                }
            }
            .ifBlank { "            // No TalonFX configuration is required." }
        val readings = document.hardware.flatMap { device -> device.measurements.map { device to it } }.mapNotNull { (device, measurement) ->
            val field = document.field(measurement.fieldId) ?: return@mapNotNull null
            val read = when (measurement.source) {
                SubsystemMeasurementSource.MOTOR_POSITION_NATIVE -> "${device.hardwareId}.position.valueAsDouble"
                SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND -> "${device.hardwareId}.velocity.valueAsDouble"
                SubsystemMeasurementSource.MOTOR_CURRENT_AMPS -> "${device.hardwareId}.statorCurrent.valueAsDouble"
                SubsystemMeasurementSource.DIGITAL_STATE -> "${device.hardwareId}.get()"
                SubsystemMeasurementSource.ANALOG_VOLTAGE -> "${device.hardwareId}.voltage"
                SubsystemMeasurementSource.COLOR_ARGB -> error("FRC color sensors are rejected by validation")
            }
            val converted = if (field.type == SubsystemValueType.DOUBLE) {
                "($read) * ${measurement.scale.kotlinDouble()} + ${measurement.offset.kotlinDouble()}"
            } else read
            val next = "next${field.fieldId.pascalCase()}"
            val finiteCheck = if (field.type == SubsystemValueType.DOUBLE) {
                buildString {
                    append("\n            require($next.isFinite()) { ${("Non-finite ${field.displayName}").quoted()} }")
                    measurement.validMinimum?.let { append("\n            require($next >= ${it.kotlinDouble()}) { ${("${field.displayName} below its valid minimum").quoted()} }") }
                    measurement.validMaximum?.let { append("\n            require($next <= ${it.kotlinDouble()}) { ${("${field.displayName} above its valid maximum").quoted()} }") }
                }
            } else ""
            "            val $next = $converted$finiteCheck"
        }.distinct().joinToString("\n").ifBlank { "            // This subsystem has no readable sensors." }
        val commitReadings = document.hardware.flatMap { it.measurements }.mapNotNull { measurement ->
            document.field(measurement.fieldId)?.let { field ->
                "            cached${field.fieldId.pascalCase()} = next${field.fieldId.pascalCase()}"
            }
        }.distinct().joinToString("\n")
        val homingCondition = homingConditionExpression(document, "cached")
        val currentFields = document.hardware.flatMap { device ->
            device.measurements.filter { it.source == SubsystemMeasurementSource.MOTOR_CURRENT_AMPS }
        }.map { it.fieldId }.distinct()
        val currentValidity = if (document.safety.requiresCurrentMonitoring) {
            currentFields.joinToString(" && ") {
                "cached${it.pascalCase()}.isFinite() && cached${it.pascalCase()} >= 0.0"
            }.ifBlank { "false" }
        } else "true"
        val telemetry = telemetryBody(document)
        val commands = document.actuatorLeaders().joinToString("\n\n") { device ->
            val neutral = requireNotNull(device.safeOutput).kotlinDouble()
            val command = (listOf(device to "requested") + document.followersOf(device.hardwareId).map { follower ->
                follower to follower.following!!.transformedExpression("requested")
            }).joinToString("\n            ") { (target, expression) ->
                val applied = if (target.kind == SubsystemHardwareKind.POSITIONAL_SERVO) {
                    target.invertedExpression(expression)
                } else {
                    expression
                }
                when (target.kind) {
                    SubsystemHardwareKind.MOTOR -> "${target.hardwareId}.setVoltage(($applied).coerceIn(-12.0, 12.0))"
                    SubsystemHardwareKind.POSITIONAL_SERVO -> "${target.hardwareId}.set(($applied).coerceIn(0.0, 1.0))"
                    SubsystemHardwareKind.CONTINUOUS_SERVO -> "${target.hardwareId}.set(($applied).coerceIn(-1.0, 1.0))"
                    else -> error("Not actuator")
                }
            }
            """    override fun ${device.commandName()}(value: Double) {
        val requested = value.takeIf(Double::isFinite) ?: $neutral
        if (outputFaultLatched && requested != $neutral) return
        if (requested != $neutral && (!configurationHealthy || !homed || !calibrated ||
                !feedbackValid || !currentReadingValid)) return
        try {
            $command
        } catch (_: Exception) {
            outputFaultLatched = ${document.safety.latchOutputFaults}
            safe()
        }
    }"""
        }
        val neutralWrites = document.hardware.filter { it.kind.isActuator() }
            .joinToString("\n") { device ->
                val neutral = requireNotNull(device.safeOutput).kotlinDouble()
                val appliedNeutral = if (device.kind == SubsystemHardwareKind.POSITIONAL_SERVO) {
                    device.invertedExpression(neutral)
                } else {
                    neutral
                }
                val command = when (device.kind) {
                    SubsystemHardwareKind.MOTOR -> "${device.hardwareId}.setVoltage($appliedNeutral.coerceIn(-12.0, 12.0))"
                    SubsystemHardwareKind.POSITIONAL_SERVO -> "${device.hardwareId}.set($appliedNeutral.coerceIn(0.0, 1.0))"
                    SubsystemHardwareKind.CONTINUOUS_SERVO -> "${device.hardwareId}.set($appliedNeutral.coerceIn(-1.0, 1.0))"
                    else -> error("Not an FRC actuator")
                }
                "        try { $command } catch (_: Exception) { succeeded = false }"
            }
            .ifBlank { "        // Sensor-only subsystem: neutral is already satisfied." }
        val close = document.hardware.joinToString("\n") { device ->
            when (device.kind) {
                SubsystemHardwareKind.MOTOR -> "        try { ${device.hardwareId}.close() } catch (_: Exception) { /* Continue closing. */ }"
                SubsystemHardwareKind.POSITIONAL_SERVO,
                SubsystemHardwareKind.CONTINUOUS_SERVO,
                SubsystemHardwareKind.DIGITAL_INPUT,
                SubsystemHardwareKind.ANALOG_INPUT -> "        try { ${device.hardwareId}.close() } catch (_: Exception) { /* Continue closing. */ }"
                SubsystemHardwareKind.COLOR_SENSOR -> ""
            }
        }
        return """
            package $pkg

            ${imports.sorted().joinToString("\n") { "import $it" }}

            /**
             * FRC adapter starter. All device reads occur in [refresh], configuration is checked,
             * and failed writes latch until [recoverWithNeutral] applies every declared neutral.
             */
            class Frc${document.kotlinTypeName}IO : ${document.kotlinTypeName}IO {
            $fields
            $cached
                override var feedbackValid: Boolean = false
                    private set
                override var feedbackTimestampMs: Long = 0L
                    private set
                override var configurationHealthy: Boolean = false
                    private set
                override var homed: Boolean = ${(!document.requiresHoming())}
                    private set
                override var homingConditionMet: Boolean = false
                    private set
                override var homingFaultLatched: Boolean = false
                    private set
                override var calibrated: Boolean = ${(!document.safety.requiresCalibration)}
                    private set
                override var currentReadingValid: Boolean = ${(!document.safety.requiresCurrentMonitoring)}
                    private set
                override var outputFaultLatched: Boolean = false
                    private set
                private var closed = false

                init {
                    configurationHealthy = try {
            $init
                        true
                    } catch (_: Exception) {
                        false
                    }
                    HardwareRegistry.registerDevice(${("Subsystems/${document.documentId}").quoted()}, this)
                }

                override fun refresh() {
                    if (closed) return
                    try {
            $readings
            $commitReadings
                        feedbackTimestampMs = RobotClock.currentTimeMillis()
                        feedbackValid = true
                        currentReadingValid = $currentValidity
                        homingConditionMet = $homingCondition
                    } catch (_: Exception) {
                        feedbackValid = false
                        currentReadingValid = ${(!document.safety.requiresCurrentMonitoring)}
                    }
                }

            $commands

                override fun safe() {
                    if (!applyNeutral()) outputFaultLatched = ${document.safety.latchOutputFaults}
                }

                override fun recoverWithNeutral(): Boolean {
                    val recovered = applyNeutral()
                    if (recovered) outputFaultLatched = false
                    return recovered
                }

${automaticRecoveryMethods(document)}

                override fun establishCalibration() {
                    if (configurationHealthy) calibrated = true
                }

${homingMethods(document, isFtc = false)}

                private fun applyNeutral(): Boolean {
                    var succeeded = true
            $neutralWrites
                    return succeeded
                }

                override fun logTelemetry(telemetry: com.areslib.telemetry.ITelemetry, prefix: String) {
$telemetry
                }

                override fun close() {
                    if (closed) return
                    closed = true
                    safe()
            $close
                }
            }
        """.trimIndent() + "\n"
    }

    private fun mockIoSource(document: SubsystemDocument, pkg: String): String {
        val measurements = document.hardware.flatMap { it.measurements }.mapNotNull { document.field(it.fieldId) }.distinctBy { it.fieldId }
        val fields = measurements.joinToString("\n") { field ->
            "    override var ${field.fieldId}: ${field.kotlinType()} = ${field.defaultKotlinLiteral()}"
        }
        val commandFields = document.hardware.filter { it.kind.isActuator() }.joinToString("\n") { device ->
            "    var ${device.hardwareId}Command: Double = ${requireNotNull(device.safeOutput).kotlinDouble()}\n        private set"
        }
        val simSignalFields = document.hardware.filter { it.kind.isActuator() }.joinToString("\n") { device ->
            "    private val ${device.hardwareId}SimSignal = com.areslib.simulation.SimAppliedOutputRegistry.register(${document.uid.quoted()}, ${device.hardwareId.quoted()})"
        }
        val linkagePlantFields = if (document.linkage.enabled) {
            val linkage = document.linkage
            """
                /** Fixed deterministic step used by the generated desktop mechanism plant. */
                var simulationStepSeconds: Double = 0.02
                private val linkagePlant = com.areslib.math.kinematics.TwoDofLinkagePlant(
                    com.areslib.math.kinematics.TwoDofLinkagePlantParameters(
                        linkage = com.areslib.math.kinematics.TwoDofLinkageParameters(
                            l1 = ${linkage.link1LengthMeters.kotlinDouble()},
                            l2 = ${linkage.link2LengthMeters.kotlinDouble()},
                            m1 = ${linkage.link1MassKg.kotlinDouble()},
                            m2 = ${linkage.link2MassKg.kotlinDouble()},
                            rc1 = ${linkage.link1CenterOfMassMeters.kotlinDouble()},
                            rc2 = ${linkage.link2CenterOfMassMeters.kotlinDouble()},
                        ),
                        joint1TorquePerVoltNm = ${linkage.joint1TorquePerVoltNm.kotlinDouble()},
                        joint2TorquePerVoltNm = ${linkage.joint2TorquePerVoltNm.kotlinDouble()},
                        joint1ViscousDampingNmPerRadPerSec = ${linkage.joint1DampingNmPerRadPerSec.kotlinDouble()},
                        joint2ViscousDampingNmPerRadPerSec = ${linkage.joint2DampingNmPerRadPerSec.kotlinDouble()},
                        joint1MinimumRad = ${linkage.joint1MinRad.kotlinDouble()},
                        joint1MaximumRad = ${linkage.joint1MaxRad.kotlinDouble()},
                        joint2MinimumRad = ${linkage.joint2MinRad.kotlinDouble()},
                        joint2MaximumRad = ${linkage.joint2MaxRad.kotlinDouble()},
                    ),
                )
            """.trimIndent()
        } else ""
        val linkageRefresh = if (document.linkage.enabled) {
            val linkage = document.linkage
            """
                linkagePlant.step(
                    ${requireNotNull(linkage.joint1ActuatorId)}Command,
                    ${requireNotNull(linkage.joint2ActuatorId)}Command,
                    simulationStepSeconds,
                )
                ${requireNotNull(linkage.joint1AngleFieldId)} = linkagePlant.joint1PositionRad
                ${requireNotNull(linkage.joint2AngleFieldId)} = linkagePlant.joint2PositionRad
            """.trimIndent()
        } else ""
        val commands = document.actuatorLeaders().joinToString("\n\n") { device ->
            val neutral = requireNotNull(device.safeOutput).kotlinDouble()
            val assignments = (listOf(device to "requested") + document.followersOf(device.hardwareId).map { follower ->
                follower to follower.following!!.transformedExpression("requested")
            }).joinToString("\n        ") { (target, expression) ->
                val applied = target.invertedExpression(expression)
                val bounded = when (target.kind) {
                    SubsystemHardwareKind.MOTOR -> "($applied).coerceIn(-12.0, 12.0)"
                    SubsystemHardwareKind.POSITIONAL_SERVO -> "($applied).coerceIn(0.0, 1.0)"
                    SubsystemHardwareKind.CONTINUOUS_SERVO -> "($applied).coerceIn(-1.0, 1.0)"
                    else -> error("Not an actuator")
                }
                "${target.hardwareId}Command = $bounded\n        ${target.hardwareId}SimSignal.publish(${target.hardwareId}Command)"
            }
            """    override fun ${device.commandName()}(value: Double) {
        val requested = value.takeIf(Double::isFinite) ?: $neutral
        if (outputFaultLatched && requested != $neutral) return
        if (requested != $neutral && (!configurationHealthy || !homed || !calibrated ||
                !feedbackValid || !currentReadingValid)) return
        if (failNextWrite) {
            failNextWrite = false
            outputFaultLatched = ${document.safety.latchOutputFaults}
            safe()
            return
        }
        $assignments
    }"""
        }
        val safe = document.hardware.filter { it.kind.isActuator() }.joinToString("\n") {
            val neutral = requireNotNull(it.safeOutput).kotlinDouble()
            "        ${it.hardwareId}Command = ${it.invertedExpression(neutral)}\n        ${it.hardwareId}SimSignal.publish(${it.hardwareId}Command)"
        }
        val currentFields = document.hardware.flatMap { device ->
            device.measurements.filter { it.source == SubsystemMeasurementSource.MOTOR_CURRENT_AMPS }
        }.map { it.fieldId }.distinct()
        val currentValidity = if (document.safety.requiresCurrentMonitoring) {
            currentFields.joinToString(" && ") { "$it.isFinite() && $it >= 0.0" }.ifBlank { "false" }
        } else "true"
        val telemetry = telemetryBody(document)
        val mockHomingCondition = homingConditionExpression(document, "")
        return """
            package $pkg

            import com.areslib.util.RobotClock

            /**
             * Deterministic desktop adapter with hardware-parity fault, freshness, homing,
             * calibration, configuration-health, neutral-recovery, and cleanup controls.
             */
            class Mock${document.kotlinTypeName}IO : ${document.kotlinTypeName}IO {
            $fields
            $commandFields
            $simSignalFields
            ${linkagePlantFields.prependIndent("    ")}
                override var feedbackValid: Boolean = false
                override var feedbackTimestampMs: Long = 0L
                override var configurationHealthy: Boolean = ${(!document.safety.requiresConfigurationHealth)}
                override var homed: Boolean = ${(!document.requiresHoming())}
                override var homingConditionMet: Boolean = false
                override var homingFaultLatched: Boolean = false
                override var calibrated: Boolean = ${(!document.safety.requiresCalibration)}
                override var currentReadingValid: Boolean = ${(!document.safety.requiresCurrentMonitoring)}
                override var outputFaultLatched: Boolean = false
                /** Makes the next snapshot invalid without changing its retained cached values. */
                var failNextRefresh: Boolean = false
                /** Makes the next output/neutral attempt fail and exercise latch behavior. */
                var failNextWrite: Boolean = false
                /** Number of explicit neutral-recovery attempts, including failed writes. */
                var neutralRecoveryAttempts: Int = 0
                    private set
                /** Number of explicit calibration-establishment attempts. */
                var calibrationEstablishmentAttempts: Int = 0
                    private set
                /** Number of fail-closed neutral holds commanded through [safe]. */
                var safeCalls: Int = 0
                    private set
                /** True after idempotent resource cleanup. */
                var closed: Boolean = false
                    private set

                override fun refresh() {
                    if (closed) return
                    if (failNextRefresh) {
                        failNextRefresh = false
                        feedbackValid = false
                        currentReadingValid = ${(!document.safety.requiresCurrentMonitoring)}
                        return
                    }
            ${linkageRefresh.prependIndent("        ")}
                    feedbackTimestampMs = RobotClock.currentTimeMillis()
                    feedbackValid = true
                    currentReadingValid = $currentValidity
                    homingConditionMet = $mockHomingCondition
                }

            $commands

                override fun safe() {
                    safeCalls++
            $safe
                }

                override fun recoverWithNeutral(): Boolean {
                    neutralRecoveryAttempts++
                    if (failNextWrite) {
                        failNextWrite = false
                        outputFaultLatched = ${document.safety.latchOutputFaults}
                        return false
                    }
                    safe()
                    outputFaultLatched = false
                    return true
                }

${automaticRecoveryMethods(document)}

                override fun establishCalibration() {
                    calibrationEstablishmentAttempts++
                    if (configurationHealthy) calibrated = true
                }

${mockHomingMethods(document)}

                override fun logTelemetry(telemetry: com.areslib.telemetry.ITelemetry, prefix: String) {
$telemetry
                }

                override fun close() {
                    if (closed) return
                    safe()
                    closed = true
                }
            }
        """.trimIndent() + "\n"
    }

    private fun automaticRecoveryControllerHelper(document: SubsystemDocument): String {
        val recovery = document.safety.faultRecovery
        if (!recovery.enabled) return ""
        val actuator = document.hardware.single { it.hardwareId == recovery.actuatorId }
        val outputScale = if (actuator.kind == SubsystemHardwareKind.MOTOR) 12.0 else 1.0
        val recoveryOutput = (recovery.reverseDutyCycle * outputScale).kotlinDouble()
        val evidence = "state.${requireNotNull(recovery.currentFieldId)}.toDouble()"
        val action = when (recovery.recoveryAction) {
            FaultRecoveryActionKind.REVERSE_BRIEFLY -> """
        if (automaticRecoveryRetries >= ${recovery.maxRetries}) {
            io.latchOutputFault()
            resetAutomaticRecovery()
            return true
        }
        automaticRecoveryRetries++
        automaticRecoveryStartedAtMs = now
        jamEvidenceSinceMs = Long.MIN_VALUE
        if (!io.commandAutomaticRecovery($recoveryOutput)) {
            io.latchOutputFault()
            resetAutomaticRecovery()
        }
        return true"""
            FaultRecoveryActionKind.NEUTRAL_STOP -> """
        io.latchOutputFault()
        resetAutomaticRecovery()
        return true"""
            FaultRecoveryActionKind.NONE,
            FaultRecoveryActionKind.HOLD_POSITION -> error("Unsupported generated automatic recovery action")
        }
        return """

    private fun handleAutomaticRecovery(
        state: ${document.kotlinTypeName}State,
        scale: Double,
        interlocksPermitted: Boolean,
        now: Long,
    ): Boolean {
        val healthy = interlocksPermitted && scale.isFinite() && scale > 0.0 &&
            state.feedbackValid && state.configurationHealthy && state.homed && state.calibrated &&
            state.currentReadingValid && !state.outputFaultLatched
        if (!healthy) {
            resetAutomaticRecovery()
            return false
        }
        if (automaticRecoveryStartedAtMs != Long.MIN_VALUE) {
            if (now < automaticRecoveryStartedAtMs) {
                io.safe()
                resetAutomaticRecovery()
                return true
            }
            if (now - automaticRecoveryStartedAtMs < ${recovery.reverseDurationMs}L) {
                if (!io.commandAutomaticRecovery($recoveryOutput)) {
                    io.latchOutputFault()
                    resetAutomaticRecovery()
                }
                return true
            }
            io.safe()
            automaticRecoveryStartedAtMs = Long.MIN_VALUE
            jamEvidenceSinceMs = Long.MIN_VALUE
            return true
        }

        val recoveryCurrentAmps = $evidence
        if (!recoveryCurrentAmps.isFinite() || recoveryCurrentAmps < ${recovery.currentThresholdAmps.kotlinDouble()}) {
            resetAutomaticRecovery()
            return false
        }
        if (jamEvidenceSinceMs == Long.MIN_VALUE || now < jamEvidenceSinceMs) {
            jamEvidenceSinceMs = now
            return false
        }
        if (now - jamEvidenceSinceMs < ${recovery.currentDurationMs}L) return false
$action
    }

    private fun resetAutomaticRecovery() {
        jamEvidenceSinceMs = Long.MIN_VALUE
        automaticRecoveryStartedAtMs = Long.MIN_VALUE
        automaticRecoveryRetries = 0
    }
        """.trimEnd().prependIndent("            ")
    }

    private fun automaticRecoveryMethods(document: SubsystemDocument): String {
        val recovery = document.safety.faultRecovery
        val body = if (recovery.enabled) {
            val actuator = document.hardware.single { it.hardwareId == recovery.actuatorId }
            """override fun commandAutomaticRecovery(value: Double): Boolean {
    if (outputFaultLatched || !value.isFinite()) return false
    ${actuator.commandName()}(value)
    return !outputFaultLatched
}

override fun latchOutputFault() {
    outputFaultLatched = true
    safe()
}"""
        } else {
            """override fun commandAutomaticRecovery(value: Double): Boolean = false

override fun latchOutputFault() {
    outputFaultLatched = true
    safe()
}"""
        }
        return body.prependIndent("                ")
    }

    private fun homingConditionExpression(document: SubsystemDocument, prefix: String): String {
        if (!document.requiresHoming()) return "false"
        return document.safety.homing.evidence.joinToString(" && ") { evidence ->
            val value = if (prefix.isEmpty()) evidence.fieldId else "$prefix${evidence.fieldId.pascalCase()}"
            when (evidence.comparison) {
                SubsystemHomingComparison.TRUE -> "$value == true"
                SubsystemHomingComparison.FALSE -> "$value == false"
                SubsystemHomingComparison.AT_OR_ABOVE -> "$value >= ${requireNotNull(evidence.threshold).kotlinDouble()}"
                SubsystemHomingComparison.AT_OR_BELOW -> "$value <= ${requireNotNull(evidence.threshold).kotlinDouble()}"
                SubsystemHomingComparison.ABS_AT_OR_ABOVE -> "kotlin.math.abs($value) >= ${requireNotNull(evidence.threshold).kotlinDouble()}"
                SubsystemHomingComparison.ABS_AT_OR_BELOW -> "kotlin.math.abs($value) <= ${requireNotNull(evidence.threshold).kotlinDouble()}"
            }
        }.ifBlank { "false" }
    }

    private fun homingMethods(document: SubsystemDocument, isFtc: Boolean): String {
        if (!document.requiresHoming()) return """
                override fun commandHoming(): Boolean = false
                override fun establishHome(): Boolean = false
                override fun failHoming() { homingFaultLatched = false }
                override fun cancelHoming(): Boolean = recoverWithNeutral()
        """.trimIndent().prependIndent("                ")
        val homing = document.safety.homing
        val actuator = document.hardware.first { it.hardwareId == homing.actuatorId }
        val searchOutput = requireNotNull(homing.searchOutput).kotlinDouble()
        val write = if (isFtc) {
            "requireNotNull(${actuator.hardwareId}) { ${("Missing ${actuator.displayName}").quoted()} }.power = ($searchOutput / 12.0).coerceIn(-1.0, 1.0)"
        } else {
            "${actuator.hardwareId}.setVoltage($searchOutput)"
        }
        val zero = if (actuator.kind == SubsystemHardwareKind.MOTOR) {
            if (isFtc) {
                "requireNotNull(${actuator.hardwareId}) { ${("Missing ${actuator.displayName}").quoted()} }.mode = com.qualcomm.robotcore.hardware.DcMotor.RunMode.STOP_AND_RESET_ENCODER"
            } else {
                "check(${actuator.hardwareId}.setPosition(${homing.zeroPosition.kotlinDouble()}).isOK) { \"Failed to establish home position\" }"
            }
        } else "// This homing strategy does not reset an encoder."
        return """
                override fun commandHoming(): Boolean {
                    if (!configurationHealthy || !feedbackValid || !currentReadingValid ||
                        outputFaultLatched || homingFaultLatched || closed) return false
                    return try {
                        $write
                        true
                    } catch (_: Exception) {
                        false
                    }
                }

                override fun establishHome(): Boolean {
                    if (!homingConditionMet || !applyNeutral()) return false
                    return try {
                        $zero
                        homed = true
                        homingFaultLatched = false
                        true
                    } catch (_: Exception) {
                        false
                    }
                }

                override fun failHoming() {
                    if (!applyNeutral()) outputFaultLatched = ${document.safety.latchOutputFaults}
                    homingFaultLatched = true
                    homed = false
                }

                override fun cancelHoming(): Boolean {
                    val neutral = recoverWithNeutral()
                    if (neutral) homingFaultLatched = false
                    return neutral
                }
        """.trimIndent().prependIndent("                ")
    }

    private fun mockHomingMethods(document: SubsystemDocument): String {
        if (!document.requiresHoming()) return """
                override fun commandHoming(): Boolean = false
                override fun establishHome(): Boolean = false
                override fun failHoming() { homingFaultLatched = false }
                override fun cancelHoming(): Boolean = recoverWithNeutral()
        """.trimIndent().prependIndent("                ")
        val homing = document.safety.homing
        val actuator = document.hardware.first { it.hardwareId == homing.actuatorId }
        val output = requireNotNull(homing.searchOutput).kotlinDouble()
        return """
                override fun commandHoming(): Boolean {
                    if (!configurationHealthy || !feedbackValid || !currentReadingValid ||
                        outputFaultLatched || homingFaultLatched || closed) return false
                    if (failNextWrite) { failNextWrite = false; return false }
                    ${actuator.hardwareId}Command = $output
                    return true
                }

                override fun establishHome(): Boolean {
                    if (!homingConditionMet || !recoverWithNeutral()) return false
                    homed = true
                    homingFaultLatched = false
                    return true
                }

                override fun failHoming() {
                    safe()
                    homingFaultLatched = true
                    homed = false
                }

                override fun cancelHoming(): Boolean {
                    val neutral = recoverWithNeutral()
                    if (neutral) homingFaultLatched = false
                    return neutral
                }
        """.trimIndent().prependIndent("                ")
    }

    private fun testSource(document: SubsystemDocument, pkg: String): String {
        val firstTarget = document.stateFields.firstOrNull { it.role == SubsystemFieldRole.TARGET }
        val assertion = firstTarget?.let {
            if (it.type == SubsystemValueType.DOUBLE) {
                "assertEquals(${it.defaultKotlinLiteral()}, state.${it.fieldId}, 0.0)"
            } else {
                "assertEquals(${it.defaultKotlinLiteral()}, state.${it.fieldId})"
            }
        } ?: "assertNotNull(state)"
        val imports = when (document.platform) {
            SubsystemPlatform.FTC -> """import org.junit.Assert.assertEquals
            import org.junit.Assert.assertFalse
            import org.junit.Assert.assertNotNull
            import org.junit.Assert.assertTrue
            import org.junit.Test"""
            SubsystemPlatform.FRC -> """import org.junit.jupiter.api.Assertions.assertEquals
            import org.junit.jupiter.api.Assertions.assertFalse
            import org.junit.jupiter.api.Assertions.assertNotNull
            import org.junit.jupiter.api.Assertions.assertTrue
            import org.junit.jupiter.api.Test"""
        }
        val firstActuator = document.actuatorLeaders().firstOrNull()
        val actuatorAssertions = firstActuator?.let { device ->
            val command = "io.${device.commandName()}"
            val observed = "io.${device.hardwareId}Command"
            val neutral = device.invertedExpression(requireNotNull(device.safeOutput).kotlinDouble())
            val validCommand = when (device.kind) {
                SubsystemHardwareKind.MOTOR -> 6.0
                SubsystemHardwareKind.POSITIONAL_SERVO -> 0.75
                SubsystemHardwareKind.CONTINUOUS_SERVO -> 0.5
                else -> error("Not actuator")
            }.kotlinDouble()
            val followerActiveAssertions = document.followersOf(device.hardwareId).joinToString("\n") { follower ->
                val expected = follower.invertedExpression(
                    follower.following!!.transformedExpression(validCommand),
                )
                "        assertEquals($expected, io.${follower.hardwareId}Command, 0.0)"
            }
            val followerNeutralAssertions = document.followersOf(device.hardwareId).joinToString("\n") { follower ->
                val expected = follower.invertedExpression(requireNotNull(follower.safeOutput).kotlinDouble())
                "        assertEquals($expected, io.${follower.hardwareId}Command, 0.0)"
            }
            """
                    $command($validCommand)
                    assertEquals(${device.invertedExpression(validCommand)}, $observed, 0.0)
            $followerActiveAssertions
                    io.failNextWrite = true
                    $command(4.0)
                    assertTrue(io.outputFaultLatched)
                    assertEquals($neutral, $observed, 0.0)
            $followerNeutralAssertions
                    $command(3.0)
                    assertEquals($neutral, $observed, 0.0)
                    assertTrue(io.recoverWithNeutral())
                    assertFalse(io.outputFaultLatched)
            """.trimIndent()
        }.orEmpty()
        val homingAssertions = if (document.requiresHoming()) {
            val evidenceAssignments = document.safety.homing.evidence.joinToString("\n") { evidence ->
                when (evidence.comparison) {
                    SubsystemHomingComparison.TRUE -> "        io.${evidence.fieldId} = true"
                    SubsystemHomingComparison.FALSE -> "        io.${evidence.fieldId} = false"
                    SubsystemHomingComparison.AT_OR_ABOVE,
                    SubsystemHomingComparison.ABS_AT_OR_ABOVE -> "        io.${evidence.fieldId} = ${requireNotNull(evidence.threshold).kotlinDouble()}"
                    SubsystemHomingComparison.AT_OR_BELOW,
                    SubsystemHomingComparison.ABS_AT_OR_BELOW -> "        io.${evidence.fieldId} = 0.0"
                }
            }
            """
                    assertFalse(io.homed)
                    io.configurationHealthy = true
                    io.calibrated = true
                    io.refresh()
                    assertFalse(io.homed)
$evidenceAssignments
                    io.refresh()
                    assertTrue(io.homingConditionMet)
                    assertTrue(io.commandHoming())
                    assertTrue(io.establishHome())
                    assertTrue(io.homed)
            """.trimIndent()
        } else "        assertTrue(io.homed)"
        val currentField = document.hardware.flatMap { it.measurements }
            .firstOrNull { it.source == SubsystemMeasurementSource.MOTOR_CURRENT_AMPS }
            ?.fieldId
        val currentAssertions = if (document.safety.requiresCurrentMonitoring) currentField?.let { fieldId ->
            """
                    io.$fieldId = -1.0
                    io.refresh()
                    assertFalse(io.currentReadingValid)
            """.trimIndent()
        } ?: error("Validated current monitoring requires a current measurement")
        else "        assertTrue(io.currentReadingValid)"
        val homingControllerTest = if (document.requiresHoming()) {
            val evidenceAssignments = document.safety.homing.evidence.joinToString("\n") { evidence ->
                when (evidence.comparison) {
                    SubsystemHomingComparison.TRUE -> "        io.${evidence.fieldId} = true"
                    SubsystemHomingComparison.FALSE -> "        io.${evidence.fieldId} = false"
                    SubsystemHomingComparison.AT_OR_ABOVE,
                    SubsystemHomingComparison.ABS_AT_OR_ABOVE -> "        io.${evidence.fieldId} = ${requireNotNull(evidence.threshold).kotlinDouble()}"
                    SubsystemHomingComparison.AT_OR_BELOW,
                    SubsystemHomingComparison.ABS_AT_OR_BELOW -> "        io.${evidence.fieldId} = 0.0"
                }
            }
            val dwell = document.safety.homing.dwellMs
            """
                @Test
                fun `homing evidence must dwell before home is established`() {
                    val io = Mock${document.kotlinTypeName}IO()
                    val controller = ${document.kotlinTypeName}Controller(io)
                    val state = ${document.kotlinTypeName}State(
                        feedbackValid = true,
                        configurationHealthy = true,
                        homed = false,
                        homingRequested = true,
                        calibrated = true,
                        currentReadingValid = true,
                    )
                    io.configurationHealthy = true
                    io.calibrated = true
            $evidenceAssignments
                    io.refresh()
                    RobotClock.useMockTime(1_000L)
                    try {
                        controller.update(state, 1.0)
                        assertFalse(io.homed)
                        RobotClock.useMockTime(${1_000L + (dwell - 1L).coerceAtLeast(0L)}L)
                        controller.update(state, 1.0)
                        assertFalse(io.homed)
                        RobotClock.useMockTime(${1_000L + dwell}L)
                        controller.update(state, 1.0)
                        assertTrue(io.homed)
                    } finally {
                        RobotClock.useSystemTime()
                    }
                }

            """.trimIndent()
        } else ""
        val firstTargetOverride = firstTarget?.let { field ->
            when (field.type) {
                SubsystemValueType.DOUBLE -> "${field.fieldId} = ${(field.maximum ?: 1.0).kotlinDouble()},"
                SubsystemValueType.INT -> "${field.fieldId} = ${(field.maximum?.toInt() ?: 1)},"
                SubsystemValueType.BOOLEAN -> "${field.fieldId} = true,"
                SubsystemValueType.STRING -> "${field.fieldId} = \"active\","
            }
        }.orEmpty()
        val targetSetterSequenceTest = if (firstTarget != null && document.hasSafetyRequestHandshake()) {
            val value = when (firstTarget.type) {
                SubsystemValueType.DOUBLE -> (firstTarget.maximum ?: 1.0).kotlinDouble()
                SubsystemValueType.INT -> (firstTarget.maximum?.toInt() ?: 1).toString()
                SubsystemValueType.BOOLEAN -> "true"
                SubsystemValueType.STRING -> "\"active\""
            }
            val setter = "set${firstTarget.fieldId.pascalCase()}"
            val registry = "${pkg.substringBeforeLast('.')}.GeneratedSubsystemRegistry"
            val key = subsystemTargetActionKey(document.documentId, firstTarget.fieldId)
            """
                @Test
                fun `direct and registered target actions advance the command sequence`() {
                    val subsystem = ${document.kotlinTypeName}Subsystem(Mock${document.kotlinTypeName}IO())
                    val store = Store(RobotState(superstructure = SuperstructureState(
                        subsystems = mapOf(${document.kotlinTypeName}Subsystem.ID to ${document.kotlinTypeName}State())
                    )))
                    subsystem.$setter(store, $value)
                    assertEquals(1L, ${document.kotlinTypeName}Subsystem.state(store.state).commandSequence)

                    val task = requireNotNull($registry.createActionTask(${key.quoted()}, $value))
                    task.initialize(store.state).forEach { store.dispatch(it) }
                    assertEquals(2L, ${document.kotlinTypeName}Subsystem.state(store.state).commandSequence)
                }

            """.trimIndent()
        } else ""
        val controllerNeutralAssertion = firstActuator?.let { device ->
            "assertEquals(${requireNotNull(device.safeOutput).kotlinDouble()}, io.${device.hardwareId}Command, 0.0)"
        } ?: "assertNotNull(controller)"
        val neutralRecoveryControllerTest = if (document.safety.requiresExplicitNeutralRecovery) {
            """
                @Test
                fun `neutral recovery requests are consumed once and failed neutral stays latched`() {
                    val io = Mock${document.kotlinTypeName}IO()
                    val controller = ${document.kotlinTypeName}Controller(io)
                    io.configurationHealthy = true
                    RobotClock.useMockTime(1_000L)
                    try {
                        io.refresh()
                        val firstRequest = ${document.kotlinTypeName}State(
                            feedbackValid = true,
                            feedbackTimestampMs = 1_000L,
                            configurationHealthy = true,
                            homed = true,
                            calibrated = true,
                            currentReadingValid = true,
                            outputFaultLatched = true,
                            commandSequence = 7L,
                            neutralRecoveryRequestSequence = 1L,
                        )
                        controller.update(firstRequest, 1.0)
                        assertEquals(1, io.neutralRecoveryAttempts)
                        assertFalse(io.outputFaultLatched)
                        val safeCallsAfterRecovery = io.safeCalls
                        controller.update(firstRequest.copy(outputFaultLatched = false), 1.0)
                        assertEquals(1, io.neutralRecoveryAttempts)
                        assertTrue(io.safeCalls > safeCallsAfterRecovery)

                        val safeCallsDuringHold = io.safeCalls
                        controller.update(firstRequest.copy(
                            outputFaultLatched = false,
                            commandSequence = 8L,
                            $firstTargetOverride
                        ), 1.0)
                        assertEquals(safeCallsDuringHold, io.safeCalls)

                        io.outputFaultLatched = true
                        io.failNextWrite = true
                        controller.update(firstRequest.copy(
                            outputFaultLatched = true,
                            commandSequence = 8L,
                            neutralRecoveryRequestSequence = 2L,
                        ), 1.0)
                        assertEquals(2, io.neutralRecoveryAttempts)
                        assertTrue(io.outputFaultLatched)
                        controller.update(firstRequest.copy(
                            outputFaultLatched = true,
                            commandSequence = 8L,
                            neutralRecoveryRequestSequence = 2L,
                        ), 1.0)
                        assertEquals(2, io.neutralRecoveryAttempts)
                        assertTrue(io.outputFaultLatched)
                    } finally {
                        RobotClock.useSystemTime()
                    }
                }

            """.trimIndent()
        } else ""
        val calibrationControllerTest = if (document.safety.requiresCalibration) {
            """
                @Test
                fun `calibration confirmation requires fresh healthy state and successful neutral`() {
                    val io = Mock${document.kotlinTypeName}IO()
                    val controller = ${document.kotlinTypeName}Controller(io)
                    io.configurationHealthy = true
                    RobotClock.useMockTime(1_000L)
                    try {
                        io.refresh()
                        val staleRequest = ${document.kotlinTypeName}State(
                            feedbackValid = false,
                            feedbackTimestampMs = 1_000L,
                            configurationHealthy = true,
                            homed = true,
                            calibrated = false,
                            currentReadingValid = true,
                            commandSequence = 5L,
                            calibrationConfirmationRequestSequence = 1L,
                        )
                        controller.update(staleRequest, 1.0)
                        assertEquals(0, io.calibrationEstablishmentAttempts)
                        controller.update(staleRequest.copy(feedbackValid = true), 1.0)
                        assertEquals(0, io.calibrationEstablishmentAttempts)

                        controller.update(staleRequest.copy(
                            feedbackValid = true,
                            outputFaultLatched = true,
                            calibrationConfirmationRequestSequence = 2L,
                        ), 1.0)
                        assertEquals(0, io.neutralRecoveryAttempts)
                        assertEquals(0, io.calibrationEstablishmentAttempts)

                        io.failNextWrite = true
                        controller.update(staleRequest.copy(
                            feedbackValid = true,
                            calibrationConfirmationRequestSequence = 3L,
                        ), 1.0)
                        assertEquals(1, io.neutralRecoveryAttempts)
                        assertEquals(0, io.calibrationEstablishmentAttempts)
                        assertTrue(io.outputFaultLatched)

                        assertTrue(io.recoverWithNeutral())
                        controller.update(staleRequest.copy(
                            feedbackValid = true,
                            calibrationConfirmationRequestSequence = 4L,
                        ), 1.0)
                        assertEquals(3, io.neutralRecoveryAttempts)
                        assertEquals(1, io.calibrationEstablishmentAttempts)
                        assertTrue(io.calibrated)
                        val safeCallsAfterCalibration = io.safeCalls
                        controller.update(staleRequest.copy(
                            feedbackValid = true,
                            calibrated = true,
                            calibrationConfirmationRequestSequence = 4L,
                        ), 1.0)
                        assertTrue(io.safeCalls > safeCallsAfterCalibration)
                        val safeCallsDuringHold = io.safeCalls
                        controller.update(staleRequest.copy(
                            feedbackValid = true,
                            calibrated = true,
                            commandSequence = 6L,
                            calibrationConfirmationRequestSequence = 4L,
                            $firstTargetOverride
                        ), 1.0)
                        assertEquals(safeCallsDuringHold, io.safeCalls)
                    } finally {
                        RobotClock.useSystemTime()
                    }
                }

            """.trimIndent()
        } else ""
        return """
            package $pkg

            import com.areslib.Store
            import com.areslib.state.RobotState
            import com.areslib.state.SuperstructureState
            import com.areslib.util.RobotClock
            $imports

            class ${document.kotlinTypeName}GeneratedTest {
                @Test
                fun `generated state and mock IO start safely`() {
                    val state = ${document.kotlinTypeName}State()
                    val io = Mock${document.kotlinTypeName}IO()
                    $assertion
                    io.safe()
                    assertFalse(io.outputFaultLatched)
                }

                @Test
                fun `failed writes latch and require explicit neutral recovery`() {
                    val io = Mock${document.kotlinTypeName}IO()
                    io.configurationHealthy = true
                    io.homed = true
                    io.calibrated = true
                    io.refresh()
            $actuatorAssertions
                }

                @Test
                fun `homing and current validity are independent safety permits`() {
                    val io = Mock${document.kotlinTypeName}IO()
            $homingAssertions
            $currentAssertions
                }

            $homingControllerTest

            $neutralRecoveryControllerTest

            $calibrationControllerTest

            $targetSetterSequenceTest

                @Test
                fun `stale feedback is rejected by the immutable state contract`() {
                    val io = Mock${document.kotlinTypeName}IO()
                    val subsystem = ${document.kotlinTypeName}Subsystem(io)
                    val store = Store(RobotState(superstructure = SuperstructureState(
                        subsystems = mapOf(${document.kotlinTypeName}Subsystem.ID to ${document.kotlinTypeName}State())
                    )))
                    io.configurationHealthy = true
                    io.homed = true
                    io.calibrated = true
                    io.refresh()
                    subsystem.readSensors(store, io.feedbackTimestampMs + ${(document.safety.feedbackTimeoutMs ?: 250L) + 1L}L)
                    assertFalse(${document.kotlinTypeName}Subsystem.state(store.state).feedbackValid)
                    val controller = ${document.kotlinTypeName}Controller(io)
                    controller.update(${document.kotlinTypeName}State(
                        feedbackValid = false,
                        configurationHealthy = true,
                        homed = true,
                        calibrated = true,
                        currentReadingValid = true,
                        $firstTargetOverride
                    ), 1.0)
                    $controllerNeutralAssertion
                }

                @Test
                fun `zero scale models disabled and commands neutral`() {
                    val io = Mock${document.kotlinTypeName}IO()
                    io.configurationHealthy = true
                    io.homed = true
                    io.calibrated = true
                    io.refresh()
                    val controller = ${document.kotlinTypeName}Controller(io)
                    controller.update(${document.kotlinTypeName}State(
                        feedbackValid = true,
                        configurationHealthy = true,
                        homed = true,
                        calibrated = true,
                        currentReadingValid = true,
                        $firstTargetOverride
                    ), 0.0)
                    $controllerNeutralAssertion
                }

                @Test
                fun `invalid feedback and cleanup fail closed`() {
                    val io = Mock${document.kotlinTypeName}IO()
                    io.failNextRefresh = true
                    io.refresh()
                    assertFalse(io.feedbackValid)
                    io.close()
                    assertTrue(io.closed)
                    io.close()
                    assertTrue(io.closed)
                }
            }
        """.trimIndent() + "\n"
    }

    private fun telemetryBody(document: SubsystemDocument): String {
        if (!document.safety.telemetryEnabled) return "        // Telemetry is disabled by the subsystem safety document."
        val measurements = document.hardware.flatMap { it.measurements }
            .mapNotNull { document.field(it.fieldId) }
            .distinctBy { it.fieldId }
            .joinToString("\n") { field ->
                when (field.type) {
                    SubsystemValueType.DOUBLE -> "        telemetry.putNumber(\"${'$'}prefix/${field.fieldId}\", ${field.fieldId})"
                    SubsystemValueType.BOOLEAN -> "        telemetry.putBoolean(\"${'$'}prefix/${field.fieldId}\", ${field.fieldId})"
                    SubsystemValueType.INT -> "        telemetry.putNumber(\"${'$'}prefix/${field.fieldId}\", ${field.fieldId}.toDouble())"
                    SubsystemValueType.STRING -> "        telemetry.putString(\"${'$'}prefix/${field.fieldId}\", ${field.fieldId})"
                }
            }
        val safety = """
        telemetry.putBoolean("${'$'}prefix/FeedbackValid", feedbackValid)
        telemetry.putBoolean("${'$'}prefix/ConfigurationHealthy", configurationHealthy)
        telemetry.putBoolean("${'$'}prefix/Homed", homed)
        telemetry.putBoolean("${'$'}prefix/HomingConditionMet", homingConditionMet)
        telemetry.putBoolean("${'$'}prefix/HomingFaultLatched", homingFaultLatched)
        telemetry.putBoolean("${'$'}prefix/Calibrated", calibrated)
        telemetry.putBoolean("${'$'}prefix/CurrentReadingValid", currentReadingValid)
        telemetry.putBoolean("${'$'}prefix/OutputFaultLatched", outputFaultLatched)
        """.trimIndent()
        return listOf(measurements, safety).filter(String::isNotBlank).joinToString("\n")
    }
}

private val PID_STRATEGIES = setOf(
    SubsystemControlStrategy.POSITION_PID,
    SubsystemControlStrategy.VELOCITY_PID,
)

private fun SubsystemDocument.field(id: String?): SubsystemStateFieldDocument? =
    id?.let { requested -> stateFields.firstOrNull { it.fieldId == requested } }

private fun String.sourceFor(document: SubsystemDocument): SubsystemMeasurementSource? =
    document.hardware.asSequence().flatMap { it.measurements.asSequence() }
        .firstOrNull { it.fieldId == this }?.source

private fun SubsystemDocument.requiresHoming(): Boolean =
    safety.homing.method != SubsystemHomingMethod.NONE

private fun SubsystemDocument.hasSafetyRequestHandshake(): Boolean =
    safety.requiresExplicitNeutralRecovery || safety.requiresCalibration

private fun SubsystemHardwareKind.isActuator(): Boolean = this == SubsystemHardwareKind.MOTOR ||
    this == SubsystemHardwareKind.POSITIONAL_SERVO || this == SubsystemHardwareKind.CONTINUOUS_SERVO

private fun SubsystemHardwareDocument.commandName(): String = when (kind) {
    SubsystemHardwareKind.MOTOR -> "set${hardwareId.pascalCase()}Voltage"
    SubsystemHardwareKind.POSITIONAL_SERVO -> "set${hardwareId.pascalCase()}Position"
    SubsystemHardwareKind.CONTINUOUS_SERVO -> "set${hardwareId.pascalCase()}Power"
    else -> error("$kind is not an actuator")
}

private fun SubsystemHardwareDocument.ftcType(): String = when (kind) {
    SubsystemHardwareKind.MOTOR -> "DcMotorEx"
    SubsystemHardwareKind.POSITIONAL_SERVO -> "Servo"
    SubsystemHardwareKind.CONTINUOUS_SERVO -> "CRServo"
    SubsystemHardwareKind.DIGITAL_INPUT -> "DigitalChannel"
    SubsystemHardwareKind.ANALOG_INPUT -> "AnalogInput"
    SubsystemHardwareKind.COLOR_SENSOR -> "ColorSensor"
}

private fun SubsystemHardwareDocument.dslFunction(): String = when (kind) {
    SubsystemHardwareKind.MOTOR -> "motor"
    SubsystemHardwareKind.POSITIONAL_SERVO -> "positionalServo"
    SubsystemHardwareKind.CONTINUOUS_SERVO -> "continuousServo"
    SubsystemHardwareKind.DIGITAL_INPUT -> "digitalInput"
    SubsystemHardwareKind.ANALOG_INPUT -> "analogInput"
    SubsystemHardwareKind.COLOR_SENSOR -> "colorSensor"
}

private fun SubsystemControlLoopDocument.dslFunction(): String = when (strategy) {
    SubsystemControlStrategy.DIRECT -> "direct"
    SubsystemControlStrategy.POSITION_PID -> "positionPid"
    SubsystemControlStrategy.VELOCITY_PID -> "velocityPid"
    SubsystemControlStrategy.BANG_BANG -> "bangBang"
    SubsystemControlStrategy.SERVO_POSITION -> "servoPosition"
}

private fun SubsystemStateFieldDocument.dslFunction(): String = when (type) {
    SubsystemValueType.DOUBLE -> "double"
    SubsystemValueType.BOOLEAN -> "boolean"
    SubsystemValueType.INT -> "int"
    SubsystemValueType.STRING -> "text"
}

private fun SubsystemStateFieldDocument.kotlinType(): String = when (type) {
    SubsystemValueType.DOUBLE -> "Double"
    SubsystemValueType.BOOLEAN -> "Boolean"
    SubsystemValueType.INT -> "Int"
    SubsystemValueType.STRING -> "String"
}

private fun SubsystemStateFieldDocument.defaultKotlinLiteral(): String = when (type) {
    SubsystemValueType.DOUBLE -> requireNotNull(defaultNumber).kotlinDouble()
    SubsystemValueType.BOOLEAN -> requireNotNull(defaultBoolean).toString()
    SubsystemValueType.INT -> requireNotNull(defaultInt).toString()
    SubsystemValueType.STRING -> requireNotNull(defaultText).quoted()
}

private fun SubsystemStateFieldDocument.defaultDslLiteral(): String = defaultKotlinLiteral()

private fun SubsystemStateFieldDocument.optionalStateArguments(): String {
    val arguments = buildList {
        unit?.let { add("unit = ${it.quoted()}") }
        minimum?.let { add("minimum = ${it.kotlinDouble()}") }
        maximum?.let { add("maximum = ${it.kotlinDouble()}") }
    }
    return arguments.joinToString(separator = "\n", prefix = if (arguments.isEmpty()) "" else "\n") {
        "            $it,"
    }
}

private fun SubsystemStateFieldDocument.clampedExpression(expression: String): String {
    val lowerBound = minimum
    val upperBound = maximum
    return when {
        lowerBound != null && upperBound != null ->
            "($expression).coerceIn(${lowerBound.kotlinDouble()}, ${upperBound.kotlinDouble()})"
        lowerBound != null -> "($expression).coerceAtLeast(${lowerBound.kotlinDouble()})"
        upperBound != null -> "($expression).coerceAtMost(${upperBound.kotlinDouble()})"
        else -> expression
    }
}

private fun homingDsl(document: SubsystemDocument): String {
        val homing = document.safety.homing
        val actuator = homing.actuatorId ?: return ""
        fun commonArguments(): String =
            "searchOutput = ${requireNotNull(homing.searchOutput).kotlinDouble()}, dwellMs = ${homing.dwellMs}L, " +
                "timeoutMs = ${homing.timeoutMs}L, zeroPosition = ${homing.zeroPosition.kotlinDouble()}"
        return when (homing.method) {
            SubsystemHomingMethod.NONE -> ""
            SubsystemHomingMethod.DIGITAL_SENSOR -> {
                val evidence = homing.evidence.single()
                val active = evidence.comparison == SubsystemHomingComparison.TRUE
                "        safety.homing.digitalSensor($actuator, ${evidence.fieldId}, ${commonArguments()}, activeWhen = $active)"
            }
            SubsystemHomingMethod.CURRENT_STALL -> {
                val evidence = homing.evidence.single()
                "        safety.homing.currentStall($actuator, ${evidence.fieldId}, ${commonArguments()}, minimumCurrentAmps = ${requireNotNull(evidence.threshold).kotlinDouble()})"
            }
            SubsystemHomingMethod.VELOCITY_STALL -> {
                val evidence = homing.evidence.single()
                "        safety.homing.velocityStall($actuator, ${evidence.fieldId}, ${commonArguments()}, maximumAbsoluteVelocity = ${requireNotNull(evidence.threshold).kotlinDouble()})"
            }
            SubsystemHomingMethod.CURRENT_AND_VELOCITY_STALL -> {
                val current = homing.evidence.first { it.fieldId.sourceFor(document) == SubsystemMeasurementSource.MOTOR_CURRENT_AMPS }
                val velocity = homing.evidence.first { it.fieldId.sourceFor(document) == SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND }
                "        safety.homing.currentAndVelocityStall($actuator, ${current.fieldId}, ${velocity.fieldId}, ${commonArguments()}, " +
                    "minimumCurrentAmps = ${requireNotNull(current.threshold).kotlinDouble()}, " +
                    "maximumAbsoluteVelocity = ${requireNotNull(velocity.threshold).kotlinDouble()})"
            }
            SubsystemHomingMethod.CUSTOM_MEASUREMENT -> {
                val evidence = homing.evidence.joinToString(", ") {
                    "SubsystemHomingEvidenceDocument(${it.fieldId.quoted()}, SubsystemHomingComparison.${it.comparison}, " +
                        (it.threshold?.kotlinDouble() ?: "null") + ")"
                }
                "        safety.homing.custom($actuator, ${commonArguments()}, evidence = listOf($evidence))"
            }
        }
    }

private fun feedforwardExpression(document: SubsystemDocument, loop: SubsystemControlLoopDocument): String {
        val ff = loop.feedforward
        if (ff.kind == SubsystemFeedforwardKind.NONE) return "            val ${loop.loopId}Feedforward = 0.0"
        val velocity = ff.velocityFieldId?.let { "state.$it.toDouble()" }
            ?: if (loop.strategy == SubsystemControlStrategy.VELOCITY_PID) "${loop.loopId}Target" else "0.0"
        val acceleration = ff.accelerationFieldId?.let { "state.$it.toDouble()" } ?: "0.0"
        val gravity = when (ff.kind) {
            SubsystemFeedforwardKind.NONE, SubsystemFeedforwardKind.SIMPLE_MOTOR -> "0.0"
            SubsystemFeedforwardKind.ELEVATOR -> ff.kG.kotlinDouble()
            SubsystemFeedforwardKind.ARM ->
                "${ff.kG.kotlinDouble()} * kotlin.math.cos(state.${requireNotNull(ff.gravityAngleFieldId)}.toDouble())"
            SubsystemFeedforwardKind.TWO_DOF_ARM -> {
                val linkage = document.linkage
                val theta1 = "state.${requireNotNull(linkage.joint1AngleFieldId)}.toDouble()"
                val theta2 = "state.${requireNotNull(linkage.joint2AngleFieldId)}.toDouble()"
                val sharedDistal = "(${linkage.link2MassKg.kotlinDouble()} * ${linkage.link2CenterOfMassMeters.kotlinDouble()} * 9.80665 * kotlin.math.cos($theta1 + $theta2))"
                val torque = if (ff.linkageJoint == 1) {
                    "((${linkage.link1MassKg.kotlinDouble()} * ${linkage.link1CenterOfMassMeters.kotlinDouble()} + ${linkage.link2MassKg.kotlinDouble()} * ${linkage.link1LengthMeters.kotlinDouble()}) * 9.80665 * kotlin.math.cos($theta1) + $sharedDistal)"
                } else {
                    sharedDistal
                }
                "${ff.kG.kotlinDouble()} * $torque"
            }
            SubsystemFeedforwardKind.FOUR_BAR_LINKAGE ->
                error("Generated four-bar feedforward requires a closed-chain model and is intentionally unsupported")
        }
        return """            val ${loop.loopId}DesiredVelocity = $velocity
            val ${loop.loopId}DesiredAcceleration = $acceleration
            val ${loop.loopId}Static = if (${loop.loopId}DesiredVelocity == 0.0) 0.0 else ${ff.kS.kotlinDouble()} * sign(${loop.loopId}DesiredVelocity)
            val ${loop.loopId}Feedforward = ${loop.loopId}Static + ${ff.kV.kotlinDouble()} * ${loop.loopId}DesiredVelocity +
                ${ff.kA.kotlinDouble()} * ${loop.loopId}DesiredAcceleration + $gravity"""
}

private fun SubsystemDocument.actuatorLeaders(): List<SubsystemHardwareDocument> =
    hardware.filter { it.kind.isActuator() && it.following == null }

private fun SubsystemDocument.followersOf(leaderId: String): List<SubsystemHardwareDocument> =
    hardware.filter { it.following?.leaderId == leaderId }

private fun SubsystemFollowerDocument.transformedExpression(requested: String): String = when (transform) {
    SubsystemFollowerTransform.SAME_DIRECTION -> requested
    SubsystemFollowerTransform.INVERTED -> "-($requested)"
    SubsystemFollowerTransform.MIRRORED_POSITION -> "1.0 - ($requested)"
}

/**
 * Converts a logical mechanism command into the direction applied by this physical device.
 * Relationship transforms are evaluated first, then mounting inversion is applied.
 */
private fun SubsystemHardwareDocument.invertedExpression(requested: String): String {
    if (!inverted) return requested
    return when (kind) {
        SubsystemHardwareKind.MOTOR,
        SubsystemHardwareKind.CONTINUOUS_SERVO -> "-($requested)"
        SubsystemHardwareKind.POSITIONAL_SERVO -> "1.0 - ($requested)"
        else -> requested
    }
}

private fun registryActionCase(
    document: SubsystemDocument,
    field: SubsystemStateFieldDocument,
    resourceExpression: String,
): String {
    val key = subsystemTargetActionKey(document.documentId, field.fieldId)
    val numericBounds = buildList {
        field.minimum?.let { add("candidate >= ${it.kotlinDouble()}") }
        field.maximum?.let { add("candidate <= ${it.kotlinDouble()}") }
    }
    val converted = when (field.type) {
        SubsystemValueType.DOUBLE -> {
            val checks = (listOf("candidate.isFinite()") + numericBounds).joinToString(" && ")
            "(value as? Number)?.toDouble()?.takeIf { candidate -> $checks }"
        }
        SubsystemValueType.INT -> {
            val checks = (listOf(
                "candidate.isFinite()",
                "candidate >= Int.MIN_VALUE.toDouble()",
                "candidate <= Int.MAX_VALUE.toDouble()",
                "candidate % 1.0 == 0.0",
            ) + numericBounds).joinToString(" && ")
            "(value as? Number)?.toDouble()?.takeIf { candidate -> $checks }?.toInt()"
        }
        SubsystemValueType.BOOLEAN -> "value as? Boolean"
        SubsystemValueType.STRING -> "value as? String"
    }
    val commandSequence = if (document.hasSafetyRequestHandshake()) {
        """
            val nextCommandSequence = if (current.commandSequence == Long.MAX_VALUE) 1L else current.commandSequence + 1L
            RobotAction.UpdateNamedSubsystemState(
                ${document.kotlinTypeName}Subsystem.ID,
                current.copy(${field.fieldId} = typedValue, commandSequence = nextCommandSequence),
            )
        """.trimIndent()
    } else {
        """
            RobotAction.UpdateNamedSubsystemState(
                ${document.kotlinTypeName}Subsystem.ID,
                current.copy(${field.fieldId} = typedValue),
            )
        """.trimIndent()
    }
    return """    ${key.quoted()} -> $converted?.let { typedValue ->
        StateActionTask(${("Set ${document.displayName} ${field.displayName}").quoted()}, $resourceExpression) { robotState ->
            val current = ${document.kotlinTypeName}Subsystem.state(robotState)
            $commandSequence
        }
    }"""
}

private fun registryHomingActionCase(document: SubsystemDocument, resourceExpression: String): String {
    val key = subsystemTargetActionKey(document.documentId, "homingRequested")
    return """    ${key.quoted()} -> (value as? Boolean)?.let { requested ->
        StateActionTask(${("Run ${document.displayName} homing").quoted()}, $resourceExpression) { robotState ->
            val current = ${document.kotlinTypeName}Subsystem.state(robotState)
            RobotAction.UpdateNamedSubsystemState(
                ${document.kotlinTypeName}Subsystem.ID,
                current.copy(homingRequested = requested),
            )
        }
    }"""
}

private fun registryNeutralRecoveryActionCase(document: SubsystemDocument, resourceExpression: String): String =
    registryOneShotSafetyActionCase(
        key = subsystemNeutralRecoveryActionKey(document.documentId),
        taskName = "Recover ${document.displayName} with neutral",
        document = document,
        sequenceField = "neutralRecoveryRequestSequence",
        resourceExpression = resourceExpression,
    )

private fun registryCalibrationConfirmationActionCase(document: SubsystemDocument, resourceExpression: String): String =
    registryOneShotSafetyActionCase(
        key = subsystemCalibrationConfirmationActionKey(document.documentId),
        taskName = "Confirm ${document.displayName} calibration",
        document = document,
        sequenceField = "calibrationConfirmationRequestSequence",
        resourceExpression = resourceExpression,
    )

private fun registryOneShotSafetyActionCase(
    key: String,
    taskName: String,
    document: SubsystemDocument,
    sequenceField: String,
    resourceExpression: String,
): String = """    ${key.quoted()} -> (value as? Boolean)?.takeIf { it }?.let {
        StateActionTask(${taskName.quoted()}, $resourceExpression) { robotState ->
            val current = ${document.kotlinTypeName}Subsystem.state(robotState)
            val nextSequence = if (current.$sequenceField == Long.MAX_VALUE) 1L else current.$sequenceField + 1L
            RobotAction.UpdateNamedSubsystemState(
                ${document.kotlinTypeName}Subsystem.ID,
                current.copy($sequenceField = nextSequence),
            )
        }
    }"""

private fun String.pascalCase(): String = split(Regex("[^A-Za-z0-9]+"))
    .filter(String::isNotEmpty)
    .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

private fun String.quoted(): String = buildString {
    append('"')
    this@quoted.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
    append('"')
}

private fun Double.kotlinDouble(): String = when {
    this == -0.0 -> "0.0"
    toString().contains('.') || toString().contains('e', ignoreCase = true) -> toString()
    else -> "${this}.0"
}

private fun platformPrefix(platform: SubsystemPlatform): String = when (platform) {
    SubsystemPlatform.FTC -> "Ftc"
    SubsystemPlatform.FRC -> "Frc"
}
