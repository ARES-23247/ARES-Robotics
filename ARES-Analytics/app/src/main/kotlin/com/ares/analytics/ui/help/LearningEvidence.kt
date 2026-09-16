package com.ares.analytics.ui.help

import com.ares.analytics.domain.learning.AcademyAutonomousSnapshot
import com.ares.analytics.domain.learning.AcademyControlsSnapshot
import com.ares.analytics.domain.learning.AcademyGraduationSnapshot
import com.ares.analytics.domain.learning.AcademyRunAnalysisSnapshot
import com.ares.analytics.domain.learning.AcademySubsystemSnapshot
import com.ares.analytics.domain.learning.AcademySuperstructureSnapshot
import com.ares.analytics.domain.learning.AcademyTuningSnapshot
import com.ares.analytics.service.AresGenerationPhase
import com.ares.analytics.viewmodel.PathPlannerState
import com.ares.analytics.viewmodel.SubsystemBuilderStage
import com.ares.analytics.viewmodel.SubsystemGeneratorState
import com.ares.analytics.viewmodel.SubsystemProblemSeverity
import com.ares.analytics.viewmodel.TuningState
import com.ares.analytics.viewmodel.controls.ControlsEditorState
import com.ares.analytics.viewmodel.controls.ControlsProblemSeverity
import com.ares.analytics.viewmodel.robotstudio.RobotStudioStageId
import com.ares.analytics.viewmodel.robotstudio.RobotStudioStageStatus
import com.ares.analytics.viewmodel.robotstudio.RobotStudioState
import com.ares.analytics.viewmodel.runanalysis.GuidedRunAnalysisState
import com.ares.analytics.viewmodel.superstructure.PreviewPortCondition
import com.ares.analytics.viewmodel.superstructure.SuperstructureStudioState
import com.areslib.catalog.CapabilityContext
import com.areslib.controls.ControlTargetKind
import com.areslib.routine.RoutineValidationSeverity
import com.areslib.subsystem.SubsystemControlStrategy
import com.areslib.subsystem.SubsystemFieldRole
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemHomingMethod
import com.areslib.subsystem.SubsystemMeasurementSource
import com.areslib.subsystem.SubsystemSimulationSupport
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.tuning.TuningParameterDeclaration

// === AutonomousLearningEvidence.kt ===
/** Maps the canonical routine editor to Academy facts; the trajectory is only kinematic evidence. */
fun PathPlannerState.toAcademyAutonomousSnapshot(): AcademyAutonomousSnapshot {
    val savedRevision = availableRoutines.any { saved ->
        saved.documentId == routine.documentId && saved.revision == routine.revision && saved == routine
    }
    return AcademyAutonomousSnapshot(
        isAvailable = projectMetadata != null,
        hasProjectCapabilities = capabilityCatalog != null,
        hasRoutineSteps = routine.steps.isNotEmpty(),
        hasValidRoutine = routine.steps.isNotEmpty() && routineValidation.none { it.severity == RoutineValidationSeverity.ERROR },
        hasKinematicPreview = trajectory != null && estimatedDuration > 0.0 && routinePreviewWarning == null,
        hasAutonomousSelectorEntry = availableInAutonomousSelector && autonomousEntry?.enabled == true,
        hasSavedCanonicalRevision = savedRevision,
        hasGeneratedProject = savedRevision && generationPhase == AresGenerationPhase.SUCCEEDED,
    )
}

// === ControlsLearningEvidence.kt ===
private fun String.isGeneratedSubsystemTargetAction(): Boolean =
    startsWith("subsystem.") && contains(".set.")

