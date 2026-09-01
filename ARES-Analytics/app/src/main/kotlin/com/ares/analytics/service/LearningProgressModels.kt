package com.ares.analytics.service

import com.ares.analytics.ui.help.LearningProgressView
import com.ares.analytics.ui.help.LearningRubricRating
import kotlinx.serialization.Serializable
import java.io.File

/** Durable, local-only progress for the in-app Help & Learn lessons. */
@Serializable
data class LearningProgress(
    val contentVersion: Int = CURRENT_LEARNING_CONTENT_VERSION,
    override val practicedLessonIds: Set<String> = emptySet(),
    override val startedLessonIds: Set<String> = emptySet(),
    override val completedCheckpointIds: Set<String> = emptySet(),
    override val activeLessonId: String? = null,
    val selectedPathId: String? = null,
    val firstMissionNudgeDismissed: Boolean = false,
    val studentDisplayName: String = "",
    val checkpointReflections: Map<String, String> = emptyMap(),
    val mentorNotes: Map<String, String> = emptyMap(),
    val rubricRatings: Map<String, LearningRubricRating> = emptyMap(),
) : LearningProgressView

/** A locally authored assignment. It is guidance, not proof that a checkpoint was completed. */
@Serializable
data class AcademyLearningAssignment(
    val assignmentId: String,
    val title: String,
    val pathId: String,
    val lessonIds: List<String>,
    val instructions: String = "",
    val dueLabel: String = "",
    val completed: Boolean = false,
    val createdAtEpochMs: Long,
)

/** One isolated learner record. Records are switched explicitly and never merged automatically. */
@Serializable
data class AcademyLearnerRecord(
    val learnerId: String,
    val progress: LearningProgress = LearningProgress(),
    val assignments: List<AcademyLearningAssignment> = emptyList(),
)

@Serializable
data class AcademyClassroomStore(
    val schemaVersion: Int = ACADEMY_CLASSROOM_SCHEMA_VERSION,
    val activeLearnerId: String,
    val learners: List<AcademyLearnerRecord>,
)

data class AcademyProgressSnapshot(
    val file: File,
    val learnerId: String,
    val pathId: String,
)
