package com.ares.analytics.viewmodel.project

import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.util.ProjectLayout
import com.ares.analytics.service.project.persistence.ProjectMetadataRepository
import com.ares.analytics.service.project.persistence.SavedProjectMetadata
import com.ares.analytics.service.project.persistence.UnsupportedProjectMetadataSchemaException
import com.areslib.project.ARES_PROJECT_METADATA_SCHEMA_VERSION
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.project.ProjectSessionMutationResult
import com.ares.analytics.service.project.ProjectSessionRevision
import com.ares.analytics.service.project.ProjectSessionSnapshot
import com.areslib.controls.ControllerInputPlatform
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresFtcHubCommandTransport
import com.areslib.project.AresFtcRuntimeOptionsDocument
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectIdentityDocument
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.AresProjectAuthoringModel
import com.areslib.project.AresRuntimeOptionsDocument
import com.areslib.project.AresXrpRuntimeOptionsDocument
import com.areslib.project.AresXrpControllerModel
import com.areslib.project.requireFtcRuntimeOptions
import com.areslib.project.requireXrpRuntimeOptions
import com.areslib.project.validateAresProjectMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class ProjectIdentityField {
    PROJECT_ID, TEAM_ID, SEASON_ID, ROBOT_ID, DISPLAY_NAME,
    ROBOT_LENGTH, ROBOT_WIDTH, FIELD_LENGTH, FIELD_WIDTH,
    XRP_SSID, XRP_LINK_PORT, XRP_DEADMAN_TIMEOUT, XRP_BROWNOUT_THRESHOLD,
}
data class ProjectIdentityDraft(
    val projectId: String = "",
    val teamId: String = "",
    val seasonId: String = "",
    val robotId: String = "",
    val displayName: String = "",
    val robotLengthMeters: String = "",
    val robotWidthMeters: String = "",
    val fieldLengthMeters: String = "",
    val fieldWidthMeters: String = "",
    val authoringModel: AresProjectAuthoringModel = AresProjectAuthoringModel.GUI_OWNED,
    val ftcHubCommandTransport: AresFtcHubCommandTransport = AresFtcHubCommandTransport.STANDARD_SDK,
    val ftcLimelightProxyEnabled: Boolean = false,
    val xrpControllerModel: AresXrpControllerModel = AresXrpControllerModel.SPARKFUN_XRP_RP2350,
    val xrpWifiMode: String = "AP",
    val xrpSsid: String = "ARES-XRP-AUTO",
    val xrpLinkPort: String = "5811",
    val xrpDeadmanTimeoutMs: String = "200",
    val xrpBrownoutThresholdVolts: String = "4.3",
)

data class ProjectIdentityChange(
    val label: String,
    val before: String,
    val after: String,
)

data class ProjectIdentityProposal(
    val expectedContentHash: String?,
    val expectedInvalidRawContentHash: String? = null,
    val proposedContentHash: String,
    val document: AresProjectMetadataDocument,
    val changes: List<ProjectIdentityChange>,
)

data class ProjectIdentityEditorState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val projectPath: String = "",
    val workspaceLeague: League = League.FTC,
    val currentDocument: AresProjectMetadataDocument? = null,
    val currentContentHash: String? = null,
    val projectRevision: ProjectSessionRevision? = null,
    val draft: ProjectIdentityDraft = ProjectIdentityDraft(),
    val fieldErrors: Map<ProjectIdentityField, String> = emptyMap(),
    val generalErrors: List<String> = emptyList(),
    val proposal: ProjectIdentityProposal? = null,
    /** Blocks canonical writes when the selected folder is not a real robot source repository. */
    val projectSourceError: String? = null,
    val protectedError: String? = null,
    /** Hash of an invalid existing file that may be replaced only through reviewed repair. */
    val protectedContentHash: String? = null,
    /** Explicit unsupported version from the inspected bytes; null is not evidence of an old format. */
    val unsupportedSchemaVersion: Int? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
) {
    val canReview: Boolean
        get() = !loading && !saving &&
            projectSourceError == null &&
            (protectedError == null || protectedContentHash != null) &&
            fieldErrors.isEmpty() && generalErrors.isEmpty()
}

