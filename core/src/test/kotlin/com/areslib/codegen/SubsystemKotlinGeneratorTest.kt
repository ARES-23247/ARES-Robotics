package com.areslib.codegen

import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemImplementationDocument
import com.areslib.subsystem.SubsystemImplementationKind
import com.areslib.subsystem.InterlockComparison
import com.areslib.subsystem.SubsystemInterlockDocument
import com.areslib.subsystem.SubsystemLinkageDocument
import com.areslib.subsystem.FaultRecoveryActionKind
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemContinuousInputDocument
import com.areslib.subsystem.SubsystemControlStrategy
import com.areslib.subsystem.SubsystemFaultRecoveryDocument
import com.areslib.subsystem.SubsystemFeedforwardDocument
import com.areslib.subsystem.SubsystemHardwareScaffolding
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemMotionProfileDocument
import com.areslib.subsystem.SubsystemSafetyDocument
import com.areslib.subsystem.SubsystemFeedforwardKind
import com.areslib.subsystem.SubsystemFollowerTransform
import com.areslib.subsystem.SubsystemSimulationDocument
import com.areslib.subsystem.SubsystemSimulationSupport
import com.areslib.subsystem.SubsystemSourceOwnership
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import com.areslib.subsystem.SubsystemValueType
import com.areslib.subsystem.supportsPlatform
import com.areslib.subsystem.mergeSubsystemCapabilities
import com.areslib.subsystem.subsystem
import com.areslib.subsystem.subsystemTargetCapabilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.net.URLClassLoader
import java.nio.file.Files

