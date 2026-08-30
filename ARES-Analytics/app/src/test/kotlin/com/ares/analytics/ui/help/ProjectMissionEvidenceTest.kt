package com.ares.analytics.ui.help

import com.ares.analytics.service.AresGenerationPhase
import com.ares.analytics.shared.models.Trajectory
import com.ares.analytics.viewmodel.PathPlannerState
import com.ares.analytics.viewmodel.superstructure.SuperstructurePreviewSnapshot
import com.ares.analytics.viewmodel.superstructure.SuperstructureSaveReview
import com.ares.analytics.viewmodel.superstructure.SuperstructureStudioState
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutinePose
import com.areslib.routine.RoutineStep
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import com.areslib.superstructure.StateTransitionEdge
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.SuperstructureFieldReference
import com.areslib.superstructure.SuperstructureHealthFallbackPolicy
import com.areslib.superstructure.SuperstructureStatePreset
import com.areslib.superstructure.SuperstructureSubsystemTarget
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectMissionEvidenceTest {
    @Test
    fun `superstructure evidence comes from complete project and runtime-preview state`() {
        val arm = SubsystemTemplates.create(
            SubsystemTemplate.POSITION_CONTROLLED_MECHANISM,
            "arm",
            "Arm",
            SubsystemPlatform.FTC,
        )
        val lift = SubsystemTemplates.create(
            SubsystemTemplate.POSITION_CONTROLLED_MECHANISM,
            "lift",
            "Lift",
            SubsystemPlatform.FTC,
        )
        val armTarget = arm.stateFields.single { it.role == SubsystemFieldRole.TARGET }
        val liftTarget = lift.stateFields.single { it.role == SubsystemFieldRole.TARGET }
        val armMeasurement = arm.stateFields.first { it.role == SubsystemFieldRole.MEASUREMENT }
        val armTargetReference = SuperstructureFieldReference(arm.uid, armTarget.uid)
        val liftTargetReference = SuperstructureFieldReference(lift.uid, liftTarget.uid)
        val measurementReference = SuperstructureFieldReference(arm.uid, armMeasurement.uid)
        fun posture(id: String, armValue: Double, liftValue: Double) = SuperstructureStatePreset(
            stateId = id,
            subsystemTargets = listOf(
                SuperstructureSubsystemTarget(armTargetReference, constantDoubleValue = armValue),
                SuperstructureSubsystemTarget(liftTargetReference, constantDoubleValue = liftValue),
            ),
        )
        val document = SuperstructureDocument(
            superstructureId = "practice-machine",
            initialStateId = "IDLE",
            faultStateId = "FAULT",
            disabledStateId = "IDLE",
            states = listOf(
                posture("IDLE", 0.0, 0.0),
                posture("SCORE", 1.0, 1.0),
                posture("FAULT", 0.0, 0.0),
            ),
            transitions = listOf(
                StateTransitionEdge(
                    transitionId = "score",
                    sourceStateId = "IDLE",
                    targetStateId = "SCORE",
                    actionKey = "machine.score",
                ),
            ),
            healthFallbacks = listOf(
                SuperstructureHealthFallbackPolicy(
                    policyId = "arm-health",
                    source = measurementReference,
                    fallbackStateId = "FAULT",
                ),
            ),
        )
        val preview = SuperstructurePreviewSnapshot(
            nowMs = 20L,
            isEnabled = true,
            currentStateId = "FAULT",
            previousStateId = "IDLE",
            stateAgeMs = 0L,
            transitionSequence = 1L,
            candidateTransitionId = null,
            isFaulted = true,
            faultReason = "arm-health",
            lastRejectionReason = null,
            lastLifecycleError = null,
            lifecycleActions = emptyList(),
            ports = emptyList(),
        )
        val snapshot = SuperstructureStudioState(
            projectPath = "C:/practice",
            saved = document,
            savedContentHash = "saved-hash",
            draft = document,
            subsystems = listOf(arm, lift),
            validationErrors = emptyList(),
            review = SuperstructureSaveReview(null, "candidate-hash", "token", listOf("review")),
            loading = false,
            dirty = false,
            preview = preview,
        ).toAcademySuperstructureSnapshot()

        assertTrue(snapshot.isAvailable)
        assertTrue(snapshot.hasSeveralGeneratedSubsystems)
        assertTrue(snapshot.hasCompletePostures)
        assertTrue(snapshot.hasExplicitTransitions)
        assertTrue(snapshot.hasFailSafePolicy)
        assertTrue(snapshot.hasDeterministicPreview)
        assertTrue(snapshot.hasFaultInjectionEvidence)
        assertTrue(snapshot.hasStructuredReview)
        assertTrue(snapshot.hasSavedCanonicalDocument)
    }

    @Test
    fun `superstructure draft never earns complete posture evidence from partial targets`() {
        val arm = SubsystemTemplates.create(
            SubsystemTemplate.POSITION_CONTROLLED_MECHANISM,
            "arm",
            "Arm",
            SubsystemPlatform.FTC,
        )
        val target = arm.stateFields.single { it.role == SubsystemFieldRole.TARGET }
        val reference = SuperstructureFieldReference(arm.uid, target.uid)
        val document = SuperstructureDocument(
            superstructureId = "partial-machine",
            initialStateId = "IDLE",
            faultStateId = "FAULT",
            states = listOf(
                SuperstructureStatePreset("IDLE", subsystemTargets = listOf(SuperstructureSubsystemTarget(reference, constantDoubleValue = 0.0))),
                SuperstructureStatePreset("ACTIVE"),
                SuperstructureStatePreset("FAULT", subsystemTargets = listOf(SuperstructureSubsystemTarget(reference, constantDoubleValue = 0.0))),
            ),
        )

        val snapshot = SuperstructureStudioState(
            projectPath = "C:/practice",
            draft = document,
            subsystems = listOf(arm),
            loading = false,
        ).toAcademySuperstructureSnapshot()

        assertFalse(snapshot.hasSeveralGeneratedSubsystems)
        assertFalse(snapshot.hasCompletePostures)
        assertFalse(snapshot.hasFailSafePolicy)
    }

    @Test
    fun `autonomous evidence distinguishes canonical generation from kinematic preview`() {
        val routine = RoutineDocument(
            documentId = "practice-auto",
            revision = 2,
            name = "Practice auto",
            steps = listOf(RoutineStep.wait(1.0, stepId = "wait-one")),
        )
        val state = PathPlannerState(
            estimatedDuration = 1.0,
            trajectory = Trajectory(durationSeconds = 1.0, states = emptyList()),
            projectMetadata = AresProjectMetadataDocument(
                projectId = "practice",
                identity = com.areslib.project.AresProjectIdentityDocument("99999", "2026", "practice", "Practice Robot"),
                league = AresLeague.FTC,
                coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
                robotLengthMeters = 0.45,
                robotWidthMeters = 0.45,
                fieldLengthMeters = 3.66,
                fieldWidthMeters = 3.66,
            ),
            generationPhase = AresGenerationPhase.SUCCEEDED,
            routine = routine,
            availableRoutines = listOf(routine),
            capabilityCatalog = CapabilityCatalogDocument(projectId = "practice"),
            autonomousEntry = AutonomousCatalogEntry(
                entryId = "practice-auto",
                displayName = "Practice auto",
                routineId = routine.documentId,
                startingPose = RoutinePose(0.0, 0.0, 0.0),
                enabled = true,
            ),
            availableInAutonomousSelector = true,
        )

        val snapshot = state.toAcademyAutonomousSnapshot()

        assertTrue(snapshot.isAvailable)
        assertTrue(snapshot.hasProjectCapabilities)
        assertTrue(snapshot.hasRoutineSteps)
        assertTrue(snapshot.hasValidRoutine)
        assertTrue(snapshot.hasKinematicPreview)
        assertTrue(snapshot.hasAutonomousSelectorEntry)
        assertTrue(snapshot.hasSavedCanonicalRevision)
        assertTrue(snapshot.hasGeneratedProject)
    }

    @Test
    fun `unsaved autonomous draft cannot earn saved or generated evidence`() {
        val routine = RoutineDocument(
            documentId = "unsaved-auto",
            name = "Unsaved auto",
            steps = listOf(RoutineStep.wait(1.0, stepId = "wait-one")),
        )
        val snapshot = PathPlannerState(
            projectMetadata = AresProjectMetadataDocument(
                projectId = "practice",
                identity = com.areslib.project.AresProjectIdentityDocument("99999", "2026", "practice", "Practice Robot"),
                league = AresLeague.FTC,
                coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
                robotLengthMeters = 0.45,
                robotWidthMeters = 0.45,
                fieldLengthMeters = 3.66,
                fieldWidthMeters = 3.66,
            ),
            generationPhase = AresGenerationPhase.SUCCEEDED,
            routine = routine,
            capabilityCatalog = CapabilityCatalogDocument(projectId = "practice"),
        ).toAcademyAutonomousSnapshot()

        assertTrue(snapshot.hasRoutineSteps)
        assertTrue(snapshot.hasValidRoutine)
        assertFalse(snapshot.hasKinematicPreview)
        assertFalse(snapshot.hasSavedCanonicalRevision)
        assertFalse(snapshot.hasGeneratedProject)
    }
}
