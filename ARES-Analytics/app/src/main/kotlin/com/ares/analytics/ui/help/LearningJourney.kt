package com.ares.analytics.ui.help

interface LearningProgressView {
    val practicedLessonIds: Set<String>
    val startedLessonIds: Set<String>
    val completedCheckpointIds: Set<String>
    val activeLessonId: String?
}

/** Runtime facts the desktop can observe without asking a student to make a safety claim. */
data class AcademyRuntimeSnapshot(
    val isAvailable: Boolean = false,
    val isLocalSimulatorSelected: Boolean = false,
    val isSimulatorRunning: Boolean = false,
    val isLocalSimulatorOnline: Boolean = false,
    val isNt4Connected: Boolean = false,
    val subsystem: AcademySubsystemSnapshot = AcademySubsystemSnapshot.Unavailable,
    val controls: AcademyControlsSnapshot = AcademyControlsSnapshot.Unavailable,
    val tuning: AcademyTuningSnapshot = AcademyTuningSnapshot.Unavailable,
    val superstructure: AcademySuperstructureSnapshot = AcademySuperstructureSnapshot.Unavailable,
    val autonomous: AcademyAutonomousSnapshot = AcademyAutonomousSnapshot.Unavailable,
    val runAnalysis: AcademyRunAnalysisSnapshot = AcademyRunAnalysisSnapshot.Unavailable,
    val graduation: AcademyGraduationSnapshot = AcademyGraduationSnapshot.Unavailable,
) {
    companion object {
        val Unavailable = AcademyRuntimeSnapshot()
    }
}

/** Observable Robot Studio evidence for graduating GUI-authored assets into a verified project. */
data class AcademyGraduationSnapshot(
    val isAvailable: Boolean = false,
    val hasProjectIdentity: Boolean = false,
    val hasAuthoringStagesReady: Boolean = false,
    val hasVerifiedBuild: Boolean = false,
    val hasRunningProjectSimulator: Boolean = false,
    val hasImportedRunEvidence: Boolean = false,
) {
    companion object { val Unavailable = AcademyGraduationSnapshot() }
}

/** Observable, workspace-scoped facts from Guided Run Review; none are student conclusions. */
data class AcademyRunAnalysisSnapshot(
    val isAvailable: Boolean = false,
    val hasWorkspaceRuns: Boolean = false,
    val hasSelectedRun: Boolean = false,
    val hasSourceEvidence: Boolean = false,
    val hasGuidedReport: Boolean = false,
    val hasQuantitativeEvidence: Boolean = false,
    val hasBaselineComparison: Boolean = false,
    val hasLimitations: Boolean = false,
    val hasExportedReport: Boolean = false,
) {
    companion object { val Unavailable = AcademyRunAnalysisSnapshot() }
}

/** Observable facts from Superstructure Studio and its production-runtime preview harness. */
data class AcademySuperstructureSnapshot(
    val isAvailable: Boolean = false,
    val hasSeveralGeneratedSubsystems: Boolean = false,
    val hasCompletePostures: Boolean = false,
    val hasExplicitTransitions: Boolean = false,
    val hasFailSafePolicy: Boolean = false,
    val hasDeterministicPreview: Boolean = false,
    val hasFaultInjectionEvidence: Boolean = false,
    val hasStructuredReview: Boolean = false,
    val hasSavedCanonicalDocument: Boolean = false,
) {
    companion object { val Unavailable = AcademySuperstructureSnapshot() }
}

/** Observable facts from the canonical Routine Builder and kinematic preview. */
data class AcademyAutonomousSnapshot(
    val isAvailable: Boolean = false,
    val hasProjectCapabilities: Boolean = false,
    val hasRoutineSteps: Boolean = false,
    val hasValidRoutine: Boolean = false,
    val hasKinematicPreview: Boolean = false,
    val hasAutonomousSelectorEntry: Boolean = false,
    val hasSavedCanonicalRevision: Boolean = false,
    val hasGeneratedProject: Boolean = false,
) {
    companion object { val Unavailable = AcademyAutonomousSnapshot() }
}

