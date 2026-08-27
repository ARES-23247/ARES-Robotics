package com.ares.analytics.service.tuning

import com.ares.analytics.service.AlignedRunSample
import com.ares.analytics.service.ComparisonClaimKind
import com.ares.analytics.service.GuidedComparisonFinding
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
import com.ares.analytics.shared.League
import com.ares.analytics.shared.Session
import com.ares.analytics.shared.WorkspaceConfig
import com.ares.analytics.viewmodel.tuning.renderGuidedTuningExperimentReport
import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningAssignment
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningProfileAuthority
import com.areslib.tuning.TuningProfileDocument
import com.areslib.tuning.TuningValue
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GuidedTuningExperimentTest {
    @Test
    fun `proposal policy changes exactly one typed value within conservative bounds`() {
        val gain = declaration(default = 2.0, minimum = 0.0, maximum = 10.0)
        val increased = GuidedTuningProposalPolicy.propose(
            gain,
            TuningValue(doubleValue = 2.0),
            ExperimentDirection.INCREASE,
        )
        assertEquals(2.2, requireNotNull(increased.proposed.doubleValue), 1e-12)
        assertEquals(0.2, increased.stepLimit, 1e-12)
        assertTrue(requireNotNull(increased.proposed.doubleValue) <= requireNotNull(gain.maximum))

        val nearLimit = gain.copy(defaultValue = TuningValue(doubleValue = 9.95))
        val bounded = GuidedTuningProposalPolicy.propose(
            nearLimit,
            TuningValue(doubleValue = 9.95),
            ExperimentDirection.INCREASE,
        )
        assertEquals(10.0, bounded.proposed.doubleValue)
        assertFailsWith<IllegalArgumentException> {
            GuidedTuningProposalPolicy.propose(gain, TuningValue(doubleValue = 10.0), ExperimentDirection.INCREASE)
        }
    }

    @Test
    fun `integer proposal is a whole step and unsupported values fail closed`() {
        val integer = declaration(
            uid = "drive.samples",
            key = "drive.samples",
            default = 2.0,
            minimum = 0.0,
            maximum = 5.0,
            type = TuningParameterType.INT,
        )
        val proposal = GuidedTuningProposalPolicy.propose(
            integer,
            TuningValue(intValue = 2),
            ExperimentDirection.DECREASE,
        )
        assertEquals(1, proposal.proposed.intValue)
        assertEquals(1.0, proposal.stepLimit)

        assertFailsWith<IllegalArgumentException> {
            GuidedTuningProposalPolicy.propose(
                declaration(policy = TuningApplyPolicy.READ_ONLY_VENDOR),
                TuningValue(doubleValue = 2.0),
                ExperimentDirection.INCREASE,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GuidedTuningProposalPolicy.propose(
                declaration(type = TuningParameterType.BOOLEAN),
                TuningValue(booleanValue = true),
                ExperimentDirection.INCREASE,
            )
        }
    }

    @Test
    fun `repository snapshots canonical configuration and excludes local experiment state`() {
        val root = Files.createTempDirectory("guided-tuning-project").toFile()
        File(root, ".ares/project.json").apply { parentFile.mkdirs(); writeText("{\"projectId\":\"robot.project\"}") }
        File(root, ".ares/drivetrains/drive.aresdrivetrain").apply { parentFile.mkdirs(); writeText("{\"uid\":\"drive.primary\"}") }
        File(root, ".ares/local/private.json").apply { parentFile.mkdirs(); writeText("do not hash") }
        File(root, ".ares/history/old.json").apply { parentFile.mkdirs(); writeText("do not hash") }
        val gain = declaration()
        val profile = profile(gain)
        val repository = GuidedTuningExperimentRepository(nowMillis = { 1_000L }, idProvider = { "fixed" })
        val experiment = repository.create(
            workspace(root),
            profile,
            listOf(profile),
            listOf(gain),
            seed(),
            GuidedTuningProposalPolicy.propose(gain, gain.defaultValue, ExperimentDirection.INCREASE),
            metricOption(),
            experimentPlan(),
        )

        assertEquals("experiment-fixed", experiment.uid)
        assertEquals("Will a small kP increase reduce tracking error?", experiment.question)
        assertEquals(listOf("Same routine", "Same starting pose", "Same simulated load"), experiment.heldConstants)
        assertEquals(5.0, experiment.successThresholdPercent)
        assertEquals(MentorReviewState.REQUESTED, experiment.mentorReviewState)
        assertEquals(2, experiment.snapshot.configurationFiles.size)
        assertTrue(experiment.snapshot.configurationFiles.any { it.projectRelativePath == ".ares/project.json" })
        assertFalse(experiment.snapshot.configurationFiles.any { "/local/" in it.projectRelativePath || "/history/" in it.projectRelativePath })
        assertEquals(experiment, repository.load(root.path, experiment.uid))
        assertEquals(listOf(experiment), repository.list(root.path))
        assertTrue(repository.relativePath(experiment).startsWith(".ares/local/tuning/experiments/"))
        assertTrue(repository.sha256(root.path, experiment).matches(Regex("[a-f0-9]{64}")))

        val secondRoot = Files.createTempDirectory("guided-tuning-project-copy").toFile()
        File(secondRoot, ".ares/project.json").apply { parentFile.mkdirs(); writeText("{\"projectId\":\"robot.project\"}") }
        File(secondRoot, ".ares/drivetrains/drive.aresdrivetrain").apply { parentFile.mkdirs(); writeText("{\"uid\":\"drive.primary\"}") }
        val second = repository.create(
            workspace(secondRoot), profile, listOf(profile), listOf(gain), seed(),
            GuidedTuningProposalPolicy.propose(gain, gain.defaultValue, ExperimentDirection.INCREASE),
            metricOption(), experimentPlan(),
        )
        assertEquals(experiment.snapshot.snapshotSha256, second.snapshot.snapshotSha256)
        assertFailsWith<IllegalArgumentException> { repository.load(root.path, "../escape") }

        File(root, ".ares/local/tuning/experiments/corrupt.arestuningexperiment.json").writeText("not json")
        assertFailsWith<IllegalStateException> { repository.list(root.path) }
    }

    @Test
    fun `evaluator compares exact selected statistic and preserves causal uncertainty`() = runBlocking {
        val baseline = metricSeries("baseline", 10.0)
        val candidate = metricSeries("candidate", 7.0)
        val report = report(baseline, candidate)
        val evaluator = GuidedTuningExperimentEvaluator(FakeComparisonRepository(report))
        val experiment = experiment(metricOption(), createdAt = 1_000L)

        val (evaluated, returnedReport) = evaluator.evaluate(workspace(Files.createTempDirectory("guided-eval").toFile()), experiment, "candidate")

        assertEquals(report, returnedReport)
        assertEquals(10.0, evaluated.evaluation?.baselineValue)
        assertEquals(7.0, evaluated.evaluation?.candidateValue)
        assertEquals(-3.0, evaluated.evaluation?.absoluteDelta)
        assertEquals(true, evaluated.evaluation?.improvedIntendedMetric)
        assertTrue(evaluated.evaluation!!.summary.contains("does not prove"))
        assertTrue(evaluated.evaluation!!.limitations.any { it.contains("Simulation evidence") })
        assertEquals(ExperimentPhase.EVALUATED, evaluated.phase)
    }

    @Test
    fun `evaluator classifies improvement regression and below-threshold evidence explicitly`() = runBlocking {
        suspend fun evaluate(candidateValue: Double): ExperimentEvaluation {
            val evaluator = GuidedTuningExperimentEvaluator(
                FakeComparisonRepository(report(metricSeries("baseline", 10.0), metricSeries("candidate", candidateValue)))
            )
            return requireNotNull(
                evaluator.evaluate(
                    workspace(Files.createTempDirectory("guided-outcome").toFile()),
                    experiment(metricOption(), createdAt = 1_000L),
                    "candidate",
                ).first.evaluation
            )
        }

        assertEquals(ExperimentOutcome.IMPROVED, evaluate(9.0).outcome)
        assertEquals(ExperimentOutcome.INCONCLUSIVE, evaluate(9.7).outcome)
        assertEquals(ExperimentOutcome.REGRESSED, evaluate(11.0).outcome)
    }

    @Test
    fun `current metrics are evaluated as lower is better`() {
        val source = report(metricSeries("baseline", 10.0))
        val current = source.metrics.single().copy(
            id = "drive_total_current_amps",
            label = "Drivetrain current",
            unit = "A",
        )

        val option = source.copy(metrics = listOf(current)).toExperimentMetricOptions().single()

        assertEquals(ExperimentMetricGoal.LOWER_IS_BETTER, option.goal)
        assertEquals(ExperimentMetricStatistic.P95, option.statistic)
    }

    @Test
    fun `observe only metric and workspace mismatch do not invent an improvement claim`() = runBlocking {
        val evaluator = GuidedTuningExperimentEvaluator(FakeComparisonRepository(report(metricSeries("baseline", 1.0), metricSeries("candidate", 2.0))))
        val observe = metricOption().copy(goal = ExperimentMetricGoal.OBSERVE_ONLY)
        val root = Files.createTempDirectory("guided-observe").toFile()
        val evaluated = evaluator.evaluate(workspace(root), experiment(observe, 1_000L), "candidate").first
        assertEquals(null, evaluated.evaluation?.improvedIntendedMetric)

        assertFailsWith<IllegalArgumentException> {
            evaluator.evaluate(workspace(root).copy(robotId = "another-robot"), experiment(observe, 1_000L), "candidate")
        }
        Unit
    }

    @Test
    fun `evaluator rejects non-simulation candidates and incompatible metric units`() = runBlocking {
        val root = Files.createTempDirectory("guided-boundaries").toFile()
        val normal = report(metricSeries("baseline", 10.0), metricSeries("candidate", 9.0))
        val nonSimulation = normal.copy(
            sessions = normal.sessions.map { it.copy(tags = emptyList()) },
        )
        assertFailsWith<IllegalArgumentException> {
            GuidedTuningExperimentEvaluator(FakeComparisonRepository(nonSimulation)).evaluate(
                workspace(root),
                experiment(metricOption(), 1_000L),
                "candidate",
            )
        }

        val incompatibleUnits = normal.copy(
            metrics = normal.metrics.map { it.copy(unit = "degrees") },
        )
        assertFailsWith<IllegalArgumentException> {
            GuidedTuningExperimentEvaluator(FakeComparisonRepository(incompatibleUnits)).evaluate(
                workspace(root),
                experiment(metricOption(), 1_000L),
                "candidate",
            )
        }
        Unit
    }

    @Test
    fun `candidate runs must be new distinct runs from after the snapshot`() {
        val experiment = experiment(metricOption(), createdAt = 1_000L)
        val sessions = listOf(
            session("baseline", 500L, tags = listOf("simulation")),
            session("old", 999L, tags = listOf("simulation")),
            session("candidate-a", 1_000L, tags = listOf("simulation")),
            session("candidate-b", 2_000L, tags = listOf("SIMULATION")),
            session("later-live-run", 3_000L),
            session("later-import", 4_000L, tags = listOf("imported")),
            session("another-team", 5_000L, tags = listOf("simulation")).copy(teamId = "other-team"),
            session("another-robot", 6_000L, tags = listOf("simulation")).copy(robotId = "other-robot"),
        )
        assertEquals(listOf("candidate-b", "candidate-a"), experiment.candidateRuns(sessions).map(Session::sessionId))
    }

    @Test
    fun `saved experiment restores its evidence and metric UI boundary`() {
        val original = experiment(metricOption(), createdAt = 1_000L)

        val restored = original.restoredSeed()

        assertEquals(original.baselineSessionId, restored.baselineSessionId)
        assertEquals(original.finding.findingId, restored.finding.id)
        assertEquals(ComparisonClaimKind.CORRELATION, restored.finding.kind)
        assertEquals(original.finding.absoluteTimestampMs, restored.finding.evidence.absoluteTimestampMs)
        assertEquals(original.metric.metricId, restored.availableMetrics.single().id)
        assertEquals("rad", restored.availableMetrics.single().unit)
    }

    @Test
    fun `acceptance requires finite evidence that improved the intended metric`() {
        val base = experiment(metricOption(), createdAt = 1_000L)
        assertFalse(base.canAcceptSimulationResult())
        assertFalse(base.copy(evaluation = evaluation(null, null, null, "Unavailable")).canAcceptSimulationResult())
        assertFalse(base.copy(evaluation = evaluation(10.0, 12.0, false, "Worse")).canAcceptSimulationResult())
        assertTrue(base.copy(evaluation = evaluation(10.0, 7.0, true, "Improved")).canAcceptSimulationResult())
    }

    @Test
    fun `mentor report keeps uncertainty and formats student-facing numbers`() {
        val base = experiment(metricOption(), createdAt = 1_000L)
        val report = renderGuidedTuningExperimentReport(
            base.copy(
                change = base.change.copy(boundedStepLimit = 0.04749999999999999),
                evaluation = evaluation(null, null, null, "The required signal was unavailable."),
                decision = ExperimentDecision.REVISE,
                decisionNote = "Use a simulator-observable metric.",
                nextTest = "Repeat with loop-time p95 while holding the same route.",
            )
        )

        assertTrue(report.contains("Maximum bounded step used: 0.0475"))
        assertTrue(report.contains("Baseline value: unavailable"))
        assertTrue(report.contains("not proof of causation"))
        assertTrue(report.contains("Decision: REVISE"))
        assertTrue(report.contains("Question: Will a small kP increase reduce tracking error?"))
        assertTrue(report.contains("Success threshold: 5% improvement"))
        assertTrue(report.contains("Next safe test or review: Repeat with loop-time p95"))
        assertFalse(report.contains("0.047499999999"))
    }

    @Test
    fun `evaluator rejects a candidate that predates the experiment snapshot`() = runBlocking {
        val oldCandidateReport = report(metricSeries("baseline", 10.0), metricSeries("candidate", 9.0)).copy(
            sessions = listOf(session("baseline", 500L), session("candidate", 999L)),
        )
        val evaluator = GuidedTuningExperimentEvaluator(FakeComparisonRepository(oldCandidateReport))
        assertFailsWith<IllegalArgumentException> {
            evaluator.evaluate(
                workspace(Files.createTempDirectory("guided-old-candidate").toFile()),
                experiment(metricOption(), createdAt = 1_000L),
                "candidate",
            )
        }
        Unit
    }

    private fun declaration(
        uid: String = "drive.translation.kp",
        key: String = "drive.translation.kP",
        default: Double = 2.0,
        minimum: Double? = 0.0,
        maximum: Double? = 10.0,
        type: TuningParameterType = TuningParameterType.DOUBLE,
        policy: TuningApplyPolicy = TuningApplyPolicy.LIVE_SAFE,
    ) = TuningParameterDeclaration(
        uid = uid,
        key = key,
        componentUid = "drive.primary",
        displayName = "Translation kP",
        description = "Proportional translation gain",
        type = type,
        unit = if (type == TuningParameterType.BOOLEAN) null else "gain",
        minimum = if (type == TuningParameterType.BOOLEAN) null else minimum,
        maximum = if (type == TuningParameterType.BOOLEAN) null else maximum,
        defaultValue = when (type) {
            TuningParameterType.INT -> TuningValue(intValue = default.toInt())
            TuningParameterType.BOOLEAN -> TuningValue(booleanValue = true)
            else -> TuningValue(doubleValue = default)
        },
        applyPolicy = policy,
    )

    private fun profile(declaration: TuningParameterDeclaration) = TuningProfileDocument(
        uid = "profile.competition",
        profileId = "competition",
        displayName = "Competition",
        description = "Test profile",
        projectUid = "robot.project",
        drivebaseUid = "drive.primary",
        authority = TuningProfileAuthority.CANONICAL_CHECKED_IN,
        values = listOf(TuningAssignment(declaration.uid, declaration.defaultValue)),
    )

    private fun workspace(root: File) = WorkspaceConfig(
        id = "workspace",
        teamId = "23247",
        seasonId = "2026",
        robotId = "robot",
        robotName = "Test Robot",
        projectPath = root.path,
        league = League.FTC,
    )

    private fun seed() = GuidedTuningExperimentSeed(
        finding = finding(),
        baselineSessionId = "baseline",
        availableMetrics = listOf(metricOption()),
    )

    private fun finding() = GuidedComparisonFinding(
        id = "finding-loop-time",
        kind = ComparisonClaimKind.CORRELATION,
        title = "Candidate loop time was slower",
        explanation = "The values changed together; this does not prove why.",
        evidence = RunComparisonEvidenceLink("baseline", 1_234L, 234L, listOf("Robot/LoopTimeMs")),
    )

    private fun metricOption() = ExperimentMetricOption(
        id = "mechanism_tracking_error",
        label = "Mechanism tracking error",
        unit = "rad",
        statistic = ExperimentMetricStatistic.P95,
        goal = ExperimentMetricGoal.LOWER_IS_BETTER,
    )

    private fun experiment(metric: ExperimentMetricOption, createdAt: Long): GuidedTuningExperiment {
        val gain = declaration()
        return GuidedTuningExperiment(
            uid = "experiment-test",
            teamId = "23247",
            seasonId = "2026",
            robotId = "robot",
            title = "Controlled experiment",
            question = "Will a small kP increase reduce tracking error?",
            hypothesis = "The candidate should improve the metric.",
            heldConstants = listOf("Same routine", "Same starting pose", "Same simulated load"),
            successThresholdPercent = 5.0,
            safetyNotes = "Local Sim only; stop on a fault or missing feedback.",
            mentorReviewState = MentorReviewState.REQUESTED,
            finding = ExperimentFindingEvidence("finding", "CORRELATION", "Finding", "Cause unproven", "baseline", 1_000L, 0L, listOf("topic")),
            snapshot = ExperimentSnapshot("profile.competition", "a".repeat(64), emptyList(), emptyList(), "b".repeat(64)),
            change = ExperimentParameterChange(
                gain.uid, gain.key, gain.displayName, gain.unit.orEmpty(), gain.type.name, gain.applyPolicy.name,
                ExperimentValue(doubleValue = 2.0), ExperimentValue(doubleValue = 2.2), 0.0, 10.0, 0.2,
            ),
            metric = ExperimentMetricIntent(metric.id, metric.label, metric.unit, metric.statistic, metric.goal),
            baselineSessionId = "baseline",
            createdAtEpochMs = createdAt,
            updatedAtEpochMs = createdAt,
        )
    }

    private fun metricSeries(sessionId: String, p95: Double) = RunComparisonSeries(
        sessionId = sessionId,
        runLabel = sessionId,
        sourceTopics = listOf("Mechanism/Error"),
        samples = listOf(AlignedRunSample(0L, 1_000L, p95)),
        summary = RunMetricSummary(p95, p95, p95, p95, 1),
    )

    private fun experimentPlan() = GuidedTuningExperimentPlan(
        question = "Will a small kP increase reduce tracking error?",
        hypothesis = "Increasing kP should reduce p95 mechanism tracking error.",
        heldConstants = listOf("Same routine", "Same starting pose", "Same simulated load"),
        successThresholdPercent = 5.0,
        safetyNotes = "Local Sim only; stop on a fault or missing feedback.",
        mentorReviewState = MentorReviewState.REQUESTED,
    )

    private fun evaluation(
        baseline: Double?,
        candidate: Double?,
        improved: Boolean?,
        summary: String,
    ) = ExperimentEvaluation(
        comparisonAlignmentId = RUN_START_ALIGNMENT_ID,
        baselineValue = baseline,
        candidateValue = candidate,
        unit = "rad",
        absoluteDelta = if (baseline != null && candidate != null) candidate - baseline else null,
        percentDelta = null,
        improvedIntendedMetric = improved,
        summary = summary,
        evidenceTimestampMs = null,
        evidenceTopics = emptyList(),
        limitations = emptyList(),
    )

    private fun report(vararg series: RunComparisonSeries) = RunComparisonReport(
        sessions = series.map { run ->
            session(run.sessionId, 1_000L, tags = if (run.sessionId == "baseline") emptyList() else listOf("simulation"))
        },
        primarySessionId = "baseline",
        selectedAlignment = RunAlignmentOption(RUN_START_ALIGNMENT_ID, RunAlignmentKind.RUN_START, "Run start", "Align run starts"),
        availableAlignments = emptyList(),
        anchors = emptyList(),
        trajectories = emptyList(),
        metrics = listOf(RunComparisonMetric("mechanism_tracking_error", "Mechanism tracking error", "rad", "Exact error", series.toList())),
        faults = emptyList(),
        findings = listOf(finding().copy(evidence = RunComparisonEvidenceLink("candidate", 1_000L, 0L, listOf("Mechanism/Error")))),
        limitations = listOf("Recorded evidence only."),
    )

    private fun session(id: String, createdAt: Long, tags: List<String> = emptyList()) =
        Session(id, "23247", "2026", "robot", createdAt, tags = tags)

    private class FakeComparisonRepository(
        private val report: RunComparisonReport,
    ) : RunComparisonRepository {
        override suspend fun compare(workspace: WorkspaceConfig, request: RunComparisonRequest): RunComparisonReport = report
        override suspend fun exportMarkdown(report: RunComparisonReport, destination: File) = destination.writeText("unused")
    }
}
