package com.ares.analytics.viewmodel.tuning

import com.ares.analytics.service.GuidedRunAnalysisRepository
import com.ares.analytics.service.RunComparisonReport
import com.ares.analytics.service.shortRunLabel
import com.ares.analytics.service.tuning.BoundedTuningProposal
import com.ares.analytics.service.tuning.ExperimentDecision
import com.ares.analytics.service.tuning.ExperimentDirection
import com.ares.analytics.service.tuning.ExperimentMetricOption
import com.ares.analytics.service.tuning.ExperimentPhase
import com.ares.analytics.service.tuning.ExperimentValue
import com.ares.analytics.service.tuning.GuidedTuningExperiment
import com.ares.analytics.service.tuning.GuidedTuningExperimentEvaluator
import com.ares.analytics.service.tuning.GuidedTuningExperimentPlan
import com.ares.analytics.service.tuning.GuidedTuningExperimentRepository
import com.ares.analytics.service.tuning.GuidedTuningExperimentSeed
import com.ares.analytics.service.tuning.GuidedTuningProposalPolicy
import com.ares.analytics.service.tuning.PeerReviewState
import com.ares.analytics.service.tuning.ResolvedTuningValue
import com.ares.analytics.service.tuning.TuningValueProvenance
import com.ares.analytics.service.tuning.candidateRuns
import com.ares.analytics.service.tuning.canAcceptSimulationResult
import com.ares.analytics.service.tuning.numericValue
import com.ares.analytics.service.tuning.restoredSeed
import com.ares.analytics.shared.Session
import com.ares.analytics.shared.WorkspaceConfig
import com.ares.analytics.viewmodel.TuningState
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val DEFAULT_HELD_CONSTANTS = "Same routine\nSame starting pose\nSame simulated battery and load"
private const val DEFAULT_SUCCESS_THRESHOLD = "5"
private const val DEFAULT_SAFETY_NOTES =
    "Local Sim only. Stop and roll back if the simulator reports a fault or required feedback is missing."

data class GuidedTuningExperimentState(
    val seed: GuidedTuningExperimentSeed? = null,
    val parameterUid: String? = null,
    val direction: ExperimentDirection = ExperimentDirection.INCREASE,
    val metricId: String? = null,
    val question: String = "",
    val hypothesis: String = "",
    val heldConstantsText: String = DEFAULT_HELD_CONSTANTS,
    val successThresholdText: String = DEFAULT_SUCCESS_THRESHOLD,
    val safetyNotes: String = DEFAULT_SAFETY_NOTES,
    val requestPeerReview: Boolean = false,
    val proposalPreview: BoundedTuningProposal? = null,
    val experiment: GuidedTuningExperiment? = null,
    val experiments: List<GuidedTuningExperiment> = emptyList(),
    val candidateRuns: List<Session> = emptyList(),
    val selectedCandidateSessionId: String? = null,
    val comparisonReport: RunComparisonReport? = null,
    val isWorking: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
) {
    val selectedMetric: ExperimentMetricOption?
        get() = seed?.availableMetrics?.firstOrNull { it.id == metricId }
}

sealed interface GuidedTuningExperimentIntent {
    data class Begin(val seed: GuidedTuningExperimentSeed) : GuidedTuningExperimentIntent
    data class SelectParameter(val uid: String) : GuidedTuningExperimentIntent
    data class SetDirection(val direction: ExperimentDirection) : GuidedTuningExperimentIntent
    data class SelectMetric(val metricId: String) : GuidedTuningExperimentIntent
    data class SetQuestion(val value: String) : GuidedTuningExperimentIntent
    data class SetHypothesis(val value: String) : GuidedTuningExperimentIntent
    data class SetHeldConstants(val value: String) : GuidedTuningExperimentIntent
    data class SetSuccessThreshold(val value: String) : GuidedTuningExperimentIntent
    data class SetSafetyNotes(val value: String) : GuidedTuningExperimentIntent
    data class SetPeerReviewRequested(val value: Boolean) : GuidedTuningExperimentIntent
    data object CreateAndStage : GuidedTuningExperimentIntent
    data object RefreshTuningContext : GuidedTuningExperimentIntent
    data object RefreshCandidateRuns : GuidedTuningExperimentIntent
    data class SelectCandidateRun(val sessionId: String) : GuidedTuningExperimentIntent
    data object EvaluateCandidate : GuidedTuningExperimentIntent
    data class Decide(val decision: ExperimentDecision, val note: String, val nextTest: String) : GuidedTuningExperimentIntent
    data object StartRevision : GuidedTuningExperimentIntent
    data class LoadExperiment(val uid: String) : GuidedTuningExperimentIntent
    data class ExportReport(val destination: File) : GuidedTuningExperimentIntent
    data object ClearMessage : GuidedTuningExperimentIntent
}

