package com.ares.analytics.viewmodel.runanalysis

import com.ares.analytics.service.GuidedRunAnalysisReport
import com.ares.analytics.service.GuidedRunAnalysisRepository
import com.ares.analytics.service.RUN_START_ALIGNMENT_ID
import com.ares.analytics.service.RunComparisonReport
import com.ares.analytics.service.RunComparisonRepository
import com.ares.analytics.service.RunComparisonRequest
import com.ares.analytics.shared.Session
import com.ares.analytics.shared.WorkspaceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class GuidedRunAnalysisState(
    val loadingSessions: Boolean = true,
    val analyzing: Boolean = false,
    val sessions: List<Session> = emptyList(),
    val selectedSessionId: String? = null,
    val comparisonSessionIds: List<String> = emptyList(),
    val selectedAlignmentId: String = RUN_START_ALIGNMENT_ID,
    val report: GuidedRunAnalysisReport? = null,
    val comparisonReport: RunComparisonReport? = null,
    val comparing: Boolean = false,
    val error: String? = null,
    val comparisonError: String? = null,
    val exportMessage: String? = null,
    val comparisonExportMessage: String? = null,
    /** Defaults the native report chooser to this workspace without constraining the user's destination. */
    val comparisonExportDirectory: String? = null,
)

/** Generation-safe state holder for the read-only guided run review. */
class GuidedRunAnalysisViewModel(
    private val service: GuidedRunAnalysisRepository,
    private val scope: CoroutineScope,
    private val comparisonService: RunComparisonRepository = UnsupportedRunComparisonRepository,
) {
    private val _state = MutableStateFlow(GuidedRunAnalysisState())
    val state: StateFlow<GuidedRunAnalysisState> = _state.asStateFlow()

    private var workspace: WorkspaceConfig? = null
    private var loadJob: Job? = null
    private var analysisJob: Job? = null
    private var comparisonJob: Job? = null
    private var generation = 0L
    private var comparisonGeneration = 0L

    fun load(selectedWorkspace: WorkspaceConfig) {
        workspace = selectedWorkspace
        _state.value = _state.value.copy(comparisonExportDirectory = selectedWorkspace.projectPath)
        refreshSessions()
    }

    fun refreshSessions() {
        val selectedWorkspace = workspace ?: return
        val request = ++generation
        loadJob?.cancel()
        analysisJob?.cancel()
        comparisonJob?.cancel()
        comparisonGeneration++
        _state.value = _state.value.copy(
            loadingSessions = true,
            analyzing = false,
            comparing = false,
            error = null,
            comparisonError = null,
            exportMessage = null,
            comparisonExportMessage = null,
        )
        loadJob = scope.launch {
            runCatching { service.listWorkspaceSessions(selectedWorkspace) }
                .onSuccess { sessions ->
                    if (request != generation || workspace != selectedWorkspace) return@onSuccess
                    val selectedId = _state.value.selectedSessionId?.takeIf { id -> sessions.any { it.sessionId == id } }
                        ?: sessions.firstOrNull()?.sessionId
                    val comparisons = _state.value.comparisonSessionIds
                        .filter { candidate -> candidate != selectedId && sessions.any { it.sessionId == candidate } }
                        .ifEmpty { sessions.firstOrNull { it.sessionId != selectedId }?.let { listOf(it.sessionId) }.orEmpty() }
                    _state.value = _state.value.copy(
                        loadingSessions = false,
                        sessions = sessions,
                        selectedSessionId = selectedId,
                        comparisonSessionIds = comparisons,
                        report = null,
                        comparisonReport = null,
                        error = null,
                        comparisonError = null,
                    )
                    selectedId?.let {
                        analyze(it, selectedWorkspace, request)
                        compare(selectedWorkspace)
                    }
                }
                .onFailure { failure ->
                    if (request != generation || workspace != selectedWorkspace) return@onFailure
                    _state.value = _state.value.copy(
                        loadingSessions = false,
                        analyzing = false,
                        comparing = false,
                        sessions = emptyList(),
                        report = null,
                        comparisonReport = null,
                        error = failure.actionableMessage("ARES could not list runs for this workspace"),
                    )
                }
        }
    }

    fun selectSession(sessionId: String) {
        val selectedWorkspace = workspace ?: return
        if (_state.value.sessions.none { it.sessionId == sessionId }) {
            _state.value = _state.value.copy(error = "That run is not part of the selected workspace. Refresh the run list.")
            return
        }
        val request = ++generation
        val comparisons = _state.value.comparisonSessionIds.filterNot { it == sessionId }
            .ifEmpty { _state.value.sessions.firstOrNull { it.sessionId != sessionId }?.let { listOf(it.sessionId) }.orEmpty() }
        _state.value = _state.value.copy(
            selectedSessionId = sessionId,
            comparisonSessionIds = comparisons,
            selectedAlignmentId = RUN_START_ALIGNMENT_ID,
            report = null,
            comparisonReport = null,
            error = null,
            comparisonError = null,
            exportMessage = null,
            comparisonExportMessage = null,
        )
        analyze(sessionId, selectedWorkspace, request)
        compare(selectedWorkspace)
    }

    fun toggleComparisonSession(sessionId: String) {
        val selectedWorkspace = workspace ?: return
        val current = _state.value
        if (sessionId == current.selectedSessionId || current.sessions.none { it.sessionId == sessionId }) return
        val updated = if (sessionId in current.comparisonSessionIds) {
            current.comparisonSessionIds - sessionId
        } else {
            (current.comparisonSessionIds + sessionId).distinct().take(5)
        }
        _state.value = current.copy(
            comparisonSessionIds = updated,
            selectedAlignmentId = RUN_START_ALIGNMENT_ID,
            comparisonReport = null,
            comparisonError = null,
            exportMessage = null,
            comparisonExportMessage = null,
        )
        compare(selectedWorkspace)
    }

    fun selectAlignment(alignmentId: String) {
        val selectedWorkspace = workspace ?: return
        if (_state.value.comparisonReport?.availableAlignments?.none { it.id == alignmentId } != false) return
        _state.value = _state.value.copy(selectedAlignmentId = alignmentId, comparisonError = null)
        compare(selectedWorkspace)
    }

    fun refreshAnalysis() {
        val selectedWorkspace = workspace ?: return
        val sessionId = _state.value.selectedSessionId ?: return
        val request = ++generation
        analyze(sessionId, selectedWorkspace, request)
        compare(selectedWorkspace)
    }

    fun export(destination: File) {
        val report = _state.value.report ?: return
        _state.value = _state.value.copy(exportMessage = "Saving evidence report…")
        scope.launch {
            runCatching { service.exportMarkdown(report, destination) }
                .onSuccess {
                    _state.value = _state.value.copy(exportMessage = "Saved ${destination.name}")
                }
                .onFailure { failure ->
                    _state.value = _state.value.copy(
                        exportMessage = failure.actionableMessage("The evidence report was not saved"),
                    )
                }
        }
    }

    fun exportComparison(destination: File) {
        val report = _state.value.comparisonReport ?: return
        _state.value = _state.value.copy(comparisonExportMessage = "Saving mentor/student comparison…")
        scope.launch {
            runCatching { comparisonService.exportMarkdown(report, destination) }
                .onSuccess { _state.value = _state.value.copy(comparisonExportMessage = "Saved ${destination.name}") }
                .onFailure { failure ->
                    _state.value = _state.value.copy(
                        comparisonExportMessage = failure.actionableMessage("The comparison report was not saved"),
                    )
                }
        }
    }

    fun clearExportMessage() {
        _state.value = _state.value.copy(exportMessage = null)
    }

    private fun analyze(sessionId: String, selectedWorkspace: WorkspaceConfig, request: Long) {
        analysisJob?.cancel()
        _state.value = _state.value.copy(analyzing = true, report = null, error = null)
        analysisJob = scope.launch {
            runCatching { service.analyze(selectedWorkspace, sessionId) }
                .onSuccess { report ->
                    if (request != generation || workspace != selectedWorkspace || _state.value.selectedSessionId != sessionId) return@onSuccess
                    _state.value = _state.value.copy(analyzing = false, report = report, error = null)
                }
                .onFailure { failure ->
                    if (request != generation || workspace != selectedWorkspace || _state.value.selectedSessionId != sessionId) return@onFailure
                    _state.value = _state.value.copy(
                        analyzing = false,
                        report = null,
                        error = failure.actionableMessage("ARES could not build the guided review"),
                    )
                }
        }
    }

    private fun compare(selectedWorkspace: WorkspaceConfig) {
        comparisonJob?.cancel()
        val request = ++comparisonGeneration
        val current = _state.value
        val primary = current.selectedSessionId
        if (primary == null || current.comparisonSessionIds.isEmpty()) {
            _state.value = current.copy(comparing = false, comparisonReport = null, comparisonError = null)
            return
        }
        val alignmentId = current.selectedAlignmentId
        val comparisonIds = current.comparisonSessionIds
        _state.value = current.copy(comparing = true, comparisonReport = null, comparisonError = null)
        comparisonJob = scope.launch {
            runCatching {
                comparisonService.compare(
                    selectedWorkspace,
                    RunComparisonRequest(primary, comparisonIds, alignmentId),
                )
            }.onSuccess { report ->
                if (request != comparisonGeneration || workspace != selectedWorkspace ||
                    _state.value.selectedSessionId != primary || _state.value.comparisonSessionIds != comparisonIds ||
                    _state.value.selectedAlignmentId != alignmentId
                ) return@onSuccess
                _state.value = _state.value.copy(comparing = false, comparisonReport = report, comparisonError = null)
            }.onFailure { failure ->
                if (failure is CancellationException || request != comparisonGeneration || workspace != selectedWorkspace ||
                    _state.value.selectedSessionId != primary || _state.value.comparisonSessionIds != comparisonIds ||
                    _state.value.selectedAlignmentId != alignmentId
                ) return@onFailure
                _state.value = _state.value.copy(
                    comparing = false,
                    comparisonReport = null,
                    comparisonError = failure.actionableMessage("ARES could not compare the selected runs"),
                )
            }
        }
    }
}

private object UnsupportedRunComparisonRepository : RunComparisonRepository {
    override suspend fun compare(
        workspace: WorkspaceConfig,
        request: RunComparisonRequest,
    ): RunComparisonReport = error("Run comparison service is not configured")

    override suspend fun exportMarkdown(report: RunComparisonReport, destination: File) {
        error("Run comparison service is not configured")
    }
}

private fun Throwable.actionableMessage(prefix: String): String {
    val detail = message?.replace(Regex("[\\r\\n]+"), " ")?.trim().orEmpty()
    return if (detail.isBlank()) "$prefix. Refresh the run or choose another import." else "$prefix: $detail. Refresh the run or choose another import."
}
