package com.ares.analytics.ui.help

import com.ares.analytics.service.ComparisonClaimKind
import com.ares.analytics.service.GuidedComparisonFinding
import com.ares.analytics.service.GuidedRunAnalysisReport
import com.ares.analytics.service.GuidedRunAnalysisRepository
import com.ares.analytics.service.GuidedRunConfidence
import com.ares.analytics.service.GuidedRunDestination
import com.ares.analytics.service.GuidedRunEvidenceContext
import com.ares.analytics.service.GuidedRunFinding
import com.ares.analytics.service.GuidedRunMetric
import com.ares.analytics.service.GuidedRunNextAction
import com.ares.analytics.service.RUN_START_ALIGNMENT_ID
import com.ares.analytics.service.RunAlignmentKind
import com.ares.analytics.service.RunAlignmentOption
import com.ares.analytics.service.RunComparisonEvidenceLink
import com.ares.analytics.service.RunComparisonMetric
import com.ares.analytics.service.RunComparisonReport
import com.ares.analytics.service.RunComparisonRepository
import com.ares.analytics.service.RunComparisonRequest
import com.ares.analytics.service.RunComparisonSeries
import com.ares.analytics.service.RunEvidenceSourceKind
import com.ares.analytics.service.RunMetricSummary
import com.ares.analytics.service.RunSourceEvidence
import com.ares.analytics.service.versioncontrol.ProjectChange
import com.ares.analytics.service.versioncontrol.ProjectChangeKind
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.SIMULATION_SESSION_TAG
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.WorkspaceConfig

