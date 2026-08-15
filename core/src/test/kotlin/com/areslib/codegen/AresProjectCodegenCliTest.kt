package com.areslib.codegen

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogCodec
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.drivetrain.DrivetrainPlatform
import com.areslib.routine.AresRoutineCodec
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineStep
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import com.areslib.superstructure.StateTransitionEdge
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.SuperstructureDocumentCodec
import com.areslib.superstructure.SuperstructureStatePreset
import com.areslib.superstructure.SuperstructureSubsystemTarget
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AresProjectCodegenCliTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `drivebase generation rejects cross-platform and desktop targets before writing`() {
        validateDrivebaseCodegenPlatform(DrivetrainPlatform.FTC, ControllerInputPlatform.FTC)
        validateDrivebaseCodegenPlatform(DrivetrainPlatform.FRC, ControllerInputPlatform.FRC)

        val mismatch = assertThrows<IllegalArgumentException> {
            validateDrivebaseCodegenPlatform(DrivetrainPlatform.FTC, ControllerInputPlatform.FRC)
        }
        assertTrue(mismatch.message.orEmpty().contains("targets FTC"))
        assertThrows<IllegalStateException> {
            validateDrivebaseCodegenPlatform(DrivetrainPlatform.FRC, ControllerInputPlatform.DESKTOP_GLFW)
        }
        assertThrows<IllegalStateException> {
            validateDrivebaseCodegenPlatform(DrivetrainPlatform.FRC, null)
        }
    }

    @Test
    fun `generates checked in source and check mode detects stale edits`() {
        val ares = Files.createDirectories(temporary.resolve(".ares/routines"))
        Files.writeString(
            temporary.resolve(".ares/action-catalog.json"),
            CapabilityCatalogCodec.encode(
                CapabilityCatalogDocument(
                    projectId = "test",
                    actions = listOf(ActionDescriptor("intake.stop", "Stop intake", "Stops intake."))
                )
            )
        )
        Files.writeString(
            temporary.resolve(".ares/project.json"),
            AresProjectMetadataCodec.encode(
                AresProjectMetadataDocument(
                    projectId = "test",
                    league = AresLeague.FTC,
                    coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
                    robotLengthMeters = 0.45,
                    robotWidthMeters = 0.45,
                    fieldLengthMeters = 3.6576,
                    fieldWidthMeters = 3.6576,
                )
            )
        )
        Files.writeString(
            ares.resolve("stop.aresroutine"),
            AresRoutineCodec.encode(
                RoutineDocument(
                    documentId = "stop",
                    name = "Stop",
                    steps = listOf(RoutineStep.action("intake.stop"))
                )
            )
        )
        val output = temporary.resolve("src/generated/GeneratedAresProject.kt")
        val baseArguments = arrayOf(
            "--project", temporary.toString(),
            "--output", output.toString(),
            "--package", "org.example.generated"
        )

        AresProjectCodegenCli.run(baseArguments)
        val generatedSource = Files.readString(output)
        assertTrue(generatedSource.contains("object GeneratedAresProject"))
        assertTrue(generatedSource.contains("const val ROBOT_LENGTH_METERS: Double = 0.45"))
        AresProjectCodegenCli.run(baseArguments + "--check")

        Files.writeString(output, Files.readString(output) + "// stale")
        assertThrows<IllegalArgumentException> { AresProjectCodegenCli.run(baseArguments + "--check") }
    }

    @Test
    fun `project generation installs validated superstructure runtime and action routing`() {
        val ares = Files.createDirectories(temporary.resolve(".ares"))
        val subsystemRoot = Files.createDirectories(ares.resolve("subsystems"))
        val superstructureRoot = Files.createDirectories(ares.resolve("superstructures"))
        val subsystem = SubsystemTemplates.create(
            SubsystemTemplate.SIMPLE_ACTUATOR,
            documentId = "arm",
            kotlinTypeName = "Arm",
            platform = SubsystemPlatform.FTC,
        )
        Files.writeString(subsystemRoot.resolve("arm.aressubsystem"), SubsystemDocumentCodec.encode(subsystem))
        val target = subsystem.stateFields.single { it.role == SubsystemFieldRole.TARGET }
        val safeValue = target.defaultNumber ?: target.defaultInt?.toDouble() ?: 0.0
        fun preset(id: String, value: Double) = SuperstructureStatePreset(
            stateId = id,
            subsystemTargets = listOf(
                SuperstructureSubsystemTarget(subsystem.documentId, target.fieldId, constantDoubleValue = value),
            ),
        )
        val superstructure = SuperstructureDocument(
            superstructureId = "scoring-machine",
            initialStateId = "STOW",
            faultStateId = "FAULT",
            states = listOf(preset("STOW", safeValue), preset("ACTIVE", 0.5), preset("FAULT", safeValue)),
            transitions = listOf(
                StateTransitionEdge(
                    transitionId = "activate",
                    sourceStateId = "STOW",
                    targetStateId = "ACTIVE",
                    actionKey = "machine.activate",
                    timeoutSeconds = 0.2,
                    timeoutTargetStateId = "FAULT",
                ),
                StateTransitionEdge(
                    transitionId = "stop",
                    sourceStateId = "ACTIVE",
                    targetStateId = "STOW",
                    actionKey = "machine.stop",
                ),
                StateTransitionEdge(
                    transitionId = "recover",
                    sourceStateId = "FAULT",
                    targetStateId = "STOW",
                    actionKey = "machine.recover",
                ),
            ),
        )
        Files.writeString(
            superstructureRoot.resolve("scoring-machine.aressuperstructure"),
            SuperstructureDocumentCodec.encode(superstructure),
        )
        Files.writeString(
            ares.resolve("action-catalog.json"),
            CapabilityCatalogCodec.encode(
                CapabilityCatalogDocument(
                    projectId = "test",
                    actions = listOf(
                        ActionDescriptor("machine.activate", "Activate", "Activate the machine."),
                        ActionDescriptor("machine.stop", "Stop", "Stop the machine."),
                        ActionDescriptor("machine.recover", "Recover", "Recover from the fault state."),
                    ),
                ),
            ),
        )
        Files.writeString(ares.resolve("project.json"), projectMetadata())

        val starterRoot = temporary.resolve("src/main/kotlin")
        val generatedRoot = temporary.resolve("build/generated/ares/main/kotlin")
        val generatedTestRoot = temporary.resolve("build/generated/ares/test/kotlin")
        SubsystemKotlinGenerator.generate(
            subsystem,
            SubsystemKotlinCodegenTarget(SubsystemPlatform.FTC, "org.example.subsystems"),
        ).filter { it.ownership == SubsystemArtifactOwnership.GENERATED_STARTER }.forEach { file ->
            val destination = starterRoot.resolve(file.relativePath)
            Files.createDirectories(destination.parent)
            Files.writeString(destination, file.content)
        }
        val projectOutput = temporary.resolve("build/generated/project/GeneratedAresProject.kt")
        val superstructureOutput = temporary.resolve("build/generated/ares/superstructure")
        val arguments = arrayOf(
            "--project", temporary.toString(),
            "--output", projectOutput.toString(),
            "--package", "org.example.generated",
            "--platform", "FTC",
            "--subsystems-package", "org.example.subsystems",
            "--subsystems-starter-output", starterRoot.toString(),
            "--subsystems-generated-output", generatedRoot.toString(),
            "--subsystems-generated-test-output", generatedTestRoot.toString(),
            "--superstructure-output", superstructureOutput.toString(),
            "--superstructure-package", "org.example.subsystems.superstructure",
        )

        AresProjectCodegenCli.run(arguments)

        val projectSource = Files.readString(projectOutput)
        assertTrue(projectSource.contains("GeneratedSuperstructureRegistry.createActionTask(\"machine.activate\")"))
        assertTrue(Files.readString(superstructureOutput.resolve("ScoringMachineSuperstructure.kt"))
            .contains("SuperstructureRuntimeBinding"))
        assertTrue(Files.isRegularFile(superstructureOutput.resolve("GeneratedSuperstructureRegistry.kt")))
        AresProjectCodegenCli.run(arguments + "--check")
    }

    @Test
    fun `rejects generated output outside selected project`() {
        Files.createDirectories(temporary.resolve(".ares"))
        val outside = temporary.parent.resolve("outside.kt")
        assertThrows<IllegalArgumentException> {
            AresProjectCodegenCli.run(
                arrayOf(
                    "--project", temporary.toString(),
                    "--output", outside.toString(),
                    "--package", "org.example"
                )
            )
        }
    }

    @Test
    fun `zero drivebase documents clear stale generated manifest output`() {
        Files.createDirectories(temporary.resolve(".ares"))
        Files.writeString(
            temporary.resolve(".ares/action-catalog.json"),
            CapabilityCatalogCodec.encode(CapabilityCatalogDocument(projectId = "test")),
        )
        Files.writeString(
            temporary.resolve(".ares/project.json"),
            AresProjectMetadataCodec.encode(
                AresProjectMetadataDocument(
                    projectId = "test", league = AresLeague.FTC,
                    coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
                    robotLengthMeters = 0.45, robotWidthMeters = 0.45,
                    fieldLengthMeters = 3.6576, fieldWidthMeters = 3.6576,
                )
            ),
        )
        val generatedRoot = Files.createDirectories(temporary.resolve("build/generated/drivebase"))
        val stale = generatedRoot.resolve("GeneratedAresDrivebaseConfig.kt")
        Files.writeString(stale, "// stale")
        Files.writeString(generatedRoot.resolve(".ares-drivebase-manifest"), "GeneratedAresDrivebaseConfig.kt\n")

        AresProjectCodegenCli.run(
            arrayOf(
                "--project", temporary.toString(),
                "--output", temporary.resolve("build/generated/project/GeneratedAresProject.kt").toString(),
                "--package", "example.generated",
                "--drivebase-output", generatedRoot.toString(),
                "--drivebase-package", "example.generated",
            )
        )

        assertFalse(Files.exists(stale))
        assertFalse(Files.exists(generatedRoot.resolve(".ares-drivebase-manifest")))
    }

    private fun projectMetadata(): String = AresProjectMetadataCodec.encode(
        AresProjectMetadataDocument(
            projectId = "test",
            league = AresLeague.FTC,
            coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
            robotLengthMeters = 0.45,
            robotWidthMeters = 0.45,
            fieldLengthMeters = 3.6576,
            fieldWidthMeters = 3.6576,
        ),
    )
}