data class GuidedExperimentProposal(
    val key: String,
    val value: TuningValue,
    val provenance: TuningValueProvenance,
)

/**
 * Owns the persisted student experiment, not robot output. Simulation launch and runtime tuning
 * remain explicit UI actions through their existing services.
 */
class GuidedTuningExperimentViewModel(
    private val workspace: WorkspaceConfig,
    private val scope: CoroutineScope,
    private val runRepository: GuidedRunAnalysisRepository,
    private val repository: GuidedTuningExperimentRepository,
    private val evaluator: GuidedTuningExperimentEvaluator,
    private val tuningState: () -> TuningState,
    private val stageProposal: (GuidedExperimentProposal) -> Unit,
    private val removeProposal: (String) -> Unit,
) {
    private val _state = MutableStateFlow(GuidedTuningExperimentState())
    val state: StateFlow<GuidedTuningExperimentState> = _state.asStateFlow()
    private var workJob: Job? = null

    init {
        reloadExperiments()
    }

    fun onIntent(intent: GuidedTuningExperimentIntent) {
        when (intent) {
            is GuidedTuningExperimentIntent.Begin -> begin(intent.seed)
            is GuidedTuningExperimentIntent.SelectParameter -> updateParameter(intent.uid)
            is GuidedTuningExperimentIntent.SetDirection -> {
                _state.value = _state.value.copy(direction = intent.direction, errorMessage = null)
                refreshPreview()
            }
            is GuidedTuningExperimentIntent.SelectMetric -> _state.value = _state.value.copy(metricId = intent.metricId, errorMessage = null)
            is GuidedTuningExperimentIntent.SetQuestion -> _state.value = _state.value.copy(question = intent.value, errorMessage = null)
            is GuidedTuningExperimentIntent.SetHypothesis -> _state.value = _state.value.copy(hypothesis = intent.value, errorMessage = null)
            is GuidedTuningExperimentIntent.SetHeldConstants -> _state.value = _state.value.copy(heldConstantsText = intent.value, errorMessage = null)
            is GuidedTuningExperimentIntent.SetSuccessThreshold -> _state.value = _state.value.copy(successThresholdText = intent.value, errorMessage = null)
            is GuidedTuningExperimentIntent.SetSafetyNotes -> _state.value = _state.value.copy(safetyNotes = intent.value, errorMessage = null)
            is GuidedTuningExperimentIntent.SetPeerReviewRequested -> _state.value = _state.value.copy(requestPeerReview = intent.value, errorMessage = null)
            GuidedTuningExperimentIntent.CreateAndStage -> createAndStage()
            GuidedTuningExperimentIntent.RefreshTuningContext -> refreshTuningContext()
            GuidedTuningExperimentIntent.RefreshCandidateRuns -> refreshCandidateRuns()
            is GuidedTuningExperimentIntent.SelectCandidateRun -> _state.value = _state.value.copy(
                selectedCandidateSessionId = intent.sessionId,
                comparisonReport = null,
                errorMessage = null,
            )
            GuidedTuningExperimentIntent.EvaluateCandidate -> evaluateCandidate()
            is GuidedTuningExperimentIntent.Decide -> decide(intent.decision, intent.note, intent.nextTest)
            GuidedTuningExperimentIntent.StartRevision -> startRevision()
            is GuidedTuningExperimentIntent.LoadExperiment -> loadExperiment(intent.uid)
            is GuidedTuningExperimentIntent.ExportReport -> export(intent.destination)
            GuidedTuningExperimentIntent.ClearMessage -> _state.value = _state.value.copy(statusMessage = null, errorMessage = null)
        }
    }

    private fun begin(seed: GuidedTuningExperimentSeed) {
        val firstParameter = editableRows().firstOrNull()?.declaration?.uid
        _state.value = _state.value.copy(
            seed = seed,
            parameterUid = firstParameter,
            metricId = seed.availableMetrics.firstOrNull()?.id,
            question = "",
            hypothesis = "",
            heldConstantsText = DEFAULT_HELD_CONSTANTS,
            successThresholdText = DEFAULT_SUCCESS_THRESHOLD,
            safetyNotes = DEFAULT_SAFETY_NOTES,
            requestPeerReview = false,
            proposalPreview = null,
            experiment = null,
            candidateRuns = emptyList(),
            selectedCandidateSessionId = null,
            comparisonReport = null,
            statusMessage = "Choose one typed parameter and write a testable prediction. The finding is evidence, not a proven cause.",
            errorMessage = if (firstParameter == null) "This project has no editable numeric tuning declarations." else null,
        )
        refreshPreview()
    }

    private fun updateParameter(uid: String) {
        if (editableRows().none { it.declaration.uid == uid }) return
        _state.value = _state.value.copy(parameterUid = uid, errorMessage = null)
        refreshPreview()
    }

    private fun refreshTuningContext() {
        if (_state.value.seed == null || _state.value.experiment != null) return
        val editable = editableRows()
        val selected = _state.value.parameterUid?.takeIf { uid -> editable.any { it.declaration.uid == uid } }
            ?: editable.firstOrNull()?.declaration?.uid
        _state.value = _state.value.copy(
            parameterUid = selected,
            errorMessage = if (selected == null) "This project has no editable numeric tuning declarations." else null,
        )
        refreshPreview()
    }

    private fun refreshPreview() {
        val current = _state.value
        val row = editableRows().firstOrNull { it.declaration.uid == current.parameterUid }
        val preview = row?.sourceTypedValue?.let { value ->
            runCatching { GuidedTuningProposalPolicy.propose(row.declaration, value, current.direction) }
                .onFailure { failure -> _state.value = _state.value.copy(errorMessage = failure.message) }
                .getOrNull()
        }
        _state.value = _state.value.copy(proposalPreview = preview)
    }

    private fun createAndStage() {
        val current = _state.value
        val seed = current.seed ?: return fail("Select a comparison finding first.")
        val proposal = current.proposalPreview ?: return fail("Choose an editable numeric parameter.")
        val metric = current.selectedMetric ?: return fail("Choose the recorded metric this experiment should evaluate.")
        val source = tuningState()
        val profile = source.selectedProfile ?: return fail("Choose a canonical tuning profile first.")
        if (source.proposals.isNotEmpty()) {
            return fail("Finish or discard the existing proposal before starting a one-factor experiment.")
        }
        if (current.hypothesis.isBlank()) return fail("Write what you expect this one change to improve.")
        if (current.question.isBlank()) return fail("Write the question this experiment should answer.")
        val heldConstants = current.heldConstantsText.lineSequence().map(String::trim).filter(String::isNotBlank).distinct().toList()
        if (heldConstants.isEmpty()) return fail("List at least one condition that will stay the same between runs.")
        val threshold = current.successThresholdText.toDoubleOrNull()
            ?: return fail("Enter a numeric success threshold percent.")
        if (threshold !in 0.1..100.0) return fail("Choose a success threshold from 0.1% through 100%.")
        if (current.safetyNotes.isBlank()) return fail("Record the Local Sim safety boundary for this experiment.")
        workJob?.cancel()
        _state.value = current.copy(isWorking = true, errorMessage = null)
        workJob = scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.create(
                        workspace,
                        profile,
                        source.profiles,
                        source.catalog,
                        seed,
                        proposal,
                        metric,
                        GuidedTuningExperimentPlan(
                            question = current.question,
                            hypothesis = current.hypothesis,
                            heldConstants = heldConstants,
                            successThresholdPercent = threshold,
                            safetyNotes = current.safetyNotes,
                            peerReviewState = if (current.requestPeerReview) PeerReviewState.REQUESTED else PeerReviewState.NOT_REQUESTED,
                        ),
                    )
                }
            }.onSuccess { experiment ->
                val path = repository.relativePath(experiment)
                val hash = repository.sha256(workspace.projectPath, experiment)
                stageProposal(
                    GuidedExperimentProposal(
                        experiment.change.key,
                        experiment.change.proposed.toTuningValue(),
                        TuningValueProvenance(
                            source = "Guided simulation experiment",
                            note = experiment.hypothesis,
                            evidencePath = path,
                            evidenceSha256 = hash,
                        ),
                    )
                )
                _state.value = _state.value.copy(
                    experiment = experiment,
                    experiments = repository.list(workspace.projectPath),
                    isWorking = false,
                    statusMessage = "Experiment snapshot saved and one bounded proposal staged. Launch Local Sim; canonical files are unchanged.",
                    errorMessage = null,
                )
                refreshCandidateRuns()
            }.onFailure { fail(it.message ?: "The experiment could not be created.") }
        }
    }

    private fun refreshCandidateRuns() {
        val experiment = _state.value.experiment ?: return
        workJob?.cancel()
        _state.value = _state.value.copy(isWorking = true, errorMessage = null)
        workJob = scope.launch {
            runCatching { runRepository.listWorkspaceSessions(workspace).let(experiment::candidateRuns) }
                .onSuccess { sessions ->
                    val selected = _state.value.selectedCandidateSessionId?.takeIf { id -> sessions.any { it.sessionId == id } }
                        ?: sessions.firstOrNull()?.sessionId
                    _state.value = _state.value.copy(
                        candidateRuns = sessions,
                        selectedCandidateSessionId = selected,
                        isWorking = false,
                        statusMessage = if (sessions.isEmpty()) {
                            "No new simulation run is recorded yet. Run the same test, stop recording, then refresh."
                        } else {
                            "Found ${sessions.size} candidate run(s) recorded after the experiment snapshot."
                        },
                    )
                }
                .onFailure { fail(it.message ?: "Candidate runs could not be loaded.") }
        }
    }

    private fun evaluateCandidate() {
        val experiment = _state.value.experiment ?: return fail("Create the experiment snapshot first.")
        val candidateId = _state.value.selectedCandidateSessionId ?: return fail("Record and select a new simulation run first.")
        workJob?.cancel()
        _state.value = _state.value.copy(isWorking = true, errorMessage = null)
        workJob = scope.launch {
            runCatching { evaluator.evaluate(workspace, experiment, candidateId) }
                .onSuccess { (evaluated, report) ->
                    val saved = withContext(Dispatchers.IO) { repository.update(workspace.projectPath, evaluated) }
                    val path = repository.relativePath(saved)
                    val hash = repository.sha256(workspace.projectPath, saved)
                    stageProposal(
                        GuidedExperimentProposal(
                            saved.change.key,
                            saved.change.proposed.toTuningValue(),
                            TuningValueProvenance("Guided simulation experiment", saved.evaluation?.summary.orEmpty(), path, hash),
                        )
                    )
                    _state.value = _state.value.copy(
                        experiment = saved,
                        experiments = repository.list(workspace.projectPath),
                        comparisonReport = report,
                        isWorking = false,
                        statusMessage = saved.evaluation?.summary,
                        errorMessage = null,
                    )
                }
                .onFailure { fail(it.message ?: "The baseline and candidate runs could not be compared.") }
        }
    }

    private fun decide(decision: ExperimentDecision, note: String, nextTest: String) {
        val experiment = _state.value.experiment ?: return fail("Create an experiment first.")
        if (experiment.evaluation == null && decision != ExperimentDecision.ROLL_BACK) {
            return fail("Compare a candidate simulation run before accepting or revising the experiment.")
        }
        if (decision == ExperimentDecision.ACCEPT && !experiment.canAcceptSimulationResult()) {
            return fail("Accept is available only when recorded simulator evidence shows the intended metric improved. Revise or roll back this experiment instead.")
        }
        if (note.isBlank()) return fail("Record why you chose this decision.")
        if (nextTest.isBlank()) return fail("Record the next safe test or review step.")
        val phase = when (decision) {
            ExperimentDecision.ACCEPT -> ExperimentPhase.ACCEPTED
            ExperimentDecision.REVISE -> ExperimentPhase.REVISION_REQUESTED
            ExperimentDecision.REJECT -> ExperimentPhase.REJECTED
            ExperimentDecision.ROLL_BACK -> ExperimentPhase.ROLLED_BACK
            ExperimentDecision.UNDECIDED -> ExperimentPhase.EVALUATED
        }
        workJob?.cancel()
        _state.value = _state.value.copy(isWorking = true, errorMessage = null)
        workJob = scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val saved = repository.update(
                        workspace.projectPath,
                        experiment.copy(decision = decision, decisionNote = note.trim(), nextTest = nextTest.trim(), phase = phase),
                    )
                    saved to repository.list(workspace.projectPath)
                }
            }.onSuccess { (saved, experiments) ->
                if (decision == ExperimentDecision.REJECT || decision == ExperimentDecision.ROLL_BACK) removeProposal(saved.change.key)
                _state.value = _state.value.copy(
                    experiment = saved,
                    experiments = experiments,
                    isWorking = false,
                    statusMessage = when (decision) {
                        ExperimentDecision.ACCEPT -> "Accepted as simulation evidence. Canonical promotion still requires its separate structured review."
                        ExperimentDecision.REVISE -> "Marked for revision. Start a fresh one-change experiment; the completed evidence remains saved."
                        ExperimentDecision.REJECT -> "Rejected the candidate and removed the local proposal. Canonical files were unchanged."
                        ExperimentDecision.ROLL_BACK -> "Rolled back the local proposal and stopped the managed simulator. Canonical files were unchanged."
                        ExperimentDecision.UNDECIDED -> "Decision cleared."
                    },
                    errorMessage = null,
                )
            }.onFailure { fail(it.message ?: "The experiment decision could not be saved.") }
        }
    }

    private fun startRevision() {
        val experiment = _state.value.experiment ?: return fail("Choose an experiment first.")
        if (experiment.phase != ExperimentPhase.REVISION_REQUESTED) {
            return fail("Record a Revise decision before starting a new candidate.")
        }
        removeProposal(experiment.change.key)
        _state.value = _state.value.copy(
            experiment = null,
            candidateRuns = emptyList(),
            selectedCandidateSessionId = null,
            comparisonReport = null,
            hypothesis = "",
            question = "",
            heldConstantsText = DEFAULT_HELD_CONSTANTS,
            successThresholdText = DEFAULT_SUCCESS_THRESHOLD,
            safetyNotes = DEFAULT_SAFETY_NOTES,
            requestPeerReview = false,
            statusMessage = "The previous experiment remains saved. Choose one bounded value and write a new prediction.",
            errorMessage = null,
        )
        refreshPreview()
    }

    private fun loadExperiment(uid: String) {
        runCatching { repository.load(workspace.projectPath, uid) }
            .onSuccess { experiment ->
                val proposalRow = editableRows().firstOrNull {
                    it.declaration.uid == experiment.change.parameterUid
                }
                val proposal = proposalRow?.let { row ->
                    BoundedTuningProposal(
                        declaration = row.declaration,
                        before = experiment.change.before.toTuningValue(),
                        proposed = experiment.change.proposed.toTuningValue(),
                        stepLimit = experiment.change.boundedStepLimit,
                    )
                }
                _state.value = _state.value.copy(
                    seed = experiment.restoredSeed(),
                    parameterUid = experiment.change.parameterUid,
                    direction = if (
                        (experiment.change.proposed.toTuningValue().numericValue() ?: 0.0) >=
                        (experiment.change.before.toTuningValue().numericValue() ?: 0.0)
                    ) ExperimentDirection.INCREASE else ExperimentDirection.DECREASE,
                    metricId = experiment.metric.metricId,
                    hypothesis = experiment.hypothesis,
                    question = experiment.question,
                    heldConstantsText = experiment.heldConstants.joinToString("\n").ifBlank { DEFAULT_HELD_CONSTANTS },
                    successThresholdText = experiment.successThresholdPercent
                        .takeIf { it > 0.0 }
                        ?.toString()
                        ?: DEFAULT_SUCCESS_THRESHOLD,
                    safetyNotes = experiment.safetyNotes.ifBlank { DEFAULT_SAFETY_NOTES },
                    requestPeerReview = experiment.peerReviewState == PeerReviewState.REQUESTED,
                    proposalPreview = proposal,
                    experiment = experiment,
                    candidateRuns = emptyList(),
                    selectedCandidateSessionId = experiment.candidateSessionId,
                    comparisonReport = null,
                    statusMessage = "Loaded ${experiment.title}.",
                    errorMessage = if (proposal == null) {
                        "The saved experiment's parameter is no longer declared by this project. Evidence remains readable, but the candidate cannot be applied."
                    } else null,
                )
                refreshCandidateRuns()
            }
            .onFailure { fail(it.message ?: "The experiment could not be loaded.") }
    }

    private fun export(destination: File) {
        val experiment = _state.value.experiment ?: return fail("Choose an experiment to export.")
        workJob?.cancel()
        _state.value = _state.value.copy(isWorking = true, errorMessage = null)
        workJob = scope.launch {
            runCatching { withContext(Dispatchers.IO) { exportExperimentReport(experiment, destination) } }
                .onSuccess { _state.value = _state.value.copy(isWorking = false, statusMessage = "Saved ${destination.name}") }
                .onFailure { fail(it.message ?: "The mentor/student report could not be saved.") }
        }
    }

    private fun exportExperimentReport(experiment: GuidedTuningExperiment, destination: File) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile ?: File("."), ".${destination.name}.tmp")
        temporary.writeText(renderGuidedTuningExperimentReport(experiment))
        runCatching {
            java.nio.file.Files.move(
                temporary.toPath(),
                destination.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            java.nio.file.Files.move(temporary.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun editableRows(): List<ResolvedTuningValue> = tuningState().rows.filter { row ->
        row.sourceTypedValue != null &&
            row.declaration.type in setOf(TuningParameterType.DOUBLE, TuningParameterType.INT) &&
            row.declaration.applyPolicy != com.areslib.tuning.TuningApplyPolicy.READ_ONLY_VENDOR &&
            tuningState().consumerSupportByUid[row.declaration.uid] != false
    }

    private fun reloadExperiments() {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.list(workspace.projectPath) } }
                .onSuccess { experiments -> _state.value = _state.value.copy(experiments = experiments) }
                .onFailure { fail(it.message ?: "Saved tuning experiments could not be loaded.") }
        }
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(isWorking = false, errorMessage = message, statusMessage = null)
    }
}

