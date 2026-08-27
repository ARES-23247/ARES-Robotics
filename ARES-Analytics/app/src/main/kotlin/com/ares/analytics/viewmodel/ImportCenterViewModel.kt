package com.ares.analytics.viewmodel

import com.ares.analytics.service.ImportArchiveEntry
import com.ares.analytics.service.ImportArchiveService
import com.ares.analytics.service.ImportArchiveSnapshot
import com.ares.analytics.service.ManualLogImportOutcome
import com.ares.analytics.service.ManualLogImportService
import com.ares.analytics.shared.WorkspaceConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import java.io.File

data class ImportCenterState(
    val snapshot: ImportArchiveSnapshot = ImportArchiveSnapshot(),
    val isLoading: Boolean = true,
    val retryingId: String? = null,
    val isImporting: Boolean = false,
    val importPhase: String? = null,
    val lastImport: ManualLogImportOutcome? = null,
    val message: String? = null,
    val error: String? = null
)

sealed class ImportCenterIntent {
    data object Refresh : ImportCenterIntent()
    data class Retry(val entry: ImportArchiveEntry) : ImportCenterIntent()
    data class ImportFiles(val files: List<File>) : ImportCenterIntent()
    data object CancelImport : ImportCenterIntent()
    data object ClearNotice : ImportCenterIntent()
}

class ImportCenterViewModel(
    private val archiveService: ImportArchiveService,
    private val manualLogImportService: ManualLogImportService,
    private val workspace: WorkspaceConfig,
    private val scope: CoroutineScope,
    private val onImportCompleted: () -> Unit = {},
) {
    private val _state = MutableStateFlow(ImportCenterState())
    val state: StateFlow<ImportCenterState> = _state.asStateFlow()
    private var importJob: Job? = null

    init {
        refresh()
    }

    fun onIntent(intent: ImportCenterIntent) {
        when (intent) {
            ImportCenterIntent.Refresh -> refresh()
            ImportCenterIntent.ClearNotice -> _state.update { it.copy(message = null, error = null) }
            is ImportCenterIntent.Retry -> retry(intent.entry)
            is ImportCenterIntent.ImportFiles -> importFiles(intent.files)
            ImportCenterIntent.CancelImport -> cancelImport()
        }
    }

    private fun refresh() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val snapshot = withContext(Dispatchers.IO) { archiveService.load(workspace.projectPath) }
                _state.update { it.copy(snapshot = snapshot, isLoading = false) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                _state.update {
                    it.copy(isLoading = false, error = failure.message ?: "Failed to read import reports")
                }
            }
        }
    }

    private fun retry(entry: ImportArchiveEntry) {
        if (_state.value.retryingId != null || _state.value.isImporting) return
        scope.launch {
            _state.update { it.copy(retryingId = entry.id, message = null, error = null) }
            try {
                val requeued = withContext(Dispatchers.IO) { archiveService.retry(workspace.projectPath, entry) }
                val snapshot = withContext(Dispatchers.IO) { archiveService.load(workspace.projectPath) }
                _state.update {
                    it.copy(
                        snapshot = snapshot,
                        retryingId = null,
                        message = "Requeued ${entry.report?.sourceName ?: requeued.name}; auto-import will process it shortly"
                    )
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                _state.update {
                    it.copy(retryingId = null, error = failure.message ?: "Failed to retry quarantined log")
                }
            }
        }
    }

    private fun importFiles(files: List<File>) {
        if (files.isEmpty() || importJob?.isActive == true || _state.value.retryingId != null) return
        importJob = scope.launch {
            _state.update {
                it.copy(
                    isImporting = true,
                    importPhase = "Reading ${files.size} selected file${if (files.size == 1) "" else "s"} and verifying source evidence…",
                    lastImport = null,
                    message = null,
                    error = null,
                )
            }
            try {
                _state.update { it.copy(importPhase = "Decoding bounded batches into the local workspace database…") }
                val outcome = manualLogImportService.importFiles(files, workspace)
                val snapshot = withContext(Dispatchers.IO) { archiveService.load(workspace.projectPath) }
                _state.update {
                    it.copy(
                        snapshot = snapshot,
                        isLoading = false,
                        isImporting = false,
                        importPhase = null,
                        lastImport = outcome,
                        message = if (outcome.wasAlreadyImported) {
                            "These exact files were already imported. ARES reused the existing run instead of creating a duplicate."
                        } else {
                            "Import complete. The original files were not changed."
                        },
                    )
                }
                onImportCompleted()
            } catch (cancelled: CancellationException) {
                _state.update {
                    it.copy(
                        isImporting = false,
                        importPhase = null,
                        message = "Import cancelled. No partial run is visible in Run History.",
                    )
                }
            } catch (failure: Throwable) {
                val snapshot = runCatching { withContext(Dispatchers.IO) { archiveService.load(workspace.projectPath) } }
                    .getOrDefault(_state.value.snapshot)
                _state.update {
                    it.copy(
                        snapshot = snapshot,
                        isLoading = false,
                        isImporting = false,
                        importPhase = null,
                        error = failure.actionableImportMessage(),
                    )
                }
            } finally {
                importJob = null
            }
        }
    }

    private fun cancelImport() {
        val active = importJob ?: return
        if (!active.isActive) return
        _state.update { it.copy(importPhase = "Stopping safely and removing partial database rows…") }
        active.cancel()
    }

    fun dispose() {
        importJob?.cancel()
        importJob = null
    }
}

private fun Throwable.actionableImportMessage(): String {
    val detail = message.orEmpty()
    return when {
        this is OutOfMemoryError || detail.contains("out of memory", ignoreCase = true) ||
            detail.contains("could not allocate", ignoreCase = true) ->
            "This log exceeded the current local database memory budget. ARES kept the original file unchanged and placed its archived copy in Quarantine. Close other memory-heavy programs, then use Retry; if it repeats, import a shorter recording."
        detail.contains("timestamp", ignoreCase = true) ->
            "ARES could not identify a supported timestamp with an explicit unit. Export the log with timestampMs, timestampUs, or a supported native robot-log format, then retry."
        detail.contains("unsupported", ignoreCase = true) ->
            "That file type is not supported here. Choose CSV, CSV.GZ, JSONL, Parquet, WPILOG/WPILOGXZ, DSLOG, RLOG, REVLOG, Hoot, or Road Runner .log files."
        detail.isNotBlank() -> "Import failed: $detail. The original file was not changed; inspect Quarantine for preserved evidence and retry guidance."
        else -> "Import failed. The original file was not changed; inspect Quarantine for preserved evidence and retry guidance."
    }
}