/** Observable facts from the typed tuning proposal workflow; none imply hardware motion. */
data class AcademyTuningSnapshot(
    val isAvailable: Boolean = false,
    val hasTypedCatalog: Boolean = false,
    val hasFeedforwardDeclaration: Boolean = false,
    val hasValidProposal: Boolean = false,
    val hasProposalProvenance: Boolean = false,
    val hasStructuredReview: Boolean = false,
    val hasPromotableReview: Boolean = false,
) {
    companion object {
        val Unavailable = AcademyTuningSnapshot()
    }
}

/** Observable facts from the canonical Controls Editor; none imply that a button was pressed. */
data class AcademyControlsSnapshot(
    val isAvailable: Boolean = false,
    val hasActionCatalog: Boolean = false,
    val hasGeneratedSubsystemCapability: Boolean = false,
    val hasMappedControlSelection: Boolean = false,
    val hasValidAppliedBinding: Boolean = false,
    val hasSavedControlScheme: Boolean = false,
    val hasGeneratedBindings: Boolean = false,
) {
    companion object {
        val Unavailable = AcademyControlsSnapshot()
    }
}

/**
 * Narrow, observable facts from the real Subsystem Builder.
 *
 * These facts prove only that the canonical form contains a reviewed declaration. They do not
 * prove generated Kotlin compiled, simulation passed, or physical hardware behaved safely.
 */
data class AcademySubsystemSnapshot(
    val isAvailable: Boolean = false,
    val hasPositionMechanismDraft: Boolean = false,
    val hasNaturalStateContract: Boolean = false,
    val hasCompleteSafetyContract: Boolean = false,
    val hasSimulationAndVerification: Boolean = false,
    val isReviewingGeneratedArtifacts: Boolean = false,
    val hasSavedCanonicalDescriptor: Boolean = false,
) {
    companion object {
        val Unavailable = AcademySubsystemSnapshot()
    }
}

enum class LearningCheckpointEvidence {
    /** A student must confirm this learning or interpretation themselves. */
    SELF_REPORTED,

    /** The app can see that Local Sim is the selected execution target. */
    LOCAL_SIMULATOR_SELECTED,

    /** The app can see its managed simulator process running. */
    SIMULATOR_RUNNING,

    /** NT4 and the local simulator scanner both identify a live local simulation. */
    LOCAL_SIMULATOR_CONNECTED,

    /** A previously observed simulator process is no longer running. */
    SIMULATOR_STOPPED_AFTER_RUNNING,

    /** The current canonical subsystem draft matches the lesson's position-mechanism goal. */
    POSITION_MECHANISM_DRAFT,

    /** Motor inputs and targets are represented by explicit cached state fields. */
    SUBSYSTEM_NATURAL_STATE_READY,

    /** The declared safety contract contains the bounded controls required by the lesson. */
    SUBSYSTEM_SAFETY_CONTRACT_READY,

    /** Mock IO and generated verification are enabled in the canonical draft. */
    SUBSYSTEM_SIMULATION_VERIFICATION_READY,

    /** The student has opened the generated artifact review with a valid preview. */
    SUBSYSTEM_ARTIFACT_REVIEWED,

    /** A valid canonical `.aressubsystem` revision was saved to the selected project. */
    SUBSYSTEM_DESCRIPTOR_SAVED,

    /** The selected project contains a readable, non-empty typed action catalog. */
    CONTROLS_ACTION_CATALOG_READY,

    /** A generated subsystem target capability is visible to the Controls Editor. */
    CONTROLS_SUBSYSTEM_CAPABILITY_DISCOVERED,

    /** The selected logical control has an explicit mapping for the robot platform. */
    CONTROLS_PLATFORM_MAPPING_SELECTED,

    /** A locally applied, valid binding targets a generated subsystem capability. */
    CONTROLS_BINDING_APPLIED,

    /** The valid binding is present in a saved canonical control-scheme revision. */
    CONTROLS_SCHEME_SAVED,

    /** Deterministic project generation completed after the saved binding became current. */
    CONTROLS_BINDINGS_GENERATED,

    /** A canonical profile and bounded typed parameter catalog are loaded from the selected project. */
    TUNING_TYPED_CATALOG_READY,

    /** The project declares at least one feedforward-related parameter with a unit. */
    TUNING_FEEDFORWARD_DECLARATION_FOUND,