/** Maps real catalog, mapping, binding, save, and generation state to Academy facts. */
fun ControlsEditorState.toAcademyControlsSnapshot(): AcademyControlsSnapshot {
    val scheme = selectedScheme
    if (loadError != null || scheme == null || selectedProfile == null) {
        return AcademyControlsSnapshot.Unavailable
    }
    val generatedSubsystemActions = actions.filter { action ->
        action.key.isGeneratedSubsystemTargetAction() && CapabilityContext.TELEOP in action.allowedContexts
    }
    val generatedKeys = generatedSubsystemActions.mapTo(linkedSetOf()) { it.key }
    val selectedMapping = selectedControl?.mappings?.firstOrNull { it.platform == targetPlatform }
    val validBindings = scheme.bindings.filter { binding ->
        binding.enabled && binding.target.kind == ControlTargetKind.ACTION && binding.target.key in generatedKeys &&
            problems.none { it.severity == ControlsProblemSeverity.ERROR && it.bindingId == binding.bindingId }
    }
    val hasValidAppliedBinding = validBindings.isNotEmpty()
    val hasSavedBinding = hasValidAppliedBinding && !dirty && !draftHasUnappliedChanges

    return AcademyControlsSnapshot(
        isAvailable = true,
        hasActionCatalog = actions.isNotEmpty(),
        hasGeneratedSubsystemCapability = generatedSubsystemActions.isNotEmpty(),
        hasMappedControlSelection = selectedMapping != null,
        hasValidAppliedBinding = hasValidAppliedBinding,
        hasSavedControlScheme = hasSavedBinding,
        hasGeneratedBindings = hasSavedBinding && generationPhase == AresGenerationPhase.SUCCEEDED &&
            !generatedContentHash.isNullOrBlank(),
    )
}

// === RobotStudioLearningEvidence.kt ===
/** Converts the project-matched Robot Studio stage graph to narrow graduation evidence. */
fun RobotStudioState.toAcademyGraduationSnapshot(): AcademyGraduationSnapshot {
    val byId = stages.associateBy { it.id }
    val projectIdentity = byId[RobotStudioStageId.PROJECT_IDENTITY]?.status == RobotStudioStageStatus.READY
    val build = byId[RobotStudioStageId.GENERATE_VERIFY]
    return AcademyGraduationSnapshot(
        isAvailable = !loading && error == null && projectPath.isNotBlank(),
        hasProjectIdentity = projectIdentity,
        hasAuthoringStagesReady = projectIdentity && build != null &&
            build.status != RobotStudioStageStatus.BLOCKED && build.status != RobotStudioStageStatus.INVALID,
        hasVerifiedBuild = build?.status == RobotStudioStageStatus.READY,
        hasRunningProjectSimulator = byId[RobotStudioStageId.SIMULATE]?.status == RobotStudioStageStatus.RUNNING,
        hasImportedRunEvidence = byId[RobotStudioStageId.ANALYZE]?.status == RobotStudioStageStatus.READY,
    )
}

// === RunAnalysisLearningEvidence.kt ===
/** Maps workspace-scoped persisted-run analysis to narrow Academy evidence. */
fun GuidedRunAnalysisState.toAcademyRunAnalysisSnapshot(): AcademyRunAnalysisSnapshot {
    val selected = selectedSessionId?.takeIf { id -> sessions.any { it.sessionId == id } }
    val currentReport = report?.takeIf { it.session.sessionId == selected }
    val currentComparison = comparisonReport?.takeIf {
        it.primarySessionId == selected && it.sessions.map { session -> session.sessionId }.containsAll(comparisonSessionIds)
    }
    return AcademyRunAnalysisSnapshot(
        isAvailable = !loadingSessions && error == null,
        hasWorkspaceRuns = sessions.isNotEmpty(),
        hasSelectedRun = selected != null,
        hasSourceEvidence = currentReport?.source?.explanation?.isNotBlank() == true,
        hasGuidedReport = currentReport != null,
        hasQuantitativeEvidence = currentReport?.metrics?.isNotEmpty() == true || currentComparison?.metrics?.isNotEmpty() == true,
        hasBaselineComparison = currentReport?.comparison != null || currentComparison?.sessions?.size?.let { it >= 2 } == true,
        hasLimitations = currentReport?.limitations?.isNotEmpty() == true || currentComparison?.limitations?.isNotEmpty() == true,
        hasExportedReport =
            (exportMessage?.startsWith("Saved ") == true && currentReport != null) ||
                (comparisonExportMessage?.startsWith("Saved ") == true && currentComparison != null),
    )
}