class SubsystemKotlinGeneratorTest {
    @Test
    fun `every selectable controller strategy compiles and executes its generated behavior`() {
        val documents = SubsystemControlStrategy.entries.map(::behaviorDocument)
        assertEquals(SubsystemControlStrategy.entries.toSet(), documents.map { it.controlLoops.single().strategy }.toSet())

        val root = Files.createTempDirectory("ares-subsystem-controller-behavior")
        try {
            val sourceFiles = documents.flatMap { document ->
                val target = SubsystemKotlinCodegenTarget(SubsystemPlatform.FTC, "org.example.behavior")
                val generated = SubsystemKotlinGenerator.generate(document, target)
                val packageName = "${target.basePackage}.${document.documentId.replace('-', '_')}"
                val selected = generated.filter { it.artifact in setOf(
                    SubsystemArtifact.STATE,
                    SubsystemArtifact.IO_CONTRACT,
                    SubsystemArtifact.CONTROLLER,
                ) }
                val files = selected.map { file ->
                    root.resolve(file.relativePath.substringAfterLast('/')).toFile().apply { writeText(file.content) }
                }
                val ioSource = selected.single { it.artifact == SubsystemArtifact.IO_CONTRACT }.content
                files + root.resolve("${document.kotlinTypeName}BehaviorHarness.kt").toFile().apply {
                    writeText(controllerBehaviorHarness(document, packageName, ioSource))
                }
            }
            val classes = root.resolve("classes")
            Files.createDirectories(classes)
            val messages = mutableListOf<String>()
            val arguments = K2JVMCompilerArguments().apply {
                freeArgs = sourceFiles.map { it.path }
                destination = classes.toString()
                classpath = System.getProperty("java.class.path")
                jvmTarget = "17"
                noStdlib = true
                noReflect = true
            }
            val result = K2JVMCompiler().exec(object : MessageCollector {
                override fun clear() = messages.clear()
                override fun hasErrors(): Boolean = messages.isNotEmpty()
                override fun report(
                    severity: CompilerMessageSeverity,
                    message: String,
                    location: CompilerMessageSourceLocation?,
                ) {
                    if (severity.isError) messages += "${location?.path.orEmpty()}:${location?.line ?: 0}: $message"
                }
            }, Services.EMPTY, arguments)
            assertEquals(ExitCode.OK, result, messages.joinToString("\n"))

            URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader).use { loader ->
                documents.forEach { document ->
                    val packageName = "org.example.behavior.${document.documentId.replace('-', '_')}"
                    val harness = loader.loadClass("$packageName.${document.kotlinTypeName}BehaviorHarness")
                    val outcome = try {
                        harness.getMethod("run").invoke(null) as String
                    } catch (failure: java.lang.reflect.InvocationTargetException) {
                        throw failure.targetException
                    }
                    assertEquals("ok", outcome, document.controlLoops.single().strategy.name)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `every selectable template compiles its domain control and simulator sources`() {
        val root = Files.createTempDirectory("ares-subsystem-template-compilation")
        try {
            val portableArtifacts = setOf(
                SubsystemArtifact.DEFINITION,
                SubsystemArtifact.STATE,
                SubsystemArtifact.IO_CONTRACT,
                SubsystemArtifact.CONTROLLER,
                SubsystemArtifact.MOCK_IO,
            )
            val cases = SubsystemTemplate.entries.map { template ->
                val platform = if (template.supportsPlatform(SubsystemPlatform.FTC)) {
                    SubsystemPlatform.FTC
                } else {
                    SubsystemPlatform.FRC
                }
                val typeName = template.name.lowercase().split('_').joinToString("") { token ->
                    token.replaceFirstChar(Char::uppercaseChar)
                }
                val documentId = "template-${template.name.lowercase().replace('_', '-')}"
                val document = SubsystemTemplates.create(template, documentId, typeName, platform)
                val target = SubsystemKotlinCodegenTarget(
                    platform,
                    "org.example.templates.${platform.name.lowercase()}",
                )
                document to target
            }
            val generatedSources = cases.flatMap { (document, target) ->
                SubsystemKotlinGenerator.generate(document, target)
                    .filter { it.artifact in portableArtifacts }
            }
            val sourceFiles = generatedSources.map { generated ->
                root.resolve(generated.relativePath).toFile().apply {
                    parentFile.mkdirs()
                    writeText(generated.content)
                }
            }

            val classes = root.resolve("classes")
            Files.createDirectories(classes)
            val messages = mutableListOf<String>()
            val arguments = K2JVMCompilerArguments().apply {
                freeArgs = sourceFiles.map { it.path }
                destination = classes.toString()
                classpath = System.getProperty("java.class.path")
                jvmTarget = "17"
                noStdlib = true
                noReflect = true
            }
            val result = K2JVMCompiler().exec(object : MessageCollector {
                override fun clear() = messages.clear()
                override fun hasErrors(): Boolean = messages.isNotEmpty()
                override fun report(
                    severity: CompilerMessageSeverity,
                    message: String,
                    location: CompilerMessageSourceLocation?,
                ) {
                    if (severity.isError) messages += "${location?.path.orEmpty()}:${location?.line ?: 0}: $message"
                }
            }, Services.EMPTY, arguments)

            assertEquals(ExitCode.OK, result, messages.joinToString("\n"))
            assertEquals(
                SubsystemTemplate.entries.size * portableArtifacts.size,
                sourceFiles.size,
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `profiled position starter generates allocation-free constrained setpoint and feedforward`() {
        val document = SubsystemTemplates.create(
            SubsystemTemplate.ELEVATOR_LIFT,
            "elevator",
            "Elevator",
            SubsystemPlatform.FTC,
        )
        val files = SubsystemKotlinGenerator.generate(
            document,
            SubsystemKotlinCodegenTarget(SubsystemPlatform.FTC, "org.example.subsystems"),
        )
        val controller = files.single { it.artifact == SubsystemArtifact.CONTROLLER }.content

        assertTrue(controller.contains("primaryProfilePosition"))
        assertTrue(controller.contains("primaryProfileVelocity"))
        assertTrue(controller.contains("primaryVelocityStep = 1.5 * dtSeconds"))
        assertTrue(controller.contains("minOf(0.8, primaryStoppingVelocity)"))
        assertTrue(controller.contains("primaryDesiredAcceleration = primaryProfileAcceleration"))
        assertTrue(controller.contains("+ 0.6"))
        assertTrue(!controller.contains("mutableListOf"))
        assertTrue(!controller.contains("DoubleArray"))
    }

    @Test
    fun `two joint linkage mock runs accepted outputs through deterministic plant`() {
        val shoulder = SubsystemHardwareScaffolding.create(
            SubsystemHardwareKind.MOTOR,
            "shoulder",
            "Shoulder",
            SubsystemPlatform.FTC,
        )
        val elbow = SubsystemHardwareScaffolding.create(
            SubsystemHardwareKind.MOTOR,
            "elbow",
            "Elbow",
            SubsystemPlatform.FTC,
        )
        val document = SubsystemDocument(
            documentId = "two-joint-arm",
            displayName = "Two joint arm",
            kotlinTypeName = "TwoJointArm",
            platform = SubsystemPlatform.FTC,
            hardware = listOf(shoulder.hardware, elbow.hardware),
            stateFields = shoulder.stateFields + elbow.stateFields,
            controlLoops = shoulder.controlLoops + elbow.controlLoops,
            linkage = SubsystemLinkageDocument(
                enabled = true,
                link1LengthMeters = 0.4,
                link2LengthMeters = 0.25,
                link1MassKg = 1.1,
                link2MassKg = 0.6,
                joint1ActuatorId = "shoulder",
                joint2ActuatorId = "elbow",
                joint1AngleFieldId = "shoulderPosition",
                joint2AngleFieldId = "elbowPosition",
                joint1TorquePerVoltNm = 1.2,
                joint2TorquePerVoltNm = 0.8,
            ),
        )

        val mock = SubsystemKotlinGenerator.generate(
            document,
            SubsystemKotlinCodegenTarget(SubsystemPlatform.FTC, "org.example.subsystems"),
        ).single { it.artifact == SubsystemArtifact.MOCK_IO }.content

        assertTrue(mock.contains("TwoDofLinkagePlant("))
        assertTrue(mock.contains("joint1TorquePerVoltNm = 1.2"))
        assertTrue(mock.contains("linkagePlant.step("))
        assertTrue(mock.contains("shoulderCommand,"))
        assertTrue(mock.contains("elbowCommand,"))
        assertTrue(mock.contains("shoulderPosition = linkagePlant.joint1PositionRad"))
        assertTrue(mock.contains("elbowPosition = linkagePlant.joint2PositionRad"))
    }

    @Test
    fun `automatic jam recovery is bounded current-gated and owned by generated IO`() {
        val scaffold = SubsystemHardwareScaffolding.create(
            SubsystemHardwareKind.MOTOR,
            "roller",
            "Roller",
            SubsystemPlatform.FTC,
        )
        val currentField = scaffold.hardware.measurements.single {
            it.source == com.areslib.subsystem.SubsystemMeasurementSource.MOTOR_CURRENT_AMPS
        }.fieldId
        val document = SubsystemDocument(
            documentId = "jam-safe-intake",
            displayName = "Jam-safe intake",
            kotlinTypeName = "JamSafeIntake",
            platform = SubsystemPlatform.FTC,
            hardware = listOf(scaffold.hardware),
            stateFields = scaffold.stateFields,
            controlLoops = scaffold.controlLoops,
            safety = SubsystemSafetyDocument(
                requiresCurrentMonitoring = true,
                faultRecovery = SubsystemFaultRecoveryDocument(
                    enabled = true,
                    actuatorId = scaffold.hardware.hardwareId,
                    currentFieldId = currentField,
                    currentThresholdAmps = 12.0,
                    currentDurationMs = 200L,
                    recoveryAction = FaultRecoveryActionKind.REVERSE_BRIEFLY,
                    reverseDurationMs = 300L,
                    reverseDutyCycle = -0.25,
                    maxRetries = 2,
                ),
            ),
        )
        val files = SubsystemKotlinGenerator.generate(
            document,
            SubsystemKotlinCodegenTarget(SubsystemPlatform.FTC, "org.example.subsystems"),
        )
        val controller = files.single { it.artifact == SubsystemArtifact.CONTROLLER }.content
        val io = files.single { it.artifact == SubsystemArtifact.IO_CONTRACT }.content
        val physical = files.single { it.artifact == SubsystemArtifact.PLATFORM_IO }.content
        val mock = files.single { it.artifact == SubsystemArtifact.MOCK_IO }.content

        assertTrue(controller.contains("jamEvidenceSinceMs"))
        assertTrue(controller.contains("recoveryCurrentAmps < 12.0"))
        assertTrue(controller.contains("now - jamEvidenceSinceMs < 200L"))
        assertTrue(controller.contains("automaticRecoveryRetries >= 2"))
        assertTrue(controller.contains("io.commandAutomaticRecovery(-3.0)"))
        assertTrue(io.contains("fun commandAutomaticRecovery(value: Double): Boolean"))
        assertTrue(physical.contains("override fun latchOutputFault()"))
        assertTrue(mock.contains("SimAppliedOutputRegistry.register"))
        assertTrue(mock.contains("HardwareRegistry.registerDevice"))
        assertTrue(mock.contains("override var configurationHealthy: Boolean = true"))
    }

    @Test
    fun `cross-subsystem interlocks resolve before generation and fail closed in the registry`() {
        val target = SubsystemTemplates.create(
            SubsystemTemplate.SIMPLE_ACTUATOR,
            documentId = "arm",
            kotlinTypeName = "Arm",
            platform = SubsystemPlatform.FTC,
        )
        val owner = SubsystemTemplates.create(
            SubsystemTemplate.SIMPLE_ACTUATOR,
            documentId = "intake",
            kotlinTypeName = "Intake",
            platform = SubsystemPlatform.FTC,
        ).copy(
            interlocks = listOf(
                SubsystemInterlockDocument(
                    interlockId = "arm-clear",
                    targetSubsystemUid = target.uid,
                    targetFieldId = target.stateFields.first().fieldId,
                    comparison = InterlockComparison.GREATER_THAN,
                    thresholdValue = 0.75,
                    forbiddenZoneDescription = "Intake cannot move while the arm is extended",
                ),
            ),
        )
        val codegenTarget = SubsystemKotlinCodegenTarget(SubsystemPlatform.FTC, "org.example.subsystems")
        val registry = SubsystemKotlinGenerator.generateRegistry(listOf(owner, target), codegenTarget).content
        val lifecycle = SubsystemKotlinGenerator.generate(owner, codegenTarget)
            .single { it.artifact == SubsystemArtifact.SUBSYSTEM_LIFECYCLE }.content

        assertTrue(registry.contains("fun interlocksPermitIntake(robotState: RobotState): Boolean"))
        assertTrue(registry.contains("as? ArmState ?: return false"))
        assertTrue(registry.contains("if (!interlockState0.feedbackValid"))
        assertTrue(registry.contains("> 0.75) return false"))
        assertTrue(lifecycle.contains("GeneratedSubsystemRegistry.interlocksPermitIntake(state)"))

        val missingTarget = owner.copy(
            interlocks = owner.interlocks.map { it.copy(targetSubsystemUid = "missing-subsystem") },
        )
        val error = assertThrows<IllegalArgumentException> {
            SubsystemKotlinGenerator.generateRegistry(listOf(missingTarget, target), codegenTarget)
        }
        assertTrue(error.message.orEmpty().contains("does not resolve to exactly one subsystem"))
    }

    @Test
    fun `hand-authored source is never emitted or guessed by code generation`() {
        val document = SubsystemTemplates.create(
            SubsystemTemplate.SIMPLE_ACTUATOR,
            documentId = "prism",
            kotlinTypeName = "Prism",
            platform = SubsystemPlatform.FTC,
        ).copy(
            generateMockIo = false,
            generateTest = false,
            implementation = SubsystemImplementationDocument(
                kind = SubsystemImplementationKind.HAND_AUTHORED,
                ownership = SubsystemSourceOwnership.USER_OWNED,
                modulePath = ":TeamCode",
                sourceFiles = listOf("TeamCode/src/main/java/example/PrismSubsystem.kt"),
                subsystemClassName = "example.PrismSubsystem",
                ioContractClassName = "example.PrismDriverIO",
                hardwareAdapterClassName = "example.FtcPrismDriverIO",
                simulation = SubsystemSimulationDocument(SubsystemSimulationSupport.UNAVAILABLE),
            ),
            capabilityActionKeys = listOf("prism.off"),
        )
        val target = SubsystemKotlinCodegenTarget(SubsystemPlatform.FTC, "org.example.generated.subsystems")

        val error = assertThrows<IllegalArgumentException> { SubsystemKotlinGenerator.generate(document, target) }
        assertTrue(error.message.orEmpty().contains("hand-authored USER-OWNED"))

        val registry = SubsystemKotlinGenerator.generateRegistry(listOf(document), target).content
        assertTrue(registry.contains("USER-OWNED hand-authored subsystems"))
        assertTrue(registry.contains("prism: example.PrismSubsystem"))
        assertTrue(!registry.contains("import example.PrismSubsystem"))
        assertTrue(!registry.contains("FtcPrismIO"))
        assertTrue(!registry.contains("PrismSubsystem("))
        assertTrue(!registry.contains(document.implementation.sourceFiles.single()))
    }

    @Test
    fun `generated suite exposes readable DSL typed runtime and safe cached IO`() {
        val document = subsystem("intake", "Intake", SubsystemPlatform.FTC) {
            description = "Student \"intake\"\nwith notes"
            requiredAtStartup = false
            val power = state.double("power", "Power", SubsystemFieldRole.TARGET, 0.0)
            val motor = hardware.motor("roller", "Roller") { hardwareMapName = "intake" }
            control.direct("rollerControl", "Roller control", motor, power) {
                minimumOutput = -12.0
                maximumOutput = 12.0
            }
        }

        val files = SubsystemKotlinGenerator.generate(
            document,
            SubsystemKotlinCodegenTarget(SubsystemPlatform.FTC, "org.example.generated.subsystems"),
        )
        assertEquals(
            files.sortedWith(compareBy<GeneratedSubsystemFile> { it.sourceSet.ordinal }.thenBy { it.relativePath }),
            files,
        )
        val definition = files.single { it.relativePath.endsWith("IntakeDefinition.kt") }.content
        val io = files.single { it.relativePath.endsWith("FtcIntakeIO.kt") }.content
        val subsystem = files.single { it.relativePath.endsWith("IntakeSubsystem.kt") }.content
        val state = files.single { it.relativePath.endsWith("IntakeState.kt") }.content
        val controller = files.single { it.relativePath.endsWith("IntakeController.kt") }.content
        val contractTest = files.single { it.relativePath.endsWith("IntakeGeneratedTest.kt") }.content
        assertTrue(definition.contains("val document = subsystem("))
        assertTrue(definition.contains("Student \\\"intake\\\"\\nwith notes"))
        assertTrue(io.contains("value.takeIf(Double::isFinite) ?: 0.0"))
        assertTrue(io.contains("HardwareRegistry.registerDevice"))
        assertTrue(io.contains("outputFaultLatched"))
        assertTrue(io.contains("recoverWithNeutral"))
        assertTrue(io.contains("configurationHealthy"))
        assertTrue(subsystem.contains("UpdateNamedSubsystemState"))
        assertTrue(!subsystem.contains("io.refresh()"))
        assertTrue(subsystem.contains("snapshotAgeMs"))
        assertTrue(subsystem.contains("commandSequence = nextCommandSequence"))
        assertTrue(state.contains("neutralRecoveryRequestSequence"))
        assertTrue(state.contains("commandSequence"))
        assertTrue(controller.contains("takeIf(Double::isFinite) ?: 0.0"))
        assertTrue(controller.contains("handledNeutralRecoveryRequestSequence"))
        assertTrue(controller.contains("neutralHoldCommandSequence"))
        assertTrue(controller.contains("if (io.recoverWithNeutral())"))
        assertTrue(controller.contains("neutralHoldCommandSequence = state.commandSequence"))
        assertTrue(controller.contains("feedbackAgeMs"))
        assertTrue(contractTest.contains("requests are consumed once and failed neutral stays latched"))
        assertTrue(contractTest.contains("direct and registered target actions advance the command sequence"))
        assertTrue(files.any { it.sourceSet == GeneratedSubsystemSourceSet.TEST })
        assertTrue(files.filter { it.ownership == SubsystemArtifactOwnership.GENERATED_STARTER }
            .all { it.content.startsWith("// ARES OWNERSHIP: GENERATED STARTER") })
        assertTrue(files.filter { it.ownership == SubsystemArtifactOwnership.GENERATED_DO_NOT_EDIT }
            .all { it.content.startsWith("// ARES OWNERSHIP: GENERATED - DO NOT EDIT") })

        val registry = SubsystemKotlinGenerator.generateRegistry(
            listOf(document),
            SubsystemKotlinCodegenTarget(SubsystemPlatform.FTC, "org.example.generated.subsystems"),
        ).content
        assertTrue(registry.contains("subsystem.intake.set.power"))
        assertTrue(registry.contains("subsystem.intake.recover.neutral"))
        assertTrue(registry.contains("StateActionTask"))
        assertTrue(registry.contains("current.copy(power = typedValue, commandSequence = nextCommandSequence)"))
        assertTrue(registry.contains("current.copy(neutralRecoveryRequestSequence = nextSequence)"))
        assertTrue(registry.contains("(value as? Boolean)?.takeIf { it }"))
        assertTrue(registry.contains("GeneratedSubsystemRegistrySupport.install(this, \"intake\", false)"))
        assertTrue(registry.contains("import com.areslib.subsystem.GeneratedSubsystemRegistrySupport"))
    }

    @Test
    fun `homed prototype keeps boundaries and generates its complete safety contract`() {
        val document = SubsystemTemplates.create(
            SubsystemTemplate.HOMED_MECHANISM,
            documentId = "prototype-elevator",
            kotlinTypeName = "PrototypeElevator",
            platform = SubsystemPlatform.FTC,
        )
        val files = SubsystemKotlinGenerator.generate(
            document,
            SubsystemKotlinCodegenTarget(SubsystemPlatform.FTC, "org.example.subsystems"),
        )

        assertEquals(8, files.size)
        assertEquals(6, files.count { it.ownership == SubsystemArtifactOwnership.GENERATED_STARTER })
        assertEquals(2, files.count { it.ownership == SubsystemArtifactOwnership.GENERATED_DO_NOT_EDIT })
        assertEquals(1, files.count { it.group == SubsystemArtifactGroup.DOMAIN })
        assertEquals(2, files.count { it.group == SubsystemArtifactGroup.CONTROL })
        assertEquals(2, files.count { it.group == SubsystemArtifactGroup.HARDWARE })
        assertEquals(1, files.count { it.group == SubsystemArtifactGroup.SIMULATION })
        assertEquals(1, files.count { it.group == SubsystemArtifactGroup.GENERATED_PLUMBING })
        assertEquals(1, files.count { it.group == SubsystemArtifactGroup.VERIFICATION })

        val state = files.single { it.artifact == SubsystemArtifact.STATE }.content
        val io = files.single { it.artifact == SubsystemArtifact.IO_CONTRACT }.content
        val physical = files.single { it.artifact == SubsystemArtifact.PLATFORM_IO }.content
        val mock = files.single { it.artifact == SubsystemArtifact.MOCK_IO }.content
        val test = files.single { it.artifact == SubsystemArtifact.CONTRACT_TEST }.content
        assertTrue(state.contains("val homed: Boolean = false"))
        assertTrue(state.contains("val currentReadingValid: Boolean = false"))
        assertTrue(io.contains("Cached hardware boundary"))
        assertTrue(physical.contains("override var homingConditionMet"))
        assertTrue(physical.contains("override fun commandHoming"))
        assertTrue(physical.contains("override fun establishHome"))
        assertTrue(physical.contains("feedbackTimestampMs = RobotClock.currentTimeMillis()"))
        assertTrue(physical.contains("if (!applyNeutral()) outputFaultLatched = true"))
        assertTrue(mock.contains("failNextRefresh"))
        assertTrue(mock.contains("failNextWrite"))
        assertTrue(test.contains("failed writes latch and require explicit neutral recovery"))
        assertTrue(test.contains("invalid feedback and cleanup fail closed"))
        assertTrue(test.contains("homing evidence must dwell before home is established"))
        assertTrue(test.contains("neutral recovery requests are consumed once"))

        val controller = files.single { it.artifact == SubsystemArtifact.CONTROLLER }.content
        assertTrue(controller.contains("homingStartedAtMs"))
        assertTrue(controller.contains("homingEvidenceSinceMs"))
        assertTrue(controller.contains("io.failHoming()"))
        assertTrue(controller.contains("io.establishHome"))

        val capabilities = subsystemTargetCapabilities(listOf(document))
        assertTrue(capabilities.any {
            it.descriptor.key == "subsystem.prototype-elevator.set.homingRequested" &&
                it.operation.name == "SET_HOMING_REQUEST"
        })
    }

    @Test
    fun `calibration confirmation is a one-shot healthy neutral-gated request`() {
        val base = SubsystemTemplates.create(
            SubsystemTemplate.POSITION_CONTROLLED_MECHANISM,
            documentId = "calibrated-arm",
            kotlinTypeName = "CalibratedArm",
            platform = SubsystemPlatform.FTC,
        )
        val document = base.copy(safety = base.safety.copy(requiresCalibration = true))
        val target = SubsystemKotlinCodegenTarget(SubsystemPlatform.FTC, "org.example.subsystems")
        val files = SubsystemKotlinGenerator.generate(document, target)
        val state = files.single { it.artifact == SubsystemArtifact.STATE }.content
        val controller = files.single { it.artifact == SubsystemArtifact.CONTROLLER }.content
        val test = files.single { it.artifact == SubsystemArtifact.CONTRACT_TEST }.content
        val registry = SubsystemKotlinGenerator.generateRegistry(listOf(document), target).content

        assertTrue(state.contains("calibrationConfirmationRequestSequence"))
        assertTrue(controller.contains("handledCalibrationConfirmationRequestSequence"))
        assertTrue(controller.contains("safetyRequestPermitted(state, now) && !state.outputFaultLatched"))
        assertTrue(controller.contains("if (!mayCalibrate || !io.recoverWithNeutral())"))
        assertTrue(controller.contains("io.establishCalibration()"))
        assertTrue(controller.contains("neutralHoldCommandSequence = state.commandSequence"))
        assertTrue(test.contains("calibration confirmation requires fresh healthy state"))
        assertTrue(registry.contains("subsystem.calibrated-arm.confirm.calibration"))
        assertTrue(registry.contains("current.copy(calibrationConfirmationRequestSequence = nextSequence)"))
    }

    @Test
    fun `FRC generation uses native addressing and never FTC hardware map`() {
        val document = subsystem("climber", "Climber", SubsystemPlatform.FRC) {
            val volts = state.double("volts", "Voltage", SubsystemFieldRole.TARGET, 0.0)
            val motor = hardware.motor("winch", "Winch") { canId = 17; canBus = "CAN2" }
            control.direct("manual", "Manual", motor, volts)
        }
        val files = SubsystemKotlinGenerator.generate(
            document,
            SubsystemKotlinCodegenTarget(SubsystemPlatform.FRC, "com.example.generated.subsystems"),
        )
        val io = files.single { it.relativePath.endsWith("FrcClimberIO.kt") }.content
        assertTrue(io.contains("TalonFX(17, \"CAN2\")"))
        assertTrue(!io.contains("HardwareMap"))
    }

    @Test
    fun `typed sensor templates generate canonical cached reads on FTC and FRC`() {
        val ftcEncoder = SubsystemTemplates.create(
            SubsystemTemplate.ABSOLUTE_ENCODER_SENSOR,
            "wrist-encoder",
            "WristEncoder",
            SubsystemPlatform.FTC,
        )
        val ftcIo = SubsystemKotlinGenerator.generate(
            ftcEncoder,
            SubsystemKotlinCodegenTarget(SubsystemPlatform.FTC, "org.example.generated.subsystems"),
        ).single { it.relativePath.endsWith("FtcWristEncoderIO.kt") }.content
        assertTrue(ftcIo.contains("AnalogInput::class.java"))
        assertTrue(ftcIo.contains("input.voltage / input.maxVoltage"))
        assertTrue(ftcIo.contains("* ${2.0 * Math.PI}"))

        val ftcImu = SubsystemTemplates.create(
            SubsystemTemplate.IMU_SENSOR,
            "robot-imu",
            "RobotImu",
            SubsystemPlatform.FTC,
        )
        val ftcImuFiles = SubsystemKotlinGenerator.generate(
            ftcImu,
            SubsystemKotlinCodegenTarget(SubsystemPlatform.FTC, "org.example.generated.subsystems"),
        )
        val ftcImuIo = ftcImuFiles.single { it.relativePath.endsWith("FtcRobotImuIO.kt") }.content
        val ftcImuDefinition = ftcImuFiles.single { it.relativePath.endsWith("RobotImuDefinition.kt") }.content
        assertTrue(ftcImuIo.contains("RevHubOrientationOnRobot.LogoFacingDirection.UP"))
        assertTrue(ftcImuIo.contains("RevHubOrientationOnRobot.UsbFacingDirection.FORWARD"))
        assertTrue(ftcImuIo.contains("require(imu.initialize(IMU.Parameters"))
        assertTrue(ftcImuDefinition.contains("SubsystemHubFacingDirection.UP"))

        val frcEncoder = SubsystemTemplates.create(
            SubsystemTemplate.QUADRATURE_ENCODER_SENSOR,
            "arm-encoder",
            "ArmEncoder",
            SubsystemPlatform.FRC,
        )
        val frcIo = SubsystemKotlinGenerator.generate(
            frcEncoder,
            SubsystemKotlinCodegenTarget(SubsystemPlatform.FRC, "com.example.generated.subsystems"),
        ).single { it.relativePath.endsWith("FrcArmEncoderIO.kt") }.content
        assertTrue(frcIo.contains("Encoder(0, 1)"))
        assertTrue(frcIo.contains("distancePerPulse = 1.0 / 1.0"))
        assertTrue(frcIo.contains("sensor.rate"))

        val frcImu = SubsystemTemplates.create(
            SubsystemTemplate.IMU_SENSOR,
            "robot-imu",
            "RobotImu",
            SubsystemPlatform.FRC,
        )
        val frcImuIo = SubsystemKotlinGenerator.generate(
            frcImu,
            SubsystemKotlinCodegenTarget(SubsystemPlatform.FRC, "com.example.generated.subsystems"),
        ).single { it.relativePath.endsWith("FrcRobotImuIO.kt") }.content
        assertTrue(frcImuIo.contains("Math.toRadians(-sensor.angle)"))
        assertTrue(frcImuIo.contains("Math.toRadians(-sensor.rate)"))
    }

    @Test
    fun `FRC pneumatic template declares module and fail closed boolean output`() {
        val document = SubsystemTemplates.create(
            SubsystemTemplate.PNEUMATIC_ACTUATOR,
            "claw",
            "Claw",
            SubsystemPlatform.FRC,
        )
        val files = SubsystemKotlinGenerator.generate(
            document,
            SubsystemKotlinCodegenTarget(SubsystemPlatform.FRC, "com.example.generated.subsystems"),
        )
        val io = files.single { it.relativePath.endsWith("FrcClawIO.kt") }.content
        val mock = files.single { it.relativePath.endsWith("MockClawIO.kt") }.content
        assertTrue(io.contains("Solenoid(1, PneumaticsModuleType.REVPH, 0)"))
        assertTrue(io.contains("solenoid.set((requested) >= 0.5)"))
        assertTrue(mock.contains("solenoidCommand = 0.0"))
    }

    @Test
    fun `PID generation makes sensor conversion filtering and anti-windup explicit`() {
        val document = subsystem("elevator", "Elevator", SubsystemPlatform.FTC) {
            val target = state.double("targetMeters", "Target", SubsystemFieldRole.TARGET, 0.0, "m", 0.0, 1.2)
            val position = state.double("positionMeters", "Position", SubsystemFieldRole.MEASUREMENT, 0.0, "m")
            val motor = hardware.motor("leader", "Leader") {
                hardwareMapName = "elevator"
                measurement(
                    position,
                    com.areslib.subsystem.SubsystemMeasurementSource.MOTOR_POSITION_NATIVE,
                    scale = 0.02,
                )
            }
            control.positionPid("position", "Position", motor, target, position) {
                kP = 4.0
                kI = 0.5
                kD = 0.1
            }
        }
        val files = SubsystemKotlinGenerator.generate(
            document,
            SubsystemKotlinCodegenTarget(SubsystemPlatform.FTC, "org.example.generated.subsystems"),
        )
        val io = files.single { it.relativePath.endsWith("FtcElevatorIO.kt") }.content
        val controller = files.single { it.relativePath.endsWith("ElevatorController.kt") }.content

        assertTrue(io.contains("* 0.02 + 0.0"))
        assertTrue(controller.contains("DerivativeAlpha"))
        assertTrue(controller.contains("CandidateIntegral"))
        assertTrue(controller.contains("Unclamped =="))
        assertTrue(controller.contains("!positionTarget.isFinite()"))
        assertTrue(controller.contains("coerceIn(0.0, 1.2)"))
    }

    @Test
    fun `leader command drives inverted follower in physical and mock adapters`() {
        val document = subsystem("dual-motor", "DualMotor", SubsystemPlatform.FTC) {
            val volts = state.double("volts", "Voltage", SubsystemFieldRole.TARGET, 0.0, "V", -12.0, 12.0)
            val leader = hardware.motor("leader", "Leader") {
                hardwareMapName = "leader"
                inverted = true
            }
            hardware.motor("follower", "Follower") {
                hardwareMapName = "follower"
                inverted = true
                follow(leader, SubsystemFollowerTransform.INVERTED)
            }
            control.direct("motor", "Motor voltage", leader, volts)
        }
        val files = SubsystemKotlinGenerator.generate(
            document,
            SubsystemKotlinCodegenTarget(SubsystemPlatform.FTC, "org.example.generated.subsystems"),
        )
        val definition = files.single { it.artifact == SubsystemArtifact.DEFINITION }.content
        val io = files.single { it.artifact == SubsystemArtifact.IO_CONTRACT }.content
        val physical = files.single { it.artifact == SubsystemArtifact.PLATFORM_IO }.content
        val mock = files.single { it.artifact == SubsystemArtifact.MOCK_IO }.content

        assertTrue(definition.contains("follow(leader, com.areslib.subsystem.SubsystemFollowerTransform.INVERTED)"))
        assertTrue(io.contains("fun setLeaderVoltage(value: Double)"))
        assertTrue(!io.contains("fun setFollowerVoltage"))
        assertTrue(physical.contains("leader?.direction = DcMotorSimple.Direction.REVERSE"))
        assertTrue(physical.contains("follower?.direction = DcMotorSimple.Direction.REVERSE"))
        assertTrue(physical.contains("follower").and(physical.contains("-(requested)")))
        assertTrue(mock.contains("leaderCommand = (-(requested)).coerceIn(-12.0, 12.0)"))
        assertTrue(mock.contains("followerCommand = (-(-(requested))).coerceIn(-12.0, 12.0)"))
    }

    @Test
    fun `servo inversion is explicit in FTC FRC and mock adapters`() {
        fun document(platform: SubsystemPlatform) = subsystem("servo-pair", "ServoPair", platform) {
            val position = state.double("position", "Position", SubsystemFieldRole.TARGET, 0.5, null, 0.0, 1.0)
            val power = state.double("power", "Power", SubsystemFieldRole.TARGET, 0.0, null, -1.0, 1.0)
            val positional = hardware.positionalServo("arm", "Arm servo") {
                hardwareMapName = if (platform == SubsystemPlatform.FTC) "arm" else null
                channel = if (platform == SubsystemPlatform.FRC) 0 else null
                inverted = true
            }
            val continuous = hardware.continuousServo("roller", "Roller servo") {
                hardwareMapName = if (platform == SubsystemPlatform.FTC) "roller" else null
                channel = if (platform == SubsystemPlatform.FRC) 1 else null
                inverted = true
            }
            control.servoPosition("arm", "Arm position", positional, position)
            control.direct("roller", "Roller power", continuous, power)
        }

        val ftc = SubsystemKotlinGenerator.generate(
            document(SubsystemPlatform.FTC),
            SubsystemKotlinCodegenTarget(SubsystemPlatform.FTC, "org.example.generated.subsystems"),
        )
        val frc = SubsystemKotlinGenerator.generate(
            document(SubsystemPlatform.FRC),
            SubsystemKotlinCodegenTarget(SubsystemPlatform.FRC, "org.example.generated.subsystems"),
        )

        val ftcPhysical = ftc.single { it.artifact == SubsystemArtifact.PLATFORM_IO }.content
        assertTrue(ftcPhysical.contains("arm?.direction = Servo.Direction.REVERSE"))
        assertTrue(ftcPhysical.contains("roller?.direction = DcMotorSimple.Direction.REVERSE"))

        val frcPhysical = frc.single { it.artifact == SubsystemArtifact.PLATFORM_IO }.content
        assertTrue(frcPhysical.contains("roller.setInverted(true)"))
        assertTrue(frcPhysical.contains("arm.set((1.0 - (requested)).coerceIn(0.0, 1.0))"))

        val mock = ftc.single { it.artifact == SubsystemArtifact.MOCK_IO }.content
        assertTrue(mock.contains("armCommand = (1.0 - (requested)).coerceIn(0.0, 1.0)"))
        assertTrue(mock.contains("rollerCommand = (-(requested)).coerceIn(-1.0, 1.0)"))
    }

    @Test
    fun `velocity controller emits explicit simple motor feedforward`() {
        val document = SubsystemTemplates.create(
            SubsystemTemplate.VELOCITY_CONTROLLED_MECHANISM,
            documentId = "shooter",
            kotlinTypeName = "Shooter",
            platform = SubsystemPlatform.FTC,
        ).let { source ->
            val loop = source.controlLoops.single().copy(
                feedforward = source.controlLoops.single().feedforward.copy(
                    kind = SubsystemFeedforwardKind.SIMPLE_MOTOR,
                    kS = 0.25,
                    kV = 0.12,
                    kA = 0.01,
                    velocityFieldId = "target",
                ),
            )
            source.copy(controlLoops = listOf(loop))
        }

        val files = SubsystemKotlinGenerator.generate(
            document,
            SubsystemKotlinCodegenTarget(SubsystemPlatform.FTC, "org.example.generated.subsystems"),
        )
        val definition = files.single { it.artifact == SubsystemArtifact.DEFINITION }.content
        val controller = files.single { it.artifact == SubsystemArtifact.CONTROLLER }.content

        assertTrue(definition.contains("feedforward.kind = com.areslib.subsystem.SubsystemFeedforwardKind.SIMPLE_MOTOR"))
        assertTrue(definition.contains("feedforward.kS = 0.25"))
        assertTrue(controller.contains("primaryStatic"))
        assertTrue(controller.contains("primaryFeedforward"))
        assertTrue(controller.contains("0.01 * primaryDesiredAcceleration"))
    }

    @Test
    fun `project generator implements derived subsystem actions through generated registry`() {
        val document = subsystem("intake", "Intake", SubsystemPlatform.FTC) {
            val power = state.double("power", "Power", SubsystemFieldRole.TARGET, 0.0, minimum = -12.0, maximum = 12.0)
            val motor = hardware.motor("roller", "Roller") { hardwareMapName = "intake" }
            control.direct("rollerControl", "Roller control", motor, power)
        }
        val subsystemActions = subsystemTargetCapabilities(listOf(document))
        val catalog = mergeSubsystemCapabilities(
            CapabilityCatalogDocument(projectId = "robot"),
            listOf(document),
        )
        val source = AresKotlinProjectGenerator.generate(
            KotlinProjectCodegenRequest(
                packageName = "org.example.generated",
                catalog = catalog,
                routines = emptyList(),
                subsystemActions = subsystemActions,
                subsystemRegistryFqn = "org.example.generated.subsystems.GeneratedSubsystemRegistry",
            )
        ).source

        assertTrue(source.contains("fun actionSubsystemIntakeSetPower(value: Double): Task = requireNotNull("))
        assertTrue(source.contains("GeneratedSubsystemRegistry.createActionTask(\"subsystem.intake.set.power\", value)"))
    }

    @Test
    fun `arm feedforward generates cosine gravity compensation and bounds velocity`() {
        val base = SubsystemTemplates.create(
            SubsystemTemplate.POSITION_CONTROLLED_MECHANISM,
            documentId = "rotary-arm",
            kotlinTypeName = "RotaryArm",
            platform = SubsystemPlatform.FTC,
        )
        val loop = base.controlLoops.single().copy(
            feedforward = com.areslib.subsystem.SubsystemFeedforwardDocument(
                kind = SubsystemFeedforwardKind.ARM,
                kS = 0.15,
                kV = 1.20,
                kA = 0.05,
                kG = 0.60,
                gravityAngleFieldId = "position",
            )
        )
        val document = base.copy(
            stateFields = base.stateFields.map { field ->
                if (field.fieldId in setOf("target", "position")) field.copy(unit = "rad") else field
            },
            controlLoops = listOf(loop),
        )
        val files = SubsystemKotlinGenerator.generate(
            document,
            SubsystemKotlinCodegenTarget(SubsystemPlatform.FTC, "org.example.generated.subsystems"),
        )
        val definition = files.single { it.artifact == SubsystemArtifact.DEFINITION }.content
        val controller = files.single { it.artifact == SubsystemArtifact.CONTROLLER }.content

        assertTrue(definition.contains("feedforward.kind = com.areslib.subsystem.SubsystemFeedforwardKind.ARM"))
        assertTrue(definition.contains("feedforward.kG = 0.6"))
        assertTrue(controller.contains("0.6 * kotlin.math.cos(state.position.toDouble())"))
        assertTrue(controller.contains("primaryStatic"))
        assertTrue(controller.contains("primaryFeedforward"))
    }

    private fun behaviorDocument(strategy: SubsystemControlStrategy): SubsystemDocument {
        val template = when (strategy) {
            SubsystemControlStrategy.DIRECT -> SubsystemTemplate.SIMPLE_ACTUATOR
            SubsystemControlStrategy.POSITION_PID -> SubsystemTemplate.POSITION_CONTROLLED_MECHANISM
            SubsystemControlStrategy.PROFILED_POSITION_PID -> SubsystemTemplate.ELEVATOR_LIFT
            SubsystemControlStrategy.VELOCITY_PID -> SubsystemTemplate.VELOCITY_CONTROLLED_MECHANISM
            SubsystemControlStrategy.BANG_BANG -> SubsystemTemplate.POSITION_CONTROLLED_MECHANISM
            SubsystemControlStrategy.SERVO_POSITION -> SubsystemTemplate.POSITIONAL_SERVO
        }
        val typeStem = strategy.name.lowercase().split('_').joinToString("") { token ->
            token.replaceFirstChar { it.uppercase() }
        }
        val documentId = "behavior-${strategy.name.lowercase().replace('_', '-')}"
        val base = SubsystemTemplates.create(template, documentId, "${typeStem}Behavior", SubsystemPlatform.FTC)
        val sourceLoop = base.controlLoops.single()
        val continuous = strategy in setOf(
            SubsystemControlStrategy.POSITION_PID,
            SubsystemControlStrategy.PROFILED_POSITION_PID,
        )
        val loop = sourceLoop.copy(
            strategy = strategy,
            kP = if (strategy in setOf(
                    SubsystemControlStrategy.POSITION_PID,
                    SubsystemControlStrategy.PROFILED_POSITION_PID,
                    SubsystemControlStrategy.VELOCITY_PID,
                )
            ) 1.0 else 0.0,
            kI = 0.0,
            kD = 0.0,
            motionProfile = SubsystemMotionProfileDocument(maximumVelocity = 10.0, maximumAcceleration = 100.0),
            feedforward = SubsystemFeedforwardDocument(),
            derivativeFilterTimeConstantSeconds = 0.0,
            continuousInput = SubsystemContinuousInputDocument(enabled = continuous),
            tolerance = if (strategy == SubsystemControlStrategy.BANG_BANG) 0.10 else 0.0,
            hysteresis = if (strategy == SubsystemControlStrategy.BANG_BANG) 0.05 else 0.0,
            minimumOutput = if (strategy == SubsystemControlStrategy.SERVO_POSITION) 0.0 else -1.0,
            maximumOutput = 1.0,
        )
        val angularFields = if (continuous) setOfNotNull(loop.targetFieldId, loop.measurementFieldId) else emptySet()
        return base.copy(
            stateFields = base.stateFields.map { field ->
                if (field.fieldId in angularFields) field.copy(unit = "rad", minimum = null, maximum = null) else field
            },
            controlLoops = listOf(loop),
        )
    }

    private fun controllerBehaviorHarness(
        document: SubsystemDocument,
        packageName: String,
        ioSource: String,
    ): String {
        val loop = document.controlLoops.single()
        val typeName = document.kotlinTypeName
        val measuredFields = document.hardware.flatMap { it.measurements }
            .distinctBy { it.fieldId }
            .mapNotNull { measurement -> document.stateFields.firstOrNull { it.fieldId == measurement.fieldId } }
        val measurementOverrides = measuredFields.joinToString("\n") { field ->
            val typeAndDefault = when (field.type) {
                SubsystemValueType.DOUBLE -> "Double = 0.0"
                SubsystemValueType.INT -> "Int = 0"
                SubsystemValueType.BOOLEAN -> "Boolean = false"
                SubsystemValueType.STRING -> "String = \"\""
            }
            "    override val ${field.fieldId}: $typeAndDefault"
        }
        val commandMethods = Regex("fun (set[A-Za-z0-9_]+)\\(value: Double\\)")
            .findAll(ioSource)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
        require(commandMethods.isNotEmpty()) { "No generated actuator command found for ${document.documentId}" }
        val commandOverrides = commandMethods.joinToString("\n") { method ->
            "    override fun $method(value: Double) { lastCommand = value }"
        }
        val measurementArgument = loop.measurementFieldId?.let { "        $it = measurement,\n" }.orEmpty()
        val behavior = when (loop.strategy) {
            SubsystemControlStrategy.DIRECT -> """
                controller.update(state(2.0), scale = 0.5)
                require(closeTo(io.lastCommand, 0.5)) { "direct clamp/scale: ${'$'}{io.lastCommand}" }
                controller.update(state(Double.NaN), scale = 1.0)
                require(closeTo(io.lastCommand, 0.0)) { "direct invalid target: ${'$'}{io.lastCommand}" }
            """.trimIndent()
            SubsystemControlStrategy.SERVO_POSITION -> """
                controller.update(state(2.0), scale = 0.5)
                require(closeTo(io.lastCommand, 1.0)) { "servo clamp: ${'$'}{io.lastCommand}" }
                controller.update(state(Double.NaN), scale = 1.0)
                require(closeTo(io.lastCommand, 0.0)) { "servo invalid target: ${'$'}{io.lastCommand}" }
            """.trimIndent()
            SubsystemControlStrategy.POSITION_PID -> """
                controller.update(state(-Math.PI + 0.1, Math.PI - 0.1), scale = 1.0)
                require(closeTo(io.lastCommand, 0.2, 1e-8)) { "wrapped position PID: ${'$'}{io.lastCommand}" }
                controller.update(state(0.0, Double.NaN), scale = 1.0)
                require(closeTo(io.lastCommand, 0.0)) { "position invalid feedback: ${'$'}{io.lastCommand}" }
            """.trimIndent()
            SubsystemControlStrategy.PROFILED_POSITION_PID -> """
                controller.update(state(-Math.PI + 0.1, Math.PI - 0.1), scale = 1.0)
                require(io.lastCommand > 0.0 && io.lastCommand < 0.2) { "wrapped profiled PID: ${'$'}{io.lastCommand}" }
                controller.update(state(0.0, Double.NaN), scale = 1.0)
                require(closeTo(io.lastCommand, 0.0)) { "profiled invalid feedback: ${'$'}{io.lastCommand}" }
            """.trimIndent()
            SubsystemControlStrategy.VELOCITY_PID -> """
                controller.update(state(2.0, 0.5), scale = 1.0)
                require(closeTo(io.lastCommand, 1.0)) { "velocity clamp: ${'$'}{io.lastCommand}" }
                controller.update(state(0.0, Double.NaN), scale = 1.0)
                require(closeTo(io.lastCommand, 0.0)) { "velocity invalid feedback: ${'$'}{io.lastCommand}" }
            """.trimIndent()
            SubsystemControlStrategy.BANG_BANG -> """
                fun updateError(error: Double) = controller.update(state(0.0, -error), scale = 1.0)
                updateError(0.0)
                require(closeTo(io.lastCommand, 0.0)) { "on/off zero error: ${'$'}{io.lastCommand}" }
                updateError(0.20)
                require(closeTo(io.lastCommand, 1.0)) { "on/off start: ${'$'}{io.lastCommand}" }
                updateError(0.12)
                require(closeTo(io.lastCommand, 1.0)) { "on/off active hold: ${'$'}{io.lastCommand}" }
                updateError(0.08)
                require(closeTo(io.lastCommand, 0.0)) { "on/off stop band: ${'$'}{io.lastCommand}" }
                updateError(0.12)
                require(closeTo(io.lastCommand, 0.0)) { "on/off hysteresis hold: ${'$'}{io.lastCommand}" }
                updateError(0.16)
                require(closeTo(io.lastCommand, 1.0)) { "on/off restart: ${'$'}{io.lastCommand}" }
                updateError(-0.16)
                require(closeTo(io.lastCommand, 0.0)) { "on/off reversal neutral: ${'$'}{io.lastCommand}" }
                updateError(-0.16)
                require(closeTo(io.lastCommand, -1.0)) { "on/off reverse start: ${'$'}{io.lastCommand}" }
                controller.reset()
                updateError(0.12)
                require(closeTo(io.lastCommand, 0.0)) { "on/off reset clears latch: ${'$'}{io.lastCommand}" }
                controller.update(state(0.0, Double.NaN), scale = 1.0)
                require(closeTo(io.lastCommand, 0.0)) { "on/off invalid feedback: ${'$'}{io.lastCommand}" }
            """.trimIndent()
        }
        return """
            package $packageName

            import com.areslib.util.RobotClock
            import kotlin.math.abs

            private class ProbeIO : ${typeName}IO {
                var lastCommand: Double = 0.0
                override val feedbackValid: Boolean = true
                override val feedbackTimestampMs: Long = 1_000L
                override val configurationHealthy: Boolean = true
                override val homed: Boolean = true
                override val homingConditionMet: Boolean = false
                override val homingFaultLatched: Boolean = false
                override val calibrated: Boolean = true
                override val currentReadingValid: Boolean = true
                override val outputFaultLatched: Boolean = false
$measurementOverrides
$commandOverrides
                override fun safe() { lastCommand = 0.0 }
                override fun recoverWithNeutral(): Boolean { lastCommand = 0.0; return true }
                override fun commandAutomaticRecovery(value: Double): Boolean { lastCommand = value; return true }
                override fun latchOutputFault() { lastCommand = 0.0 }
                override fun establishCalibration() = Unit
                override fun commandHoming(): Boolean = false
                override fun establishHome(): Boolean = false
                override fun failHoming() { lastCommand = 0.0 }
                override fun cancelHoming(): Boolean { lastCommand = 0.0; return true }
                override fun close() = Unit
            }

            object ${typeName}BehaviorHarness {
                private fun state(target: Double, measurement: Double = 0.0) = ${typeName}State(
                    ${loop.targetFieldId} = target,
$measurementArgument                    feedbackValid = true,
                    feedbackTimestampMs = 1_000L,
                    configurationHealthy = true,
                    homed = true,
                    calibrated = true,
                    currentReadingValid = true,
                    outputFaultLatched = false,
                )

                private fun closeTo(actual: Double, expected: Double, tolerance: Double = 1e-9): Boolean =
                    abs(actual - expected) <= tolerance

                @JvmStatic
                fun run(): String {
                    RobotClock.useMockTime(1_000L)
                    return try {
                        val io = ProbeIO()
                        val controller = ${typeName}Controller(io)
${behavior.prependIndent("                        ")}
                        "ok"
                    } finally {
                        RobotClock.useSystemTime()
                    }
                }
            }
        """.trimIndent() + "\n"
    }
}