    /** At least one locally staged proposal is valid against its declaration. */
    TUNING_VALID_PROPOSAL_STAGED,

    /** Every staged proposal records a nonblank source and explanation. */
    TUNING_PROVENANCE_RECORDED,

    /** A structured, side-effect-free promotion review has been created. */
    TUNING_STRUCTURED_REVIEW_CREATED,

    /** The review has changes, reviewer, summary, evidence, and no blocking errors. */
    TUNING_REVIEW_READY_FOR_CONFIRMATION,

    SUPERSTRUCTURE_GENERATED_SUBSYSTEMS_READY,
    SUPERSTRUCTURE_COMPLETE_POSTURES_READY,
    SUPERSTRUCTURE_TRANSITIONS_READY,
    SUPERSTRUCTURE_FAIL_SAFE_POLICY_READY,
    SUPERSTRUCTURE_PREVIEW_RUNNING,
    SUPERSTRUCTURE_FAULT_INJECTION_OBSERVED,
    SUPERSTRUCTURE_STRUCTURED_REVIEW_CREATED,
    SUPERSTRUCTURE_DOCUMENT_SAVED,

    AUTONOMOUS_CAPABILITY_CATALOG_READY,
    AUTONOMOUS_ROUTINE_STEPS_READY,
    AUTONOMOUS_ROUTINE_VALID,
    AUTONOMOUS_KINEMATIC_PREVIEW_READY,
    AUTONOMOUS_SELECTOR_ENTRY_READY,
    AUTONOMOUS_ROUTINE_SAVED,
    AUTONOMOUS_PROJECT_GENERATED,

    RUN_ANALYSIS_WORKSPACE_RUNS_READY,
    RUN_ANALYSIS_RUN_SELECTED,
    RUN_ANALYSIS_SOURCE_EVIDENCE_READY,
    RUN_ANALYSIS_GUIDED_REPORT_READY,
    RUN_ANALYSIS_QUANTITATIVE_EVIDENCE_READY,
    RUN_ANALYSIS_BASELINE_COMPARISON_READY,
    RUN_ANALYSIS_LIMITATIONS_READY,
    RUN_ANALYSIS_REPORT_EXPORTED,

    GRADUATION_PROJECT_IDENTITY_READY,
    GRADUATION_AUTHORING_STAGES_READY,
    GRADUATION_VERIFIED_BUILD_READY,
    GRADUATION_PROJECT_SIMULATOR_RUNNING,
    GRADUATION_PROJECT_SIMULATOR_STOPPED_AFTER_RUNNING,
    GRADUATION_RUN_EVIDENCE_READY,
}

enum class LearningCheckpointAction {
    SELECT_LOCAL_SIMULATOR,
    START_SIMULATOR,
    OPEN_DASHBOARD,
    STOP_SIMULATOR,
    OPEN_SUBSYSTEM_BUILDER,
    OPEN_HOMING_LAB,
    OPEN_STATE_FLOW_LAB,
    OPEN_CONTROLS,
    OPEN_TUNING,
    OPEN_SUPERSTRUCTURE_STUDIO,
    OPEN_AUTONOMOUS,
    OPEN_IMPORTS,
    OPEN_GUIDED_ANALYSIS,
    OPEN_ROBOT_STUDIO,
    OPEN_DEVELOPER_REFERENCE,
    OPEN_LESSON,
}

data class LearningCheckpoint(
    val id: String,
    val title: String,
    val instruction: String,
    val successText: String,
    val evidence: LearningCheckpointEvidence = LearningCheckpointEvidence.SELF_REPORTED,
    val action: LearningCheckpointAction = LearningCheckpointAction.OPEN_LESSON,
)

enum class LearningLessonStatus(val label: String) {
    NOT_STARTED("Not started"),
    IN_PROGRESS("In progress"),
    PRACTICED("Practiced"),
    RECOMMENDED_LATER("Recommended later"),
}

data class LearningLessonJourneyState(
    val lesson: LearningLesson,
    val status: LearningLessonStatus,
    val prerequisitesMet: Boolean,
    val completedCheckpointIds: Set<String>,
    val currentCheckpoint: LearningCheckpoint?,
) {
    val completedCheckpointCount: Int
        get() = lesson.checkpoints.count { it.id in completedCheckpointIds }
}

