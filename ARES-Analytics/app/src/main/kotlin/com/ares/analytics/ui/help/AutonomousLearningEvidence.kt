package com.ares.analytics.ui.help

import com.ares.analytics.service.AresGenerationPhase
import com.ares.analytics.viewmodel.PathPlannerState
import com.areslib.routine.RoutineValidationSeverity

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
