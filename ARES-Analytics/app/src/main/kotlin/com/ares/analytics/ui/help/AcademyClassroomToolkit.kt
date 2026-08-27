package com.ares.analytics.ui.help

import com.ares.analytics.service.LearningProgress
import com.ares.analytics.service.AcademyLearningAssignment
import kotlinx.serialization.Serializable

@Serializable
enum class LearningRubricRating(val label: String, val explanation: String) {
    NOT_REVIEWED("Not reviewed", "A mentor has not reviewed this skill yet."),
    EMERGING("Emerging", "The student can proceed with direct prompts."),
    DEVELOPING("Developing", "The student can complete most steps and explain some evidence."),
    INDEPENDENT("Independent", "The student can choose evidence, explain limitations, and ask for review at safety boundaries."),
}

data class LearningRubricCriterion(
    val id: String,
    val title: String,
    val prompt: String,
    val boundary: String,
)

data class LearningPathProgressSummary(
    val path: LearningPath,
    val practicedLessons: Int,
    val startedLessons: Int,
    val completedCheckpoints: Int,
    val totalCheckpoints: Int,
    val recommendedLesson: LearningLesson?,
)

/** Pure classroom summaries and exports; no rating here is a certification or hardware approval. */
object AcademyClassroomToolkit {
    val rubricCriteria: List<LearningRubricCriterion> = listOf(
        LearningRubricCriterion(
            id = "evidence-source",
            title = "Identify the evidence source",
            prompt = "Can the student name the source, unit, timestamp/freshness boundary, and whether it is observed or inferred?",
            boundary = "A named topic or plot is evidence only for what it actually measures.",
        ),
        LearningRubricCriterion(
            id = "predict-test-explain",
            title = "Predict, test, then explain",
            prompt = "Does the student make a prediction before changing one thing and compare the result with named evidence?",
            boundary = "A teaching model or simulator result is not physical validation.",
        ),
        LearningRubricCriterion(
            id = "runtime-flow",
            title = "Trace the runtime flow",
            prompt = "Can the student trace input through a typed action, Redux state, controller, cached IO, and telemetry?",
            boundary = "Generated plumbing does not remove the need to understand ownership and safety gates.",
        ),
        LearningRubricCriterion(
            id = "safety-boundary",
            title = "Respect the safety boundary",
            prompt = "Can the student distinguish configuration review, simulation evidence, restrained testing, and field validation?",
            boundary = "Only an authorized team process can approve physical operation.",
        ),
        LearningRubricCriterion(
            id = "reversible-iteration",
            title = "Iterate reversibly",
            prompt = "Can the student preserve a baseline, make one bounded change, and describe how to roll it back?",
            boundary = "A promoted value still requires the project's normal review and validation process.",
        ),
    )

    fun pathSummary(pathId: String, progress: LearningProgressView): LearningPathProgressSummary {
        val path = requireNotNull(LearningCatalog.path(pathId)) { "Unknown learning path '$pathId'" }
        val lessons = path.lessonIds.mapNotNull(LearningCatalog::lesson)
        val checkpointIds = lessons.flatMap { lesson -> lesson.checkpoints.map(LearningCheckpoint::id) }
        return LearningPathProgressSummary(
            path = path,
            practicedLessons = lessons.count { it.id in progress.practicedLessonIds },
            startedLessons = lessons.count { it.id in progress.startedLessonIds },
            completedCheckpoints = checkpointIds.count { it in progress.completedCheckpointIds },
            totalCheckpoints = checkpointIds.size,
            recommendedLesson = LearningJourneyEvaluator.recommendedLesson(path, progress),
        )
    }