/**
 * Pure learning-state rules. These rules never infer physical safety, understanding, or certification
 * from a connection or process status.
 */
object LearningJourneyEvaluator {
    fun observableCheckpointIds(
        runtime: AcademyRuntimeSnapshot,
        previouslyCompleted: Set<String>,
    ): Set<String> = if (!runtime.isAvailable) emptySet() else buildSet {
        if (runtime.isLocalSimulatorSelected) {
            add(FirstMissionCheckpointIds.LOCAL_SIM_SELECTED)
        }
        if (runtime.isSimulatorRunning) {
            add(FirstMissionCheckpointIds.SIMULATOR_RUNNING)
        }
        if (
            runtime.isLocalSimulatorSelected &&
            runtime.isLocalSimulatorOnline &&
            runtime.isNt4Connected
        ) {
            add(FirstMissionCheckpointIds.LOCAL_SIM_CONNECTED)
        }
        if (
            FirstMissionCheckpointIds.SIMULATOR_RUNNING in previouslyCompleted &&
            !runtime.isSimulatorRunning
        ) {
            add(FirstMissionCheckpointIds.SIMULATOR_STOPPED)
        }
        if (runtime.subsystem.isAvailable) {
            if (runtime.subsystem.hasPositionMechanismDraft) {
                add(SubsystemMissionCheckpointIds.POSITION_DRAFT)
            }
            if (runtime.subsystem.hasNaturalStateContract) {
                add(SubsystemMissionCheckpointIds.NATURAL_STATE)
            }
            if (runtime.subsystem.hasCompleteSafetyContract) {
                add(SubsystemMissionCheckpointIds.SAFETY_CONTRACT)
            }
            if (runtime.subsystem.hasSimulationAndVerification) {
                add(SubsystemMissionCheckpointIds.SIMULATION_VERIFICATION)
            }
            if (runtime.subsystem.isReviewingGeneratedArtifacts) {
                add(SubsystemMissionCheckpointIds.ARTIFACT_REVIEW)
            }
            if (runtime.subsystem.hasSavedCanonicalDescriptor) {
                add(SubsystemMissionCheckpointIds.DESCRIPTOR_SAVED)
            }
        }
        if (runtime.controls.isAvailable) {
            if (runtime.controls.hasActionCatalog) add(ControlsMissionCheckpointIds.ACTION_CATALOG)
            if (runtime.controls.hasGeneratedSubsystemCapability) add(ControlsMissionCheckpointIds.SUBSYSTEM_CAPABILITY)
            if (runtime.controls.hasMappedControlSelection) add(ControlsMissionCheckpointIds.PLATFORM_MAPPING)
            if (runtime.controls.hasValidAppliedBinding) add(ControlsMissionCheckpointIds.BINDING_APPLIED)
            if (runtime.controls.hasSavedControlScheme) add(ControlsMissionCheckpointIds.SCHEME_SAVED)
            if (runtime.controls.hasGeneratedBindings) add(ControlsMissionCheckpointIds.BINDINGS_GENERATED)
        }
        if (runtime.tuning.isAvailable) {
            if (runtime.tuning.hasTypedCatalog) add(TuningMissionCheckpointIds.TYPED_CATALOG)
            if (runtime.tuning.hasFeedforwardDeclaration) add(TuningMissionCheckpointIds.FEEDFORWARD_DECLARATION)
            if (runtime.tuning.hasValidProposal) add(TuningMissionCheckpointIds.VALID_PROPOSAL)
            if (runtime.tuning.hasProposalProvenance) add(TuningMissionCheckpointIds.PROVENANCE)
            if (runtime.tuning.hasStructuredReview) add(TuningMissionCheckpointIds.STRUCTURED_REVIEW)
            if (runtime.tuning.hasPromotableReview) add(TuningMissionCheckpointIds.REVIEW_READY)
        }
        if (runtime.superstructure.isAvailable) {
            if (runtime.superstructure.hasSeveralGeneratedSubsystems) add(SuperstructureMissionCheckpointIds.SUBSYSTEMS_READY)
            if (runtime.superstructure.hasCompletePostures) add(SuperstructureMissionCheckpointIds.COMPLETE_POSTURES)
            if (runtime.superstructure.hasExplicitTransitions) add(SuperstructureMissionCheckpointIds.TRANSITIONS)
            if (runtime.superstructure.hasFailSafePolicy) add(SuperstructureMissionCheckpointIds.FAIL_SAFE_POLICY)
            if (runtime.superstructure.hasDeterministicPreview) add(SuperstructureMissionCheckpointIds.PREVIEW)
            if (runtime.superstructure.hasFaultInjectionEvidence) add(SuperstructureMissionCheckpointIds.FAULT_INJECTION)
            if (runtime.superstructure.hasStructuredReview) add(SuperstructureMissionCheckpointIds.STRUCTURED_REVIEW)
            if (runtime.superstructure.hasSavedCanonicalDocument) add(SuperstructureMissionCheckpointIds.DOCUMENT_SAVED)
        }
        if (runtime.autonomous.isAvailable) {
            if (runtime.autonomous.hasProjectCapabilities) add(AutonomousMissionCheckpointIds.CAPABILITIES)
            if (runtime.autonomous.hasRoutineSteps) add(AutonomousMissionCheckpointIds.STEPS)
            if (runtime.autonomous.hasValidRoutine) add(AutonomousMissionCheckpointIds.VALID)
            if (runtime.autonomous.hasKinematicPreview) add(AutonomousMissionCheckpointIds.KINEMATIC_PREVIEW)
            if (runtime.autonomous.hasAutonomousSelectorEntry) add(AutonomousMissionCheckpointIds.SELECTOR_ENTRY)
            if (runtime.autonomous.hasSavedCanonicalRevision) add(AutonomousMissionCheckpointIds.SAVED)
            if (runtime.autonomous.hasGeneratedProject) add(AutonomousMissionCheckpointIds.GENERATED)
        }
        if (runtime.runAnalysis.isAvailable) {
            if (runtime.runAnalysis.hasWorkspaceRuns) add(RunImportMissionCheckpointIds.RUNS_AVAILABLE)
            if (runtime.runAnalysis.hasSelectedRun) add(RunImportMissionCheckpointIds.RUN_SELECTED)
            if (runtime.runAnalysis.hasSourceEvidence) add(RunImportMissionCheckpointIds.SOURCE_EVIDENCE)
            if (runtime.runAnalysis.hasGuidedReport) add(RunAnalysisMissionCheckpointIds.REPORT_READY)
            if (runtime.runAnalysis.hasQuantitativeEvidence) add(RunAnalysisMissionCheckpointIds.QUANTITATIVE_EVIDENCE)
            if (runtime.runAnalysis.hasBaselineComparison) add(RunAnalysisMissionCheckpointIds.BASELINE_COMPARISON)
            if (runtime.runAnalysis.hasLimitations) add(RunAnalysisMissionCheckpointIds.LIMITATIONS)
            if (runtime.runAnalysis.hasExportedReport) add(RunAnalysisMissionCheckpointIds.EXPORTED)
        }
        if (runtime.graduation.isAvailable) {
            if (runtime.graduation.hasProjectIdentity) add(GraduationMissionCheckpointIds.PROJECT_IDENTITY)
            if (runtime.graduation.hasAuthoringStagesReady) add(GraduationMissionCheckpointIds.AUTHORING_READY)
            if (runtime.graduation.hasVerifiedBuild) add(GraduationMissionCheckpointIds.VERIFIED_BUILD)
            if (runtime.graduation.hasRunningProjectSimulator) add(GraduationMissionCheckpointIds.SIMULATOR_RUNNING)
            if (
                GraduationMissionCheckpointIds.SIMULATOR_RUNNING in previouslyCompleted &&
                !runtime.graduation.hasRunningProjectSimulator
            ) {
                add(GraduationMissionCheckpointIds.SIMULATOR_STOPPED)
            }
            if (runtime.graduation.hasImportedRunEvidence) add(GraduationMissionCheckpointIds.RUN_EVIDENCE)
        }
    }

