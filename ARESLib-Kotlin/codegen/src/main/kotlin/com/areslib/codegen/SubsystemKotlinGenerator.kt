package com.areslib.codegen

import com.areslib.subsystem.SubsystemControlLoopDocument
import com.areslib.subsystem.SubsystemControlStrategy
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemFeedforwardKind
import com.areslib.subsystem.SubsystemFollowerTransform
import com.areslib.subsystem.SubsystemHardwareDocument
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemHomingComparison
import com.areslib.subsystem.SubsystemHomingMethod
import com.areslib.subsystem.SubsystemImplementationKind
import com.areslib.subsystem.InterlockComparison
import com.areslib.subsystem.isAresGenerated
import com.areslib.subsystem.SubsystemMeasurementSource
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemStateFieldDocument
import com.areslib.subsystem.SubsystemValueType
import com.areslib.subsystem.subsystemCalibrationConfirmationActionKey
import com.areslib.subsystem.subsystemNeutralRecoveryActionKey
import com.areslib.subsystem.subsystemTargetActionKey
import com.areslib.subsystem.SubsystemSchema

/** Deterministic Kotlin source generator shared by Gradle, Analytics preview, and tests. */
object SubsystemKotlinGenerator {
    fun generate(document: SubsystemDocument, target: SubsystemKotlinCodegenTarget): List<GeneratedSubsystemFile> {
        require(target.platform != SubsystemPlatform.XRP) {
            "XRP projects use the standalone MicroPython generator, not Kotlin subsystem codegen"
        }
        val issues = SubsystemSchema.validate(document)
        require(issues.isEmpty()) { issues.joinToString("; ") { "${it.path}: ${it.message}" } }
        require(document.implementation.kind.isAresGenerated()) {
            "Subsystem '${document.documentId}' is hand-authored USER-OWNED source; ARES will not generate or replace its Kotlin implementation"
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
            implementationSource(document, "$directory/${document.kotlinTypeName}State.kt", SubsystemContractRenderer.stateSource(document, pkg), SubsystemArtifact.STATE,
                SubsystemArtifactGroup.DOMAIN, "Immutable Redux state and safety observations owned by the subsystem."),
            implementationSource(document, "$directory/${document.kotlinTypeName}IO.kt", SubsystemContractRenderer.ioSource(document, pkg), SubsystemArtifact.IO_CONTRACT,
                SubsystemArtifactGroup.HARDWARE, "Cached, fail-closed boundary shared by physical and simulated adapters."),
            implementationSource(document, "$directory/${document.kotlinTypeName}Controller.kt", SubsystemControllerRenderer.render(document, pkg), SubsystemArtifact.CONTROLLER,
                SubsystemArtifactGroup.CONTROL, "Allocation-free policy that converts immutable state into safe IO commands."),
            implementationSource(document, "$directory/${document.kotlinTypeName}Subsystem.kt", SubsystemLifecycleRenderer.render(document, pkg), SubsystemArtifact.SUBSYSTEM_LIFECYCLE,
                SubsystemArtifactGroup.CONTROL, "Lifecycle bridge that separates cached reads, Redux updates, and output writes."),
            implementationSource(document, "$directory/${platformPrefix(document.platform)}${document.kotlinTypeName}IO.kt", hardwareIoSource(document, pkg),
                SubsystemArtifact.PLATFORM_IO, SubsystemArtifactGroup.HARDWARE,
                "Platform adapter that owns devices, cached reads, configuration, and output faults."),
        )
        if (document.generateMockIo) {
            files += implementationSource(document, "$directory/Mock${document.kotlinTypeName}IO.kt", SubsystemMockIoRenderer.render(document, pkg),
                SubsystemArtifact.MOCK_IO, SubsystemArtifactGroup.SIMULATION,
                "Deterministic simulator adapter with the same safety and recovery semantics as hardware.")
        }
        if (document.generateTest) {
            files += generated(
                "$directory/${document.kotlinTypeName}GeneratedTest.kt",
                SubsystemGeneratedTestRenderer.render(document, pkg),
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
        require(target.platform != SubsystemPlatform.XRP) {
            "XRP projects use the standalone MicroPython generator, not Kotlin subsystem codegen"
        }
        val source = SubsystemRegistryRenderer.render(documents, target)
        return generated(
            "GeneratedSubsystemRegistry.kt",
            source,
            SubsystemArtifact.REGISTRY,
            SubsystemArtifactGroup.GENERATED_PLUMBING,
            "Mechanical composition for generated starters, with explicit hand-authored registration reminders.",
        )
    }

    internal fun registryInterlockFunction(
        owner: SubsystemDocument,
        documents: List<SubsystemDocument>,
    ): String {
        val checks = owner.interlocks.mapIndexed { index, interlock ->
            val target = documents.single { it.uid == interlock.targetSubsystemUid }
            val field = target.stateFields.single { it.fieldId == interlock.targetFieldId }
            val snapshot = "interlockState$index"
            val numericValue = field.numericExpression(snapshot)
            val lockoutCondition = when (interlock.comparison) {
                InterlockComparison.LESS_THAN -> "$numericValue < ${interlock.thresholdValue.kotlinDouble()}"
                InterlockComparison.GREATER_THAN -> "$numericValue > ${interlock.thresholdValue.kotlinDouble()}"
                InterlockComparison.EQUALS_STATE -> when (field.type) {
                    SubsystemValueType.DOUBLE,
                    SubsystemValueType.INT ->
                        "kotlin.math.abs($numericValue - ${interlock.thresholdValue.kotlinDouble()}) <= 1e-9"
                    SubsystemValueType.BOOLEAN ->
                        "$snapshot.${field.fieldId} == ${interlock.targetStateName!!.lowercase()}"
                    SubsystemValueType.STRING ->
                        "$snapshot.${field.fieldId} == ${interlock.targetStateName!!.quoted()}"
                }
                InterlockComparison.NOT_EQUALS_STATE -> when (field.type) {
                    SubsystemValueType.DOUBLE,
                    SubsystemValueType.INT ->
                        "kotlin.math.abs($numericValue - ${interlock.thresholdValue.kotlinDouble()}) > 1e-9"
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

    private fun implementationSource(
        document: SubsystemDocument,
        path: String,
        source: String,
        artifact: SubsystemArtifact,
        group: SubsystemArtifactGroup,
        description: String,
    ): GeneratedSubsystemFile = when (document.implementation.kind) {
        SubsystemImplementationKind.DECLARATIVE_GENERATED ->
            generated(path, source, artifact, group, description)
        SubsystemImplementationKind.GENERATED_STARTER ->
            starter(path, source, artifact, group, description)
        SubsystemImplementationKind.HAND_AUTHORED ->
            error("Hand-authored subsystem '${document.documentId}' cannot emit generated implementation source")
    }

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
                device.connection.secondaryChannel?.let { add("secondaryChannel = $it") }
                device.connection.pneumaticsModuleType?.let {
                    add("pneumaticsModuleType = com.areslib.subsystem.SubsystemPneumaticsModuleType.$it")
                }
                if (!device.required) add("required = false")
                if (device.inverted) add("inverted = true")
                device.currentLimitAmps?.let { add("currentLimitAmps = ${it.kotlinDouble()}") }
                device.safeOutput?.let { add("safeOutput = ${it.kotlinDouble()}") }
                device.encoderCountsPerRevolution?.let { add("encoderCountsPerRevolution = ${it.kotlinDouble()}") }
                device.distanceMetersPerVolt?.let { add("distanceMetersPerVolt = ${it.kotlinDouble()}") }
                device.imuLogoFacingDirection?.let {
                    add("imuLogoFacingDirection = com.areslib.subsystem.SubsystemHubFacingDirection.$it")
                }
                device.imuUsbFacingDirection?.let {
                    add("imuUsbFacingDirection = com.areslib.subsystem.SubsystemHubFacingDirection.$it")
                }
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
                if (loop.strategy == SubsystemControlStrategy.PROFILED_POSITION_PID) {
                    add("maximumVelocity = ${loop.motionProfile.maximumVelocity.kotlinDouble()}")
                    add("maximumAcceleration = ${loop.motionProfile.maximumAcceleration.kotlinDouble()}")
                }
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
                if (loop.continuousInput.enabled) {
                    add("continuousInputEnabled = true")
                    add("continuousInputMinimum = ${loop.continuousInput.minimumInput.kotlinDouble()}")
                    add("continuousInputMaximum = ${loop.continuousInput.maximumInput.kotlinDouble()}")
                }
                if (loop.tolerance != 0.0) add("tolerance = ${loop.tolerance.kotlinDouble()}")
                if (loop.hysteresis != 0.0) add("hysteresis = ${loop.hysteresis.kotlinDouble()}")
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


    private fun hardwareIoSource(document: SubsystemDocument, pkg: String): String = when (document.platform) {
        SubsystemPlatform.FTC -> SubsystemFtcIoRenderer.render(document, pkg)
        SubsystemPlatform.FRC -> SubsystemFrcIoRenderer.render(document, pkg)
        SubsystemPlatform.XRP -> error("XRP hardware adapters are generated as MicroPython")
    }




}


internal fun SubsystemDocument.field(id: String?): SubsystemStateFieldDocument? =
    id?.let { requested -> stateFields.firstOrNull { it.fieldId == requested } }

private fun String.sourceFor(document: SubsystemDocument): SubsystemMeasurementSource? =
    document.hardware.asSequence().flatMap { it.measurements.asSequence() }
        .firstOrNull { it.fieldId == this }?.source

internal fun SubsystemDocument.requiresHoming(): Boolean =
    safety.homing.method != SubsystemHomingMethod.NONE

internal fun SubsystemDocument.hasSafetyRequestHandshake(): Boolean =
    safety.requiresExplicitNeutralRecovery || safety.requiresCalibration




private fun SubsystemHardwareDocument.dslFunction(): String = when (kind) {
    SubsystemHardwareKind.MOTOR -> "motor"
    SubsystemHardwareKind.POSITIONAL_SERVO -> "positionalServo"
    SubsystemHardwareKind.CONTINUOUS_SERVO -> "continuousServo"
    SubsystemHardwareKind.ABSOLUTE_ENCODER -> "absoluteEncoder"
    SubsystemHardwareKind.QUADRATURE_ENCODER -> "quadratureEncoder"
    SubsystemHardwareKind.DIGITAL_INPUT -> "digitalInput"
    SubsystemHardwareKind.DIGITAL_OUTPUT -> "digitalOutput"
    SubsystemHardwareKind.ANALOG_INPUT -> "analogInput"
    SubsystemHardwareKind.PWM_OUTPUT -> "pwmOutput"
    SubsystemHardwareKind.DISTANCE_SENSOR -> "distanceSensor"
    SubsystemHardwareKind.IMU -> "imu"
    SubsystemHardwareKind.COLOR_SENSOR -> "colorSensor"
    SubsystemHardwareKind.SOLENOID -> "solenoid"
    SubsystemHardwareKind.INDICATOR_LIGHT -> "indicatorLight"
    SubsystemHardwareKind.BUZZER -> "buzzer"
    SubsystemHardwareKind.PRISM_DRIVER -> "prismDriver"
}

private fun SubsystemControlLoopDocument.dslFunction(): String = when (strategy) {
    SubsystemControlStrategy.DIRECT -> "direct"
    SubsystemControlStrategy.POSITION_PID -> "positionPid"
    SubsystemControlStrategy.PROFILED_POSITION_PID -> "profiledPositionPid"
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

internal fun SubsystemStateFieldDocument.kotlinType(): String = when (type) {
    SubsystemValueType.DOUBLE -> "Double"
    SubsystemValueType.BOOLEAN -> "Boolean"
    SubsystemValueType.INT -> "Int"
    SubsystemValueType.STRING -> "String"
}

internal fun SubsystemStateFieldDocument.defaultKotlinLiteral(): String = when (type) {
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


/** Emits the narrowest numeric expression allowed by the descriptor's declared state type. */
internal fun SubsystemStateFieldDocument.numericExpression(receiver: String): String = when (type) {
    SubsystemValueType.DOUBLE -> "$receiver.$fieldId"
    SubsystemValueType.INT -> "$receiver.$fieldId.toDouble()"
    SubsystemValueType.BOOLEAN,
    SubsystemValueType.STRING -> error("State field '$fieldId' is not numeric")
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




internal fun registryActionCase(
    document: SubsystemDocument,
    field: SubsystemStateFieldDocument,
    resourceExpression: String,
): String {
    val key = subsystemTargetActionKey(document.documentId, field.fieldId)
    val actuatorKind = document.controlLoops
        .firstOrNull { it.targetFieldId == field.fieldId }
        ?.let { loop -> document.hardware.firstOrNull { it.hardwareId == loop.actuatorId } }
        ?.kind
    val fieldMinimum = field.minimum
    val fieldMaximum = field.maximum
    val numericBounds = buildList {
        field.minimum?.let { add("candidate >= ${it.kotlinDouble()}") }
        field.maximum?.let { add("candidate <= ${it.kotlinDouble()}") }
    }
    val namedConversion = when (actuatorKind) {
        SubsystemHardwareKind.INDICATOR_LIGHT -> com.areslib.hardware.actuator.IndicatorLightColor.entries
            .filter { it != com.areslib.hardware.actuator.IndicatorLightColor.RAINBOW }
            .filter { option -> fieldMinimum == null || option.position >= fieldMinimum }
            .filter { option -> fieldMaximum == null || option.position <= fieldMaximum }
            .joinToString(
                prefix = "when (value as? String) {\n",
                postfix = "\n        else -> null\n    }",
                separator = "\n",
            ) { option -> "        ${option.name.quoted()} -> ${option.position.kotlinDouble()}" }
        SubsystemHardwareKind.PRISM_DRIVER -> com.areslib.hardware.actuator.PrismPwmPreset.entries
            .filter { option -> fieldMinimum == null || option.pulseWidthUs >= fieldMinimum }
            .filter { option -> fieldMaximum == null || option.pulseWidthUs <= fieldMaximum }
            .joinToString(
                prefix = "when (value as? String) {\n",
                postfix = "\n        else -> null\n    }",
                separator = "\n",
            ) { option ->
                val value = if (field.type == SubsystemValueType.INT) option.pulseWidthUs.toString()
                else option.pulseWidthUs.toDouble().kotlinDouble()
                "        ${option.name.quoted()} -> $value"
            }
        else -> null
    }
    val converted = namedConversion ?: when (field.type) {
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
        StateActionTask(${("Set ${field.displayName}").quoted()}, $resourceExpression) { robotState ->
            val current = ${document.kotlinTypeName}Subsystem.state(robotState)
            $commandSequence
        }
    }"""
}

internal fun registryIndicatorCycleActionCase(
    document: SubsystemDocument,
    field: SubsystemStateFieldDocument,
    resourceExpression: String,
    forward: Boolean,
): String {
    require(field.type == SubsystemValueType.DOUBLE) {
        "Indicator-light cycle target '${document.documentId}.${field.fieldId}' must be a Double"
    }
    val fieldMinimum = field.minimum
    val fieldMaximum = field.maximum
    val positions = com.areslib.hardware.actuator.IndicatorLightColor.entries
        .asSequence()
        .filter {
            it != com.areslib.hardware.actuator.IndicatorLightColor.OFF &&
                it != com.areslib.hardware.actuator.IndicatorLightColor.RAINBOW
        }
        .filter { option -> fieldMinimum == null || option.position >= fieldMinimum }
        .filter { option -> fieldMaximum == null || option.position <= fieldMaximum }
        .distinctBy { it.position }
        .map { it.position }
        .toList()
    require(positions.isNotEmpty()) {
        "Indicator-light cycle target '${document.documentId}.${field.fieldId}' has no visible colors inside its bounds"
    }
    val traversal = if (forward) positions else positions.asReversed()
    val wrap = if (forward) positions.first() else positions.last()
    val comparison = if (forward) "<" else ">"
    val nextValue = traversal.joinToString(
        prefix = "when {\n",
        postfix = "\n                else -> ${wrap.kotlinDouble()}\n            }",
        separator = "\n",
    ) { position ->
        "                current.${field.fieldId} $comparison ${position.kotlinDouble()} -> ${position.kotlinDouble()}"
    }
    val stateUpdate = if (document.hasSafetyRequestHandshake()) {
        """val nextCommandSequence = if (current.commandSequence == Long.MAX_VALUE) 1L else current.commandSequence + 1L
            RobotAction.UpdateNamedSubsystemState(
                ${document.kotlinTypeName}Subsystem.ID,
                current.copy(${field.fieldId} = nextValue, commandSequence = nextCommandSequence),
            )"""
    } else {
        """RobotAction.UpdateNamedSubsystemState(
                ${document.kotlinTypeName}Subsystem.ID,
                current.copy(${field.fieldId} = nextValue),
            )"""
    }
    val key = if (forward) {
        com.areslib.subsystem.subsystemIndicatorCycleForwardActionKey(document.documentId, field.fieldId)
    } else {
        com.areslib.subsystem.subsystemIndicatorCycleBackwardActionKey(document.documentId, field.fieldId)
    }
    val direction = if (forward) "forward" else "backward"
    return """    ${key.quoted()} -> StateActionTask(${("Cycle ${field.displayName} $direction").quoted()}, $resourceExpression) { robotState ->
            val current = ${document.kotlinTypeName}Subsystem.state(robotState)
            val nextValue = $nextValue
            $stateUpdate
        }"""
}

internal fun registryHomingActionCase(document: SubsystemDocument, resourceExpression: String): String {
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

internal fun registryNeutralRecoveryActionCase(document: SubsystemDocument, resourceExpression: String): String =
    registryOneShotSafetyActionCase(
        key = subsystemNeutralRecoveryActionKey(document.documentId),
        taskName = "Recover ${document.displayName} with neutral",
        document = document,
        sequenceField = "neutralRecoveryRequestSequence",
        resourceExpression = resourceExpression,
    )

internal fun registryCalibrationConfirmationActionCase(document: SubsystemDocument, resourceExpression: String): String =
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

internal fun platformPrefix(platform: SubsystemPlatform): String = when (platform) {
    SubsystemPlatform.FTC -> "Ftc"
    SubsystemPlatform.FRC -> "Frc"
    SubsystemPlatform.XRP -> "Xrp"
}