    fun markdownReport(
        progress: LearningProgress,
        pathId: String,
        mentorName: String,
    ): String {
        val summary = pathSummary(pathId, progress)
        val student = progress.studentDisplayName.ifBlank { "Student not named" }
        val mentor = mentorName.trim().ifBlank { "Mentor not named" }
        return buildString {
            appendLine("# ARES Robot Academy learning record")
            appendLine()
            appendLine("- Student: ${markdownText(student)}")
            appendLine("- Mentor: ${markdownText(mentor)}")
            appendLine("- Learning path: ${markdownText(summary.path.title)}")
            appendLine("- Lessons practiced: ${summary.practicedLessons} / ${summary.path.lessonIds.size}")
            appendLine("- Checkpoints recorded: ${summary.completedCheckpoints} / ${summary.totalCheckpoints}")
            appendLine()
            appendLine("> This local record is not a grade, certification, code review, or proof of physical robot safety. App-observed facts and student reflections are identified separately.")
            appendLine()
            appendLine("## Lessons")
            summary.path.lessonIds.mapNotNull(LearningCatalog::lesson).forEach { lesson ->
                val state = LearningJourneyEvaluator.lessonState(lesson, progress)
                appendLine()
                appendLine("### ${markdownText(lesson.title)}")
                appendLine("- Status: ${state.status.label}")
                appendLine("- Checkpoints: ${state.completedCheckpointCount} / ${lesson.checkpoints.size}")
                lesson.checkpoints.filter { it.id in state.completedCheckpointIds }.forEach { checkpoint ->
                    val source = if (checkpoint.evidence == LearningCheckpointEvidence.SELF_REPORTED) "student reflection" else "app-observed fact"
                    appendLine("  - [$source] ${markdownText(checkpoint.title)}")
                    progress.checkpointReflections[checkpoint.id]?.let { reflection ->
                        appendLine("    - Reflection: ${markdownText(reflection)}")
                    }
                }
                progress.mentorNotes[lesson.id]?.let { note ->
                    appendLine("- Mentor note: ${markdownText(note)}")
                }
            }
            appendLine()
            appendLine("## Mentor rubric")
            rubricCriteria.forEach { criterion ->
                val rating = progress.rubricRatings[criterion.id] ?: LearningRubricRating.NOT_REVIEWED
                appendLine("- ${criterion.title}: ${rating.label}")
                appendLine("  - Review prompt: ${criterion.prompt}")
                appendLine("  - Boundary: ${criterion.boundary}")
            }
            appendLine()
            appendLine("## Suggested next lesson")
            appendLine(summary.recommendedLesson?.title ?: "Every lesson in this path is marked practiced; choose a new path or revisit a lesson with new evidence.")
        }
    }

    fun assignmentWorksheet(
        assignment: AcademyLearningAssignment,
        studentName: String,
    ): String {
        val path = requireNotNull(LearningCatalog.path(assignment.pathId)) {
            "Unknown learning path '${assignment.pathId}'"
        }
        val lessons = assignment.lessonIds.map { lessonId ->
            requireNotNull(LearningCatalog.lesson(lessonId)) { "Unknown lesson '$lessonId'" }
        }
        return buildString {
            appendLine("# ARES Robot Academy assignment")
            appendLine()
            appendLine("- Student: ${markdownText(studentName.ifBlank { "Student not named" })}")
            appendLine("- Assignment: ${markdownText(assignment.title)}")
            appendLine("- Learning path: ${markdownText(path.title)}")
            appendLine("- Due: ${markdownText(assignment.dueLabel.ifBlank { "No due date set" })}")
            appendLine()
            appendLine("> This worksheet guides practice. It is not a grade, certification, code review, or proof of physical robot safety.")
            appendLine()
            if (assignment.instructions.isNotBlank()) {
                appendLine("## Mentor instructions")
                appendLine(markdownText(assignment.instructions))
                appendLine()
            }
            appendLine("## Lessons")
            lessons.forEachIndexed { index, lesson ->
                appendLine()
                appendLine("### ${index + 1}. ${markdownText(lesson.title)}")
                appendLine(lesson.outcome)
                appendLine()
                appendLine("Prediction: ______________________________________________")
                appendLine()
                appendLine("Evidence source and units: ________________________________")
                appendLine()
                appendLine("Observation: _____________________________________________")
                appendLine()
                appendLine("What this does **not** prove: _____________________________")
                appendLine()
                appendLine("Safe next step or review needed: __________________________")
            }
        }
    }

    private fun markdownText(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\r", " ")
        .replace("\n", " ")
        .replace("|", "\\|")
}
