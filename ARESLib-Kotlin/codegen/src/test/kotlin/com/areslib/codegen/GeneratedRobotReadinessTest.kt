package com.areslib.codegen

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineStep
import com.areslib.subsystem.*
import com.areslib.superstructure.*
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Runs actual generated state, registries, controllers, lifecycle and mock IO together. */
class GeneratedRobotReadinessTest {
    @TempDir lateinit var temporary: Path

    @Test fun `FTC generated scoring robot readiness`() = verify(SubsystemPlatform.FTC)
    @Test fun `FRC generated scoring robot readiness`() = verify(SubsystemPlatform.FRC)

    private fun verify(platform: SubsystemPlatform) {
        // Same templates as RepresentativeZeroCodeRobotTest, with their production safety defaults.
        val documents = listOf(
            SubsystemTemplates.create(SubsystemTemplate.FLYWHEEL_SHOOTER, "flywheel", "Flywheel", platform),
            SubsystemTemplates.create(SubsystemTemplate.INTAKE_CONVEYOR, "intake", "Intake", platform),
        )
        val keys = setOf("machine.run", "machine.stow", "machine.recover", "prepare.first", "prepare.second")
        val catalog = mergeSubsystemCapabilities(CapabilityCatalogDocument(
            projectId = "readiness", actions = keys.filter { it.startsWith("machine.") }.map { ActionDescriptor(it, it, "Readiness scenario action") },
        ), documents)
        fun preset(id: String, active: Boolean) = SuperstructureStatePreset(
            stateId = id,
            subsystemTargets = documents.map { document ->
                val field = document.stateFields.single { it.fieldId == "target" }
                SuperstructureSubsystemTarget(SuperstructureFieldReference(document.uid, field.uid),
                    constantDoubleValue = if (!active) 0.0 else if (document.documentId == "flywheel") 300.0 else 4.0)
            },
            onEntryActionKeys = if (active) listOf("prepare.first", "prepare.second") else emptyList(),
        )
        val machine = SuperstructureDocument(
            superstructureId = "scoring", initialStateId = "STOW", faultStateId = "FAULT", disabledStateId = "STOW",
            states = listOf(preset("STOW", false), preset("RUN", true), preset("FAULT", false)),
            transitions = listOf(
                StateTransitionEdge("run", "STOW", "RUN", actionKey = "machine.run"),
                StateTransitionEdge("stow", "RUN", "STOW", actionKey = "machine.stow"),
                StateTransitionEdge("recover", "FAULT", "STOW", actionKey = "machine.recover"),
            ),
            healthFallbacks = documents.map { document ->
                SuperstructureHealthFallbackPolicy("${document.documentId}-feedback",
                    SuperstructureFieldReference(document.uid, document.stateFields.first { it.role == SubsystemFieldRole.MEASUREMENT }.uid),
                    fallbackStateId = "FAULT")
            },
        )
        val sources = linkedMapOf<String, String>()
        val target = SubsystemKotlinCodegenTarget(platform, "readiness.subsystems")
        for (document in documents) {
            for (file in SubsystemKotlinGenerator.generate(document, target)) {
                if (file.sourceSet == GeneratedSubsystemSourceSet.MAIN && file.artifact != SubsystemArtifact.PLATFORM_IO) {
                    sources[file.relativePath] = file.content
                }
            }
            // Only unexercised physical constructors are stubs. Calling one fails immediately.
            // The actual emitted registry, state, controller, lifecycle and mock IO are unmodified.
            val prefix = if (platform == SubsystemPlatform.FTC) "Ftc" else "Frc"
            val parameter = if (platform == SubsystemPlatform.FTC) "hardwareMap: com.qualcomm.robotcore.hardware.HardwareMap" else ""
            sources["physical/${document.kotlinTypeName}.kt"] = """
                package readiness.subsystems.${document.documentId}
                class $prefix${document.kotlinTypeName}IO($parameter) : ${document.kotlinTypeName}IO by Mock${document.kotlinTypeName}IO() {
                    init { error("Physical IO is outside the desktop readiness fixture") }
                }
            """.trimIndent()
        }
        if (platform == SubsystemPlatform.FTC) sources["physical/HardwareMap.kt"] =
            "package com.qualcomm.robotcore.hardware\nclass HardwareMap"
        sources["GeneratedSubsystemRegistry.kt"] = SubsystemKotlinGenerator.generateRegistry(documents, target).content
        sources["Scoring.kt"] = SuperstructureKotlinGenerator.generate(machine, "readiness.machine",
            "readiness.subsystems.GeneratedSubsystemRegistry", documents, keys).content
        sources["Machines.kt"] = SuperstructureKotlinGenerator.generateRegistry(listOf(machine), "readiness.machine").content
        sources["Project.kt"] = AresKotlinProjectGenerator.generate(KotlinProjectCodegenRequest(
            packageName = "readiness.project", catalog = catalog,
            routines = listOf(RoutineDocument(documentId = "score", name = "Score",
                steps = listOf(RoutineStep.action("machine.run"), RoutineStep.wait(2.0), RoutineStep.action("machine.stow")))),
            targetInputPlatform = if (platform == SubsystemPlatform.FTC) ControllerInputPlatform.FTC else ControllerInputPlatform.FRC,
            subsystemActions = subsystemTargetCapabilities(documents),
            subsystemRegistryFqn = "readiness.subsystems.GeneratedSubsystemRegistry",
            generatedActionRegistryBindings = keys.filter { it.startsWith("machine.") }.associateWith { "readiness.machine.GeneratedSuperstructureRegistry" },
        )).source
        sources["Harness.kt"] = requireNotNull(javaClass.getResource("/readiness/ScoringRobot.kt.txt")).readText()
            .replace("PLATFORM_NAME", platform.name)
        val files = sources.map { (relative, content) ->
            temporary.resolve(relative).also { Files.createDirectories(it.parent); Files.writeString(it, content) }.toString()
        }
        val errors = mutableListOf<String>()
        val result = K2JVMCompiler().exec(object : MessageCollector {
            override fun clear() = errors.clear()
            override fun hasErrors() = errors.isNotEmpty()
            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                if (severity.isError) errors += "$location: $message"
            }
        }, Services.EMPTY, K2JVMCompilerArguments().apply {
            freeArgs = files; destination = temporary.resolve("classes").toString()
            classpath = System.getProperty("java.class.path"); jvmTarget = "17"; noStdlib = true; noReflect = true
        })
        assertEquals(ExitCode.OK, result, errors.joinToString("\n"))
        URLClassLoader(arrayOf(temporary.resolve("classes").toUri().toURL()), javaClass.classLoader).use { loader ->
            @Suppress("UNCHECKED_CAST")
            val harness = loader.loadClass("readiness.ScoringRobot").getConstructor().newInstance() as Callable<String>
            val report = harness.call()
            val output = Path.of("build", "reports", "robot-readiness", "${platform.name.lowercase()}.json")
            Files.createDirectories(output.parent); Files.writeString(output, report)
            println(report)
        }
    }
}