import com.ares.analytics.ui.components.dashboard.AnalysisMode
import com.ares.analytics.ui.components.dashboard.StudentSignalRecipe
import com.ares.analytics.ui.components.dashboard.computeDashboardSignalStats
import com.ares.analytics.ui.components.dashboard.resolveStudentSignalRecipe
import com.ares.analytics.ui.components.dashboard.studentDashboardStatExplainerItems
import com.ares.analytics.ui.components.runanalysis.StudentRunComparisonPreset
import com.ares.analytics.ui.components.runanalysis.formatStudentEngineeringNotebookEntry
import com.ares.analytics.ui.components.runanalysis.resolveStudentComparisonPreset
import com.ares.analytics.ui.screens.StudentPitCheckpointPreset
import com.ares.analytics.ui.screens.StudentPitRoleIdentity
import com.ares.analytics.ui.screens.recommendPitCheckpointFromChanges
import com.ares.analytics.viewmodel.runanalysis.GuidedRunAnalysisViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Wave6StatisticsRunAnalysisCheckpointsAndMaintainabilityTest {

    @Test
    fun studentSignalRecipesResolveSignalsAndComputeDescriptiveStatistics() {
        val availableKeys = listOf(
            "/SystemStats/BatteryVoltage",
            "/Robot/LoopTimeMs",
            "/Subsystems/Arm/PositionDeg",
            "/Subsystems/Arm/TargetDeg",
            "/Odometry/Pose/HeadingRad",
            "/Odometry/TargetPose/HeadingRad",
        )

        val batteryResolved = resolveStudentSignalRecipe(availableKeys, StudentSignalRecipe.BATTERY_HEALTH)
        assertEquals("/SystemStats/BatteryVoltage", batteryResolved.primaryKey)
        assertNull(batteryResolved.secondaryKey)
        assertEquals(AnalysisMode.SINGLE, batteryResolved.analysisMode)
        assertTrue(batteryResolved.studentGuidance.contains("12.0", ignoreCase = true) || batteryResolved.studentGuidance.contains("brownout", ignoreCase = true))

        val loopResolved = resolveStudentSignalRecipe(availableKeys, StudentSignalRecipe.LOOP_TIMING)
        assertEquals("/Robot/LoopTimeMs", loopResolved.primaryKey)
        assertNull(loopResolved.secondaryKey)
        assertEquals(AnalysisMode.SINGLE, loopResolved.analysisMode)

        val mechanismResolved = resolveStudentSignalRecipe(availableKeys, StudentSignalRecipe.MECHANISM_SETPOINT_ERROR)
        assertEquals("/Subsystems/Arm/PositionDeg", mechanismResolved.primaryKey)
        assertEquals("/Subsystems/Arm/TargetDeg", mechanismResolved.secondaryKey)
        assertEquals(AnalysisMode.ABSOLUTE_ERROR, mechanismResolved.analysisMode)

        val headingResolved = resolveStudentSignalRecipe(availableKeys, StudentSignalRecipe.HEADING_POSE_TRACKING)
        assertEquals("/Odometry/Pose/HeadingRad", headingResolved.primaryKey)
        assertEquals("/Odometry/TargetPose/HeadingRad", headingResolved.secondaryKey)
        assertEquals(AnalysisMode.ABSOLUTE_ERROR, headingResolved.analysisMode)

        val stats = computeDashboardSignalStats(listOf(10.0, 12.0, 12.0, 14.0, 16.0))
        assertNotNull(stats)
        assertEquals(5.0, stats["Count"])
        assertEquals(10.0, stats["Min"])
        assertEquals(16.0, stats["Max"])
        assertEquals(12.8, stats["Mean"]!!, 1e-6)
        assertEquals(12.0, stats["Median"]!!, 1e-6)
        assertTrue((stats["95th %"] ?: 0.0) >= 14.0)

        val explainers = studentDashboardStatExplainerItems()
        assertEquals(4, explainers.size)
        assertTrue(explainers.any { it.metricName.contains("Mean", ignoreCase = true) })
        assertTrue(explainers.any { it.metricName.contains("P95", ignoreCase = true) || it.metricName.contains("95th", ignoreCase = true) })
        assertTrue(explainers.any { it.metricName.contains("Std Dev", ignoreCase = true) })
        assertTrue(explainers.any { it.metricName.contains("IQR", ignoreCase = true) })
    }

    @Test
    fun studentRunComparisonPresetsAndEngineeringNotebookFormatterProduceStructuredMarkdown() {
        val sessions = listOf(
            Session(
                sessionId = "run-latest",
                teamId = "9432",
                seasonId = "2026",
                robotId = "alpha",
                createdAt = 3_000L,
                durationMs = 45_000L,
                tags = listOf("teleop"),
            ),
            Session(
                sessionId = "run-previous-short",
                teamId = "9432",
                seasonId = "2026",
                robotId = "alpha",
                createdAt = 2_000L,
                durationMs = 18_000L,
                tags = listOf("practice"),
            ),
            Session(
                sessionId = "run-longest-match",
                teamId = "9432",
                seasonId = "2026",
                robotId = "alpha",
                createdAt = 1_500L,
                durationMs = 150_000L,
                tags = listOf("match"),
                matchNumber = 12,
            ),
            Session(
                sessionId = "run-sim-baseline",
                teamId = "9432",
                seasonId = "2026",
                robotId = "alpha",
                createdAt = 1_000L,
                durationMs = 30_000L,
                tags = listOf(SIMULATION_SESSION_TAG),
            ),
        )

        val latestVsPrev = resolveStudentComparisonPreset(sessions, StudentRunComparisonPreset.LATEST_VS_PREVIOUS)
        assertNotNull(latestVsPrev)
        assertEquals("run-latest", latestVsPrev.primarySessionId)
        assertEquals(listOf("run-previous-short"), latestVsPrev.comparisonSessionIds)

        val latestVsLongest = resolveStudentComparisonPreset(sessions, StudentRunComparisonPreset.LATEST_VS_LONGEST_COMPLETED)
        assertNotNull(latestVsLongest)
        assertEquals("run-latest", latestVsLongest.primarySessionId)
        assertEquals(listOf("run-longest-match"), latestVsLongest.comparisonSessionIds)

        val latestVsSim = resolveStudentComparisonPreset(sessions, StudentRunComparisonPreset.LATEST_VS_SIMULATOR_BASELINE)
        assertNotNull(latestVsSim)
        assertEquals("run-latest", latestVsSim.primarySessionId)
        assertEquals(listOf("run-sim-baseline"), latestVsSim.comparisonSessionIds)

        val report = GuidedRunAnalysisReport(
            session = sessions.first(),
            source = RunSourceEvidence(
                kind = RunEvidenceSourceKind.IMPORTED_FILE,
                explanation = "Verified local log import",
                sourceName = "match12.wpilog",
            ),
            evidenceContext = GuidedRunEvidenceContext(
                freshnessStatus = "Recorded 5m ago",
                startTimestampMs = 0L,
                endTimestampMs = 45_000L,
                confidence = GuidedRunConfidence.MODERATE,
                confidenceExplanation = "Battery, loop time, and pose topics present.",
            ),
            summary = null,
            metrics = listOf(
                GuidedRunMetric("Min Battery", "11.4", "V", "/SystemStats/BatteryVoltage"),
                GuidedRunMetric("P95 Loop Time", "16.2", "ms", "/Robot/LoopTimeMs"),
            ),
            alerts = emptyList(),
            findings = listOf(
                GuidedRunFinding(
                    title = "Arm settling lag after scoring raise",
                    statusText = "Needs review",
                    observedEvidence = "Arm position trailed target by 4.2 deg at t=14.0s.",
                    interpretationLimit = "Single run cannot separate friction from low kP.",
                    possibleCauses = listOf("kP slightly low", "Gravity feedforward under-compensated"),
                    safeVerificationSteps = listOf("Run 1-step Tuning experiment in simulator first"),
                ),
            ),
            comparison = null,
            regressions = emptyList(),
            driverReview = null,
            missingSignals = emptyList(),
            limitations = listOf("Historical log does not prove current pit wiring."),
            nextActions = listOf(
                GuidedRunNextAction(
                    title = "Open Guided Tuning Experiment",
                    reason = "Test a 1-parameter kP or kG adjustment safely.",
                    destination = GuidedRunDestination.TUNING,
                ),
            ),
        )

        val comparisonReport = RunComparisonReport(
            sessions = listOf(sessions[0], sessions[2]),
            primarySessionId = "run-latest",
            selectedAlignment = RunAlignmentOption(
                id = RUN_START_ALIGNMENT_ID,
                kind = RunAlignmentKind.RUN_START,
                label = "Run start",
                explanation = "Aligned at start of recording",
            ),
            availableAlignments = emptyList(),
            anchors = emptyList(),
            trajectories = emptyList(),
            metrics = listOf(
                RunComparisonMetric(
                    id = "loop_time",
                    label = "Loop Time",
                    unit = "ms",
                    explanation = "Control loop period",
                    series = listOf(
                        RunComparisonSeries(
                            sessionId = "run-latest",
                            runLabel = "Latest Run",
                            sourceTopics = listOf("/Robot/LoopTimeMs"),
                            samples = emptyList(),
                            summary = RunMetricSummary(10.0, 19.0, 14.2, 16.2, 100),
                        ),
                        RunComparisonSeries(
                            sessionId = "run-longest-match",
                            runLabel = "Match 12",
                            sourceTopics = listOf("/Robot/LoopTimeMs"),
                            samples = emptyList(),
                            summary = RunMetricSummary(9.5, 17.0, 12.8, 14.5, 100),
                        ),
                    ),
                ),
            ),
            faults = emptyList(),
            findings = listOf(
                GuidedComparisonFinding(
                    id = "finding-1",
                    kind = ComparisonClaimKind.OBSERVATION,
                    title = "P95 loop time increased by 1.7 ms vs Match 12",
                    explanation = "Observed higher P95 loop time during teleop.",
                    evidence = RunComparisonEvidenceLink("run-latest", 14_000L, 14_000L, listOf("/Robot/LoopTimeMs")),
                ),
            ),
            limitations = emptyList(),
        )

        val notebookMarkdown = formatStudentEngineeringNotebookEntry(report, comparisonReport)
        assertTrue(notebookMarkdown.contains("# Engineering Notebook Run Review"))
        assertTrue(notebookMarkdown.contains("**Min Battery**: 11.4 V"))
        assertTrue(notebookMarkdown.contains("Arm settling lag after scoring raise"))

        assertTrue(notebookMarkdown.contains("P95 loop time increased by 1.7 ms vs Match 12"))
        assertTrue(notebookMarkdown.contains("Open Guided Tuning Experiment"))

        // Verify ViewModel preset application
        val fakeRepo = object : GuidedRunAnalysisRepository {
            override suspend fun listWorkspaceSessions(workspace: WorkspaceConfig): List<Session> = sessions
            override suspend fun analyze(workspace: WorkspaceConfig, sessionId: String): GuidedRunAnalysisReport = report
            override suspend fun exportMarkdown(report: GuidedRunAnalysisReport, destination: File) = Unit
        }
        val fakeComparisonRepo = object : RunComparisonRepository {
            override suspend fun compare(workspace: WorkspaceConfig, request: RunComparisonRequest): RunComparisonReport = comparisonReport
            override suspend fun exportMarkdown(report: RunComparisonReport, destination: File) = Unit
        }
        val vm = GuidedRunAnalysisViewModel(
            service = fakeRepo,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            comparisonService = fakeComparisonRepo,
        )
        vm.load(
            WorkspaceConfig(
                id = "ws-1",
                teamId = "9432",
                seasonId = "2026",
                robotId = "alpha",
                league = League.FTC,
                projectPath = ".",
            ),
        )

        vm.applyComparisonPreset(StudentRunComparisonPreset.LATEST_VS_SIMULATOR_BASELINE)
        assertEquals("run-latest", vm.state.value.selectedSessionId)
        assertEquals(listOf("run-sim-baseline"), vm.state.value.comparisonSessionIds)
    }

    @Test
    fun smartPitCheckpointsRecommendPresetFromChangedAreasAndProvideRoleIdentities() {
        StudentPitRoleIdentity.entries.forEach { role ->
            assertTrue(role.label.isNotBlank())
            assertTrue(role.authorName.isNotBlank())
            assertTrue(role.authorEmail.contains("@"))
        }

        val tuningRec = recommendPitCheckpointFromChanges(
            listOf(ProjectChange(".ares/tuning/elevator_pid.json", ProjectChangeKind.MODIFIED)),
        )
        assertNotNull(tuningRec)
        assertEquals(StudentPitCheckpointPreset.BEFORE_TUNING, tuningRec.preset)
        assertTrue(tuningRec.areaBreakdownSummary.contains("Tuning (1)"))

        val autoRec = recommendPitCheckpointFromChanges(
            listOf(ProjectChange(".ares/routines/two_piece_auto.json", ProjectChangeKind.MODIFIED)),
        )
        assertNotNull(autoRec)
        assertEquals(StudentPitCheckpointPreset.BEFORE_AUTO_TEST, autoRec.preset)
        assertTrue(autoRec.areaBreakdownSummary.contains("Autonomous routines (1)"))

        val wiringRec = recommendPitCheckpointFromChanges(
            listOf(
                ProjectChange(".ares/subsystems/intake.json", ProjectChangeKind.MODIFIED),
                ProjectChange(".ares/drivetrains/swerve.json", ProjectChangeKind.MODIFIED),
            ),
        )
        assertNotNull(wiringRec)
        assertEquals(StudentPitCheckpointPreset.AFTER_WIRING_UPDATE, wiringRec.preset)
        assertTrue(wiringRec.areaBreakdownSummary.contains("Subsystems (1)"))
        assertTrue(wiringRec.areaBreakdownSummary.contains("Drivebase (1)"))
    }

    @Test
    fun allProductionKotlinFilesStayAtOrBelow900LineCeilingWithZeroGrandfatheredFiles() {
        val projectRoot = File(".").canonicalFile.let { current ->
            if (File(current, "app/src/main").exists()) current else File(current, "ARES-Analytics")
        }
        val roots = listOf(
            File(projectRoot, "app/src/main"),
            File(projectRoot, "shared/src/main"),
            File(projectRoot, "gateway/src/main"),
        ).filter(File::exists)

        val oversized = mutableListOf<String>()
        roots.forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val count = file.useLines { it.count() }
                    if (count > 750) {
                        val rel = file.relativeTo(projectRoot).invariantSeparatorsPath
                        oversized += "$rel ($count lines)"
                    }
                }
        }
        assertTrue(
            oversized.isEmpty(),
            "Expected 0 production Kotlin files above 750 lines, but found:\n${oversized.joinToString("\n")}",
        )

        val baselineFile = File(projectRoot, "config/maintainability/large-production-kotlin-baseline.txt")
        if (baselineFile.exists()) {
            val activeEntries = baselineFile.readLines()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            assertTrue(
                activeEntries.isEmpty(),
                "Expected 0 grandfathered files in large-production-kotlin-baseline.txt, found: $activeEntries",
            )
        }
    }
}
