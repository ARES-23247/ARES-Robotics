package com.ares.analytics.service.project

import com.ares.analytics.BuildConfig
import com.ares.analytics.service.AresGenerationPhase
import com.ares.analytics.service.BuildExecutionPhase
import com.ares.analytics.service.ManagedToolchainPaths
import com.ares.analytics.service.ProjectBuildService
import com.ares.analytics.service.versioncontrol.ProjectArchiveExporter
import com.ares.analytics.shared.models.League
import com.ares.analytics.util.Sha256
import com.areslib.catalog.CapabilityCatalogCodec
import com.areslib.controls.ControlSchemeCodec
import com.areslib.controls.ControllerInputPlatform
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.routine.AresRoutineCodec
import com.areslib.routine.AutonomousCatalogCodec
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutinePose
import com.areslib.routine.RoutineStep
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.tuning.TuningProfileDocumentCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end consumer round-trip proof for a real BioBuzz project.
 *
 * Exercises create/open -> save configuration changes -> export -> safe extract ->
 * reopen in fresh session -> assert independent values -> keep byte-identical USER-OWNED extension ->
 * regenerate with real Gradle wrapper -> multi-file determinism -> verification build compile & tests ->
 * prove reopened configuration produces intended behavior in generated runtime and simulated IO.
 */
class BiobuzzConsumerRoundtripIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val temporaryDirectory get() = temporaryFolder.root.toPath()
    private val exporter = ProjectArchiveExporter()

    private val candidateRepoUri = System.getProperty("ares.repository.uri")
        ?: "file:///C:/Users/david/dev/robotics/ARES-Robotics/.codex-validation/reviewed-project-roundtrip/ARESLib-Kotlin/build/release-repository"
    private val candidateVersion = System.getProperty("ares.version")
        ?: "19.1.3-rc.roundtrip.dbb5b9f.1"

    @Test
    fun `biobuzz complete consumer roundtrip proves settings, user extensions, determinism, and simulated IO`() = runBlocking {
        val workspace = temporaryDirectory.resolve("biobuzz-workspace").toFile().apply { mkdirs() }
        val project = extractBundledBioBuzz(workspace)
        configureSdk(project)
        assertTrue(File(project, ".ares/project.json").isFile)

        // 1. Open in initial ProjectSession
        val initialSession = ProjectSession()
        val initialSnapshot = initialSession.snapshot(project.path, ControllerInputPlatform.FTC)
        val initialDocs = initialSnapshot.documents.query
        assertEquals("biobuzz-reference", initialDocs.metadata?.projectId)
        assertEquals(3.6576, initialDocs.metadata?.fieldLengthMeters)

        // 2. Save meaningful configuration changes across:
        //    (A) Field settings (3.58m x 3.58m)
        val metadataFile = File(project, ".ares/project.json")
        val originalMetadata = AresProjectMetadataCodec.decode(metadataFile.readText())
        val modifiedMetadata = originalMetadata.copy(fieldLengthMeters = 3.58, fieldWidthMeters = 3.58)
        metadataFile.writeText(AresProjectMetadataCodec.encode(modifiedMetadata))

        //    (B) Subsystem / Hardware settings (modify feedbackTimeoutMs to 120ms)
        val intakeFile = File(project, ".ares/subsystems/biobuzz-intake.aressubsystem")
        val originalIntake = SubsystemDocumentCodec.decode(intakeFile.readText())
        val modifiedIntake = originalIntake.copy(
            safety = originalIntake.safety.copy(feedbackTimeoutMs = 120)
        )
        intakeFile.writeText(SubsystemDocumentCodec.encode(modifiedIntake))

        //    (C) Tuning settings (heading kP = 2.40)
        val tuningFile = File(project, ".ares/tuning/simulation.arestuning")
        val originalTuningText = tuningFile.readText()
        assertTrue(originalTuningText.contains("ftc.drive.heading.kp"))
        val modifiedTuningText = originalTuningText.replace(
            Regex("""("parameterUid"\s*:\s*"ftc\.drive\.heading\.kp"[\s\S]*?"doubleValue"\s*:\s*)1\.8"""),
            "$1" + "2.4"
        )
        check(modifiedTuningText != originalTuningText) { "Tuning replacement did not modify file content" }
        tuningFile.writeText(modifiedTuningText)

        //    (D) Control settings (deadband = 0.10)
        val controlsFile = File(project, ".ares/controls/driver.arescontrols")
        val originalControls = ControlSchemeCodec.decode(controlsFile.readText())
        val modifiedBindings = originalControls.bindings.map { binding ->
            if (binding.bindingId == "feed-ball") {
                binding.copy(source = binding.source.copy(
                    transform = binding.source.transform?.copy(deadband = 0.10)
                ))
            } else binding
        }
        controlsFile.writeText(ControlSchemeCodec.encode(originalControls.copy(bindings = modifiedBindings)))

        //    (E) Autonomous catalog settings (create routine and register autonomous entry)
        val routineFile = File(project, ".ares/routines/sample-routine.aresroutine").apply {
            parentFile.mkdirs()
            writeText(
                AresRoutineCodec.encode(
                    RoutineDocument(
                        documentId = "sample-routine",
                        name = "Sample Routine",
                        steps = listOf(RoutineStep.wait(1.0)),
                    )
                )
            )
        }
        val autoCatalogFile = File(project, ".ares/autonomous-catalog.json")
        val originalCatalog = AutonomousCatalogCodec.decode(autoCatalogFile.readText())
        val modifiedCatalog = originalCatalog.copy(
            entries = listOf(
                AutonomousCatalogEntry(
                    entryId = "custom-biobuzz-auto",
                    displayName = "Custom BioBuzz Autonomous",
                    description = "Configured autonomous routine entry",
                    routineId = "sample-routine",
                    startingPose = RoutinePose(1.2, 0.8, 0.0),
                    sortOrder = 42,
                )
            )
        )
        autoCatalogFile.writeText(AutonomousCatalogCodec.encode(modifiedCatalog))

        // 3. Keep a working USER-OWNED extension in TeamRobotExtensions.kt
        val teamExtensionsFile = File(project, "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/extensions/TeamRobotExtensions.kt")
        assertTrue(teamExtensionsFile.isFile)
        val customizedTeamExtensions = buildString {
            appendLine("// ARES OWNERSHIP: USER-OWNED")
            appendLine("package org.firstinspires.ftc.teamcode.extensions")
            appendLine()
            appendLine("object TeamRobotExtensions {")
            appendLine("    fun customIntakeMultiplier(): Double = 1.25")
            appendLine("    fun customDriverTag(): String = \"BIOBUZZ_PRESERVED_USER_EXTENSION\"")
            appendLine("}")
        }
        teamExtensionsFile.writeText(customizedTeamExtensions)

        // Add consumer simulation test in simulator/ to prove USER-OWNED extension compiles and executes
        val consumerSimTestFile = File(project, "simulator/src/test/kotlin/org/firstinspires/ftc/teamcode/BiobuzzReopenedConsumerSimulationTest.kt").apply {
            parentFile.mkdirs()
            writeText(buildString {
                appendLine("package org.firstinspires.ftc.teamcode")
                appendLine()
                appendLine("import com.areslib.ftc.FtcBaseRobot")
                appendLine("import com.areslib.networktables.NT4Instance")
                appendLine("import com.areslib.sim.model.MecanumRobotDouble")
                appendLine("import com.areslib.sim.model.SimDcMotorEx")
                appendLine("import com.areslib.sim.opmode.SimOpModeRunner")
                appendLine("import com.areslib.util.RobotClock")
                appendLine("import org.firstinspires.ftc.teamcode.extensions.TeamRobotExtensions")
                appendLine("import org.firstinspires.ftc.teamcode.opmodes.ARESStarterTeleOp")
                appendLine("import org.junit.After")
                appendLine("import org.junit.Assert.assertEquals")
                appendLine("import org.junit.Assert.assertNotNull")
                appendLine("import org.junit.Assert.assertTrue")
                appendLine("import org.junit.Test")
                appendLine("import kotlin.math.abs")
                appendLine()
                appendLine("class BiobuzzReopenedConsumerSimulationTest {")
                appendLine("    @After")
                appendLine("    fun cleanUp() {")
                appendLine("        NT4Instance.defaultInstance.closeServer()")
                appendLine("        RobotClock.useSystemTime()")
                appendLine("    }")
                appendLine()
                appendLine("    @Test")
                appendLine("    fun `reopened configuration drives simulated IO and validates user extension`() {")
                appendLine("        val extensionMultiplier = TeamRobotExtensions.customIntakeMultiplier()")
                appendLine("        assertEquals(1.25, extensionMultiplier, 1e-6)")
                appendLine("        assertEquals(\"BIOBUZZ_PRESERVED_USER_EXTENSION\", TeamRobotExtensions.customDriverTag())")
                appendLine()
                appendLine("        RobotClock.useMockTime(1_000L)")
                appendLine("        val robotDouble = MecanumRobotDouble()")
                appendLine("        val lifecycle = requireNotNull(")
                appendLine("            SimOpModeRunner.createOpModeInstance(null, ARESStarterTeleOp::class.java.name),")
                appendLine("        )")
                appendLine("        try {")
                appendLine("            lifecycle.initialize(robotDouble.hardwareMap)")
                appendLine("            assertNotNull(FtcBaseRobot.activeInstance)")
                appendLine("            lifecycle.tick()")
                appendLine("            lifecycle.start()")
                appendLine()
                appendLine("            lifecycle.gamepad1.left_stick_y = -1.0f")
                appendLine("            RobotClock.useMockTime(1_020L)")
                appendLine("            lifecycle.tick()")
                appendLine()
                appendLine("            val drivePowers = listOf(robotDouble.fl, robotDouble.fr, robotDouble.rl, robotDouble.rr).map { it.power }")
                appendLine("            assertTrue(\"Drive motors must respond to joystick\", drivePowers.any { abs(it) > 0.01 })")
                appendLine()
                appendLine("            lifecycle.gamepad1.a = true")
                appendLine("            RobotClock.useMockTime(1_040L)")
                appendLine("            lifecycle.tick()")
                appendLine()
                appendLine("            val intakeMotor = robotDouble.hardwareMap.get(SimDcMotorEx::class.java, \"intake\")")
                appendLine("            assertTrue(\"Intake motor power must be commanded\", intakeMotor.power > 0.01)")
                appendLine("        } finally {")
                appendLine("            lifecycle.stop()")
                appendLine("        }")
                appendLine("    }")
                appendLine("}")
            })
        }

        // 4. Export project via ProjectArchiveExporter
        val exportArchive = temporaryDirectory.resolve("biobuzz-export.aresproject.zip").toFile()
        val exportResult = exporter.export(project.path, exportArchive.path)
        assertTrue(exportArchive.isFile)
        assertTrue(exportResult.fileCount > 0)

        // 5. Extract safely into a clean destination
        val extractedDir = temporaryDirectory.resolve("biobuzz-extracted").toFile()
        val extractedProject = exporter.extract(exportArchive.path, extractedDir.path)
        assertEquals(extractedDir.canonicalPath, extractedProject.canonicalPath)
        configureSdk(extractedProject)

        // 6. Reopen in a fresh ProjectSession and assert independent values
        val extractedSession = ProjectSession()
        val extractedSnapshot = extractedSession.snapshot(extractedProject.path, ControllerInputPlatform.FTC)
        val extractedDocs = extractedSnapshot.documents.query

        // Independent assertions:
        assertEquals(3.58, extractedDocs.metadata?.fieldLengthMeters, "Field length must retain saved value")
        assertEquals(3.58, extractedDocs.metadata?.fieldWidthMeters, "Field width must retain saved value")

        val extractedIntake = extractedDocs.subsystems.single { it.documentId == "biobuzz-intake" }
        assertEquals(120, extractedIntake.safety.feedbackTimeoutMs, "Intake feedback timeout must retain saved value")

        val extractedTuning = extractedDocs.tuningProfiles.single { it.profileId == "simulation" }
        val headingKp = extractedTuning.values.single { it.parameterUid == "ftc.drive.heading.kp" }.value.doubleValue
        assertEquals(2.4, headingKp, "Tuning parameter heading.kp must retain saved value")

        val extractedControls = extractedDocs.controlSchemes.single { it.documentId == "driver" }
        val feedBinding = extractedControls.bindings.single { it.bindingId == "feed-ball" }
        assertEquals(0.10, feedBinding.source.transform?.deadband, "Control deadband must retain saved value")

        val extractedCatalog = extractedDocs.autonomousCatalog
        val autoEntry = extractedCatalog?.entries.orEmpty().single { it.entryId == "custom-biobuzz-auto" }
        assertEquals(42, autoEntry.sortOrder, "Autonomous catalog entry sortOrder must retain saved value")
        assertEquals(1.2, autoEntry.startingPose.xMeters, 1e-6, "Autonomous catalog entry starting pose must retain saved value")

        val extractedRoutine = extractedDocs.routines.single { it.documentId == "sample-routine" }
        assertEquals("Sample Routine", extractedRoutine.name, "Custom routine must retain saved value")

        val extractedExtensionFile = File(extractedProject, "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/extensions/TeamRobotExtensions.kt")
        assertTrue(extractedExtensionFile.isFile)
        assertEquals(customizedTeamExtensions, extractedExtensionFile.readText(), "USER-OWNED extension must be byte-identical")

        // 7. Regenerate Kotlin source via ProjectBuildService with real Gradle wrapper
        val buildService = ProjectBuildService(
            aresRepositoryUri = candidateRepoUri,
            aresVersion = candidateVersion,
        )
        val buildLogs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val logJob = launch(Dispatchers.IO) {
            buildService.buildOutput.collect { line ->
                buildLogs.add(line)
            }
        }
        try {
            buildService.generateAresProject(extractedProject.path, League.FTC)
            awaitGenerationFinished(buildService)
            assertEquals(AresGenerationPhase.SUCCEEDED, buildService.aresGenerationState.value.phase)

            // 8. Determinism check: capture all generated files across directories
            val generatedMainDir = File(extractedProject, "TeamCode/build/generated/ares/main/kotlin")
            val generatedTestDir = File(extractedProject, "TeamCode/build/generated/ares/test/kotlin")
            val generatedDrivebaseDir = File(extractedProject, "TeamCode/build/generated/ares/drivebase/kotlin")
            val verificationManifest = File(extractedProject, "build/generated/ares/verification/ares-project-verification.json")
            val runtimeOutput = File(generatedMainDir, "org/firstinspires/ftc/teamcode/generated/GeneratedAresProject.kt")

            assertTrue(runtimeOutput.isFile, "Generated runtime output must exist")
            assertTrue(verificationManifest.isFile, "Verification manifest must exist")

            val capturedFilesFirstRun = listOf(generatedMainDir, generatedTestDir, generatedDrivebaseDir)
                .flatMap { dir -> dir.walkTopDown().filter(File::isFile).toList() }
                .associate { it.relativeTo(extractedProject).invariantSeparatorsPath to it.readText() }
                .toMutableMap()
            capturedFilesFirstRun[verificationManifest.relativeTo(extractedProject).invariantSeparatorsPath] = verificationManifest.readText()
            assertTrue(capturedFilesFirstRun.size > 2, "Expected multiple generated files")

            // Re-run generation to prove strict multi-file determinism
            buildService.generateAresProject(extractedProject.path, League.FTC)
            awaitGenerationFinished(buildService)
            assertEquals(AresGenerationPhase.SUCCEEDED, buildService.aresGenerationState.value.phase)

            val capturedFilesSecondRun = listOf(generatedMainDir, generatedTestDir, generatedDrivebaseDir)
                .flatMap { dir -> dir.walkTopDown().filter(File::isFile).toList() }
                .associate { it.relativeTo(extractedProject).invariantSeparatorsPath to it.readText() }
                .toMutableMap()
            capturedFilesSecondRun[verificationManifest.relativeTo(extractedProject).invariantSeparatorsPath] = verificationManifest.readText()

            assertEquals(
                capturedFilesFirstRun.keys.sorted(),
                capturedFilesSecondRun.keys.sorted(),
                "Set of generated files must be identical between runs"
            )
            capturedFilesFirstRun.forEach { (path, content) ->
                assertEquals(content, capturedFilesSecondRun[path], "File $path must be byte-for-byte identical across runs")
            }

            // Verify generated runtime reflects the reopened configuration
            val generatedSource = runtimeOutput.readText()
            assertTrue(generatedSource.contains("3.58"), "Generated Kotlin must contain reopened field dimension 3.58")
            assertEquals(customizedTeamExtensions, extractedExtensionFile.readText(), "USER-OWNED extension must not be altered by codegen")

            // 9. Compile and run consumer robot and simulator tests via verification build
            buildService.runBuild(extractedProject.path, League.FTC)
            awaitBuildFinished(buildService)

            val executionState = buildService.processState.value.buildExecution
            assertEquals(
                BuildExecutionPhase.SUCCEEDED,
                executionState.phase,
                "Verification build must succeed: ${executionState.message}\nRecent output:\n" + buildLogs.takeLast(60).joinToString("\n")
            )
            assertEquals(0, executionState.exitCode, "Verification build exit code must be 0")
        } finally {
            logJob.cancel()
            buildService.shutdownAndJoin()
        }
    }

    private suspend fun awaitGenerationFinished(service: ProjectBuildService, timeoutMs: Long = 180_000L) = withTimeout(timeoutMs) {
        while (!service.processState.value.buildRunning && service.aresGenerationState.value.phase != AresGenerationPhase.RUNNING) {
            delay(10)
        }
        while (service.processState.value.buildRunning || service.aresGenerationState.value.phase == AresGenerationPhase.RUNNING) {
            delay(20)
        }
    }

    private suspend fun awaitBuildFinished(service: ProjectBuildService, timeoutMs: Long = 240_000L) = withTimeout(timeoutMs) {
        while (!service.processState.value.buildRunning && service.processState.value.buildExecution.phase != BuildExecutionPhase.RUNNING) {
            delay(10)
        }
        while (service.processState.value.buildRunning || service.processState.value.buildExecution.phase == BuildExecutionPhase.RUNNING) {
            delay(20)
        }
    }

    private fun configureSdk(project: File) {
        val sdk = ManagedToolchainPaths.resolveAndroidSdk() ?: File(System.getProperty("user.home"), "AppData/Local/Android/Sdk")
        if (sdk.isDirectory) {
            File(project, "local.properties").writeText("sdk.dir=${sdk.path.replace('\\', '/')}\n")
        }
    }

    private fun extractBundledBioBuzz(targetDirectory: File): File {
        val archiveName = "ARES-BIOBUZZ-Example-${BuildConfig.BIOBUZZ_EXAMPLE_VERSION}"
        val resourceStream = requireNotNull(javaClass.getResourceAsStream("/project-templates/$archiveName.zip")) {
            "Missing bundled resource: /project-templates/$archiveName.zip"
        }
        ZipInputStream(resourceStream.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = File(targetDirectory, entry.name).canonicalFile
                require(output.toPath().startsWith(targetDirectory.canonicalFile.toPath()))
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile.mkdirs()
                    output.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
        return File(targetDirectory, archiveName)
    }
}
