package com.ares.analytics.mcp

import com.ares.analytics.mcp.bridge.StudioBridgeClient
import com.ares.analytics.mcp.engine.ConfirmationTokenManager
import com.ares.analytics.mcp.engine.HeadlessProjectEngine
import com.ares.analytics.mcp.engine.HeadlessProjectMutators
import com.ares.analytics.mcp.engine.JGitSnapshotSafety
import com.ares.analytics.mcp.engine.TuningAssignmentInput
import com.areslib.controls.ControlSchemeCodec
import com.areslib.controls.ControllerProfileCodec
import com.areslib.routine.AresRoutineCodec
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineStep
import com.areslib.routine.RoutineStepKind
import com.areslib.superstructure.SuperstructureDisabledPolicy
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.SuperstructureDocumentCodec
import com.areslib.superstructure.SuperstructureFieldReference
import com.areslib.superstructure.SuperstructureStatePreset
import com.areslib.superstructure.SuperstructureSubsystemTarget
import com.areslib.superstructure.SuperstructureTargetMode
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HeadlessProjectEngineTest {

    private fun resolveTemplateDir(): File = listOf(
        File("../app/src/main/resources/project-templates"),
        File("ARES-Analytics/app/src/main/resources/project-templates"),
        File("app/src/main/resources/project-templates"),
    ).first { it.isDirectory }.canonicalFile

    private fun createEngine(tempRoot: File): Pair<HeadlessProjectEngine, HeadlessProjectMutators> {
        val bridgeDir = File(tempRoot, "bridge").apply { mkdirs() }
        val engine = HeadlessProjectEngine(
            bridgeClient = StudioBridgeClient(bridgeDir = bridgeDir),
            tokens = ConfirmationTokenManager(),
            templateDir = resolveTemplateDir(),
        )
        return engine to HeadlessProjectMutators(engine)
    }

    private fun smokeSubsystemJson(documentId: String = "intake", kotlinType: String = "IntakeSubsystem"): String = """
        |{
        |  "schemaVersion": 11,
        |  "documentId": "$documentId",
        |  "uid": "$documentId",
        |  "displayName": "Intake",
        |  "kotlinTypeName": "$kotlinType",
        |  "description": "Test subsystem for MCP validation.",
        |  "platform": "FTC",
        |  "revision": 1,
        |  "hardware": [
        |    {
        |      "hardwareId": "roller",
        |      "uid": "roller",
        |      "displayName": "Roller servo",
        |      "kind": "POSITIONAL_SERVO",
        |      "connection": {"hardwareMapName": "intakeRoller"},
        |      "measurements": [],
        |      "required": false,
        |      "safeOutput": 0.0
        |    }
        |  ],
        |  "stateFields": [
        |    {
        |      "fieldId": "position",
        |      "uid": "position",
        |      "displayName": "Roller position",
        |      "type": "DOUBLE",
        |      "role": "TARGET",
        |      "unit": "normalized servo position",
        |      "defaultNumber": 0.0,
        |      "minimum": 0.0,
        |      "maximum": 1.0
        |    }
        |  ],
        |  "controlLoops": [
        |    {
        |      "loopId": "positionOutput",
        |      "uid": "positionOutput",
        |      "displayName": "Roller output",
        |      "strategy": "SERVO_POSITION",
        |      "actuatorId": "roller",
        |      "targetFieldId": "position",
        |      "feedforward": {"kind": "NONE"},
        |      "minimumOutput": 0.0,
        |      "maximumOutput": 1.0
        |    }
        |  ],
        |  "tuningParameters": [],
        |  "template": "SIMPLE_ACTUATOR",
        |  "implementation": {
        |    "kind": "GENERATED_STARTER",
        |    "ownership": "GENERATED_STARTER",
        |    "sourceFiles": [],
        |    "simulation": {"support": "GENERATED_MOCK"},
        |    "teaching": {
        |      "level": "BEGINNER",
        |      "summary": "MCP test subsystem.",
        |      "concepts": ["state"]
        |    }
        |  },
        |  "capabilityActionKeys": [],
        |  "interlocks": [],
        |  "safety": {
        |    "feedbackTimeoutMs": null,
        |    "homing": {"method": "NONE"},
        |    "requiresCalibration": false,
        |    "requiresConfigurationHealth": false,
        |    "requiresCurrentMonitoring": false,
        |    "latchOutputFaults": false,
        |    "requiresExplicitNeutralRecovery": false,
        |    "telemetryEnabled": true,
        |    "zeroAllocationPeriodic": true
        |  },
        |  "requiredAtStartup": false,
        |  "generateMockIo": true,
        |  "generateTest": true
        |}
    """.trimMargin()

    @Test
    fun `init inspect validate and all six mutators enforce validation cas and two-step confirmations`() = runBlocking {
        val tempRoot = Files.createTempDirectory("ares-mcp-engine-").toFile()
        val ftcDir = File(tempRoot, "ftc-project")
        val frcDir = File(tempRoot, "frc-project")
        try {
            val (engine, mutators) = createEngine(tempRoot)

            val ftcInit = engine.initProject(
                targetDir = ftcDir,
                leagueRaw = "FTC",
                displayName = "Engine FTC Bot",
                teamId = "23247",
                robotId = "MecanumV2",
            )
            assertEquals("FTC", ftcInit.league)
            assertTrue(ftcInit.validation.valid)

            val frcInit = engine.initProject(
                targetDir = frcDir,
                leagueRaw = "FRC",
                displayName = "Engine FRC Bot",
                teamId = "1115",
                robotId = "SwerveV1",
            )
            assertEquals("FRC", frcInit.league)
            assertTrue(frcInit.validation.valid)

            val inspected = engine.inspectProject(ftcDir)
            assertTrue(inspected.drivetrainIds.isNotEmpty())
            assertTrue(inspected.tuningKeys.isNotEmpty())

            val driveRes = mutators.mutateDrivetrain(
                projectRoot = ftcDir,
                maxLinearSpeedMps = 1.8,
                maxAngularSpeedRadPerSec = 5.0,
                expectedRevision = inspected.revision,
            )
            assertFalse(driveRes.pendingConfirmation)
            assertNotNull(driveRes.newRevision)

            assertFailsWith<IllegalStateException> {
                mutators.mutateDrivetrain(
                    projectRoot = ftcDir,
                    maxLinearSpeedMps = 3.0,
                    expectedRevision = "stale-revision",
                )
            }

            // Upsert and delete subsystem with two-step token
            val subUpsert = mutators.mutateSubsystem(
                projectRoot = ftcDir,
                operationRaw = "UPSERT",
                documentJson = smokeSubsystemJson("intake", "IntakeSubsystem"),
            )
            assertTrue(File(ftcDir, subUpsert.filePath).isFile)

            // Upsert and delete superstructure referencing intake.position
            val fieldRef = SuperstructureFieldReference(subsystemUid = "intake", fieldUid = "position")
            val superstructure = SuperstructureDocument(
                superstructureId = "upper-mechanism",
                displayName = "Upper Mechanism",
                initialStateId = "stowed",
                faultStateId = "safe",
                disabledPolicy = SuperstructureDisabledPolicy.FORCE_SAFE_AND_REJECT_REQUESTS,
                disabledStateId = "safe",
                states = listOf(
                    SuperstructureStatePreset(
                        stateId = "stowed",
                        displayName = "Stowed",
                        subsystemTargets = listOf(
                            SuperstructureSubsystemTarget(
                                target = fieldRef,
                                targetMode = SuperstructureTargetMode.CONSTANT,
                                constantDoubleValue = 0.0,
                            ),
                        ),
                    ),
                    SuperstructureStatePreset(
                        stateId = "safe",
                        displayName = "Safe",
                        subsystemTargets = listOf(
                            SuperstructureSubsystemTarget(
                                target = fieldRef,
                                targetMode = SuperstructureTargetMode.CONSTANT,
                                constantDoubleValue = 0.0,
                            ),
                        ),
                    ),
                ),
            )
            val ssUpsert = mutators.mutateSuperstructure(
                projectRoot = ftcDir,
                operationRaw = "UPSERT",
                documentJson = SuperstructureDocumentCodec.encode(superstructure),
            )
            assertTrue(File(ftcDir, ssUpsert.filePath).isFile)

            val ssDelPreview = mutators.mutateSuperstructure(
                projectRoot = ftcDir,
                operationRaw = "DELETE",
                superstructureIdHint = "upper-mechanism",
            )
            assertTrue(ssDelPreview.pendingConfirmation)
            val ssToken = requireNotNull(ssDelPreview.confirmationToken)
            val ssDelApply = mutators.mutateSuperstructure(
                projectRoot = ftcDir,
                operationRaw = "DELETE",
                superstructureIdHint = "upper-mechanism",
                confirmationToken = ssToken,
            )
            assertFalse(File(ftcDir, ssDelApply.filePath).exists())

            val subDelPreview = mutators.mutateSubsystem(
                projectRoot = ftcDir,
                operationRaw = "DELETE",
                documentIdHint = "intake",
            )
            assertTrue(subDelPreview.pendingConfirmation)
            mutators.mutateSubsystem(
                projectRoot = ftcDir,
                operationRaw = "DELETE",
                documentIdHint = "intake",
                confirmationToken = subDelPreview.confirmationToken,
            )
            assertFalse(File(ftcDir, subUpsert.filePath).exists())

            // Routine upsert + autonomous registration + delete
            val routine = RoutineDocument(
                schemaVersion = 2,
                documentId = "park-close",
                revision = 1,
                name = "Park Close",
                description = "Wait briefly in safe zone.",
                steps = listOf(RoutineStep(kind = RoutineStepKind.WAIT, stepId = "hold", durationSeconds = 0.2)),
            )
            val routineUpsert = mutators.mutateRoutine(
                projectRoot = ftcDir,
                operationRaw = "UPSERT",
                documentJson = AresRoutineCodec.encode(routine),
                registerAutonomousDisplayName = "Park Close",
            )
            assertTrue(File(ftcDir, routineUpsert.filePath).isFile)
            assertTrue(File(ftcDir, ".ares/autonomous-catalog.json").readText().contains("park-close"))

            val routineDelPreview = mutators.mutateRoutine(
                projectRoot = ftcDir,
                operationRaw = "DELETE",
                documentIdHint = "park-close",
            )
            assertTrue(routineDelPreview.pendingConfirmation)
            mutators.mutateRoutine(
                projectRoot = ftcDir,
                operationRaw = "DELETE",
                documentIdHint = "park-close",
                confirmationToken = routineDelPreview.confirmationToken,
            )

            // Controls (CONTROL_SCHEME & CONTROLLER_PROFILE)
            val existingScheme = ControlSchemeCodec.decode(File(ftcDir, ".ares/controls/driver.arescontrols").readText())
            mutators.mutateControls(
                projectRoot = ftcDir,
                operationRaw = "UPSERT",
                targetKindRaw = "CONTROL_SCHEME",
                documentJson = ControlSchemeCodec.encode(existingScheme.copy(description = "Updated by test")),
            )
            val existingProfile = ControllerProfileCodec.decode(File(ftcDir, ".ares/controllers/ftc-driver.arescontroller").readText())
            mutators.mutateControls(
                projectRoot = ftcDir,
                operationRaw = "UPSERT",
                targetKindRaw = "CONTROLLER_PROFILE",
                documentJson = ControllerProfileCodec.encode(existingProfile.copy(documentId = "test-driver")),
            )
            assertTrue(File(ftcDir, ".ares/controllers/test-driver.arescontroller").isFile)

            // Tuning LOCAL_OVERLAY immediate, CANONICAL gated, invalid rolled back
            val localTune = mutators.mutateTuning(
                projectRoot = ftcDir,
                targetRaw = "LOCAL_OVERLAY",
                profileId = "pit-overlay",
                assignments = listOf(TuningAssignmentInput("ftc.drive.heading.kp", doubleValue = 2.1)),
            )
            assertFalse(localTune.pendingConfirmation)
            assertTrue(File(ftcDir, localTune.filePath).isFile)

            val canonicalPreview = mutators.mutateTuning(
                projectRoot = ftcDir,
                targetRaw = "CANONICAL",
                profileId = "simulation",
                assignments = listOf(TuningAssignmentInput("ftc.drive.heading.kp", doubleValue = 2.0)),
            )
            assertTrue(canonicalPreview.pendingConfirmation)
            val canonicalApply = mutators.mutateTuning(
                projectRoot = ftcDir,
                targetRaw = "CANONICAL",
                profileId = "simulation",
                assignments = listOf(TuningAssignmentInput("ftc.drive.heading.kp", doubleValue = 2.0)),
                confirmationToken = canonicalPreview.confirmationToken,
            )
            assertFalse(canonicalApply.pendingConfirmation)

            val badRoutineFile = File(ftcDir, ".ares/routines/bad.aresroutine")
            assertFailsWith<Exception> {
                mutators.mutateRoutine(
                    projectRoot = ftcDir,
                    operationRaw = "UPSERT",
                    documentJson = "{ \"documentId\": \"bad\", \"steps\": [] }",
                )
            }
            assertFalse(badRoutineFile.exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `jgit safety snapshots capture and restore without touching HEAD`() {
        val tempRoot = Files.createTempDirectory("ares-mcp-jgit-").toFile()
        try {
            Git.init().setDirectory(tempRoot).call().use { git ->
                val readme = File(tempRoot, "README.md")
                readme.writeText("v1\n")
                git.add().addFilepattern("README.md").call()
                val ident = PersonIdent("Test Bot", "bot@example.com")
                git.commit().setSign(false).setMessage("initial").setAuthor(ident).setCommitter(ident).call()
                val headBefore = git.repository.resolve("HEAD").name

                readme.writeText("v2 before snapshot\n")
                val extraFile = File(tempRoot, "notes.txt").apply { writeText("keep-me-in-snap\n") }
                val snap = JGitSnapshotSafety.createSnapshot(tempRoot, slug = "before-edit")
                assertTrue(snap.created)
                val refName = requireNotNull(snap.refName)
                assertTrue(refName.startsWith("refs/ares-mcp/snapshots/"))

                readme.writeText("v3 corrupted\n")
                extraFile.delete()
                File(tempRoot, "unwanted.txt").writeText("should be deleted on restore\n")

                val snapshots = JGitSnapshotSafety.listSnapshots(tempRoot)
                assertTrue(snapshots.any { it.refName == refName })

                val restore = JGitSnapshotSafety.restoreSnapshot(tempRoot, refName)
                assertTrue(restore.restored)
                assertEquals("v2 before snapshot\n", readme.readText())
                assertTrue(extraFile.isFile)
                assertFalse(File(tempRoot, "unwanted.txt").exists())
                assertEquals(headBefore, git.repository.resolve("HEAD").name)
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
