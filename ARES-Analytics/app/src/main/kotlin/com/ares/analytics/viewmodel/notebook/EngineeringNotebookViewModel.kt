package com.ares.analytics.viewmodel.notebook

import com.ares.analytics.domain.notebook.NotebookSectionParser
import com.ares.analytics.domain.notebook.StructuredNotebookSections
import com.ares.analytics.service.integration.EngineeringNotebookCaptureService
import com.ares.analytics.shared.models.EngineeringNotebookEntry
import com.ares.analytics.shared.models.NotebookEntryType
import com.ares.analytics.shared.models.NotebookEvidenceReference
import com.ares.analytics.shared.models.NotebookReviewState
import com.ares.analytics.shared.models.PublicationReceipt
import com.ares.analytics.shared.models.WorkspaceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

enum class NotebookEditorMode(val label: String) {
    GUIDED_FORM("Guided FIRST Rubric"),
    RAW_MARKDOWN("Raw Markdown"),
    PREVIEW("Formatted Preview"),
}

enum class NotebookReviewFilter(val label: String) {
    ALL("All"),
    DRAFT("Drafts"),
    APPROVED("Approved"),
}

data class EngineeringNotebookState(
    val entries: List<EngineeringNotebookEntry> = emptyList(),
    val selectedEntryId: String? = null,
    val isNewDraft: Boolean = false,
    val revisions: List<EngineeringNotebookEntry> = emptyList(),
    val selectedRevisionNumber: Int? = null,
    val receipts: List<PublicationReceipt> = emptyList(),
    val editorMode: NotebookEditorMode = NotebookEditorMode.GUIDED_FORM,
    val draftType: NotebookEntryType = NotebookEntryType.ENGINEERING_NOTE,
    val structuredDraft: StructuredNotebookSections = StructuredNotebookSections(),
    val rawMarkdownDraft: String = "",
    val draftEvidence: List<NotebookEvidenceReference> = emptyList(),
    val draftAuthorId: String = "",
    val reviewerId: String = "",
    val searchQuery: String = "",
    val typeFilter: NotebookEntryType? = null,
    val reviewFilter: NotebookReviewFilter = NotebookReviewFilter.ALL,
    val approvedOnlyForPortfolio: Boolean = false,
    val isDirty: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
) {
    val activeRevisionEntry: EngineeringNotebookEntry?
        get() = revisions.firstOrNull { it.revision == selectedRevisionNumber } ?: revisions.lastOrNull()

    fun filteredEntries(): List<EngineeringNotebookEntry> {
        val q = searchQuery.trim().lowercase(Locale.US)
        return entries.filter { entry ->
            val matchesType = typeFilter == null || entry.entryType == typeFilter
            val matchesReview = when (reviewFilter) {
                NotebookReviewFilter.ALL -> true
                NotebookReviewFilter.DRAFT -> entry.reviewState == NotebookReviewState.DRAFT || entry.reviewState == NotebookReviewState.REVIEWED
                NotebookReviewFilter.APPROVED -> entry.reviewState == NotebookReviewState.APPROVED || entry.reviewState == NotebookReviewState.PUBLISHED
            }
            val matchesSearch = q.isEmpty() ||
                entry.entryId.lowercase(Locale.US).contains(q) ||
                entry.markdownBody.lowercase(Locale.US).contains(q) ||
                entry.humanAuthorId?.lowercase(Locale.US)?.contains(q) == true ||
                entry.evidence.any { ref ->
                    ref.referenceId.lowercase(Locale.US).contains(q) ||
                        ref.label?.lowercase(Locale.US)?.contains(q) == true
                }
            matchesType && matchesReview && matchesSearch
        }
    }

    fun portfolioCandidateEntries(): List<EngineeringNotebookEntry> =
        if (approvedOnlyForPortfolio) {
            entries.filter {
                it.reviewState == NotebookReviewState.APPROVED || it.reviewState == NotebookReviewState.PUBLISHED
            }
        } else {
            entries
        }
}

