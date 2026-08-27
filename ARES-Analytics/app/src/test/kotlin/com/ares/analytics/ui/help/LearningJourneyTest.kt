package com.ares.analytics.ui.help

import com.ares.analytics.service.LearningProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearningJourneyTest {
    @Test
    fun `observable evaluator requires an explicit available runtime`() {
        assertTrue(
            LearningJourneyEvaluator.observableCheckpointIds(
                AcademyRuntimeSnapshot.Unavailable,
                setOf(FirstMissionCheckpointIds.SIMULATOR_RUNNING),
            ).isEmpty(),
        )
    }

    @Test
    fun `local connection requires selected online simulator and NT4`() {
        val incomplete = LearningJourneyEvaluator.observableCheckpointIds(
            AcademyRuntimeSnapshot(
                isAvailable = true,
                isLocalSimulatorSelected = true,
                isSimulatorRunning = true,
                isLocalSimulatorOnline = false,
                isNt4Connected = true,
            ),
            emptySet(),
        )
        assertTrue(FirstMissionCheckpointIds.SIMULATOR_RUNNING in incomplete)
        assertFalse(FirstMissionCheckpointIds.LOCAL_SIM_CONNECTED in incomplete)

        val connected = LearningJourneyEvaluator.observableCheckpointIds(
            AcademyRuntimeSnapshot(
                isAvailable = true,
                isLocalSimulatorSelected = true,
                isSimulatorRunning = true,
                isLocalSimulatorOnline = true,
                isNt4Connected = true,
            ),
            incomplete,
        )
        assertTrue(FirstMissionCheckpointIds.LOCAL_SIM_CONNECTED in connected)
    }

    @Test
    fun `stopped fact is recorded only after a running process was observed`() {
        val stoppedBeforeRun = LearningJourneyEvaluator.observableCheckpointIds(
            AcademyRuntimeSnapshot(isAvailable = true),
            emptySet(),
        )
        assertFalse(FirstMissionCheckpointIds.SIMULATOR_STOPPED in stoppedBeforeRun)

        val stoppedAfterRun = LearningJourneyEvaluator.observableCheckpointIds(
            AcademyRuntimeSnapshot(isAvailable = true),
            setOf(FirstMissionCheckpointIds.SIMULATOR_RUNNING),
        )
        assertTrue(FirstMissionCheckpointIds.SIMULATOR_STOPPED in stoppedAfterRun)
    }

    @Test
    fun `subsystem observations come from explicit builder facts`() {
        val observed = LearningJourneyEvaluator.observableCheckpointIds(
            AcademyRuntimeSnapshot(
                isAvailable = true,
                subsystem = AcademySubsystemSnapshot(
                    isAvailable = true,
                    hasPositionMechanismDraft = true,
                    hasNaturalStateContract = true,
                    hasCompleteSafetyContract = true,
                    hasSimulationAndVerification = true,
                    isReviewingGeneratedArtifacts = true,
                    hasSavedCanonicalDescriptor = true,
                ),
            ),
            emptySet(),
        )

        assertTrue(SubsystemMissionCheckpointIds.POSITION_DRAFT in observed)
        assertTrue(SubsystemMissionCheckpointIds.NATURAL_STATE in observed)
        assertTrue(SubsystemMissionCheckpointIds.SAFETY_CONTRACT in observed)
        assertTrue(SubsystemMissionCheckpointIds.SIMULATION_VERIFICATION in observed)
        assertTrue(SubsystemMissionCheckpointIds.ARTIFACT_REVIEW in observed)
        assertTrue(SubsystemMissionCheckpointIds.DESCRIPTOR_SAVED in observed)
        assertFalse(SubsystemMissionCheckpointIds.HOMING_REASONING in observed)
        assertFalse(SubsystemMissionCheckpointIds.RUNTIME_FLOW in observed)
    }

    @Test
    fun `control observations stop at structural evidence and never infer execution`() {
        val observed = LearningJourneyEvaluator.observableCheckpointIds(
            AcademyRuntimeSnapshot(
                isAvailable = true,
                controls = AcademyControlsSnapshot(
                    isAvailable = true,
                    hasActionCatalog = true,
                    hasGeneratedSubsystemCapability = true,
                    hasMappedControlSelection = true,
                    hasValidAppliedBinding = true,
                    hasSavedControlScheme = true,
                    hasGeneratedBindings = true,
                ),
            ),
            emptySet(),
        )

        assertTrue(ControlsMissionCheckpointIds.ACTION_CATALOG in observed)
        assertTrue(ControlsMissionCheckpointIds.SUBSYSTEM_CAPABILITY in observed)
        assertTrue(ControlsMissionCheckpointIds.PLATFORM_MAPPING in observed)
        assertTrue(ControlsMissionCheckpointIds.BINDING_APPLIED in observed)
        assertTrue(ControlsMissionCheckpointIds.SCHEME_SAVED in observed)
        assertTrue(ControlsMissionCheckpointIds.BINDINGS_GENERATED in observed)
        assertFalse(ControlsMissionCheckpointIds.BINDING_POLICY in observed)
        assertFalse(ControlsMissionCheckpointIds.RUNTIME_FLOW in observed)
    }

    @Test
    fun `tuning observations record typed review facts but not interpretation`() {
        val observed = LearningJourneyEvaluator.observableCheckpointIds(
            AcademyRuntimeSnapshot(
                isAvailable = true,
                tuning = AcademyTuningSnapshot(
                    isAvailable = true,
                    hasTypedCatalog = true,
                    hasFeedforwardDeclaration = true,
                    hasValidProposal = true,
                    hasProposalProvenance = true,
                    hasStructuredReview = true,
                    hasPromotableReview = true,
                ),
            ),
            emptySet(),
        )

        assertTrue(TuningMissionCheckpointIds.TYPED_CATALOG in observed)
        assertTrue(TuningMissionCheckpointIds.FEEDFORWARD_DECLARATION in observed)
        assertTrue(TuningMissionCheckpointIds.VALID_PROPOSAL in observed)
        assertTrue(TuningMissionCheckpointIds.PROVENANCE in observed)
        assertTrue(TuningMissionCheckpointIds.STRUCTURED_REVIEW in observed)
        assertTrue(TuningMissionCheckpointIds.REVIEW_READY in observed)
        assertFalse(TuningMissionCheckpointIds.MODEL_PREDICTION in observed)
        assertFalse(TuningMissionCheckpointIds.APPLY_POLICY in observed)
        assertFalse(TuningMissionCheckpointIds.EVIDENCE_BOUNDARY in observed)
    }

    @Test
    fun `superstructure observations record runtime preview facts but not student reasoning`() {
        val observed = LearningJourneyEvaluator.observableCheckpointIds(
            AcademyRuntimeSnapshot(
                isAvailable = true,
                superstructure = AcademySuperstructureSnapshot(
                    isAvailable = true,
                    hasSeveralGeneratedSubsystems = true,
                    hasCompletePostures = true,
                    hasExplicitTransitions = true,
                    hasFailSafePolicy = true,
                    hasDeterministicPreview = true,
                    hasFaultInjectionEvidence = true,
                    hasStructuredReview = true,
                    hasSavedCanonicalDocument = true,
                ),
            ),
            emptySet(),
        )

        assertTrue(SuperstructureMissionCheckpointIds.SUBSYSTEMS_READY in observed)
        assertTrue(SuperstructureMissionCheckpointIds.COMPLETE_POSTURES in observed)
        assertTrue(SuperstructureMissionCheckpointIds.TRANSITIONS in observed)
        assertTrue(SuperstructureMissionCheckpointIds.FAIL_SAFE_POLICY in observed)
        assertTrue(SuperstructureMissionCheckpointIds.PREVIEW in observed)
        assertTrue(SuperstructureMissionCheckpointIds.FAULT_INJECTION in observed)
        assertTrue(SuperstructureMissionCheckpointIds.STRUCTURED_REVIEW in observed)
        assertTrue(SuperstructureMissionCheckpointIds.DOCUMENT_SAVED in observed)
        assertFalse(SuperstructureMissionCheckpointIds.POSTURE_REASONING in observed)
        assertFalse(SuperstructureMissionCheckpointIds.FAILURE_REASONING in observed)
    }

    @Test
    fun `autonomous observations stop before prediction and evidence interpretation`() {
        val observed = LearningJourneyEvaluator.observableCheckpointIds(
            AcademyRuntimeSnapshot(
                isAvailable = true,
                autonomous = AcademyAutonomousSnapshot(
                    isAvailable = true,
                    hasProjectCapabilities = true,
                    hasRoutineSteps = true,
                    hasValidRoutine = true,
                    hasKinematicPreview = true,
                    hasAutonomousSelectorEntry = true,
                    hasSavedCanonicalRevision = true,
                    hasGeneratedProject = true,
                ),
            ),
            emptySet(),
        )

        assertTrue(AutonomousMissionCheckpointIds.CAPABILITIES in observed)
        assertTrue(AutonomousMissionCheckpointIds.STEPS in observed)
        assertTrue(AutonomousMissionCheckpointIds.VALID in observed)
        assertTrue(AutonomousMissionCheckpointIds.KINEMATIC_PREVIEW in observed)
        assertTrue(AutonomousMissionCheckpointIds.SELECTOR_ENTRY in observed)
        assertTrue(AutonomousMissionCheckpointIds.SAVED in observed)
        assertTrue(AutonomousMissionCheckpointIds.GENERATED in observed)
        assertFalse(AutonomousMissionCheckpointIds.STARTING_PREDICTION in observed)
        assertFalse(AutonomousMissionCheckpointIds.EVIDENCE_BOUNDARY in observed)
    }

    @Test
    fun `run analysis observations never infer the student claim`() {
        val observed = LearningJourneyEvaluator.observableCheckpointIds(
            AcademyRuntimeSnapshot(
                isAvailable = true,
                runAnalysis = AcademyRunAnalysisSnapshot(
                    isAvailable = true,
                    hasWorkspaceRuns = true,
                    hasSelectedRun = true,
                    hasSourceEvidence = true,
                    hasGuidedReport = true,
                    hasQuantitativeEvidence = true,
                    hasBaselineComparison = true,
                    hasLimitations = true,
                    hasExportedReport = true,
                ),
            ),
            emptySet(),
        )

        assertTrue(RunImportMissionCheckpointIds.RUNS_AVAILABLE in observed)
        assertTrue(RunImportMissionCheckpointIds.RUN_SELECTED in observed)
        assertTrue(RunImportMissionCheckpointIds.SOURCE_EVIDENCE in observed)
        assertTrue(RunAnalysisMissionCheckpointIds.REPORT_READY in observed)
        assertTrue(RunAnalysisMissionCheckpointIds.QUANTITATIVE_EVIDENCE in observed)
        assertTrue(RunAnalysisMissionCheckpointIds.BASELINE_COMPARISON in observed)
        assertTrue(RunAnalysisMissionCheckpointIds.LIMITATIONS in observed)
        assertTrue(RunAnalysisMissionCheckpointIds.EXPORTED in observed)
        assertFalse(RunImportMissionCheckpointIds.EVIDENCE_DESCRIPTION in observed)
        assertFalse(RunAnalysisMissionCheckpointIds.CLAIM in observed)
    }

    @Test
    fun `graduation observations never infer ownership or validation understanding`() {
        val observed = LearningJourneyEvaluator.observableCheckpointIds(
            AcademyRuntimeSnapshot(
                isAvailable = true,
                graduation = AcademyGraduationSnapshot(
                    isAvailable = true,
                    hasProjectIdentity = true,
                    hasAuthoringStagesReady = true,
                    hasVerifiedBuild = true,
                    hasRunningProjectSimulator = true,
                    hasImportedRunEvidence = true,
                ),
            ),
            emptySet(),
        )

        assertTrue(GraduationMissionCheckpointIds.PROJECT_IDENTITY in observed)
        assertTrue(GraduationMissionCheckpointIds.AUTHORING_READY in observed)
        assertTrue(GraduationMissionCheckpointIds.VERIFIED_BUILD in observed)
        assertTrue(GraduationMissionCheckpointIds.SIMULATOR_RUNNING in observed)
        assertTrue(GraduationMissionCheckpointIds.RUN_EVIDENCE in observed)
        assertFalse(GraduationMissionCheckpointIds.OWNERSHIP_REASONING in observed)
        assertFalse(GraduationMissionCheckpointIds.GENERATED_FLOW in observed)
        assertFalse(GraduationMissionCheckpointIds.VALIDATION_BOUNDARY in observed)

        val stopped = LearningJourneyEvaluator.observableCheckpointIds(
            AcademyRuntimeSnapshot(
                isAvailable = true,
                graduation = AcademyGraduationSnapshot(isAvailable = true),
            ),
            observed,
        )
        assertTrue(GraduationMissionCheckpointIds.SIMULATOR_STOPPED in stopped)
    }

    @Test
    fun `lesson prerequisites guide sequence without claiming certification`() {
        val lesson = LearningCatalog.lesson("drivebase-blueprint") ?: error("Missing lesson")
        val waiting = LearningJourneyEvaluator.lessonState(lesson, LearningProgress())
        assertEquals(LearningLessonStatus.RECOMMENDED_LATER, waiting.status)
        assertFalse(waiting.prerequisitesMet)

        val available = LearningJourneyEvaluator.lessonState(
            lesson,
            LearningProgress(practicedLessonIds = setOf("start-simulator")),
        )
        assertEquals(LearningLessonStatus.NOT_STARTED, available.status)
        assertTrue(available.prerequisitesMet)
    }

    @Test
    fun `recommended lesson follows practiced prerequisites`() {
        val path = LearningCatalog.path("drivetrains-odometry") ?: error("Missing path")
        assertEquals("start-simulator", LearningJourneyEvaluator.recommendedLesson(path, LearningProgress())?.id)
        assertEquals(
            "drivebase-blueprint",
            LearningJourneyEvaluator.recommendedLesson(
                path,
                LearningProgress(practicedLessonIds = setOf("start-simulator")),
            )?.id,
        )
        assertNull(
            LearningJourneyEvaluator.recommendedLesson(
                path,
                LearningProgress(practicedLessonIds = path.lessonIds.toSet()),
            ),
        )
    }

    @Test
    fun `track paths lead a student through progressive milestones`() {
        val track = LearningCatalog.path("drivetrains-odometry") ?: error("Missing drivetrains path")

        assertEquals(
            "start-simulator",
            LearningJourneyEvaluator.recommendedLesson(track, LearningProgress())?.id,
        )
        assertEquals(
            "drivebase-blueprint",
            LearningJourneyEvaluator.recommendedLesson(
                track,
                LearningProgress(practicedLessonIds = setOf("start-simulator")),
            )?.id,
        )
        assertEquals(
            "drive-kinematics-lab",
            LearningJourneyEvaluator.recommendedLesson(
                track,
                LearningProgress(practicedLessonIds = setOf("start-simulator", "drivebase-blueprint")),
            )?.id,
        )
    }
}

