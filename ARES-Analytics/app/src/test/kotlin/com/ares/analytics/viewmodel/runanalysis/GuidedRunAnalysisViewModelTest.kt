package com.ares.analytics.viewmodel.runanalysis

import com.ares.analytics.service.GuidedRunAnalysisReport
import com.ares.analytics.service.GuidedRunAnalysisRepository
import com.ares.analytics.service.GuidedRunConfidence
import com.ares.analytics.service.GuidedRunEvidenceContext
import com.ares.analytics.service.RunEvidenceSourceKind
import com.ares.analytics.service.RunSourceEvidence
import com.ares.analytics.service.RunAlignmentKind
import com.ares.analytics.service.RunAlignmentOption
import com.ares.analytics.service.RunComparisonReport
import com.ares.analytics.service.RunComparisonRepository
import com.ares.analytics.service.RunComparisonRequest
import com.ares.analytics.service.RunAlignmentAnchor
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.WorkspaceConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GuidedRunAnalysisViewModelTest {
    @Test
    fun `a delayed old workspace cannot replace the selected workspace review`() = runTest {
        val oldSessions = CompletableDeferred<List<Session>>()
        val newSessions = CompletableDeferred<List<Session>>()
        val repository = FakeRepository(
            sessionLoads = mapOf("old" to oldSessions, "new" to newSessions),
        )
        val viewModel = GuidedRunAnalysisViewModel(repository, this)

        viewModel.load(workspace("old", "old-robot"))
        runCurrent()
        viewModel.load(workspace("new", "new-robot"))
        runCurrent()
        newSessions.complete(listOf(session("new-run", "new-robot")))
        advanceUntilIdle()

        assertEquals(listOf("new-run"), viewModel.state.value.sessions.map(Session::sessionId))
        assertEquals("new-run", viewModel.state.value.report?.session?.sessionId)
        assertEquals("C:/projects/new", viewModel.state.value.comparisonExportDirectory)

        oldSessions.complete(listOf(session("old-run", "old-robot")))
        advanceUntilIdle()

        assertEquals(listOf("new-run"), viewModel.state.value.sessions.map(Session::sessionId))
        assertEquals("new-run", viewModel.state.value.report?.session?.sessionId)
    }

    @Test
    fun `selection outside the workspace is rejected without running analysis`() = runTest {
        val sessions = CompletableDeferred<List<Session>>().apply {
            complete(listOf(session("mine", "practice")))
        }
        val repository = FakeRepository(mapOf("workspace" to sessions))
        val viewModel = GuidedRunAnalysisViewModel(repository, this)
        viewModel.load(workspace("workspace", "practice"))
        advanceUntilIdle()
        repository.analyzedSessionIds.clear()

        viewModel.selectSession("another-team")

        assertTrue(viewModel.state.value.error.orEmpty().contains("not part of the selected workspace"))
        assertTrue(repository.analyzedSessionIds.isEmpty())
        assertEquals("mine", viewModel.state.value.selectedSessionId)
    }

    @Test
    fun `empty workspace has a stable empty state instead of an analysis error`() = runTest {
        val sessions = CompletableDeferred<List<Session>>().apply { complete(emptyList()) }
        val repository = FakeRepository(mapOf("empty" to sessions))
        val viewModel = GuidedRunAnalysisViewModel(repository, this)

        viewModel.load(workspace("empty", "practice"))
        advanceUntilIdle()

        assertTrue(viewModel.state.value.sessions.isEmpty())
        assertNull(viewModel.state.value.selectedSessionId)
        assertNull(viewModel.state.value.report)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `student can select several comparison runs and change a shared alignment`() = runTest {
        val sessions = CompletableDeferred<List<Session>>().apply {
            complete(listOf(session("primary", "practice"), session("second", "practice"), session("third", "practice")))
        }
        val repository = FakeRepository(mapOf("workspace" to sessions))
        val comparisons = FakeComparisonRepository()
        val viewModel = GuidedRunAnalysisViewModel(repository, this, comparisons)

        viewModel.load(workspace("workspace", "practice"))
        advanceUntilIdle()

        assertEquals(listOf("second"), viewModel.state.value.comparisonSessionIds)
        assertEquals(listOf("second"), comparisons.requests.last().comparisonSessionIds)

        viewModel.toggleComparisonSession("third")
        advanceUntilIdle()
        assertEquals(listOf("second", "third"), viewModel.state.value.comparisonSessionIds)

        viewModel.selectAlignment("autonomous-start")
        advanceUntilIdle()
        assertEquals("autonomous-start", comparisons.requests.last().alignmentId)
        assertEquals("autonomous-start", viewModel.state.value.comparisonReport?.selectedAlignment?.id)
    }

    @Test
    fun `removing the final comparison cancels work without surfacing a false error`() = runTest {
        val sessions = CompletableDeferred<List<Session>>().apply {
            complete(listOf(session("primary", "practice"), session("second", "practice")))
        }
        val repository = FakeRepository(mapOf("workspace" to sessions))
        val comparisons = object : RunComparisonRepository {
            override suspend fun compare(workspace: WorkspaceConfig, request: RunComparisonRequest): RunComparisonReport {
                awaitCancellation()
            }

            override suspend fun exportMarkdown(report: RunComparisonReport, destination: File) = Unit
        }
        val viewModel = GuidedRunAnalysisViewModel(repository, this, comparisons)

        viewModel.load(workspace("workspace", "practice"))
        runCurrent()
        assertTrue(viewModel.state.value.comparing)

        viewModel.toggleComparisonSession("second")
        runCurrent()

        assertTrue(viewModel.state.value.comparisonSessionIds.isEmpty())
        assertTrue(!viewModel.state.value.comparing)
        assertNull(viewModel.state.value.comparisonError)
    }

    private class FakeRepository(
        private val sessionLoads: Map<String, CompletableDeferred<List<Session>>>,
    ) : GuidedRunAnalysisRepository {
        val analyzedSessionIds = mutableListOf<String>()

        override suspend fun listWorkspaceSessions(workspace: WorkspaceConfig): List<Session> =
            sessionLoads.getValue(workspace.id).await()

        override suspend fun analyze(workspace: WorkspaceConfig, sessionId: String): GuidedRunAnalysisReport {
            analyzedSessionIds += sessionId
            return report(session(sessionId, workspace.robotId))
        }

        override suspend fun exportMarkdown(report: GuidedRunAnalysisReport, destination: File) {
            destination.writeText(report.session.sessionId)
        }
    }

    private class FakeComparisonRepository : RunComparisonRepository {
        val requests = mutableListOf<RunComparisonRequest>()

        override suspend fun compare(workspace: WorkspaceConfig, request: RunComparisonRequest): RunComparisonReport {
            requests += request
            val selected = listOf(request.primarySessionId) + request.comparisonSessionIds
            val options = listOf(
                RunAlignmentOption("run-start", RunAlignmentKind.RUN_START, "Run start", "First sample"),
                RunAlignmentOption("autonomous-start", RunAlignmentKind.AUTONOMOUS_START, "Autonomous start", "Auto marker"),
            )
            return RunComparisonReport(
                sessions = selected.map { session(it, workspace.robotId) },
                primarySessionId = request.primarySessionId,
                selectedAlignment = options.first { it.id == request.alignmentId },
                availableAlignments = options,
                anchors = selected.map { RunAlignmentAnchor(it, 1_000L, request.alignmentId) },
                trajectories = emptyList(),
                metrics = emptyList(),
                faults = emptyList(),
                findings = emptyList(),
                limitations = emptyList(),
            )
        }

        override suspend fun exportMarkdown(report: RunComparisonReport, destination: File) {
            destination.writeText(report.primarySessionId)
        }
    }

    companion object {
        private fun workspace(id: String, robotId: String) = WorkspaceConfig(
            id = id,
            teamId = "23247",
            seasonId = "decode",
            robotId = robotId,
            projectPath = "C:/projects/$id",
            league = League.FTC,
        )

        private fun session(id: String, robotId: String) = Session(
            sessionId = id,
            teamId = "23247",
            seasonId = "decode",
            robotId = robotId,
            createdAt = 1_000L,
        )

        private fun report(session: Session) = GuidedRunAnalysisReport(
            session = session,
            source = RunSourceEvidence(
                kind = RunEvidenceSourceKind.LOCAL_SESSION_WITHOUT_REPORT,
                explanation = "Test source",
            ),
            evidenceContext = GuidedRunEvidenceContext(
                freshnessStatus = "Historical test recording",
                startTimestampMs = 0L,
                endTimestampMs = 1_000L,
                confidence = GuidedRunConfidence.LIMITED,
                confidenceExplanation = "Test coverage",
            ),
            summary = null,
            metrics = emptyList(),
            alerts = emptyList(),
            findings = emptyList(),
            comparison = null,
            regressions = emptyList(),
            driverReview = null,
            missingSignals = emptyList(),
            limitations = emptyList(),
            nextActions = emptyList(),
        )
    }
}
