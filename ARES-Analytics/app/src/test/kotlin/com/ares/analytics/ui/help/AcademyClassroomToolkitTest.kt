package com.ares.analytics.ui.help

import com.ares.analytics.service.LearningProgress
import com.ares.analytics.service.AcademyLearningAssignment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AcademyClassroomToolkitTest {
    @Test
    fun `summary counts only lessons and checkpoints in selected path`() {
        val path = LearningCatalog.path("drivetrains-odometry")!!
        val firstLesson = LearningCatalog.lesson(path.lessonIds.first())!!
        val progress = LearningProgress(
            practicedLessonIds = setOf(firstLesson.id, "safe-subsystem"),
            startedLessonIds = setOf(firstLesson.id, "safe-subsystem"),
            completedCheckpointIds = firstLesson.checkpoints.map { it.id }.toSet(),
        )

        val summary = AcademyClassroomToolkit.pathSummary(path.id, progress)

        assertEquals(1, summary.practicedLessons)
        assertEquals(1, summary.startedLessons)
        assertEquals(firstLesson.checkpoints.size, summary.completedCheckpoints)
        assertTrue(summary.totalCheckpoints >= summary.completedCheckpoints)
    }

    @Test
    fun `markdown report distinguishes observed facts from student reflection`() {
        val lesson = LearningCatalog.lesson("start-simulator")!!
        val observed = lesson.checkpoints.first { it.evidence != LearningCheckpointEvidence.SELF_REPORTED }
        val reflected = lesson.checkpoints.first { it.evidence == LearningCheckpointEvidence.SELF_REPORTED }
        val progress = LearningProgress(
            completedCheckpointIds = setOf(observed.id, reflected.id),
            checkpointReflections = mapOf(reflected.id to "I identified the selected source and its limitation."),
        )

        val report = AcademyClassroomToolkit.markdownReport(progress, "drivetrains-odometry", "Mentor")

        assertTrue(report.contains("[app-observed fact]"))
        assertTrue(report.contains("[student reflection]"))
        assertTrue(report.contains("not a grade, certification, code review, or proof of physical robot safety"))
    }

    @Test
    fun `assignment worksheet teaches evidence boundaries without claiming completion`() {
        val worksheet = AcademyClassroomToolkit.assignmentWorksheet(
            assignment = AcademyLearningAssignment(
                assignmentId = "assignment-1",
                title = "Mechanism evidence",
                pathId = "subsystems-architecture",
                lessonIds = listOf("safe-subsystem"),
                instructions = "Change one thing at a time.",
                dueLabel = "Friday",
                createdAtEpochMs = 1L,
            ),
            studentName = "Student A",
        )

        assertTrue(worksheet.contains("Student A"))
        assertTrue(worksheet.contains("Build a Safe Mechanism Subsystem"))
        assertTrue(worksheet.contains("Evidence source and units"))
        assertTrue(worksheet.contains("What this does **not** prove"))
        assertTrue(worksheet.contains("not a grade, certification, code review, or proof of physical robot safety"))
    }
}