    fun lessonState(
        lesson: LearningLesson,
        progress: LearningProgressView,
    ): LearningLessonJourneyState {
        val prerequisitesMet = lesson.prerequisiteLessonIds.all { it in progress.practicedLessonIds }
        val relevantCompleted = progress.completedCheckpointIds.intersect(lesson.checkpoints.mapTo(mutableSetOf()) { it.id })
        val status = when {
            lesson.id in progress.practicedLessonIds -> LearningLessonStatus.PRACTICED
            !prerequisitesMet -> LearningLessonStatus.RECOMMENDED_LATER
            lesson.id in progress.startedLessonIds || relevantCompleted.isNotEmpty() -> LearningLessonStatus.IN_PROGRESS
            else -> LearningLessonStatus.NOT_STARTED
        }
        return LearningLessonJourneyState(
            lesson = lesson,
            status = status,
            prerequisitesMet = prerequisitesMet,
            completedCheckpointIds = relevantCompleted,
            currentCheckpoint = lesson.checkpoints.firstOrNull { it.id !in relevantCompleted },
        )
    }

    fun recommendedLesson(path: LearningPath, progress: LearningProgressView): LearningLesson? {
        val lessons = path.lessonIds.mapNotNull(LearningCatalog::lesson)
        val next = lessons.firstOrNull { it.id !in progress.practicedLessonIds } ?: return null
        return firstUnpracticedPrerequisite(next, progress, linkedSetOf()) ?: next
    }