internal fun renderGuidedTuningExperimentReport(experiment: GuidedTuningExperiment): String = buildString {
        appendLine("# ARES guided tuning experiment")
        appendLine()
        appendLine("Experiment: ${experiment.title}")
        appendLine("Workspace: ${experiment.teamId} / ${experiment.seasonId} / ${experiment.robotId}")
        appendLine("Configuration snapshot: ${experiment.snapshot.snapshotSha256}")
        appendLine("Canonical profile: ${experiment.snapshot.profileUid} @ ${experiment.snapshot.profileContentSha256}")
        appendLine()
        appendLine("> This report records simulation evidence. It is not proof of causation or physical-hardware validation.")
        appendLine()
        appendLine("## Evidence and uncertainty")
        appendLine("- ${experiment.finding.claimKind}: ${experiment.finding.title}")
        appendLine("- ${experiment.finding.explanation}")
        appendLine("- Replay: ${experiment.finding.sessionId} at ${experiment.finding.absoluteTimestampMs} ms")
        appendLine("- Topics: ${experiment.finding.sourceTopics.joinToString()}")
        appendLine()
        appendLine("## Controlled change")
        appendLine("- Question: ${experiment.question.ifBlank { "What effect will the one bounded change have on the intended metric?" }}")
        appendLine("- Hypothesis: ${experiment.hypothesis}")
        appendLine("- Parameter: ${experiment.change.displayName} (${experiment.change.key})")
        appendLine("- Before: ${experiment.change.before.display()} ${experiment.change.unit}")
        appendLine("- Candidate: ${experiment.change.proposed.display()} ${experiment.change.unit}")
        appendLine("- Maximum bounded step used: ${experiment.change.boundedStepLimit.reportNumber()} ${experiment.change.unit}")
        appendLine("- Apply policy: ${experiment.change.applyPolicy}")
        appendLine("- Success threshold: ${experiment.successThresholdPercent.reportNumber()}% improvement")
        appendLine("- Held constant:")
        experiment.heldConstants.ifEmpty { listOf("Not recorded in this legacy experiment") }
            .forEach { appendLine("  - $it") }
        appendLine("- Safety boundary: ${experiment.safetyNotes.ifBlank { "Local Sim only; physical validation requires a separate documented safety procedure." }}")
        appendLine("- Optional peer review: ${experiment.peerReviewState.name.replace('_', ' ')}")
        appendLine()
        appendLine("## Baseline and candidate")
        appendLine("- Baseline run: ${experiment.baselineSessionId}")
        appendLine("- Candidate run: ${experiment.candidateSessionId ?: "not recorded"}")
        appendLine("- Intended metric: ${experiment.metric.metricLabel} / ${experiment.metric.statistic} / ${experiment.metric.goal}")
        experiment.evaluation?.let { evaluation ->
            appendLine("- Baseline value: ${evaluation.baselineValue.reportNumber()} ${evaluation.unit}")
            appendLine("- Candidate value: ${evaluation.candidateValue.reportNumber()} ${evaluation.unit}")
            appendLine("- Delta: ${evaluation.absoluteDelta.reportNumber()} ${evaluation.unit} (${evaluation.percentDelta.reportNumber()}%)")
            appendLine("- Result: ${evaluation.summary}")
            appendLine("- Outcome: ${evaluation.outcome}")
            appendLine()
            appendLine("## Limitations")
            evaluation.limitations.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("## Student/mentor decision")
        appendLine("- Decision: ${experiment.decision}")
        appendLine("- Reason: ${experiment.decisionNote.ifBlank { "Not recorded" }}")
        appendLine("- Next safe test or review: ${experiment.nextTest.ifBlank { "Not recorded" }}")
        appendLine("- Rollback path: stop the managed simulator and remove the local proposal; reload the unchanged canonical profile before another run.")
        appendLine("- Canonical profile changed by this workflow: no")
}

private val guidedExperimentTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm:ss a")

fun Session.guidedExperimentLabel(): String {
    val recordedAt = guidedExperimentTimeFormatter.format(Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()))
    return "Simulation · $recordedAt · ${sessionId.take(8)}"
}

private fun ExperimentValue.display(): String = when {
    doubleValue != null -> doubleValue.reportNumber()
    intValue != null -> intValue.toString()
    booleanValue != null -> booleanValue.toString()
    else -> textValue.orEmpty()
}

private fun Double?.reportNumber(): String = when {
    this == null || !isFinite() -> "unavailable"
    else -> BigDecimal.valueOf(this)
        .setScale(6, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
}
