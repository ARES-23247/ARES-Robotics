package com.ares.analytics.service

import com.ares.analytics.ui.help.AcademyRunAnalysisSnapshot
import com.ares.analytics.ui.help.AcademyRuntimeSnapshot
import com.ares.analytics.ui.help.AcademyClassroomToolkit
import com.ares.analytics.ui.help.FirstMissionCheckpointIds
import com.ares.analytics.ui.help.LearningCatalog
import com.ares.analytics.ui.help.LearningCheckpointEvidence
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AcademyClassroomPilotTest {
    @Test
    fun `offline first mission through run review produces bounded export`() = runTest {
        val root = Files.createTempDirectory("academy-classroom-pilot").toFile()
        File(root, ".ares").mkdirs()
        val databaseFile = File(root, "academy.db")
        val database = DatabaseService(databaseFile.path)
        val sysId = SysIdService(database)
        val driverAnalysis = DriverAnalysisService(database, sysId)
        val parser = LogParserService(database, SummaryEngineService(database, sysId, driverAnalysis))
        val practiceWorkflow = AcademyPracticeWorkflowService(AcademyPracticePackService(), database, parser)
        val practiceImport = practiceWorkflow.installAndImport(
            root,
            AcademyPracticeIdentity("23247", "2026", "academy-bot"),
        )
        val practiceRetry = practiceWorkflow.installAndImport(
            root,
            AcademyPracticeIdentity("23247", "2026", "academy-bot"),
        )
        val comparison = RunComparisonService(database).compare(
            WorkspaceConfig(
                id = "academy-pilot",
                teamId = "23247",
                seasonId = "2026",
                robotId = "academy-bot",
                projectPath = root.path,
                league = League.FTC,
            ),
            RunComparisonRequest(
                primarySessionId = practiceImport.sessionIds.first(),
                comparisonSessionIds = listOf(practiceImport.sessionIds.last()),
            ),
        )
        val progressFile = File(root, "learning-progress.json")
        val progress = LearningProgressService(progressFile)
        progress.updateStudentDisplayName("Pilot Student")
        progress.selectPath("drivetrains-odometry")

        progress.startLesson("start-simulator")
        progress.observeRuntime(
            AcademyRuntimeSnapshot(
                isAvailable = true,
                isLocalSimulatorSelected = true,
                isSimulatorRunning = true,
                isLocalSimulatorOnline = true,
                isNt4Connected = true,
            ),
        )
        recordEveryReflection(progress, "start-simulator")
        progress.observeRuntime(AcademyRuntimeSnapshot(isAvailable = true, isLocalSimulatorSelected = true))
        progress.setPracticed("start-simulator", true)

        progress.observeRuntime(
            AcademyRuntimeSnapshot(
                isAvailable = true,
                runAnalysis = AcademyRunAnalysisSnapshot(
                    isAvailable = true,
                    hasWorkspaceRuns = true,
                    hasSelectedRun = true,
                    hasSourceEvidence = true,
                    hasGuidedReport = true,
                    hasQuantitativeEvidence = true,
                    hasBaselineComparison = true,
                    hasLimitations = true,
                    hasExportedReport = true,
                ),
            ),
        )
        recordEveryReflection(progress, "compare-run-evidence")
        progress.setPracticed("compare-run-evidence", true)

        val reportFile = File(root, "pilot-record.md")
        progress.exportMentorReport(reportFile, "drivetrains-odometry", "Pilot Mentor")
        val summary = AcademyClassroomToolkit.pathSummary("drivetrains-odometry", progress.progress.value)

        assertTrue(practiceImport.pack.files.any { it.name == "baseline-arm-run.csv" })
        assertEquals(2, practiceImport.importedCount)
        assertEquals(2, practiceRetry.reusedCount)
        assertEquals(2, database.getSessions().count { AcademyPracticeWorkflowService.ACADEMY_SYNTHETIC_TAG in it.tags })
        assertTrue(comparison.availableAlignments.any { it.kind == RunAlignmentKind.AUTONOMOUS_START })
        assertTrue(comparison.availableAlignments.any { it.kind == RunAlignmentKind.MATCH_EVENT && "Arm cycle begins" in it.label })
        assertTrue(FirstMissionCheckpointIds.LOCAL_SIM_CONNECTED in progress.progress.value.completedCheckpointIds)
        assertEquals("drivebase-blueprint", summary.recommendedLesson?.id)
        assertTrue(reportFile.readText().contains("synthetic").not())
        assertTrue(reportFile.readText().contains("not a grade, certification, code review, or proof of physical robot safety"))
        database.close()
    }

    private suspend fun recordEveryReflection(service: LearningProgressService, lessonId: String) {
        LearningCatalog.lesson(lessonId)!!.checkpoints
            .filter { it.evidence == LearningCheckpointEvidence.SELF_REPORTED }
            .forEach { checkpoint ->
                service.recordReflection(
                    checkpoint.id,
                    "I named the evidence source and one limitation; this is not physical validation.",
                )
            }
    }
}
