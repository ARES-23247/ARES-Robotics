package com.ares.analytics.viewmodel.tuning

import com.ares.analytics.service.AlignedRunSample
import com.ares.analytics.service.ComparisonClaimKind
import com.ares.analytics.service.GuidedComparisonFinding
import com.ares.analytics.service.GuidedRunAnalysisReport
import com.ares.analytics.service.GuidedRunAnalysisRepository
import com.ares.analytics.service.RUN_START_ALIGNMENT_ID
import com.ares.analytics.service.RunAlignmentKind
import com.ares.analytics.service.RunAlignmentOption
import com.ares.analytics.service.RunComparisonEvidenceLink
import com.ares.analytics.service.RunComparisonMetric
import com.ares.analytics.service.RunComparisonReport
import com.ares.analytics.service.RunComparisonRepository
import com.ares.analytics.service.RunComparisonRequest
import com.ares.analytics.service.RunComparisonSeries
import com.ares.analytics.service.RunMetricSummary
import com.ares.analytics.service.tuning.ExperimentDirection
import com.ares.analytics.service.tuning.ExperimentMetricGoal
import com.ares.analytics.service.tuning.ExperimentMetricOption
import com.ares.analytics.service.tuning.ExperimentMetricStatistic
import com.ares.analytics.service.tuning.GuidedTuningExperiment
import com.ares.analytics.service.tuning.GuidedTuningExperimentEvaluator
import com.ares.analytics.service.tuning.GuidedTuningExperimentPlan
import com.ares.analytics.service.tuning.GuidedTuningExperimentRepository
import com.ares.analytics.service.tuning.GuidedTuningExperimentSeed
import com.ares.analytics.service.tuning.GuidedTuningProposalPolicy
import com.ares.analytics.service.tuning.PeerReviewState
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.viewmodel.TuningState
import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningAssignment
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningProfileAuthority
import com.areslib.tuning.TuningProfileDocument
import com.areslib.tuning.TuningValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuidedExperimentLifecycleAuditTest {

    @Test
    fun `candidate selection change supersedes pending evaluation and prevents stale staging`() = withTestFixture { f ->
        f.viewModel.onIntent(GuidedTuningExperimentIntent.LoadExperiment(f.expA.uid))
        awaitJobs(f.scope)
        f.viewModel.onIntent(GuidedTuningExperimentIntent.SelectCandidateRun("candidate-1"))
        assertEquals("candidate-1", f.viewModel.state.value.selectedCandidateSessionId)
        f.stagedProposals.clear()

        f.viewModel.onIntent(GuidedTuningExperimentIntent.EvaluateCandidate)
        withTimeout(5_000) { f.comparisonRepo.entered.await() }

        f.viewModel.onIntent(GuidedTuningExperimentIntent.SelectCandidateRun("candidate-2"))
        assertEquals("candidate-2", f.viewModel.state.value.selectedCandidateSessionId)

        f.comparisonRepo.result.complete(f.testReport)
        awaitJobs(f.scope)

        assertTrue(f.stagedProposals.isEmpty())
        assertEquals("candidate-2", f.viewModel.state.value.selectedCandidateSessionId)
        assertNull(f.viewModel.state.value.experiment?.candidateSessionId)
        assertNull(f.viewModel.state.value.comparisonReport)
        assertNull(f.viewModel.state.value.errorMessage)
        assertNull(f.repository.load(f.workspace.projectPath, f.expA.uid).candidateSessionId)
    }

    @Test
    fun `LoadExperiment supersedes pending evaluation and prevents stale staging or replacement`() = withTestFixture { f ->
        f.viewModel.onIntent(GuidedTuningExperimentIntent.LoadExperiment(f.expA.uid))
        awaitJobs(f.scope)
        f.viewModel.onIntent(GuidedTuningExperimentIntent.SelectCandidateRun("candidate-1"))
        f.stagedProposals.clear()

        f.viewModel.onIntent(GuidedTuningExperimentIntent.EvaluateCandidate)
        withTimeout(5_000) { f.comparisonRepo.entered.await() }

        f.viewModel.onIntent(GuidedTuningExperimentIntent.LoadExperiment(f.expB.uid))
        awaitJobs(f.scope)
        assertEquals(f.expB.uid, f.viewModel.state.value.experiment?.uid)

        f.comparisonRepo.result.complete(f.testReport)
        awaitJobs(f.scope)

        assertTrue(f.stagedProposals.isEmpty())
        assertEquals(f.expB.uid, f.viewModel.state.value.experiment?.uid)
        assertNull(f.viewModel.state.value.errorMessage)
        assertNull(f.repository.load(f.workspace.projectPath, f.expA.uid).candidateSessionId)
    }

    @Test
    fun `Begin supersedes pending evaluation and prevents stale staging or draft overwrite`() = withTestFixture { f ->
        f.viewModel.onIntent(GuidedTuningExperimentIntent.LoadExperiment(f.expA.uid))
        awaitJobs(f.scope)
        f.viewModel.onIntent(GuidedTuningExperimentIntent.SelectCandidateRun("candidate-1"))
        f.stagedProposals.clear()

        f.viewModel.onIntent(GuidedTuningExperimentIntent.EvaluateCandidate)
        withTimeout(5_000) { f.comparisonRepo.entered.await() }

        f.viewModel.onIntent(GuidedTuningExperimentIntent.Begin(f.newSeed))
        assertNull(f.viewModel.state.value.experiment)
        assertEquals(f.newSeed.baselineSessionId, f.viewModel.state.value.seed?.baselineSessionId)

        f.comparisonRepo.result.complete(f.testReport)
        awaitJobs(f.scope)

        assertTrue(f.stagedProposals.isEmpty())
        assertNull(f.viewModel.state.value.experiment)
        assertEquals(f.newSeed.baselineSessionId, f.viewModel.state.value.seed?.baselineSessionId)
        assertNull(f.viewModel.state.value.errorMessage)
        assertNull(f.repository.load(f.workspace.projectPath, f.expA.uid).candidateSessionId)
    }

    @Test
    fun `positive control persists evaluation and stages proposal when context is unchanged`() = withTestFixture { f ->
        f.viewModel.onIntent(GuidedTuningExperimentIntent.LoadExperiment(f.expA.uid))
        awaitJobs(f.scope)
        f.viewModel.onIntent(GuidedTuningExperimentIntent.SelectCandidateRun("candidate-1"))
        assertEquals("candidate-1", f.viewModel.state.value.selectedCandidateSessionId)
        f.stagedProposals.clear()

        f.viewModel.onIntent(GuidedTuningExperimentIntent.EvaluateCandidate)
        withTimeout(5_000) { f.comparisonRepo.entered.await() }

        f.comparisonRepo.result.complete(f.testReport)
        awaitJobs(f.scope)

        assertEquals(1, f.stagedProposals.size)
        assertEquals(f.expA.change.key, f.stagedProposals.single().key)
        assertEquals("candidate-1", f.viewModel.state.value.experiment?.candidateSessionId)
        assertEquals(f.testReport, f.viewModel.state.value.comparisonReport)
        assertNull(f.viewModel.state.value.errorMessage)
        assertEquals("candidate-1", f.repository.load(f.workspace.projectPath, f.expA.uid).candidateSessionId)
    }

    private fun withTestFixture(
        block: suspend (TestFixture) -> Unit,
    ) = runBlocking {
        val tempDir = Files.createTempDirectory("guided-lifecycle-audit-test").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val fixture = setupFixture(tempDir, scope)
            withTimeout(10_000) { block(fixture) }
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            tempDir.deleteRecursively()
        }
    }

    private suspend fun awaitJobs(scope: CoroutineScope) = withTimeout(5_000) {
        while (true) {
            val jobs = scope.coroutineContext[Job]!!.children.toList()
            if (jobs.isEmpty()) break
            jobs.forEach { it.join() }
        }
    }

    private suspend fun setupFixture(tempDir: File, scope: CoroutineScope): TestFixture {
        File(tempDir, ".ares/project.json").apply { parentFile.mkdirs(); writeText("{\"projectId\":\"robot.project\"}") }
        File(tempDir, ".ares/drivetrains/drive.aresdrivetrain").apply { parentFile.mkdirs(); writeText("{\"uid\":\"drive.primary\"}") }
        val workspace = WorkspaceConfig(
            id = "workspace",
            teamId = "23247",
            seasonId = "2026",
            robotId = "robot",
            robotName = "Test Robot",
            projectPath = tempDir.path,
            league = League.FTC,
        )
        val gain = TuningParameterDeclaration(
            uid = "drive.translation.kp",
            key = "drive.translation.kP",
            componentUid = "drive.primary",
            displayName = "Translation kP",
            description = "Proportional translation gain",
            type = TuningParameterType.DOUBLE,
            unit = "gain",
            minimum = 0.0,
            maximum = 10.0,
            defaultValue = TuningValue(doubleValue = 2.0),
            applyPolicy = TuningApplyPolicy.LIVE_SAFE,
        )
        val profile = TuningProfileDocument(
            uid = "profile.competition",
            profileId = "competition",
            displayName = "Competition",
            description = "Test profile",
            projectId = "robot.project",
            drivebaseUid = "drive.primary",
            authority = TuningProfileAuthority.CANONICAL_CHECKED_IN,
            values = listOf(TuningAssignment(gain.uid, gain.defaultValue)),
        )
        val repository = GuidedTuningExperimentRepository(nowMillis = { 10_000L }, idProvider = { "exp-a" })
        val metric = ExperimentMetricOption(
            id = "mechanism_tracking_error",
            label = "Mechanism tracking error",
            unit = "rad",
            statistic = ExperimentMetricStatistic.P95,
            goal = ExperimentMetricGoal.LOWER_IS_BETTER,
        )
        val seed = GuidedTuningExperimentSeed(
            finding = GuidedComparisonFinding(
                id = "finding-1",
                kind = ComparisonClaimKind.CORRELATION,
                title = "Test Finding",
                explanation = "Observation only",
                evidence = RunComparisonEvidenceLink("baseline", 1_000L, 0L, listOf("Mechanism/Error")),
            ),
            baselineSessionId = "baseline",
            availableMetrics = listOf(metric),
        )
        val plan = GuidedTuningExperimentPlan(
            question = "Will small increase improve tracking?",
            hypothesis = "Tracking error reduces",
            heldConstants = listOf("Same route"),
            successThresholdPercent = 5.0,
            safetyNotes = "Local Sim only",
            peerReviewState = PeerReviewState.NOT_REQUESTED,
        )
        val expA = repository.create(
            workspace, profile, listOf(profile), listOf(gain),
            seed, GuidedTuningProposalPolicy.propose(gain, gain.defaultValue, ExperimentDirection.INCREASE),
            metric, plan,
        )
        val repositoryB = GuidedTuningExperimentRepository(nowMillis = { 11_000L }, idProvider = { "exp-b" })
        val expB = repositoryB.create(
            workspace, profile, listOf(profile), listOf(gain),
            seed, GuidedTuningProposalPolicy.propose(gain, gain.defaultValue, ExperimentDirection.INCREASE),
            metric, plan.copy(hypothesis = "Hypothesis B"),
        )

        val candidate1 = Session("candidate-1", workspace.teamId, workspace.seasonId, workspace.robotId, 12_000L, tags = listOf("simulation"))
        val candidate2 = Session("candidate-2", workspace.teamId, workspace.seasonId, workspace.robotId, 13_000L, tags = listOf("simulation"))
        val runRepo = FakeRunRepository(listOf(candidate1, candidate2))
        val comparisonRepo = BlockingComparisonRepository()
        val evaluator = GuidedTuningExperimentEvaluator(comparisonRepo)
        val stagedProposals = mutableListOf<GuidedExperimentProposal>()

        val tuningState = TuningState(
            projectPath = tempDir.path,
            catalog = listOf(gain),
            profiles = listOf(profile),
            selectedProfileId = profile.profileId,
            consumerSupportByUid = mapOf(gain.uid to true),
            liveTypedValues = mapOf(gain.key to requireNotNull(gain.defaultValue)),
        )

        val viewModel = GuidedTuningExperimentViewModel(
            workspace = workspace,
            scope = scope,
            runRepository = runRepo,
            repository = repository,
            evaluator = evaluator,
            tuningState = { tuningState },
            stageProposal = { stagedProposals.add(it) },
            removeProposal = {},
        )
        awaitJobs(scope)

        val testReport = RunComparisonReport(
            sessions = listOf(
                Session("baseline", workspace.teamId, workspace.seasonId, workspace.robotId, 1_000L),
                candidate1,
            ),
            primarySessionId = "baseline",
            selectedAlignment = RunAlignmentOption(RUN_START_ALIGNMENT_ID, RunAlignmentKind.RUN_START, "Run start", "Align run starts"),
            availableAlignments = emptyList(),
            anchors = emptyList(),
            trajectories = emptyList(),
            metrics = listOf(
                RunComparisonMetric(
                    id = "mechanism_tracking_error",
                    label = "Mechanism tracking error",
                    unit = "rad",
                    explanation = "Exact error",
                    series = listOf(
                        RunComparisonSeries("baseline", "baseline", listOf("Mechanism/Error"), listOf(AlignedRunSample(0L, 1_000L, 10.0)), RunMetricSummary(10.0, 10.0, 10.0, 10.0, 1)),
                        RunComparisonSeries(candidate1.sessionId, candidate1.sessionId, listOf("Mechanism/Error"), listOf(AlignedRunSample(0L, 1_000L, 7.0)), RunMetricSummary(7.0, 7.0, 7.0, 7.0, 1)),
                    ),
                ),
            ),
            faults = emptyList(),
            findings = emptyList(),
            limitations = listOf("Recorded evidence only."),
        )

        return TestFixture(
            root = tempDir,
            workspace = workspace,
            scope = scope,
            repository = repository,
            runRepo = runRepo,
            comparisonRepo = comparisonRepo,
            evaluator = evaluator,
            stagedProposals = stagedProposals,
            viewModel = viewModel,
            expA = expA,
            expB = expB,
            candidate1 = candidate1,
            candidate2 = candidate2,
            newSeed = seed.copy(baselineSessionId = "new-baseline"),
            testReport = testReport,
        )
    }

    private class TestFixture(
        val root: File,
        val workspace: WorkspaceConfig,
        val scope: CoroutineScope,
        val repository: GuidedTuningExperimentRepository,
        val runRepo: FakeRunRepository,
        val comparisonRepo: BlockingComparisonRepository,
        val evaluator: GuidedTuningExperimentEvaluator,
        val stagedProposals: MutableList<GuidedExperimentProposal>,
        val viewModel: GuidedTuningExperimentViewModel,
        val expA: GuidedTuningExperiment,
        val expB: GuidedTuningExperiment,
        val candidate1: Session,
        val candidate2: Session,
        val newSeed: GuidedTuningExperimentSeed,
        val testReport: RunComparisonReport,
    )

    private class FakeRunRepository(
        private val sessions: List<Session> = emptyList(),
    ) : GuidedRunAnalysisRepository {
        override suspend fun listWorkspaceSessions(workspace: WorkspaceConfig): List<Session> = sessions
        override suspend fun analyze(workspace: WorkspaceConfig, sessionId: String): GuidedRunAnalysisReport = error("unused")
        override suspend fun exportMarkdown(report: GuidedRunAnalysisReport, destination: File) = error("unused")
    }

    private class BlockingComparisonRepository : RunComparisonRepository {
        val entered = CompletableDeferred<Unit>()
        val result = CompletableDeferred<RunComparisonReport>()

        override suspend fun compare(workspace: WorkspaceConfig, request: RunComparisonRequest): RunComparisonReport {
            entered.complete(Unit)
            return result.await()
        }

        override suspend fun exportMarkdown(report: RunComparisonReport, destination: File) = Unit
    }
}