class EngineeringNotebookViewModel(
    private val workspace: WorkspaceConfig,
    private val captureService: EngineeringNotebookCaptureService,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(
        run {
            val initialSections = NotebookSectionParser.defaultTemplate(
                NotebookEntryType.ENGINEERING_NOTE,
                workspace.robotName,
            )
            EngineeringNotebookState(
                isNewDraft = true,
                structuredDraft = initialSections,
                rawMarkdownDraft = NotebookSectionParser.renderMarkdown(initialSections),
            )
        }
    )
    val state: StateFlow<EngineeringNotebookState> = _state.asStateFlow()

    fun refresh(selectEntryIdAfterLoad: String? = null) {
        scope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                captureService.listWorkspaceEntries(workspace)
            }.onSuccess { loaded ->
                val current = _state.value
                val targetId = selectEntryIdAfterLoad
                    ?: current.selectedEntryId?.takeIf { id -> loaded.any { it.entryId == id } }
                    ?: if (!current.isDirty && loaded.isNotEmpty()) loaded.first().entryId else null

                if (targetId != null) {
                    val revs = captureService.listRevisions(targetId)
                    val receipts = captureService.listReceipts(targetId)
                    val latest = revs.lastOrNull() ?: loaded.firstOrNull { it.entryId == targetId }
                    if (latest != null) {
                        _state.update {
                            it.copy(
                                entries = loaded,
                                selectedEntryId = latest.entryId,
                                isNewDraft = false,
                                revisions = revs.ifEmpty { listOf(latest) },
                                selectedRevisionNumber = latest.revision,
                                receipts = receipts,
                                draftType = latest.entryType,
                                structuredDraft = NotebookSectionParser.parseMarkdown(latest.markdownBody),
                                rawMarkdownDraft = latest.markdownBody,
                                draftEvidence = latest.evidence,
                                draftAuthorId = latest.humanAuthorId.orEmpty(),
                                reviewerId = latest.humanReviewerId ?: it.reviewerId,
                                isDirty = false,
                                isLoading = false,
                            )
                        }
                    } else {
                        _state.update { it.copy(entries = loaded, isLoading = false) }
                    }
                } else {
                    _state.update { it.copy(entries = loaded, isLoading = false) }
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Could not load workspace notebook entries: ${error.message ?: error::class.simpleName}",
                    )
                }
            }
        }
    }

    fun openAndSelectEntry(entryId: String) {
        _state.update {
            it.copy(
                searchQuery = "",
                typeFilter = null,
                reviewFilter = NotebookReviewFilter.ALL,
                isDirty = false,
            )
        }
        refresh(selectEntryIdAfterLoad = entryId)
    }

    fun selectEntry(entryId: String) {
        scope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null, statusMessage = null) }
            runCatching {
                val revs = captureService.listRevisions(entryId)
                val receipts = captureService.listReceipts(entryId)
                revs to receipts
            }.onSuccess { (revs, receipts) ->
                val latest = revs.lastOrNull() ?: _state.value.entries.firstOrNull { it.entryId == entryId }
                if (latest != null) {
                    _state.update {
                        it.copy(
                            selectedEntryId = latest.entryId,
                            isNewDraft = false,
                            revisions = revs.ifEmpty { listOf(latest) },
                            selectedRevisionNumber = latest.revision,
                            receipts = receipts,
                            draftType = latest.entryType,
                            structuredDraft = NotebookSectionParser.parseMarkdown(latest.markdownBody),
                            rawMarkdownDraft = latest.markdownBody,
                            draftEvidence = latest.evidence,
                            draftAuthorId = latest.humanAuthorId.orEmpty(),
                            reviewerId = latest.humanReviewerId ?: it.reviewerId,
                            isDirty = false,
                            isLoading = false,
                        )
                    }
                } else {
                    _state.update { it.copy(isLoading = false) }
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load entry $entryId: ${error.message}",
                    )
                }
            }
        }
    }

    fun selectRevision(revisionNumber: Int) {
        _state.update { current ->
            val target = current.revisions.firstOrNull { it.revision == revisionNumber } ?: return@update current
            current.copy(
                selectedRevisionNumber = target.revision,
                draftType = target.entryType,
                structuredDraft = NotebookSectionParser.parseMarkdown(target.markdownBody),
                rawMarkdownDraft = target.markdownBody,
                draftEvidence = target.evidence,
                draftAuthorId = target.humanAuthorId.orEmpty(),
                reviewerId = target.humanReviewerId ?: current.reviewerId,
                isDirty = false,
                statusMessage = null,
            )
        }
    }

    fun startNewEntry(entryType: NotebookEntryType = NotebookEntryType.ENGINEERING_NOTE) {
        val template = NotebookSectionParser.defaultTemplate(entryType, workspace.robotName)
        val rendered = NotebookSectionParser.renderMarkdown(template)
        _state.update {
            it.copy(
                selectedEntryId = null,
                isNewDraft = true,
                revisions = emptyList(),
                selectedRevisionNumber = null,
                receipts = emptyList(),
                draftType = entryType,
                structuredDraft = template,
                rawMarkdownDraft = rendered,
                draftEvidence = emptyList(),
                isDirty = true,
                statusMessage = "Started new ${entryType.name.replace('_', ' ').lowercase(Locale.US)} draft.",
                errorMessage = null,
            )
        }
    }

    fun setEditorMode(mode: NotebookEditorMode) {
        _state.update { current ->
            when {
                current.editorMode == mode -> current
                current.editorMode == NotebookEditorMode.GUIDED_FORM -> {
                    val syncedRaw = NotebookSectionParser.renderMarkdown(current.structuredDraft)
                    current.copy(editorMode = mode, rawMarkdownDraft = syncedRaw)
                }
                mode == NotebookEditorMode.GUIDED_FORM -> {
                    val parsed = NotebookSectionParser.parseMarkdown(current.rawMarkdownDraft)
                    current.copy(editorMode = mode, structuredDraft = parsed)
                }
                else -> current.copy(editorMode = mode)
            }
        }
    }

    fun updateDraftType(entryType: NotebookEntryType) {
        _state.update { current ->
            if (current.isNewDraft && !current.isDirty) {
                val template = NotebookSectionParser.defaultTemplate(entryType, workspace.robotName)
                current.copy(
                    draftType = entryType,
                    structuredDraft = template,
                    rawMarkdownDraft = NotebookSectionParser.renderMarkdown(template),
                )
            } else {
                current.copy(draftType = entryType, isDirty = true)
            }
        }
    }

    fun updateStructured(transform: (StructuredNotebookSections) -> StructuredNotebookSections) {
        _state.update { current ->
            val updated = transform(current.structuredDraft)
            current.copy(
                structuredDraft = updated,
                rawMarkdownDraft = NotebookSectionParser.renderMarkdown(updated),
                isDirty = true,
                statusMessage = null,
            )
        }
    }

    fun updateRawMarkdown(markdown: String) {
        _state.update { current ->
            current.copy(
                rawMarkdownDraft = markdown,
                structuredDraft = NotebookSectionParser.parseMarkdown(markdown),
                isDirty = true,
                statusMessage = null,
            )
        }
    }

    fun updateAuthorId(author: String) {
        _state.update { it.copy(draftAuthorId = author, isDirty = true) }
    }

    fun updateReviewerId(reviewer: String) {
        _state.update { it.copy(reviewerId = reviewer) }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun setTypeFilter(type: NotebookEntryType?) {
        _state.update { it.copy(typeFilter = type) }
    }

    fun setReviewFilter(filter: NotebookReviewFilter) {
        _state.update { it.copy(reviewFilter = filter) }
    }

    fun setApprovedOnlyForPortfolio(approvedOnly: Boolean) {
        _state.update { it.copy(approvedOnlyForPortfolio = approvedOnly) }
    }

    fun addEvidenceReference(kind: String, referenceId: String, label: String?) {
        val cleanRef = referenceId.trim()
        if (cleanRef.isEmpty()) return
        _state.update { current ->
            val next = (current.draftEvidence + NotebookEvidenceReference(
                kind = kind.trim().ifBlank { "artifact" },
                referenceId = cleanRef,
                label = label?.trim()?.takeIf(String::isNotBlank),
            )).distinctBy { "${it.kind}|${it.referenceId}" }
            current.copy(draftEvidence = next, isDirty = true)
        }
    }

    fun removeEvidenceReference(referenceId: String) {
        _state.update { current ->
            current.copy(
                draftEvidence = current.draftEvidence.filterNot { it.referenceId == referenceId },
                isDirty = true,
            )
        }
    }

    fun saveRevision() {
        val snapshot = _state.value
        val markdown = if (snapshot.editorMode == NotebookEditorMode.GUIDED_FORM) {
            NotebookSectionParser.renderMarkdown(snapshot.structuredDraft)
        } else {
            snapshot.rawMarkdownDraft
        }
        scope.launch {
            _state.update { it.copy(isSaving = true, errorMessage = null, statusMessage = null) }
            runCatching {
                captureService.saveEditedEntry(
                    workspace = workspace,
                    entryId = if (snapshot.isNewDraft) null else snapshot.selectedEntryId,
                    entryType = snapshot.draftType,
                    markdownBody = markdown,
                    evidence = snapshot.draftEvidence,
                    authorId = snapshot.draftAuthorId.takeIf(String::isNotBlank),
                )
            }.onSuccess { saved ->
                val allEntries = captureService.listWorkspaceEntries(workspace)
                val revs = captureService.listRevisions(saved.entryId)
                val receipts = captureService.listReceipts(saved.entryId)
                _state.update {
                    it.copy(
                        entries = allEntries,
                        selectedEntryId = saved.entryId,
                        isNewDraft = false,
                        revisions = revs.ifEmpty { listOf(saved) },
                        selectedRevisionNumber = saved.revision,
                        receipts = receipts,
                        draftType = saved.entryType,
                        structuredDraft = NotebookSectionParser.parseMarkdown(saved.markdownBody),
                        rawMarkdownDraft = saved.markdownBody,
                        draftEvidence = saved.evidence,
                        isDirty = false,
                        isSaving = false,
                        statusMessage = "Saved ${saved.entryId} · r${saved.revision} (SHA-256 ${saved.contentHash.take(12)}…)",
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Could not save notebook revision: ${error.message}",
                    )
                }
            }
        }
    }

    fun approveSelectedRevision() {
        val snapshot = _state.value
        val entryId = snapshot.selectedEntryId ?: return
        val rev = snapshot.selectedRevisionNumber ?: return
        scope.launch {
            _state.update { it.copy(isSaving = true, errorMessage = null, statusMessage = null) }
            runCatching {
                captureService.approveRevision(
                    entryId = entryId,
                    revision = rev,
                    reviewerId = snapshot.reviewerId.ifBlank { "team-mentor" },
                )
            }.onSuccess { approved ->
                val allEntries = captureService.listWorkspaceEntries(workspace)
                val revs = captureService.listRevisions(entryId)
                _state.update {
                    it.copy(
                        entries = allEntries,
                        revisions = revs,
                        selectedRevisionNumber = approved.revision,
                        reviewerId = approved.humanReviewerId.orEmpty(),
                        isSaving = false,
                        statusMessage = "Approved ${approved.entryId} r${approved.revision} (signed off by ${approved.humanReviewerId}).",
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Could not approve revision: ${error.message}",
                    )
                }
            }
        }
    }

    fun exportPortfolioMarkdown(destination: File) {
        val candidates = _state.value.portfolioCandidateEntries()
        scope.launch {
            _state.update { it.copy(isSaving = true, errorMessage = null) }
            runCatching {
                captureService.exportWorkspacePortfolioMarkdown(workspace, candidates, destination)
            }.onSuccess {
                _state.update {
                    it.copy(
                        isSaving = false,
                        statusMessage = "Exported Markdown portfolio (${candidates.size} entries) to ${destination.name}.",
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(isSaving = false, errorMessage = "Portfolio export failed: ${error.message}")
                }
            }
        }
    }

    fun exportPortfolioHtml(destination: File) {
        val candidates = _state.value.portfolioCandidateEntries()
        scope.launch {
            _state.update { it.copy(isSaving = true, errorMessage = null) }
            runCatching {
                captureService.exportWorkspacePortfolioHtml(workspace, candidates, destination)
            }.onSuccess {
                _state.update {
                    it.copy(
                        isSaving = false,
                        statusMessage = "Exported print-ready HTML portfolio (${candidates.size} entries) to ${destination.name}.",
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(isSaving = false, errorMessage = "HTML portfolio export failed: ${error.message}")
                }
            }
        }
    }

    fun buildPortfolioMarkdownForClipboard(onReady: (String) -> Unit) {
        val candidates = _state.value.portfolioCandidateEntries()
        scope.launch {
            runCatching {
                captureService.buildWorkspacePortfolioMarkdown(workspace, candidates)
            }.onSuccess { markdown ->
                onReady(markdown)
                _state.update {
                    it.copy(statusMessage = "Copied ${candidates.size}-entry Engineering Portfolio Markdown to clipboard.")
                }
            }.onFailure { error ->
                _state.update { it.copy(errorMessage = "Could not copy portfolio: ${error.message}") }
            }
        }
    }
}
