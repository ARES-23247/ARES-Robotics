package com.ares.analytics.service

import com.ares.analytics.ui.help.AcademyRuntimeSnapshot
import com.ares.analytics.ui.help.FirstMissionCheckpointIds
import com.ares.analytics.ui.help.AcademySubsystemSnapshot
import com.ares.analytics.ui.help.AcademyControlsSnapshot
import com.ares.analytics.ui.help.ControlsMissionCheckpointIds
import com.ares.analytics.ui.help.AcademyTuningSnapshot
import com.ares.analytics.ui.help.TuningMissionCheckpointIds
import com.ares.analytics.ui.help.SubsystemMissionCheckpointIds
import com.ares.analytics.ui.help.AcademySuperstructureSnapshot
import com.ares.analytics.ui.help.AcademyAutonomousSnapshot
import com.ares.analytics.ui.help.SuperstructureMissionCheckpointIds
import com.ares.analytics.ui.help.AutonomousMissionCheckpointIds
import com.ares.analytics.ui.help.AcademyRunAnalysisSnapshot
import com.ares.analytics.ui.help.RunImportMissionCheckpointIds
import com.ares.analytics.ui.help.RunAnalysisMissionCheckpointIds
import com.ares.analytics.ui.help.AcademyGraduationSnapshot
import com.ares.analytics.ui.help.GraduationMissionCheckpointIds
import com.ares.analytics.ui.help.LearningRubricRating
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LearningProgressServiceTest {
    @Test
    fun `first mission nudge dismissal survives service recreation`() = runTest {
        val tempDir = Files.createTempDirectory("learning-nudge-test").toFile()
        val file = File(tempDir, "learning-progress.json")
        val service = LearningProgressService(file)

        service.dismissFirstMissionNudge()

        assertTrue(LearningProgressService(file).progress.value.firstMissionNudgeDismissed)
    }

    @Test
    fun `practice progress survives service recreation`() = runTest {
        val tempDir = Files.createTempDirectory("learning-progress-test").toFile()
        val file = File(tempDir, "learning-progress.json")
        LearningProgressService(file).setPracticed("start-simulator", true)

        val reloaded = LearningProgressService(file).progress.value
        assertEquals(setOf("start-simulator"), reloaded.practicedLessonIds)
        assertEquals(setOf("start-simulator"), reloaded.startedLessonIds)
        assertEquals("start-simulator", reloaded.activeLessonId)
        assertEquals(CURRENT_LEARNING_CONTENT_VERSION, reloaded.contentVersion)
    }

    @Test
    fun `corrupt progress fails to an empty safe state`() {
        val tempDir = Files.createTempDirectory("learning-progress-corrupt-test").toFile()
        val file = File(tempDir, "learning-progress.json").apply { writeText("not-json") }
        assertTrue(LearningProgressService(file).progress.value.practicedLessonIds.isEmpty())
    }

    @Test
    fun `unreadable classroom store is quarantined before any overwrite`() = runTest {
        val tempDir = Files.createTempDirectory("learning-progress-quarantine-test").toFile()
        val corruptBytes = """{"learners":[{"learnerId":"a","progress":{"""
        val file = File(tempDir, "learning-progress.json").apply { writeText(corruptBytes) }

        val service = LearningProgressService(file)
        assertTrue(service.progress.value.practicedLessonIds.isEmpty())

        // The unreadable bytes must survive as a quarantine copy alongside the store...
        val quarantined = tempDir.listFiles { f -> f.name.startsWith("learning-progress.corrupt-") }.orEmpty()
        assertEquals(1, quarantined.size)
        assertEquals(corruptBytes, quarantined[0].readText())

        // ...even after the empty store persists over the original file.
        service.setPracticed("start-simulator", true)
        assertEquals(corruptBytes, quarantined[0].readText())
    }

    @Test
    fun `retired progress document is rejected and quarantined`() {
        val tempDir = Files.createTempDirectory("learning-progress-v1-test").toFile()
        val file = File(tempDir, "learning-progress.json").apply {
            writeText("""{"contentVersion":1,"practicedLessonIds":["start-simulator"]}""")
        }

        val current = LearningProgressService(file).progress.value

        assertEquals(CURRENT_LEARNING_CONTENT_VERSION, current.contentVersion)
        assertTrue(current.practicedLessonIds.isEmpty())
        assertEquals(1, tempDir.listFiles { candidate ->
            candidate.name.startsWith("learning-progress.corrupt-")
        }.orEmpty().size)
    }

    @Test
    fun `checkpoint and active lesson progress survive recreation`() = runTest {
        val tempDir = Files.createTempDirectory("learning-checkpoint-test").toFile()
        val file = File(tempDir, "learning-progress.json")
        val service = LearningProgressService(file)
        service.startLesson("safe-subsystem")
        service.recordReflection(SubsystemMissionCheckpointIds.RUNTIME_FLOW, "I traced the generated runtime boundary.")

        val reloaded = LearningProgressService(file).progress.value
        assertEquals("safe-subsystem", reloaded.activeLessonId)
        assertTrue("safe-subsystem" in reloaded.startedLessonIds)
        assertTrue(SubsystemMissionCheckpointIds.RUNTIME_FLOW in reloaded.completedCheckpointIds)

        val resumed = LearningProgressService(file)
        resumed.clearActiveLesson()
        assertEquals(null, LearningProgressService(file).progress.value.activeLessonId)
        assertTrue(SubsystemMissionCheckpointIds.RUNTIME_FLOW in LearningProgressService(file).progress.value.completedCheckpointIds)
    }

    @Test
    fun `runtime observations record simulator facts but not student reflection`() = runTest {
        val tempDir = Files.createTempDirectory("learning-runtime-test").toFile()
        val service = LearningProgressService(File(tempDir, "learning-progress.json"))
        service.observeRuntime(
            AcademyRuntimeSnapshot(
                isAvailable = true,
                isLocalSimulatorSelected = true,
                isSimulatorRunning = true,
                isLocalSimulatorOnline = true,
                isNt4Connected = true,
            ),
        )

        val running = service.progress.value
        assertTrue(FirstMissionCheckpointIds.LOCAL_SIM_SELECTED in running.completedCheckpointIds)
        assertTrue(FirstMissionCheckpointIds.SIMULATOR_RUNNING in running.completedCheckpointIds)
        assertTrue(FirstMissionCheckpointIds.LOCAL_SIM_CONNECTED in running.completedCheckpointIds)
        assertTrue(FirstMissionCheckpointIds.IDENTIFIED_DATA_SOURCE !in running.completedCheckpointIds)

        service.observeRuntime(AcademyRuntimeSnapshot(isAvailable = true, isLocalSimulatorSelected = true))
        assertTrue(FirstMissionCheckpointIds.SIMULATOR_STOPPED in service.progress.value.completedCheckpointIds)
    }

    @Test
    fun `unavailable runtime never changes progress`() = runTest {
        val tempDir = Files.createTempDirectory("learning-runtime-unavailable-test").toFile()
        val service = LearningProgressService(File(tempDir, "learning-progress.json"))
        service.observeRuntime(AcademyRuntimeSnapshot.Unavailable)
        assertEquals(LearningProgress(), service.progress.value)
    }

    @Test
    fun `builder observations start the subsystem lesson rather than the simulator lesson`() = runTest {
        val tempDir = Files.createTempDirectory("learning-subsystem-runtime-test").toFile()
        val service = LearningProgressService(File(tempDir, "learning-progress.json"))

        service.observeRuntime(
            AcademyRuntimeSnapshot(
                isAvailable = true,
                subsystem = AcademySubsystemSnapshot(
                    isAvailable = true,
                    hasPositionMechanismDraft = true,
                ),
            ),
        )

        val progress = service.progress.value
        assertTrue(SubsystemMissionCheckpointIds.POSITION_DRAFT in progress.completedCheckpointIds)
        assertTrue("safe-subsystem" in progress.startedLessonIds)
        assertTrue("start-simulator" !in progress.startedLessonIds)
        assertEquals("safe-subsystem", progress.activeLessonId)
    }

    @Test
    fun `controls observations start the controller mission without claiming reflection`() = runTest {
        val tempDir = Files.createTempDirectory("learning-controls-runtime-test").toFile()
        val service = LearningProgressService(File(tempDir, "learning-progress.json"))

        service.observeRuntime(
            AcademyRuntimeSnapshot(
                isAvailable = true,
                controls = AcademyControlsSnapshot(
                    isAvailable = true,
                    hasActionCatalog = true,
                    hasGeneratedSubsystemCapability = true,
                ),
            ),
        )

        val progress = service.progress.value
        assertTrue(ControlsMissionCheckpointIds.ACTION_CATALOG in progress.completedCheckpointIds)
        assertTrue(ControlsMissionCheckpointIds.SUBSYSTEM_CAPABILITY in progress.completedCheckpointIds)
        assertTrue(ControlsMissionCheckpointIds.BINDING_POLICY !in progress.completedCheckpointIds)
        assertTrue(ControlsMissionCheckpointIds.RUNTIME_FLOW !in progress.completedCheckpointIds)
        assertEquals("map-one-control", progress.activeLessonId)
    }

    @Test
    fun `tuning review observations start the tuning mission without claiming model understanding`() = runTest {
        val tempDir = Files.createTempDirectory("learning-tuning-runtime-test").toFile()
        val service = LearningProgressService(File(tempDir, "learning-progress.json"))

        service.observeRuntime(
            AcademyRuntimeSnapshot(
                isAvailable = true,
                tuning = AcademyTuningSnapshot(
                    isAvailable = true,
                    hasTypedCatalog = true,
                    hasValidProposal = true,
                    hasProposalProvenance = true,
                ),
            ),
        )

        val progress = service.progress.value
        assertTrue(TuningMissionCheckpointIds.TYPED_CATALOG in progress.completedCheckpointIds)
        assertTrue(TuningMissionCheckpointIds.VALID_PROPOSAL in progress.completedCheckpointIds)
        assertTrue(TuningMissionCheckpointIds.PROVENANCE in progress.completedCheckpointIds)
        assertTrue(TuningMissionCheckpointIds.MODEL_PREDICTION !in progress.completedCheckpointIds)
        assertTrue(TuningMissionCheckpointIds.EVIDENCE_BOUNDARY !in progress.completedCheckpointIds)
        assertEquals("tuning-evidence", progress.activeLessonId)
    }

    @Test
    fun `superstructure preview evidence starts the coordinator mission without claiming reasoning`() = runTest {
        val tempDir = Files.createTempDirectory("learning-superstructure-runtime-test").toFile()
        val service = LearningProgressService(File(tempDir, "learning-progress.json"))

        service.observeRuntime(
            AcademyRuntimeSnapshot(
                isAvailable = true,
                superstructure = AcademySuperstructureSnapshot(
                    isAvailable = true,
                    hasSeveralGeneratedSubsystems = true,
                    hasDeterministicPreview = true,
                    hasFaultInjectionEvidence = true,
                ),
            ),
        )

        val progress = service.progress.value
        assertTrue(SuperstructureMissionCheckpointIds.SUBSYSTEMS_READY in progress.completedCheckpointIds)
        assertTrue(SuperstructureMissionCheckpointIds.PREVIEW in progress.completedCheckpointIds)
        assertTrue(SuperstructureMissionCheckpointIds.FAULT_INJECTION in progress.completedCheckpointIds)
        assertTrue(SuperstructureMissionCheckpointIds.POSTURE_REASONING !in progress.completedCheckpointIds)
        assertEquals("coordinate-mechanisms", progress.activeLessonId)
    }

    @Test
    fun `autonomous project evidence starts routine mission without claiming prediction`() = runTest {
        val tempDir = Files.createTempDirectory("learning-autonomous-runtime-test").toFile()
        val service = LearningProgressService(File(tempDir, "learning-progress.json"))

        service.observeRuntime(
            AcademyRuntimeSnapshot(
                isAvailable = true,
                autonomous = AcademyAutonomousSnapshot(
                    isAvailable = true,
                    hasProjectCapabilities = true,
                    hasRoutineSteps = true,
                    hasValidRoutine = true,
                ),
            ),
        )

        val progress = service.progress.value
        assertTrue(AutonomousMissionCheckpointIds.CAPABILITIES in progress.completedCheckpointIds)
        assertTrue(AutonomousMissionCheckpointIds.STEPS in progress.completedCheckpointIds)
        assertTrue(AutonomousMissionCheckpointIds.VALID in progress.completedCheckpointIds)
        assertTrue(AutonomousMissionCheckpointIds.STARTING_PREDICTION !in progress.completedCheckpointIds)
        assertEquals("first-routine", progress.activeLessonId)
    }

    @Test
    fun `workspace run evidence starts import mission without claiming interpretation`() = runTest {
        val tempDir = Files.createTempDirectory("learning-run-import-test").toFile()
        val service = LearningProgressService(File(tempDir, "learning-progress.json"))

        service.observeRuntime(
            AcademyRuntimeSnapshot(
                isAvailable = true,
                runAnalysis = AcademyRunAnalysisSnapshot(
                    isAvailable = true,
                    hasWorkspaceRuns = true,
                    hasSelectedRun = true,
                    hasSourceEvidence = true,
                ),
            ),
        )

        val progress = service.progress.value
        assertTrue(RunImportMissionCheckpointIds.RUNS_AVAILABLE in progress.completedCheckpointIds)
        assertTrue(RunImportMissionCheckpointIds.RUN_SELECTED in progress.completedCheckpointIds)
        assertTrue(RunImportMissionCheckpointIds.SOURCE_EVIDENCE in progress.completedCheckpointIds)
        assertTrue(RunImportMissionCheckpointIds.EVIDENCE_DESCRIPTION !in progress.completedCheckpointIds)
        assertEquals("compare-run-evidence", progress.activeLessonId)
    }

    @Test
    fun `guided report evidence starts analysis mission without claiming conclusion`() = runTest {
        val tempDir = Files.createTempDirectory("learning-run-analysis-test").toFile()
        val service = LearningProgressService(File(tempDir, "learning-progress.json"))

        service.observeRuntime(
            AcademyRuntimeSnapshot(
                isAvailable = true,
                runAnalysis = AcademyRunAnalysisSnapshot(
                    isAvailable = true,
                    hasGuidedReport = true,
                    hasQuantitativeEvidence = true,
                    hasLimitations = true,
                ),
            ),
        )

        val progress = service.progress.value
        assertTrue(RunAnalysisMissionCheckpointIds.REPORT_READY in progress.completedCheckpointIds)
        assertTrue(RunAnalysisMissionCheckpointIds.QUANTITATIVE_EVIDENCE in progress.completedCheckpointIds)
        assertTrue(RunAnalysisMissionCheckpointIds.LIMITATIONS in progress.completedCheckpointIds)
        assertTrue(RunAnalysisMissionCheckpointIds.CLAIM !in progress.completedCheckpointIds)
        assertEquals("compare-run-evidence", progress.activeLessonId)
    }

    @Test
    fun `verified project evidence starts graduation without claiming code understanding`() = runTest {
        val tempDir = Files.createTempDirectory("learning-graduation-test").toFile()
        val service = LearningProgressService(File(tempDir, "learning-progress.json"))

        service.observeRuntime(
            AcademyRuntimeSnapshot(
                isAvailable = true,
                graduation = AcademyGraduationSnapshot(
                    isAvailable = true,
                    hasProjectIdentity = true,
                    hasAuthoringStagesReady = true,
                    hasVerifiedBuild = true,
                ),
            ),
        )

        val progress = service.progress.value
        assertTrue(GraduationMissionCheckpointIds.PROJECT_IDENTITY in progress.completedCheckpointIds)
        assertTrue(GraduationMissionCheckpointIds.AUTHORING_READY in progress.completedCheckpointIds)
        assertTrue(GraduationMissionCheckpointIds.VERIFIED_BUILD in progress.completedCheckpointIds)
        assertTrue(GraduationMissionCheckpointIds.OWNERSHIP_REASONING !in progress.completedCheckpointIds)
        assertTrue(GraduationMissionCheckpointIds.GENERATED_FLOW !in progress.completedCheckpointIds)
        assertEquals("generated-kotlin-graduation", progress.activeLessonId)
    }

    @Test
    fun `observable checkpoints cannot be manually asserted`() = runTest {
        val tempDir = Files.createTempDirectory("learning-runtime-manual-test").toFile()
        val service = LearningProgressService(File(tempDir, "learning-progress.json"))
        assertFailsWith<IllegalArgumentException> {
            service.setCheckpointCompleted(FirstMissionCheckpointIds.LOCAL_SIM_CONNECTED, true)
        }
        assertTrue(service.progress.value.completedCheckpointIds.isEmpty())
    }

    @Test
    fun `written reflection survives restart and removing it clears only that checkpoint`() = runTest {
        val directory = Files.createTempDirectory("learning-reflection-test").toFile()
        val file = File(directory, "learning-progress.json")
        val service = LearningProgressService(file)

        service.startLesson("safe-subsystem")
        service.recordReflection(
            SubsystemMissionCheckpointIds.RUNTIME_FLOW,
            "The target passes through Redux and the controller; this does not prove the motor is wired correctly.",
        )

        val reloaded = LearningProgressService(file).progress.value
        assertTrue(SubsystemMissionCheckpointIds.RUNTIME_FLOW in reloaded.completedCheckpointIds)
        assertTrue(reloaded.checkpointReflections.getValue(SubsystemMissionCheckpointIds.RUNTIME_FLOW).contains("does not prove"))

        service.setCheckpointCompleted(SubsystemMissionCheckpointIds.RUNTIME_FLOW, false)
        assertTrue(SubsystemMissionCheckpointIds.RUNTIME_FLOW !in service.progress.value.completedCheckpointIds)
        assertTrue(SubsystemMissionCheckpointIds.RUNTIME_FLOW !in service.progress.value.checkpointReflections)
    }

    @Test
    fun `lesson reset is scoped and path reset removes shared lesson evidence explicitly`() = runTest {
        val directory = Files.createTempDirectory("learning-reset-test").toFile()
        val service = LearningProgressService(File(directory, "learning-progress.json"))
        service.setPracticed("safe-subsystem", true)
        service.recordReflection(SubsystemMissionCheckpointIds.RUNTIME_FLOW, "I traced the runtime boundary.")
        service.setPracticed("start-simulator", true)

        service.resetLesson("safe-subsystem")
        assertTrue("safe-subsystem" !in service.progress.value.practicedLessonIds)
        assertTrue("start-simulator" in service.progress.value.practicedLessonIds)
        assertTrue(SubsystemMissionCheckpointIds.RUNTIME_FLOW !in service.progress.value.checkpointReflections)

        service.resetPath("drivetrains-odometry")
        assertTrue("start-simulator" !in service.progress.value.practicedLessonIds)
    }

    @Test
    fun `mentor report includes boundaries reflections notes and rubric without telemetry`() = runTest {
        val directory = Files.createTempDirectory("learning-export-test").toFile()
        val service = LearningProgressService(File(directory, "learning-progress.json"))
        service.updateStudentDisplayName("Student A")
        service.setPracticed("safe-subsystem", true)
        service.recordReflection(SubsystemMissionCheckpointIds.RUNTIME_FLOW, "Observed Redux flow; no hardware claim.")
        service.updateMentorNote("safe-subsystem", "Ask the student to identify the cached input next.")
        service.setRubricRating("runtime-flow", LearningRubricRating.DEVELOPING)
        val output = File(directory, "record.md")

        service.exportMentorReport(output, "subsystems-architecture", "Mentor B")

        val report = output.readText()
        assertTrue(report.contains("Student A"))
        assertTrue(report.contains("Mentor B"))
        assertTrue(report.contains("Observed Redux flow; no hardware claim."))
        assertTrue(report.contains("Developing"))
        assertTrue(report.contains("not a grade, certification, code review, or proof of physical robot safety"))
        assertTrue(!report.contains("OAuth"))
    }

    @Test
    fun `new student record cannot inherit prior learner evidence`() = runTest {
        val directory = Files.createTempDirectory("learning-new-student-test").toFile()
        val service = LearningProgressService(File(directory, "learning-progress.json"))
        service.updateStudentDisplayName("Student A")
        service.setPracticed("safe-subsystem", true)
        service.recordReflection(SubsystemMissionCheckpointIds.RUNTIME_FLOW, "Student A reflection")
        service.updateMentorNote("safe-subsystem", "Student A mentor note")
        service.setRubricRating("runtime-flow", LearningRubricRating.DEVELOPING)

        service.startNewStudent("Student B")

        val next = service.progress.value
        assertEquals("Student B", next.studentDisplayName)
        assertTrue(next.practicedLessonIds.isEmpty())
        assertTrue(next.completedCheckpointIds.isEmpty())
        assertTrue(next.checkpointReflections.isEmpty())
        assertTrue(next.mentorNotes.isEmpty())
        assertTrue(next.rubricRatings.isEmpty())
        assertEquals("drivetrains-odometry", next.selectedPathId)
    }

    @Test
    fun `separate learner records can be switched without mixing evidence`() = runTest {
        val directory = Files.createTempDirectory("learning-roster-test").toFile()
        val file = File(directory, "learning-progress.json")
        var nextId = 0
        val service = LearningProgressService(file, learnerIdFactory = { "learner-${++nextId}" })
        service.updateStudentDisplayName("Student A")
        service.setPracticed("safe-subsystem", true)
        val studentAId = service.classroom.value.activeLearnerId

        service.startNewStudent("Student B")
        val studentBId = service.classroom.value.activeLearnerId
        service.setPracticed("start-simulator", true)

        assertTrue(studentAId != studentBId)
        assertEquals(2, service.classroom.value.learners.size)
        assertTrue("safe-subsystem" !in service.progress.value.practicedLessonIds)
        assertTrue("start-simulator" in service.progress.value.practicedLessonIds)

        service.switchStudent(studentAId)
        assertEquals("Student A", service.progress.value.studentDisplayName)
        assertTrue("safe-subsystem" in service.progress.value.practicedLessonIds)
        assertTrue("start-simulator" !in service.progress.value.practicedLessonIds)

        val reloaded = LearningProgressService(file)
        assertEquals(studentAId, reloaded.classroom.value.activeLearnerId)
        assertEquals(2, reloaded.classroom.value.learners.size)
    }

    @Test
    fun `assignments and immutable snapshots persist for only the active learner`() = runTest {
        val directory = Files.createTempDirectory("learning-assignment-test").toFile()
        val file = File(directory, "learning-progress.json")
        val service = LearningProgressService(
            progressFile = file,
            nowMillis = { 1234L },
            learnerIdFactory = { "learner-b" },
        )
        service.updateStudentDisplayName("Student A")
        val studentAId = service.classroom.value.activeLearnerId
        service.createAssignment(
            title = "Trace a safe mechanism",
            pathId = "subsystems-architecture",
            lessonIds = listOf("safe-subsystem"),
            instructions = "Predict, observe, and explain the safety boundary.",
            dueLabel = "Session 2",
        )
        val assignment = service.classroom.value.learners.single().assignments.single()
        service.setAssignmentCompleted(assignment.assignmentId, true)
        val firstSnapshot = service.saveLocalSnapshot("subsystems-architecture", "Mentor")
        val secondSnapshot = service.saveLocalSnapshot("subsystems-architecture", "Mentor")

        assertTrue(firstSnapshot.file.isFile)
        assertTrue(secondSnapshot.file.isFile)
        assertTrue(firstSnapshot.file != secondSnapshot.file)
        assertEquals(2, service.snapshotsFor(studentAId).size)

        service.startNewStudent("Student B")
        assertTrue(service.classroom.value.learners.first { it.learnerId == service.classroom.value.activeLearnerId }.assignments.isEmpty())
        service.switchStudent(studentAId)
        val restored = service.classroom.value.learners.first { it.learnerId == studentAId }.assignments.single()
        assertTrue(restored.completed)
        assertEquals("Trace a safe mechanism", restored.title)
    }
}
