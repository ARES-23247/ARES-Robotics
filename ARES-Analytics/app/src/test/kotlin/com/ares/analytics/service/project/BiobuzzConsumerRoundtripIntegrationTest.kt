package com.ares.analytics.service.project

import com.ares.analytics.BuildConfig
import com.ares.analytics.service.AresGenerationPhase
import com.ares.analytics.service.BuildExecutionPhase
import com.ares.analytics.service.versioncontrol.ProjectArchiveExporter
import com.ares.analytics.shared.models.League
import com.ares.analytics.util.Sha256
import com.areslib.catalog.CapabilityCatalogCodec
import com.areslib.controls.ControlBindingDocument
import com.areslib.controls.ControlEvent
import com.areslib.controls.ControlSchemeCodec
import com.areslib.controls.ControlSourceDocument
import com.areslib.controls.ControlSourceKind
import com.areslib.controls.ControlTargetDocument
import com.areslib.controls.ControlTargetKind
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    @Test
    fun `biobuzz complete consumer roundtrip proves settings, user extensions, determinism, and simulated IO`() = runBlocking {
        val workspace = temporaryDirectory.resolve("biobuzz-workspace").toFile().apply { mkdirs() }
        val project = extractBundledBioBuzz(workspace)
        configureConsumerSdk(project)
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
        saveConsumerMetadata(project, modifiedMetadata)

        //    (B) Subsystem / Hardware settings (modify feedbackTimeoutMs to 120ms)
        val intakeFile = File(project, ".ares/subsystems/biobuzz-intake.aressubsystem")
        val originalIntake = SubsystemDocumentCodec.decode(intakeFile.readText())
        val modifiedIntake = originalIntake.copy(
            safety = originalIntake.safety.copy(feedbackTimeoutMs = 120)
        )
        assertIs<ProjectSessionMutationResult.Applied<*>>(initialSession.saveSubsystem(
            initialSession.snapshot(project.path, ControllerInputPlatform.FTC, forceReload = true).revision, modifiedIntake,
        ))

        //    (C) Tuning settings (heading kP = 2.40)
        saveConsumerHeadingGain(initialSession, project, 2.4)

        //    (D) Control settings (deadband = 0.10, bind Y button to enable heading lock)
        val controlsFile = File(project, ".ares/controls/driver.arescontrols")
        val originalControls = ControlSchemeCodec.decode(controlsFile.readText())
        val modifiedBindings = originalControls.bindings.map { binding ->
            if (binding.bindingId == "feed-ball") {
                binding.copy(source = binding.source.copy(
                    transform = binding.source.transform?.copy(deadband = 0.10)
                ))
            } else binding
        }.toMutableList().apply {
            add(
                ControlBindingDocument(
                    bindingId = "enable-heading-lock",
                    displayName = "Enable Heading Lock",
                    source = ControlSourceDocument(
                        kind = ControlSourceKind.BUTTON,
                        controllerSlot = "driver",
                        controlIds = listOf("y"),
                    ),
                    event = ControlEvent.PRESS,
                    target = ControlTargetDocument(
                        kind = ControlTargetKind.ACTION,
                        key = "drivetrain.headingLock.enable",
                    ),
                    enabled = true,
                )
            )
        }
        assertIs<ProjectSessionMutationResult.Applied<*>>(initialSession.saveControls(
            initialSession.snapshot(project.path, ControllerInputPlatform.FTC, forceReload = true).revision,
            emptyList(), listOf(originalControls.copy(bindings = modifiedBindings)),
        ))

        //    (E) Autonomous catalog settings (create routine and register autonomous entry)
        val routine = RoutineDocument(documentId = "sample-routine", name = "Sample Routine", steps = listOf(RoutineStep.wait(1.0)))
        val autoEntryToSave = AutonomousCatalogEntry(
            entryId = "custom-biobuzz-auto", displayName = "Custom BioBuzz Autonomous",
            description = "Configured autonomous routine entry", routineId = routine.documentId,
            startingPose = RoutinePose(1.2, 0.8, 0.0), sortOrder = 42,
        )
        assertIs<ProjectSessionMutationResult.Applied<*>>(initialSession.saveRoutine(
            initialSession.snapshot(project.path, ControllerInputPlatform.FTC, forceReload = true).revision,
            routine, autoEntryToSave,
        ))

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

        // Add consumer simulation test in simulator/ to prove USER-OWNED extension, heading gains, and feedback timeout
        val consumerSimTestFile = File(project, "simulator/src/test/kotlin/org/firstinspires/ftc/teamcode/BiobuzzReopenedConsumerSimulationTest.kt").apply {
            parentFile.mkdirs()
            writeText(buildString {
                appendLine("package org.firstinspires.ftc.teamcode")
                appendLine()
                appendLine("import com.areslib.Store")
                appendLine("import com.areslib.action.RobotAction")
                appendLine("import com.areslib.control.tuning.PIDFCoefficients")
                appendLine("import com.areslib.ftc.FtcBaseRobot")
                appendLine("import com.areslib.ftc.FtcMecanumRobot")
                appendLine("import com.areslib.math.geometry.Pose2d")
                appendLine("import com.areslib.networktables.NT4Instance")
                appendLine("import com.areslib.sim.model.MecanumRobotDouble")
                appendLine("import com.areslib.sim.model.SimDcMotorEx")
                appendLine("import com.areslib.sim.opmode.SimOpModeRunner")
                appendLine("import com.areslib.state.RobotState")
                appendLine("import com.areslib.state.SuperstructureState")
                appendLine("import com.areslib.util.RobotClock")
                appendLine("import org.firstinspires.ftc.teamcode.extensions.TeamRobotExtensions")
                appendLine("import org.firstinspires.ftc.teamcode.generated.GeneratedAresProject")
                appendLine("import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresDrivebaseConfig")
                appendLine("import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresTuningConfig")
                appendLine("import org.firstinspires.ftc.teamcode.opmodes.ARESStarterTeleOp")
                appendLine("import org.firstinspires.ftc.teamcode.subsystems.biobuzz_intake.BiobuzzIntakeController")
                appendLine("import org.firstinspires.ftc.teamcode.subsystems.biobuzz_intake.BiobuzzIntakeState")
                appendLine("import org.firstinspires.ftc.teamcode.subsystems.biobuzz_intake.BiobuzzIntakeSubsystem")
                appendLine("import org.firstinspires.ftc.teamcode.subsystems.biobuzz_intake.FtcBiobuzzIntakeIO")
                appendLine("import org.junit.After")
                appendLine("import org.junit.Assert.assertEquals")
                appendLine("import org.junit.Assert.assertFalse")
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
                appendLine("        assertEquals(3.58, GeneratedAresProject.FIELD_LENGTH_METERS, 1e-9)")
                appendLine("        assertEquals(3.58, GeneratedAresProject.FIELD_WIDTH_METERS, 1e-9)")
                appendLine("        val autonomous = GeneratedAresProject.autonomousEntries.single { it.entryId == \"custom-biobuzz-auto\" }")
                appendLine("        assertEquals(1.2, autonomous.startingPose.xMeters, 1e-9)")
                appendLine("        assertEquals(1, GeneratedAresProject.runtimeDefinition.routines.getValue(\"sample-routine\").steps.size)")
                appendLine("    }")
                appendLine()
                appendLine("    @Test")
                appendLine("    fun `saved heading gains govern controller output below saturation with live safe apply policy`() {")
                appendLine("        assertEquals(2.40, GeneratedAresTuningConfig.Parameters.DRIVE_HEADINGKP, 1e-6)")
                appendLine("        assertEquals(0.0, GeneratedAresTuningConfig.Parameters.DRIVE_HEADINGKD, 1e-6)")
                appendLine("        RobotClock.useMockTime(1_000L)")
                appendLine("        val robotDouble = MecanumRobotDouble()")
                appendLine("        val lifecycle = requireNotNull(")
                appendLine("            SimOpModeRunner.createOpModeInstance(null, ARESStarterTeleOp::class.java.name),")
                appendLine("        )")
                appendLine("        try {")
                appendLine("            lifecycle.initialize(robotDouble.hardwareMap)")
                appendLine("            assertNotNull(FtcBaseRobot.activeInstance)")
                appendLine("            val robot = FtcBaseRobot.activeInstance as FtcMecanumRobot")
                appendLine("            lifecycle.gamepad1.id = 1")
                appendLine("            lifecycle.tick()")
                appendLine("            lifecycle.start()")
                appendLine("            robot.resetPose(Pose2d())")
                appendLine()
                appendLine("            // Enable heading lock via bound Y button")
                appendLine("            lifecycle.gamepad1.y = true")
                appendLine("            RobotClock.useMockTime(1_010L)")
                appendLine("            lifecycle.tick()")
                appendLine("            lifecycle.gamepad1.y = false")
                appendLine()
                appendLine("            // Initial tick with zero error establishes heading target = 0.0 rad")
                appendLine("            robotDouble.updateSensors(0.02, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)")
                appendLine("            RobotClock.useMockTime(1_030L)")
                appendLine("            Thread.sleep(35)")
                appendLine("            lifecycle.tick()")
                appendLine("            assertEquals(0.0, robot.store.state.drive.headingLockTargetRadians ?: Double.NaN, 1e-4)")
                appendLine()
                appendLine("            // Inject realistic heading error: robot heading = -0.10 rad, target = 0.0 rad (error = +0.10 rad)")
                appendLine("            // Independent calculation with saved active profile gain kP1 = 2.40 (pure proportional, kD = 0.0):")
                appendLine("            // u1 = 2.40 * 0.10 = 0.24 rad/s (below saturation limit 1.393 rad/s, above deadband 0.0436 rad)")
                appendLine("            robotDouble.updateSensors(0.02, 0.0, 0.0, 0.0, 0.0, 0.0, -0.10)")
                appendLine("            RobotClock.useMockTime(1_050L)")
                appendLine("            Thread.sleep(35)")
                appendLine("            lifecycle.tick()")
                appendLine()
                appendLine("            // Next tick: updateProjectControls consumes fresh heading -0.10 rad (error = +0.10 rad)")
                appendLine("            RobotClock.useMockTime(1_070L)")
                appendLine("            Thread.sleep(35)")
                appendLine("            lifecycle.tick()")
                appendLine("            val omega1 = robot.store.state.drive.angularVelocityRadiansPerSecond")
                appendLine("            assertEquals(0.24, omega1, 1e-3)")
                appendLine("            val pFr1 = robotDouble.fr.power")
                appendLine("            val pFl1 = robotDouble.fl.power")
                appendLine("            assertTrue(\"Front-right wheel power must be positive for positive CCW rotation\", pFr1 > 0.01)")
                appendLine("            assertEquals(-pFr1, pFl1, 1e-3)")
                appendLine()
                appendLine("            // Live update to second gain kP2 = 1.80 per LIVE_SAFE apply policy")
                appendLine("            robot.store.dispatch(RobotAction.UpdateTuningState(")
                appendLine("                robot.store.state.tuning.let {")
                appendLine("                    it.copy(drive = it.drive.copy(headingGains = PIDFCoefficients(1.8, 0.0, 0.0)))")
                appendLine("                }")
                appendLine("            ))")
                appendLine("            // Same error +0.10 rad under secondary gain kP2 = 1.80:")
                appendLine("            // u2 = 1.80 * 0.10 = 0.18 rad/s (below saturation limit)")
                appendLine("            RobotClock.useMockTime(1_090L)")
                appendLine("            Thread.sleep(35)")
                appendLine("            lifecycle.tick()")
                appendLine("            val omega2 = robot.store.state.drive.angularVelocityRadiansPerSecond")
                appendLine("            assertEquals(0.18, omega2, 1e-3)")
                appendLine("            val pFr2 = robotDouble.fr.power")
                appendLine("            val pFl2 = robotDouble.fl.power")
                appendLine("            assertTrue(\"Front-right wheel power must be positive for positive CCW rotation under kP2\", pFr2 > 0.01)")
                appendLine("            assertEquals(-pFr2, pFl2, 1e-3)")
                appendLine()
                appendLine("            // Linearity below saturation: controller output ratio must strictly match gain ratio (2.4 / 1.8 = 4 / 3)")
                appendLine("            assertEquals(2.4 / 1.8, omega1 / omega2, 1e-3)")
                appendLine("            assertEquals(4.0 / 3.0, omega1 / omega2, 1e-3)")
                appendLine("            assertTrue(\"Higher heading gain must produce strictly higher angular velocity\", omega1 > omega2)")
                appendLine("            assertTrue(\"Higher heading gain must produce strictly higher wheel drive effort\", pFr1 > pFr2)")
                appendLine("        } finally {")
                appendLine("            lifecycle.stop()")
                appendLine("        }")
                appendLine("    }")
                appendLine()
                appendLine("    @Test")
                appendLine("    fun `saved mechanism feedback timeout governs output and recovers per rearm policy`() {")
                appendLine("        val robotDouble = MecanumRobotDouble()")
                appendLine("        val intakeMotor = robotDouble.hardwareMap.get(SimDcMotorEx::class.java, \"intake\")")
                appendLine("        val intakeIO = FtcBiobuzzIntakeIO(robotDouble.hardwareMap)")
                appendLine("        val controller = BiobuzzIntakeController(intakeIO)")
                appendLine("        val initialRawState = BiobuzzIntakeState(intakeVoltage = 12.0, configurationHealthy = true, homed = true, calibrated = true, currentReadingValid = true)")
                appendLine()
                appendLine("        // 1. Missing initial feedback: before refresh(), feedbackValid is false; output must neutralize to 0.0")
                appendLine("        assertFalse(intakeIO.feedbackValid)")
                appendLine("        controller.update(initialRawState, 1.0)")
                appendLine("        assertEquals(0.0, intakeMotor.power, 1e-6)")
                appendLine()
                appendLine("        // 2. Fresh feedback establishes nonzero output")
                appendLine("        RobotClock.useMockTime(2_000L)")
                appendLine("        intakeIO.refresh()")
                appendLine("        assertTrue(intakeIO.feedbackValid)")
                appendLine("        assertEquals(2_000L, intakeIO.feedbackTimestampMs)")
                appendLine("        val freshState = initialRawState.copy(feedbackValid = true, feedbackTimestampMs = 2_000L)")
                appendLine("        controller.update(freshState, 1.0)")
                appendLine("        assertEquals(1.0, intakeMotor.power, 1e-6)")
                appendLine()
                appendLine("        // 3. Enable / disable toggle: disabling neutralizes, re-enabling activates")
                appendLine("        controller.update(freshState.copy(intakeVoltage = 0.0), 1.0)")
                appendLine("        assertEquals(0.0, intakeMotor.power, 1e-6)")
                appendLine("        controller.update(freshState, 1.0)")
                appendLine("        assertEquals(1.0, intakeMotor.power, 1e-6)")
                appendLine()
                appendLine("        // 4. Exact inclusive threshold boundary: at t = 2_000L + 120L = 2_120L, feedback is still fresh")
                appendLine("        RobotClock.useMockTime(2_120L)")
                appendLine("        controller.update(freshState, 1.0)")
                appendLine("        assertEquals(1.0, intakeMotor.power, 1e-6)")
                appendLine()
                appendLine("        // 5. Across timeout boundary: at t = 2_000L + 121L = 2_121L, feedback is stale; output neutralizes")
                appendLine("        RobotClock.useMockTime(2_121L)")
                appendLine("        controller.update(freshState, 1.0)")
                appendLine("        assertEquals(0.0, intakeMotor.power, 1e-6)")
                appendLine()
                appendLine("        // 6. Redux state boundary: Subsystem.readSensors marks feedbackValid false at 121ms")
                appendLine("        val store = Store(RobotState(superstructure = SuperstructureState(subsystems = mapOf(BiobuzzIntakeSubsystem.ID to freshState))))")
                appendLine("        val subsystem = BiobuzzIntakeSubsystem(intakeIO)")
                appendLine("        subsystem.readSensors(store, 2_120L)")
                appendLine("        assertTrue(BiobuzzIntakeSubsystem.state(store.state).feedbackValid)")
                appendLine("        subsystem.readSensors(store, 2_121L)")
                appendLine("        assertFalse(BiobuzzIntakeSubsystem.state(store.state).feedbackValid)")
                appendLine()
                appendLine("        // 7. Recovery according to documented rearm policy: fresh feedback safely restores output")
                appendLine("        RobotClock.useMockTime(2_200L)")
                appendLine("        intakeIO.refresh()")
                appendLine("        assertEquals(2_200L, intakeIO.feedbackTimestampMs)")
                appendLine("        subsystem.readSensors(store, 2_200L)")
                appendLine("        assertTrue(BiobuzzIntakeSubsystem.state(store.state).feedbackValid)")
                appendLine("        val recoveredState = BiobuzzIntakeSubsystem.state(store.state).copy(intakeVoltage = 12.0)")
                appendLine("        controller.update(recoveredState, 1.0)")
                appendLine("        assertEquals(1.0, intakeMotor.power, 1e-6)")
                appendLine()
                appendLine("        // 8. Full OpMode lifecycle integration")
                appendLine("        val lifecycle = requireNotNull(")
                appendLine("            SimOpModeRunner.createOpModeInstance(null, ARESStarterTeleOp::class.java.name),")
                appendLine("        )")
                appendLine("        try {")
                appendLine("            lifecycle.initialize(robotDouble.hardwareMap)")
                appendLine("            lifecycle.gamepad1.id = 1")
                appendLine("            lifecycle.tick()")
                appendLine("            lifecycle.start()")
                appendLine("            lifecycle.gamepad1.a = true")
                appendLine("            RobotClock.useMockTime(2_300L)")
                appendLine("            lifecycle.tick()")
                appendLine("            assertTrue(intakeMotor.power > 0.01)")
                appendLine("            lifecycle.gamepad1.a = false")
                appendLine("            RobotClock.useMockTime(2_320L)")
                appendLine("            lifecycle.tick()")
                appendLine("            assertEquals(0.0, intakeMotor.power, 1e-6)")
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
        configureConsumerSdk(extractedProject)

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
        val headingKd = extractedTuning.values.single { it.parameterUid == "ftc.drive.heading.kd" }.value.doubleValue
        assertEquals(0.0, headingKd, "Tuning parameter heading.kd must retain saved value")

        val extractedControls = extractedDocs.controlSchemes.single { it.documentId == "driver" }
        val feedBinding = extractedControls.bindings.single { it.bindingId == "feed-ball" }
        assertEquals(0.10, feedBinding.source.transform?.deadband, "Control deadband must retain saved value")
        val headingLockBinding = extractedControls.bindings.single { it.bindingId == "enable-heading-lock" }
        assertEquals("drivetrain.headingLock.enable", headingLockBinding.target.key, "Control action must retain saved value")

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
        val driver = ConsumerRoundtripBuild("biobuzz")
        val buildService = driver.service
        try {
            driver.generate(extractedProject)
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
            driver.generate(extractedProject)
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
            driver.verify(extractedProject)

            val executionState = buildService.processState.value.buildExecution
            assertEquals(
                BuildExecutionPhase.SUCCEEDED,
                executionState.phase,
                "Verification build must succeed: ${executionState.message}\nRecent output:\n" + buildService.buildOutput.replayCache.takeLast(60).joinToString("\n")
            )
            assertEquals(0, executionState.exitCode, "Verification build exit code must be 0")
        } finally {
            buildService.shutdownAndJoin()
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