// === SubsystemLearningEvidence.kt ===
/** Maps the real canonical builder state to the narrow facts Robot Academy may record. */
fun SubsystemGeneratorState.toAcademySubsystemSnapshot(): AcademySubsystemSnapshot {
    val document = draft?.document ?: return AcademySubsystemSnapshot.Unavailable
    val errorsAbsent = loadError == null && problems.none { it.severity == SubsystemProblemSeverity.ERROR }
    val motor = document.hardware.firstOrNull { it.kind == SubsystemHardwareKind.MOTOR && it.following == null }
    val motorMeasurements = motor?.measurements.orEmpty()
    val positionLoop = document.controlLoops.firstOrNull {
        it.strategy in setOf(
            SubsystemControlStrategy.POSITION_PID,
            SubsystemControlStrategy.PROFILED_POSITION_PID,
        ) && it.actuatorId == motor?.hardwareId
    }
    val target = positionLoop?.targetFieldId?.let { id -> document.stateFields.firstOrNull { it.fieldId == id } }
    val measuredPosition = positionLoop?.measurementFieldId?.let { id ->
        document.stateFields.firstOrNull { it.fieldId == id && it.role == SubsystemFieldRole.MEASUREMENT }
    }
    val positionMeasurement = motorMeasurements.firstOrNull {
        it.source == SubsystemMeasurementSource.MOTOR_POSITION_NATIVE && it.fieldId == measuredPosition?.fieldId
    }
    val velocityMeasurement = motorMeasurements.firstOrNull {
        it.source == SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND
    }
    val currentMeasurement = motorMeasurements.firstOrNull {
        it.source == SubsystemMeasurementSource.MOTOR_CURRENT_AMPS
    }
    val homing = document.safety.homing
    val hasBoundedHoming = homing.method != SubsystemHomingMethod.NONE &&
        homing.actuatorId == motor?.hardwareId &&
        homing.searchOutput?.let { it.isFinite() && it != 0.0 } == true &&
        homing.evidence.isNotEmpty() &&
        homing.dwellMs > 0L && homing.timeoutMs > homing.dwellMs
    val hasSoftLimits = target?.minimum?.isFinite() == true &&
        target.maximum?.isFinite() == true && target.minimum!! < target.maximum!!
    val hasSafeNeutral = motor?.safeOutput?.let { it.isFinite() && it == 0.0 } == true
    val hasCurrentContract = document.safety.requiresCurrentMonitoring &&
        currentMeasurement != null &&
        currentMeasurement.validMinimum?.let { it.isFinite() && it >= 0.0 } == true
    val naturalStateReady = target?.role == SubsystemFieldRole.TARGET &&
        measuredPosition != null && positionMeasurement != null &&
        velocityMeasurement?.let { measurement ->
            document.stateFields.any { it.fieldId == measurement.fieldId && it.role == SubsystemFieldRole.MEASUREMENT }
        } == true &&
        currentMeasurement?.let { measurement ->
            document.stateFields.any { it.fieldId == measurement.fieldId && it.role == SubsystemFieldRole.MEASUREMENT }
        } == true
    val safetyReady = errorsAbsent && hasSafeNeutral && hasSoftLimits && hasBoundedHoming &&
        hasCurrentContract && document.safety.feedbackTimeoutMs?.let { it > 0L } == true &&
        document.safety.requiresConfigurationHealth && document.safety.latchOutputFaults &&
        document.safety.requiresExplicitNeutralRecovery
    val matchingSavedDocument = documents.any {
        it.uid == document.uid && it.documentId == document.documentId && it.revision == document.revision
    }

    return AcademySubsystemSnapshot(
        isAvailable = true,
        hasPositionMechanismDraft = errorsAbsent &&
            !document.displayName.filter(Char::isLetterOrDigit).startsWith("NewSubsystem", ignoreCase = true) &&
            document.template in setOf(
                SubsystemTemplate.POSITION_CONTROLLED_MECHANISM,
                SubsystemTemplate.HOMED_MECHANISM,
                SubsystemTemplate.ELEVATOR_LIFT,
                SubsystemTemplate.ARM_PIVOT,
                SubsystemTemplate.CURRENT_HOMED_MECHANISM,
                SubsystemTemplate.VELOCITY_HOMED_MECHANISM,
            ) && positionLoop != null,
        hasNaturalStateContract = errorsAbsent && naturalStateReady &&
            SubsystemBuilderStage.STATE_AND_BEHAVIOR in visitedStages,
        hasCompleteSafetyContract = safetyReady && SubsystemBuilderStage.SAFETY in visitedStages,
        hasSimulationAndVerification = errorsAbsent && document.generateMockIo && document.generateTest &&
            document.implementation.simulation.support == SubsystemSimulationSupport.GENERATED_MOCK &&
            SubsystemBuilderStage.SIMULATION_AND_TESTING in visitedStages,
        isReviewingGeneratedArtifacts = errorsAbsent && activeStage == SubsystemBuilderStage.REVIEW &&
            previewFiles.isNotEmpty(),
        hasSavedCanonicalDescriptor = errorsAbsent && !dirty && matchingSavedDocument,
    )
}

