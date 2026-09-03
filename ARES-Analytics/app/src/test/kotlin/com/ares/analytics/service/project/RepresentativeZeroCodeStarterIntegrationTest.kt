package com.ares.analytics.service.project

import com.ares.analytics.shared.models.League
import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogCodec
import com.areslib.controls.ControlBindingDocument
import com.areslib.controls.ControlEvent
import com.areslib.controls.ControlSchemeCodec
import com.areslib.controls.ControlSourceDocument
import com.areslib.controls.ControlSourceKind
import com.areslib.controls.ControlTargetDocument
import com.areslib.controls.ControlTargetKind
import com.areslib.drivetrain.DrivetrainPlatform
import com.areslib.routine.AresRoutineCodec
import com.areslib.routine.AutonomousCatalogCodec
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutinePose
import com.areslib.routine.RoutineStep
import com.areslib.subsystem.InterlockComparison
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.subsystem.SubsystemControlStrategy
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemInterlockDocument
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import com.areslib.subsystem.subsystemTargetActionKey
import com.areslib.superstructure.StateTransitionEdge
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.SuperstructureDocumentCodec
import com.areslib.superstructure.SuperstructureFieldReference
import com.areslib.superstructure.SuperstructureStatePreset
import com.areslib.superstructure.SuperstructureSubsystemTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in consumer proof for the complete representative GUI-authored robot. This uses the real
 * FTC and FRC starter build graphs and an isolated ARES validation repository; no remote artifact
 * or installed application is involved.
 */
class RepresentativeZeroCodeStarterIntegrationTest {
    @Test
    fun `representative FTC and FRC starter projects generate verify and simulate`() = runBlocking {
        assumeTrue(enabled())
        val workspace = File(
            System.getProperty("ares.workspaceRoot")
                ?: System.getenv("ARES_WORKSPACE_ROOT")
                ?: "..",
        ).canonicalFile
        val validationRepository = requireNotNull(
            System.getProperty("ares.validationRepository") ?: System.getenv("ARES_VALIDATION_REPOSITORY"),
        ) { "Set -Dares.validationRepository or ARES_VALIDATION_REPOSITORY." }
        val validationVersion = requireNotNull(
            System.getProperty("ares.validationVersion") ?: System.getenv("ARES_VALIDATION_VERSION"),
        ) { "Set -Dares.validationVersion or ARES_VALIDATION_VERSION." }
        val outputRoot = Files.createTempDirectory("ares-representative-zero-code-").toFile()

        try {
            requestedLeagues().forEach { league ->
                val source = File(workspace, if (league == League.FTC) "ARES-FTC-Starter" else "ARES-FRC-Starter")
                require(source.isDirectory) { "Missing local starter source: ${source.path}" }
                val project = File(outputRoot, league.name.lowercase())
                copySourceTree(source, project)
                seedRepresentativeRobot(project, league)
                runConsumerBuild(project, league, validationRepository, validationVersion)

                val generated = if (league == League.FTC) {
                    File(project, "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/generated/GeneratedAresProject.kt")
                } else {
                    File(project, "src/main/kotlin/org/aresfirst/starter/frc/generated/GeneratedAresProject.kt")
                }
                assertTrue(generated.isFile)
                assertTrue(generated.readText().contains("score-cycle"))
                assertTrue(project.walkTopDown().filter(File::isFile).any { file ->
                    file.extension == "kt" && file.readText().contains("Subsystems/elevator")
                })
            }
        } finally {
            outputRoot.deleteRecursively()
        }
    }

