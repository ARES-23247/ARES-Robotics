package com.ares.analytics.ui.help

import com.ares.analytics.viewmodel.superstructure.PreviewPortCondition
import com.ares.analytics.viewmodel.superstructure.SuperstructureStudioState

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