    private fun firstUnpracticedPrerequisite(
        lesson: LearningLesson,
        progress: LearningProgressView,
        visiting: MutableSet<String>,
    ): LearningLesson? {
        check(visiting.add(lesson.id)) { "Learning prerequisite cycle includes '${lesson.id}'" }
        for (prerequisiteId in lesson.prerequisiteLessonIds) {
            if (prerequisiteId in progress.practicedLessonIds) continue
            val prerequisite = requireNotNull(LearningCatalog.lesson(prerequisiteId)) {
                "Learning lesson '${lesson.id}' references missing prerequisite '$prerequisiteId'"
            }
            return firstUnpracticedPrerequisite(prerequisite, progress, visiting) ?: prerequisite
        }
        visiting.remove(lesson.id)
        return null
    }
}

object FirstMissionCheckpointIds {
    const val LOCAL_SIM_SELECTED = "first-mission.local-sim-selected"
    const val SIMULATOR_RUNNING = "first-mission.simulator-running"
    const val LOCAL_SIM_CONNECTED = "first-mission.local-sim-connected"
    const val IDENTIFIED_DATA_SOURCE = "first-mission.identified-data-source"
    const val SIMULATOR_STOPPED = "first-mission.simulator-stopped"
}

object DrivebaseMissionCheckpointIds {
    const val TYPE_SELECTED = "drivebase-blueprint.type-selected"
    const val DIMENSIONS_SET = "drivebase-blueprint.dimensions-set"
    const val BLUEPRINT_SAVED = "drivebase-blueprint.blueprint-saved"
}

object SubsystemMissionCheckpointIds {
    const val POSITION_DRAFT = "safe-subsystem.position-draft"
    const val NATURAL_STATE = "safe-subsystem.natural-state"
    const val SAFETY_CONTRACT = "safe-subsystem.safety-contract"
    const val SIMULATION_VERIFICATION = "safe-subsystem.simulation-verification"
    const val ARTIFACT_REVIEW = "safe-subsystem.artifact-review"
    const val DESCRIPTOR_SAVED = "safe-subsystem.descriptor-saved"
    const val HOMING_REASONING = "safe-subsystem.homing-reasoning"
    const val RUNTIME_FLOW = "safe-subsystem.runtime-flow"
}

object ControlsMissionCheckpointIds {
    const val ACTION_CATALOG = "map-one-control.action-catalog"
    const val SUBSYSTEM_CAPABILITY = "map-one-control.subsystem-capability"
    const val PLATFORM_MAPPING = "map-one-control.platform-mapping"
    const val BINDING_APPLIED = "map-one-control.binding-applied"
    const val BINDING_POLICY = "map-one-control.binding-policy"
    const val SCHEME_SAVED = "map-one-control.scheme-saved"
    const val BINDINGS_GENERATED = "map-one-control.bindings-generated"
    const val RUNTIME_FLOW = "map-one-control.runtime-flow"
}

