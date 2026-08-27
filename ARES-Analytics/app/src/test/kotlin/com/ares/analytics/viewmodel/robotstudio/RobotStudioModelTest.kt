package com.ares.analytics.viewmodel.robotstudio

import com.ares.analytics.service.BuildExecutionPhase
import com.ares.analytics.service.BuildExecutionState
import com.ares.analytics.service.RobotProjectReadinessEvidence
import com.ares.analytics.service.drivebase.DrivebaseKind
import com.ares.analytics.service.hardware.HardwareReviewStatus
import com.ares.analytics.shared.League
import com.areslib.simulation.SimulationProductId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RobotStudioModelTest {
    @Test
    fun `unreachable safety recovery keeps controls visibly incomplete`() {
        val stages = evaluateRobotStudioStages(
            completeEvidence().copy(
                capabilityActionCount = 4,
                controlTeleopActionCount = 4,
                controlBoundActionCount = 2,
                controlSafetyActionCount = 1,
                controlBoundSafetyActionCount = 0,
            ),
            RobotStudioRuntimeEvidence(),
        )

        val controls = stages.first { it.id == RobotStudioStageId.CONTROLS }
        assertEquals(RobotStudioStageStatus.NEEDS_ACTION, controls.status)
        assertTrue(controls.explanation.contains("safety/recovery"))
        assertEquals(RobotStudioStageStatus.BLOCKED, stages.status(RobotStudioStageId.GENERATE_VERIFY))
    }

    @Test
    fun `partial readiness cannot enable global execution controls`() {
        val buildOnly = RobotStudioStage(
            id = RobotStudioStageId.GENERATE_VERIFY,
            title = "Verify & build",
            outcome = "Fixture",
            status = RobotStudioStageStatus.READY,
            explanation = "Fixture",
            issues = emptyList(),
            storage = "Fixture",
            consumer = "Fixture",
            action = RobotStudioAction.RUN_BUILD,
            actionLabel = "Verify again",
        )
        val state = RobotStudioState(loading = false, stages = listOf(buildOnly))

        assertFalse(state.hasCompleteReadiness)
        assertFalse(state.canRunBuild)
        assertFalse(state.canRunSimulation)
    }

    @Test
    fun `complete canonical evidence enables build and simulation actions without claiming success`() {
        val stages = evaluateRobotStudioStages(completeEvidence(), RobotStudioRuntimeEvidence())
        val studio = RobotStudioState(loading = false, stages = stages)

        assertEquals(RobotStudioStageStatus.READY, stages.status(RobotStudioStageId.PROJECT_IDENTITY))
        assertEquals(RobotStudioStageStatus.READY, stages.status(RobotStudioStageId.HARDWARE))
        assertEquals(RobotStudioStageStatus.READY, stages.status(RobotStudioStageId.CONTROLS))
        assertEquals(RobotStudioStageStatus.NEEDS_ACTION, stages.status(RobotStudioStageId.GENERATE_VERIFY))
        assertEquals(RobotStudioStageStatus.BLOCKED, stages.status(RobotStudioStageId.SIMULATE))
        assertTrue(stages.first { it.id == RobotStudioStageId.SIMULATE }.explanation.contains("Verify & build"))
        assertTrue(stages.first { it.id == RobotStudioStageId.GENERATE_VERIFY }.explanation.contains("not proof"))
        assertTrue(studio.canRunBuild)
        assertFalse(studio.canRunSimulation)
    }

    @Test
    fun `pipeline stages follow exact dependency order with autonomous before controls`() {
        val stages = evaluateRobotStudioStages(completeEvidence(), RobotStudioRuntimeEvidence())
        val ids = stages.map { it.id }
        assertEquals(
            listOf(
                RobotStudioStageId.PROJECT_IDENTITY,
                RobotStudioStageId.HARDWARE,
                RobotStudioStageId.COORDINATION,
                RobotStudioStageId.AUTONOMOUS,
                RobotStudioStageId.CONTROLS,
                RobotStudioStageId.TUNING,
                RobotStudioStageId.GENERATE_VERIFY,
                RobotStudioStageId.SIMULATE,
                RobotStudioStageId.DEPLOY,
                RobotStudioStageId.ANALYZE,
            ),
            ids,
        )
    }

    @Test
    fun `missing required authoring blocks build and simulation`() {
        val noDrivebase = evaluateRobotStudioStages(
            completeEvidence().copy(
                drivebaseKind = null,
                drivebaseNoCodeSupported = false,
                localizationConfigured = false,
            ),
            RobotStudioRuntimeEvidence(),
        )
        assertEquals(RobotStudioStageStatus.NEEDS_ACTION, noDrivebase.status(RobotStudioStageId.HARDWARE))
        assertEquals(RobotStudioStageStatus.BLOCKED, noDrivebase.status(RobotStudioStageId.GENERATE_VERIFY))
        assertEquals(RobotStudioStageStatus.BLOCKED, noDrivebase.status(RobotStudioStageId.SIMULATE))
        assertFalse(RobotStudioState(loading = false, stages = noDrivebase).canRunBuild)

        val baselineControls = evaluateRobotStudioStages(
            completeEvidence().copy(controlSchemeCount = 0, controllerProfileCount = 0),
            RobotStudioRuntimeEvidence(),
        )
        assertEquals(RobotStudioStageStatus.OPTIONAL, baselineControls.status(RobotStudioStageId.CONTROLS))
        assertEquals(RobotStudioStageStatus.NEEDS_ACTION, baselineControls.status(RobotStudioStageId.GENERATE_VERIFY))
        assertTrue(baselineControls.first { it.id == RobotStudioStageId.CONTROLS }.explanation.contains("baseline driving controls"))

        val incompleteControls = evaluateRobotStudioStages(
            completeEvidence().copy(controlSchemeCount = 0, controllerProfileCount = 1),
            RobotStudioRuntimeEvidence(),
        )
        assertEquals(RobotStudioStageStatus.NEEDS_ACTION, incompleteControls.status(RobotStudioStageId.CONTROLS))
        assertEquals(RobotStudioStageStatus.BLOCKED, incompleteControls.status(RobotStudioStageId.GENERATE_VERIFY))
    }

    @Test
    fun `missing or invalid metadata routes students to reviewed project identity setup`() {
        val missing = evaluateRobotStudioStages(
            completeEvidence().copy(metadataPresent = false, metadataLeagueMatches = false),
            RobotStudioRuntimeEvidence(),
        )
        val invalidPlatform = evaluateRobotStudioStages(
            completeEvidence().copy(metadataLeagueMatches = false),
            RobotStudioRuntimeEvidence(),
        )

        assertEquals(
            RobotStudioAction.OPEN_PROJECT_IDENTITY,
            missing.first { it.id == RobotStudioStageId.PROJECT_IDENTITY }.action,
        )
        assertEquals(
            "Set up identity",
            missing.first { it.id == RobotStudioStageId.PROJECT_IDENTITY }.actionLabel,
        )
        assertEquals(
            RobotStudioAction.OPEN_PROJECT_IDENTITY,
            invalidPlatform.first { it.id == RobotStudioStageId.PROJECT_IDENTITY }.action,
        )
        assertEquals(
            RobotStudioStageStatus.INVALID,
            invalidPlatform.status(RobotStudioStageId.PROJECT_IDENTITY),
        )
    }

    @Test
    fun `unsupported drive runtime is visibly code required and blocks no-code workflow`() {
        val stages = evaluateRobotStudioStages(
            completeEvidence().copy(
                drivebaseKind = DrivebaseKind.DIFFERENTIAL,
                drivebaseNoCodeSupported = false,
            ),
            RobotStudioRuntimeEvidence(),
        )
        val studio = RobotStudioState(stages = stages)

        assertEquals(RobotStudioStageStatus.CODE_REQUIRED, stages.status(RobotStudioStageId.HARDWARE))
        assertEquals(RobotStudioStageStatus.BLOCKED, stages.status(RobotStudioStageId.GENERATE_VERIFY))
        assertTrue(studio.blockingCount >= 2)
        assertTrue(stages.first { it.id == RobotStudioStageId.HARDWARE }.explanation.contains("no no-code runtime adapter"))
    }

    @Test
    fun `typed metadata and capability diagnostics are surfaced instead of text guessed`() {
        val stages = evaluateRobotStudioStages(
            completeEvidence().copy(
                metadataErrors = listOf("project.json: invalid league"),
                capabilityErrors = listOf("action-catalog.json: duplicate action"),
            ),
            RobotStudioRuntimeEvidence(),
        )

        assertEquals(RobotStudioStageStatus.INVALID, stages.status(RobotStudioStageId.PROJECT_IDENTITY))
        assertEquals(RobotStudioStageStatus.INVALID, stages.status(RobotStudioStageId.HARDWARE))
        assertTrue(stages.first { it.id == RobotStudioStageId.PROJECT_IDENTITY }.issues.any { "invalid league" in it })
        assertTrue(stages.first { it.id == RobotStudioStageId.HARDWARE }.issues.any { "duplicate action" in it })
    }

    @Test
    fun `coordinated mechanisms are optional until authored and invalid definitions block generation`() {
        val optional = evaluateRobotStudioStages(completeEvidence(), RobotStudioRuntimeEvidence())
        val invalid = evaluateRobotStudioStages(
            completeEvidence().copy(
                superstructureCount = 1,
                superstructureErrors = listOf("main.aressuperstructure: unknown action 'arm.raise'"),
            ),
            RobotStudioRuntimeEvidence(),
        )

        assertEquals(RobotStudioStageStatus.OPTIONAL, optional.status(RobotStudioStageId.COORDINATION))
        assertEquals(RobotStudioAction.OPEN_SUPERSTRUCTURES, optional.first { it.id == RobotStudioStageId.COORDINATION }.action)
        assertEquals(RobotStudioStageStatus.INVALID, invalid.status(RobotStudioStageId.COORDINATION))
        assertEquals(RobotStudioStageStatus.BLOCKED, invalid.status(RobotStudioStageId.GENERATE_VERIFY))
    }

    @Test
    fun `runtime and imported run evidence report only what was observed`() {
        val stages = evaluateRobotStudioStages(
            completeEvidence().copy(importedRunCount = 2),
            RobotStudioRuntimeEvidence(
                build = buildState(BuildExecutionPhase.RUNNING),
                simulatorRunning = true,
                simulatorProjectPath = "C:/fixture/robot",
                simulatorLeague = League.FTC,
                localSimulatorOnline = true,
                nt4Connected = true,
            ),
        )

        assertEquals(RobotStudioStageStatus.RUNNING, stages.status(RobotStudioStageId.GENERATE_VERIFY))
        assertEquals(RobotStudioStageStatus.RUNNING, stages.status(RobotStudioStageId.SIMULATE))
        assertEquals(RobotStudioStageStatus.READY, stages.status(RobotStudioStageId.ANALYZE))
        assertEquals(RobotStudioAction.OPEN_GUIDED_ANALYSIS, stages.first { it.id == RobotStudioStageId.ANALYZE }.action)
    }

    @Test
    fun `successful verification is project correlated and explicitly compile only`() {
        val matching = evaluateRobotStudioStages(
            completeEvidence(),
            RobotStudioRuntimeEvidence(build = buildState(BuildExecutionPhase.SUCCEEDED)),
        )
        val otherProject = evaluateRobotStudioStages(
            completeEvidence(),
            RobotStudioRuntimeEvidence(
                build = buildState(BuildExecutionPhase.SUCCEEDED).copy(projectPath = "C:/fixture/another-robot"),
            ),
        )

        val matchingStage = matching.first { it.id == RobotStudioStageId.GENERATE_VERIFY }
        assertEquals(RobotStudioStageStatus.READY, matchingStage.status)
        assertTrue(matchingStage.explanation.contains("Nothing was deployed"))
        assertTrue(matchingStage.outcome.contains("without deploying"))
        assertEquals(RobotStudioStageStatus.NEEDS_ACTION, otherProject.status(RobotStudioStageId.GENERATE_VERIFY))
        assertEquals(RobotStudioStageStatus.NEEDS_ACTION, matching.status(RobotStudioStageId.SIMULATE))
        assertEquals(RobotStudioStageStatus.BLOCKED, otherProject.status(RobotStudioStageId.SIMULATE))
        assertTrue(RobotStudioState(loading = false, stages = matching).canRunSimulation)
        assertFalse(RobotStudioState(loading = false, stages = otherProject).canRunSimulation)
    }

    @Test
    fun `failed and canceled verification remain actionable without claiming readiness`() {
        val failed = evaluateRobotStudioStages(
            completeEvidence(),
            RobotStudioRuntimeEvidence(build = buildState(BuildExecutionPhase.FAILED, exitCode = 7)),
        ).first { it.id == RobotStudioStageId.GENERATE_VERIFY }
        val canceled = evaluateRobotStudioStages(
            completeEvidence(),
            RobotStudioRuntimeEvidence(build = buildState(BuildExecutionPhase.CANCELED)),
        ).first { it.id == RobotStudioStageId.GENERATE_VERIFY }

        assertEquals(RobotStudioStageStatus.INVALID, failed.status)
        assertTrue(failed.issues.single().contains("exit code 7"))
        assertEquals("Retry verification", failed.actionLabel)
        assertEquals(RobotStudioStageStatus.NEEDS_ACTION, canceled.status)
        assertEquals("Retry verification", canceled.actionLabel)
    }

    @Test
    fun `build execution state transitions evaluate appropriate stage statuses and action labels`() {
        val evidence = completeEvidence()

        // 1. Initial idle verification state
        val idle = evaluateRobotStudioStages(
            evidence,
            RobotStudioRuntimeEvidence(build = buildState(BuildExecutionPhase.IDLE)),
        ).first { it.id == RobotStudioStageId.GENERATE_VERIFY }
        assertEquals(RobotStudioStageStatus.NEEDS_ACTION, idle.status)
        assertEquals("Verify & build", idle.actionLabel)
        assertEquals(RobotStudioAction.RUN_BUILD, idle.action)
        assertTrue(idle.issues.isEmpty())

        // 2. Active build running state
        val running = evaluateRobotStudioStages(
            evidence,
            RobotStudioRuntimeEvidence(build = buildState(BuildExecutionPhase.RUNNING)),
        ).first { it.id == RobotStudioStageId.GENERATE_VERIFY }
        assertEquals(RobotStudioStageStatus.RUNNING, running.status)
        assertEquals("Verification running", running.actionLabel)
        assertEquals(RobotStudioAction.RUN_BUILD, running.action)
        assertTrue(running.issues.isEmpty())

        // 3. Timed out build execution state (failure with timeout diagnostics)
        val timedOutState = BuildExecutionState(
            phase = BuildExecutionPhase.FAILED,
            projectPath = "C:/fixture/robot",
            league = League.FTC,
            message = "Project verification timed out after 120 seconds. No deployment was performed.",
            exitCode = 124,
            requestId = 2L,
        )
        val timedOut = evaluateRobotStudioStages(
            evidence,
            RobotStudioRuntimeEvidence(build = timedOutState),
        ).first { it.id == RobotStudioStageId.GENERATE_VERIFY }
        assertEquals(RobotStudioStageStatus.INVALID, timedOut.status)
        assertEquals("Retry verification", timedOut.actionLabel)
        assertEquals(RobotStudioAction.RUN_BUILD, timedOut.action)
        assertEquals(listOf("Project verification timed out after 120 seconds. No deployment was performed."), timedOut.issues)
        assertTrue(timedOut.explanation.contains("timed out"))

        // 4. Retry / rebuild execution running state
        val retrying = evaluateRobotStudioStages(
            evidence,
            RobotStudioRuntimeEvidence(build = buildState(BuildExecutionPhase.RUNNING).copy(requestId = 3L)),
        ).first { it.id == RobotStudioStageId.GENERATE_VERIFY }
        assertEquals(RobotStudioStageStatus.RUNNING, retrying.status)
        assertEquals("Verification running", retrying.actionLabel)
        assertTrue(retrying.issues.isEmpty())

        // 5. Canceled build execution state
        val canceledState = BuildExecutionState(
            phase = BuildExecutionPhase.CANCELED,
            projectPath = "C:/fixture/robot",
            league = League.FTC,
            message = "Project verification was canceled. No deployment was performed.",
            exitCode = null,
            requestId = 4L,
        )
        val canceled = evaluateRobotStudioStages(
            evidence,
            RobotStudioRuntimeEvidence(build = canceledState),
        ).first { it.id == RobotStudioStageId.GENERATE_VERIFY }
        assertEquals(RobotStudioStageStatus.NEEDS_ACTION, canceled.status)
        assertEquals("Retry verification", canceled.actionLabel)
        assertEquals(RobotStudioAction.RUN_BUILD, canceled.action)
        assertTrue(canceled.issues.isEmpty())
        assertTrue(canceled.explanation.contains("canceled"))

        // 6. Succeeded build execution state
        val succeededState = BuildExecutionState(
            phase = BuildExecutionPhase.SUCCEEDED,
            projectPath = "C:/fixture/robot",
            league = League.FTC,
            message = "Verification passed. Nothing was deployed; rebuild after edits.",
            exitCode = 0,
            requestId = 5L,
        )
        val succeeded = evaluateRobotStudioStages(
            evidence,
            RobotStudioRuntimeEvidence(build = succeededState),
        ).first { it.id == RobotStudioStageId.GENERATE_VERIFY }
        assertEquals(RobotStudioStageStatus.READY, succeeded.status)
        assertEquals("Verify again", succeeded.actionLabel)
        assertEquals(RobotStudioAction.RUN_BUILD, succeeded.action)
        assertTrue(succeeded.issues.isEmpty())
    }

    @Test
    fun `1-click wireless deploy stage transitions through connecting building and installing`() {
        val ready = evaluateRobotStudioStages(completeEvidence(), RobotStudioRuntimeEvidence())
        assertEquals(RobotStudioStageStatus.NEEDS_ACTION, ready.status(RobotStudioStageId.DEPLOY))
        assertEquals(RobotStudioAction.DEPLOY_ROBOT, ready.first { it.id == RobotStudioStageId.DEPLOY }.action)

        val connecting = evaluateRobotStudioStages(
            completeEvidence(),
            RobotStudioRuntimeEvidence(deploy = com.ares.analytics.service.DeployExecutionState(phase = com.ares.analytics.service.DeployExecutionPhase.CONNECTING)),
        )
        assertEquals(RobotStudioStageStatus.RUNNING, connecting.status(RobotStudioStageId.DEPLOY))

        val succeeded = evaluateRobotStudioStages(
            completeEvidence(),
            RobotStudioRuntimeEvidence(deploy = com.ares.analytics.service.DeployExecutionState(phase = com.ares.analytics.service.DeployExecutionPhase.SUCCEEDED)),
        )
        assertEquals(RobotStudioStageStatus.READY, succeeded.status(RobotStudioStageId.DEPLOY))
    }

    @Test
    fun `simulator evidence from another workspace is ignored`() {
        val stages = evaluateRobotStudioStages(
            completeEvidence(),
            RobotStudioRuntimeEvidence(
                build = buildState(BuildExecutionPhase.SUCCEEDED),
                simulatorRunning = true,
                simulatorProjectPath = "C:/fixture/other-robot",
                simulatorLeague = League.FTC,
                localSimulatorOnline = true,
                nt4Connected = true,
            ),
        )

        assertEquals(RobotStudioStageStatus.NEEDS_ACTION, stages.status(RobotStudioStageId.SIMULATE))
        assertTrue(stages.first { it.id == RobotStudioStageId.SIMULATE }.explanation.contains("FTC desktop OpMode simulator"))
    }

    @Test
    fun `hardware review is visible and a template deployment block remains fail closed`() {
        val unreviewed = evaluateRobotStudioStages(
            completeEvidence().copy(hardwareItemCount = 7),
            RobotStudioRuntimeEvidence(),
        )
        assertEquals(RobotStudioStageStatus.NEEDS_ACTION, unreviewed.status(RobotStudioStageId.HARDWARE))
        assertEquals(RobotStudioStageStatus.NEEDS_ACTION, unreviewed.status(RobotStudioStageId.GENERATE_VERIFY))
        assertEquals(RobotStudioStageStatus.BLOCKED, unreviewed.status(RobotStudioStageId.SIMULATE))
        assertEquals(RobotStudioStageStatus.BLOCKED, unreviewed.status(RobotStudioStageId.DEPLOY))
        assertTrue(RobotStudioState(loading = false, stages = unreviewed).canRunBuild)
        assertTrue(unreviewed.first { it.id == RobotStudioStageId.DEPLOY }.explanation.contains("Hardware Setup"))
        assertEquals(
            RobotStudioAction.OPEN_HARDWARE_SETUP,
            unreviewed.first { it.id == RobotStudioStageId.HARDWARE }.action,
        )

        val verifiedForSimulation = evaluateRobotStudioStages(
            completeEvidence().copy(hardwareItemCount = 7),
            RobotStudioRuntimeEvidence(build = buildState(BuildExecutionPhase.SUCCEEDED)),
        )
        assertEquals(RobotStudioStageStatus.NEEDS_ACTION, verifiedForSimulation.status(RobotStudioStageId.SIMULATE))
        assertTrue(RobotStudioState(loading = false, stages = verifiedForSimulation).canRunSimulation)
        assertEquals(RobotStudioStageStatus.BLOCKED, verifiedForSimulation.status(RobotStudioStageId.DEPLOY))

        val staleReview = evaluateRobotStudioStages(
            completeEvidence().copy(
                hardwareItemCount = 7,
                hardwareReviewStatus = HardwareReviewStatus.STALE,
                hardwareReviewedBy = "Mentor",
            ),
            RobotStudioRuntimeEvidence(),
        )
        assertEquals(RobotStudioStageStatus.NEEDS_ACTION, staleReview.status(RobotStudioStageId.HARDWARE))
        assertEquals(RobotStudioStageStatus.NEEDS_ACTION, staleReview.status(RobotStudioStageId.GENERATE_VERIFY))
        assertTrue(RobotStudioState(loading = false, stages = staleReview).canRunBuild)
        assertEquals(RobotStudioStageStatus.BLOCKED, staleReview.status(RobotStudioStageId.DEPLOY))
        assertTrue(staleReview.first { it.id == RobotStudioStageId.HARDWARE }.explanation.contains("changed"))

        val currentButReferenceOnly = evaluateRobotStudioStages(
            completeEvidence().copy(
                hardwareItemCount = 7,
                hardwareReviewStatus = HardwareReviewStatus.CURRENT,
                hardwareReviewedBy = "Mentor",
                physicalDeploymentBlockReason = "This starter remains simulation-only.",
            ),
            RobotStudioRuntimeEvidence(),
        )
        assertEquals(RobotStudioStageStatus.READY, currentButReferenceOnly.status(RobotStudioStageId.HARDWARE))
        assertEquals(RobotStudioStageStatus.BLOCKED, currentButReferenceOnly.status(RobotStudioStageId.DEPLOY))
        assertTrue(currentButReferenceOnly.first { it.id == RobotStudioStageId.DEPLOY }.issues.single().contains("simulation-only"))
    }

    @Test
    fun `saved drivetrain readiness is distinct from physical port review`() {
        val evidence = completeEvidence().copy(
            hardwareItemCount = 6,
            hardwareReviewStatus = HardwareReviewStatus.NOT_REVIEWED,
        )
        val sections = evaluateRobotStudioHardwareReadiness(evidence)
        val stages = evaluateRobotStudioStages(evidence, RobotStudioRuntimeEvidence())

        assertEquals(RobotStudioStageStatus.READY, sections.drivetrain.status)
        assertEquals(RobotStudioStageStatus.NEEDS_ACTION, sections.portMap.status)
        assertTrue(sections.portMap.explanation.contains("desktop simulation remains available"))
        assertEquals(RobotStudioStageStatus.NEEDS_ACTION, stages.status(RobotStudioStageId.HARDWARE))
        assertTrue(RobotStudioState(loading = false, stages = stages).canRunBuild)
    }

    @Test
    fun `unsupported simulator capability is visible and blocks only simulation`() {
        val stages = evaluateRobotStudioStages(
            completeEvidence().copy(
                simulationErrors = listOf("Custom drivetrain has no implemented desktop physics adapter."),
            ),
            RobotStudioRuntimeEvidence(build = buildState(BuildExecutionPhase.SUCCEEDED)),
        )
        val state = RobotStudioState(
            loading = false,
            stages = stages,
            simulationProduct = SimulationProductId.FTC_DESKTOP_OPMODE,
        )

        assertEquals(RobotStudioStageStatus.INVALID, stages.status(RobotStudioStageId.SIMULATE))
        assertFalse(state.canRunSimulation)
        assertTrue(state.canRunBuild)
        assertTrue(state.simulationDisabledReason.contains("Custom drivetrain"))
    }

    private fun completeEvidence() = RobotProjectReadinessEvidence(
        projectPath = "C:/fixture/robot",
        league = League.FTC,
        metadataPresent = true,
        metadataLeagueMatches = true,
        drivebaseKind = DrivebaseKind.FTC_MECANUM,
        drivebaseNoCodeSupported = true,
        localizationConfigured = true,
        subsystemCount = 0,
        simulationProduct = SimulationProductId.FTC_DESKTOP_OPMODE,
        capabilityActionCount = 0,
        controlSchemeCount = 1,
        controllerProfileCount = 1,
        routineCount = 0,
        autonomousCatalogPresent = false,
        tuningDeclarationCount = 0,
        tuningProfileCount = 0,
        generatedProjectSourcePresent = true,
    )

    private fun List<RobotStudioStage>.status(id: RobotStudioStageId): RobotStudioStageStatus =
        first { it.id == id }.status

    private fun buildState(phase: BuildExecutionPhase, exitCode: Int? = null) = BuildExecutionState(
        phase = phase,
        projectPath = "C:/fixture/robot",
        league = League.FTC,
        message = when (phase) {
            BuildExecutionPhase.SUCCEEDED -> "Verification passed. Nothing was deployed; rebuild after edits."
            BuildExecutionPhase.FAILED -> "Project verification failed with exit code ${exitCode ?: 1}. Review the terminal."
            BuildExecutionPhase.CANCELED -> "Project verification was canceled. No deployment was performed."
            BuildExecutionPhase.RUNNING -> "Project verification is running. No deployment is performed."
            BuildExecutionPhase.IDLE -> "No verification has run."
        },
        exitCode = exitCode,
        requestId = 1L,
    )
}
