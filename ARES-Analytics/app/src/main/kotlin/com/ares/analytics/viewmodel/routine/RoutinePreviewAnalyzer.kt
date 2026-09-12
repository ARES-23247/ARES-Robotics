package com.ares.analytics.viewmodel.routine

import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineDriveStep
import com.areslib.routine.RoutinePose
import com.areslib.routine.RoutineStep
import com.areslib.routine.RoutineStepKind

private const val MAX_ROUTINE_PREVIEW_STEPS = 4_096
private const val MAX_ROUTINE_PREVIEW_DEPTH = 64

internal data class RoutinePreviewAnalysis(
    val steps: List<RoutineStep>,
    val drives: List<RoutineDriveStep>,
    val warning: String?,
)

/** Expands only deterministic routine control flow into a serial drive preview. */
internal fun analyzeRoutinePreview(
    root: RoutineDocument,
    availableRoutines: List<RoutineDocument>,
): RoutinePreviewAnalysis {
    // The edited root intentionally replaces its saved copy. Other duplicate IDs are
    // ambiguous only when called; unrelated library entries do not affect this preview.
    val routinesById = availableRoutines.filter { it.documentId != root.documentId }
        .groupBy(RoutineDocument::documentId)
    val deterministicSteps = mutableListOf<RoutineStep>()
    val drives = mutableListOf<RoutineDriveStep>()
    val activeCalls = linkedSetOf(root.documentId)
    var expandedSteps = 0

    fun visit(steps: List<RoutineStep>, depth: Int): String? {
        if (depth > MAX_ROUTINE_PREVIEW_DEPTH) {
            return "Preview unavailable: routine nesting exceeds $MAX_ROUTINE_PREVIEW_DEPTH levels."
        }
        for (step in steps) {
            expandedSteps++
            if (expandedSteps > MAX_ROUTINE_PREVIEW_STEPS) {
                return "Preview unavailable: expanded routine exceeds $MAX_ROUTINE_PREVIEW_STEPS steps."
            }
            when (step.kind) {
                RoutineStepKind.DRIVE_TO -> {
                    routinePreviewStepWarning(step)?.let { return "Preview unavailable: $it." }
                    deterministicSteps += step
                    step.drive?.let(drives::add)
                }
                RoutineStepKind.REPEAT -> {
                    val count = step.repeatCount
                        ?: return "Preview unavailable: repeat count is missing."
                    if (count < 0) return "Preview unavailable: repeat count must be non-negative."
                    if (count > MAX_ROUTINE_PREVIEW_STEPS) {
                        return "Preview unavailable: repeat count exceeds $MAX_ROUTINE_PREVIEW_STEPS."
                    }
                    if (step.children.isNotEmpty()) {
                        repeat(count) {
                            visit(step.children, depth + 1)?.let { return it }
                        }
                    }
                }
                RoutineStepKind.CALL -> {
                    val routineId = step.routineId
                        ?: return "Preview unavailable: called routine ID is missing."
                    val candidates = if (routineId == root.documentId) listOf(root) else routinesById[routineId]
                        ?: return "Preview unavailable: called routine '$routineId' is not loaded."
                    if (candidates.size != 1) {
                        return "Preview unavailable: called routine '$routineId' has duplicate IDs."
                    }
                    val called = candidates.single()
                    if (!activeCalls.add(routineId)) {
                        return "Preview unavailable: routine call cycle includes '$routineId'."
                    }
                    val warning = visit(called.steps, depth + 1)
                    activeCalls.remove(routineId)
                    if (warning != null) return warning
                }
                RoutineStepKind.BRANCH,
                RoutineStepKind.TOGETHER,
                RoutineStepKind.FIRST_TO_FINISH,
                RoutineStepKind.DEADLINE,
                -> return compositePreviewWarning(step.kind)
                RoutineStepKind.ACTION,
                RoutineStepKind.WAIT,
                RoutineStepKind.WAIT_UNTIL,
                -> {
                    routinePreviewStepWarning(step)?.let { return "Preview unavailable: $it." }
                    deterministicSteps += step
                }
            }
        }
        return null
    }

    val warning = visit(root.steps, depth = 0)
    if (warning != null) return RoutinePreviewAnalysis(emptyList(), emptyList(), warning)
    return RoutinePreviewAnalysis(
        steps = deterministicSteps.toList(),
        drives = drives,
        warning = warning,
    )
}

/** Structural checks shared by direct compilation and active control-flow expansion. */
internal fun routinePreviewStepWarning(step: RoutineStep): String? = when (step.kind) {
    RoutineStepKind.ACTION ->
        if (step.actionKey.isNullOrBlank()) "action key is missing" else null
    RoutineStepKind.WAIT ->
        if (step.durationSeconds?.let { it.isFinite() && it >= 0.0 } != true)
            "wait duration must be finite and non-negative" else null
    RoutineStepKind.WAIT_UNTIL ->
        if (step.timeoutSeconds?.let { it.isFinite() && it > 0.0 } != true)
            "wait-until timeout must be finite and positive" else null
    RoutineStepKind.DRIVE_TO ->
        if (step.drive?.target?.isFinitePreviewPose() != true) "drive target must be present and finite" else null
    else -> "unsupported ${step.kind} step"
}

internal fun RoutinePose.isFinitePreviewPose(): Boolean =
    xMeters.isFinite() && yMeters.isFinite() && headingRadians.isFinite()

private fun compositePreviewWarning(kind: RoutineStepKind): String {
    val label = when (kind) {
        RoutineStepKind.BRANCH -> "Branch"
        RoutineStepKind.TOGETHER -> "Parallel group"
        RoutineStepKind.FIRST_TO_FINISH -> "First-to-finish group"
        RoutineStepKind.DEADLINE -> "Deadline group"
        else -> kind.name
    }
    return "Preview unavailable: $label has multiple possible timelines. " +
        "Select a runtime scenario before showing its route or duration."
}
