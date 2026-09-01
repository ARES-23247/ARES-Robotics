package com.ares.analytics.ui.help

import com.areslib.routine.RoutineValidationIssue

data class RoutineValidationLearningLink(
    val lessonId: String,
    val checkpointId: String,
)

/** Stable validation-code links; user-facing diagnostic text is deliberately not parsed. */
object RoutineValidationLearningLinks {
    val byCode: Map<String, RoutineValidationLearningLink> = buildMap {
        fun codes(checkpointId: String, vararg codes: String) {
            codes.forEach { code -> put(code, RoutineValidationLearningLink("first-routine", checkpointId)) }
        }
        codes(
            AutonomousMissionCheckpointIds.STEPS,
            "empty_routine", "routine_too_large", "routine_expansion_too_large", "routine_too_deep",
            "invalid_step_id", "duplicate_step_id", "conflicting_payload", "invalid_repeat_count",
            "empty_group", "missing_deadline", "empty_branch", "recursive_routine_call",
            "parallel_resource_conflict", "too_many_arguments", "arguments_too_large",
            "invalid_argument_key", "argument_too_long",
        )
        codes(
            AutonomousMissionCheckpointIds.CAPABILITIES,
            "invalid_action_key", "unknown_action", "invalid_condition_key", "unknown_condition",
            "invalid_routine_id", "missing_routine",
        )
        codes(
            AutonomousMissionCheckpointIds.VALID,
            "missing_drive", "invalid_drive_target", "invalid_motion_preset", "invalid_trajectory_engine",
            "invalid_marker_progress", "too_many_drive_references", "invalid_wait", "missing_wait_timeout",
        )
        codes(
            AutonomousMissionCheckpointIds.VALID,
            "unsupported_schema", "invalid_document_id", "invalid_revision", "invalid_parent_hash",
            "missing_name", "name_too_long", "description_too_long",
        )
    }

    fun forIssue(issue: RoutineValidationIssue): RoutineValidationLearningLink? = byCode[issue.code]
}