object TuningMissionCheckpointIds {
    const val TYPED_CATALOG = "tuning-evidence.typed-catalog"
    const val FEEDFORWARD_DECLARATION = "tuning-evidence.feedforward-declaration"
    const val MODEL_PREDICTION = "tuning-evidence.model-prediction"
    const val VALID_PROPOSAL = "tuning-evidence.valid-proposal"
    const val PROVENANCE = "tuning-evidence.provenance"
    const val APPLY_POLICY = "tuning-evidence.apply-policy"
    const val STRUCTURED_REVIEW = "tuning-evidence.structured-review"
    const val REVIEW_READY = "tuning-evidence.review-ready"
    const val EVIDENCE_BOUNDARY = "tuning-evidence.boundary"
}

object SuperstructureMissionCheckpointIds {
    const val SUBSYSTEMS_READY = "coordinate-mechanisms.subsystems-ready"
    const val COMPLETE_POSTURES = "coordinate-mechanisms.complete-postures"
    const val POSTURE_REASONING = "coordinate-mechanisms.posture-reasoning"
    const val TRANSITIONS = "coordinate-mechanisms.transitions"
    const val FAIL_SAFE_POLICY = "coordinate-mechanisms.fail-safe-policy"
    const val PREVIEW = "coordinate-mechanisms.preview"
    const val FAULT_INJECTION = "coordinate-mechanisms.fault-injection"
    const val STRUCTURED_REVIEW = "coordinate-mechanisms.structured-review"
    const val DOCUMENT_SAVED = "coordinate-mechanisms.document-saved"
    const val FAILURE_REASONING = "coordinate-mechanisms.failure-reasoning"
}

object StateflowCanvasMissionCheckpointIds {
    const val NODES_POSITIONED = "stateflow-canvas-mastery.nodes-positioned"
    const val BEZIER_TRANSITIONS = "stateflow-canvas-mastery.bezier-transitions"
    const val CONFLICT_LOCKS = "stateflow-canvas-mastery.conflict-locks"
}

object AutonomousMissionCheckpointIds {
    const val CAPABILITIES = "first-routine.capabilities"
    const val STARTING_PREDICTION = "first-routine.starting-prediction"
    const val STEPS = "first-routine.steps"
    const val VALID = "first-routine.valid"
    const val KINEMATIC_PREVIEW = "first-routine.kinematic-preview"
    const val SELECTOR_ENTRY = "first-routine.selector-entry"
    const val SAVED = "first-routine.saved"
    const val GENERATED = "first-routine.generated"
    const val EVIDENCE_BOUNDARY = "first-routine.evidence-boundary"
}

object RunImportMissionCheckpointIds {
    const val RUNS_AVAILABLE = "bring-in-run.runs-available"
    const val RUN_SELECTED = "bring-in-run.run-selected"
    const val SOURCE_EVIDENCE = "bring-in-run.source-evidence"
    const val EVIDENCE_DESCRIPTION = "bring-in-run.evidence"
}

object RunAnalysisMissionCheckpointIds {
    const val REPORT_READY = "compare-run-evidence.report-ready"
    const val QUANTITATIVE_EVIDENCE = "compare-run-evidence.quantitative-evidence"
    const val BASELINE_COMPARISON = "compare-run-evidence.baseline-comparison"
    const val LIMITATIONS = "compare-run-evidence.limitations"
    const val EXPORTED = "compare-run-evidence.exported"
    const val CLAIM = "compare-run-evidence.claim"
}

object GraduationMissionCheckpointIds {
    const val PROJECT_IDENTITY = "generated-kotlin-graduation.project-identity"
    const val AUTHORING_READY = "generated-kotlin-graduation.authoring-ready"
    const val OWNERSHIP_REASONING = "generated-kotlin-graduation.ownership-reasoning"
    const val VERIFIED_BUILD = "generated-kotlin-graduation.verified-build"
    const val GENERATED_FLOW = "generated-kotlin-graduation.generated-flow"
    const val SIMULATOR_RUNNING = "generated-kotlin-graduation.simulator-running"
    const val SIMULATOR_STOPPED = "generated-kotlin-graduation.simulator-stopped"
    const val RUN_EVIDENCE = "generated-kotlin-graduation.run-evidence"
    const val VALIDATION_BOUNDARY = "generated-kotlin-graduation.validation-boundary"
}