// === SuperstructureLearningEvidence.kt ===
/** Converts current Studio state into structural/runtime-preview evidence without inferring clearance. */
fun SuperstructureStudioState.toAcademySuperstructureSnapshot(): AcademySuperstructureSnapshot {
    val document = draft ?: return AcademySuperstructureSnapshot.Unavailable
    val expectedTargets = document.states.firstOrNull()?.subsystemTargets?.map { it.target }?.toSet().orEmpty()
    val completePostures = document.states.size >= 3 && expectedTargets.size >= 2 && document.states.all { state ->
        state.subsystemTargets.map { it.target }.toSet() == expectedTargets
    }
    val failSafe = validationErrors.isEmpty() &&
        document.faultStateId.isNotBlank() && document.disabledStateId.isNotBlank() &&
        (document.interlocks.isNotEmpty() || document.healthFallbacks.isNotEmpty())
    val previewSnapshot = preview
    return AcademySuperstructureSnapshot(
        isAvailable = projectPath.isNotBlank(),
        hasSeveralGeneratedSubsystems = generatedSubsystems.size >= 2,
        hasCompletePostures = completePostures,
        hasExplicitTransitions = document.transitions.isNotEmpty(),
        hasFailSafePolicy = failSafe,
        hasDeterministicPreview = previewSnapshot != null,
        hasFaultInjectionEvidence = previewSnapshot?.let { snapshot ->
            snapshot.isFaulted || snapshot.lastRejectionReason != null ||
                snapshot.ports.any { it.condition != PreviewPortCondition.HEALTHY }
        } == true,
        hasStructuredReview = review != null,
        hasSavedCanonicalDocument = saved != null && !dirty && savedContentHash != null && saved == document,
    )
}

// === TuningLearningEvidence.kt ===
private val feedforwardTokens = listOf(
    "feedforward",
    "static friction",
    "gravity compensation",
    ".ks",
    ".kv",
    ".ka",
    ".kg",
)

private fun TuningParameterDeclaration.isFeedforwardRelated(): Boolean {
    val searchable = "$key $displayName $description".lowercase()
    return feedforwardTokens.any(searchable::contains)
}

/** Maps the real typed tuning editor to narrow Academy evidence without inferring execution. */
fun TuningState.toAcademyTuningSnapshot(): AcademyTuningSnapshot {
    val profile = selectedProfile ?: return AcademyTuningSnapshot.Unavailable
    if (projectPath.isBlank() || catalog.isEmpty()) return AcademyTuningSnapshot.Unavailable

    val proposedRows = rows.filter { it.proposedTypedValue != null }
    val validProposal = proposedRows.isNotEmpty() && proposedRows.all { it.validationMessage == null }
    val hasProvenance = validProposal && proposedRows.all { row ->
        row.provenance?.let { it.source.isNotBlank() && it.note.isNotBlank() } == true
    }
    val currentReview = review
    return AcademyTuningSnapshot(
        isAvailable = profile.profileId.isNotBlank(),
        hasTypedCatalog = catalog.all { declaration ->
            declaration.uid.isNotBlank() && declaration.key.isNotBlank() && declaration.type.name.isNotBlank() &&
                declaration.defaultValue.let { value ->
                    listOf(value.doubleValue, value.intValue, value.booleanValue, value.textValue).count { it != null } == 1
                }
        },
        hasFeedforwardDeclaration = catalog.any { it.isFeedforwardRelated() && !it.unit.isNullOrBlank() },
        hasValidProposal = validProposal,
        hasProposalProvenance = hasProvenance,
        hasStructuredReview = currentReview?.changes?.isNotEmpty() == true,
        hasPromotableReview = currentReview?.canPromote == true,
    )
}
