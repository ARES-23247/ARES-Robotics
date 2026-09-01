package com.ares.analytics.ui.help

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoutineValidationLearningLinksTest {
    @Test
    fun `every routine validation learning link resolves to an enumerated lesson checkpoint`() {
        val expectedCodes = setOf(
            "empty_routine", "routine_too_large", "routine_expansion_too_large", "routine_too_deep",
            "invalid_step_id", "duplicate_step_id", "conflicting_payload", "invalid_repeat_count",
            "empty_group", "missing_deadline", "empty_branch", "recursive_routine_call",
            "parallel_resource_conflict", "too_many_arguments", "arguments_too_large",
            "invalid_argument_key", "argument_too_long", "invalid_action_key", "unknown_action",
            "invalid_condition_key", "unknown_condition", "invalid_routine_id", "missing_routine",
            "missing_drive", "invalid_drive_target", "invalid_motion_preset", "invalid_trajectory_engine",
            "invalid_marker_progress", "too_many_drive_references", "invalid_wait", "missing_wait_timeout",
            "unsupported_schema", "invalid_document_id", "invalid_revision", "invalid_parent_hash",
            "missing_name", "name_too_long", "description_too_long",
        )
        assertEquals(expectedCodes, RoutineValidationLearningLinks.byCode.keys)
        RoutineValidationLearningLinks.byCode.forEach { (code, link) ->
            val lesson = assertNotNull(LearningCatalog.lesson(link.lessonId), "Missing lesson for $code")
            assertTrue(lesson.checkpoints.any { it.id == link.checkpointId }, "Missing checkpoint for $code")
        }
    }
}
