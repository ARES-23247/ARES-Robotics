package com.ares.analytics.domain.learning

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