    private fun seedRepresentativeRobot(project: File, league: League) {
        val platform = if (league == League.FTC) SubsystemPlatform.FTC else SubsystemPlatform.FRC
        var nextCanId = 21
        var nextChannel = 5
        fun subsystem(template: SubsystemTemplate, id: String, type: String): SubsystemDocument {
            val base = SubsystemTemplates.create(template, id, type, platform)
            return base.copy(hardware = base.hardware.map { device ->
                val connection = when (league) {
                    League.FTC, League.XRP -> device.connection.copy(hardwareMapName = "$id-${device.hardwareId}")
                    League.FRC -> when (device.kind) {
                        SubsystemHardwareKind.MOTOR -> device.connection.copy(canId = nextCanId++)
                        SubsystemHardwareKind.POSITIONAL_SERVO,
                        SubsystemHardwareKind.CONTINUOUS_SERVO -> device.connection.copy(channel = nextChannel++)
                        else -> device.connection
                    }
                }
                device.copy(connection = connection)
            })
        }
        val elevator = subsystem(SubsystemTemplate.CURRENT_HOMED_MECHANISM, "elevator", "Elevator")
        val flywheel = subsystem(SubsystemTemplate.FLYWHEEL_SHOOTER, "flywheel", "Flywheel")
        val intakeBase = subsystem(SubsystemTemplate.INTAKE_CONVEYOR, "intake", "Intake").let { base ->
            base.copy(
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
        val wrist = subsystem(SubsystemTemplate.POSITIONAL_SERVO, "wrist", "Wrist")
        val elevatorMeasurement = elevator.stateFields.first { it.role == SubsystemFieldRole.MEASUREMENT }
        val intake = intakeBase.copy(interlocks = listOf(
            SubsystemInterlockDocument(
                interlockId = "elevator-clear",
                targetSubsystemUid = elevator.uid,
                targetFieldId = elevatorMeasurement.fieldId,
                comparison = InterlockComparison.GREATER_THAN,
                thresholdValue = 0.25,
                forbiddenZoneDescription = "Intake motion is blocked while the elevator is above clearance.",
            ),
        ))
        val subsystems = listOf(elevator, flywheel, intake, wrist)
        val ares = File(project, ".ares")
        val subsystemRoot = File(ares, "subsystems").apply { mkdirs() }
        subsystems.forEach { document ->
            File(subsystemRoot, "${document.documentId}.aressubsystem").writeText(SubsystemDocumentCodec.encode(document))
        }

        val transitionKeys = listOf("machine.ready", "machine.score", "machine.stow", "machine.recover")
        val catalogFile = File(ares, "action-catalog.json")
        val catalog = CapabilityCatalogCodec.decode(catalogFile.readText())
        catalogFile.writeText(CapabilityCatalogCodec.encode(catalog.copy(actions = catalog.actions + transitionKeys.map { key ->
            ActionDescriptor(key, key.substringAfter('.').replaceFirstChar(Char::uppercase), "Scoring-machine transition.")
        })))

        val superstructureRoot = File(ares, "superstructures").apply { mkdirs() }
        File(superstructureRoot, "scoring-machine.aressuperstructure").writeText(
            SuperstructureDocumentCodec.encode(superstructure(subsystems)),
        )
        val routineRoot = File(ares, "routines").apply { mkdirs() }
        val routine = RoutineDocument(
            documentId = "score-cycle",
            name = "Score cycle",
            description = "Homes, prepares, scores, and returns every mechanism to a safe state.",
            steps = listOf(
                RoutineStep.action("machine.ready"),
                RoutineStep.wait(0.15),
                RoutineStep.action("machine.score"),
                RoutineStep.wait(0.25),
                RoutineStep.action("machine.stow"),
            ),
        )
        File(routineRoot, "score-cycle.aresroutine").writeText(AresRoutineCodec.encode(routine))

        val autonomousFile = File(ares, "autonomous-catalog.json")
        val autonomous = AutonomousCatalogCodec.decode(autonomousFile.readText())
        autonomousFile.writeText(AutonomousCatalogCodec.encode(autonomous.copy(
            entries = autonomous.entries + AutonomousCatalogEntry(
                entryId = "score-cycle",
                displayName = "Representative score cycle",
                routineId = routine.documentId,
                startingPose = RoutinePose(0.0, 0.0, 0.0),
            ),
        )))

        val controlsFile = File(ares, "controls/driver.arescontrols")
        val controls = ControlSchemeCodec.decode(controlsFile.readText())
        controlsFile.writeText(ControlSchemeCodec.encode(controls.copy(bindings = controls.bindings + listOf(
            ControlBindingDocument(
                bindingId = "score-cycle",
                displayName = "Run score cycle",
                source = ControlSourceDocument(ControlSourceKind.BUTTON, "driver", listOf("a")),
                event = ControlEvent.PRESS,
                target = ControlTargetDocument(ControlTargetKind.ROUTINE, routine.documentId),
            ),
            ControlBindingDocument(
                bindingId = "home-elevator",
                displayName = "Home elevator",
                source = ControlSourceDocument(ControlSourceKind.BUTTON, "driver", listOf("b")),
                event = ControlEvent.PRESS,
                target = ControlTargetDocument(
                    ControlTargetKind.ACTION,
                    subsystemTargetActionKey("elevator", "homingRequested"),
                    arguments = mapOf("value" to "true"),
                ),
            ),
        ))))
    }

    private fun superstructure(subsystems: List<SubsystemDocument>): SuperstructureDocument {
        fun preset(id: String, active: Boolean) = SuperstructureStatePreset(
            stateId = id,
            subsystemTargets = subsystems.map { subsystem ->
                val target = subsystem.stateFields.first { it.role == SubsystemFieldRole.TARGET }
                val safe = target.defaultNumber ?: target.defaultInt?.toDouble() ?: 0.0
                SuperstructureSubsystemTarget(
                    target = SuperstructureFieldReference(subsystem.uid, target.uid),
                    constantDoubleValue = if (active) target.maximum?.coerceAtMost(1.0) ?: 0.5 else safe,
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
                StateTransitionEdge("stow-score", "SCORE", "STOW", actionKey = "machine.stow"),
                StateTransitionEdge("stow-ready", "READY", "STOW", actionKey = "machine.stow"),
                StateTransitionEdge("recover", "FAULT", "STOW", actionKey = "machine.recover"),
            ),
        )
    }

    private fun runConsumerBuild(project: File, league: League, repository: String, version: String) {
        val commonArguments = listOf(
            "-ParesVersion=$version",
            "-ParesRepository=${validationRepositoryUri(repository)}",
            "--refresh-dependencies",
            "--no-parallel",
            "--no-daemon",
            "--console=plain",
        )
        val starterTask = if (league == League.FTC) ":TeamCode:generateSubsystemStarters" else "generateSubsystemStarters"
        val command = mutableListOf("cmd.exe", "/c", "gradlew.bat", "generateAresProject")
        when (league) {
            League.FTC -> command += listOf(":TeamCode:verifyAresProject", ":TeamCode:testDebugUnitTest", ":simulator:test", ":TeamCode:assembleDebug")
            League.FRC -> command += listOf("verifyAresProject", "test", "build")
            League.XRP -> command += listOf("verifyAresProject", "test")
        }
        command += commonArguments
        val log = File(project, "build/representative-zero-code.log").apply { parentFile.mkdirs() }
        fun run(label: String, processCommand: List<String>) {
            val process = ProcessBuilder(processCommand)
                .directory(project)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .start()
            check(process.waitFor(12, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                "${league.name} representative $label timed out. See ${log.path}."
            }
            check(process.exitValue() == 0) {
                "${league.name} representative $label failed:\n${log.readLines().takeLast(100).joinToString("\n")}"
            }
        }
        run("starter generation", listOf("cmd.exe", "/c", "gradlew.bat", starterTask) + commonArguments)
        val expectedStarter = when (league) {
            League.FTC -> File(project, "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems/elevator/ElevatorState.kt")
            League.FRC -> File(
                project,
                "src/main/kotlin/org/aresfirst/starter/frc/generated/subsystems/elevator/ElevatorState.kt",
            )
            League.XRP -> File(project, "src/subsystems/elevator/elevator_state.py")
        }
        check(expectedStarter.isFile) {
            val generatedKotlin = project.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && "elevator" in it.invariantSeparatorsPath }
                .map { it.relativeTo(project).invariantSeparatorsPath }
                .toList()
            "${league.name} starter generation did not create ${expectedStarter.relativeTo(project)}. " +
                "Elevator Kotlin files: $generatedKotlin\n${log.readLines().takeLast(100).joinToString("\n")}"
        }
        run("build", command)
    }

    private fun copySourceTree(source: File, destination: File) {
        source.walkTopDown()
            .onEnter { directory -> directory == source || directory.name !in SKIPPED_DIRECTORIES }
            .forEach { entry ->
                val relative = entry.relativeTo(source)
                val target = File(destination, relative.path)
                if (entry.isDirectory) target.mkdirs() else entry.copyTo(target, overwrite = true)
            }
    }

    private fun enabled(): Boolean = (
        System.getProperty("ares.runRepresentativeZeroCodeE2E")
            ?: System.getenv("ARES_RUN_REPRESENTATIVE_ZERO_CODE_E2E")
        ).toBoolean()

    private fun requestedLeagues(): List<League> {
        val requested = System.getProperty("ares.representativeLeagues")
            ?: System.getenv("ARES_REPRESENTATIVE_LEAGUES")
            ?: return listOf(League.FTC, League.FRC)
        val parsed = requested.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { value -> League.valueOf(value.uppercase()) }
            .distinct()
        require(parsed.isNotEmpty()) { "ARES_REPRESENTATIVE_LEAGUES must name FTC, FRC, or both." }
        return parsed
    }

    private companion object {
        val SKIPPED_DIRECTORIES = setOf(".git", ".gradle", "build", ".idea")

        fun validationRepositoryUri(value: String): String {
            val file = if (value.startsWith("file:", ignoreCase = true)) File(URI(value)) else File(value)
            require(file.isDirectory) { "Representative validation repository does not exist: $value" }
            return file.canonicalFile.toURI().toASCIIString()
        }
    }
}