/** Owns the reviewed `.ares/project.json` workflow. It never mutates workspace configuration. */
class ProjectIdentityViewModel(
    private val scope: CoroutineScope,
    private val repository: ProjectMetadataRepository = ProjectMetadataRepository(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val projectSession: ProjectSession? = null,
) {
    private val _state = MutableStateFlow(ProjectIdentityEditorState())
    val state: StateFlow<ProjectIdentityEditorState> = _state.asStateFlow()

    private var workspace: WorkspaceConfig? = null
    private var loadJob: Job? = null
    private var generation = 0L

    fun load(config: WorkspaceConfig) {
        if (!scope.isActive) return
        workspace = config
        val selectedGeneration = ++generation
        loadJob?.cancel()
        _state.value = ProjectIdentityEditorState(
            loading = true,
            projectPath = config.projectPath,
            workspaceLeague = config.league,
        )
        loadJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val loaded = withContext(ioDispatcher) { inspect(config) }
                if (selectedGeneration != generation || workspace != config) return@launch
                _state.value = loaded
            } catch (cancelled: CancellationException) {
                if (selectedGeneration == generation && workspace == config) {
                    _state.value = _state.value.copy(
                        loading = false,
                        protectedError = "Project inspection was cancelled. Reload before reviewing an identity.",
                    )
                }
                throw cancelled
            } catch (failure: Exception) {
                if (selectedGeneration != generation || workspace != config) return@launch
                _state.value = ProjectIdentityEditorState(
                    loading = false,
                    projectPath = config.projectPath,
                    workspaceLeague = config.league,
                    protectedError = failure.message
                        ?: "The selected project could not be inspected. Check the folder and reload.",
                )
            }
        }
    }

    fun update(field: ProjectIdentityField, value: String) {
        val config = workspace ?: return
        val current = _state.value
        if (field in STABLE_IDENTITY_FIELDS && (current.currentDocument != null || current.saving)) {
            _state.value = current.copy(
                message = if (current.saving) "The reviewed identity is being saved. Stable IDs are locked for this save."
                    else "The saved project ID is stable. Create a new project instead of renaming this identity.",
                messageIsError = false,
            )
            return
        }
        val nextDraft = when (field) {
            ProjectIdentityField.PROJECT_ID -> current.draft.copy(projectId = value)
            ProjectIdentityField.TEAM_ID -> current.draft.copy(teamId = value)
            ProjectIdentityField.SEASON_ID -> current.draft.copy(seasonId = value)
            ProjectIdentityField.ROBOT_ID -> current.draft.copy(robotId = value)
            ProjectIdentityField.DISPLAY_NAME -> current.draft.copy(displayName = value)
            ProjectIdentityField.ROBOT_LENGTH -> current.draft.copy(robotLengthMeters = value)
            ProjectIdentityField.ROBOT_WIDTH -> current.draft.copy(robotWidthMeters = value)
            ProjectIdentityField.FIELD_LENGTH -> current.draft.copy(fieldLengthMeters = value)
            ProjectIdentityField.FIELD_WIDTH -> current.draft.copy(fieldWidthMeters = value)
            ProjectIdentityField.XRP_SSID -> current.draft.copy(xrpSsid = value)
            ProjectIdentityField.XRP_LINK_PORT -> current.draft.copy(xrpLinkPort = value)
            ProjectIdentityField.XRP_DEADMAN_TIMEOUT -> current.draft.copy(xrpDeadmanTimeoutMs = value)
            ProjectIdentityField.XRP_BROWNOUT_THRESHOLD -> current.draft.copy(xrpBrownoutThresholdVolts = value)
        }
        val validation = validateProjectIdentityDraft(config.league, nextDraft)
        _state.value = current.copy(
            draft = nextDraft,
            fieldErrors = validation.fieldErrors,
            generalErrors = validation.generalErrors,
            proposal = null,
            message = null,
            messageIsError = false,
        )
    }

    /** Selects the reviewed FTC hub command path; no robot process changes until generation/build. */
    fun updateFtcHubCommandTransport(value: AresFtcHubCommandTransport) = updateFtcRuntimeOptions {
        copy(ftcHubCommandTransport = value)
    }

    /** Controls whether the bounded Limelight HTTP proxy is owned by the FTC robot facade. */
    fun updateFtcLimelightProxyEnabled(enabled: Boolean) = updateFtcRuntimeOptions {
        copy(ftcLimelightProxyEnabled = enabled)
    }

    fun updateXrpWifiMode(value: String) = updateXrpRuntimeOptions {
        copy(xrpWifiMode = value)
    }

    fun updateXrpControllerModel(value: AresXrpControllerModel) = updateXrpRuntimeOptions {
        copy(xrpControllerModel = value)
    }

    private fun updateFtcRuntimeOptions(transform: ProjectIdentityDraft.() -> ProjectIdentityDraft) {
        val config = workspace ?: return
        if (config.league != League.FTC) return
        val current = _state.value
        val nextDraft = current.draft.transform()
        val validation = validateProjectIdentityDraft(config.league, nextDraft)
        _state.value = current.copy(
            draft = nextDraft,
            fieldErrors = validation.fieldErrors,
            generalErrors = validation.generalErrors,
            proposal = null,
            message = null,
            messageIsError = false,
        )
    }

    private fun updateXrpRuntimeOptions(transform: ProjectIdentityDraft.() -> ProjectIdentityDraft) {
        val config = workspace ?: return
        if (config.league != League.XRP) return
        val current = _state.value
        val nextDraft = current.draft.transform()
        val validation = validateProjectIdentityDraft(config.league, nextDraft)
        _state.value = current.copy(
            draft = nextDraft,
            fieldErrors = validation.fieldErrors,
            generalErrors = validation.generalErrors,
            proposal = null,
            message = null,
            messageIsError = false,
        )
    }

    fun resetDraft() {
        val config = workspace ?: return
        val current = _state.value
        val draft = projectIdentityDraft(config, current.currentDocument)
        val validation = validateProjectIdentityDraft(config.league, draft)
        _state.value = current.copy(
            draft = draft,
            fieldErrors = validation.fieldErrors,
            generalErrors = validation.generalErrors,
            proposal = null,
            message = "Draft reset to the last reviewed project identity.",
            messageIsError = false,
        )
    }

    fun review() {
        val config = workspace ?: return
        val current = _state.value
        if (
            current.projectSourceError != null ||
            (current.protectedError != null && current.protectedContentHash == null) ||
            current.loading ||
            current.saving
        ) {
            return
        }
        val validation = validateProjectIdentityDraft(config.league, current.draft)
        val document = validation.document
        if (document == null) {
            _state.value = current.copy(
                fieldErrors = validation.fieldErrors,
                generalErrors = validation.generalErrors,
                proposal = null,
                message = "Fix every project identity error before reviewing the diff.",
                messageIsError = true,
            )
            return
        }
        val changes = projectIdentityChanges(current.currentDocument, document)
        _state.value = if (changes.isEmpty()) {
            current.copy(
                proposal = null,
                message = "The canonical project identity already matches this draft.",
                messageIsError = false,
            )
        } else {
            current.copy(
                fieldErrors = emptyMap(),
                generalErrors = emptyList(),
                proposal = ProjectIdentityProposal(
                    expectedContentHash = current.currentContentHash,
                    expectedInvalidRawContentHash = current.protectedContentHash,
                    proposedContentHash = AresProjectMetadataCodec.contentHash(document),
                    document = document,
                    changes = changes,
                ),
                message = "Review every change below. No file has been written yet.",
                messageIsError = false,
            )
        }
    }

    fun cancelReview() {
        _state.value = _state.value.copy(
            proposal = null,
            message = "Review cancelled; the draft is still editable.",
            messageIsError = false,
        )
    }

    fun applyReviewed() {
        val config = workspace ?: return
        val current = _state.value
        if (!scope.isActive || current.loading || current.saving) return
        val proposal = current.proposal ?: return
        val freshDocument = validateProjectIdentityDraft(config.league, current.draft).document
        if (freshDocument == null || AresProjectMetadataCodec.contentHash(freshDocument) != proposal.proposedContentHash) {
            _state.value = current.copy(
                proposal = null,
                message = "The draft changed after preview. Review the new diff before saving.",
                messageIsError = true,
            )
            return
        }
        _state.value = current.copy(
            saving = true,
            message = "Saving reviewed project identity…",
            messageIsError = false,
        )
        val applyGeneration = generation
        // Establish cancellation handling before dispatch, including cancellation of queued I/O.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val outcome = withContext(ioDispatcher) { persistReviewed(config, current, proposal) }
                if (applyGeneration != generation || workspace != config) return@launch
                val saved = outcome.metadata
                val latest = _state.value
                val editedWhileSaving = latest.draft != current.draft
                val draft = if (editedWhileSaving) latest.draft else projectIdentityDraft(config, saved.document)
                val validation = validateProjectIdentityDraft(config.league, draft)
                val savedMessage = when {
                    saved.repaired ->
                        "Repaired .ares/project.json after explicit review. The exact invalid file is preserved in .ares/recovery/project."
                    saved.created -> "Created .ares/project.json after explicit review."
                    else -> "Saved .ares/project.json and preserved the previous version in project history."
                }
                _state.value = ProjectIdentityEditorState(
                    loading = false,
                    projectPath = config.projectPath,
                    workspaceLeague = config.league,
                    currentDocument = saved.document,
                    currentContentHash = saved.contentHash,
                    projectRevision = outcome.revision,
                    draft = draft,
                    fieldErrors = validation.fieldErrors,
                    generalErrors = validation.generalErrors,
                    message = savedMessage + if (editedWhileSaving) " Your newer draft remains unsaved; review it before saving." else "",
                    messageIsError = false,
                )
            } catch (cancelled: CancellationException) {
                if (applyGeneration == generation && workspace == config) {
                    _state.value = _state.value.copy(
                        saving = false,
                        proposal = null,
                        protectedError = "Saving was interrupted. Reload to check the canonical file before reviewing another change.",
                        protectedContentHash = null,
                        message = null,
                        messageIsError = false,
                    )
                }
                throw cancelled
            } catch (failure: Exception) {
                if (applyGeneration != generation || workspace != config) return@launch
                _state.value = _state.value.copy(
                    saving = false,
                    proposal = null,
                    message = failure.message ?: "Project identity could not be saved. Reload and try again.",
                    messageIsError = true,
                )
            }
        }
    }

    private data class IdentitySaveOutcome(val metadata: SavedProjectMetadata, val revision: ProjectSessionRevision?)

    private fun savedIdentity(saved: SavedProjectMetadata, snapshot: ProjectSessionSnapshot?): IdentitySaveOutcome {
        if (snapshot != null) {
            check(snapshot.documents.query.metadata?.let(AresProjectMetadataCodec::contentHash) == saved.contentHash) {
                "The reviewed identity was saved, but it changed again while refreshing the project. Reload before reviewing another change."
            }
        }
        return IdentitySaveOutcome(saved, snapshot?.revision)
    }

    private fun persistReviewed(
        config: WorkspaceConfig,
        current: ProjectIdentityEditorState,
        proposal: ProjectIdentityProposal,
    ): IdentitySaveOutcome {
        ProjectLayout.validationError(config.projectPath, config.league)?.let { sourceError ->
            error("The selected folder stopped being a valid robot project: $sourceError")
        }
        val invalidHash = proposal.expectedInvalidRawContentHash
        val result = when {
            invalidHash != null -> projectSession?.repairProjectIdentity(
                config.projectPath, config.league.targetPlatform(), invalidHash, proposal.document,
            )
            current.projectRevision != null -> projectSession?.saveProjectIdentity(current.projectRevision, proposal.document)
            else -> null
        }
        when (result) {
            is ProjectSessionMutationResult.Applied -> return savedIdentity(result.value, result.snapshot)
            is ProjectSessionMutationResult.Stale -> error("The project changed after review. Reload before saving.")
            is ProjectSessionMutationResult.Conflict -> error(result.message)
            is ProjectSessionMutationResult.Failed -> error(result.message)
            null -> Unit
        }
        val saved = if (invalidHash != null) {
            repository.repairReviewed(config.projectPath, invalidHash, proposal.document)
        } else {
            repository.saveReviewed(config.projectPath, proposal.expectedContentHash, proposal.document)
        }
        val snapshot = projectSession?.let { session ->
            try {
                session.snapshot(config.projectPath, config.league.targetPlatform(), forceReload = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw IllegalStateException("The identity was saved, but the project could not be refreshed. Reload it: ${failure.message}", failure)
            }
        }
        return savedIdentity(saved, snapshot)
    }

    private fun inspect(config: WorkspaceConfig): ProjectIdentityEditorState {
        val inspected = repository.inspect(config.projectPath)
        val currentResult = inspected.result
        val initiallyLoaded = currentResult.getOrNull()
        val sessionSnapshot = initiallyLoaded?.let {
            projectSession?.snapshot(config.projectPath, config.league.targetPlatform(), forceReload = true)
        }
        val current = if (sessionSnapshot != null) {
            checkNotNull(sessionSnapshot.documents.query.metadata) {
                "The canonical identity changed while the project was loading. Reload before reviewing it."
            }
        } else {
            initiallyLoaded
        }
        val corruptError = currentResult.exceptionOrNull()?.takeIf { inspected.rawContentHash != null }
        val corruptHash = corruptError?.let { inspected.rawContentHash }
        val unsupportedSchema = corruptError as? UnsupportedProjectMetadataSchemaException
        val projectSourceError = ProjectLayout.validationError(config.projectPath, config.league)
        val mismatch = current?.takeIf { it.league != config.league.toAresLeague() }
        val draft = projectIdentityDraft(config, current)
        val validation = validateProjectIdentityDraft(config.league, draft)
        return ProjectIdentityEditorState(
            loading = false,
            projectPath = config.projectPath,
            workspaceLeague = config.league,
            currentDocument = current,
            currentContentHash = current?.let(AresProjectMetadataCodec::contentHash),
            projectRevision = sessionSnapshot?.revision,
            draft = draft,
            fieldErrors = validation.fieldErrors,
            generalErrors = validation.generalErrors,
            projectSourceError = projectSourceError,
            protectedError = when {
                unsupportedSchema != null -> {
                    val format = if (unsupportedSchema.schemaVersion < ARES_PROJECT_METADATA_SCHEMA_VERSION) "retired" else "newer"
                    "The selected .ares/project.json uses a $format project format. ${unsupportedSchema.message}"
                }
                corruptError != null ->
                    "The existing .ares/project.json cannot be used: ${corruptError.message}. Its exact bytes remain unchanged. Enter the measured robot dimensions, review the repair, and ARES will preserve the original under .ares/recovery/project before replacing it."
                mismatch != null ->
                    "The canonical project is ${mismatch.league}, but this workspace is ${config.league}. Select the correct workspace league; ARES will not rewrite platform identity automatically."
                else -> null
            },
            protectedContentHash = corruptHash.takeUnless { unsupportedSchema != null },
            unsupportedSchemaVersion = unsupportedSchema?.schemaVersion,
            message = when {
                corruptError != null -> null
                projectSourceError != null ->
                    "The canonical identity is valid, but this folder is not a runnable robot project. Switch projects or create an official starter."
                current == null -> "No canonical project identity exists yet. Enter measured geometry, then review the file before creating it."
                else -> "Loaded the canonical project identity. Stable project ID and platform are protected."
            },
        )
    }

    private fun League.targetPlatform(): ControllerInputPlatform = when (this) {
        League.FTC -> ControllerInputPlatform.FTC
        League.FRC -> ControllerInputPlatform.FRC
        League.XRP -> ControllerInputPlatform.XRP
    }
}
