package com.areslib.codegen

import com.areslib.subsystem.SubsystemControlLoopDocument
import com.areslib.subsystem.SubsystemControlStrategy
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemHardwareDocument
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemMeasurementSource
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemStateFieldDocument
import com.areslib.subsystem.SubsystemValueType
import com.areslib.subsystem.subsystemTargetActionKey
import com.areslib.subsystem.validateSubsystemDocument

enum class GeneratedSubsystemSourceSet { MAIN, TEST }

data class GeneratedSubsystemFile(
    val relativePath: String,
    val content: String,
    val sourceSet: GeneratedSubsystemSourceSet = GeneratedSubsystemSourceSet.MAIN,
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
            GeneratedSubsystemFile("$directory/${document.name}Definition.kt", definitionSource(document, pkg)),
            GeneratedSubsystemFile("$directory/${document.name}State.kt", stateSource(document, pkg)),
            GeneratedSubsystemFile("$directory/${document.name}IO.kt", ioSource(document, pkg)),
            GeneratedSubsystemFile("$directory/${document.name}Controller.kt", controllerSource(document, pkg)),
            GeneratedSubsystemFile("$directory/${document.name}Subsystem.kt", subsystemSource(document, pkg)),
            GeneratedSubsystemFile("$directory/${platformPrefix(document.platform)}${document.name}IO.kt", hardwareIoSource(document, pkg)),
        )
        if (document.generateMockIo) {
            files += GeneratedSubsystemFile("$directory/Mock${document.name}IO.kt", mockIoSource(document, pkg))
        }
        if (document.generateTest) {
            files += GeneratedSubsystemFile(
                "$directory/${document.name}GeneratedTest.kt",
                testSource(document, pkg),
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
        documents.forEach { document ->
            require(document.platform == target.platform) {
                "Subsystem '${document.documentId}' targets ${document.platform}, not ${target.platform}"
            }
            require(validateSubsystemDocument(document).isEmpty()) { "Subsystem '${document.documentId}' is invalid" }
        }
        val imports = documents.sortedBy { it.documentId }.flatMap { document ->
            val segment = document.documentId.replace('-', '_')
            val pkg = "${target.basePackage}.$segment"
            buildList {
                add("$pkg.${document.name}Subsystem")
                add("$pkg.${platformPrefix(document.platform)}${document.name}IO")
                if (document.generateMockIo) add("$pkg.Mock${document.name}IO")
            }
        }.distinct().sorted()
        val factories = documents.sortedBy { it.documentId }.joinToString("\n") { document ->
            val factory = when (target.platform) {
                SubsystemPlatform.FTC ->
                    "${document.name}Subsystem(Ftc${document.name}IO(hardwareMap))"
                SubsystemPlatform.FRC -> if (document.generateMockIo) {
                    "${document.name}Subsystem(if (isReal) Frc${document.name}IO() else Mock${document.name}IO())"
                } else {
                    "${document.name}Subsystem(Frc${document.name}IO()).takeIf { isReal }"
                }
            }
            "    install(${document.documentId.quoted()}, ${document.requiredAtStartup}) { $factory }"
        }
        val actionCases = documents.sortedBy { it.documentId }.flatMap { document ->
            document.stateFields
                .filter { it.role == SubsystemFieldRole.TARGET }
                .sortedBy { it.fieldId }
                .map { field -> registryActionCase(document, field) }
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
        val body = if (documents.isEmpty()) {
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
        val installHelper = if (documents.isEmpty()) "" else """

private inline fun MutableList<Subsystem>.install(
    documentId: String,
    required: Boolean,
    factory: () -> Subsystem?,
) {
    try {
        factory()?.let(::add)
    } catch (error: Exception) {
        if (required) {
            throw IllegalStateException("Required generated subsystem '${'$'}documentId' failed to initialize", error)
        }
        System.err.println("Optional generated subsystem '${'$'}documentId' was skipped: ${'$'}{error.message}")
    }
}
""".trimEnd()
        val source = buildString {
            append("package ${target.basePackage}\n\n")
            if (actionCases.isNotBlank()) {
                append("import com.areslib.action.RobotAction\n")
                append("import com.areslib.sequencer.StateActionTask\n")
            }
            append("import com.areslib.sequencer.Task\n")
            append("import com.areslib.subsystem.Subsystem\n")
            if (target.platform == SubsystemPlatform.FTC) {
                append("import com.qualcomm.robotcore.hardware.HardwareMap\n")
            }
            imports.forEach { append("import $it\n") }
            append("\n/** Generated composition root. The season shell registers every returned subsystem. */\n")
            append("object GeneratedSubsystemRegistry {\n")
            append(body.prependIndent("    "))
            append("\n\n")
            append(actionFactory.prependIndent("    "))
            append("\n}\n")
            append(installHelper)
            if (installHelper.isNotEmpty()) append('\n')
        }
        return GeneratedSubsystemFile("GeneratedSubsystemRegistry.kt", source)
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
        val hardwareLines = document.hardware.joinToString("\n") { device ->
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
                device.measurementFieldId?.let { fieldId ->
                    val arguments = buildList {
                        add(fieldId)
                        add("SubsystemMeasurementSource.${requireNotNull(device.measurementSource)}")
                        if (device.measurementScale != 1.0) add("scale = ${device.measurementScale.kotlinDouble()}")
                        if (device.measurementOffset != 0.0) add("offset = ${device.measurementOffset.kotlinDouble()}")
                    }
                    add("measurement(${arguments.joinToString()})")
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
                if (loop.kS != 0.0) add("kS = ${loop.kS.kotlinDouble()}")
                if (loop.kV != 0.0) add("kV = ${loop.kV.kotlinDouble()}")
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
        val hash = SubsystemDocumentCodec.contentHash(document)
        return """
            package $pkg

            import com.areslib.subsystem.SubsystemFieldRole
            import com.areslib.subsystem.SubsystemMeasurementSource
            import com.areslib.subsystem.SubsystemPlatform
            import com.areslib.subsystem.subsystem

            /** Generated from `.ares/subsystems/${document.documentId}.aressubsystem`; safe to read and learn from. */
            object ${document.name}Definition {
                const val CONTENT_SHA256: String = "$hash"

                val document = subsystem(${document.documentId.quoted()}, ${document.name.quoted()}, SubsystemPlatform.${document.platform}) {
            $descriptionLine$requiredLine$mockLine$testLine$fieldLines

            $hardwareLines

            $controlLines
                }
            }
        """.trimIndent() + "\n"
    }

    private fun stateSource(document: SubsystemDocument, pkg: String): String {
        val fields = document.stateFields.joinToString(",\n") { field ->
            "    val ${field.fieldId}: ${field.kotlinType()} = ${field.defaultKotlinLiteral()}"
        }
        return """
            package $pkg

            import com.areslib.state.SubsystemState

            /** Immutable state owned by the ${document.name} subsystem. */
            data class ${document.name}State(
            $fields
            ) : SubsystemState
        """.trimIndent() + "\n"
    }

    private fun ioSource(document: SubsystemDocument, pkg: String): String {
        val measurements = document.hardware.mapNotNull { device ->
            val field = document.field(device.measurementFieldId) ?: return@mapNotNull null
            "    val ${field.fieldId}: ${field.kotlinType()}"
        }.distinct()
        val commands = document.hardware.filter { it.kind.isActuator() }.map { device ->
            "    fun ${device.commandName()}(value: Double)"
        }
        val members = (measurements + commands).joinToString("\n")
        return """
            package $pkg

            import com.areslib.hardware.SubsystemIO

            /** Cached hardware boundary. Getters never perform direct device reads. */
            interface ${document.name}IO : SubsystemIO, AutoCloseable {
            $members
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
        return """
            package $pkg

            import com.areslib.util.RobotClock
            import kotlin.math.abs
            import kotlin.math.sign

            /** Allocation-free controller generated from the visual/hand-authored subsystem DSL. */
            class ${document.name}Controller(private val io: ${document.name}IO) {
                private var lastTimestampMs = 0L
            $stateFields

                fun update(state: ${document.name}State, scale: Double) {
                    if (!scale.isFinite() || scale <= 0.0) {
                        reset()
                        io.safe()
                        return
                    }
                    val now = RobotClock.currentTimeMillis()
                    val dtSeconds = if (lastTimestampMs == 0L) 0.02 else ((now - lastTimestampMs) / 1000.0).coerceIn(0.001, 0.1)
                    lastTimestampMs = now

            $loopBodies
                }

                fun reset() {
                    lastTimestampMs = 0L
            $reset
                }
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
            val ${loop.loopId}Feedforward = if (${loop.loopId}Target == 0.0) 0.0 else ${loop.kS.kotlinDouble()} * sign(${loop.loopId}Target) + ${loop.kV.kotlinDouble()} * ${loop.loopId}Target
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
        val copies = document.hardware.mapNotNull { device ->
            val field = document.field(device.measurementFieldId) ?: return@mapNotNull null
            "            ${field.fieldId} = io.${field.fieldId}"
        }.distinct().joinToString(",\n")
        val setters = document.stateFields.filter { it.role == SubsystemFieldRole.TARGET }.joinToString("\n\n") { field ->
            val cap = field.fieldId.pascalCase()
            """    fun set$cap(store: Store, value: ${field.kotlinType()}) {
        val current = state(store.state)
        store.dispatch(RobotAction.UpdateNamedSubsystemState(ID, current.copy(${field.fieldId} = value)))
    }"""
        }
        return """
            package $pkg

            import com.areslib.Store
            import com.areslib.action.RobotAction
            import com.areslib.state.RobotState
            import com.areslib.subsystem.Subsystem

            /** Robot-loop host. Hardware reads and output writes remain separated and cached. */
            class ${document.name}Subsystem(private val io: ${document.name}IO) : Subsystem {
                private val controller = ${document.name}Controller(io)

                override fun readSensors(store: Store, timestampMs: Long) {
                    io.refresh()
                    val updated = state(store.state).copy(
            $copies
                    )
                    store.dispatch(RobotAction.UpdateNamedSubsystemState(ID, updated, timestampMs))
                }

                override fun writeOutputs(state: RobotState, scale: Double) {
                    controller.update(state(state), scale)
                }

            $setters

                override fun close() {
                    controller.reset()
                    io.safe()
                    io.close()
                }

                companion object {
                    const val ID: String = ${document.documentId.quoted()}

                    fun state(robotState: RobotState): ${document.name}State =
                        robotState.superstructure.subsystems[ID] as? ${document.name}State ?: ${document.name}State()
                }
            }
        """.trimIndent() + "\n"
    }

    private fun hardwareIoSource(document: SubsystemDocument, pkg: String): String = when (document.platform) {
        SubsystemPlatform.FTC -> ftcIoSource(document, pkg)
        SubsystemPlatform.FRC -> frcIoSource(document, pkg)
    }

    private fun ftcIoSource(document: SubsystemDocument, pkg: String): String {
        val imports = linkedSetOf("com.qualcomm.robotcore.hardware.HardwareMap")
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
        if (document.hardware.any { it.kind == SubsystemHardwareKind.MOTOR && it.inverted }) {
            imports += "com.qualcomm.robotcore.hardware.DcMotorSimple"
        }
        if (document.hardware.any { it.measurementSource == SubsystemMeasurementSource.MOTOR_CURRENT_AMPS }) {
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
        val cached = document.hardware.mapNotNull { device ->
            val field = document.field(device.measurementFieldId) ?: return@mapNotNull null
            "    private var cached${field.fieldId.pascalCase()}: ${field.kotlinType()} = ${field.defaultKotlinLiteral()}\n" +
                "    override val ${field.fieldId}: ${field.kotlinType()} get() = cached${field.fieldId.pascalCase()}"
        }.distinct().joinToString("\n")
        val init = document.hardware.mapNotNull { device ->
            when {
                device.kind == SubsystemHardwareKind.MOTOR && device.inverted ->
                    "        ${device.hardwareId}?.direction = DcMotorSimple.Direction.REVERSE"
                device.kind == SubsystemHardwareKind.DIGITAL_INPUT ->
                    "        ${device.hardwareId}?.mode = DigitalChannel.Mode.INPUT"
                else -> null
            }
        }.joinToString("\n").ifBlank { "        // No one-time device configuration is required." }
        val refresh = document.hardware.mapNotNull { device ->
            val field = document.field(device.measurementFieldId) ?: return@mapNotNull null
            val read = when (requireNotNull(device.measurementSource)) {
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
                "($read) * ${device.measurementScale.kotlinDouble()} + ${device.measurementOffset.kotlinDouble()}"
            } else read
            "        cached${field.fieldId.pascalCase()} = try { $converted } catch (_: Exception) { ${field.defaultKotlinLiteral()} }"
        }.joinToString("\n").ifBlank { "        // This subsystem has no readable sensors." }
        val commands = document.hardware.filter { it.kind.isActuator() }.joinToString("\n\n") { device ->
            val assignment = when (device.kind) {
                SubsystemHardwareKind.MOTOR -> "${device.hardwareId}?.power = (safeValue / 12.0).coerceIn(-1.0, 1.0)"
                SubsystemHardwareKind.POSITIONAL_SERVO -> "${device.hardwareId}?.position = safeValue.coerceIn(0.0, 1.0)"
                SubsystemHardwareKind.CONTINUOUS_SERVO -> "${device.hardwareId}?.power = safeValue.coerceIn(-1.0, 1.0)"
                else -> error("Not an actuator")
            }
            """    override fun ${device.commandName()}(value: Double) {
        val safeValue = value.takeIf(Double::isFinite) ?: 0.0
        $assignment
    }"""
        }
        val safe = document.hardware.filter { it.kind == SubsystemHardwareKind.MOTOR || it.kind == SubsystemHardwareKind.CONTINUOUS_SERVO }
            .joinToString("\n") { device ->
                val assignment = when (device.kind) {
                    SubsystemHardwareKind.MOTOR -> "${device.hardwareId}?.power = 0.0"
                    SubsystemHardwareKind.CONTINUOUS_SERVO -> "${device.hardwareId}?.power = 0.0"
                    else -> error("Not a stop-capable FTC actuator")
                }
                "        try { $assignment } catch (_: Exception) { /* Continue stopping other devices. */ }"
            }
            .ifBlank { "        // Positional servos retain their last safe position." }
        return """
            package $pkg

            ${imports.sorted().joinToString("\n") { "import $it" }}

            /** FTC SDK adapter generated from the subsystem DSL. */
            class Ftc${document.name}IO(hardwareMap: HardwareMap) : ${document.name}IO {
            $fields
            $cached

                init {
            $init
                }

                override fun refresh() {
            $refresh
                }

            $commands

                override fun safe() {
            $safe
                }

                override fun close() = safe()
            }
        """.trimIndent() + "\n"
    }

    private fun frcIoSource(document: SubsystemDocument, pkg: String): String {
        val imports = linkedSetOf<String>()
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
        val cached = document.hardware.mapNotNull { device ->
            val field = document.field(device.measurementFieldId) ?: return@mapNotNull null
            "    private var cached${field.fieldId.pascalCase()}: ${field.kotlinType()} = ${field.defaultKotlinLiteral()}\n" +
                "    override val ${field.fieldId}: ${field.kotlinType()} get() = cached${field.fieldId.pascalCase()}"
        }.distinct().joinToString("\n")
        val init = document.hardware.filter { it.kind == SubsystemHardwareKind.MOTOR }
            .joinToString("\n") { device ->
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
                    append("        ${device.hardwareId}.configurator.apply($configName)\n")
                    append("        ${device.hardwareId}.optimizeBusUtilization()")
                }
            }
            .ifBlank { "        // No TalonFX configuration is required." }
        val refresh = document.hardware.mapNotNull { device ->
            val field = document.field(device.measurementFieldId) ?: return@mapNotNull null
            val read = when (requireNotNull(device.measurementSource)) {
                SubsystemMeasurementSource.MOTOR_POSITION_NATIVE -> "${device.hardwareId}.position.valueAsDouble"
                SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND -> "${device.hardwareId}.velocity.valueAsDouble"
                SubsystemMeasurementSource.MOTOR_CURRENT_AMPS -> "${device.hardwareId}.statorCurrent.valueAsDouble"
                SubsystemMeasurementSource.DIGITAL_STATE -> "${device.hardwareId}.get()"
                SubsystemMeasurementSource.ANALOG_VOLTAGE -> "${device.hardwareId}.voltage"
                SubsystemMeasurementSource.COLOR_ARGB -> error("FRC color sensors are rejected by validation")
            }
            val converted = if (field.type == SubsystemValueType.DOUBLE) {
                "($read) * ${device.measurementScale.kotlinDouble()} + ${device.measurementOffset.kotlinDouble()}"
            } else read
            "        cached${field.fieldId.pascalCase()} = $converted"
        }.joinToString("\n").ifBlank { "        // This subsystem has no readable sensors." }
        val commands = document.hardware.filter { it.kind.isActuator() }.joinToString("\n\n") { device ->
            val command = when (device.kind) {
                SubsystemHardwareKind.MOTOR -> "${device.hardwareId}.setVoltage(safeValue.coerceIn(-12.0, 12.0))"
                SubsystemHardwareKind.POSITIONAL_SERVO -> "${device.hardwareId}.set(safeValue.coerceIn(0.0, 1.0))"
                SubsystemHardwareKind.CONTINUOUS_SERVO -> "${device.hardwareId}.set(safeValue.coerceIn(-1.0, 1.0))"
                else -> error("Not actuator")
            }
            """    override fun ${device.commandName()}(value: Double) {
        val safeValue = value.takeIf(Double::isFinite) ?: 0.0
        $command
    }"""
        }
        val safe = document.hardware.filter { it.kind == SubsystemHardwareKind.MOTOR || it.kind == SubsystemHardwareKind.CONTINUOUS_SERVO }
            .joinToString("\n") { device ->
                val command = when (device.kind) {
                    SubsystemHardwareKind.MOTOR -> "${device.hardwareId}.setVoltage(0.0)"
                    SubsystemHardwareKind.CONTINUOUS_SERVO -> "${device.hardwareId}.set(0.0)"
                    else -> error("Not a stop-capable FRC actuator")
                }
                "        try { $command } catch (_: Exception) { /* Continue stopping other devices. */ }"
            }
            .ifBlank { "        // Positional servos retain their last safe position." }
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

            /** FRC hardware adapter generated from the subsystem DSL. */
            class Frc${document.name}IO : ${document.name}IO {
            $fields
            $cached

                init {
            $init
                }

                override fun refresh() {
            $refresh
                }

            $commands

                override fun safe() {
            $safe
                }

                override fun close() {
                    safe()
            $close
                }
            }
        """.trimIndent() + "\n"
    }

    private fun mockIoSource(document: SubsystemDocument, pkg: String): String {
        val measurements = document.hardware.mapNotNull { document.field(it.measurementFieldId) }.distinctBy { it.fieldId }
        val fields = measurements.joinToString("\n") { field ->
            "    override var ${field.fieldId}: ${field.kotlinType()} = ${field.defaultKotlinLiteral()}"
        }
        val commandFields = document.hardware.filter { it.kind.isActuator() }.joinToString("\n") { device ->
            "    var ${device.hardwareId}Command: Double = 0.0\n        private set"
        }
        val commands = document.hardware.filter { it.kind.isActuator() }.joinToString("\n\n") { device ->
            """    override fun ${device.commandName()}(value: Double) {
        ${device.hardwareId}Command = value.takeIf(Double::isFinite) ?: 0.0
    }"""
        }
        val safe = document.hardware.filter { it.kind.isActuator() }.joinToString("\n") {
            "        ${it.hardwareId}Command = 0.0"
        }
        return """
            package $pkg

            /** Deterministic desktop/test adapter; tests may assign cached sensor properties directly. */
            class Mock${document.name}IO : ${document.name}IO {
            $fields
            $commandFields

                override fun refresh() = Unit

            $commands

                override fun safe() {
            $safe
                }

                override fun close() = safe()
            }
        """.trimIndent() + "\n"
    }

    private fun testSource(document: SubsystemDocument, pkg: String): String {
        val firstTarget = document.stateFields.firstOrNull { it.role == SubsystemFieldRole.TARGET }
        val assertion = firstTarget?.let {
            "assertEquals(${it.defaultKotlinLiteral()}, state.${it.fieldId})"
        } ?: "assertNotNull(state)"
        val imports = when (document.platform) {
            SubsystemPlatform.FTC -> """import org.junit.Assert.assertEquals
            import org.junit.Assert.assertNotNull
            import org.junit.Test"""
            SubsystemPlatform.FRC -> """import org.junit.jupiter.api.Assertions.assertEquals
            import org.junit.jupiter.api.Assertions.assertNotNull
            import org.junit.jupiter.api.Test"""
        }
        return """
            package $pkg

            $imports

            class ${document.name}GeneratedTest {
                @Test
                fun `generated state and mock IO start safely`() {
                    val state = ${document.name}State()
                    val io = Mock${document.name}IO()
                    $assertion
                    io.safe()
                }
            }
        """.trimIndent() + "\n"
    }
}

private val PID_STRATEGIES = setOf(
    SubsystemControlStrategy.POSITION_PID,
    SubsystemControlStrategy.VELOCITY_PID,
)

private fun SubsystemDocument.field(id: String?): SubsystemStateFieldDocument? =
    id?.let { requested -> stateFields.firstOrNull { it.fieldId == requested } }

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

private fun SubsystemStateFieldDocument.clampedExpression(expression: String): String = when {
    minimum != null && maximum != null ->
        "($expression).coerceIn(${minimum.kotlinDouble()}, ${maximum.kotlinDouble()})"
    minimum != null -> "($expression).coerceAtLeast(${minimum.kotlinDouble()})"
    maximum != null -> "($expression).coerceAtMost(${maximum.kotlinDouble()})"
    else -> expression
}

private fun registryActionCase(
    document: SubsystemDocument,
    field: SubsystemStateFieldDocument,
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
    return """    ${key.quoted()} -> $converted?.let { typedValue ->
        StateActionTask(${("Set ${document.name} ${field.displayName}").quoted()}) { robotState ->
            val current = ${document.name}Subsystem.state(robotState)
            RobotAction.UpdateNamedSubsystemState(
                ${document.name}Subsystem.ID,
                current.copy(${field.fieldId} = typedValue),
            )
        }
    }"""
}

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
